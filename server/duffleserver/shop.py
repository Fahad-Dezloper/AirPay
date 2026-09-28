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
import urllib.parse
import urllib.request

log = logging.getLogger("duffle.shop")

API = "https://api.bitrefill.com/v2"

#: Cloudflare turns away anything that looks automated. See the module note.
USER_AGENT = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/129.0 Safari/537.36")

#: A catalogue changes far more slowly than people browse it, and it costs four calls to read.
CATALOG_TTL = 3600.0

#: Which network a number is on. Porting takes days, so minutes of cache are free — and the
#: lookup rate-limits long before the catalogue does.
OPERATOR_TTL = 600.0

#: What a person is actually trying to do, in the order they would look for it. Bitrefill's own
#: categories are shop-shaped ("apparel", "game-stores"); these are bill-shaped.
SECTIONS = (
    ("Phone", ("refill", "bundles", "data", "esim", "voip")),
    # Three different things used to share one shelf called "TV & streaming": subscriptions you
    # redeem online, satellite TV that credits a set-top box, and cinema tickets. They are not
    # alternatives to each other — nobody deciding between Hotstar and a Dish TV recharge — and
    # the one that needs a subscriber ID has nothing in common with the one that emails a code.
    #
    # Keyed on the precise category rather than "entertainment", which all three carry.
    ("Streaming", ("streaming",)),
    ("TV", ("dth",)),
    ("Going out", ("experiences",)),
    ("Bills & wallets", ("digital-wallet",)),
    ("Travel", ("travel", "flights", "train", "bus", "online-travel-agencies", "gasoline")),
    ("Food", ("food", "food-delivery", "groceries", "restaurants")),
    ("Shopping", ("ecommerce", "online-marketplaces", "retail", "department-stores",
                  "electronics", "apparel")),
    ("Games", ("games", "game-stores", "in-game-currencies")),
)


class Shop:
    """Bitrefill, reduced to the two things Duffle needs: what can I buy, and where do I pay."""

    #: number -> (asked_at, answer). See OPERATOR_TTL.
    _operators: dict

    def __init__(self, api_key: str | None, country: str = "IN"):
        self.api_key = api_key
        self.country = country
        self._catalog: tuple[float, list[dict]] | None = None
        self._lock = threading.Lock()
        self._operators = {}

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
            # The same packages carrying what they cost, because a screen that asks somebody to
            # CHOOSE needs it: "2GB/Day 5G Plan - 84 Days" is not something anyone can pick
            # without a price beside it.
            #
            # `amount` is the face value in the product's own currency and is the number to show
            # — 219 is the 219 rupees a Jio 30GB pack really costs. `price` is Bitrefill's
            # internal unit and is NOT money anybody recognises: it runs about 1205 to the USDC,
            # tracks a rate that moves, and is deliberately not sent to the app.
            #
            # Neither is what gets paid. Only invoice.payment.price is authoritative (see the
            # module note), so this is for choosing and the invoice is for paying.
            "packages": [
                # `id` because the docs make package_id and value mutually exclusive and the id
                # is the unambiguous one: a bundle's value is a sentence ("2GB/Day 5G Plan - 84
                # Days") and two packages can share a price.
                {"id": pk.get("id"), "value": pk.get("value"), "amount": pk.get("amount")}
                for pk in (p.get("packages") or []) if pk.get("value")
            ],
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
                payment_method: str = "usdc_solana", refund_address: str | None = None,
                package_id: str | None = None, email: str | None = None,
                webhook_url: str | None = None) -> dict:
        """Asks for a price, and gets back somewhere to pay it.

        `auto_pay` is false on purpose: it would let an account balance settle the order without
        anyone signing anything, which is the opposite of what this app is for.

        A phone refill credits a number directly and is refused without one, which arrives as a
        bare 400 — the product's `recipient` field is what says in advance that it is needed.
        """
        if not self.enabled:
            raise RuntimeError("no Bitrefill key configured")
        # package_id and value are mutually exclusive — send the precise one when we have it.
        item = {"product_id": product_id, "quantity": 1}
        if package_id:
            item["package_id"] = package_id
        else:
            item["value"] = value
        if phone_number:
            item["phone_number"] = phone_number
        body = {
            "products": [item],
            "payment_method": payment_method,
            "auto_pay": False,
        }
        # Where money goes if the order cannot be filled. The docs list this as required for
        # crypto payments and the API accepts an invoice without it — which is the worst
        # combination, because the failure only shows up when a refund is actually owed and
        # there is nowhere to send it. It is the buyer's own wallet.
        if refund_address:
            body["refund_address"] = refund_address
        # Who to tell. Without this Bitrefill notifies the account that owns the API key, which
        # is not the person who paid.
        if email:
            body["email"] = email
        # Where to be told. Bitrefill POSTs the finished invoice here instead of us polling for
        # a minute and then giving up.
        if webhook_url:
            body["webhook_url"] = webhook_url
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

    def operator(self, phone_number: str) -> dict:
        """Which network a number is on, according to Bitrefill.

        The only honest way to answer this. India has had full mobile number portability since
        2011, so the prefix says who ISSUED a number and not who runs it now — a prefix table
        would be confidently wrong for a very large number of people, and a recharge sent to the
        wrong network is real money gone. This asks the shop that has to deliver it.

        Answers are cached: the same number gets looked up every time somebody edits a digit and
        presses the button again, and the endpoint rate-limits (429) well before the catalogue
        does. Portability takes days, so a few minutes of cache costs nothing.
        """
        number = (phone_number or "").strip()
        if not number.startswith("+") or not number[1:].isdigit() or len(number) < 8:
            return {"operator_found": False}
        now = time.time()
        hit = self._operators.get(number)
        if hit and now - hit[0] < OPERATOR_TTL:
            return hit[1]
        d = self._get("/check_phone_number?phone_number=" + urllib.parse.quote(number))
        product = d.get("data") or {}
        out = {
            "operator_found": bool(d.get("operator_found")) and bool(product.get("id")),
            "product": self._trim(product) if product.get("id") else None,
        }
        self._operators[number] = (now, out)
        return out

    def order(self, invoice_id: str) -> dict:
        """What happened to an invoice. The app asks after paying, and keeps asking: delivery
        follows the payment by seconds, not milliseconds."""
        data = self._get(f"/invoices/{invoice_id}").get("data") or {}
        orders = data.get("orders") or []
        out = []
        for o in orders:
            # The invoice's embedded order carries status and nothing to redeem — verified
            # against a real invoice, where the object has no redemption field at all. The
            # documented home for it is the order's own endpoint, so that is asked, but only
            # once the order says it has been delivered: before then there is nothing there
            # and the call is a round trip for an empty answer.
            redemption = o.get("redemption_info") or o.get("redemptionInfo")
            if redemption is None and o.get("id") and o.get("status") == "delivered":
                try:
                    full = self._get(f"/orders/{o['id']}").get("data") or {}
                    redemption = full.get("redemption_info") or full.get("redemptionInfo")
                except Exception as e:                       # noqa: BLE001
                    log.info("order %s: %s", o.get("id"), e)
            out.append({"status": o.get("status"),
                        "delivered_time": o.get("delivered_time"),
                        "redemption": redemption})
        return {
            "invoice_id": data.get("id"),
            "status": data.get("status"),
            "payment_status": (data.get("payment") or {}).get("status"),
            "orders": out,
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
