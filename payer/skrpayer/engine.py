"""
The payout state machine. One rule above all: **one payout id can move money at most once.**

A payout is `created -> sending -> confirmed | failed`. Its signature is written down BEFORE it
is sent, so after a crash or a timeout the signature is known and can be asked about. A payout is
only ever re-signed after the chain says the old transaction's blockhash is expired AND it never
landed — never on a guess, because a second signature on a slow first one pays twice.

The caller's whole contract is: give me an id, a destination and an amount; ask me again with
the same id as often as you like; the answer is always the same payout.
"""
from __future__ import annotations

import os
import sqlite3
import threading
import time

from nacl.signing import SigningKey

from . import solana as sol

CREATED, SENDING, CONFIRMED, FAILED = "created", "sending", "confirmed", "failed"
DAY = 86_400.0


class Refused(Exception):
    """A payout that must not happen. `code` is stable; the message is for logs."""

    def __init__(self, code: str, message: str = ""):
        super().__init__(message or code)
        self.code = code


class Limits:
    def __init__(self, per_payout: int, per_day: int, per_recipient_day: int, min_payout: int = 1):
        self.per_payout, self.per_day = per_payout, per_day
        self.per_recipient_day, self.min_payout = per_recipient_day, min_payout


class Engine:
    def __init__(self, db_path: str, key: SigningKey, rpc, mint: str, limits: Limits,
                 pause_file: str | None = None):
        self.key = key
        self.pubkey = sol.b58encode(bytes(key.verify_key))
        self.rpc, self.mint, self.limits = rpc, mint, limits
        self.pause_file = pause_file
        self._lock = threading.Lock()
        #: Serialises `advance` for ALL payouts. The HTTP thread and the background loop both call
        #: it; without this two threads could each see `created`, each sign a transaction with a
        #: different blockhash, and pay twice. Throughput is irrelevant here; correctness is not.
        self._advance_lock = threading.RLock()
        self._db = sqlite3.connect(db_path, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        with self._db:
            self._db.executescript("""
                CREATE TABLE IF NOT EXISTS payouts (
                    id TEXT PRIMARY KEY, to_addr TEXT NOT NULL, amount INTEGER NOT NULL,
                    state TEXT NOT NULL, signature TEXT, blockhash TEXT, error TEXT,
                    created REAL NOT NULL, updated REAL NOT NULL, attempts INTEGER NOT NULL DEFAULT 0);
                CREATE INDEX IF NOT EXISTS payouts_to ON payouts(to_addr, created);
                CREATE INDEX IF NOT EXISTS payouts_state ON payouts(state);
            """)
        info = rpc.mint_info(mint)
        if info["extensions"]:
            # Token-2022 extensions (transfer fees, hooks) change what a transfer needs.
            raise RuntimeError("this mint has extensions; a plain checked transfer is not enough")
        self.decimals, self.token_program = info["decimals"], info["owner"]
        if self.token_program not in (sol.TOKEN_PROGRAM, sol.TOKEN_2022):
            raise RuntimeError("mint is not owned by a token program")
        self.source = sol.ata(self.pubkey, mint, self.token_program)

    # -- switches ---------------------------------------------------------------------------
    @property
    def paused(self) -> bool:
        return bool(self.pause_file and os.path.exists(self.pause_file))

    def pause(self, on: bool) -> None:
        if not self.pause_file:
            raise Refused("no_pause_file")
        if on:
            open(self.pause_file, "w").close()
        elif os.path.exists(self.pause_file):
            os.remove(self.pause_file)

    # -- reads ------------------------------------------------------------------------------
    def get(self, pid: str) -> dict | None:
        with self._lock:
            r = self._db.execute("SELECT * FROM payouts WHERE id=?", (pid,)).fetchone()
        return dict(r) if r else None

    def spent_since(self, since: float, to: str | None = None) -> int:
        q, a = "SELECT COALESCE(SUM(amount),0) FROM payouts WHERE state!=? AND created>=?", [FAILED, since]
        if to:
            q, a = q + " AND to_addr=?", a + [to]
        with self._lock:
            return int(self._db.execute(q, a).fetchone()[0])

    def totals(self) -> dict:
        with self._lock:
            rows = self._db.execute("SELECT state, COUNT(*), COALESCE(SUM(amount),0) FROM payouts GROUP BY state").fetchall()
        return {r[0]: {"count": r[1], "amount": int(r[2])} for r in rows}

    # -- the one write ----------------------------------------------------------------------
    def request(self, pid: str, to: str, amount: int, now: float | None = None) -> dict:
        now = now if now is not None else time.time()
        if not isinstance(pid, str) or not 8 <= len(pid) <= 80 or not all(c.isalnum() or c in "-_:." for c in pid):
            raise Refused("bad_id")
        if not sol.is_address(to):
            raise Refused("bad_destination")
        if to == self.pubkey:
            raise Refused("self_payment")
        if not isinstance(amount, int) or isinstance(amount, bool) or amount < self.limits.min_payout:
            raise Refused("bad_amount")
        with self._lock:
            old = self._db.execute("SELECT * FROM payouts WHERE id=?", (pid,)).fetchone()
            if old is not None:
                # The same id must mean the same payout. A different destination or amount under a
                # used id is somebody trying to redirect money, or a bug; both stop here.
                if old["to_addr"] != to or old["amount"] != amount:
                    raise Refused("id_reused", "same id, different payout")
            else:
                if self.paused:
                    raise Refused("paused")
                self._admit(to, amount, now)
                self._db.execute(
                    "INSERT INTO payouts(id,to_addr,amount,state,created,updated) VALUES(?,?,?,?,?,?)",
                    (pid, to, amount, CREATED, now, now))
                self._db.commit()
        return self.advance(pid, now)

    def _admit(self, to: str, amount: int, now: float) -> None:
        L = self.limits
        if amount > L.per_payout:
            raise Refused("over_payout_cap")
        if self.spent_since_locked(now - DAY) + amount > L.per_day:
            raise Refused("over_daily_cap")
        if self.spent_since_locked(now - DAY, to) + amount > L.per_recipient_day:
            raise Refused("over_recipient_cap")

    def spent_since_locked(self, since: float, to: str | None = None) -> int:
        q, a = "SELECT COALESCE(SUM(amount),0) FROM payouts WHERE state!=? AND created>=?", [FAILED, since]
        if to:
            q, a = q + " AND to_addr=?", a + [to]
        return int(self._db.execute(q, a).fetchone()[0])

    # -- moving it along --------------------------------------------------------------------
    def advance(self, pid: str, now: float | None = None) -> dict:
        with self._advance_lock:
            return self._advance(pid, now if now is not None else time.time())

    def _advance(self, pid: str, now: float) -> dict:
        p = self.get(pid)
        if p is None or p["state"] in (CONFIRMED, FAILED):
            return self._public(p)
        try:
            if p["state"] == CREATED:
                p = self._send(p, now)
            elif p["state"] == SENDING:
                p = self._check(p, now)
        except sol.RpcError as e:
            # Unreachable or ambiguous: change nothing, try again on the next call. Never resend.
            self._note(pid, f"rpc: {str(e)[:120]}", now)
            p = self.get(pid)
        return self._public(p)

    def _send(self, p: dict, now: float) -> dict:
        if self.paused:
            return p  # waits, unsent; nothing was signed
        if self.rpc.token_balance(self.source) < p["amount"]:
            return self._set(p["id"], FAILED, now, error="insufficient_balance")
        bh, _ = self.rpc.blockhash()
        msg = sol.transfer_message(self.pubkey, p["to_addr"], self.mint, self.decimals,
                                   p["amount"], bh, self.token_program)
        tx, signature = sol.sign_transaction(msg, self.key)
        # Written down BEFORE sending: if we crash between here and the network, the signature
        # is known and `_check` can find out whether it landed.
        self._set(p["id"], SENDING, now, signature=signature, blockhash=bh, attempts=p["attempts"] + 1)
        try:
            self.rpc.send(tx)
        except sol.RpcError as e:
            data = getattr(e, "data", None)
            if data is not None and data.get("code") in (-32002, -32003):
                # The node simulated it and said no: it was never accepted, so it cannot land.
                return self._set(p["id"], FAILED, now, error=f"rejected: {str(e)[:100]}")
            # Anything else is ambiguous (timeout, node error): stay in `sending` and ask later.
        return self.get(p["id"])

    def _check(self, p: dict, now: float) -> dict:
        st = self.rpc.status(p["signature"])
        if st is not None:
            if st.get("err") is not None:
                return self._set(p["id"], FAILED, now, error=f"onchain: {st['err']}")
            if st.get("confirmationStatus") in ("confirmed", "finalized"):
                return self._set(p["id"], CONFIRMED, now)
            return p
        # Not seen. Only if the blockhash is KNOWN expired can it never land; then it is safe to
        # sign a fresh one. Unknown (None) means wait.
        if self.rpc.blockhash_valid(p["blockhash"]) is False and self.rpc.status(p["signature"]) is None:
            return self._set(p["id"], CREATED, now)
        return p

    def pending(self) -> list[str]:
        with self._lock:
            return [r[0] for r in self._db.execute("SELECT id FROM payouts WHERE state IN (?,?)", (CREATED, SENDING))]

    # -- storage helpers --------------------------------------------------------------------
    def _set(self, pid: str, state: str, now: float, **cols) -> dict:
        sets = ", ".join(f"{k}=?" for k in ("state", "updated", *cols))
        with self._lock, self._db:
            self._db.execute(f"UPDATE payouts SET {sets} WHERE id=?", (state, now, *cols.values(), pid))
        return self.get(pid)

    def _note(self, pid: str, error: str, now: float) -> None:
        with self._lock, self._db:
            self._db.execute("UPDATE payouts SET error=?, updated=? WHERE id=?", (error, now, pid))

    @staticmethod
    def _public(p: dict | None) -> dict:
        if p is None:
            raise Refused("unknown_id")
        return {"id": p["id"], "state": p["state"], "signature": p["signature"],
                "to": p["to_addr"], "amount": p["amount"], "error": p["error"]}
