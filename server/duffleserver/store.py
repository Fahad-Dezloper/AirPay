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
                CREATE TABLE IF NOT EXISTS offers (
                    id         TEXT PRIMARY KEY,
                    funder     TEXT NOT NULL DEFAULT 'duffle',
                    app        TEXT NOT NULL,
                    what       TEXT NOT NULL,
                    address    TEXT NOT NULL,
                    token      TEXT NOT NULL DEFAULT 'SOL',
                    minimum    INTEGER NOT NULL DEFAULT 0,
                    reward     INTEGER NOT NULL DEFAULT 0,
                    budget     INTEGER NOT NULL DEFAULT 0,
                    starts     REAL NOT NULL,
                    ends       REAL NOT NULL,
                    window_hours REAL NOT NULL DEFAULT 48,
                    link       TEXT NOT NULL DEFAULT ''
                );
                -- One row per person per offer. The `signature` is the whole point: it is the
                -- proof a dApp is eventually shown, and the reason attribution is recorded
                -- even when the reward is zero.
                CREATE TABLE IF NOT EXISTS claims (
                    offer_id   TEXT NOT NULL,
                    address    TEXT NOT NULL,
                    claimed_at REAL NOT NULL,
                    state      TEXT NOT NULL DEFAULT 'open',
                    skr        INTEGER NOT NULL DEFAULT 0,
                    signature  TEXT,
                    refused    TEXT,
                    checked_at REAL NOT NULL DEFAULT 0,
                    PRIMARY KEY (offer_id, address)
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
                              # "Later": not a no. The asker is told so it shows as pending
                              # rather than as silence, and it stays open to be paid.
                              ("deferred", "INTEGER"), ("deferred_by", "TEXT"),
                              # What was asked for: "SOL" or an SPL mint. `lamports` then holds
                              # the amount in that token's smallest unit.
                              ("token", "TEXT NOT NULL DEFAULT 'SOL'"), ("decimals", "INTEGER NOT NULL DEFAULT 9"),
                              ("symbol", "TEXT NOT NULL DEFAULT 'SOL'"),
                              # How the payment started: only "tap" earns a reward.
                              ("origin", "TEXT NOT NULL DEFAULT ''"),
                              # When a payer was attached by a *second* registration — the mark
                              # of two phones, since the asking phone cannot know who will pay.
                              ("payer_at", "REAL"),
                              ("registrations", "INTEGER NOT NULL DEFAULT 1")):
                if col not in cols:
                    self._db.execute(f"ALTER TABLE requests ADD COLUMN {col} {kind}")
            self._db.execute("CREATE INDEX IF NOT EXISTS by_payer ON requests(payer, status)")

    # -- requests ---------------------------------------------------------------------------

    def register(self, reference: str, recipient: str, amount: str, lamports: int, label: str,
                 payer: str | None = None, genesis: str | None = None,
                 token: str = "SOL", decimals: int = 9, symbol: str = "SOL", origin: str = "") -> dict:
        now = time.time()
        with self._lock, self._db:
            row = self._db.execute("SELECT * FROM requests WHERE reference=?", (reference,)).fetchone()
            if row is None:
                self._db.execute(
                    "INSERT INTO requests(reference,recipient,amount,lamports,label,created_at,next_check,payer,genesis,"
                    "token,decimals,symbol,origin) VALUES (?,?,?,?,?,?,0,?,?,?,?,?,?)",
                    (reference, recipient, amount, lamports, label[:64], now, payer, genesis, token, decimals,
                     symbol[:12], origin[:16]),
                )
                return {"status": "open", "created": True}
            if row["recipient"] != recipient or row["lamports"] != lamports or row["token"] != token:
                raise Conflict("that reference is already registered with different details")
            # Fill in a payer we did not know. A request made by tapping is registered by the
            # phone ASKING for money, which cannot know who will pay; the paying phone registers
            # the same reference moments later and does know. Without this the server has nobody
            # to tell when the request is withdrawn. Never overwrites a payer already recorded.
            self._db.execute("UPDATE requests SET registrations=registrations+1 WHERE reference=?", (reference,))
            if payer and not row["payer"]:
                self._db.execute("UPDATE requests SET payer=?, payer_at=? WHERE reference=?",
                                 (payer, now, reference))
            # Same for the origin: the phone that taps knows it was a tap; the one that served
            # the request may not have said so.
            if origin and not row["origin"]:
                self._db.execute("UPDATE requests SET origin=? WHERE reference=?", (origin[:16], reference))
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

    def defer(self, reference: str, by: str) -> dict | None:
        """The payer said "later". Unlike a rejection this is not an answer, so nothing about
        the request changes except that the asker can stop wondering."""
        with self._lock, self._db:
            row = self._db.execute("SELECT * FROM requests WHERE reference=?", (reference,)).fetchone()
            if row is None:
                return None
            self._db.execute("UPDATE requests SET deferred=1, deferred_by=? WHERE reference=?", (by, reference))
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
        # The per-person caps are about the TAP reward budget: they stop one person taking the
        # share meant for twenty. An offer is bounded twice already — by its own budget and by
        # the weekly pool — so counting it here as well would mean claiming offers costs you
        # your tap rewards, which is backwards: offers are the half that pays for itself.
        # `week_spent` below deliberately still counts everything, because that IS the budget.
        taps = [r for r in rows if r["kind"] != "offer"]
        return {
            "rewarded_this_week": sum(1 for r in rows if r["week"] == week and r["kind"] == "per_payment"),
            "earned_in_campaign": sum(r["skr"] for r in taps),
            "earned_this_month": sum(r["skr"] for r in taps if r["created_at"] >= month_start),
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

    def reward_attempts(self, limit: int = 100) -> list[dict]:
        """Every payment the rules looked at, oldest first — including the ones that earned
        nothing. This is the only place a farming pattern, or a rule refusing too much, is
        visible; R2's admin view reads it."""
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM reward_attempts ORDER BY at LIMIT ?", (limit,)).fetchall()
        return [dict(r) for r in rows]

    def settled_payments(self, since: float, limit: int = 2000) -> list[dict]:
        """Every payment that landed since `since`, in the shape patrol.py reads. Both
        directions of a pair are in here, which is what makes a round trip visible."""
        with self._lock:
            rows = self._db.execute(
                "SELECT reference, payer, recipient, lamports, token, paid_at FROM requests"
                " WHERE status='paid' AND payer IS NOT NULL AND paid_at >= ? ORDER BY paid_at LIMIT ?",
                (since, limit),
            ).fetchall()
        return [{"reference": r["reference"], "payer": r["payer"], "counterparty": r["recipient"],
                 "units": r["lamports"], "token": r["token"], "at": r["paid_at"]} for r in rows]

    def locked_rewards(self, now: float) -> list[dict]:
        """Rewards that can still be taken back, one row per payment (not per award), with the
        payment's own details joined on so the patterns can be looked for."""
        with self._lock:
            rows = self._db.execute(
                "SELECT w.reference, w.payer, w.counterparty, SUM(w.skr) AS skr,"
                "       MIN(w.created_at) AS created_at, MIN(w.unlock_at) AS unlock_at,"
                "       q.lamports AS units, q.token AS token, q.payer_at AS payer_at,"
                "       q.registrations AS registrations"
                "  FROM rewards w LEFT JOIN requests q ON q.reference = w.reference"
                " WHERE w.state='locked' GROUP BY w.reference",
            ).fetchall()
        out = []
        for r in rows:
            d = dict(r)
            # Two registrations *and* a payer that arrived in the later one: the asking phone
            # cannot know who will pay, so that second call came from the other handset.
            d["corroborated"] = bool(d.get("payer_at")) and (d.get("registrations") or 1) > 1
            d["units"] = d.get("units") or 0
            out.append(d)
        return out

    def settle_matured(self, now: float) -> int:
        """Locked rewards past their unlock time become final. Nothing is paid out here — that
        is R6's decision — this only ends the window in which they can be reversed."""
        with self._lock, self._db:
            cur = self._db.execute(
                "UPDATE rewards SET state='settled' WHERE state='locked' AND unlock_at <= ?", (now,))
        return cur.rowcount

    def reverse_rewards(self, reference: str, reason: str) -> int:
        """Takes back every award for one payment, with the reason kept. Only a locked reward
        can be reversed; once settled it is theirs, whatever we notice afterwards."""
        with self._lock, self._db:
            cur = self._db.execute(
                "UPDATE rewards SET state='reversed', reason=? WHERE reference=? AND state='locked'",
                (reason, reference))
        return cur.rowcount

    def reward_ledger(self, since: float) -> dict:
        """The admin view: what was paid, by rule and by day, and what was refused and why."""
        with self._lock:
            paid = self._db.execute(
                "SELECT kind, state, COUNT(*) AS n, SUM(skr) AS skr FROM rewards"
                " WHERE created_at >= ? GROUP BY kind, state", (since,)).fetchall()
            days = self._db.execute(
                "SELECT date(created_at, 'unixepoch') AS day, COUNT(*) AS n, SUM(skr) AS skr"
                "  FROM rewards WHERE created_at >= ? AND state!='reversed' GROUP BY day ORDER BY day",
                (since,)).fetchall()
            refused = self._db.execute(
                "SELECT refused, COUNT(*) AS n FROM reward_attempts"
                " WHERE at >= ? AND refused IS NOT NULL GROUP BY refused ORDER BY n DESC",
                (since,)).fetchall()
            reversed_rows = self._db.execute(
                "SELECT reference, payer, counterparty, skr, reason FROM rewards"
                " WHERE state='reversed' AND created_at >= ?", (since,)).fetchall()
            people = self._db.execute(
                "SELECT COUNT(DISTINCT payer) AS n FROM rewards WHERE created_at >= ? AND state!='reversed'",
                (since,)).fetchone()[0]
        return {
            "by_rule": [dict(r) for r in paid],
            "by_day": [dict(r) for r in days],
            "refused": [dict(r) for r in refused],
            "reversed": [dict(r) for r in reversed_rows],
            "people": people,
        }

    def refusals_for(self, payer: str, limit: int = 10) -> list[dict]:
        """Recent payments by this payer that earned nothing, newest first. The rewards screen
        shows these: "why didn't I get anything?" is the question a reward layer has to be able
        to answer, or people assume it is broken."""
        with self._lock:
            rows = self._db.execute(
                "SELECT reference, counterparty, refused, at FROM reward_attempts"
                " WHERE payer=? AND refused IS NOT NULL ORDER BY at DESC LIMIT ?", (payer, limit),
            ).fetchall()
        return [dict(r) for r in rows]

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

    # -- offers -----------------------------------------------------------------------------

    def put_offer(self, o: dict) -> None:
        cols = ("id", "funder", "app", "what", "address", "token", "minimum", "reward",
                "budget", "starts", "ends", "window_hours", "link")
        with self._lock, self._db:
            self._db.execute(
                f"INSERT OR REPLACE INTO offers({','.join(cols)}) VALUES ({','.join('?' * len(cols))})",
                tuple(o.get(c) for c in cols),
            )

    def offers(self, live_at: float | None = None) -> list[dict]:
        sql = "SELECT * FROM offers"
        args: tuple = ()
        if live_at is not None:
            sql += " WHERE starts <= ? AND ends >= ?"
            args = (live_at, live_at)
        with self._lock:
            return [dict(r) for r in self._db.execute(sql + " ORDER BY ends", args).fetchall()]

    def offer(self, offer_id: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM offers WHERE id=?", (offer_id,)).fetchone()
        return dict(row) if row else None

    def claim_offer(self, offer_id: str, address: str, at: float) -> bool:
        """Opens a claim. False when this person already has one — re-claiming would restart
        the attribution window and let a payment be counted twice."""
        with self._lock, self._db:
            try:
                self._db.execute(
                    "INSERT INTO claims(offer_id,address,claimed_at) VALUES (?,?,?)",
                    (offer_id, address, at))
                return True
            except sqlite3.IntegrityError:
                return False

    def claim(self, offer_id: str, address: str) -> dict | None:
        with self._lock:
            row = self._db.execute("SELECT * FROM claims WHERE offer_id=? AND address=?",
                                   (offer_id, address)).fetchone()
        return dict(row) if row else None

    def claims_for(self, address: str) -> list[dict]:
        with self._lock:
            return [dict(r) for r in self._db.execute(
                "SELECT * FROM claims WHERE address=?", (address,)).fetchall()]

    def open_claims(self, before: float, limit: int = 100) -> list[dict]:
        """Claims still waiting on a payment, least recently looked at first."""
        with self._lock:
            return [dict(r) for r in self._db.execute(
                "SELECT * FROM claims WHERE state='open' AND checked_at <= ? ORDER BY checked_at LIMIT ?",
                (before, limit)).fetchall()]

    def offer_spent(self, offer_id: str) -> int:
        with self._lock:
            return self._db.execute(
                "SELECT COALESCE(SUM(skr),0) FROM claims WHERE offer_id=? AND state='paid'",
                (offer_id,)).fetchone()[0]

    def settle_claim(self, offer_id: str, address: str, state: str, skr: int,
                     signature: str | None, refused: str | None, at: float) -> bool:
        """Only an open claim can be settled, so two checks racing pay once."""
        with self._lock, self._db:
            cur = self._db.execute(
                "UPDATE claims SET state=?, skr=?, signature=?, refused=?, checked_at=?"
                " WHERE offer_id=? AND address=? AND state='open'",
                (state, int(skr), signature, refused, at, offer_id, address))
        return cur.rowcount == 1

    def touch_claim(self, offer_id: str, address: str, at: float, refused: str | None) -> None:
        """Looked at, nothing yet. The reason is kept so the app can say why it hasn't paid."""
        with self._lock, self._db:
            self._db.execute("UPDATE claims SET checked_at=?, refused=? WHERE offer_id=? AND address=?",
                             (at, refused, offer_id, address))

    def offer_proof(self, offer_id: str) -> dict:
        """What a dApp is shown: how many verified Seeker owners opened it, how many paid, and
        the signatures. This is the asset the unfunded campaign exists to produce."""
        with self._lock:
            rows = self._db.execute("SELECT state, skr, signature FROM claims WHERE offer_id=?",
                                    (offer_id,)).fetchall()
        paid = [r for r in rows if r["state"] == "paid"]
        return {"offer_id": offer_id, "opened": len(rows), "paid": len(paid),
                "skr_paid": sum(r["skr"] for r in paid),
                "signatures": [r["signature"] for r in paid if r["signature"]]}

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
