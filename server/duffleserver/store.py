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
