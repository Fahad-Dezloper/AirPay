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
            for col in ("payer", "genesis"):
                if col not in cols:
                    self._db.execute(f"ALTER TABLE requests ADD COLUMN {col} TEXT")
            self._db.execute("CREATE INDEX IF NOT EXISTS by_payer ON requests(payer, status)")

    # -- requests ---------------------------------------------------------------------------

    def register(self, reference: str, recipient: str, amount: str, lamports: int, label: str,
                 payer: str | None = None, genesis: str | None = None) -> dict:
        now = time.time()
        with self._lock, self._db:
            row = self._db.execute("SELECT * FROM requests WHERE reference=?", (reference,)).fetchone()
            if row is None:
                self._db.execute(
                    "INSERT INTO requests(reference,recipient,amount,lamports,label,created_at,next_check,payer,genesis)"
                    " VALUES (?,?,?,?,?,?,0,?,?)",
                    (reference, recipient, amount, lamports, label[:64], now, payer, genesis),
                )
                return {"status": "open", "created": True}
            if row["recipient"] != recipient or row["lamports"] != lamports:
                raise Conflict("that reference is already registered with different details")
            # A repeat registration is a nudge: look now rather than on the next sweep.
            if row["status"] == "open":
                self._db.execute("UPDATE requests SET next_check=0 WHERE reference=?", (reference,))
            return {"status": row["status"], "signature": row["signature"], "created": False}

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
