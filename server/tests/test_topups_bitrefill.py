"""Bitrefill as CellUp's fallback for the recharges under Cryptorefills' minimum.

Cryptorefills refuses orders under 0.56 USDC, which is where ₹10 and ₹20 sit. Bitrefill sells them
(AirPay did, through shop.py), so the desk offers Bitrefill's plans for the same network, only
below the minimum and never an amount Cryptorefills already sells, and routes their orders there.
Every Bitrefill call here is a fake: no invoice is ever created against the real API.
"""
import unittest

from duffleserver.shop import Shop
from duffleserver.store import Store
from duffleserver.topups import BR_PAY_WINDOW, PAY_WINDOW, TopupDesk, TopupRefused, bitrefill_state

BUYER = "7xKpQ3FnA1b2C3d4E5f6G7h8J9k1L2m3N4p5Q6r7S8tU"
UNITS = 1165.0  # Bitrefill's price unit per USDC; pinned on Shop for these tests


def units(usdc):
    return round(usdc * UNITS)


CR_PLAN = {"id": "p50", "brand": "Reliance Jio Credits", "category": "mobile_credits",
           "denomination": "Topup Plan - 50 INR", "label": "₹50", "usdc": "0.60", "face_amount": 50,
           "face_currency": "INR", "delivery": "phone", "validity_days": None, "validity": None,
           "description": None, "benefits": []}
OPERATORS = [{"id": "Reliance Jio", "name": "Reliance Jio"}, {"id": "Airtel", "name": "Airtel"},
             {"id": "Vi", "name": "Vi"}]

JIO = {"id": "reliance-jio-india", "name": "Reliance Jio India", "currency": "INR", "categories": ["refill"],
       "recipient_type": "phone_number", "in_stock": True, "packages": [
           {"id": "jio-10", "value": "10", "amount": 10, "price": units(0.12)},
           {"id": "jio-20", "value": "20", "amount": 20, "price": units(0.24)},
           # Cryptorefills sells ₹50 already: not offered again even if Bitrefill is cheaper.
           {"id": "jio-50", "value": "50", "amount": 50, "price": units(0.55)},
           # Over the minimum: Cryptorefills' to sell, never Bitrefill's.
           {"id": "jio-100", "value": "100", "amount": 100, "price": units(1.20)},
           {"id": "jio-noprice", "value": "15", "amount": 15},
       ]}
JIO_BUNDLES = {"id": "jio-bundles-india", "name": "Jio Bundles India", "currency": "INR", "categories": ["bundles"],
               "recipient_type": "phone_number", "in_stock": True, "packages": [
                   {"id": "jb-1", "value": "1GB Data - 1 Day", "amount": 19, "price": units(0.23)}]}
AIRTEL = {"id": "airtel-india", "name": "Airtel India", "currency": "INR", "categories": ["refill"],
          "recipient_type": "phone_number", "in_stock": True, "packages": [
              {"id": "a-10", "value": "10", "amount": 10, "price": units(0.12)}]}
GIFT = {"id": "jio-gift-india", "name": "JioMart Jio India", "currency": "INR", "categories": ["ecommerce"],
        "recipient_type": None, "in_stock": True, "packages": [{"id": "g", "value": "10", "price": units(0.12)}]}


class FakeCR:
    min_usdc = 0.56

    def __init__(self):
        self.created, self.cancelled, self.got = [], [], []

    def operators(self, country):
        return OPERATORS if country == "IN" else []

    def plans(self, country, operator):
        return [CR_PLAN] if (country, operator) == ("IN", "Reliance Jio") else []

    def create(self, **kw):
        self.created.append(kw)
        return {"id": f"cr{len(self.created)}", "state": "waiting", "address": "CrPayHere", "usdc": "0.60"}

    def get(self, cr_id):
        self.got.append(cr_id)
        return {"id": cr_id, "state": "waiting"}

    def cancel(self, cr_id):
        self.cancelled.append(cr_id)
        return {"id": cr_id, "state": "cancelled"}


class FakeShop:
    def __init__(self, country="IN", enabled=True):
        self.country, self.enabled = country, enabled
        self.reads, self.invoices, self.orders_asked = 0, [], []
        self.status, self.payment_status, self.redemption = "unpaid", "unpaid", None
        self.fail = None
        self.siblings = {}

    def for_country(self, cc):
        if cc == self.country:
            return self
        return self.siblings.setdefault(cc, FakeShop(cc, self.enabled))

    def products(self):
        self.reads += 1
        if self.fail:
            raise self.fail
        return [JIO, JIO_BUNDLES, AIRTEL, GIFT] if self.country == "IN" else []

    def invoice(self, product_id, value, phone_number=None, payment_method="usdc_solana",
                refund_address=None, package_id=None, email=None, webhook_url=None):
        self.invoices.append(dict(product_id=product_id, value=value, phone_number=phone_number,
                                  payment_method=payment_method, refund_address=refund_address,
                                  package_id=package_id, email=email))
        return {"invoice_id": f"inv{len(self.invoices)}", "address": "BrPayHere", "amount": 118_734,
                "currency": "USDC", "method": "usdc_solana", "status": "unpaid",
                "product_id": product_id, "value": value}

    def order(self, invoice_id):
        self.orders_asked.append(invoice_id)
        return {"invoice_id": invoice_id, "status": self.status, "payment_status": self.payment_status,
                "orders": [{"status": "delivered", "redemption": self.redemption}] if self.redemption else []}


class Clock:
    t = 1_000_000.0

    def __call__(self):
        return self.t


class Base(unittest.TestCase):
    def setUp(self):
        # Class-level and re-learned by other tests' invoices: pinned so the estimates are known.
        self._units, Shop.price_units = Shop.price_units, UNITS
        self.addCleanup(setattr, Shop, "price_units", self._units)
        self.cr, self.shop, self.clock, self.told = FakeCR(), FakeShop(), Clock(), []
        self.store = Store(":memory:")
        self.desk = TopupDesk(self.store, self.cr, deliver=lambda b, d: self.told.append((b, d)),
                              now=self.clock, classify=lambda a: "owner", shop=self.shop)

    def place(self, plan_id, **kw):
        args = dict(phone="+91 98000 00000", country="in", operator="Reliance Jio", plan_id=plan_id,
                    email="a@b.co", ip="203.0.113.7")
        args.update(kw)
        return self.desk.place(BUYER, **args)


class Plans(Base):
    def test_only_amounts_under_the_minimum_are_added(self):
        plans = self.desk.plans("IN", "Reliance Jio")
        ids = [p["id"] for p in plans]
        self.assertIn("p50", ids)
        br = [p for p in plans if p["id"].startswith("br:")]
        self.assertEqual(sorted(p["id"] for p in br),
                         ["br:jio-bundles-india:jb-1", "br:reliance-jio-india:jio-10", "br:reliance-jio-india:jio-20"])
        for p in br:
            self.assertLess(float(p["usdc"]), FakeCR.min_usdc)

    def test_an_amount_cryptorefills_sells_is_not_repeated(self):
        ids = [p["id"] for p in self.desk.plans("IN", "Reliance Jio")]
        self.assertNotIn("br:reliance-jio-india:jio-50", ids)   # ₹50 is Cryptorefills'
        self.assertNotIn("br:reliance-jio-india:jio-100", ids)  # over the minimum
        self.assertNotIn("br:reliance-jio-india:jio-noprice", ids)  # no price: cannot know

    def test_same_shape_as_a_cryptorefills_plan(self):
        p = next(p for p in self.desk.plans("IN", "Reliance Jio") if p["id"] == "br:reliance-jio-india:jio-10")
        self.assertEqual(set(p), set(CR_PLAN))
        self.assertEqual((p["category"], p["label"], p["face_amount"], p["face_currency"], p["delivery"], p["usdc"]),
                         ("mobile_credits", "₹10", 10, "INR", "phone", "0.12"))
        bundle = next(p for p in self.desk.plans("IN", "Reliance Jio") if p["id"] == "br:jio-bundles-india:jb-1")
        self.assertEqual((bundle["category"], bundle["label"]), ("mobile_bundle", "1GB Data - 1 Day"))

    def test_only_the_network_asked_for(self):
        ids = [p["id"] for p in self.desk.plans("IN", "Airtel")]
        self.assertEqual(ids, ["br:airtel-india:a-10"])
        self.assertEqual(self.desk.plans("IN", "Vi"), [])

    def test_gift_cards_are_not_recharges(self):
        ids = [p["id"] for p in self.desk.plans("IN", "Reliance Jio")]
        self.assertFalse(any("jio-gift" in i for i in ids))

    def test_no_shop_or_a_disabled_one_is_cryptorefills_only(self):
        for shop in (None, FakeShop(enabled=False)):
            desk = TopupDesk(Store(":memory:"), self.cr, now=self.clock, shop=shop)
            self.assertEqual(desk.plans("IN", "Reliance Jio"), [CR_PLAN])

    def test_bitrefill_down_never_hides_cryptorefills(self):
        self.shop.fail = RuntimeError("503 down")
        self.assertEqual(self.desk.plans("IN", "Reliance Jio"), [CR_PLAN])

    def test_another_country_reads_its_own_shelf(self):
        self.desk.plans("NG", "MTN")
        self.assertIn("NG", self.shop.siblings)
        self.assertEqual(self.shop.reads, 0)

    def test_cached(self):
        self.desk.plans("IN", "Reliance Jio"); self.desk.plans("IN", "Reliance Jio")
        self.assertEqual(self.shop.reads, 1)


class Orders(Base):
    def test_a_small_plan_is_bought_on_bitrefill(self):
        out = self.place("br:reliance-jio-india:jio-10")
        self.assertEqual(self.cr.created, [])
        inv = self.shop.invoices[0]
        self.assertEqual(inv, dict(product_id="reliance-jio-india", value="10", phone_number="+919800000000",
                                   payment_method="usdc_solana", refund_address=BUYER, package_id="jio-10",
                                   email="a@b.co"))
        # The invoice's exact figure, not the catalogue's estimate.
        self.assertEqual((out["usdc"], out["address"], out["state"], out["pay_to"]),
                         ("0.118734", "BrPayHere", "waiting", "owner"))
        self.assertEqual(out["expires_at"], self.clock.t + BR_PAY_WINDOW)
        self.assertTrue(out["id"].startswith("cu_"))
        row = self.store._db.execute("SELECT supplier, cr_id FROM topups WHERE id=?", (out["id"],)).fetchone()
        self.assertEqual(tuple(row), ("bitrefill", "inv1"))

    def test_cryptorefills_path_unchanged(self):
        out = self.place("p50")
        self.assertEqual(self.shop.invoices, [])
        self.assertEqual((out["usdc"], out["address"]), ("0.60", "CrPayHere"))
        row = self.store._db.execute("SELECT supplier FROM topups WHERE id=?", (out["id"],)).fetchone()
        self.assertEqual(row[0], "cryptorefills")
        self.desk.status(BUYER, out["id"])
        self.assertEqual((self.cr.got, self.shop.orders_asked), (["cr1"], []))

    def test_a_plan_not_offered_cannot_be_named(self):
        for pid in ("br:reliance-jio-india:jio-100", "br:reliance-jio-india:jio-50", "br:made-up:x"):
            with self.assertRaises(TopupRefused) as e:
                self.place(pid)
            self.assertEqual(e.exception.code, "unknown_plan")
        self.assertEqual(self.shop.invoices, [])

    def test_bitrefill_refusal_is_plain_words(self):
        def boom(*a, **k):
            raise RuntimeError('400 {"message":"Product is out of stock"}')
        self.shop.invoice = boom
        with self.assertRaises(TopupRefused) as e:
            self.place("br:reliance-jio-india:jio-10")
        self.assertEqual(e.exception.code, "OUT_OF_STOCK")

    def test_unknown_payment_destination_is_refused_and_not_followed(self):
        desk = TopupDesk(self.store, self.cr, now=self.clock, classify=lambda a: None, shop=self.shop)
        with self.assertRaises(TopupRefused):
            desk.place(BUYER, phone="+919800000000", country="IN", operator="Reliance Jio",
                       plan_id="br:reliance-jio-india:jio-10", email="a@b.co", ip="1.2.3.4")
        self.assertEqual(self.cr.cancelled, [])  # never sent to Cryptorefills
        self.assertEqual(desk.history(BUYER)[0]["state"], "cancelled")

    def test_states_follow_the_invoice_and_tell_once(self):
        out = self.place("br:reliance-jio-india:jio-10")
        self.shop.payment_status = "payment_detected"
        self.assertEqual(self.desk.status(BUYER, out["id"])["state"], "paying")
        self.shop.status, self.shop.payment_status = "payment_confirmed", "paid"
        self.assertEqual(self.desk.status(BUYER, out["id"])["state"], "delivering")
        self.shop.status, self.shop.redemption = "all_delivered", {"code": "X1"}
        self.clock.t += 20; self.desk.watch(); self.clock.t += 20; self.desk.watch()
        got = self.desk.status(BUYER, out["id"])
        self.assertEqual((got["state"], got["code"], got["address"]), ("delivered", '{"code": "X1"}', None))
        self.assertEqual(len(self.told), 1)
        self.assertEqual(self.told[0][1]["status"], "delivered")

    def test_cancel_is_local_and_never_calls_cryptorefills(self):
        out = self.place("br:reliance-jio-india:jio-10")
        got = self.desk.cancel(BUYER, out["id"])
        self.assertEqual((got["state"], got["address"]), ("cancelled", None))
        self.assertEqual(self.cr.cancelled, [])
        self.assertEqual(self.shop.orders_asked, ["inv1"])  # looked once in case it was paid

    def test_cancel_after_payment_follows_it_instead(self):
        out = self.place("br:reliance-jio-india:jio-10")
        self.shop.status = "payment_confirmed"
        self.assertEqual(self.desk.cancel(BUYER, out["id"])["state"], "delivering")

    def test_abandoned_invoice_expires(self):
        out = self.place("br:reliance-jio-india:jio-10")
        self.clock.t += PAY_WINDOW + 11 * 60
        self.desk.watch()
        self.assertEqual(self.desk.status(BUYER, out["id"])["state"], "expired")

    def test_unreachable_bitrefill_keeps_the_state(self):
        out = self.place("br:reliance-jio-india:jio-10")
        def boom(_):
            raise OSError("down")
        self.shop.order = boom
        self.assertEqual(self.desk.status(BUYER, out["id"])["state"], "waiting")

    def test_open_orders_cap_counts_both_suppliers(self):
        self.place("p50"); self.place("br:reliance-jio-india:jio-10"); self.place("br:reliance-jio-india:jio-20")
        with self.assertRaises(TopupRefused) as e:
            self.place("br:reliance-jio-india:jio-10")
        self.assertEqual(e.exception.code, "too_many_open")


class StateMapping(unittest.TestCase):
    def test_vocabulary(self):
        cases = {
            ("unpaid", "unpaid"): "waiting",
            ("unpaid", "payment_detected"): "paying",
            ("unpaid", "underpaid"): "partial",
            ("payment_confirmed", "paid"): "delivering",
            ("not_delivered", "paid"): "delivering",      # contains "delivered"; is not
            ("partially_delivered", "paid"): "delivering",
            ("all_delivered", "paid"): "delivered",
            ("complete", None): "delivered",
            ("expired", "unpaid"): "expired",
            ("denied", "paid"): "failed",
            ("payment_error", None): "failed",
            ("refunded", "paid"): "refunded",
            ("something_new", None): "unknown",
        }
        for (status, pay), want in cases.items():
            self.assertEqual(bitrefill_state({"status": status, "payment_status": pay}), want, (status, pay))


class Migration(unittest.TestCase):
    def test_an_old_table_gets_the_supplier_column(self):
        store = Store(":memory:")
        with store._lock, store._db:
            store._db.executescript(
                "CREATE TABLE topups (id TEXT PRIMARY KEY, cr_id TEXT UNIQUE, buyer TEXT NOT NULL, phone TEXT NOT NULL,"
                " country TEXT NOT NULL, operator TEXT NOT NULL, plan_id TEXT NOT NULL, label TEXT NOT NULL,"
                " category TEXT, face TEXT, usdc TEXT NOT NULL, address TEXT, state TEXT NOT NULL, raw_state TEXT,"
                " code TEXT, told INTEGER NOT NULL DEFAULT 0, created_at REAL NOT NULL, updated_at REAL NOT NULL,"
                " looked_at REAL NOT NULL DEFAULT 0);"
                "INSERT INTO topups VALUES ('cu_old','cr9','b','+1','IN','x','p','l',NULL,NULL,'1',NULL,'delivered',"
                " NULL,NULL,1,1,1,1);")
        TopupDesk(store, FakeCR())
        TopupDesk(store, FakeCR())  # twice: the migration must be safe to run again
        row = store._db.execute("SELECT supplier FROM topups WHERE id='cu_old'").fetchone()
        self.assertEqual(row[0], "cryptorefills")


if __name__ == "__main__":
    unittest.main()


class PackageNames(unittest.TestCase):
    """Bitrefill names its packages in words; the facts in them become the plan's fields."""

    def plan(self, value, category="mobile_credits"):
        from duffleserver.topups import TopupDesk
        desk = TopupDesk.__new__(TopupDesk)
        return desk._br_plan({"id": "jio", "name": "Reliance Jio India", "currency": "INR"},
                             {"id": "pk", "value": value, "amount": 11, "price": 11}, category)

    def test_data_pack_is_data_with_its_size_and_days(self):
        p = self.plan("1GB – 1 Day Data Pack")
        if p is None:
            self.skipTest("no price estimate in this environment")
        self.assertEqual(p["category"], "mobile_data")
        self.assertEqual(p["benefits"], [{"type": "DATA", "unit": "GB", "amount": 1.0}])
        self.assertEqual(p["validity_days"], 1)

    def test_talktime_stays_a_top_up_with_its_credit(self):
        p = self.plan("₹7.47 Talktime Top-Up")
        if p is None:
            self.skipTest("no price estimate in this environment")
        self.assertEqual(p["category"], "mobile_credits")
        self.assertEqual(p["benefits"], [{"type": "CREDITS", "unit": "INR", "amount": 7.47}])
