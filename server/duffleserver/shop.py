"""
Buying real things with crypto, through Bitrefill.

This is the answer to "why would anyone open this on a Tuesday". A tap pays a person; this pays
a phone bill, a Hotstar subscription, a PhonePe balance that settles an electricity bill. India
has 156 products; the catalogue is Bitrefill's, the payment is ours.

**The key lives here, not in the app.** A Bitrefill token compiled into an APK is readable by
anyone who decompiles it, and it can read the account's order history.

Two things learned from the live API rather than the docs, both of which look like other
failures if you do not know them:

  * **Cloudflare refuses non-browser user agents** with a 403 that reads exactly like a bad
    token. A browser-ish User-Agent is not optional.
  * **A product's `price` cannot be reconciled** with what the invoice actually asks for. Only
    `invoice.payment.price` is authoritative, and it is denominated in the token's base units —
    560000 is 0.56 USDC, which is what a 50 rupee Google Play code costs.
"""
from __future__ import annotations

import json
import logging
import threading
import time
import urllib.error
import urllib.request

log = logging.getLogger("duffle.shop")

API = "https://api.bitrefill.com/v2"

#: Cloudflare turns away anything that looks automated. See the module note.
USER_AGENT = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/129.0 Safari/537.36")

#: A catalogue changes far more slowly than people browse it, and it costs four calls to read.
CATALOG_TTL = 3600.0

#: What a person is actually trying to do, in the order they would look for it. Bitrefill's own
#: categories are shop-shaped ("apparel", "game-stores"); these are bill-shaped.
SECTIONS = (
    ("Phone", ("refill", "bundles", "data", "esim", "voip")),
    ("TV & streaming", ("streaming", "dth", "entertainment")),
    ("Bills & wallets", ("digital-wallet",)),
    ("Travel", ("travel", "flights", "train", "bus", "online-travel-agencies", "gasoline")),
    ("Food", ("food", "food-delivery", "groceries", "restaurants")),
    ("Shopping", ("ecommerce", "online-marketplaces", "retail", "department-stores",
                  "electronics", "apparel")),
    ("Games", ("games", "game-stores", "in-game-currencies")),
)


class Shop:
    """Bitrefill, reduced to the two things Duffle needs: what can I buy, and where do I pay."""

    def __init__(self, api_key: str | None, country: str = "IN"):
        self.api_key = api_key
        self.country = country
        self._catalog: tuple[float, list[dict]] | None = None
        self._lock = threading.Lock()

    @property
    def enabled(self) -> bool:
        return bool(self.api_key)

    # -- catalogue ---------------------------------------------------------------------------

    def products(self, refresh: bool = False) -> list[dict]:
        """Everything buyable in this country, cached. Empty when unconfigured or unreachable —
        a shop that cannot be read is an empty shelf, not an exception on the payment path."""
        if not self.enabled:
            return []
        with self._lock:
            hit = self._catalog
        if hit and not refresh and time.time() - hit[0] < CATALOG_TTL:
            return hit[1]
        try:
            items = self._fetch_all()
        except (urllib.error.URLError, ValueError, KeyError) as e:
            log.info("catalogue unavailable: %s", e)
            return hit[1] if hit else []
        with self._lock:
            self._catalog = (time.time(), items)
        log.info("catalogue: %d products for %s", len(items), self.country)
        return items

    def _fetch_all(self) -> list[dict]:
        out: list[dict] = []
        start, total = 0, None
        # Paged 50 at a time whatever limit is asked for, so the loop follows `total` rather
        # than trusting a `_next` link on a different hostname.
        while start < (total if total is not None else 1) and start < 1000:
            page = self._get(f"/products?country={self.country}&limit=50&start={start}")
            total = (page.get("meta") or {}).get("total", 0)
            rows = page.get("data") or []
            if not rows:
                break
            out += rows
            start += len(rows)
        return [self._trim(p) for p in out if p.get("in_stock")]

    @staticmethod
    def _trim(p: dict) -> dict:
        """Only what a phone needs to draw a row and ask for a price."""
        return {
            "id": p.get("id"),
            "name": p.get("name"),
            "currency": p.get("currency"),
            "categories": p.get("categories") or [],
            "image": p.get("image"),
            # What the buyer has to supply: a phone number, an account, or nothing at all.
            "recipient": p.get("recipient_type") or "none",
            "redemption": p.get("redemption_methods") or [],
            "values": [pk.get("value") for pk in (p.get("packages") or []) if pk.get("value")],
        }

    def sections(self) -> list[dict]:
        """The catalogue grouped the way someone shops for it, not the way a shop files it."""
        items = self.products()
        seen: set[str] = set()
        out = []
        for title, cats in SECTIONS:
            rows = [p for p in items
                    if p["id"] not in seen and any(c in cats for c in p["categories"])]
            for p in rows:
                seen.add(p["id"])
            if rows:
                out.append({"title": title, "products": rows})
        rest = [p for p in items if p["id"] not in seen]
        if rest:
            out.append({"title": "More", "products": rest})
        return out

    # -- paying ------------------------------------------------------------------------------

    def invoice(self, product_id: str, value, phone_number: str | None = None,
                payment_method: str = "usdc_solana") -> dict:
        """Asks for a price, and gets back somewhere to pay it.

        `auto_pay` is false on purpose: it would let an account balance settle the order without
        anyone signing anything, which is the opposite of what this app is for.

        A phone refill credits a number directly and is refused without one, which arrives as a
        bare 400 — the product's `recipient` field is what says in advance that it is needed.
        """
        if not self.enabled:
            raise RuntimeError("no Bitrefill key configured")
        item = {"product_id": product_id, "value": value, "quantity": 1}
        if phone_number:
            item["phone_number"] = phone_number
        body = {
            "products": [item],
            "payment_method": payment_method,
            "auto_pay": False,
        }
        data = self._post("/invoices", body).get("data") or {}
        pay = data.get("payment") or {}
        if not pay.get("address") or pay.get("price") is None:
            raise RuntimeError("that invoice came back without somewhere to pay it")
        return {
            "invoice_id": data.get("id"),
            "address": pay["address"],
            # Base units of the payment token, which is what a transfer instruction wants.
            "amount": int(pay["price"]),
            "currency": pay.get("currency"),
            "method": pay.get("method"),
            "status": pay.get("status"),
            "product_id": product_id,
            "value": value,
        }

    def order(self, invoice_id: str) -> dict:
        """What happened to an invoice. The app asks after paying, and keeps asking: delivery
        follows the payment by seconds, not milliseconds."""
        data = self._get(f"/invoices/{invoice_id}").get("data") or {}
        orders = data.get("orders") or []
        return {
            "invoice_id": data.get("id"),
            "status": data.get("status"),
            "payment_status": (data.get("payment") or {}).get("status"),
            "orders": [{"status": o.get("status"),
                        "delivered_time": o.get("delivered_time"),
                        # Where a code, PIN or link comes back for products that have one.
                        "redemption": o.get("redemption_info") or o.get("redemptionInfo")}
                       for o in orders],
        }

    # -- transport ---------------------------------------------------------------------------

    def _get(self, path: str) -> dict:
        return self._call(path, None)

    def _post(self, path: str, body: dict) -> dict:
        return self._call(path, json.dumps(body).encode())

    def _call(self, path: str, data: bytes | None) -> dict:
        req = urllib.request.Request(API + path, data, {
            "User-Agent": USER_AGENT,
            "Accept": "application/json",
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.api_key}",
        })
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.loads(r.read())
        except urllib.error.HTTPError as e:
            # Bitrefill puts the reason in the body; the status line alone says only "400",
            # which is the difference between "that needs a phone number" and no idea at all.
            detail = ""
            try:
                detail = e.read().decode("utf-8", "replace")[:300]
            except Exception:
                pass
            raise RuntimeError(f"{e.code} {detail}".strip()) from None
