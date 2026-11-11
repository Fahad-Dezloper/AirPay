"""CellUp's recharge desk: the server prices it, limits unpaid orders, follows it, tells the buyer once."""
import unittest

from duffleserver.cryptorefills import RefillError
from duffleserver.store import Store
from duffleserver.topups import MAX_OPEN, PAY_WINDOW, TopupDesk, TopupRefused

BUYER, OTHER = "7xKpQ3FnA1b2C3d4E5f6G7h8J9k1L2m3N4p5Q6r7S8tU", "Bq2mWd8TA1b2C3d4E5f6G7h8J9k1L2m3N4p5Q6r7S8tV"
PLAN = {"id": "p10", "brand": "Reliance Jio Credits", "category": "mobile_credits", "denomination": "Topup Plan - 10 INR",
        "label": "₹10", "usdc": "0.13", "face_amount": "10", "face_currency": "INR", "delivery": "phone"}


class FakeCR:
    def __init__(self):
        self.created, self.cancelled, self.state, self.fail = [], [], "waiting", None

    def plans(self, country, operator):
        return [PLAN] if (country, operator) == ("IN", "Reliance Jio") else []

    def create(self, **kw):
        if self.fail:
            raise self.fail
        self.created.append(kw)
        return {"id": f"cr{len(self.created)}", "state": "waiting", "address": "PayHere", "usdc": "0.13"}

    def get(self, cr_id):
        return {"id": cr_id, "state": self.state, "raw_state": self.state.upper(), "code": None}

    def cancel(self, cr_id):
        self.cancelled.append(cr_id)
        return {"id": cr_id, "state": "cancelled"}


class Clock:
    t = 1_000_000.0

    def __call__(self):
        return self.t


def order(desk, **kw):
    args = dict(phone="+91 98000 00000", country="in", operator="Reliance Jio", plan_id="p10",
                email="a@b.co", ip="203.0.113.7")
    args.update(kw)
    return desk.place(BUYER, **args)


class Desk(unittest.TestCase):
    def setUp(self):
        self.cr, self.clock, self.told = FakeCR(), Clock(), []
        self.desk = TopupDesk(Store(":memory:"), self.cr, deliver=lambda b, d: self.told.append((b, d)),
                              now=self.clock, classify=lambda a: "owner")

    def test_price_comes_from_the_catalogue_and_payment_details_go_back(self):
        out = order(self.desk)
        self.assertEqual((out["usdc"], out["address"], out["pay_to"], out["state"]), ("0.13", "PayHere", "owner", "waiting"))
        sent = self.cr.created[0]
        self.assertEqual(sent["phone"], "+919800000000")
        self.assertEqual(sent["buyer_ip"], "203.0.113.7")
        self.assertTrue(sent["external_id"].startswith("cu_"))

    def test_bad_number_email_or_plan_never_reach_cryptorefills(self):
        for kw, code in [({"phone": "98000"}, "bad_phone"), ({"email": "nope"}, "bad_email"), ({"plan_id": "x"}, "unknown_plan")]:
            with self.assertRaises(TopupRefused) as e:
                order(self.desk, **kw)
            self.assertEqual(e.exception.code, code)
        self.assertEqual(self.cr.created, [])

    def test_unpaid_orders_are_capped_per_wallet(self):
        for _ in range(MAX_OPEN):
            order(self.desk)
        with self.assertRaises(TopupRefused) as e:
            order(self.desk)
        self.assertEqual(e.exception.code, "too_many_open")

    def test_unknown_payment_destination_cancels_instead_of_letting_anyone_pay(self):
        desk = TopupDesk(Store(":memory:"), self.cr, now=self.clock, classify=lambda a: None)
        with self.assertRaises(TopupRefused):
            order(desk)
        self.assertEqual(self.cr.cancelled, ["cr1"])

    def test_refusals_become_plain_words(self):
        self.cr.fail = RefillError("INVALID_BENEFICIARY_ACCOUNT", 422)
        with self.assertRaises(TopupRefused) as e:
            order(self.desk)
        self.assertIn("number", e.exception.message)

    def test_only_the_buyer_sees_an_order(self):
        out = order(self.desk)
        with self.assertRaises(TopupRefused):
            self.desk.status(OTHER, out["id"])

    def test_delivery_is_told_exactly_once(self):
        out = order(self.desk)
        self.cr.state = "delivered"
        self.clock.t += 20
        self.desk.watch(); self.clock.t += 20; self.desk.watch()
        self.assertEqual(self.desk.status(BUYER, out["id"])["state"], "delivered")
        self.assertEqual(len(self.told), 1)
        self.assertEqual(self.told[0][1]["type"], "topup")
        self.assertEqual(self.told[0][1]["phone"], "0000")

    def test_payment_address_disappears_once_paid(self):
        out = order(self.desk)
        self.cr.state = "delivering"
        self.assertIsNone(self.desk.status(BUYER, out["id"])["address"])

    def test_cancel_only_before_payment(self):
        out = order(self.desk)
        self.assertEqual(self.desk.cancel(BUYER, out["id"])["state"], "cancelled")
        self.assertEqual(self.cr.cancelled, ["cr1"])

    def test_abandoned_order_expires(self):
        out = order(self.desk)
        self.clock.t += PAY_WINDOW + 11 * 60
        self.desk.watch()
        self.assertEqual(self.desk.status(BUYER, out["id"])["state"], "expired")

    def test_history_is_newest_first(self):
        a = order(self.desk); self.clock.t += 1; b = order(self.desk)
        self.assertEqual([o["id"] for o in self.desk.history(BUYER)], [b["id"], a["id"]])


if __name__ == "__main__":
    unittest.main()
