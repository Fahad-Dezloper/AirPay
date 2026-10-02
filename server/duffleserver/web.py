"""HTTP surface. Stdlib ThreadingHTTPServer: one thread per connection suits long-lived SSE."""
from __future__ import annotations

import hmac
import json
import logging
import queue
import re
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from .chain import Rpc
from .service import config_as_dict
from .shop import Shop
from .store import Conflict

log = logging.getLogger("duffle.web")

MAX_BODY = 2 * 1024 * 1024
#: POST routes that need no session: the catalogue's detail (a cache, no secret), Bitrefill's
#: webhook (a nudge, never data) and the two admin routes, which check the admin token instead.
PUBLIC_POST = {"/v1/shop/product", "/v1/shop/webhook", "/v1/offers", "/v1/rewards/config"}
HEARTBEAT = 15.0
_PROOF_PATH = re.compile(r"^/v1/offers/([A-Za-z0-9_-]{1,64})/proof$")
_CLAIM_PATH = re.compile(r"^/v1/offers/([A-Za-z0-9_-]{1,64})/claim$")
_REWARDS_PATH = re.compile(r"^/v1/rewards/([1-9A-HJ-NP-Za-km-z]{32,44})$")
_REFERRALS_PATH = re.compile(r"^/v1/referrals/([1-9A-HJ-NP-Za-km-z]{32,44})$")
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


def make_handler(service, helius_auth: str | None, limiter: RateLimit | None = None,
                 admin_token: str | None = None, shop=None):
    limiter = limiter or RateLimit()
    #: Reads are limited too — they were not, and a list endpoint is as good a way to burn a
    #: provider quota as a write. Sign-in is limited hardest: it is the one route that is open
    #: to strangers and does cryptography.
    read_limiter = RateLimit(600)
    auth_limiter = RateLimit(20)
    # An unconfigured shop is an empty shelf, not a missing endpoint: the app asks either way
    # and draws nothing rather than showing an error it cannot act on.
    shop = shop or Shop(None)

    class Handler(BaseHTTPRequestHandler):
        server_version = "tap/1"

        def log_message(self, fmt, *args):
            log.debug("%s " + fmt, self.client_address[0], *args)

        # -- helpers --------------------------------------------------------------------------
        def _ip(self) -> str:
            """The caller's address. Behind a tunnel or proxy every socket peer is the proxy, which
            put everybody in one rate-limit bucket; with TRUST_PROXY set, the forwarded address is
            used instead. Off by default, because trusting the header on a directly exposed
            server lets anyone pick their own bucket."""
            if os.environ.get("TRUST_PROXY"):
                fwd = self.headers.get("CF-Connecting-IP") or (self.headers.get("X-Forwarded-For") or "").split(",")[0].strip()
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
            me = self._me()
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
            m = _REWARDS_PATH.match(url.path)
            if m:
                if me is None:
                    return self._unauthorised()
                if me != m.group(1):
                    return self._forbidden()
                return self._json(200, service.reward_summary(me))
            m = _REFERRALS_PATH.match(url.path)
            if m:
                if me is None:
                    return self._unauthorised()
                if me != m.group(1):
                    return self._forbidden()
                return self._json(200, service.referral_summary(me))
            if url.path == "/v1/shop":
                # Public: a catalogue is not a secret, and the key that reads it stays here.
                return self._json(200, {"enabled": shop.enabled, "sections": shop.sections()})
            if url.path == "/v1/offers":
                # Public: the list is the same for everyone, and `address` only adds that
                # person's own state to each row.
                # The caller's own state comes from the session, never from a query string.
                return self._json(200, {"offers": service.offers_for(me or "")})
            m = _PROOF_PATH.match(url.path)
            if m:
                # What a dApp is shown when asked to fund the next one. Admin-only: it is a
                # list of their customers' signatures.
                if not self._admin():
                    return self._json(403, {"error": "admin token required"})
                return self._json(200, service.store.offer_proof(m.group(1)))
            if url.path == "/v1/admin/rewards":
                # What was paid, by rule and by day, and everything refused with its reason.
                # Behind the admin token: it is the whole campaign's shape, including how to
                # farm it, and the refusal counts are the farming attempts themselves.
                if not self._admin():
                    return self._json(403, {"error": "admin token required"})
                days = float((parse_qs(url.query).get("days") or ["30"])[0])
                return self._json(200, service.reward_ledger(days))
            if url.path == "/v1/rewards/config":
                # Public on purpose: the app shows these amounts, and a screen promising a
                # number the server no longer pays would be worse than showing nothing.
                return self._json(200, config_as_dict(service.reward_config()))
            if url.path == "/v1/handles":
                # Public, like a name service: who owns a name is not a secret, and a person has to be
                # findable before they are paid. Rate-limited by the read limiter above.
                q = parse_qs(url.query)
                return self._json(200, {"names": service.resolve_handles((q.get("prefix") or [""])[0])})
            if url.path == "/v1/messages":
                # What arrived while this phone was not listening. It asks for everything newer
                # than the last message it holds, so a phone that never closed asks for nothing.
                if me is None:
                    return self._unauthorised()
                q = parse_qs(url.query)
                try:
                    rows = service.messages_for(me, float((q.get("since") or ["0"])[0]))
                except ValueError as e:
                    return self._json(400, {"error": str(e)})
                return self._json(200, {"messages": rows})
            if url.path == "/v1/events":
                if me is None:
                    return self._unauthorised()
                return self._events(me)
            return self._json(404, {"error": "not found"})

        def do_POST(self):
            path = urlparse(self.path).path
            if path == "/v1/helius":
                return self._helius()
            if not limiter.allow(self._ip()):
                return self._json(429, {"error": "slow down"})
            try:
                body = self._body()
                me = self._me()
                if path.startswith("/v1/auth/"):
                    return self._auth(path, body, me)
                if path not in PUBLIC_POST and me is None:
                    return self._unauthorised()
                if path == "/v1/requests":
                    # Either side of a payment may register it — the asker (recipient) or the one
                    # being asked (payer) — but a caller may not register on behalf of strangers.
                    if me not in (body.get("recipient"), body.get("payer")):
                        return self._forbidden()
                    out = service.register(body.get("reference"), body.get("recipient"),
                                           body.get("amount"), body.get("label", ""),
                                           payer=body.get("payer") or None, genesis=body.get("genesis") or None,
                                           token=body.get("token") or "SOL", decimals=body.get("decimals", 9),
                                           symbol=body.get("symbol") or "SOL", origin=body.get("origin") or "",
                                           deliver=body.get("deliver") is not False)
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
                if path == "/v1/rpc/mainnet":
                    # The one cluster where this app's money is real: paying a Bitrefill
                    # invoice. Same whitelist as devnet, because a proxy that forwards
                    # anything is a proxy that forwards something regrettable.
                    if body.get("method") not in Rpc.PROXYABLE:
                        return self._json(400, {"error": f"method not allowed: {body.get('method')}"})
                    if service.facts is None or getattr(service.facts, "mainnet", None) is None:
                        return self._json(503, {"error": "no mainnet endpoint configured"})
                    try:
                        return self._json(200, service.facts.mainnet.forward(body))
                    except Exception:
                        return self._json(502, {"error": "upstream unavailable"})
                if path in ("/v1/withdrawals", "/v1/rejections", "/v1/deferrals"):
                    # Only a party to the request may change what it says.
                    row = service.store.get(str(body.get("reference") or ""))
                    if row is not None:
                        owner = row["recipient"] if path == "/v1/withdrawals" else row["payer"]
                        if path == "/v1/withdrawals" and me != owner:
                            return self._forbidden()
                        if path != "/v1/withdrawals" and ((owner and me != owner) or me == row["recipient"]):
                            return self._forbidden()
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
                if path == "/v1/shop/invoice":
                    # The refund address is where a failed order's money goes. It is the
                    # buyer's own wallet, and only the buyer's: a caller cannot send a refund to
                    # somebody else, or buy as somebody else.
                    if body.get("refund_address") != me:
                        return self._forbidden()
                    try:
                        public = os.environ.get("PUBLIC_URL")
                        out = shop.invoice(
                            str(body.get("product_id") or ""), body.get("value"),
                            phone_number=body.get("phone_number") or None,
                            refund_address=body.get("refund_address") or None,
                            package_id=body.get("package_id") or None,
                            email=body.get("email") or None,
                            webhook_url=(public.rstrip("/") + "/v1/shop/webhook") if public else None)
                        # Who bought it, so the webhook has somebody to tell. The refund address
                        # is the buyer's own wallet, which is also how push is addressed.
                        if out.get("invoice_id"):
                            service.store.remember_purchase(
                                out["invoice_id"], body.get("refund_address") or None,
                                str(body.get("product_id") or "")[:80], shop.category_of(str(body.get("product_id") or "")),
                                out.get("amount") if isinstance(out.get("amount"), int) else None)
                        return self._json(200, out)
                    except Exception as e:
                        # Includes "out of stock" and "no such product", which are answers
                        # rather than faults — the app shows them as the reason it cannot buy.
                        return self._json(502, {"error": str(e) or "the shop did not answer"})
                if path == "/v1/shop/operator":
                    try:
                        return self._json(200, shop.operator(str(body.get("phone_number") or "")))
                    except Exception as e:
                        # A lookup that cannot be made is not a failure worth blocking on: the
                        # app falls back to asking which network, which is what it did before.
                        return self._json(200, {"operator_found": False, "error": str(e)[:120]})
                if path == "/v1/shop/product":
                    try:
                        return self._json(200, shop.details(str(body.get("product_id") or "")))
                    except Exception as e:
                        return self._json(200, {"error": str(e)[:120]})
                if path == "/v1/shop/webhook":
                    # Bitrefill signs nothing, so the body is a NUDGE and never data: the only
                    # thing taken from it is an invoice id, and the truth is then fetched with
                    # our own API key. A forged POST achieves a wasted round trip and nothing
                    # else — it cannot invent a delivery or a code.
                    #
                    # Answer immediately: the docs ask for 2xx inside five seconds and the
                    # fetch-and-notify takes longer than that.
                    invoice_id = str((body.get("data") or body).get("id") or "")
                    if invoice_id:
                        threading.Thread(
                            target=service.settle_shop, args=(shop, invoice_id),
                            name="shop-webhook", daemon=True).start()
                    return self._json(200, {"ok": True})
                if path == "/v1/shop/order":
                    try:
                        return self._json(200, shop.order(str(body.get("invoice_id") or "")))
                    except Exception as e:
                        return self._json(502, {"error": str(e) or "the shop did not answer"})
                if path == "/v1/deferrals":
                    try:
                        return self._json(200, {"heard": service.defer(body.get("reference"), body.get("label", ""))})
                    except KeyError:
                        return self._json(404, {"error": "unknown reference"})
                if path == "/v1/taps":
                    if body.get("address") != me:
                        return self._forbidden()
                    heard = service.relay_tap(body.get("to"), body.get("address"), body.get("label", ""),
                                              genesis=body.get("genesis") or None, brand=body.get("brand") or None)
                    return self._json(200, {"live": heard})
                if path == "/v1/messages":
                    try:
                        row = service.send_message(me, body.get("to") or "",
                                                   body.get("body") or "", body.get("label") or "")
                    except ValueError as e:
                        return self._json(400, {"error": str(e)})
                    return self._json(200, row)
                if path == "/v1/rewards/preview":
                    # A read, but a POST: the wallet addresses belong in a body, not in a URL
                    # that proxies and logs keep.
                    return self._json(200, service.reward_preview(
                        me, body.get("counterparty") or "",
                        body.get("token") or "SOL", int(body.get("units") or 0)))
                if path == "/v1/rewards/reveal":
                    # A POST for the same reason as the preview: an address does not belong in
                    # a URL. It changes only whether a card is shown covered.
                    return self._json(200, service.reveal_reward(me, int(body.get("id") or 0)))
                if path == "/v1/rewards/claim":
                    return self._json(200, service.claim_rewards(me))
                if path == "/v1/referrals/claim":
                    return self._json(200, service.claim_referral(body.get("code") or "", me))
                if path == "/v1/offers":
                    # Creating an offer is ours, not a user's — it spends the pool.
                    if not self._admin():
                        return self._json(403, {"error": "admin token required"})
                    return self._json(200, service.put_offer(body))
                m = _CLAIM_PATH.match(path)
                if m:
                    try:
                        return self._json(200, service.claim_offer(m.group(1), me))
                    except KeyError:
                        return self._json(404, {"error": "unknown offer"})
                if path == "/v1/rewards/config":
                    # Changing what rewards cost is an owner's action, not a user's.
                    if not self._admin():
                        return self._json(403, {"error": "admin token required"})
                    return self._json(200, service.set_reward_config(body))
                if path == "/v1/handles":
                    out = service.claim_handle(me, body.get("name"))
                    return self._json(200 if out["ok"] else 409, out)
                if path == "/v1/devices":
                    # Every wallet of the account, not only the one acting: money arriving in any
                    # of a person's wallets should reach their phone.
                    for w in service.store.family(me) or [me]:
                        service.add_device(w, body.get("push_token"))
                    return self._json(200, {"ok": True})
            except Conflict as e:
                return self._json(409, {"error": str(e)})
            except (ValueError, AttributeError, TypeError, json.JSONDecodeError) as e:
                return self._json(400, {"error": str(e) or "bad request"})
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


def serve(service, host: str, port: int, helius_auth: str | None = None,
          admin_token: str | None = None, shop=None) -> ThreadingHTTPServer:
    httpd = ThreadingHTTPServer((host, port),
                                make_handler(service, helius_auth, admin_token=admin_token, shop=shop))
    httpd.daemon_threads = True
    return httpd
