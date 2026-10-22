"""
HTTP surface of the payer. Reachable only by the one service that holds the shared secret.

Every request is authenticated by an HMAC over the method, path, a timestamp and the body hash, so
a captured request cannot be altered, and expires after a minute. Replays inside that minute are
harmless: payouts are idempotent by id. Anything that fails authentication gets the same bare 401.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import logging
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .engine import Engine, Refused

log = logging.getLogger("skrpayer")
MAX_BODY = 4096
WINDOW = 60.0
_PAY_ID = re.compile(r"^/pay/([A-Za-z0-9_:.\-]{8,80})$")


def sign(secret: bytes, method: str, path: str, ts: str, body: bytes) -> str:
    msg = "\n".join((ts, method.upper(), path, hashlib.sha256(body).hexdigest())).encode()
    return hmac.new(secret, msg, hashlib.sha256).hexdigest()


def make_handler(engine: Engine, secret: bytes, sponsor=None):
    class H(BaseHTTPRequestHandler):
        server_version = "skrpayer/1"

        def log_message(self, fmt, *args):
            log.debug(fmt, *args)

        def _out(self, code: int, obj) -> None:
            data = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _body(self) -> bytes:
            n = int(self.headers.get("Content-Length") or 0)
            if n < 0 or n > MAX_BODY:
                raise ValueError("body")
            return self.rfile.read(n) if n else b""

        def _authentic(self, body: bytes) -> bool:
            ts, got = self.headers.get("X-Payer-Time") or "", self.headers.get("X-Payer-Sig") or ""
            try:
                if abs(time.time() - float(ts)) > WINDOW:
                    return False
            except ValueError:
                return False
            want = sign(secret, self.command, self.path, ts, body)
            return hmac.compare_digest(want, got)

        def _handle(self):
            if self.path == "/healthz":
                return self._out(200, {"ok": True})
            try:
                body = self._body()
            except ValueError:
                return self._out(400, {"error": "bad request"})
            if not self._authentic(body):
                return self._out(401, {"error": "denied"})
            try:
                if self.command == "POST" and self.path == "/pay":
                    d = json.loads(body)
                    return self._out(200, engine.request(d.get("id"), d.get("to"), d.get("amount")))
                m = _PAY_ID.match(self.path)
                if self.command == "GET" and m:
                    return self._out(200, engine.advance(m.group(1)))
                if self.command == "GET" and self.path == "/status":
                    return self._out(200, {"public_key": engine.pubkey, "paused": engine.paused,
                                           "totals": engine.totals(),
                                           "balance": engine.rpc.token_balance(engine.source),
                                           "decimals": engine.decimals})
                # The fee wallet. Absent unless its own key is mounted: see sponsor.py.
                if self.path.startswith("/sponsor"):
                    if sponsor is None:
                        return self._out(200, {"enabled": False}) if self.command == "GET" \
                            else self._out(422, {"error": "sponsor_disabled"})
                    if self.command == "GET" and self.path.split("?")[0] == "/sponsor":
                        # Who is asking decides whether the fee is waived: see Sponsor.holds_enough.
                        who = self.path.partition("?user=")[2][:64] or None
                        return self._out(200, sponsor.info(who))
                    if self.command == "POST" and self.path == "/sponsor":
                        d = json.loads(body)
                        return self._out(200, sponsor.submit(d.get("tx"), d.get("user")))
                if self.command == "POST" and self.path in ("/pause", "/resume"):
                    engine.pause(self.path == "/pause")
                    return self._out(200, {"paused": engine.paused})
            except Refused as e:
                return self._out(409 if e.code in ("id_reused",) else 422, {"error": e.code})
            except (ValueError, TypeError, AttributeError):
                return self._out(400, {"error": "bad request"})
            return self._out(404, {"error": "not found"})

        do_GET = do_POST = _handle

    return H


def serve(engine: Engine, secret: bytes, host: str = "0.0.0.0", port: int = 8788, sponsor=None) -> ThreadingHTTPServer:
    from .secretfiles import bind
    httpd = bind(host, port, make_handler(engine, secret, sponsor), ThreadingHTTPServer)
    httpd.daemon_threads = True
    return httpd
