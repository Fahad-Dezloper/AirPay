"""Telling people about money moving in their own wallets.

The server used to hear only about AirPay requests (a reference it was asked to watch). A
transfer made in Phantom, or received from an exchange, left the phone silent. This looks at
every wallet a phone has registered for push and describes what changed in it.

Pure on purpose: [describe] takes a fetched transaction and an address and says what happened to
that address — no network, so it can be tested against a recorded transaction.
"""
from __future__ import annotations

from decimal import Decimal

from . import verify

#: Mints we can name without asking the chain. Anything else is shown by its first four letters.
KNOWN = {
    "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v": "USDC",
    "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB": "USDT",
    "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr": "EURC",
    "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3": "SKR",
}

#: Smaller SOL moves than this are rent and dust, not news.
MIN_LAMPORTS = 1_000


def _fmt(units: int, decimals: int) -> str:
    d = Decimal(abs(units)).scaleb(-decimals)
    s = f"{d:.6f}".rstrip("0").rstrip(".")
    return s or "0"


def describe(tx: dict, address: str) -> list[dict]:
    """What this transaction did to `address`: one entry per asset that changed.

    Each is {"direction": "in"|"out", "amount": "0.5", "symbol": "SOL"}. A failed transaction
    changed nothing, so it describes nothing.
    """
    meta = tx.get("meta") or {}
    if meta.get("err") is not None:
        return []
    out: list[dict] = []
    keys = verify.account_keys(tx)
    if address in keys:
        i = keys.index(address)
        delta = meta["postBalances"][i] - meta["preBalances"][i]
        # The fee comes out of the payer's balance; a token transfer is not a SOL send.
        if i == 0:
            delta += meta.get("fee", 0)
        if abs(delta) >= MIN_LAMPORTS:
            out.append({"direction": "in" if delta > 0 else "out", "amount": _fmt(delta, 9), "symbol": "SOL"})
    mints: dict[str, int] = {}
    decimals: dict[str, int] = {}
    for side, sign in (("preTokenBalances", -1), ("postTokenBalances", 1)):
        for b in meta.get(side) or []:
            if isinstance(b, dict) and b.get("owner") == address and b.get("mint"):
                amt = b.get("uiTokenAmount") or {}
                mints[b["mint"]] = mints.get(b["mint"], 0) + sign * int(amt.get("amount", 0))
                decimals[b["mint"]] = int(amt.get("decimals", 0))
    for mint, delta in mints.items():
        if delta:
            out.append({"direction": "in" if delta > 0 else "out", "amount": _fmt(delta, decimals[mint]),
                        "symbol": KNOWN.get(mint, mint[:4])})
    return out
