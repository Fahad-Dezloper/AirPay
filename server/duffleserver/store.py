"""
SQLite: who is signed in, which wallets belong to one person, and where to send their push.

The recharge orders live in their own table, created by topups.py; support tickets by support.py.
Tables from earlier versions of the app are dropped on start (RETIRED_TABLES).
"""
from __future__ import annotations

import sqlite3
import threading
import time


class Conflict(Exception):
    pass


#: Tables from AirPay 1.0.x (payment requests, rewards, offers, referrals, names, messages, the
#: gift-card shop). Nothing reads them any more, so they are dropped when the server starts.
RETIRED_TABLES = ("requests", "rewards", "reward_attempts", "reward_config", "offers", "claims",
                  "messages", "purchases", "referral_codes", "referrals", "payouts", "offer_notices",
                  "notice_state", "handles", "server_secrets", "wallet_notices")


class Store:
    def __init__(self, path: str = ":memory:"):
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._lock = threading.Lock()
        with self._lock, self._db:
            self._db.executescript(
                """
                CREATE TABLE IF NOT EXISTS auth_nonces (
                    nonce      TEXT PRIMARY KEY,
                    address    TEXT NOT NULL,
                    message    TEXT NOT NULL,
                    issued_at  REAL NOT NULL,
                    expires_at REAL NOT NULL,
                    attempts   INTEGER NOT NULL DEFAULT 0,
                    used       INTEGER NOT NULL DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS auth_nonces_address ON auth_nonces(address, expires_at);
                CREATE TABLE IF NOT EXISTS sessions (
                    token_hash TEXT PRIMARY KEY,
                    address    TEXT NOT NULL,
                    created_at REAL NOT NULL,
                    expires_at REAL NOT NULL,
                    revoked_at REAL
                );
                CREATE INDEX IF NOT EXISTS sessions_address ON sessions(address, created_at);
                -- One person's wallets. A wallet belongs to at most one account, which is what
                -- lets a wallet that has been through the app before be recognised on a new phone.
                CREATE TABLE IF NOT EXISTS accounts (
                    id         TEXT PRIMARY KEY,
                    created_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS account_wallets (
                    address    TEXT PRIMARY KEY,
                    account_id TEXT NOT NULL,
                    linked_at  REAL NOT NULL
                );
                CREATE INDEX IF NOT EXISTS account_wallets_account ON account_wallets(account_id);
                CREATE TABLE IF NOT EXISTS devices (
                    recipient  TEXT NOT NULL,
                    token      TEXT NOT NULL,
                    updated_at REAL NOT NULL,
                    PRIMARY KEY (recipient, token)
                );
                """
            )
            for table in RETIRED_TABLES:
                self._db.execute(f"DROP TABLE IF EXISTS {table}")
            # Columns added after launch. SQLite has no "ADD COLUMN IF NOT EXISTS", so ask first.
            for table, col, ddl in (("auth_nonces", "purpose", "TEXT NOT NULL DEFAULT 'signin'"),
                                    ("sessions", "account_id", "TEXT")):
                if col not in {r[1] for r in self._db.execute(f"PRAGMA table_info({table})")}:
                    self._db.execute(f"ALTER TABLE {table} ADD COLUMN {col} {ddl}")

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

    def open_nonces(self, address: str, now: float) -> int:
        with self._lock:
            return self._db.execute(
                "SELECT COUNT(*) FROM auth_nonces WHERE address=? AND used=0 AND expires_at>?",
                (address, now)).fetchone()[0]

    def drop_oldest_nonce(self, address: str, now: float) -> None:
        """Retires the oldest unused, unexpired challenge for an address, to make room."""
        with self._lock, self._db:
            self._db.execute(
                "DELETE FROM auth_nonces WHERE nonce = (SELECT nonce FROM auth_nonces"
                " WHERE address=? AND used=0 AND expires_at>? ORDER BY issued_at ASC, rowid ASC LIMIT 1)",
                (address, now))

    def put_nonce(self, nonce: str, address: str, message: str, issued: float, expires: float,
                  purpose: str = "signin") -> None:
        with self._lock, self._db:
            # Expired challenges are swept as new ones arrive, so the table cannot grow forever.
            self._db.execute("DELETE FROM auth_nonces WHERE expires_at < ?", (issued - 3600,))
            self._db.execute(
                "INSERT INTO auth_nonces(nonce,address,message,issued_at,expires_at,purpose) VALUES(?,?,?,?,?,?)",
                (nonce, address, message, issued, expires, purpose))

    def try_nonce(self, nonce: str, now: float, max_attempts: int) -> dict | None:
        """Counts one attempt against a live, unused challenge and returns it — atomically, in a
        single UPDATE, so two simultaneous tries cannot both be treated as the first."""
        with self._lock, self._db:
            cur = self._db.execute(
                "UPDATE auth_nonces SET attempts=attempts+1 "
                "WHERE nonce=? AND used=0 AND expires_at>? AND attempts<?",
                (nonce, now, max_attempts))
            if cur.rowcount != 1:
                return None
            r = self._db.execute("SELECT address,message,purpose FROM auth_nonces WHERE nonce=?", (nonce,)).fetchone()
            return dict(r) if r else None

    def consume_nonce(self, nonce: str) -> bool:
        with self._lock, self._db:
            return self._db.execute(
                "UPDATE auth_nonces SET used=1 WHERE nonce=? AND used=0", (nonce,)).rowcount == 1

    def put_session(self, token_hash: str, address: str, now: float, expires: float, keep: int,
                    account_id: str | None = None) -> None:
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO sessions(token_hash,address,created_at,expires_at,account_id) VALUES(?,?,?,?,?)",
                (token_hash, address, now, expires, account_id))
            # Only the newest `keep` stay live for one address.
            self._db.execute(
                "UPDATE sessions SET revoked_at=? WHERE address=? AND revoked_at IS NULL AND token_hash NOT IN "
                "(SELECT token_hash FROM sessions WHERE address=? AND revoked_at IS NULL "
                " ORDER BY created_at DESC LIMIT ?)",
                (now, address, address, keep))

    def session_address(self, token_hash: str, now: float) -> str | None:
        with self._lock:
            r = self._db.execute(
                "SELECT address FROM sessions WHERE token_hash=? AND revoked_at IS NULL AND expires_at>?",
                (token_hash, now)).fetchone()
        return r["address"] if r else None

    def session_info(self, token_hash: str, now: float) -> dict | None:
        """The wallet that signed in, and its account (looked up if the session predates accounts)."""
        with self._lock:
            r = self._db.execute(
                "SELECT address, account_id FROM sessions WHERE token_hash=? AND revoked_at IS NULL AND expires_at>?",
                (token_hash, now)).fetchone()
            if not r:
                return None
            acct = r["account_id"]
            if acct is None:
                w = self._db.execute("SELECT account_id FROM account_wallets WHERE address=?", (r["address"],)).fetchone()
                acct = w[0] if w else None
        return {"address": r["address"], "account_id": acct}

    def _family(self, address: str) -> list[str]:
        """Every wallet in this wallet's account, itself included. The caller holds the lock."""
        r = self._db.execute("SELECT account_id FROM account_wallets WHERE address=?", (address,)).fetchone()
        if not r:
            return [address]
        out = [x[0] for x in self._db.execute(
            "SELECT address FROM account_wallets WHERE account_id=? ORDER BY linked_at, address", (r[0],))]
        return out if address in out else [address] + out

    def family(self, address: str) -> list[str]:
        with self._lock:
            return self._family(address)

    def account_of(self, address: str) -> str | None:
        with self._lock:
            r = self._db.execute("SELECT account_id FROM account_wallets WHERE address=?", (address,)).fetchone()
        return r[0] if r else None

    def ensure_account_ex(self, address: str, now: float) -> tuple[str, bool]:
        """The account this wallet belongs to, made (with this wallet in it) if it has none, and
        whether it was made just now — which is what a welcome card hangs on."""
        import uuid
        with self._lock, self._db:
            r = self._db.execute("SELECT account_id FROM account_wallets WHERE address=?", (address,)).fetchone()
            if r:
                return r[0], False
            acct = uuid.uuid4().hex
            self._db.execute("INSERT INTO accounts(id,created_at) VALUES(?,?)", (acct, now))
            self._db.execute("INSERT INTO account_wallets(address,account_id,linked_at) VALUES(?,?,?)", (address, acct, now))
            return acct, True

    def ensure_account(self, address: str, now: float) -> str:
        """The account this wallet belongs to, made (with this wallet in it) if it has none."""
        return self.ensure_account_ex(address, now)[0]

    def account_key(self, address: str) -> str:
        """Who a notice is for: the account, or the wallet itself when it has none yet."""
        a = self.account_of(address)
        return a or address

    def link_wallet(self, account_id: str, address: str, now: float) -> str:
        """Puts a wallet in an account: "ok", or "other_account" if it already belongs to a
        different one. A wallet is never moved: that is what makes one that has been through the
        app before recognisable on a new phone."""
        with self._lock, self._db:
            r = self._db.execute("SELECT account_id FROM account_wallets WHERE address=?", (address,)).fetchone()
            if r:
                return "ok" if r[0] == account_id else "other_account"
            self._db.execute("INSERT INTO account_wallets(address,account_id,linked_at) VALUES(?,?,?)",
                             (address, account_id, now))
            return "ok"

    def revoke_session(self, token_hash: str, now: float) -> bool:
        with self._lock, self._db:
            return self._db.execute(
                "UPDATE sessions SET revoked_at=? WHERE token_hash=? AND revoked_at IS NULL",
                (now, token_hash)).rowcount == 1

    def revoke_all_sessions(self, address: str, now: float) -> int:
        with self._lock, self._db:
            return self._db.execute(
                "UPDATE sessions SET revoked_at=? WHERE address=? AND revoked_at IS NULL",
                (now, address)).rowcount

    def stats(self) -> dict:
        """Counts for the admin stats view (stats.py). Reads only."""
        from .stats import collect
        with self._lock:
            return collect(self._db)
