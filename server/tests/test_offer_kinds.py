"""
Swap and shop offers, attacked. Each test is a way to be paid without doing the thing, or to be
refused after doing it.
"""
from __future__ import annotations

import json
import os
import time
import unittest

from duffleserver import offers, swaps
from duffleserver.offers import Claim, Offer, Seen
from duffleserver.service import Service
from duffleserver.store import Store
from tests.test_server import FakeRpc
from tests.revealutil import scratch_all

FIX = os.path.join(os.path.dirname(__file__), "fixtures")
ME = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
NOW = 1_800_000_000.0


def fixture(n):
    return json.load(open(os.path.join(FIX, f"jupiter_swap_{n}.json")))


class SwapParserTest(unittest.TestCase):
    """Against transactions copied from mainnet, not hand-made ones."""

    def test_a_real_user_swap_is_read_correctly(self):
        f = fixture(1)
        sw = swaps.swap_of(f["result"], f["payer"])
        self.assertEqual(swaps.WSOL, sw.sold)
        self.assertEqual(10_000_000_000, sw.sold_units)            # 10 SOL, exactly
        self.assertEqual("oreoU2P8bN6jkk3jbaiVxYnG1dCXcYxwhwyK9jSybcp", sw.bought)
        self.assertGreater(sw.bought_units, 0)

    def test_a_multi_leg_bot_trade_is_never_paid_on(self):
        f = fixture(0)
        self.assertIsNone(swaps.swap_of(f["result"], f["payer"]))

    def test_somebody_elses_swap_is_not_mine(self):
        f = fixture(1)
        self.assertIsNone(swaps.swap_of(f["result"], ME))

    def test_a_swap_whose_fee_payer_is_someone_else_is_refused(self):
        # The owner appears in the transaction but did not sign or pay: not their swap.
        f = fixture(1)
        r = json.loads(json.dumps(f["result"]))
        keys = r["transaction"]["message"]["accountKeys"]
        keys[0], keys[1] = keys[1], keys[0]
        self.assertIsNone(swaps.swap_of(r, f["payer"]))

    def test_a_failed_transaction_never_counts(self):
        f = fixture(1)
        r = json.loads(json.dumps(f["result"]))
        r["meta"]["err"] = {"InstructionError": [0, "Custom"]}
        self.assertIsNone(swaps.swap_of(r, f["payer"]))

    def test_a_transaction_that_does_not_call_the_program_never_counts(self):
        f = fixture(1)
        self.assertIsNone(swaps.swap_of(f["result"], f["payer"], program="675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8"))

    def test_garbage_never_crashes(self):
        for bad in (None, {}, {"meta": {}}, {"meta": None, "transaction": 5}):
            self.assertIsNone(swaps.swap_of(bad, ME))

    def test_stable_to_stable_is_refused_even_though_it_is_a_real_swap(self):
        self.assertEqual("stable_swap", swaps.refusal(swaps.Swap(swaps.USDC, 10_000_000, swaps.USDT, 9_990_000)))
        self.assertIsNone(swaps.refusal(swaps.Swap(swaps.WSOL, 10 ** 9, swaps.USDC, 150_000_000)))

    def test_value_comes_from_a_stablecoin_leg_or_the_sol_price_or_is_unknown(self):
        self.assertEqual(7.5, swaps.usd_value(swaps.Swap(swaps.USDC, 7_500_000, "x" * 44, 1), None))
        self.assertAlmostEqual(180.0, swaps.usd_value(swaps.Swap(swaps.WSOL, 10 ** 9, "x" * 44, 1), 180.0))
        self.assertIsNone(swaps.usd_value(swaps.Swap(swaps.WSOL, 10 ** 9, "x" * 44, 1), None))
        self.assertIsNone(swaps.usd_value(swaps.Swap("a" * 44, 1, "b" * 44, 1), 180.0))


def offer(**kw):
    base = dict(id="jup", funder="duffle", app="Jupiter", what="Swap", address="", token="SOL", minimum=0,
                reward=300, budget=6000, starts=NOW - 100, ends=NOW + 10 ** 7, window_hours=48.0,
                kind="swap", params={"min_usd": 5})
    base.update(kw)
    return Offer(**base)


class SettleSeenTest(unittest.TestCase):
    def setUp(self):
        self.claim = Claim("jup", ME, NOW)

    def settle(self, seen, now=NOW + 3600, spent=0, **kw):
        return offers.settle_seen(offer(**kw), self.claim, seen, now, spent)

    def test_a_real_swap_after_the_claim_pays(self):
        d = self.settle([Seen("sig1", NOW + 600, 12.0)])
        self.assertEqual((300, "sig1"), (d.skr, d.signature))

    def test_before_the_claim_is_not_attribution(self):
        self.assertEqual("before_claim", self.settle([Seen("s", NOW - 5, 50.0)]).refused)

    def test_after_the_window_does_not_pay(self):
        self.assertEqual("outside_window", self.settle([Seen("s", NOW + 49 * 3600, 50.0)], now=NOW + 50 * 3600).refused)

    def test_under_the_dollar_floor_does_not_pay(self):
        self.assertEqual("below_minimum", self.settle([Seen("s", NOW + 60, 4.99)]).refused)

    def test_a_stable_swap_does_not_pay(self):
        self.assertEqual("stable_swap", self.settle([Seen("s", NOW + 60, 50.0, "stable_swap")]).refused)

    def test_an_unpriced_swap_waits_it_is_not_refused_or_paid(self):
        d = self.settle([Seen("s", NOW + 60, None)])
        self.assertEqual((0, "unpriced"), (d.skr, d.refused))

    def test_a_good_swap_after_a_bad_one_still_pays(self):
        d = self.settle([Seen("a", NOW + 10, 1.0), Seen("b", NOW + 20, 20.0)])
        self.assertEqual("b", d.signature)

    def test_the_offers_own_budget_is_a_hard_ceiling(self):
        self.assertEqual(100, self.settle([Seen("s", NOW + 60, 20.0)], spent=5900).skr)
        self.assertEqual("offer_budget", self.settle([Seen("s", NOW + 60, 20.0)], spent=6000).refused)

    def test_nothing_after_the_window_expires_the_claim(self):
        self.assertEqual("outside_window", self.settle([], now=NOW + 49 * 3600).refused)


class EndToEndTest(unittest.TestCase):
    """The whole path through the service: claim, the chain shows a swap, the reward is written."""

    def setUp(self):
        self.store = Store()

        class Facts:
            def is_seeker(self, a): return True
            def first_seen(self, a): return 0.0

        class Prices:
            def sol_usd(self): return 180.0

        self.rpc = FakeRpc()
        self.svc = Service(self.store, self.rpc, facts=Facts(), prices=Prices())
        self.svc.put_offer({"id": "jup", "app": "Jupiter", "what": "Swap on Jupiter", "kind": "swap",
                            "reward": 300, "budget": 600, "params": {"min_usd": 5}, "starts": NOW - 1000,
                            "ends": NOW + 10 ** 7})
        self.f = fixture(1)

    def serve_chain(self, address, at):
        sig = self.f["signature"]
        self.rpc.signatures_with_time = lambda a, limit=20: [{"signature": sig, "blockTime": at}]
        self.rpc.transaction = lambda s: self.f["result"] if s == sig else None
        return sig

    def claim_as(self, address, when):
        import time as _t
        real = _t.time
        _t.time = lambda: when
        try:
            return self.svc.claim_offer("jup", address)
        finally:
            _t.time = real

    def test_a_swap_after_the_claim_pays_300_into_the_ledger_with_the_signature_as_proof(self):
        who = self.f["payer"]
        self.claim_as(who, NOW)
        sig = self.serve_chain(who, NOW + 120)
        self.assertEqual(1, self.svc.check_claims(now=NOW + 200))
        row = self.store.claim("jup", who)
        self.assertEqual(("paid", 300, sig), (row["state"], row["skr"], row["signature"]))
        scratch_all(self.store)
        self.assertEqual(300, self.store.reward_balance(who, NOW + 200)["total"])

    def test_the_same_swap_cannot_be_claimed_by_a_second_wallet(self):
        who = self.f["payer"]
        self.claim_as(who, NOW)
        sig = self.serve_chain(who, NOW + 120)
        self.claim_as(ME, NOW)
        self.svc.check_claims(now=NOW + 200)
        self.assertEqual("paid", self.store.claim("jup", who)["state"])
        self.assertNotEqual("paid", self.store.claim("jup", ME)["state"])

    def test_a_swap_made_before_opening_the_offer_does_not_pay(self):
        who = self.f["payer"]
        self.claim_as(who, NOW)
        self.serve_chain(who, NOW - 500)
        self.svc.check_claims(now=NOW + 200)
        self.assertNotEqual("paid", self.store.claim("jup", who)["state"])

    def test_an_offer_pays_each_wallet_once(self):
        who = self.f["payer"]
        self.claim_as(who, NOW)
        self.serve_chain(who, NOW + 120)
        self.svc.check_claims(now=NOW + 200)
        self.svc.check_claims(now=NOW + 400)
        scratch_all(self.store)
        self.assertEqual(300, self.store.reward_balance(who, NOW + 400)["total"])
        self.assertEqual("already_claimed", self.claim_as(who, NOW + 500)["refused"])

    def test_the_budget_stops_the_offer_for_everyone_after_it(self):
        self.store.put_offer({**self.store.offer("jup"), "budget": 300, "params": {"min_usd": 5}})
        who = self.f["payer"]
        self.claim_as(who, NOW)
        self.serve_chain(who, NOW + 120)
        self.svc.check_claims(now=NOW + 200)
        self.assertEqual("offer_budget", self.claim_as(ME, NOW + 300)["refused"])

    def test_a_dead_rpc_defers_it_never_pays_or_refuses(self):
        from duffleserver import chain
        who = self.f["payer"]
        self.claim_as(who, NOW)
        def boom(a, limit=20): raise chain.Offline("down")
        self.rpc.signatures_with_time = boom
        self.assertEqual(0, self.svc.check_claims(now=NOW + 200))
        self.assertEqual("open", self.store.claim("jup", who)["state"])


class ShopOfferTest(unittest.TestCase):
    def setUp(self):
        self.store = Store()

        class Facts:
            def is_seeker(self, a): return True
            def first_seen(self, a): return 0.0
        self.svc = Service(self.store, FakeRpc(), facts=Facts())
        self.svc.put_offer({"id": "card", "app": "AirPay", "what": "Buy a gift card", "kind": "shop",
                            "reward": 100, "budget": 1000, "params": {"scope": "gift", "min_usd": 0.5},
                            "starts": NOW - 1000, "ends": NOW + 10 ** 7})

    def claim(self, when=NOW):
        import time as _t
        real = _t.time
        _t.time = lambda: when
        try:
            return self.svc.claim_offer("card", ME)
        finally:
            _t.time = real

    def buy(self, inv, at, category="gift", amount=1_100_000, deliver=True):
        self.store.remember_purchase(inv, ME, "p", category, amount)
        self.store._db.execute("UPDATE purchases SET created_at=? WHERE invoice_id=?", (at, inv))
        if deliver:
            self.store.settle_purchase(inv, "all_delivered", "CODE")
            self.store._db.execute("UPDATE purchases SET delivered_at=? WHERE invoice_id=?", (at + 30, inv))
        self.store._db.commit()

    def test_a_delivered_purchase_after_the_claim_pays(self):
        self.claim()
        self.buy("inv1", NOW + 60)
        self.assertEqual(1, self.svc.check_claims(now=NOW + 300))
        scratch_all(self.store)
        self.assertEqual(100, self.store.reward_balance(ME, NOW + 300)["total"])

    def test_a_purchase_that_was_paid_for_but_never_delivered_does_not_pay(self):
        self.claim()
        self.buy("inv1", NOW + 60, deliver=False)
        self.svc.check_claims(now=NOW + 300)
        scratch_all(self.store)
        self.assertEqual(0, self.store.reward_balance(ME, NOW + 300)["total"])

    def test_the_wrong_kind_of_purchase_does_not_pay(self):
        self.claim()
        self.buy("inv1", NOW + 60, category="recharge")
        self.svc.check_claims(now=NOW + 300)
        scratch_all(self.store)
        self.assertEqual(0, self.store.reward_balance(ME, NOW + 300)["total"])

    def test_a_purchase_from_before_the_offer_does_not_pay(self):
        self.buy("inv0", NOW - 600)
        self.claim()
        self.svc.check_claims(now=NOW + 300)
        scratch_all(self.store)
        self.assertEqual(0, self.store.reward_balance(ME, NOW + 300)["total"])

    def test_dust_purchases_do_not_pay(self):
        self.claim()
        self.buy("inv1", NOW + 60, amount=100_000)          # 0.10 USDC, under the 0.50 floor
        self.svc.check_claims(now=NOW + 300)
        scratch_all(self.store)
        self.assertEqual(0, self.store.reward_balance(ME, NOW + 300)["total"])

    def test_someone_elses_purchase_does_not_pay_me(self):
        self.claim()
        other = "b7aXLq81gY1SSdRwn1jUCyQeB282GJwrzBVCsJ3nFK2"
        self.store.remember_purchase("inv1", other, "p", "gift", 1_100_000)
        self.store.settle_purchase("inv1", "all_delivered", "C")
        self.svc.check_claims(now=NOW + 300)
        scratch_all(self.store)
        self.assertEqual(0, self.store.reward_balance(ME, NOW + 300)["total"])


class OfferValidationTest(unittest.TestCase):
    def setUp(self):
        self.svc = Service(Store(), FakeRpc())

    def put(self, **kw):
        return self.svc.put_offer({"id": "x", "app": "A", "what": "W", "reward": 1, **kw})

    def test_unknown_kind_params_scope_and_urls_are_refused(self):
        for bad in ({"kind": "lottery"}, {"kind": "swap", "params": {"program": "nope"}},
                    {"kind": "shop", "params": {"scope": "everything"}},
                    {"kind": "swap", "params": {"min_usd": -1}}, {"kind": "swap", "params": {"min_usd": 10 ** 9}},
                    {"kind": "swap", "params": {"store_url": "http://insecure"}},
                    {"kind": "swap", "params": {"package": "bad package!"}}, {"kind": "pay"}):
            with self.assertRaises(ValueError, msg=str(bad)):
                self.put(**bad)

    def test_unknown_params_are_dropped_not_stored(self):
        out = self.put(kind="swap", params={"min_usd": 5, "evil": "x", "reward": 10 ** 9})
        self.assertEqual({"min_usd": 5.0, "program": swaps.JUPITER_V6}, out["offer"]["params"])

    def test_a_non_pay_offer_needs_no_address(self):
        self.assertTrue(self.put(kind="shop")["ok"])


if __name__ == "__main__":
    unittest.main()


class SeedTest(unittest.TestCase):
    def test_every_launch_offer_passes_the_same_validation_as_any_offer(self):
        from duffleserver.seed import launch_offers
        svc = Service(Store(), FakeRpc())
        for o in launch_offers(NOW):
            self.assertTrue(svc.put_offer(o)["ok"], o["id"])
        self.assertEqual(3, len(svc.store.offers()))
        # Idempotent: seeding twice leaves three, not six.
        for o in launch_offers(NOW):
            svc.put_offer(o)
        self.assertEqual(3, len(svc.store.offers()))
        self.assertTrue(all(r["funder"] == "duffle" for r in svc.store.offers()))
