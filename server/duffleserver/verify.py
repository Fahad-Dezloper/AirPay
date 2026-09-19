"""
The one question that turns a request green: did this transaction pay it, in full?

Mirrors Chain.verifyTransfer in the Android app line for line, and is tested against the same
real devnet transaction. The two must never disagree — if the server said "paid" and the
phone said "short", the payee would see a notification the app then contradicts.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation

_B58 = re.compile(r"^[1-9A-HJ-NP-Za-km-z]{32,44}$")


def is_address(s: object) -> bool:
    return isinstance(s, str) and bool(_B58.match(s))


SOL = "SOL"
MAX_DECIMALS = 12


def lamports(amount: str) -> int | None:
    return units(amount, 9)


def units(amount: str, decimals: int) -> int | None:
    """Exact amount -> the token's smallest unit (lamports for SOL), or None when it cannot be
    paid. Never through a float: 0.00013 * 1e9 truncates to 129999, and 1,710 of the 99,999
    five-decimal amounts under 1 SOL do the same."""
    try:
        d = Decimal(str(amount).strip())
    except InvalidOperation:
        return None
    if not d.is_finite() or not isinstance(decimals, int) or not 0 <= decimals <= MAX_DECIMALS:
        return None
    v = d.scaleb(decimals)
    if v != v.to_integral_value():
        return None
    n = int(v)
    return n if n > 0 else None


@dataclass(frozen=True)
class Verdict:
    kind: str  # "paid" | "pending" | "rejected"
    why: str = ""


PAID = Verdict("paid")
PENDING = Verdict("pending")


def account_keys(result: dict) -> list[str]:
    """Keys in the order balances are reported: static, then lookup-table writable, readonly."""
    out: list[str] = []
    for k in result["transaction"]["message"]["accountKeys"]:
        out.append(k["pubkey"] if isinstance(k, dict) else k)
    loaded = (result.get("meta") or {}).get("loadedAddresses") or {}
    out.extend(loaded.get("writable") or [])
    out.extend(loaded.get("readonly") or [])
    return out


def token_held(balances: list | None, owner: str, mint: str) -> int:
    """All of `mint` that `owner` holds across the accounts a transaction touched. A token
    account this transaction created has no 'pre' entry, which correctly counts as 0."""
    total = 0
    for b in balances or []:
        if isinstance(b, dict) and b.get("owner") == owner and b.get("mint") == mint:
            total += int(b["uiTokenAmount"]["amount"])
    return total


def verify_transfer(result: dict | None, reference: str, recipient: str, want: int,
                    mint: str | None = None) -> Verdict:
    """`result` is the `result` field of a getTransaction response (None if not yet served).

    The reference only FINDS candidates. What matters is the recipient's balance going up by
    at least the amount asked — a check that also survives wallets rewriting what they sign
    (Seeker Wallet added two ComputeBudget instructions to a transaction Tap built)."""
    if result is None:
        return PENDING
    try:
        meta = result["meta"]
        keys = account_keys(result)
        if meta.get("err") is not None:
            return Verdict("rejected", "that transaction failed on-chain")
        if reference not in keys:
            return Verdict("rejected", "that transaction is not for this request")
        if mint is not None:
            # Tokens land in the recipient's token account, not their wallet, so their own key
            # need not appear. What counts is the token balance they own, of this mint only —
            # the right amount of a different token pays nothing.
            got = token_held(meta.get("postTokenBalances"), recipient, mint) - \
                token_held(meta.get("preTokenBalances"), recipient, mint)
            return PAID if got >= want else Verdict("rejected", f"paid {got} of {want} token units")
        if recipient not in keys:
            return Verdict("rejected", "that transaction did not pay this address")
        i = keys.index(recipient)
        got = int(meta["postBalances"][i]) - int(meta["preBalances"][i])
        return PAID if got >= want else Verdict("rejected", f"paid {got} of {want} lamports")
    except (KeyError, IndexError, TypeError, ValueError):
        return Verdict("rejected", "could not read that transaction")


def successful_signatures(result: list | None) -> list[str]:
    return [e["signature"] for e in (result or []) if isinstance(e, dict) and e.get("err") is None and "signature" in e]
