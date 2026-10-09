"""
Did this wallet make a swap on Jupiter, and of what?

Read from the chain, never from the phone. A transaction counts only if it succeeded, the wallet
signed AND paid for it, and a Jupiter route instruction is called at the top level. Then the swap
is read off the wallet's own balance changes: what it lost and what it gained.

What this deliberately cannot see: *which app* built the transaction. Jupiter's website, its
mobile app and any other front end that routes through Jupiter produce the same instructions, so an
offer can honestly say "swap on Jupiter" and never "swap in Jupiter Mobile".

The same reading covers liquid staking. Staking SOL with Jito or Marinade is, on the wallet's own
balances, SOL out and a staking token in, with the pool's program called at the top level. What it
cannot see is whether the token is KEPT: somebody can sell it the next minute, so an offer built on
this pays for the act of staking, once.
"""
from __future__ import annotations

from dataclasses import dataclass

from .verify import account_keys

JUPITER_V6 = "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4"
WSOL = "So11111111111111111111111111111111111111112"
USDC = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
USDT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
EURC = "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr"
PYUSD = "2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo"
#: Dollar-pegged (or euro-pegged) assets. A swap between two of these moves no market risk and
#: costs almost nothing, so it must never be what an offer pays for.
STABLES = {USDC, USDT, EURC, PYUSD}

# Liquid staking, read with the same balance arithmetic as a swap: SOL leaves the wallet and a
# staking token arrives. All four addresses were checked on mainnet before being written here:
# the two programs are executable, the two mints are owned by the token program with 9 decimals,
# and the Jito pool account is owned by the stake pool program.
#: The shared SPL Stake Pool program. Many pools run on it, Jito's among them, so calling it
#: proves nothing by itself: an offer must also name the token that has to arrive.
SPL_STAKE_POOL = "SPoo1Ku8WFXoNDMHPsrGSTSG1Y47rzgn41SLUNakuHy"
JITO_POOL = "Jito4APyf642JPZPx3hGc6WWJ8zPKtRbRs4P815Awbb"
JITOSOL = "J1toso1uCk3RLmjorhTtrVwY9HJ7X8V9yYac6Y7kGCPn"
MARINADE = "MarBmsSgKXdrN1egZf5sqe1TMai9K1rChYNDJgjq7aD"
MSOL = "mSoLzYCxHdYgdzU16g5QSh3i5K3z3KZK7ytfqcJm7So"
#: What a person calls each token an offer can ask them to end up with.
TOKEN_NAMES = {USDC: "USDC", JITOSOL: "JitoSOL", MSOL: "mSOL", WSOL: "SOL"}


def receive_mint(receive) -> str | None:
    """The mint an offer says has to arrive. "USDC" is the older spelling and still means USDC."""
    if not receive:
        return None
    return USDC if receive == "USDC" else str(receive)


def programs_of(program) -> tuple[str, ...]:
    """An offer names one program or a short list of them; either way, the ones that count."""
    if isinstance(program, (list, tuple)):
        return tuple(str(p) for p in program if p)
    return (str(program),) if program else (JUPITER_V6,)
#: Anything below this on the SOL leg is the rent of a token account created in the same
#: transaction, not part of the trade.
SOL_DUST = 5_000_000


@dataclass(frozen=True)
class Swap:
    sold: str
    sold_units: int
    bought: str
    bought_units: int


def _token_delta(meta: dict, owner: str) -> dict[str, int]:
    out: dict[str, int] = {}
    for side, sign in (("preTokenBalances", -1), ("postTokenBalances", 1)):
        for b in meta.get(side) or []:
            if b.get("owner") == owner:
                out[b["mint"]] = out.get(b["mint"], 0) + sign * int(b["uiTokenAmount"]["amount"])
    return out


def swap_of(result: dict | None, owner: str, program=JUPITER_V6) -> Swap | None:
    """The swap `owner` made in this transaction, or None if it is not a clean one.

    `program` is one address or several. Several is for an offer that can be met in more than one
    place, such as staking with a pool directly or buying the pool's token on Jupiter.
    """
    if not result:
        return None
    try:
        meta, msg = result["meta"], result["transaction"]["message"]
        if meta.get("err") is not None:
            return None
        keys = account_keys(result)
        # The wallet must have signed and paid: a swap somebody else made *with* our wallet's
        # address in it is not our wallet's swap.
        if not keys or keys[0] != owner:
            return None
        called = {keys[i["programIdIndex"]] for i in msg["instructions"]}
        if not called.intersection(programs_of(program)):
            return None
        delta = _token_delta(meta, owner)
        # SOL is held both natively and as wrapped SOL, and a swap moves it between the two; the
        # wallet's real SOL change is the sum, with the network fee added back (it paid that).
        native = int(meta["postBalances"][0]) - int(meta["preBalances"][0]) + int(meta.get("fee", 0))
        total_sol = native + delta.pop(WSOL, 0)
        if abs(total_sol) >= SOL_DUST:
            delta[WSOL] = total_sol
        legs = {m: d for m, d in delta.items() if d != 0}
        lost = {m: -d for m, d in legs.items() if d < 0}
        gained = {m: d for m, d in legs.items() if d > 0}
        if len(lost) != 1 or len(gained) != 1:
            return None          # not a plain A-for-B swap: ambiguous legs are never paid on
        (sm, su), (bm, bu) = next(iter(lost.items())), next(iter(gained.items()))
        return Swap(sm, su, bm, bu)
    except (KeyError, IndexError, TypeError, ValueError):
        return None


def refusal(swap: Swap) -> str | None:
    """Why a real swap still does not count, or None if it does."""
    if swap.sold in STABLES and swap.bought in STABLES:
        return "stable_swap"
    return None


def usd_value(swap: Swap, sol_usd: float | None) -> float | None:
    """Dollar value of the swap, from whichever leg we can price: a stablecoin is a dollar, SOL
    comes from the price feed. A swap between two tokens we cannot price is None — *unknown*, which
    the caller retries, rather than a guess that pays or refuses."""
    for mint, units in ((swap.sold, swap.sold_units), (swap.bought, swap.bought_units)):
        if mint in STABLES:
            return units / 1e6
        if mint == WSOL and sol_usd:
            return units / 1e9 * sol_usd
    return None
