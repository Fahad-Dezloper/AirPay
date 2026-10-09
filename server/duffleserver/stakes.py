"""
Did this wallet stake SKR, and how much?

Read from the chain, never from the phone, the same way [swaps] reads a swap. A transaction counts
only if it succeeded, the wallet signed AND paid for it, and the SKR staking program is called at
the top level. The amount is then read off the wallet's own SKR balance: what it held before the
transaction less what it held after.

Both addresses were checked on mainnet before this was written: the program account is executable,
the mint is an SPL token with 6 decimals, and real "Stake" transactions show exactly this shape
(the signer's SKR falls, the program's vault rises by the same amount).

What this deliberately cannot see: whether the SKR STAYS staked. It proves a stake was made after
the offer was opened. Somebody can unstake later and keep the reward, so an offer built on this
should be priced as paying for the act of staking, once.
"""
from __future__ import annotations

from .verify import account_keys, token_held

SKR_STAKING = "SKRskrmtL83pcL4YqLWt6iPefDqwXQWHSw9S9vz94BZ"
SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
#: SKR has 6 decimals: this many base units to one whole SKR.
SKR_UNITS = 10 ** 6


def staked(result: dict | None, owner: str, program: str = SKR_STAKING, mint: str = SKR_MINT) -> int | None:
    """Base units of SKR that left `owner` in a staking transaction, or None if this is not one.

    An unstake or a reward claim calls the same program with the balance going the other way (or
    nowhere), so it comes back as None rather than as a stake of nothing.
    """
    if not result:
        return None
    try:
        meta, msg = result["meta"], result["transaction"]["message"]
        if meta.get("err") is not None:
            return None
        keys = account_keys(result)
        # The wallet must have signed and paid: a stake somebody else made is not this wallet's.
        if not keys or keys[0] != owner:
            return None
        if program not in [keys[i["programIdIndex"]] for i in msg["instructions"]]:
            return None
        # Summed over every SKR account the wallet owns, so moving SKR between two of its own
        # accounts in the same transaction nets to nothing.
        fell = token_held(meta.get("preTokenBalances"), owner, mint) \
            - token_held(meta.get("postTokenBalances"), owner, mint)
        return fell if fell > 0 else None
    except (KeyError, IndexError, TypeError, ValueError):
        return None
