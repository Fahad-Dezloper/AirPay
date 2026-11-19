"""
The parts of AirPay every request shares: who is signed in, and how a phone is told.

Recharges themselves live in topups.py, help with one in support.py.
"""
from __future__ import annotations

import logging
import os
import re

from .auth import Auth
from .notify import Fcm
from .store import Store

log = logging.getLogger("duffle.service")

_ADDRESS = re.compile(r"^[1-9A-HJ-NP-Za-km-z]{32,44}$")


def is_address(s: object) -> bool:
    """Shape only: 32 to 44 base58 characters."""
    return isinstance(s, str) and bool(_ADDRESS.match(s))


class Service:
    def __init__(self, store: Store, rpc, fcm: Fcm | None = None):
        self.store = store
        #: Proves who is calling. See auth.py. The domain is the one the wallet shows.
        self.auth = Auth(store, domain=os.environ.get("AUTH_DOMAIN", "www.airpayy.com"))
        #: Solana reads and sends for the phones, through our provider. See web.py /v1/rpc.
        self.rpc = rpc
        self.fcm = fcm or Fcm(None, None)

    def sign_in(self, nonce: str, signature_b64: str) -> dict:
        return self.auth.verify(nonce, signature_b64)

    def add_device(self, recipient: str, token: str) -> None:
        if not is_address(recipient):
            raise ValueError("invalid recipient")
        if not isinstance(token, str) or not (20 <= len(token) <= 4096):
            raise ValueError("invalid push token")
        self.store.add_device(recipient, token)

    def _deliver(self, to: str, event: dict) -> int:
        """Push to every phone registered for `to`. Returns how many were sent."""
        sent = 0
        if self.fcm.enabled:
            for token in self.store.devices(to):
                result = self.fcm.send(token, event)
                if result == "gone":
                    self.store.drop_device(token)
                sent += result == "ok"
        log.info("%s → %s… (%d sent)", event.get("type"), to[:8], sent)
        return sent
