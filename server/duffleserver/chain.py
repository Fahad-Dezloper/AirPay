"""Solana RPC, reduced to the three calls the server needs. Stdlib only."""
from __future__ import annotations

import json
import urllib.error
import urllib.request

from . import verify


class Offline(Exception):
    """Could not reach the node. Retry later; never read as 'not paid'."""


class RpcError(Exception):
    """The node answered with an error. Also never read as 'not paid'."""


class Rpc:
    def __init__(self, url: str, timeout: float = 10.0):
        self.url = url
        self.timeout = timeout

    def call(self, method: str, params: list):
        body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
        req = urllib.request.Request(self.url, body, {"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                doc = json.loads(r.read())
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
            raise Offline(str(e)) from e
        # A JSON-RPC error arrives as HTTP 200. Checking for it first is what stops a rejected
        # query from looking exactly like "no payment yet" — which once hid a working payment
        # behind a spinner indefinitely.
        if "error" in doc:
            raise RpcError(doc["error"].get("message", "rpc error"))
        return doc.get("result")

    #: Read-and-send methods the phones are allowed to proxy through this server. Anything
    #: outside this list is refused, so an open proxy cannot be pointed at arbitrary RPC.
    PROXYABLE = {
        "getLatestBlockhash", "getBalance", "sendTransaction", "getSignaturesForAddress",
        "getTransaction", "getSignatureStatuses", "getHealth", "isBlockhashValid",
    }

    def forward(self, payload: dict) -> dict:
        """Pass a phone's JSON-RPC call to our provider and hand back the raw answer."""
        method = payload.get("method")
        if method not in self.PROXYABLE:
            raise ValueError(f"method not allowed: {method}")
        body = json.dumps({"jsonrpc": "2.0", "id": payload.get("id", 1),
                           "method": method, "params": payload.get("params", [])}).encode()
        req = urllib.request.Request(self.url, body, {"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return json.loads(r.read())
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
            raise Offline(str(e)) from e

    def signatures(self, address: str) -> list[str]:
        # `confirmed` is the floor for this method; `processed` is refused.
        res = self.call("getSignaturesForAddress", [address, {"limit": 10, "commitment": "confirmed"}])
        return verify.successful_signatures(res)

    def transaction(self, signature: str) -> dict | None:
        return self.call(
            "getTransaction",
            [signature, {"encoding": "json", "commitment": "confirmed", "maxSupportedTransactionVersion": 0}],
        )


def find_payment(rpc, reference: str, recipient: str, want: int) -> tuple[str, str, str]:
    """Returns (kind, signature, why) with kind in paid | short | notyet.
    Raises Offline / RpcError, which callers must treat as 'unknown', not 'unpaid'."""
    short = ("notyet", "", "")
    for sig in rpc.signatures(reference):
        v = verify.verify_transfer(rpc.transaction(sig), reference, recipient, want)
        if v.kind == "paid":
            return ("paid", sig, "")
        if v.kind == "rejected":
            short = ("short", sig, v.why)
    return short
