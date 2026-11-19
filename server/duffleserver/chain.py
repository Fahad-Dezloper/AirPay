"""Solana RPC: the proxy the phones pay through, and the account lookup a recharge needs. Stdlib only."""
from __future__ import annotations

import json
import urllib.error
import urllib.request


class Offline(Exception):
    """Could not reach the node. Retry later; never read as 'not paid'."""


class RpcError(Exception):
    """The node answered with an error. Also never read as 'not paid'."""


PUBLIC_DEVNET = "https://api.devnet.solana.com"
PUBLIC_MAINNET = "https://api.mainnet-beta.solana.com"


class Rpc:
    def __init__(self, url: str, timeout: float = 10.0, fallback: str | None = PUBLIC_DEVNET):
        self.url = url
        self.timeout = timeout
        # A provider that refuses us — a revoked key answers 401 to everything — must not take
        # the server down with it. The public endpoint is slower and rate-limited, but it works.
        self.urls = [url] + ([fallback] if fallback and fallback != url else [])

    def _post(self, body: bytes) -> dict:
        last: Exception | None = None
        for url in self.urls:
            req = urllib.request.Request(url, body, {"Content-Type": "application/json"})
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as r:
                    return json.loads(r.read())
            except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
                last = e
        raise Offline(str(last)) from last

    def call(self, method: str, params: list):
        body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
        doc = self._post(body)
        # A JSON-RPC error arrives as HTTP 200. Checking for it first is what stops a rejected
        # query from looking exactly like "no payment yet" — which once hid a working payment
        # behind a spinner indefinitely.
        if "error" in doc:
            raise RpcError(doc["error"].get("message", "rpc error"))
        return doc.get("result")

    #: Read-and-send methods the phones are allowed to proxy through this server. Anything
    #: outside this list is refused, so an open proxy cannot be pointed at arbitrary RPC.
    PROXYABLE = {
        # What building and sending one USDC payment needs, and nothing else.
        "getLatestBlockhash", "getBalance", "sendTransaction", "getSignatureStatuses",
        "isBlockhashValid", "getAccountInfo", "getTokenAccountBalance",
    }

    def forward(self, payload: dict) -> dict:
        """Pass a phone's JSON-RPC call to our provider and hand back the raw answer."""
        method = payload.get("method")
        if method not in self.PROXYABLE:
            raise ValueError(f"method not allowed: {method}")
        body = json.dumps({"jsonrpc": "2.0", "id": payload.get("id", 1),
                           "method": method, "params": payload.get("params", [])}).encode()
        return self._post(body)
