"""
The smallest slice of Solana this service needs: build, sign and read back one SPL-token transfer.

Hand-built on purpose. A payout service holds a hot key, so every dependency is attack surface;
the transaction here is two instructions, which is less code than a client library's imports.
Everything is checked against the chain (see tests and README), not trusted.
"""
from __future__ import annotations

import base64
import hashlib
import json
import struct
import urllib.error
import urllib.request

from nacl.signing import SigningKey

TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"
ATA_PROGRAM = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
SYSTEM_PROGRAM = "11111111111111111111111111111111"

_B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
_IDX = {c: i for i, c in enumerate(_B58)}


def b58decode(s: str) -> bytes:
    n = 0
    for ch in s:
        n = n * 58 + _IDX[ch]  # KeyError on a bad character; callers validate first
    raw = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return b"\x00" * (len(s) - len(s.lstrip("1"))) + raw


def b58encode(b: bytes) -> str:
    n = int.from_bytes(b, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = _B58[r] + out
    return "1" * (len(b) - len(b.lstrip(b"\x00"))) + out


def is_address(s: object) -> bool:
    if not isinstance(s, str) or not 32 <= len(s) <= 44 or any(c not in _IDX for c in s):
        return False
    return len(b58decode(s)) == 32


_P = 2 ** 255 - 19
_D = (-121665 * pow(121666, -1, _P)) % _P
_SQRT_M1 = pow(2, (_P - 1) // 4, _P)


def on_curve(point: bytes) -> bool:
    """Does this 32-byte value decompress to a point on the ed25519 curve?

    Solana's rule for "is this a valid public key, so NOT a program address" is exactly that —
    decompression succeeds — and it is looser than libsodium's validity check, which also demands
    the prime-order subgroup. Using libsodium's here derived the wrong token accounts, which the
    chain refused ("Associated address does not match seed derivation").
    """
    y = int.from_bytes(point, "little") & ((1 << 255) - 1)
    if y >= _P:
        return False
    u = (y * y - 1) % _P
    v = (_D * y * y + 1) % _P
    x = (u * pow(v, 3, _P) * pow(u * pow(v, 7, _P), (_P - 5) // 8, _P)) % _P
    vxx = (v * x * x) % _P
    if vxx == u % _P:
        return True
    if vxx == (-u) % _P:
        return True  # x * sqrt(-1) is the root; a root exists, so the point is on the curve
    return False


def find_pda(seeds: list[bytes], program: str) -> bytes:
    """Program-derived address: the first bump, counting down from 255, that is OFF the curve."""
    prog = b58decode(program)
    for bump in range(255, -1, -1):
        h = hashlib.sha256(b"".join(seeds) + bytes([bump]) + prog + b"ProgramDerivedAddress").digest()
        if not on_curve(h):
            return h
    raise ValueError("no program address found")


def ata(owner: str, mint: str, token_program: str) -> str:
    return b58encode(find_pda([b58decode(owner), b58decode(token_program), b58decode(mint)], ATA_PROGRAM))


def compact_u16(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _ix(program_idx: int, accounts: list[int], data: bytes) -> bytes:
    return bytes([program_idx]) + compact_u16(len(accounts)) + bytes(accounts) + compact_u16(len(data)) + data


def transfer_message(payer: str, to_owner: str, mint: str, decimals: int, amount: int,
                     blockhash: str, token_program: str) -> bytes:
    """Legacy message: (1) create the recipient's token account if it is missing — idempotent, so
    harmless when it exists — and (2) a *checked* transfer, which makes the token program verify
    the mint and decimals itself rather than trusting what we say."""
    if payer == to_owner:
        raise ValueError("cannot pay oneself")
    if not 0 < amount < 2 ** 64:
        raise ValueError("amount out of range")
    src = ata(payer, mint, token_program)
    dst = ata(to_owner, mint, token_program)
    # Order is fixed by the format: writable signers, readonly signers, writable others, readonly
    # others. Here: payer | src, dst | owner, mint, system, token, ata-program.
    keys = [payer, src, dst, to_owner, mint, SYSTEM_PROGRAM, token_program, ATA_PROGRAM]
    header = bytes([1, 0, 5])
    create = _ix(7, [0, 2, 3, 4, 5, 6], bytes([1]))
    xfer = _ix(6, [1, 4, 2, 0], bytes([12]) + struct.pack("<QB", amount, decimals))
    return (header + compact_u16(len(keys)) + b"".join(b58decode(k) for k in keys)
            + b58decode(blockhash) + compact_u16(2) + create + xfer)


def sign_transaction(message: bytes, key: SigningKey) -> tuple[bytes, str]:
    """The wire transaction and its signature (which is also its id)."""
    sig = key.sign(message).signature
    return compact_u16(1) + sig + message, b58encode(sig)


class RpcError(Exception):
    pass


class Rpc:
    def __init__(self, url: str, timeout: float = 15.0):
        self.url, self.timeout = url, timeout

    def call(self, method: str, params: list):
        body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
        req = urllib.request.Request(self.url, body, {"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                doc = json.loads(r.read())
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
            raise RpcError(f"unreachable: {e}") from None
        if "error" in doc:
            err = RpcError(doc["error"].get("message", "rpc error"))
            err.data = doc["error"]
            raise err
        return doc.get("result")

    def blockhash(self) -> tuple[str, int]:
        v = self.call("getLatestBlockhash", [{"commitment": "confirmed"}])["value"]
        return v["blockhash"], int(v["lastValidBlockHeight"])

    def send(self, tx: bytes) -> str:
        return self.call("sendTransaction", [base64.b64encode(tx).decode(),
                                             {"encoding": "base64", "preflightCommitment": "confirmed",
                                              "maxRetries": 5}])

    def status(self, signature: str) -> dict | None:
        v = self.call("getSignatureStatuses", [[signature], {"searchTransactionHistory": True}])["value"]
        return v[0]

    def blockhash_valid(self, blockhash: str) -> bool | None:
        try:
            return bool(self.call("isBlockhashValid", [blockhash, {"commitment": "confirmed"}])["value"])
        except RpcError:
            return None  # unknown is not "expired": the caller must not resend on a guess

    def mint_info(self, mint: str) -> dict:
        v = self.call("getAccountInfo", [mint, {"encoding": "jsonParsed"}])["value"]
        if v is None:
            raise RpcError("mint not found")
        parsed = v["data"]["parsed"]
        if parsed.get("type") != "mint":
            raise RpcError("not a mint")
        return {"owner": v["owner"], "decimals": int(parsed["info"]["decimals"]),
                "extensions": bool(parsed["info"].get("extensions")), "supply": parsed["info"]["supply"]}

    def token_balance(self, token_account: str) -> int:
        try:
            return int(self.call("getTokenAccountBalance", [token_account])["value"]["amount"])
        except RpcError as e:
            if "could not find account" in str(e).lower() or "invalid param" in str(e).lower():
                return 0
            raise
