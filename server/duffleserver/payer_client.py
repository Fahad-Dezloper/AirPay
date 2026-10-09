"""
Asks the SKR payer to pay somebody. The payer is a separate service with its own key and caps;
this is the only thing the main server can do to it.

Every call is signed with a shared secret over method, path, time and body, so the payer can tell
it is us and that nobody altered the request on the way. The secret is a mounted file, not an
environment variable.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import time
import urllib.error
import urllib.request


class PayerError(Exception):
    """The payer refused or could not be reached. `code` is the payer's stable reason, if any."""

    def __init__(self, code: str, retryable: bool = False):
        super().__init__(code)
        self.code, self.retryable = code, retryable


def sign(secret: bytes, method: str, path: str, ts: str, body: bytes) -> str:
    msg = "\n".join((ts, method.upper(), path, hashlib.sha256(body).hexdigest())).encode()
    return hmac.new(secret, msg, hashlib.sha256).hexdigest()


class Payer:
    def __init__(self, url: str | None, secret_file: str | None, timeout: float = 15.0):
        self.url = (url or "").rstrip("/")
        self.timeout = timeout
        self.secret = b""
        if secret_file and os.path.exists(secret_file):
            self.secret = open(secret_file).read().strip().encode()

    @property
    def enabled(self) -> bool:
        return bool(self.url and len(self.secret) >= 32)

    def _call(self, method: str, path: str, body: dict | None = None) -> dict:
        if not self.enabled:
            raise PayerError("payouts_disabled")
        raw = json.dumps(body).encode() if body is not None else b""
        ts = str(time.time())
        req = urllib.request.Request(
            self.url + path, raw or None,
            {"X-Payer-Time": ts, "X-Payer-Sig": sign(self.secret, method, path, ts, raw),
             "Content-Type": "application/json"}, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            with e:
                try:
                    code = json.loads(e.read()).get("error", "refused")
                except ValueError:
                    code = "refused"
            # 4xx is the payer saying no on purpose: not worth retrying. 5xx is worth another go.
            raise PayerError(code, retryable=e.code >= 500) from None
        except (urllib.error.URLError, TimeoutError, OSError, ValueError):
            raise PayerError("payer_unreachable", retryable=True) from None

    def pay(self, payout_id: str, to: str, amount_units: int) -> dict:
        return self._call("POST", "/pay", {"id": payout_id, "to": to, "amount": int(amount_units)})

    def status(self, payout_id: str) -> dict:
        return self._call("GET", f"/pay/{payout_id}")

    def health(self) -> dict:
        return self._call("GET", "/status")

    # -- the fee wallet: see payer/skrpayer/sponsor.py -------------------------------------

    def sponsor_info(self, user: str | None = None) -> dict:
        """Whether network fees can be paid for a buyer, by which wallet, and what it costs
        [user], which is nothing for a wallet that holds enough SKR."""
        return self._call("GET", f"/sponsor?user={user}" if user else "/sponsor")

    def sponsor(self, tx_b64: str, user: str) -> dict:
        """Hands over a transaction the buyer has signed, to be co-signed and submitted."""
        return self._call("POST", "/sponsor", {"tx": tx_b64, "user": user})
