"""Cryptorefills: the catalogue is read and trimmed right, and orders follow their go-live rules."""
import json
import unittest

from duffleserver.cryptorefills import Cryptorefills, RefillError, state_of

BRANDS_IN = {"all_brands": [
    {"family": "Reliance Jio", "brand": "Reliance Jio Credits", "kind": "mobile_recharge",
     "category": "mobile_credits", "logo_url": "https://cdn/jio.webp", "bg_color": "#0f3cc9", "is_out_of_stock": False},
    {"family": "Reliance Jio", "brand": "Reliance Jio Bundle", "kind": "mobile_recharge",
     "category": "mobile_bundle", "logo_url": "https://cdn/jio.webp", "is_out_of_stock": False},
    {"family": "eSIM", "brand": "eSIM", "kind": "mobile_recharge", "category": "e-sim"},
    {"family": "Amazon", "brand": "Amazon", "kind": "gift_card", "category": "shopping"},
]}
PRODUCTS = [{"brand": "Reliance Jio Credits", "kind": "mobile_recharge", "category": "mobile_credits", "products": [
    {"product_id": "p20", "is_dynamic": False, "denomination": "Topup Plan - 20 INR", "localized_denomination": "₹20",
     "coin_amount": "1.20", "coin": "USDC", "delivery_type": "by_phone",
     "face_value": {"currency_code": "INR", "amount": {"type": "fixed", "price": "20"}}},
    {"product_id": "p10", "is_dynamic": False, "denomination": "Topup Plan - 50 INR", "coin_amount": "0.60",
     "coin": "USDC", "delivery_type": "by_phone", "face_value": {"currency_code": "INR", "amount": {"price": "50"}}},
    {"product_id": "p5", "is_dynamic": False, "denomination": "Topup Plan - 10 INR", "coin_amount": "0.13",
     "coin": "USDC", "delivery_type": "by_phone", "face_value": {"currency_code": "INR", "amount": {"price": "10"}}},
    {"product_id": "dyn", "is_dynamic": True, "coin_amount": "1", "coin": "USDC"},
]}]


class Fake:
    def __init__(self, replies):
        self.replies, self.calls = list(replies), []

    def __call__(self, method, url, headers, body):
        self.calls.append((method, url, dict(headers), json.loads(body) if body else None))
        status, payload = self.replies.pop(0)
        return status, json.dumps(payload).encode()


def order(**kw):
    return dict(external_id="ap_1", brand="Reliance Jio Credits", country="in", denomination="Topup Plan - 10 INR",
                phone="+919800000000", email="a@b.co", buyer_ip="203.0.113.7", **kw)


class Catalogue(unittest.TestCase):
    def test_only_topups_grouped_by_network(self):
        cr = Cryptorefills(["k"], Fake([(200, BRANDS_IN)]))
        ops = cr.operators("in")
        self.assertEqual([o["name"] for o in ops], ["Reliance Jio"])
        self.assertEqual([k["category"] for k in ops[0]["kinds"]], ["mobile_credits", "mobile_bundle"])

    def test_plans_cheapest_first_and_custom_amounts_left_out(self):
        cr = Cryptorefills(["k"], Fake([(200, PRODUCTS)]))
        plans = cr.plans("IN", "Reliance Jio")
        self.assertEqual([p["id"] for p in plans], ["p10", "p20"])
        self.assertEqual(plans[0]["usdc"], "0.60")  # p5 (0.13) is under their minimum: hidden
        self.assertEqual(plans[0]["delivery"], "phone")

    def test_catalogue_is_cached(self):
        fake = Fake([(200, BRANDS_IN)])
        cr = Cryptorefills(["k"], fake)
        cr.operators("IN"); cr.operators("IN")
        self.assertEqual(len(fake.calls), 1)

    def test_bad_country_asks_nothing(self):
        fake = Fake([])
        self.assertEqual(Cryptorefills(["k"], fake).operators("India"), [])


class Orders(unittest.TestCase):
    def test_partner_endpoint_key_buyer_ip_and_idempotency(self):
        fake = Fake([(200, {"order_id": "o1", "wallet_address": "Addr", "coin_amount": "0.13",
                            "order_state": "WAITING_FOR_PAYMENT"})])
        out = Cryptorefills(["k1", "k2"], fake).create(**order())
        method, url, headers, body = fake.calls[0]
        self.assertEqual((method, url), ("POST", "https://api.cryptorefills.com/v6/partner/orders"))
        self.assertEqual(headers["X-CR-Partner-Key"], "k1")
        self.assertEqual(headers["X-CR-Forwarded-For"], "203.0.113.7")
        self.assertEqual(body["external_order_id"], "ap_1")
        self.assertEqual(body["deliveries"][0]["country_code"], "IN")
        self.assertEqual(body["payment"], {"type": "via", "coin": "USDC", "network": "Solana", "payment_via": "USER_WALLET"})
        self.assertEqual((out["state"], out["address"], out["usdc"]), ("waiting", "Addr", "0.13"))

    def test_401_fails_over_to_the_second_key_and_remembers(self):
        fake = Fake([(401, {}), (200, {"order_id": "o1"}), (200, {"order_id": "o1", "order_state": "COMPLETED"})])
        cr = Cryptorefills(["dead", "live"], fake)
        cr.create(**order())
        cr.get("o1")
        self.assertEqual([c[2]["X-CR-Partner-Key"] for c in fake.calls], ["dead", "live", "live"])

    def test_422_reason_reaches_the_caller(self):
        cr = Cryptorefills(["k"], Fake([(422, {"code": "INVALID_BENEFICIARY_ACCOUNT"})]))
        with self.assertRaises(RefillError) as e:
            cr.create(**order())
        self.assertEqual((e.exception.code, e.exception.status), ("INVALID_BENEFICIARY_ACCOUNT", 422))

    def test_minimum_refusal_names_its_reason_and_raises_the_floor(self):
        # Their 422 is a bare list, not an object (seen live 2026-11-11).
        refusal = [{"problem": "AMOUNT_LESS_THEN_MINIMUM_ALLOWED",
                    "moreDetails": {"threshold": "0.5/EUR", "minimumAmountCoin": "0.65", "minimumAmountCoinSymbol": "USDC"}}]
        cr = Cryptorefills(["k"], Fake([(200, PRODUCTS), (422, refusal), (200, PRODUCTS)]))
        self.assertEqual([p["id"] for p in cr.plans("IN", "Reliance Jio")], ["p10", "p20"])
        with self.assertRaises(RefillError) as e:
            cr.create(**order())
        self.assertEqual(e.exception.code, "AMOUNT_LESS_THEN_MINIMUM_ALLOWED")
        self.assertEqual([p["id"] for p in cr.plans("IN", "Reliance Jio")], ["p20"])

    def test_429_is_rate_limited_not_retried(self):
        fake = Fake([(429, {"code": "partner_daily_order_velocity_exceeded"})])
        with self.assertRaises(RefillError) as e:
            Cryptorefills(["k"], fake).create(**order())
        self.assertEqual(e.exception.code, "rate_limited")
        self.assertEqual(len(fake.calls), 1)

    def test_pin_products_go_to_the_email(self):
        fake = Fake([(200, {"order_id": "o"})])
        Cryptorefills(["k"], fake).create(**order(delivery="email"))
        self.assertEqual(fake.calls[0][3]["deliveries"][0]["beneficiary_account"], "a@b.co")

    def test_no_key_no_order(self):
        with self.assertRaises(RefillError):
            Cryptorefills([], Fake([])).create(**order())

    def test_states_fold_both_spellings(self):
        self.assertEqual(state_of("WaitingForPayment"), "waiting")
        self.assertEqual(state_of("WAITING_FOR_PAYMENT"), "waiting")
        self.assertEqual(state_of("Done"), "delivered")
        self.assertEqual(state_of("COMPLETED"), "delivered")
        self.assertEqual(state_of("CANCELED"), "cancelled")
        self.assertEqual(state_of("something new"), "unknown")


if __name__ == "__main__":
    unittest.main()
