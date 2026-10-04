"""
Pays the network fee for somebody who holds USDC and no SOL, and is paid back in USDC.

The buyer's phone builds a transaction whose fee payer is this service's own small wallet, signs
its half, and sends it here. This adds the second signature and submits it. The buyer never needs
SOL; the same transaction moves a small USDC fee to this wallet's token account.

This signs with a funded key something a stranger wrote, so the whole file is one question: what
is the worst a transaction can do to this wallet if it is signed? The answer has to be "spend one
network fee". [check] makes that true by refusing everything that is not exactly:

  * a legacy transaction with two signers, this wallet first and the buyer second, already
    signed by the buyer;
  * made only of USDC TransferChecked instructions authorised by the buyer (and, optionally, a
    compute budget with a capped tip);
  * where this wallet appears NOWHERE inside an instruction, so nothing can move its SOL or its
    tokens, close its accounts or reassign it;
  * and where one of the transfers pays at least the fee into this wallet's own USDC account.

It is submitted with preflight on, so a transaction that would fail is never broadcast and costs
nothing. Per-wallet and daily counts bound what is left.
"""
from __future__ import annotations

import base64
import sqlite3
import struct
import threading
import time

from nacl.exceptions import BadSignatureError
from nacl.signing import SigningKey, VerifyKey

from . import solana as sol
from .engine import Refused

USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
COMPUTE_BUDGET = "ComputeBudget111111111111111111111111111111"
#: The most a transaction may tip validators, on top of the base fee. This wallet pays it.
MAX_TIP_LAMPORTS = 20_000
#: What the cap assumes when a transaction sets a price and no limit.
DEFAULT_UNITS = 1_400_000
MAX_TRANSFERS = 3
MAX_TX = 1232


def _read_u16(buf: bytes, at: int) -> tuple[int, int]:
    """A compact-u16 and where it ends."""
    n = shift = 0
    for _ in range(3):
        if at >= len(buf):
            raise Refused("malformed")
        b = buf[at]
        at += 1
        n |= (b & 0x7F) << shift
        if not b & 0x80:
            return n, at
        shift += 7
    raise Refused("malformed")


def check(tx: bytes, sponsor: str, fee_account: str, fee_units: int, user: str,
          mint: str = USDC_MINT) -> bytes:
    """The message to sign, or [Refused]. Pure: no key, no network. See the module docstring."""
    if not isinstance(tx, (bytes, bytearray)) or not 0 < len(tx) <= MAX_TX:
        raise Refused("malformed")
    nsig, at = _read_u16(tx, 0)
    if nsig != 2:
        raise Refused("wrong_signers")
    if len(tx) < at + 128 + 3:
        raise Refused("malformed")
    ours, theirs = tx[at:at + 64], tx[at + 64:at + 128]
    msg = tx[at + 128:]
    # A versioned message starts with its high bit set, and may load accounts from lookup tables
    # that are not in the message at all. Only the plain kind is read here.
    if msg[0] & 0x80:
        raise Refused("versioned")
    required = msg[0]
    if required != 2:
        raise Refused("wrong_signers")
    nkeys, p = _read_u16(msg, 3)
    if nkeys < 2 or nkeys > 32 or len(msg) < p + 32 * nkeys + 32:
        raise Refused("malformed")
    raw_keys = [msg[p + 32 * i:p + 32 * (i + 1)] for i in range(nkeys)]
    p += 32 * nkeys + 32  # the keys, then the blockhash
    if raw_keys[0] != sol.b58decode(sponsor) or raw_keys[1] != sol.b58decode(user):
        raise Refused("wrong_signers")
    if raw_keys[0] == raw_keys[1]:
        raise Refused("wrong_signers")
    # The buyer has really signed this exact message. Ours is still the empty slot.
    if ours != bytes(64):
        raise Refused("already_signed")
    try:
        VerifyKey(raw_keys[1]).verify(msg, theirs)
    except (BadSignatureError, ValueError):
        raise Refused("not_signed") from None

    token = sol.b58decode(sol.TOKEN_PROGRAM)
    budget = sol.b58decode(COMPUTE_BUDGET)
    mint_raw, fee_raw = sol.b58decode(mint), sol.b58decode(fee_account)
    count, p = _read_u16(msg, p)
    if not 1 <= count <= MAX_TRANSFERS + 2:
        raise Refused("unexpected_instruction")
    transfers = paid = 0
    units: int | None = None
    price = 0
    for _ in range(count):
        if p >= len(msg):
            raise Refused("malformed")
        prog = msg[p]
        p += 1
        nacc, p = _read_u16(msg, p)
        accounts = list(msg[p:p + nacc])
        p += nacc
        ndata, p = _read_u16(msg, p)
        data = msg[p:p + ndata]
        p += ndata
        if len(accounts) != nacc or len(data) != ndata or prog >= nkeys or any(a >= nkeys for a in accounts):
            raise Refused("malformed")
        # The rule the rest leans on: this wallet is the fee payer and nothing else. An instruction
        # that so much as names it is refused, whatever the instruction is.
        if 0 in accounts or prog == 0:
            raise Refused("touches_sponsor")
        if raw_keys[prog] == budget:
            if not accounts and len(data) == 5 and data[0] == 2:      # SetComputeUnitLimit
                units = struct.unpack("<I", data[1:])[0]
            elif not accounts and len(data) == 9 and data[0] == 3:    # SetComputeUnitPrice
                price = struct.unpack("<Q", data[1:])[0]
            else:
                raise Refused("unexpected_instruction")
            continue
        if raw_keys[prog] != token:
            raise Refused("unexpected_instruction")
        # TransferChecked and nothing else: source, mint, destination, authority (+ read-only
        # extras, which is where a payment reference rides).
        if len(data) != 10 or data[0] != 12 or len(accounts) < 4:
            raise Refused("unexpected_instruction")
        amount, decimals = struct.unpack("<QB", data[1:])
        src, mint_i, dst, auth = accounts[:4]
        if auth != 1 or raw_keys[mint_i] != mint_raw or decimals != 6 or amount <= 0:
            raise Refused("unexpected_instruction")
        transfers += 1
        if raw_keys[dst] == fee_raw:
            paid += amount
    # All of it was read: nothing is hiding after the last instruction.
    if p != len(msg):
        raise Refused("malformed")
    if not 1 <= transfers <= MAX_TRANSFERS:
        raise Refused("unexpected_instruction")
    if price and (units if units is not None else DEFAULT_UNITS) * price // 1_000_000 > MAX_TIP_LAMPORTS:
        raise Refused("tip_too_high")
    if paid < fee_units:
        raise Refused("fee_missing")
    return bytes(msg)


def create_account_message(owner: str, mint: str, blockhash: str) -> bytes:
    """Creates [owner]'s own token account for [mint], paid for by [owner]. Idempotent."""
    account = sol.ata(owner, mint, sol.TOKEN_PROGRAM)
    keys = [owner, account, mint, sol.SYSTEM_PROGRAM, sol.TOKEN_PROGRAM, sol.ATA_PROGRAM]
    ix = bytes([5]) + sol.compact_u16(6) + bytes([0, 1, 0, 2, 3, 4]) + sol.compact_u16(1) + bytes([1])
    return (bytes([1, 0, 4]) + sol.compact_u16(len(keys)) + b"".join(sol.b58decode(k) for k in keys)
            + sol.b58decode(blockhash) + sol.compact_u16(1) + ix)


class Sponsor:
    """The fee wallet: its key, its limits and a record of what it signed."""

    def __init__(self, db_path: str, key: SigningKey, rpc, fee_units: int = 10_000,
                 per_user_day: int = 20, per_day: int = 2_000, mint: str = USDC_MINT):
        self.key, self.rpc, self.mint = key, rpc, mint
        self.pubkey = sol.b58encode(bytes(key.verify_key))
        self.fee_account = sol.ata(self.pubkey, mint, sol.TOKEN_PROGRAM)
        self.fee_units, self.per_user_day, self.per_day = fee_units, per_user_day, per_day
        self._lock = threading.Lock()
        self._db = sqlite3.connect(db_path, check_same_thread=False)
        with self._db:
            self._db.execute("CREATE TABLE IF NOT EXISTS sponsored ("
                             "signature TEXT PRIMARY KEY, user TEXT NOT NULL, at REAL NOT NULL)")
            self._db.execute("CREATE INDEX IF NOT EXISTS sponsored_user ON sponsored(user, at)")
        self._ready = False
        self._tried = 0.0

    def lamports(self) -> int:
        return int(self.rpc.call("getBalance", [self.pubkey, {"commitment": "confirmed"}])["value"])

    def ready(self) -> bool:
        """True once the fee has somewhere to land. The account is made once, from this wallet's
        own SOL, the first time it is funded; until then nothing is sponsored, because a
        transaction paying a fee into a missing account would fail."""
        if self._ready:
            return True
        try:
            if self.rpc.call("getAccountInfo", [self.fee_account, {"encoding": "base64"}])["value"] is None:
                # Asked for at most once a minute: every attempt is a real transaction with a
                # real fee, and this is called on each request until the account exists.
                if time.time() - self._tried < 60 or self.lamports() < 3_000_000:
                    return False
                self._tried = time.time()
                blockhash, _ = self.rpc.blockhash()
                tx, _sig = sol.sign_transaction(create_account_message(self.pubkey, self.mint, blockhash), self.key)
                self.rpc.send(tx)
                return False  # true on a later look, once it has landed
            self._ready = True
        except (sol.RpcError, KeyError, TypeError):
            return False
        return True

    def info(self) -> dict:
        ready = self.ready()
        return {"enabled": ready, "fee_payer": self.pubkey, "fee_account": self.fee_account,
                "fee_units": self.fee_units}

    def _count(self, since: float, user: str | None = None) -> int:
        q, args = "SELECT COUNT(*) FROM sponsored WHERE at >= ?", [since]
        if user:
            q, args = q + " AND user = ?", args + [user]
        return int(self._db.execute(q, args).fetchone()[0])

    def submit(self, tx_b64: str, user: str, now: float | None = None) -> dict:
        now = now if now is not None else time.time()
        if not sol.is_address(user) or not isinstance(tx_b64, str):
            raise Refused("malformed")
        try:
            tx = base64.b64decode(tx_b64, validate=True)
        except ValueError:
            raise Refused("malformed") from None
        if not self.ready():
            raise Refused("sponsor_not_ready")
        msg = check(tx, self.pubkey, self.fee_account, self.fee_units, user, self.mint)
        with self._lock:
            day = now - 86_400
            if self._count(day, user) >= self.per_user_day or self._count(day) >= self.per_day:
                raise Refused("sponsor_limit")
            ours = self.key.sign(msg).signature
            signed = tx[:1] + ours + tx[65:]
            signature = sol.b58encode(ours)
            try:
                # Preflight is on: a transaction that would fail is refused by the node and never
                # reaches the chain, so it costs this wallet nothing.
                self.rpc.send(signed)
            except sol.RpcError as e:
                raise Refused("rejected", str(e)[:200]) from None
            with self._db:
                self._db.execute("INSERT OR IGNORE INTO sponsored(signature, user, at) VALUES (?,?,?)",
                                 (signature, user, now))
        return {"signature": signature}
