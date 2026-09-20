"""
Is this wallet a real Seeker, and how old is it? Asked of the chain, by us.

The app checks Genesis too, for the badge. This is a separate check on purpose: the app's
answer arrives over the network from the phone that stands to be paid, and a claim from an
interested party is not evidence. Rewards use only what this module reads itself.

Genesis Tokens live on **mainnet** while payments run on devnet, so this holds two endpoints.
Both answers are cached: the same wallet is checked on every payment it makes, and neither
answer changes minute to minute.
"""
from __future__ import annotations

import logging
import threading
import time

from .chain import Offline, RpcError

log = logging.getLogger("duffle.genesis")

#: Mints every Seeker Genesis Token. Each device has its own mint; this is what they share.
MINT_AUTHORITY = "GT2zuHVaZQYZSyQMgJPLzvkmyztfyXg2NJunqFp4p3A4"
TOKEN_2022 = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"

SEEKER_TTL = 6 * 3600.0
AGE_TTL = 3600.0


class Chainfacts:
    """Facts about a wallet, read from the chain and remembered for a while."""

    def __init__(self, mainnet, devnet, now=time.time):
        #: An Rpc pointed at mainnet, where Genesis lives.
        self.mainnet = mainnet
        #: An Rpc pointed at the cluster payments run on.
        self.devnet = devnet
        self._now = now
        self._seeker: dict[str, tuple[float, bool]] = {}
        self._age: dict[str, tuple[float, float | None]] = {}
        self._lock = threading.Lock()

    def _cached(self, box: dict, key: str, ttl: float):
        with self._lock:
            hit = box.get(key)
        if hit and self._now() - hit[0] < ttl:
            return hit[1]
        return None

    def _keep(self, box: dict, key: str, value) -> None:
        with self._lock:
            box[key] = (self._now(), value)

    def is_seeker(self, owner: str) -> bool | None:
        """True, False, or **None when the chain could not be asked** — which callers must
        treat as "unknown", never as "no". A reward refused because mainnet was down should be
        retried, not written off."""
        hit = self._cached(self._seeker, owner, SEEKER_TTL)
        if hit is not None:
            return hit
        try:
            accounts = self.mainnet.call(
                "getTokenAccountsByOwner", [owner, {"programId": TOKEN_2022}, {"encoding": "jsonParsed"}]
            )
        except (Offline, RpcError) as e:
            log.info("genesis check for %s… deferred: %s", owner[:8], e)
            return None
        held = False
        for entry in (accounts or {}).get("value", []):
            try:
                info = entry["account"]["data"]["parsed"]["info"]
                amount = info["tokenAmount"]
                if amount.get("decimals") != 0 or amount.get("amount") != "1":
                    continue  # not the shape of a per-device credential
                mint = self.mainnet.call("getAccountInfo", [info["mint"], {"encoding": "jsonParsed"}])
                minted = (mint or {}).get("value", {})["data"]["parsed"]["info"]
            except (Offline, RpcError) as e:
                log.info("genesis mint read deferred: %s", e)
                return None
            except (KeyError, TypeError):
                continue
            if MINT_AUTHORITY in (minted.get("mintAuthority"), minted.get("freezeAuthority")):
                held = True
                break
        self._keep(self._seeker, owner, held)
        return held

    def first_seen(self, address: str) -> float | None:
        """When this wallet first did anything on-chain, or None if never — and None if the
        chain could not be asked, which the rules already treat as "too new to earn"."""
        hit = self._cached(self._age, address, AGE_TTL)
        if hit is not None:
            return hit
        try:
            # Newest first, so the last entry of the page is the oldest we can see. A wallet
            # with more than a page of history is old enough either way.
            sigs = self.devnet.call("getSignaturesForAddress", [address, {"limit": 1000}])
        except (Offline, RpcError) as e:
            log.info("wallet age for %s… deferred: %s", address[:8], e)
            return None
        oldest = None
        for entry in reversed(sigs or []):
            if isinstance(entry, dict) and entry.get("blockTime"):
                oldest = float(entry["blockTime"])
                break
        self._keep(self._age, address, oldest)
        return oldest
