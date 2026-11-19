"""HTTP surface: sign-in, recharges, help with one, push registration and the RPC proxy.
Stdlib ThreadingHTTPServer, one thread per connection."""
from __future__ import annotations

import hmac
import json
import logging
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from .chain import Rpc
from .cryptorefills import RefillError
from .topups import TopupRefused, match_operator
from . import stats

log = logging.getLogger("duffle.web")

#: Every request body is a small JSON object; nothing legitimate comes near this.
MAX_BODY = 64 * 1024
#: POST routes that need no session: which network a number is on (no secret, no money).
PUBLIC_POST = {"/v1/topup/detect"}

_TOPUP_PATH = re.compile(r"^/v1/topup/orders/(cu_[0-9a-f]{32})$")
_TOPUP_CANCEL = re.compile(r"^/v1/topup/orders/(cu_[0-9a-f]{32})/cancel$")
_TOPUP_CONFIRM = re.compile(r"^/v1/topup/orders/(cu_[0-9a-f]{32})/confirm$")
_TOPUP_SUPPORT = re.compile(r"^/v1/topup/orders/(cu_[0-9a-f]{32})/support$")


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
            # A full bucket is the same as no entry, so old ones are dropped: otherwise every
            # address ever seen stays in memory for the life of the process.
            if len(self._b) > 50_000:
                idle = self.cap / self.rate
                self._b = {k: v for k, v in self._b.items() if now - v[1] < idle}
            return ok


def make_handler(service, limiter: RateLimit | None = None, admin_token: str | None = None,
                 shop=None, topups=None, support=None):
    limiter = limiter or RateLimit()
    #: Reads are limited too: a list endpoint is as good a way to burn a provider quota as a
    #: write. Sign-in is limited hardest: it is open to strangers and does cryptography.
    read_limiter = RateLimit(600)
    auth_limiter = RateLimit(20)

    class Handler(BaseHTTPRequestHandler):
        server_version = "airpay/1"
        #: A caller that stops sending halfway (slow headers, a body that never arrives) frees
        #: its thread after this many seconds instead of holding it forever.
        timeout = 60

        def log_message(self, fmt, *args):
            log.debug("%s " + fmt, self.client_address[0], *args)

        # -- helpers --------------------------------------------------------------------------
        def _ip(self) -> str:
            """The caller's address. Behind a tunnel or proxy every socket peer is the proxy, which
            put everybody in one rate-limit bucket; with TRUST_PROXY set, the forwarded address is
            used instead. Off by default, because trusting the header on a directly exposed
            server lets anyone pick their own bucket."""
            if os.environ.get("TRUST_PROXY"):
                # Cloudflare (in front of Render) overwrites CF-Connecting-IP on every request.
                # Without it, the LAST forwarded hop is the one our proxy added; the first is
                # whatever the caller chose to send, so it would let anyone pick their bucket.
                fwd = (self.headers.get("CF-Connecting-IP") or "").strip() or \
                    (self.headers.get("X-Forwarded-For") or "").split(",")[-1].strip()
                if fwd:
                    return fwd
            return self.client_address[0]

        def _admin(self) -> bool:
            got = self.headers.get("Authorization") or ""
            return bool(admin_token) and hmac.compare_digest(got, f"Bearer {admin_token}")

        def _token(self) -> str | None:
            h = self.headers.get("Authorization") or ""
            return h[7:] if h.startswith("Bearer ") else None

        def _me(self) -> str | None:
            """The wallet acting, or None. The token proves WHO — one person, with an account of
            wallets — and `X-Wallet` says which of their wallets this request is about. A wallet
            that is not in the token's account is ignored, never believed: the header is a
            choice among wallets already proven, not a claim."""
            ident = service.auth.identity(self._token())
            if ident is None:
                return None
            want = (self.headers.get("X-Wallet") or "").strip()
            if want and want != ident["address"] and want in service.store.family(ident["address"]):
                return want
            return ident["address"]

        # -- AirPay recharges (topups.py) --------------------------------------------------------
        def _topup_get(self, url, me):
            if topups is None:
                return self._json(503, {"error": "recharges are off"})
            q = parse_qs(url.query)
            one = lambda k: (q.get(k) or [""])[0]
            try:
                if url.path == "/v1/topup/countries":
                    return self._json(200, {"countries": topups.countries()})
                if url.path == "/v1/topup/operators":
                    return self._json(200, {"operators": topups.operators(one("country"))})
                if url.path == "/v1/topup/plans":
                    return self._json(200, {"plans": topups.plans(one("country"), one("operator"))})
                if me is None:
                    return self._unauthorised()
                if url.path == "/v1/topup/orders":
                    return self._json(200, {"orders": topups.history(me)})
                if url.path == "/v1/topup/support" and support is not None:
                    return self._json(200, {"tickets": support.tickets(me)})
                m = _TOPUP_PATH.match(url.path)
                if m:
                    return self._json(200, topups.status(me, m.group(1)))
                m = _TOPUP_SUPPORT.match(url.path)
                if m and support is not None:
                    return self._json(200, support.preview(me, m.group(1)))
            except TopupRefused as e:
                return self._json(e.status, {"error": e.code, "message": e.message})
            except RefillError as e:
                return self._json(502, {"error": e.code, "message": "Could not reach the recharge service."})
            return self._json(404, {"error": "not found"})

        def _topup_post(self, path, body, me):
            if topups is None:
                return self._json(503, {"error": "recharges are off"})
            try:
                if path == "/v1/topup/detect":
                    # Which network a number is on. Cryptorefills cannot say; Bitrefill's lookup can,
                    # and its answer is matched to a network Cryptorefills sells in that country.
                    country = str(body.get("country") or "").upper()
                    found = None
                    if shop is not None and shop.enabled:
                        try:
                            got = shop.operator(str(body.get("phone_number") or ""))
                            if got.get("operator_found") and got.get("product"):
                                found = match_operator(got["product"].get("name"), topups.operators(country))
                        except Exception:  # noqa: BLE001 — a failed lookup only means "pick it yourself"
                            found = None
                    return self._json(200, {"operator": found})
                if path == "/v1/topup/orders":
                    out = topups.place(me, phone=str(body.get("phone_number") or ""),
                                       country=str(body.get("country") or ""),
                                       operator=str(body.get("operator") or ""),
                                       plan_id=str(body.get("plan_id") or ""),
                                       email=str(body.get("email") or "").strip(),
                                       ip=self._ip(), agent=self.headers.get("User-Agent"))
                    return self._json(200, out)
                m = _TOPUP_CANCEL.match(path)
                if m:
                    return self._json(200, topups.cancel(me, m.group(1)))
                m = _TOPUP_CONFIRM.match(path)
                if m:
                    return self._json(200, topups.confirm(me, m.group(1)))
                m = _TOPUP_SUPPORT.match(path)
                if m and support is not None:
                    return self._json(200, support.send(me, m.group(1), email=str(body.get("email") or ""),
                                                        query=str(body.get("query") or ""),
                                                        app=self.headers.get("User-Agent")))
            except TopupRefused as e:
                return self._json(e.status, {"error": e.code, "message": e.message})
            return self._json(404, {"error": "not found"})

        def _unauthorised(self):
            return self._json(401, {"error": "sign in"})

        def _forbidden(self):
            return self._json(403, {"error": "not yours"})

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
            if url.path != "/healthz" and not read_limiter.allow(self._ip()):
                return self._json(429, {"error": "slow down"})
            if url.path == "/healthz":
                return self._json(200, {
                    "ok": True,
                    "push": service.fcm.enabled,
                    # Whether each is configured, never the values: lets a deploy be checked
                    # without placing a real order or sending a real email.
                    "recharges": bool(topups is not None and topups.cr.enabled),
                    "small_recharges": bool(topups is not None and topups.shop is not None and topups.shop.enabled),
                    "support_mail": bool(support is not None and support.enabled),
                })
            if url.path.startswith("/v1/topup/"):
                return self._topup_get(url, self._me())
            if url.path == "/v1/me":
                # Who this session is acting as, and the account's wallets. Nothing else.
                me = self._me()
                if me is None:
                    return self._unauthorised()
                return self._json(200, {"address": me, "wallets": service.store.family(me) or [me]})
            if url.path == "/v1/admin/stats":
                # Counts only (stats.py), but still the business's numbers: admin only.
                if not self._admin():
                    return self._json(403, {"error": "admin token required"})
                body = service.store.stats()
                body["downloads"] = stats.downloads()
                return self._json(200, body)
            return self._json(404, {"error": "not found"})

        def do_POST(self):
            path = urlparse(self.path).path
            if not limiter.allow(self._ip()):
                return self._json(429, {"error": "slow down"})
            try:
                body = self._body()
                if not isinstance(body, dict):
                    raise ValueError("expected a JSON object")
                me = self._me()
                if path.startswith("/v1/auth/"):
                    return self._auth(path, body, me)
                if path not in PUBLIC_POST and me is None:
                    return self._unauthorised()
                if path.startswith("/v1/topup/"):
                    return self._topup_post(path, body, me)
                if path == "/v1/rpc":
                    # The phones build and send their own payment; this lends them our provider,
                    # which is dependable where the public endpoint rate-limits. Checked here, at
                    # the edge: an unlisted method is refused whatever the client would forward.
                    if body.get("method") not in Rpc.PROXYABLE:
                        return self._json(400, {"error": "method not allowed"})
                    try:
                        return self._json(200, service.rpc.forward(body))
                    except Exception:  # noqa: BLE001
                        return self._json(502, {"error": "upstream unavailable"})
                if path == "/v1/devices":
                    # Every wallet of the account, not only the one acting: a recharge paid from
                    # any of a person's wallets should reach their phone.
                    for w in service.store.family(me) or [me]:
                        service.add_device(w, body.get("push_token"))
                    return self._json(200, {"ok": True})
            except RecursionError:
                return self._json(400, {"error": "bad request"})
            except (ValueError, AttributeError, TypeError, json.JSONDecodeError):
                return self._json(400, {"error": "bad request"})
            return self._json(404, {"error": "not found"})

        def _auth(self, path: str, body: dict, me: str | None):
            """Sign-in. Strangers reach this, so it is limited harder than anything else and every
            refusal is the same words — the response never says which check failed."""
            from .auth import AuthError, RateLimited, WalletInUse
            if path == "/v1/auth/logout":
                if me is None:
                    return self._unauthorised()
                h = (self.headers.get("Authorization") or "")[7:]
                return self._json(200, {"ok": service.auth.revoke(h)})
            if not auth_limiter.allow(self._ip()):
                return self._json(429, {"error": "slow down"})
            try:
                if path == "/v1/auth/challenge":
                    purpose = str(body.get("purpose") or "signin")
                    # A link challenge is only for somebody already signed in; strangers get the
                    # sign-in one.
                    if purpose == "link" and me is None:
                        return self._unauthorised()
                    return self._json(200, service.auth.challenge(str(body.get("address") or ""), purpose=purpose))
                if path == "/v1/auth/verify":
                    return self._json(200, service.sign_in(str(body.get("nonce") or ""),
                                                           str(body.get("signature") or "")))
                if path == "/v1/auth/link":
                    if me is None:
                        return self._unauthorised()
                    return self._json(200, service.auth.link(self._token(), str(body.get("nonce") or ""),
                                                             str(body.get("signature") or "")))
            except WalletInUse:
                return self._json(409, {"error": "wallet_in_use"})
            except RateLimited:
                return self._json(429, {"error": "slow down"})
            except AuthError:
                return self._json(401, {"error": "denied"})
            return self._json(404, {"error": "not found"})

    return Handler


def serve(service, host: str, port: int, admin_token: str | None = None,
          shop=None, topups=None, support=None) -> ThreadingHTTPServer:
    from .secretfiles import bind
    httpd = bind(host, port, make_handler(service, admin_token=admin_token, shop=shop, topups=topups,
                                          support=support), ThreadingHTTPServer)
    httpd.daemon_threads = True
    return httpd
