"""
SQLite, because a hackathon backend should run from a clone with nothing to provision.

Two rules are enforced here rather than trusted to callers:
  * the first registration of a reference wins — a later call cannot re-point it at a
    different recipient or amount;
  * a signature settles at most one request — one transaction can carry several references
    while paying once, and must not mark two requests paid.
"""
from __future__ import annotations

import sqlite3
import threading
import time


class Conflict(Exception):
    pass


class Store:
    def __init__(self, path: str = ":memory:"):
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        with self._lock, self._db:
            self._db.executescript(
                """
                CREATE TABLE IF NOT EXISTS requests (
                    reference  TEXT PRIMARY KEY,
                    recipient  TEXT NOT NULL,
                    amount     TEXT NOT NULL,
                    lamports   INTEGER NOT NULL,
                    label      TEXT NOT NULL DEFAULT '',
                    created_at REAL NOT NULL,
                    status     TEXT NOT NULL DEFAULT 'open',
                    signature  TEXT UNIQUE,
                    paid_at    REAL,
                    next_check REAL NOT NULL DEFAULT 0,
                    payer      TEXT,
                    genesis    TEXT
                );
                CREATE INDEX IF NOT EXISTS due ON requests(status, next_check);
                CREATE INDEX IF NOT EXISTS by_recipient ON requests(recipient, status);
                CREATE TABLE IF NOT EXISTS rewards (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    reference  TEXT NOT NULL,
                    payer      TEXT NOT NULL,
                    counterparty TEXT NOT NULL,
                    kind       TEXT NOT NULL,
                    skr        INTEGER NOT NULL,
                    -- locked (reversible) -> settled, or reversed
                    state      TEXT NOT NULL DEFAULT 'locked',
                    reason     TEXT,
                    week       INTEGER NOT NULL,
                    created_at REAL NOT NULL,
                    unlock_at  REAL NOT NULL
                );
                CREATE INDEX IF NOT EXISTS rewards_payer ON rewards(payer, created_at);
                CREATE UNIQUE INDEX IF NOT EXISTS rewards_once ON rewards(reference, kind);
                -- Every payment that was looked at, earning or not: the refusals are how we
                -- see farming attempts and answer "why didn't I get anything?".
                CREATE TABLE IF NOT EXISTS reward_attempts (
                    reference  TEXT PRIMARY KEY,
                    payer      TEXT NOT NULL,
                    counterparty TEXT NOT NULL,
                    refused    TEXT,
                    skr        INTEGER NOT NULL DEFAULT 0,
                    at         REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS reward_config (
                    key        TEXT PRIMARY KEY,
                    value      TEXT NOT NULL,
                    updated_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS devices (
                    recipient  TEXT NOT NULL,
                    token      TEXT NOT NULL,
                    updated_at REAL NOT NULL,
                    PRIMARY KEY (recipient, token)
                );
                """
            )

        # Databases created before requests could be addressed to a payer.
        with self._lock, self._db:
            cols = {r[1] for r in self._db.execute("PRAGMA table_info(requests)")}
            for col, kind in (("payer", "TEXT"), ("genesis", "TEXT"), ("withdrawn", "INTEGER"),
                              ("rejected", "INTEGER"), ("rejected_by", "TEXT"),
                              # What was asked for: "SOL" or an SPL mint. `lamports` then holds
                              # the amount in that token's smallest unit.
                              ("token", "TEXT NOT NULL DEFAULT 'SOL'"), ("decimals", "INTEGER NOT NULL DEFAULT 9"),
                              ("symbol", "TEXT NOT NULL DEFAULT 'SOL'")):
                if col not in cols:
                    self._db.execute(f"ALTER TABLE requests ADD COLUMN {col} {kind}")
            self._db.execute("CREATE INDEX IF NOT EXISTS by_payer ON requests(payer, status)")

    # -- requests ---------------------------------------------------------------------------

    def register(self, reference: str, recipient: str, amount: str, lamports: int, label: str,
                 payer: str | None = None, genesis: str | None = None,
                 token: str = "SOL", decimals: int = 9, symbol: str = "SOL") -> dict:
        now = time.time()
        with self._lock, self._db:
            row = self._db.execute("SELECT * FROM requests WHERE reference=?", (reference,)).fetchone()
            if row is None:
                self._db.execute(
                    "INSERT INTO requests(reference,recipient,amount,lamports,label,created_at,next_check,payer,genesis,"
                    "token,decimals,symbol) VALUES (?,?,?,?,?,?,0,?,?,?,?,?)",
                    (reference, recipient, amount, lamports, label[:64], now, payer, genesis, token, decimals, symbol[:12]),
                )
                return {"status": "open", "created": True}
            if row["recipient"] != recipient or row["lamports"] != lamports or row["token"] != token:
                raise Conflict("that reference is already registered with different details")
            # Fill in a payer we did not know. A request made by tapping is registered by the
            # phone ASKING for money, which cannot know who will pay; the paying phone registers
            # the same reference moments later and does know. Without this the server has nobody
            # to tell when the request is withdrawn. Never overwrites a payer already recorded.
            if payer and not row["payer"]:
                self._db.execute("UPDATE requests SET payer=? WHERE reference=?", (payer, reference))
            # A repeat registration is a nudge: look now rather than on the next sweep.
            if row["status"] == "open":
                self._db.execute("UPDATE requests SET next_check=0 WHERE reference=?", (reference,))
            return {"status": row["status"], "signature": row["signature"], "created": False}

    def reject(self, reference: str, by: str) -> dict | None:
        """The payer declined. Like a withdrawal, it stays OPEN: they may change their mind, and
        money sent anyway must still be recognised."""
        with self._lock, self._db:
            row = self._db.execute("SELECT * FROM requests WHERE reference=?", (reference,)).fetchone()
            if row is None:
                return None
            self._db.execute("UPDATE requests SET rejected=1, rejected_by=? WHERE reference=?", (by, reference))
        return dict(row)

    def withdraw(self, reference: str) -> dict | None:
        """Mark a request as taken back. It stays OPEN on purpose: withdrawing means "stop
        asking", not "refuse money", and a payer already inside their wallet may land a payment
        a second later. That payment must still be noticed."""
        with self._lock, self._db:
            row = self._db.execute("SELECT * FROM requests WHERE reference=?", (reference,)).fetchone()
            if row is None:
                return None
            self._db.execute("UPDATE requests SET withdrawn=1 WHERE reference=?", (reference,))
        return dict(row)

    def get(self, reference: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM requests WHERE reference=?", (reference,)).fetchone()
        return dict(row) if row else None

    def due(self, now: float, limit: int = 50) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM requests WHERE status='open' AND next_check<=? ORDER BY next_check LIMIT ?",
                (now, limit),
            ).fetchall()
        return [dict(r) for r in rows]

    def schedule(self, reference: str, at: float) -> None:
        with self._lock, self._db:
            self._db.execute("UPDATE requests SET next_check=? WHERE reference=?", (at, reference))

    def mark_paid(self, reference: str, signature: str) -> bool:
        with self._lock, self._db:
            try:
                cur = self._db.execute(
                    "UPDATE requests SET status='paid', signature=?, paid_at=? WHERE reference=? AND status='open'",
                    (signature, time.time(), reference),
                )
            except sqlite3.IntegrityError:
                return False  # signature already settled a different request
            return cur.rowcount == 1

    def open_among(self, keys: list[str]) -> list[dict]:
        if not keys:
            return []
        marks = ",".join("?" * len(keys))
        with self._lock:
            rows = self._db.execute(
                f"SELECT * FROM requests WHERE status='open' AND reference IN ({marks})", keys
            ).fetchall()
        return [dict(r) for r in rows]

    def recent_paid(self, recipient: str, since: float) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM requests WHERE recipient=? AND status='paid' AND paid_at>=? ORDER BY paid_at",
                (recipient, since),
            ).fetchall()
        return [dict(r) for r in rows]

    def open_for_payer(self, payer: str, since: float) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM requests WHERE payer=? AND status='open' AND created_at>=? ORDER BY created_at",
                (payer, since),
            ).fetchall()
        return [dict(r) for r in rows]

    def recipients(self) -> list[str]:
        with self._lock:
            rows = self._db.execute(
                "SELECT recipient FROM requests UNION SELECT recipient FROM devices"
            ).fetchall()
        return sorted(r[0] for r in rows)

    def open_count(self) -> int:
        with self._lock:
            return self._db.execute("SELECT COUNT(*) FROM requests WHERE status='open'").fetchone()[0]

    # -- devices ----------------------------------------------------------------------------

    # -- rewards ----------------------------------------------------------------------------

    def reward_config(self) -> dict:
        """Whatever has been changed at runtime. The defaults live in rewards.Config."""
        with self._lock:
            return {r["key"]: r["value"] for r in self._db.execute("SELECT key, value FROM reward_config")}

    def set_reward_config(self, values: dict) -> dict:
        now = time.time()
        with self._lock, self._db:
            for k, v in values.items():
                self._db.execute(
                    "INSERT INTO reward_config(key,value,updated_at) VALUES (?,?,?)"
                    " ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at",
                    (str(k), str(v), now),
                )
        return self.reward_config()

    def reward_history(self, payer: str, counterparty: str, now: float, week: int, month_start: float) -> dict:
        """The payer's side of the rules: what they have earned, when, and with whom."""
        with self._lock:
            rows = self._db.execute(
                "SELECT kind, skr, week, created_at, counterparty FROM rewards"
                " WHERE payer=? AND state!='reversed'", (payer,),
            ).fetchall()
            spent = self._db.execute(
                "SELECT COALESCE(SUM(skr),0) FROM rewards WHERE week=? AND state!='reversed'", (week,),
            ).fetchone()[0]
        with_them = [r["created_at"] for r in rows if r["counterparty"] == counterparty]
        return {
            "rewarded_this_week": sum(1 for r in rows if r["week"] == week and r["kind"] == "per_payment"),
            "earned_in_campaign": sum(r["skr"] for r in rows),
            "earned_this_month": sum(r["skr"] for r in rows if r["created_at"] >= month_start),
            "last_reward_with_counterparty": max(with_them) if with_them else None,
            "had_first_payment": any(r["kind"] == "first_payment" for r in rows),
            "week_spent": spent,
        }

    def record_rewards(self, reference: str, payer: str, counterparty: str, awards: list,
                       week: int, now: float, unlock_at: float) -> int:
        """Writes one row per award. The unique index on (reference, kind) means a payment
        checked twice — two watchers, a webhook and a sweep — still pays once."""
        written = 0
        with self._lock, self._db:
            for kind, skr in awards:
                try:
                    self._db.execute(
                        "INSERT INTO rewards(reference,payer,counterparty,kind,skr,week,created_at,unlock_at)"
                        " VALUES (?,?,?,?,?,?,?,?)",
                        (reference, payer, counterparty, kind, int(skr), week, now, unlock_at),
                    )
                    written += 1
                except sqlite3.IntegrityError:
                    pass  # already paid for this payment
        return written

    def record_attempt(self, reference: str, payer: str, counterparty: str, refused: str | None,
                       skr: int, at: float) -> None:
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO reward_attempts(reference,payer,counterparty,refused,skr,at) VALUES (?,?,?,?,?,?)"
                " ON CONFLICT(reference) DO UPDATE SET refused=excluded.refused, skr=excluded.skr, at=excluded.at",
                (reference, payer, counterparty, refused, int(skr), at),
            )

    def rewards_for(self, payer: str, limit: int = 50) -> list[dict]:
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM rewards WHERE payer=? ORDER BY created_at DESC LIMIT ?", (payer, limit),
            ).fetchall()
        return [dict(r) for r in rows]

    def reward_balance(self, payer: str, now: float) -> dict:
        with self._lock:
            rows = self._db.execute(
                "SELECT state, skr, unlock_at FROM rewards WHERE payer=?", (payer,)).fetchall()
        locked = sum(r["skr"] for r in rows if r["state"] == "locked" and r["unlock_at"] > now)
        ready = sum(r["skr"] for r in rows if r["state"] == "locked" and r["unlock_at"] <= now)
        return {"locked": locked, "ready": ready,
                "settled": sum(r["skr"] for r in rows if r["state"] == "settled"),
                "total": locked + ready + sum(r["skr"] for r in rows if r["state"] == "settled")}

    def add_device(self, recipient: str, token: str) -> None:
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO devices(recipient,token,updated_at) VALUES (?,?,?)"
                " ON CONFLICT(recipient,token) DO UPDATE SET updated_at=excluded.updated_at",
                (recipient, token, time.time()),
            )

    def devices(self, recipient: str) -> list[str]:
        with self._lock:
            return [r[0] for r in self._db.execute("SELECT token FROM devices WHERE recipient=?", (recipient,))]

    def drop_device(self, token: str) -> None:
        with self._lock, self._db:
            self._db.execute("DELETE FROM devices WHERE token=?", (token,))
