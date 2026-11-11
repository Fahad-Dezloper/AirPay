"""
Mobile top-ups through Cryptorefills, our partner: their catalogue, and orders placed on the
partner endpoint so the commission is ours.

The catalogue is public (no key). Orders go to `/v6/partner/orders` with `X-CR-Partner-Key`,
which is the only thing that attributes an order to us. Facts from their docs and live API,
2026-11-11:

  * Every country Cryptorefills sells in (233) is listed by `available-countries`; a country's
    top-ups are the brands whose `kind` is `mobile_recharge` (Credits, Data, Bundle, Talk Time).
    eSIMs share that kind and are left out: they are not a recharge.
  * A brand's plans come from `/v5/products/country/{cc}?family_name=` with the price already in
    USDC (`coin_amount`). `delivery_type` says where it lands: `by_phone` goes straight onto the
    number; `by_email` is a PIN sent to the buyer's email.
  * An order answers with a one-off `wallet_address` and an exact `coin_amount`, good for 30
    minutes. Paying anything else, or later, does not complete it.
  * `X-CR-Forwarded-For` (the BUYER's IP, not ours) is required; without it the order is
    refused with 400.
  * `external_order_id` is an idempotency key: the same value twice returns the first order.
  * 401 means the key is dead: fail over to the second key. 422 carries a reason the buyer
    should see. 429 is a velocity cap: back off, never loop.
  * Many created-and-unpaid orders count as abuse, so an abandoned checkout is cancelled
    (DELETE) rather than left to expire.

No sandbox exists; tests here run against a fake transport.
"""
from __future__ import annotations

import json
import logging
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

log = logging.getLogger("duffle.cryptorefills")

API = "https://api.cryptorefills.com"
COUNTRIES_URL = "https://cryptorefills.com/api/available-countries"
#: Their CDN and API sit behind a bot screen; a phone-like agent is what they expect from a
#: storefront. The buyer's own agent is sent when we have it.
AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"

#: The categories that put credit or a plan on a phone number.
TOPUP_CATEGORIES = ("mobile_credits", "mobile_data", "mobile_bundle", "mobile_talk_time", "mobile_sms")

CATALOGUE_TTL = 15 * 60
COUNTRIES_TTL = 24 * 3600

#: Order states, as Cryptorefills writes them (v5 PascalCase, v6 UPPER_SNAKE), folded to ours.
_STATE = {
    "created": "waiting", "waitingforpayment": "waiting", "waiting_for_payment": "waiting",
    "paymentstarted": "paying", "payment_started": "paying",
    "partialpaymentstarted": "partial", "partial_payment_started": "partial",
    "paymentreceived": "delivering", "payment_received": "delivering",
    "waitingfordelivery": "delivering", "waiting_for_delivery": "delivering",
    "waitingformanualaction": "delivering", "waiting_for_manual_action": "delivering",
    "done": "delivered", "completed": "delivered",
    "expired": "expired", "canceled": "cancelled", "cancelled": "cancelled",
    "paymentfailed": "failed", "payment_failed": "failed",
    "paymentsetupfailed": "failed", "payment_setup_failed": "failed",
    "refunded": "refunded",
}
FINAL = {"delivered", "expired", "cancelled", "failed", "refunded"}


#: Cryptorefills refuses any order under 0.5 EUR; this is that in USDC (seen 2026-11-11).
MIN_USDC = 0.56


class RefillError(Exception):
    """A refusal the buyer should be told about. [code] is Cryptorefills' own when it gave one."""

    def __init__(self, code: str, status: int = 0, detail: str = ""):
        super().__init__(code)
        self.code, self.status, self.detail = code, status, detail


def state_of(raw: str | None) -> str:
    return _STATE.get((raw or "").replace(" ", "").lower(), "unknown")


def _first(d: dict, *keys, default=None):
    for k in keys:
        if isinstance(d, dict) and d.get(k) not in (None, ""):
            return d[k]
    return default


class Cryptorefills:
    def __init__(self, keys: list[str] | None, transport=None, now=time.time):
        # Order matters: the first live key is used, the next on a 401.
        self.keys = [k for k in (keys or []) if k]
        self._dead: set[str] = set()
        self._http = transport or self._urllib
        self._now = now
        self._lock = threading.Lock()
        self._cache: dict[str, tuple[float, object]] = {}
        # Their smallest order, 0.5 EUR in USDC. Raised if a refusal names a higher one.
        self.min_usdc = MIN_USDC

    @property
    def enabled(self) -> bool:
        return bool(self.keys)

    # ---- transport -----------------------------------------------------------------------
    @staticmethod
    def _urllib(method: str, url: str, headers: dict, body: bytes | None) -> tuple[int, bytes]:
        req = urllib.request.Request(url, data=body, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read() or b""

    def _call(self, method: str, url: str, body: dict | None = None, *, keyed: bool = False,
              buyer_ip: str | None = None, buyer_agent: str | None = None) -> dict | list:
        headers = {"Accept": "application/json", "User-Agent": buyer_agent or AGENT}
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode()
        if buyer_ip:
            headers["X-CR-Forwarded-For"] = buyer_ip
        tried = 0
        while True:
            key = None
            if keyed:
                live = [k for k in self.keys if k not in self._dead]
                if not live:
                    raise RefillError("no_key", 401)
                key = live[0]
                headers["X-CR-Partner-Key"] = key
            try:
                status, raw = self._http(method, url, headers, data)
            except (OSError, urllib.error.URLError) as e:
                raise RefillError("unreachable", 0, str(e)[:120]) from None
            if status == 401 and keyed and tried < len(self.keys):
                # Their checklist: a 401 means this key is revoked; switch to the next at once.
                log.error("cryptorefills key …%s refused (401); failing over", key[-4:])
                self._dead.add(key)
                tried += 1
                continue
            payload = None
            if raw:
                try:
                    payload = json.loads(raw)
                except ValueError:
                    payload = None
            if 200 <= status < 300:
                return payload if payload is not None else {}
            code = self._code(payload) or f"http_{status}"
            if status == 429:
                raise RefillError("rate_limited", 429, code)
            if code == "AMOUNT_LESS_THEN_MINIMUM_ALLOWED":
                self._learn_minimum(payload)
            raise RefillError(code, status, json.dumps(payload)[:300] if payload else "")

    def _learn_minimum(self, payload) -> None:
        try:
            floor = float(payload[0]["moreDetails"]["minimumAmountCoin"])
        except (KeyError, IndexError, TypeError, ValueError):
            return
        if floor > self.min_usdc:
            log.warning("cryptorefills minimum is now %.2f USDC; hiding plans under it", floor)
            self.min_usdc = floor
            with self._lock:
                for k in [k for k in self._cache if k.startswith("plans:")]:
                    del self._cache[k]

    @staticmethod
    def _code(payload) -> str | None:
        # A 422 arrives as a bare list: [{"problem": "...", "moreDetails": {...}}].
        if isinstance(payload, list) and payload and isinstance(payload[0], dict):
            v = payload[0].get("problem") or payload[0].get("code")
            return v if isinstance(v, str) and v else None
        if not isinstance(payload, dict):
            return None
        for k in ("code", "error", "status_code"):
            v = payload.get(k)
            if isinstance(v, str) and v:
                return v
        problems = payload.get("problems")
        if isinstance(problems, list) and problems and isinstance(problems[0], dict):
            return problems[0].get("problem")
        return None

    def _cached(self, key: str, ttl: float, load):
        now = self._now()
        hit = self._cache.get(key)
        if hit and now - hit[0] < ttl:
            return hit[1]
        value = load()
        with self._lock:
            self._cache[key] = (now, value)
        return value

    # ---- catalogue -----------------------------------------------------------------------
    def countries(self) -> list[dict]:
        """Every country Cryptorefills sells in: [{code, name}], by name."""
        def load():
            d = self._call("GET", COUNTRIES_URL)
            rows = d.get("countries", []) if isinstance(d, dict) else []
            out = [{"code": r.get("isoCode"), "name": r.get("name")} for r in rows if r.get("isoCode")]
            return sorted(out, key=lambda r: r["name"] or "")
        return self._cached("countries", COUNTRIES_TTL, load)

    def operators(self, country: str) -> list[dict]:
        """The networks that can be topped up in [country], each with the kinds it sells."""
        cc = (country or "").upper()
        if len(cc) != 2 or not cc.isalpha():
            return []

        def load():
            d = self._call("GET", f"{API}/v2/brands?country_code={cc}")
            brands = d.get("all_brands", []) if isinstance(d, dict) else []
            ops: dict[str, dict] = {}
            for b in brands:
                if b.get("kind") != "mobile_recharge" or b.get("category") not in TOPUP_CATEGORIES:
                    continue
                fam = b.get("family") or b.get("brand")
                op = ops.setdefault(fam, {
                    "id": fam, "name": fam, "logo": b.get("logo_url"), "color": b.get("bg_color"),
                    "country": cc, "kinds": [],
                })
                op["kinds"].append({
                    "brand": b.get("brand"), "category": b.get("category"),
                    "out_of_stock": bool(b.get("is_out_of_stock")),
                })
            return sorted(ops.values(), key=lambda o: o["name"].lower())
        return self._cached(f"ops:{cc}", CATALOGUE_TTL, load)

    def plans(self, country: str, operator: str) -> list[dict]:
        """Every plan [operator] sells in [country], cheapest first within each kind."""
        cc = (country or "").upper()
        if len(cc) != 2 or not operator:
            return []

        def load():
            q = urllib.parse.urlencode({"family_name": operator, "coin": "USDC", "lang": "en"})
            d = self._call("GET", f"{API}/v5/products/country/{cc}?{q}")
            out = []
            for b in d if isinstance(d, list) else []:
                if b.get("kind") != "mobile_recharge" or b.get("category") not in TOPUP_CATEGORIES:
                    continue
                for p in b.get("products") or []:
                    if p.get("coin") != "USDC" or p.get("is_dynamic"):
                        continue  # custom amounts are a later addition; fixed plans first
                    plan = self._plan(b, p)
                    # Below their per-order minimum (0.5 EUR) an order is refused: never offer it.
                    if plan and float(plan["usdc"]) >= self.min_usdc:
                        out.append(plan)
            out.sort(key=lambda p: (p["category"], float(p["usdc"])))
            return out
        return self._cached(f"plans:{cc}:{operator.lower()}", CATALOGUE_TTL, load)

    @staticmethod
    def _plan(brand: dict, p: dict) -> dict | None:
        try:
            usdc = f"{float(p['coin_amount']):.2f}"
        except (KeyError, TypeError, ValueError):
            return None
        face = p.get("face_value") or {}
        amount = (face.get("amount") or {}).get("price")
        details = p.get("additional_details") or {}
        validity = details.get("validity") or p.get("validity") or {}
        benefits = []
        for b in details.get("benefits") or p.get("benefits") or []:
            t = b.get("type") or b.get("benefit_type")
            if t:
                a = b.get("amount") or {}
                total = a.get("total_including_tax") or a.get("base")
                value = total.get("amount") if isinstance(total, dict) else total
                # -1 is how they write "unlimited" (calls on most bundles).
                benefits.append({"type": t, "unit": b.get("unit"), "amount": value})
        return {
            "id": p.get("product_id"),
            "brand": brand.get("brand"),
            "category": brand.get("category"),
            "denomination": p.get("denomination"),
            "label": p.get("localized_denomination") or p.get("denomination"),
            "usdc": usdc,
            "face_currency": face.get("currency_code"),
            "face_amount": amount,
            "delivery": "phone" if p.get("delivery_type") == "by_phone" else "email",
            "validity_days": validity.get("quantity") if (validity.get("unit") or "").upper() == "DAY" else None,
            "validity": validity or None,
            "description": (details.get("description") or "").strip()[:1200] or None,
            "benefits": benefits,
        }

    # ---- orders --------------------------------------------------------------------------
    def create(self, *, external_id: str, brand: str, country: str, denomination: str,
               phone: str, email: str, buyer_ip: str, buyer_agent: str | None = None,
               delivery: str = "phone") -> dict:
        """Places the order on the partner endpoint. Returns what the buyer must pay."""
        if not self.enabled:
            raise RefillError("not_configured")
        beneficiary = phone if delivery == "phone" else email
        body = {
            "external_order_id": external_id,
            "deliveries": [{"brand_name": brand, "country_code": country.upper(),
                            "denomination": denomination, "beneficiary_account": beneficiary}],
            "payment": {"type": "via", "coin": "USDC", "network": "Solana", "payment_via": "USER_WALLET"},
            "user": {"email": email},
            "lang": "en",
        }
        d = self._call("POST", f"{API}/v6/partner/orders", body, keyed=True,
                       buyer_ip=buyer_ip, buyer_agent=buyer_agent)
        return self.normalize(d)

    def get(self, order_id: str) -> dict:
        d = self._call("GET", f"{API}/v6/partner/orders/{urllib.parse.quote(order_id)}", keyed=True)
        return self.normalize(d)

    def cancel(self, order_id: str) -> dict:
        d = self._call("DELETE", f"{API}/v6/partner/orders/{urllib.parse.quote(order_id)}", keyed=True)
        return self.normalize(d)

    @staticmethod
    def normalize(d) -> dict:
        """An order in the few fields the app uses, whatever shape it arrived in."""
        if not isinstance(d, dict):
            d = {}
        o = d.get("order") if isinstance(d.get("order"), dict) else d
        pay = o.get("payment") if isinstance(o.get("payment"), dict) else {}
        raw_state = _first(o, "order_state", "state", "status")
        deliveries = o.get("deliveries") if isinstance(o.get("deliveries"), list) else []
        code = None
        for dl in deliveries:
            if isinstance(dl, dict):
                code = _first(dl, "pin", "code", "redemption_code", "voucher") or code
        return {
            "id": _first(o, "order_id", "id"),
            "external_id": _first(o, "external_order_id"),
            "state": state_of(raw_state),
            "raw_state": raw_state,
            "address": _first(o, "wallet_address") or _first(pay, "wallet_address", "address"),
            "usdc": _first(o, "coin_amount") or _first(pay, "coin_amount", "amount"),
            "expires_at": _first(o, "expires_at", "payment_expires_at") or _first(pay, "expires_at"),
            "commission_pct": _first(o, "commission_rate_pct"),
            "code": code,
        }
