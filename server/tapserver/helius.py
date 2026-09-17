"""
Helius webhooks, used as a hint and nothing more.

The webhook watches recipient addresses — one per user, so the list changes rarely — and when
it fires we learn a signature worth looking at. We then fetch that transaction from OUR RPC and
verify it ourselves. So a forged, replayed or malformed webhook can at worst make us check
early; it can never mark a request paid. Helius documents that it may deliver the same event
more than once, which the same design absorbs for free.
"""
from __future__ import annotations

import json
import logging
import threading
import time
import urllib.request

log = logging.getLogger("tap.helius")

API = "https://api.helius.xyz/v0/webhooks"


def signatures_in(payload) -> list[str]:
    """Pull candidate signatures out of a webhook body without trusting its shape."""
    items = payload if isinstance(payload, list) else [payload]
    out: list[str] = []
    for tx in items:
        if not isinstance(tx, dict):
            continue
        sig = tx.get("signature")
        if not sig:
            sigs = (tx.get("transaction") or {}).get("signatures") or []
            sig = sigs[0] if sigs else None
        if isinstance(sig, str) and 60 <= len(sig) <= 100 and sig not in out:
            out.append(sig)
    return out[:100]


def webhook_body(public_url: str, addresses: list[str], webhook_type: str, auth: str | None) -> dict:
    body = {
        "webhookURL": public_url.rstrip("/") + "/v1/helius",
        "transactionTypes": ["ANY"],
        "accountAddresses": addresses,
        "webhookType": webhook_type,
    }
    if auth:
        body["authHeader"] = auth
    return body


class AddressSync:
    """Keeps the webhook's address list equal to the set of known recipients.

    Batched: registering a hundred requests from one user is one address, and a burst of new
    users is one PUT every few seconds rather than one per registration."""

    def __init__(self, store, api_key, webhook_id, public_url, webhook_type="rawDevnet", auth=None, every=10.0):
        self.store = store
        self.api_key, self.webhook_id, self.public_url = api_key, webhook_id, public_url
        self.webhook_type, self.auth, self.every = webhook_type, auth, every
        self._dirty = threading.Event()
        self._synced: list[str] = []

    @property
    def enabled(self) -> bool:
        return bool(self.api_key and self.webhook_id and self.public_url)

    def touch(self) -> None:
        if self.enabled:
            self._dirty.set()

    def start(self) -> None:
        if self.enabled:
            threading.Thread(target=self._loop, name="helius-sync", daemon=True).start()
            self._dirty.set()

    def _loop(self) -> None:
        while True:
            self._dirty.wait()
            time.sleep(self.every)
            self._dirty.clear()
            try:
                self.sync_once()
            except Exception as e:
                log.warning("webhook address sync failed, will retry: %s", e)
                self._dirty.set()

    def sync_once(self) -> bool:
        addresses = self.store.recipients()
        if addresses == self._synced:
            return False
        body = json.dumps(webhook_body(self.public_url, addresses, self.webhook_type, self.auth)).encode()
        req = urllib.request.Request(
            f"{API}/{self.webhook_id}?api-key={self.api_key}", body, {"Content-Type": "application/json"}, method="PUT"
        )
        urllib.request.urlopen(req, timeout=20).read()
        self._synced = addresses
        log.info("webhook now watches %d address(es)", len(addresses))
        return True
