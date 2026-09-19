"""HTTP surface. Stdlib ThreadingHTTPServer: one thread per connection suits long-lived SSE."""
from __future__ import annotations

import hmac
import json
import logging
import queue
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from .chain import Rpc
from .store import Conflict

log = logging.getLogger("duffle.web")

MAX_BODY = 2 * 1024 * 1024
HEARTBEAT = 15.0
_REQ_PATH = re.compile(r"^/v1/requests/([1-9A-HJ-NP-Za-km-z]{32,44})$")


class RateLimit:
    """Per-IP token bucket for writes. Crude on purpose — enough to stop a loop, not an army."""

    def __init__(self, per_minute: int = 120):
        self.rate, self.cap = per_minute / 60.0, float(per_minute)
        self._b: dict[str, tuple[float, float]] = {}
        self._lock = threading.Lock()

    def allow(self, ip: str) -> bool:
        now = time.monotonic()
        with self._lock:
            tokens, at = self._b.get(ip, (self.cap, now))
            tokens = min(self.cap, tokens + (now - at) * self.rate)
            ok = tokens >= 1
            self._b[ip] = (tokens - 1 if ok else tokens, now)
            return ok


def make_handler(service, helius_auth: str | None, limiter: RateLimit | None = None):
    limiter = limiter or RateLimit()

    class Handler(BaseHTTPRequestHandler):
        server_version = "tap/1"

        def log_message(self, fmt, *args):
            log.debug("%s " + fmt, self.client_address[0], *args)

        # -- helpers --------------------------------------------------------------------------
        def _json(self, code: int, obj) -> None:
            data = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _body(self):
            n = int(self.headers.get("Content-Length") or 0)
            if n <= 0 or n > MAX_BODY:
                raise ValueError("missing or oversized body")
            return json.loads(self.rfile.read(n))

        # -- routes ---------------------------------------------------------------------------
        def do_GET(self):
            url = urlparse(self.path)
            if url.path == "/healthz":
                return self._json(200, {
                    "ok": True,
                    "open": service.store.open_count(),
                    "listeners": service.hub.count(),
                    "push": service.fcm.enabled,
                    "helius": bool(service.helius and service.helius.enabled),
                })
            m = _REQ_PATH.match(url.path)
            if m:
                req = service.store.get(m.group(1))
                if not req:
                    return self._json(404, {"error": "unknown reference"})
                return self._json(200, {"status": req["status"], "signature": req["signature"], "amount": req["amount"]})
            if url.path == "/v1/events":
                return self._events((parse_qs(url.query).get("recipient") or [""])[0])
            return self._json(404, {"error": "not found"})

        def do_POST(self):
            path = urlparse(self.path).path
            if path == "/v1/helius":
                return self._helius()
            if not limiter.allow(self.client_address[0]):
                return self._json(429, {"error": "slow down"})
            try:
                body = self._body()
                if path == "/v1/requests":
                    out = service.register(body.get("reference"), body.get("recipient"),
                                           body.get("amount"), body.get("label", ""),
                                           payer=body.get("payer") or None, genesis=body.get("genesis") or None,
                                           token=body.get("token") or "SOL", decimals=body.get("decimals", 9),
                                           symbol=body.get("symbol") or "SOL")
                    return self._json(200, {"status": out["status"], "signature": out.get("signature")})
                if path == "/v1/rpc":
                    # The phones share one public Solana endpoint that rate-limits after a
                    # handful of calls and answers from whichever node the load balancer picks,
                    # which left a payer unable to confirm money that had already arrived.
                    # Borrowing the server's provider makes reads dependable; the app still
                    # falls back to the public endpoint when this server is unreachable.
                    # Checked here, at the edge, rather than trusting whichever RPC client is
                    # wired in: an unlisted method must be refused even if the client would
                    # happily forward it.
                    if body.get("method") not in Rpc.PROXYABLE:
                        return self._json(400, {"error": f"method not allowed: {body.get('method')}"})
                    try:
                        return self._json(200, service.rpc.forward(body))
                    except Exception:
                        return self._json(502, {"error": "upstream unavailable"})
                if path == "/v1/withdrawals":
                    try:
                        return self._json(200, {"told": service.withdraw(body.get("reference"))})
                    except KeyError:
                        return self._json(404, {"error": "unknown reference"})
                if path == "/v1/rejections":
                    try:
                        return self._json(200, {"told": service.reject(body.get("reference"), body.get("label", ""))})
                    except KeyError:
                        return self._json(404, {"error": "unknown reference"})
                if path == "/v1/taps":
                    heard = service.relay_tap(body.get("to"), body.get("address"), body.get("label", ""),
                                              genesis=body.get("genesis") or None, brand=body.get("brand") or None)
                    return self._json(200, {"live": heard})
                if path == "/v1/devices":
                    service.add_device(body.get("recipient"), body.get("push_token"))
                    return self._json(200, {"ok": True})
            except Conflict as e:
                return self._json(409, {"error": str(e)})
            except (ValueError, AttributeError, TypeError, json.JSONDecodeError) as e:
                return self._json(400, {"error": str(e) or "bad request"})
            return self._json(404, {"error": "not found"})

        def _helius(self):
            if helius_auth:
                got = self.headers.get("Authorization") or ""
                if not hmac.compare_digest(got, helius_auth):
                    # 403 specifically: Helius retries every 4xx except 403.
                    return self._json(403, {"error": "bad authorization"})
            try:
                payload = self._body()
            except (ValueError, json.JSONDecodeError):
                return self._json(400, {"error": "bad payload"})
            # Answer at once; verification happens off-thread so Helius never times out on us.
            return self._json(200, {"queued": service.on_webhook(payload)})

        def _events(self, recipient: str):
            from .verify import is_address

            if not is_address(recipient):
                return self._json(400, {"error": "invalid recipient"})
            q = service.hub.subscribe(recipient)
            if q is None:
                return self._json(429, {"error": "too many listeners for this address"})
            try:
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("X-Accel-Buffering", "no")
                self.end_headers()
                self.wfile.write(b": connected\n\n")
                # Cover the gap while the app was reconnecting: payments that just landed, and
                # requests addressed to this phone while it had no stream open.
                since = time.time() - 600
                for r in service.store.recent_paid(recipient, since):
                    self._send({"type": "paid", "reference": r["reference"], "signature": r["signature"],
                                "amount": r["amount"], "symbol": r["symbol"]})
                for r in service.store.open_for_payer(recipient, since):
                    self._send({"type": "request", "reference": r["reference"], "recipient": r["recipient"],
                                "amount": r["amount"], "label": r["label"], "genesis": r["genesis"] or "",
                                "token": r["token"], "decimals": r["decimals"], "symbol": r["symbol"]})
                self.wfile.flush()
                while True:
                    try:
                        self._send(q.get(timeout=HEARTBEAT))
                    except queue.Empty:
                        self.wfile.write(b": hb\n\n")
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            finally:
                service.hub.unsubscribe(recipient, q)

        def _send(self, event: dict) -> None:
            kind = event.get("type", "paid")
            data = {k: v for k, v in event.items() if k != "type"}
            self.wfile.write(f"event: {kind}\ndata: ".encode() + json.dumps(data).encode() + b"\n\n")

    return Handler


def serve(service, host: str, port: int, helius_auth: str | None = None) -> ThreadingHTTPServer:
    httpd = ThreadingHTTPServer((host, port), make_handler(service, helius_auth))
    httpd.daemon_threads = True
    return httpd
