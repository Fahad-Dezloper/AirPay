"""
CellUp's one job: put credit or a plan on a phone number, anywhere Cryptorefills sells it, paid
in USDC from the buyer's own wallet.

The server's part is small and strict:
  * The plan and its price come from the catalogue the server reads, never from the phone. The
    app names a plan by id; what it costs is looked up here.
  * The order is placed on Cryptorefills' partner endpoint (our commission), with the buyer's
    IP as they require and our own id as the idempotency key.
  * The buyer pays Cryptorefills directly, to the one-off address the order came with. No money
    passes through this server, and nothing here holds a key.
  * The order is followed until it ends, and the buyer is told once when it does, by push when
    the app is closed.
  * An email is passed through for Cryptorefills' receipt and refunds, and is not stored here.

At most a few unpaid orders per wallet are allowed open at once: Cryptorefills treats many
unpaid orders as abuse of OUR key, so one buyer tapping "pay" ten times must not cost everyone.

**Bitrefill fills the gap below Cryptorefills' minimum.** Cryptorefills refuses any order under
0.5 EUR (`MIN_USDC`), which rules out the ₹10 and ₹20 recharges people actually buy most. AirPay
sold exactly those through Bitrefill (shop.py), so when a Shop is configured its plans for the
same network are offered too, but ONLY the ones under that minimum and only amounts Cryptorefills
does not already sell: everything Cryptorefills can sell still goes to Cryptorefills (our
commission). A Bitrefill plan's id starts with `br:` and its order row says `supplier =
'bitrefill'`; everything else about the order (the row, the states, the push) is the same.
"""
from __future__ import annotations

import json
import logging
import re
import time
import uuid
from decimal import Decimal, InvalidOperation

from .cryptorefills import FINAL, Cryptorefills, RefillError

log = logging.getLogger("duffle.topups")

PHONE = re.compile(r"^\+[1-9][0-9]{6,14}$")
EMAIL = re.compile(r"^[^@\s]{1,64}@[^@\s]{1,190}\.[A-Za-z]{2,24}$")
#: Unpaid orders one wallet may have open at once.
MAX_OPEN = 3
#: Cryptorefills keeps a payment window of 30 minutes; after a little more than that an order
#: still waiting is asked about once more and then left alone.
PAY_WINDOW = 30 * 60
#: How long an order still being delivered keeps being looked at.
FOLLOW_FOR = 2 * 24 * 3600
#: Bitrefill plan ids: "br:<product id>:<package id>".
BR = "br:"
#: Bitrefill's invoice answer does not say (in what shop.py reads) when it stops accepting
#: payment. Telling the app a SHORTER window than the real one costs nothing; a longer one could
#: let somebody pay a dead invoice. So the app is told 15 minutes.
BR_PAY_WINDOW = 15 * 60
#: How long the Bitrefill half of a plan list is kept (its catalogue has its own hour-long cache).
BR_PLANS_TTL = 15 * 60
#: After Bitrefill failed to answer, how long before it is asked again.
BR_RETRY = 2 * 60
#: Bitrefill's categories that put something on a phone number, as Cryptorefills' categories.
#: eSIM and VoIP are left out: they are not a recharge of the number typed in.
BR_CATEGORY = (("refill", "mobile_credits"), ("bundles", "mobile_bundle"), ("data", "mobile_data"))
_SYMBOL = {"INR": "₹", "USD": "$", "EUR": "€", "GBP": "£", "NGN": "₦", "PHP": "₱", "IDR": "Rp",
           "PKR": "Rs", "BDT": "৳", "BRL": "R$", "MXN": "MX$", "KES": "KSh"}
WALLET = re.compile(r"^[1-9A-HJ-NP-Za-km-z]{32,44}$")

#: Bitrefill's words for an invoice (and its payment), folded to the same states as
#: cryptorefills.state_of. Read off the live API where noted in the app's OrderStatusTest
#: (`all_delivered`, `not_delivered`, `partially_delivered`, `denied`, `payment_error`, `expired`);
#: the rest are the documented or likely spellings, and anything else is "unknown", which leaves
#: the order as it was and keeps it followed rather than guessing it finished.
#:
#: `not_delivered` and `partially_delivered` are NOT final: money arrived and something is still
#: owed (a retry or a refund), so the order keeps being followed, as the app does with them.
_BR_STATE = {
    "unpaid": "waiting", "created": "waiting", "pending": "waiting", "open": "waiting", "new": "waiting",
    "payment_detected": "paying", "payment_pending": "paying", "payment_seen": "paying",
    "confirming": "paying", "detected": "paying",
    "underpaid": "partial", "partially_paid": "partial", "partial_payment": "partial",
    "paid": "delivering", "overpaid": "delivering", "payment_confirmed": "delivering",
    "payment_received": "delivering", "processing": "delivering", "delivering": "delivering",
    "not_delivered": "delivering", "partially_delivered": "delivering",
    "all_delivered": "delivered", "complete": "delivered", "completed": "delivered", "delivered": "delivered",
    "expired": "expired",
    "cancelled": "cancelled", "canceled": "cancelled",
    "denied": "failed", "payment_error": "failed", "failed": "failed", "error": "failed",
    "refunded": "refunded",
}


def bitrefill_state(out: dict) -> str:
    """One state for a Bitrefill invoice, as Shop.order returns it.

    The invoice's own `status` leads. While it still says unpaid (or a word not known here), the
    payment's status can say more: money seen, or too little of it.
    """
    s = _BR_STATE.get(str(out.get("status") or "").strip().lower(), "unknown")
    if s in ("waiting", "unknown"):
        p = _BR_STATE.get(str(out.get("payment_status") or "").strip().lower(), "unknown")
        if p in ("paying", "partial", "delivering") or (s == "unknown" and p != "unknown"):
            return p
    return s


def _usdc_text(base_units: int) -> str:
    """USDC base units as the exact decimal an app pays: 560000 -> "0.56", 112345 -> "0.112345"."""
    d = (Decimal(int(base_units)) / Decimal(1_000_000)).quantize(Decimal("0.000001"))
    text = format(d.normalize(), "f")
    if "." not in text:
        text += ".00"
    elif len(text.split(".")[1]) < 2:
        text += "0"
    return text

SCHEMA = """
CREATE TABLE IF NOT EXISTS topups (
    id          TEXT PRIMARY KEY,
    cr_id       TEXT UNIQUE,
    buyer       TEXT NOT NULL,
    phone       TEXT NOT NULL,
    country     TEXT NOT NULL,
    operator    TEXT NOT NULL,
    plan_id     TEXT NOT NULL,
    label       TEXT NOT NULL,
    category    TEXT,
    face        TEXT,
    usdc        TEXT NOT NULL,
    address     TEXT,
    state       TEXT NOT NULL,
    raw_state   TEXT,
    code        TEXT,
    told        INTEGER NOT NULL DEFAULT 0,
    created_at  REAL NOT NULL,
    updated_at  REAL NOT NULL,
    looked_at   REAL NOT NULL DEFAULT 0,
    supplier    TEXT NOT NULL DEFAULT 'cryptorefills'
);
CREATE INDEX IF NOT EXISTS topups_buyer ON topups(buyer, created_at);
CREATE INDEX IF NOT EXISTS topups_state ON topups(state, created_at);
"""

#: What the buyer is told when Cryptorefills refuses, by their code. Anything not listed gets the
#: plain fallback; the code itself is logged.
REASONS = {
    "INVALID_BENEFICIARY_ACCOUNT": "That number does not look right for this network. Check it and the country.",
    "NOT_AVAILABLE_PRODUCT": "This plan is not available right now. Pick another.",
    "OUT_OF_STOCK": "This plan is out of stock right now. Pick another.",
    "NOT_ACTIVE": "This plan is not available right now. Pick another.",
    "AMOUNT_LESS_THEN_MINIMUM_ALLOWED": "This plan is below the smallest recharge allowed. Pick a bigger one. Nothing was charged.",
    "MAXIMUM_AMOUNT_PER_ORDER_EXCEEDED": "That amount is above the limit for one recharge.",
    "DAILY_SPENDING_LIMIT_EXCEEDED": "You have reached today's limit. Try again tomorrow.",
    "MONTHLY_SPENDING_LIMIT_EXCEEDED": "You have reached this month's limit.",
    "KYC_MISSING": "This recharge needs identity verification, which CellUp does not offer yet.",
    "FULLNAME_MISSING": "This recharge needs identity verification, which CellUp does not offer yet.",
    "rate_limited": "Too many recharges right now. Try again in a few minutes.",
    "unreachable": "Could not reach the recharge service. Nothing was charged. Try again.",
    "not_configured": "Recharges are not available right now.",
}
FALLBACK = "This recharge could not be started. Nothing was charged."


class TopupRefused(Exception):
    def __init__(self, code: str, message: str, status: int = 400):
        super().__init__(code)
        self.code, self.message, self.status = code, message, status


class TopupDesk:
    def __init__(self, store, cr: Cryptorefills, deliver=None, now=time.time, classify=None, shop=None):
        self.store, self.cr = store, cr
        #: Bitrefill (shop.py), for the plans under Cryptorefills' minimum. Optional.
        self.shop = shop
        self._br_cache: dict[str, tuple[float, list[dict]]] = {}
        #: Says whether a payment address is a wallet (pay into its USDC account) or already a
        #: USDC token account (pay into it as it is). Sending to the wrong one loses the money,
        #: so the app is told which, from the chain, never guessed.
        self._classify = classify or (lambda address: "owner")
        self._deliver = deliver or (lambda buyer, data: None)
        self._now = now
        with store._lock, store._db:
            store._db.executescript(SCHEMA)
            # A database made before Bitrefill was a supplier: every row in it is Cryptorefills'.
            cols = {r[1] for r in store._db.execute("PRAGMA table_info(topups)").fetchall()}
            if "supplier" not in cols:
                store._db.execute("ALTER TABLE topups ADD COLUMN supplier TEXT NOT NULL DEFAULT 'cryptorefills'")

    # ---- reading the catalogue (public) ----------------------------------------------------
    def countries(self):
        return self.cr.countries()

    def operators(self, country: str):
        return self.cr.operators(country)

    def plans(self, country: str, operator: str):
        """Cryptorefills' plans, then Bitrefill's for the same network under Cryptorefills'
        minimum (amounts Cryptorefills already sells are not repeated)."""
        ours = self.cr.plans(country, operator)
        extra = self._br_plans(country, operator, ours)
        if not extra:
            return ours
        out = list(ours) + extra
        out.sort(key=lambda p: (p["category"] or "", float(p["usdc"])))
        return out

    def _br_plans(self, country: str, operator: str, ours: list[dict]) -> list[dict]:
        shop = self.shop
        cc = (country or "").upper()
        if shop is None or not getattr(shop, "enabled", False) or len(cc) != 2 or not cc.isalpha() or not operator:
            return []
        key = f"{cc}:{operator.lower()}"
        hit = self._br_cache.get(key)
        now = self._now()
        if hit and now - hit[0] < BR_PLANS_TTL:
            found = hit[1]
        else:
            try:
                found = self._read_br_plans(cc, operator)
            except Exception as e:  # noqa: BLE001 — Bitrefill being down must never hide Cryptorefills' plans
                log.warning("bitrefill plans for %s/%s unavailable: %s", cc, operator, str(e)[:160])
                found = hit[1] if hit else []
                # Asked again in two minutes, not on every screen open: a slow failure would
                # otherwise stand in front of Cryptorefills' plans each time.
                self._br_cache[key] = (now - BR_PLANS_TTL + BR_RETRY, found)
                return self._br_filter(found, ours)
            # An empty shelf is often a read that failed quietly (Shop.products answers [] when
            # it cannot reach Bitrefill), so it is asked about again sooner.
            self._br_cache[key] = (now if found else now - BR_PLANS_TTL + BR_RETRY, found)
        return self._br_filter(found, ours)

    def _br_filter(self, found: list[dict], ours: list[dict]) -> list[dict]:
        # Only what Cryptorefills cannot sell, and never an amount it already does.
        floor = float(getattr(self.cr, "min_usdc", 0) or 0)
        sold = {(str(p.get("face_currency") or "").upper(), _num(p.get("face_amount")), p.get("category"))
                for p in ours}
        return [p for p in found if float(p["usdc"]) < floor and
                (str(p["face_currency"] or "").upper(), _num(p["face_amount"]), p["category"]) not in sold]

    def _read_br_plans(self, cc: str, operator: str) -> list[dict]:
        shop = self.shop.for_country(cc) if hasattr(self.shop, "for_country") else self.shop
        if (getattr(shop, "country", cc) or "").upper() != cc:
            return []  # a shop that can only read another country's shelf has nothing here
        ops = self.cr.operators(cc)
        if not any(o.get("id") == operator for o in ops):
            return []
        out, seen = [], {}
        for prod in shop.products() or []:
            cats = set(prod.get("categories") or [])
            category = next((ours for theirs, ours in BR_CATEGORY if theirs in cats), None)
            if category is None or (prod.get("recipient_type") or "") != "phone_number":
                continue
            if prod.get("in_stock") is False or match_operator(prod.get("name"), ops) != operator:
                continue
            for pk in prod.get("packages") or []:
                plan = self._br_plan(prod, pk, category)
                if plan is None:
                    continue
                # Two Bitrefill products for one network (Jio's "Reliance Jio India" and "Jio
                # Bundles India") can sell the same amount: offer it once, at the lower price.
                k = (plan["category"], plan["face_currency"], _num(plan["face_amount"]), plan["label"])
                if k not in seen or float(plan["usdc"]) < float(seen[k]["usdc"]):
                    seen[k] = plan
        out = list(seen.values())
        out.sort(key=lambda p: (p["category"], float(p["usdc"])))
        return out

    def _br_plan(self, prod: dict, pk: dict, category: str) -> dict | None:
        """A Bitrefill package in exactly the shape Cryptorefills._plan returns.

        `usdc` is Bitrefill's catalogue price through the rate measured from real invoices (see
        Shop._usd): an estimate, good to a cent or two. The order carries the invoice's exact
        figure, which is the only one ever paid.
        """
        pid, value = pk.get("id"), pk.get("value")
        if not pid or not value or not prod.get("id"):
            return None
        from .shop import Shop  # the same estimate the AirPay shelf shows
        usdc = Shop._usd(pk.get("price")).get("usd_price")
        if not usdc:
            return None  # no price, no way to know it is under the minimum: not offered
        currency = prod.get("currency")
        numeric = _num(value)
        face = numeric if numeric is not None else _num(pk.get("amount"))
        if numeric is not None:
            sym = _SYMBOL.get(str(currency or "").upper())
            amount = _plain(numeric)
            label = f"{sym}{amount}" if sym else f"{amount} {currency or ''}".strip()
        else:
            label = str(value)
        # Bitrefill gives a package only a name ("10GB – 1 Hour Data Pack", "₹7.47 Talktime
        # Top-Up"). Read the same facts Cryptorefills sends as fields out of it, so the card says
        # Data 10 GB rather than calling a data pack talktime.
        text = str(value)
        benefits, days = [], None
        m = re.search(r"(\d+(?:\.\d+)?)\s*(GB|MB)\b", text, re.I)
        if m:
            benefits.append({"type": "DATA", "unit": m.group(2).upper(), "amount": float(m.group(1))})
        m = re.search(r"(\d+(?:\.\d+)?)\s*Talk\s*time", text, re.I)
        if m:
            benefits.append({"type": "CREDITS", "unit": currency, "amount": float(m.group(1))})
        m = re.search(r"(\d+)\s*Days?\b", text, re.I)
        if m:
            days = int(m.group(1))
        if category == "mobile_credits" and any(b["type"] == "DATA" for b in benefits) \
                and not any(b["type"] == "CREDITS" for b in benefits):
            category = "mobile_data"
        return {
            "id": f"{BR}{prod['id']}:{pid}",
            "brand": prod.get("name"),
            "category": category,
            "denomination": str(value),
            "label": label,
            "usdc": usdc,
            "face_currency": currency,
            "face_amount": face,
            "delivery": "phone",
            "validity_days": days,
            "validity": None,
            "description": (text[:1200] if numeric is None else None),
            "benefits": benefits,
        }

    # ---- orders (signed-in buyer only) -----------------------------------------------------
    def place(self, buyer: str, *, phone: str, country: str, operator: str, plan_id: str,
              email: str, ip: str, agent: str | None = None) -> dict:
        phone = re.sub(r"[\s\-()]", "", phone or "")
        country = (country or "").upper()
        if not PHONE.match(phone):
            raise TopupRefused("bad_phone", "Enter the number with its country code, like +91 98765 43210.")
        if not EMAIL.match(email or ""):
            raise TopupRefused("bad_email", "Enter an email for the receipt.")
        plan = next((p for p in self.plans(country, operator) if p["id"] == plan_id), None)
        if plan is None:
            raise TopupRefused("unknown_plan", "That plan is no longer offered. Pick another.", 404)
        now = self._now()
        with self.store._lock:
            open_now = self.store._db.execute(
                "SELECT COUNT(*) FROM topups WHERE buyer=? AND state='waiting' AND created_at>?",
                (buyer, now - PAY_WINDOW)).fetchone()[0]
        if open_now >= MAX_OPEN:
            raise TopupRefused("too_many_open", "Finish or cancel your open recharge first.", 429)
        our_id = "cu_" + uuid.uuid4().hex
        if plan_id.startswith(BR):
            return self._place_bitrefill(buyer, our_id, plan, phone=phone, country=country,
                                         operator=operator, email=email, now=now)
        try:
            out = self.cr.create(external_id=our_id, brand=plan["brand"], country=country,
                                 denomination=plan["denomination"], phone=phone, email=email,
                                 buyer_ip=ip, buyer_agent=agent, delivery=plan["delivery"])
        except RefillError as e:
            log.warning("cryptorefills refused %s (%s) %s", e.code, e.status, e.detail)
            raise TopupRefused(e.code, REASONS.get(e.code, FALLBACK), 502 if e.status in (0, 401) else 400) from None
        if not out.get("address") or not out.get("usdc"):
            log.error("cryptorefills order %s came back without a payment address", out.get("id"))
            if out.get("id"):
                self._cancel_quietly(out["id"])
            raise TopupRefused("no_payment", FALLBACK, 502)
        with self.store._lock, self.store._db:
            self.store._db.execute(
                "INSERT INTO topups(id, cr_id, buyer, phone, country, operator, plan_id, label, category, face,"
                " usdc, address, state, raw_state, created_at, updated_at, looked_at)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (our_id, out["id"], buyer, phone, country, operator, plan_id, plan["label"], plan["category"],
                 f"{plan.get('face_amount') or ''} {plan.get('face_currency') or ''}".strip(),
                 str(out["usdc"]), out["address"], out["state"] if out["state"] != "unknown" else "waiting",
                 out.get("raw_state"), now, now, now))
        return self._finish_place(our_id, out["id"], out["address"],
                                  out.get("expires_at") or now + PAY_WINDOW)

    def _finish_place(self, our_id: str, supplier_id: str, address: str, expires_at) -> dict:
        try:
            pay_to = self._classify(address)
        except Exception:  # noqa: BLE001
            pay_to = None
        if pay_to not in ("owner", "token_account"):
            # Not knowing where USDC lands is a reason not to let anyone pay.
            row = self._row(our_id)
            self._cancel_quietly(supplier_id, row.get("supplier"))
            self._update(row, {"state": "cancelled"})
            raise TopupRefused("no_payment", FALLBACK, 502)
        return self.view(self._row(our_id)) | {"expires_at": expires_at, "pay_to": pay_to}

    def _place_bitrefill(self, buyer: str, our_id: str, plan: dict, *, phone: str, country: str,
                         operator: str, email: str, now: float) -> dict:
        """The same order, on Bitrefill: an invoice in USDC on Solana, refunds to the buyer.

        `buyer` is the signed-in wallet (web.py's `me`), which is also where Bitrefill sends the
        money back if it cannot deliver. Without a wallet to refund to, nothing is placed.
        """
        shop = self.shop.for_country(country) if hasattr(self.shop, "for_country") else self.shop
        if shop is None or not getattr(shop, "enabled", False):
            raise TopupRefused("not_configured", REASONS["not_configured"], 503)
        if not WALLET.match(buyer or ""):
            raise TopupRefused("no_refund", FALLBACK, 400)
        _, product_id, package_id = plan["id"].split(":", 2)
        try:
            inv = shop.invoice(product_id, plan["denomination"], phone_number=phone,
                               payment_method="usdc_solana", refund_address=buyer,
                               package_id=package_id, email=email)
        except Exception as e:  # noqa: BLE001 — RuntimeError("400 …") from Bitrefill, or no network
            detail = str(e)
            log.warning("bitrefill refused %s/%s: %s", product_id, package_id, detail[:200])
            if "out of stock" in detail.lower() or "out_of_stock" in detail.lower():
                raise TopupRefused("OUT_OF_STOCK", REASONS["OUT_OF_STOCK"], 400) from None
            client = detail[:1] == "4" and not detail.startswith(("401", "403", "429"))
            raise TopupRefused("bitrefill_refused" if client else "unreachable",
                               FALLBACK if client else REASONS["unreachable"], 400 if client else 502) from None
        amount, inv_id = inv.get("amount"), inv.get("invoice_id")
        if not inv_id or not inv.get("address") or not isinstance(amount, int) or amount <= 0 or \
                str(inv.get("currency") or "usdc").lower() not in ("usdc", "usdc_solana"):
            # Nothing to cancel: an unpaid Bitrefill invoice simply expires.
            log.error("bitrefill invoice %s came back without a USDC payment", inv_id)
            raise TopupRefused("no_payment", FALLBACK, 502)
        state = _BR_STATE.get(str(inv.get("status") or "").lower(), "waiting")
        with self.store._lock, self.store._db:
            self.store._db.execute(
                "INSERT INTO topups(id, cr_id, buyer, phone, country, operator, plan_id, label, category, face,"
                " usdc, address, state, raw_state, created_at, updated_at, looked_at, supplier)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (our_id, inv_id, buyer, phone, country, operator, plan["id"], plan["label"], plan["category"],
                 f"{plan.get('face_amount') or ''} {plan.get('face_currency') or ''}".strip(),
                 _usdc_text(amount), inv["address"], state if state in ("waiting", "paying", "partial") else "waiting",
                 inv.get("status"), now, now, now, "bitrefill"))
        return self._finish_place(our_id, inv_id, inv["address"], now + BR_PAY_WINDOW)

    def status(self, buyer: str, our_id: str) -> dict:
        row = self._mine(buyer, our_id)
        if row["state"] not in FINAL:
            row = self._refresh(row)
        return self.view(row)

    def cancel(self, buyer: str, our_id: str) -> dict:
        row = self._mine(buyer, our_id)
        if row["state"] == "waiting" and row.get("supplier") == "bitrefill":
            # Bitrefill has no way to cancel an invoice: an unpaid one expires by itself. So this
            # is a change on our side only, after one last look in case money already arrived
            # (then it is followed to the end like any paid order). The payment address stops
            # being shown; an invoice paid anyway after this would still be delivered or refunded
            # to the buyer's wallet by Bitrefill, but nobody here would be following it.
            row = self._refresh(row)
            if row["state"] == "waiting":
                self._update(row, {"state": "cancelled"})
            return self.view(self._row(our_id))
        if row["state"] == "waiting":
            try:
                out = self.cr.cancel(row["cr_id"])
                self._update(row, out if out.get("state") != "unknown" else {"state": "cancelled"})
            except RefillError as e:
                # 409: a payment was already seen. Follow it to the end instead.
                log.info("cancel %s refused: %s", our_id, e.code)
                return self.view(self._refresh(row))
        return self.view(self._row(our_id))

    def history(self, buyer: str, limit: int = 50) -> list[dict]:
        with self.store._lock:
            rows = self.store._db.execute(
                "SELECT * FROM topups WHERE buyer=? ORDER BY created_at DESC LIMIT ?", (buyer, limit)).fetchall()
        return [self.view(dict(r)) for r in rows]

    def watch(self) -> int:
        """Follows every order still open; called on a timer. Returns how many changed."""
        now = self._now()
        with self.store._lock:
            rows = [dict(r) for r in self.store._db.execute(
                "SELECT * FROM topups WHERE state NOT IN ('delivered','expired','cancelled','failed','refunded')"
                " AND created_at>? AND looked_at<? ORDER BY looked_at LIMIT 25",
                (now - FOLLOW_FOR, now - 8)).fetchall()]
        changed = 0
        for row in rows:
            before = row["state"]
            if before == "waiting" and now - row["created_at"] > PAY_WINDOW + 10 * 60:
                after = self._refresh(row)
                if after["state"] == "waiting":
                    self._update(after, {"state": "expired"})
                changed += 1
                continue
            if self._refresh(row)["state"] != before:
                changed += 1
        return changed

    # ---- inside ----------------------------------------------------------------------------
    @staticmethod
    def view(row: dict) -> dict:
        return {
            "id": row["id"], "state": row["state"], "phone": row["phone"], "country": row["country"],
            "operator": row["operator"], "plan_id": row["plan_id"], "label": row["label"],
            "category": row.get("category"), "face": row.get("face"), "usdc": row["usdc"],
            "address": row.get("address") if row["state"] in ("waiting", "paying", "partial") else None,
            "code": row.get("code"), "created_at": row["created_at"], "updated_at": row["updated_at"],
            "supplier": row.get("supplier") or "cryptorefills",
        }

    def _row(self, our_id: str) -> dict | None:
        with self.store._lock:
            r = self.store._db.execute("SELECT * FROM topups WHERE id=?", (our_id,)).fetchone()
        return dict(r) if r else None

    def _mine(self, buyer: str, our_id: str) -> dict:
        row = self._row(str(our_id or ""))
        if row is None or row["buyer"] != buyer:
            raise TopupRefused("not_found", "That recharge was not found.", 404)
        return row

    def _refresh(self, row: dict) -> dict:
        if row.get("supplier") == "bitrefill":
            return self._refresh_bitrefill(row)
        try:
            out = self.cr.get(row["cr_id"])
        except RefillError as e:
            log.info("topup %s: status unavailable (%s)", row["id"], e.code)
            with self.store._lock, self.store._db:
                self.store._db.execute("UPDATE topups SET looked_at=? WHERE id=?", (self._now(), row["id"]))
            return row
        return self._update(row, out)

    def _refresh_bitrefill(self, row: dict) -> dict:
        shop = self.shop.for_country(row["country"]) if hasattr(self.shop, "for_country") else self.shop
        try:
            if shop is None:
                raise RuntimeError("no shop configured")
            got = shop.order(row["cr_id"])
        except Exception as e:  # noqa: BLE001 — not knowing is not a state; look again later
            log.info("topup %s: bitrefill status unavailable (%s)", row["id"], str(e)[:120])
            with self.store._lock, self.store._db:
                self.store._db.execute("UPDATE topups SET looked_at=? WHERE id=?", (self._now(), row["id"]))
            return row
        code = next((o.get("redemption") for o in got.get("orders") or [] if o.get("redemption")), None)
        if isinstance(code, (dict, list)):
            code = json.dumps(code)
        raw = got.get("status")
        if got.get("payment_status"):
            raw = f"{raw}/{got['payment_status']}"
        return self._update(row, {"state": bitrefill_state(got), "raw_state": raw, "code": code})

    def _update(self, row: dict, out: dict) -> dict:
        now = self._now()
        state = out.get("state") if out.get("state") not in (None, "unknown") else row["state"]
        with self.store._lock, self.store._db:
            self.store._db.execute(
                "UPDATE topups SET state=?, raw_state=COALESCE(?, raw_state), code=COALESCE(?, code),"
                " updated_at=?, looked_at=? WHERE id=?",
                (state, out.get("raw_state"), out.get("code"), now if state != row["state"] else row["updated_at"],
                 now, row["id"]))
        fresh = self._row(row["id"])
        if state in FINAL and not row["told"]:
            self._tell(fresh)
        return fresh

    def _tell(self, row: dict) -> None:
        with self.store._lock, self.store._db:
            claimed = self.store._db.execute(
                "UPDATE topups SET told=1 WHERE id=? AND told=0", (row["id"],)).rowcount
        if not claimed:
            return  # told already, by another thread
        try:
            self._deliver(row["buyer"], {"type": "topup", "id": row["id"], "status": row["state"],
                                         "label": row["label"], "phone": row["phone"][-4:],
                                         "operator": row["operator"], "at": self._now()})
        except Exception:  # noqa: BLE001 — telling must never break following
            log.exception("could not tell %s about %s", row["buyer"], row["id"])

    def _cancel_quietly(self, cr_id: str, supplier: str | None = "cryptorefills") -> None:
        if supplier == "bitrefill":
            return  # a Bitrefill invoice cannot be cancelled; unpaid, it expires by itself
        try:
            self.cr.cancel(cr_id)
        except RefillError:
            pass


_NOISE = {"india", "usa", "us", "uk", "prepaid", "mobile", "topup", "top", "up", "credits", "credit",
          "recharge", "pin", "pinless", "bundles", "bundle", "data", "airtime", "telecom", "the", "wireless",
          "telecommunications", "communications", "cellular", "ltd", "limited", "inc", "plc", "sa", "group"}

#: Rebrands and brand names (operator_aliases.json): "Cable & Wireless" is sold as "Flow".
import json as _json
from pathlib import Path as _Path
ALIASES = {k: v for k, v in _json.loads((_Path(__file__).with_name("operator_aliases.json")).read_text()).items()
           if not k.startswith("_")}


def _num(v):
    """A face value as a number (10, 7.47), or None for a sentence like "2GB/Day - 84 Days"."""
    try:
        d = Decimal(str(v).replace(",", "").strip())
    except (InvalidOperation, ValueError, TypeError):
        return None
    if not d.is_finite():
        return None
    return int(d) if d == d.to_integral_value() else float(d)


def _plain(n) -> str:
    return str(int(n)) if float(n) == int(n) else f"{float(n):.2f}".rstrip("0").rstrip(".")


def _words(name: str | None) -> set[str]:
    return {w for w in re.split(r"[^a-z0-9!&]+", (name or "").lower()) if w and w not in _NOISE}


def _joined(name: str | None) -> str:
    return re.sub(r"[^a-z0-9]", "", (name or "").lower())


def match_operator(name: str | None, operators: list[dict]) -> str | None:
    """The Cryptorefills network [name] (Bitrefill's word for it) most likely means, or None.

    Bitrefill writes "Reliance Jio India" and "Vodafone Idea India"; Cryptorefills writes
    "Reliance Jio" and "Vi". Matched on shared words, and on known renames; anything not clearly
    one network is None, and the buyer picks it themselves.
    """
    low = (name or "").lower().strip()
    alias = next((v for k, v in sorted(ALIASES.items(), key=lambda kv: -len(kv[0])) if k in low), None)
    want = _words(name) | ({alias} if alias else set())
    if not want:
        return None
    # The same name written together ("Ethio Telecom" / "Ethiotelecom") is a match outright.
    joined = _joined(name)
    for op in operators:
        if joined and _joined(op.get("name")) and (_joined(op.get("name")) == joined or
                                                    (alias and _joined(op.get("name")) == _joined(alias))):
            return op["id"]
    scored = [(op["id"], len(want & _words(op.get("name")))) for op in operators]
    scored = [s for s in scored if s[1] > 0]
    if not scored:
        return None
    top = max(n for _, n in scored)
    best = [i for i, n in scored if n == top]
    return best[0] if len(best) == 1 else None
