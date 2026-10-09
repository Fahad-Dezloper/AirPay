"""
The newer offer conditions, attacked the same way as the older ones: each test is a way to be paid
without doing the thing, or to be refused after doing it.

Counted purchases, purchases paid in SOL, paying several different people, paying by tap, staking
SKR, scratch cards that pay a variable amount, and the text the app is sent for each offer.
"""
from __future__ import annotations

import copy
import json
import os
import time
import unittest

from duffleserver import offers, seed, stakes, swaps
from duffleserver.offers import Claim, Offer, Seen
from duffleserver.service import Service
from duffleserver.shop import Shop
from duffleserver.store import Store
from tests.revealutil import scratch_all
from tests.test_server import FakeRpc

FIX = os.path.join(os.path.dirname(__file__), "fixtures")
ME = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
MINE_TOO = "b7aXLq81gY1SSdRwn1jUCyQeB282GJwrzBVCsJ3nFK2"
A = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
B = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
C = "5Q544fKrFoe6tsEbD7S8EmxGTJYAKtTVhAW5Q5pge4j1"
A_SECOND = "DYw8jCTfwHNRJhhmFcbXvVDTqWMEVFBX6ZKUmG5CNSKK"
NEWCOMER = "3Nxwen3TgHiQp1kCFPfSkQYeqYg5ya5pYfEeB5RRx3wm"
USDC = swaps.USDC
NOW = 1_800_000_000.0
DAY = 86_400.0


class Facts:
    """Everybody is a Seeker except NEWCOMER, whose wallet the chain has never seen."""

    def __init__(self, old=()):
        self.old = set(old)

    def is_seeker(self, a):
        return a != NEWCOMER

    def first_seen(self, a):
        return 0.0 if a in self.old else None


class Prices:
    def __init__(self, sol=200.0):
        self.sol = sol

    def sol_usd(self):
        return self.sol


def ref(n: int) -> str:
    """A distinct, valid-looking reference for each payment."""
    alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    return "Ref" + alphabet[n % 58] * 41


class Base(unittest.TestCase):
    def setUp(self):
        self.store = Store()
        self.facts = Facts()
        self.prices = Prices()
        self.rpc = FakeRpc()
        self.svc = Service(self.store, self.rpc, facts=self.facts, prices=self.prices)
        self.n = 0

    def at(self, when, fn, *args):
        real = time.time
        time.time = lambda: when
        try:
            return fn(*args)
        finally:
            time.time = real

    def put(self, **kw):
        base = {"id": "o", "app": "AirPay", "what": "Do the thing", "reward": 100, "budget": 1000,
                "window_hours": 720, "starts": NOW - 1000, "ends": NOW + 60 * DAY}
        return self.svc.put_offer({**base, **kw})

    def claim(self, offer_id="o", who=ME, when=NOW):
        out = self.at(when, self.svc.claim_offer, offer_id, who)
        self.assertTrue(out["ok"], out)
        return out

    def check(self, when):
        return self.at(when, self.svc.check_claims, when)

    def row(self, offer_id="o", who=ME, when=NOW + 100):
        return [o for o in self.at(when, self.svc.offers_for, who) if o["id"] == offer_id][0]

    def balance(self, who=ME):
        scratch_all(self.store)
        return self.store.reward_balance(who, NOW + 40 * DAY)["total"]

    def pay(self, to, at, units=1_000_000, token=USDC, decimals=6, origin="", payer=ME):
        """A settled request in the server's own records, the way a real payment leaves one."""
        self.n += 1
        r = ref(self.n)
        self.store.register(r, to, "x", units, "", payer, None, token, decimals, "T", origin)
        self.assertTrue(self.store.mark_paid(r, f"sig{self.n}"))
        with self.store._lock, self.store._db:
            self.store._db.execute("UPDATE requests SET paid_at=? WHERE reference=?", (at, r))
        return r

    def buy(self, inv, at, category="recharge", amount=None, usd=None, deliver=True):
        self.store.remember_purchase(inv, ME, "p", category, amount, usd)
        self.store._db.execute("UPDATE purchases SET created_at=? WHERE invoice_id=?", (at, inv))
        if deliver:
            self.store.settle_purchase(inv, "all_delivered", "CODE")
            self.store._db.execute("UPDATE purchases SET delivered_at=? WHERE invoice_id=?", (at + 30, inv))
        self.store._db.commit()


class CountedShopTest(Base):
    def setUp(self):
        super().setUp()
        self.put(kind="shop", params={"scope": "recharge", "count": 2, "min_usd": 2})
        self.claim()

    def test_one_purchase_is_not_enough_and_is_reported_as_one_of_two(self):
        self.buy("inv1", NOW + 60, usd=3.0)
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual("open", self.store.claim("o", ME)["state"])
        self.assertEqual({"done": 1, "need": 2}, self.row()["progress"])

    def test_the_second_purchase_pays_once(self):
        self.buy("inv1", NOW + 60, usd=3.0)
        self.buy("inv2", NOW + 3600, usd=2.5)
        self.assertEqual(1, self.check(NOW + 4000))
        self.assertEqual(0, self.check(NOW + 5000))
        self.assertEqual(100, self.balance())
        self.assertEqual("inv2", self.store.claim("o", ME)["signature"])
        self.assertEqual({"done": 2, "need": 2}, self.row()["progress"])

    def test_a_purchase_under_the_floor_or_undelivered_or_of_the_wrong_kind_is_not_counted(self):
        self.buy("inv1", NOW + 60, usd=3.0)
        self.buy("cheap", NOW + 70, usd=1.0)
        self.buy("pending", NOW + 80, usd=3.0, deliver=False)
        self.buy("card", NOW + 90, usd=30.0, category="gift")
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual({"done": 1, "need": 2}, self.row()["progress"])

    def test_a_purchase_from_before_the_claim_is_not_counted(self):
        self.buy("old", NOW - 600, usd=3.0)
        self.buy("inv1", NOW + 60, usd=3.0)
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual({"done": 1, "need": 2}, self.row()["progress"])

    def test_half_done_when_the_window_shuts_expires_and_pays_nothing(self):
        self.buy("inv1", NOW + 60, usd=3.0)
        self.assertEqual(0, self.check(NOW + 31 * DAY))
        self.assertEqual("expired", self.store.claim("o", ME)["state"])
        self.assertEqual(0, self.balance())

    def test_count_is_validated(self):
        for bad in (0, 21, "2", 1.5, True):
            with self.assertRaises(ValueError, msg=repr(bad)):
                self.put(id="bad", kind="shop", params={"count": bad})


class SolPurchaseTest(Base):
    """A purchase paid in SOL has no USDC amount. Its dollar value is recorded beside it."""

    def setUp(self):
        super().setUp()
        self.put(kind="shop", params={"scope": "recharge", "min_usd": 2})
        self.claim()

    def test_a_sol_paid_purchase_counts_through_its_recorded_dollar_value(self):
        self.buy("inv1", NOW + 60, amount=None, usd=2.4)
        self.assertEqual(1, self.check(NOW + 300))
        self.assertEqual(100, self.balance())

    def test_a_sol_paid_purchase_under_the_floor_does_not_count(self):
        self.buy("inv1", NOW + 60, amount=None, usd=1.2)
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual("below_minimum", self.store.claim("o", ME)["refused"])

    def test_a_purchase_with_no_known_value_waits_when_there_is_a_floor(self):
        self.buy("inv1", NOW + 60, amount=None, usd=None)
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual(("open", "unpriced"), tuple(self.store.claim("o", ME)[k] for k in ("state", "refused")))

    def test_a_purchase_with_no_known_value_counts_when_there_is_no_floor(self):
        for i, params in enumerate(({"scope": "recharge"}, {"scope": "recharge", "min_usd": 0})):
            self.put(id=f"free{i}", kind="shop", params=params)
            self.claim(f"free{i}")
        self.buy("inv1", NOW + 60, amount=None, usd=None)
        self.check(NOW + 300)
        self.assertEqual(["paid", "paid"], [self.store.claim(f"free{i}", ME)["state"] for i in (0, 1)])

    def test_a_row_recorded_before_the_dollar_value_existed_still_counts_by_its_usdc_amount(self):
        self.buy("inv1", NOW + 60, amount=2_500_000, usd=None)
        self.assertEqual(1, self.check(NOW + 300))

    def test_an_old_database_gains_the_column_and_keeps_its_rows(self):
        import sqlite3
        import tempfile
        path = os.path.join(tempfile.mkdtemp(), "old.db")
        Store(path)._db.close()
        db = sqlite3.connect(path)
        db.execute("ALTER TABLE purchases DROP COLUMN usd")
        db.execute("INSERT INTO purchases(invoice_id,buyer,status) VALUES ('keep','b','created')")
        db.commit()
        db.close()
        store = Store(path)
        cols = {r[1] for r in store._db.execute("PRAGMA table_info(purchases)")}
        self.assertIn("usd", cols)
        self.assertEqual(1, store._db.execute("SELECT COUNT(*) FROM purchases WHERE invoice_id='keep'").fetchone()[0])
        store._db.close()


class UsdEstimateTest(unittest.TestCase):
    """The estimate recorded for a purchase that was not paid in USDC."""

    def setUp(self):
        self.shop = Shop("key")
        self.units = Shop.price_units
        self.addCleanup(setattr, Shop, "price_units", self.units)
        Shop.price_units = 1000.0
        self.shop._catalog = (time.time(), [
            {"id": "jio", "packages": [{"id": "pk219", "value": "219", "amount": 219, "price": 2600},
                                       {"id": "pk19", "value": "19", "amount": 19, "price": 230}]},
            {"id": "bare", "packages": []},
        ])

    def test_a_package_is_priced_from_the_catalogue_by_id_or_by_value(self):
        self.assertEqual(2.60, self.shop.usd_estimate("jio", "pk219", None))
        self.assertEqual(0.23, self.shop.usd_estimate("jio", None, "19"))
        self.assertEqual(0.23, self.shop.usd_estimate("jio", None, 19))

    def test_a_free_amount_is_scaled_from_the_nearest_package(self):
        self.assertAlmostEqual(500 * 2600 / 219 / 1000, self.shop.usd_estimate("jio", None, 500), places=2)

    def test_what_cannot_be_priced_is_none_not_a_guess(self):
        for args in (("nope", None, "219"), ("jio", "no-such-package", None), ("bare", None, "100"),
                     ("jio", None, "2GB a day"), ("jio", None, None)):
            self.assertIsNone(self.shop.usd_estimate(*args), args)


class PeopleTest(Base):
    def setUp(self):
        super().setUp()
        self.put(kind="people", params={"count": 3, "min_usd": 1}, reward=250, budget=7500)

    def test_paying_three_different_people_pays_and_progress_is_reported_on_the_way(self):
        self.claim()
        self.assertEqual({"done": 0, "need": 3}, self.row()["progress"])
        self.pay(A, NOW + 60)
        self.pay(B, NOW + 120)
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual({"done": 2, "need": 3}, self.row()["progress"])
        self.pay(C, NOW + 400)
        self.assertEqual(1, self.check(NOW + 500))
        self.assertEqual(250, self.balance())
        self.assertEqual({"done": 3, "need": 3}, self.row()["progress"])
        self.assertEqual("sig3", self.store.claim("o", ME)["signature"])

    def test_paying_the_same_person_three_times_is_one_person(self):
        self.claim()
        for i in range(3):
            self.pay(A, NOW + 60 * (i + 1))
        self.assertEqual(0, self.check(NOW + 500))
        self.assertEqual({"done": 1, "need": 3}, self.row()["progress"])

    def test_two_wallets_of_one_person_are_one_person(self):
        acct = self.store.ensure_account(A, NOW - DAY)
        self.store.link_wallet(acct, A_SECOND, NOW - DAY)
        self.claim()
        self.pay(A, NOW + 60)
        self.pay(A_SECOND, NOW + 120)
        self.pay(B, NOW + 180)
        self.assertEqual(0, self.check(NOW + 500))
        self.assertEqual({"done": 2, "need": 3}, self.row()["progress"])

    def test_my_own_linked_wallet_does_not_count(self):
        acct = self.store.ensure_account(ME, NOW - DAY)
        self.store.link_wallet(acct, MINE_TOO, NOW - DAY)
        self.claim()
        self.pay(A, NOW + 60)
        self.pay(B, NOW + 120)
        self.pay(MINE_TOO, NOW + 180)
        self.assertEqual(0, self.check(NOW + 500))
        self.assertEqual({"done": 2, "need": 3}, self.row()["progress"])
        self.assertEqual("self_payment", self.store.claim("o", ME)["refused"])

    def test_a_payment_below_the_minimum_does_not_count(self):
        self.claim()
        self.pay(A, NOW + 60)
        self.pay(B, NOW + 120)
        self.pay(C, NOW + 180, units=990_000)                     # 0.99 USDC
        self.assertEqual(0, self.check(NOW + 500))
        self.assertEqual("below_minimum", self.store.claim("o", ME)["refused"])

    def test_payments_before_the_claim_do_not_count(self):
        self.pay(A, NOW - 600)
        self.pay(B, NOW - 300)
        self.claim()
        self.pay(C, NOW + 60)
        self.assertEqual(0, self.check(NOW + 500))
        self.assertEqual({"done": 1, "need": 3}, self.row()["progress"])

    def test_payments_after_the_window_do_not_count(self):
        self.claim()
        self.pay(A, NOW + 60)
        self.pay(B, NOW + 120)
        self.pay(C, NOW + 31 * DAY)
        self.assertEqual(0, self.check(NOW + 31 * DAY + 60))
        self.assertEqual("expired", self.store.claim("o", ME)["state"])

    def test_somebody_elses_payments_are_not_mine(self):
        self.claim()
        for i, to in enumerate((A, B, C)):
            self.pay(to, NOW + 60 * (i + 1), payer=MINE_TOO)
        self.assertEqual(0, self.check(NOW + 500))
        self.assertEqual({"done": 0, "need": 3}, self.row()["progress"])

    def test_an_unpaid_request_is_not_a_payment(self):
        self.claim()
        self.pay(A, NOW + 60)
        self.pay(B, NOW + 120)
        self.store.register(ref(50), C, "x", 1_000_000, "", ME, None, USDC, 6, "T", "")
        self.assertEqual(0, self.check(NOW + 500))

    def test_a_brand_new_wallet_does_not_count_unless_it_is_a_seeker_or_old_enough(self):
        # The tap rewards' own rule: a wallet costs nothing to make.
        self.claim()
        self.pay(A, NOW + 60)
        self.pay(B, NOW + 120)
        self.pay(NEWCOMER, NOW + 180)
        self.assertEqual(0, self.check(NOW + 500))
        self.assertEqual("new_counterparty", self.store.claim("o", ME)["refused"])
        self.facts.old.add(NEWCOMER)                               # first seen long ago
        self.assertEqual(1, self.check(NOW + 700))

    def test_sol_is_valued_at_the_price_feed(self):
        self.claim()
        self.pay(A, NOW + 60)
        self.pay(B, NOW + 120)
        self.pay(C, NOW + 180, units=4_000_000, token="SOL", decimals=9)       # 0.004 SOL = $0.80
        self.assertEqual(0, self.check(NOW + 500))
        self.assertEqual("below_minimum", self.store.claim("o", ME)["refused"])
        self.pay(C, NOW + 600, units=6_000_000, token="SOL", decimals=9)       # 0.006 SOL = $1.20
        self.assertEqual(1, self.check(NOW + 700))

    def test_with_no_price_a_sol_payment_waits_unless_the_offer_has_no_floor(self):
        self.prices.sol = None
        self.put(id="free", kind="people", params={"count": 1, "min_usd": 0})
        self.claim()
        self.claim("free")
        for i, to in enumerate((A, B, C)):
            self.pay(to, NOW + 60 * (i + 1), units=50_000_000, token="SOL", decimals=9)
        self.assertEqual(1, self.check(NOW + 500))
        self.assertEqual("paid", self.store.claim("free", ME)["state"])
        self.assertEqual(("open", "unpriced"), tuple(self.store.claim("o", ME)[k] for k in ("state", "refused")))

    def test_left_unsaid_it_is_three_people(self):
        self.put(id="plain", kind="people", params={})
        self.claim("plain")
        self.assertEqual({"done": 0, "need": 3}, self.row("plain")["progress"])
        self.assertIn("Pay 3 different people in AirPay.", self.row("plain")["steps"])


class TapTest(Base):
    def setUp(self):
        super().setUp()
        self.put(kind="tap", params={"min_usd": 1}, reward=60, budget=3600)
        self.claim()

    def test_a_payment_made_by_tap_pays(self):
        self.pay(A, NOW + 60, origin="tap")
        self.assertEqual(1, self.check(NOW + 300))
        self.assertEqual(60, self.balance())
        self.assertIsNone(self.row()["progress"])

    def test_a_payment_that_was_not_a_tap_does_not_pay(self):
        self.pay(A, NOW + 60, origin="")
        self.pay(B, NOW + 90, origin="link")
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual("not_a_tap", self.store.claim("o", ME)["refused"])

    def test_tapping_my_own_other_wallet_does_not_pay(self):
        acct = self.store.ensure_account(ME, NOW - DAY)
        self.store.link_wallet(acct, MINE_TOO, NOW - DAY)
        self.pay(MINE_TOO, NOW + 60, origin="tap")
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual("self_payment", self.store.claim("o", ME)["refused"])

    def test_a_tap_under_the_minimum_or_before_the_claim_does_not_pay(self):
        self.pay(A, NOW + 60, origin="tap", units=500_000)
        self.pay(B, NOW - 60, origin="tap")
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual(0, self.balance())

    def test_a_good_tap_after_a_bad_one_still_pays(self):
        self.pay(A, NOW + 60, origin="tap", units=500_000)
        self.pay(B, NOW + 120, origin="tap")
        self.assertEqual(1, self.check(NOW + 300))


def stake_fixture(name="skr_stake"):
    return json.load(open(os.path.join(FIX, f"{name}.json")))


class StakeParserTest(unittest.TestCase):
    """Against transactions copied from mainnet, not hand-made ones."""

    def test_a_real_stake_is_read_correctly(self):
        f = stake_fixture()
        self.assertEqual(1_000_000, stakes.staked(f["result"], f["payer"]))          # 1 SKR, exactly

    def test_a_real_unstake_is_not_a_stake(self):
        f = stake_fixture("skr_unstake")
        self.assertIsNone(stakes.staked(f["result"], f["payer"]))

    def test_somebody_elses_stake_is_not_mine(self):
        self.assertIsNone(stakes.staked(stake_fixture()["result"], ME))

    def test_a_failed_transaction_never_counts(self):
        f = stake_fixture()
        f["result"]["meta"]["err"] = {"InstructionError": [0, "Custom"]}
        self.assertIsNone(stakes.staked(f["result"], f["payer"]))

    def test_skr_leaving_without_the_staking_program_is_a_transfer_not_a_stake(self):
        f = stake_fixture()
        self.assertIsNone(stakes.staked(f["result"], f["payer"], program=swaps.JUPITER_V6))

    def test_another_token_leaving_is_not_skr(self):
        f = stake_fixture()
        self.assertIsNone(stakes.staked(f["result"], f["payer"], mint=USDC))

    def test_garbage_never_crashes(self):
        for bad in (None, {}, {"meta": {}}, {"meta": None, "transaction": 5}):
            self.assertIsNone(stakes.staked(bad, ME))


class StakeOfferTest(Base):
    def setUp(self):
        super().setUp()
        self.f = stake_fixture()
        self.who = self.f["payer"]

    def serve(self, at, units=None):
        tx = copy.deepcopy(self.f["result"])
        if units is not None:
            # The same real transaction with a larger amount moved: scale the signer's balance.
            for b in tx["meta"]["preTokenBalances"]:
                if b["owner"] == self.who:
                    after = [p for p in tx["meta"]["postTokenBalances"] if p["owner"] == self.who][0]
                    b["uiTokenAmount"]["amount"] = str(int(after["uiTokenAmount"]["amount"]) + units)
        sig = self.f["signature"]
        self.rpc.signatures_with_time = lambda a, limit=20: [{"signature": sig, "blockTime": at}]
        self.rpc.transaction = lambda s: tx if s == sig else None
        return sig

    def test_a_stake_of_enough_after_the_claim_pays_with_the_signature_as_proof(self):
        self.put(kind="stake", params={"min_skr": 100})
        self.claim(who=self.who)
        sig = self.serve(NOW + 120, units=100 * stakes.SKR_UNITS)
        self.assertEqual(1, self.check(NOW + 300))
        got = self.store.claim("o", self.who)
        self.assertEqual(("paid", 100, sig), (got["state"], got["skr"], got["signature"]))

    def test_a_stake_of_too_little_does_not_pay(self):
        self.put(kind="stake", params={"min_skr": 100})
        self.claim(who=self.who)
        self.serve(NOW + 120)                                      # the real one: 1 SKR
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual("below_minimum", self.store.claim("o", self.who)["refused"])

    def test_a_stake_made_before_the_claim_does_not_pay(self):
        self.put(kind="stake", params={"min_skr": 1})
        self.claim(who=self.who)
        self.serve(NOW - 500)
        self.assertEqual(0, self.check(NOW + 300))

    def test_the_default_floor_is_100_skr_and_a_dollar_floor_is_dropped(self):
        out = self.put(kind="stake", params={"min_usd": 5})
        self.assertEqual({"min_skr": 100}, out["offer"]["params"])

    def test_a_dead_rpc_defers_it_never_pays_or_refuses(self):
        from duffleserver import chain
        self.put(kind="stake", params={"min_skr": 1})
        self.claim(who=self.who)

        def boom(a, limit=20):
            raise chain.Offline("down")
        self.rpc.signatures_with_time = boom
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual("open", self.store.claim("o", self.who)["state"])


def _blank(**kw):
    base = dict(id="o", funder="duffle", app="AirPay", what="W", address="", token="SOL", minimum=0,
                reward=200, budget=6000, starts=NOW - 100, ends=NOW + 10 ** 7, window_hours=720.0,
                kind="shop", params={})
    base.update(kw)
    return base


class DrawTest(unittest.TestCase):
    def digests(self, n=4000):
        import hashlib
        return [hashlib.sha256(str(i).encode()).digest() for i in range(n)]

    def test_every_card_is_inside_the_range_and_never_zero(self):
        got = [offers.draw(20, 200, d) for d in self.digests()]
        self.assertGreaterEqual(min(got), 20)
        self.assertLessEqual(max(got), 200)
        for low, high in ((1, 2), (0, 5), (-3, 4), (1, 1000)):
            self.assertGreaterEqual(min(offers.draw(low, high, d) for d in self.digests(500)), 1)

    def test_the_extremes_of_the_hash_are_the_extremes_of_the_range(self):
        self.assertEqual(20, offers.draw(20, 200, b"\x00" * 32))
        self.assertEqual(200, offers.draw(20, 200, b"\xff" * 32))

    def test_most_cards_are_small_and_a_few_are_near_the_top(self):
        got = sorted(offers.draw(20, 200, d) for d in self.digests())
        self.assertLess(got[len(got) // 2], 60)                    # the median is in the bottom quarter
        top = sum(1 for g in got if g >= 155)                      # the top quarter of the range
        self.assertTrue(0.03 < top / len(got) < 0.20, top)
        self.assertLess(sum(got) / len(got), 80)                   # the budget is sized on this

    def test_the_same_digest_is_always_the_same_card(self):
        d = self.digests(1)[0]
        self.assertEqual(offers.draw(20, 200, d), offers.draw(20, 200, d))

    def test_a_range_of_one_is_that_amount(self):
        self.assertEqual(50, offers.draw(50, 50, b"\x80" * 32))
        self.assertEqual(50, offers.draw(90, 50, b"\x80" * 32))


class VariableRewardTest(Base):
    def setUp(self):
        super().setUp()
        self.put(kind="shop", params={"scope": "gift", "min_usd": 5}, reward=200, reward_min=20, budget=6000)

    def wallets(self, n):
        """Distinct, well-formed addresses: 32 bytes each, in base58."""
        import hashlib
        alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        out = []
        for i in range(n):
            v, text = int.from_bytes(hashlib.sha256(f"w{i}".encode()).digest(), "big") | 1 << 255, ""
            while v:
                v, r = divmod(v, 58)
                text = alphabet[r] + text
            out.append(text)
        return out

    def buy_as(self, who, inv, at):
        self.store.remember_purchase(inv, who, "p", "gift", 6_000_000, 6.0)
        self.store._db.execute("UPDATE purchases SET created_at=? WHERE invoice_id=?", (at, inv))
        self.store.settle_purchase(inv, "all_delivered", "CODE")
        self.store._db.execute("UPDATE purchases SET delivered_at=? WHERE invoice_id=?", (at + 30, inv))
        self.store._db.commit()

    def test_the_card_is_inside_the_range_and_is_what_lands_in_the_ledger(self):
        self.claim()
        self.buy_as(ME, "inv1", NOW + 60)
        self.assertEqual(1, self.check(NOW + 300))
        paid = self.store.claim("o", ME)["skr"]
        self.assertTrue(20 <= paid <= 200, paid)
        self.assertEqual(paid, self.balance())
        self.assertEqual(paid, self.store.offer_spent("o"))        # the budget counts what was paid
        self.assertEqual(paid, self.row()["skr_earned"])

    def test_the_card_is_decided_by_the_claim_and_cannot_be_rerolled(self):
        self.claim()
        offer = Offer(**_blank(params={"reward_min": 20}))
        claim = Claim("o", ME, self.store.claim("o", ME)["claimed_at"])
        first = self.svc._reward_of(offer, claim)
        self.assertEqual([first] * 5, [self.svc._reward_of(offer, claim) for _ in range(5)])
        # Another service over the same database (a restart) deals the same card.
        again = Service(self.store, FakeRpc(), facts=self.facts, prices=self.prices)
        self.assertEqual(first, again._reward_of(offer, claim))
        self.buy_as(ME, "inv1", NOW + 60)
        self.check(NOW + 300)
        self.assertEqual(first, self.store.claim("o", ME)["skr"])
        self.check(NOW + 900)
        self.assertEqual(first, self.balance())

    def test_a_different_server_secret_deals_a_different_hand(self):
        # Which is what makes a card unpredictable from outside: the claim alone does not decide it.
        offer = Offer(**_blank(params={"reward_min": 1}, reward=1000))
        claims = [Claim("o", w, NOW) for w in self.wallets(20)]
        other = Service(Store(), FakeRpc(), facts=self.facts)
        self.assertNotEqual([self.svc._reward_of(offer, c) for c in claims],
                            [other._reward_of(offer, c) for c in claims])

    def test_many_claims_all_inside_the_range_and_not_all_the_same(self):
        self.put(kind="shop", params={"scope": "gift", "min_usd": 5}, reward=200, reward_min=20, budget=0)
        for i, w in enumerate(self.wallets(40)):
            self.claim(who=w)
            self.buy_as(w, f"inv{i}", NOW + 60)
        for i in range(4):                                         # the watcher takes 20 a pass
            self.check(NOW + 300 + 100 * i)
        paid = [r[0] for r in self.store._db.execute("SELECT skr FROM claims WHERE state='paid'")]
        self.assertEqual(40, len(paid))
        self.assertTrue(all(20 <= p <= 200 for p in paid), paid)
        self.assertGreater(len(set(paid)), 5)

    def test_the_budget_is_never_exceeded(self):
        # Room for the largest card twice over. Whatever is dealt, the total stays under it, and
        # nobody may open the offer once the largest card would no longer fit.
        self.put(kind="shop", params={"scope": "gift", "min_usd": 5}, reward=200, reward_min=20, budget=400)
        for i, w in enumerate(self.wallets(30)):
            out = self.at(NOW + i, self.svc.claim_offer, "o", w)
            if out["ok"]:
                self.buy_as(w, f"inv{i}", NOW + 60 + i)
            for _ in range(3):
                self.check(NOW + 4000 + 100 * i)
            self.assertLessEqual(self.store.offer_spent("o"), 400)
        self.assertLessEqual(self.store.offer_spent("o"), 400)
        self.assertGreater(self.store.offer_spent("o"), 200)
        refused = self.at(NOW + 9000, self.svc.claim_offer, "o", self.wallets(31)[30])
        self.assertEqual("offer_budget", refused["refused"])
        ledger = self.store._db.execute("SELECT COALESCE(SUM(skr),0) FROM rewards WHERE kind='offer'").fetchone()[0]
        self.assertEqual(self.store.offer_spent("o"), ledger)

    def test_a_fixed_offer_still_pays_exactly_its_reward(self):
        self.put(id="flat", kind="shop", params={"scope": "gift"}, reward=50)
        self.claim("flat")
        self.buy_as(ME, "inv1", NOW + 60)
        self.check(NOW + 300)
        self.assertEqual(50, self.store.claim("flat", ME)["skr"])

    def test_reward_and_reward_min_are_validated(self):
        for bad in ({"reward_min": 0}, {"reward_min": -5}, {"reward_min": 300, "reward": 200},
                    {"reward_min": "20"}, {"reward_min": 2.5}, {"reward": 1001}):
            with self.assertRaises(ValueError, msg=str(bad)):
                self.put(id="bad", kind="shop", **bad)
        self.assertTrue(self.put(id="edge", kind="shop", reward=1000, reward_min=1000)["ok"])


class ResponseFieldsTest(Base):
    """What the app is sent. It builds the offer page from these and nothing else."""

    def seeded(self):
        for o in seed.launch_offers(NOW - 10):
            self.svc.put_offer(o)
        return {o["id"]: o for o in self.at(NOW, self.svc.offers_for, ME)}

    def test_every_seeded_offer_carries_the_whole_contract(self):
        rows = self.seeded()
        self.assertEqual(9, len(rows))
        for o in rows.values():
            self.assertIn(o["kind"], offers.KINDS)
            self.assertTrue(2 <= len(o["steps"]) <= 3, o["steps"])
            self.assertTrue(o["rules"] and all(isinstance(r, str) and r for r in o["rules"]))
            self.assertEqual({"label", "target"}, set(o["cta"]))
            self.assertIn(o["cta"]["target"], offers.CTA_TARGETS)
            self.assertIsNone(o["progress"])                       # nothing claimed yet
            self.assertEqual("available", o["state"])
            if o["cta"]["target"] == "link":
                self.assertTrue(o["link"].startswith("https://"))
            for text in [o["what"], o["reward_text"], o["cta"]["label"], *o["steps"], *o["rules"]]:
                self.assertNotIn("—", text)                   # no em dash reaches a screen
                self.assertNotIn("–", text)
            self.assertLessEqual(len(o["what"]), 120)
            self.assertLessEqual(o["reward"], 1000)                # the payer's cap on one payout
            # Every field the app already reads is still there.
            for key in ("id", "app", "what", "reward", "budget", "spent", "params", "link", "icon", "featured",
                        "notified_at", "skr_earned", "signature", "refused", "claims_7d", "claimed_at",
                        "window_hours", "starts", "ends", "funder"):
                self.assertIn(key, o)

    def test_the_seeded_wording_and_buttons(self):
        rows = self.seeded()
        want = {
            "airpay-top-up": ("recharge", "Flat 50 SKR", "Recharge any phone in AirPay, $2 or more."),
            "airpay-gift-card": ("shop", "Up to 200 SKR", "Buy a gift card in AirPay, $5 or more."),
            "jupiter-first-swap": ("link", "Flat 75 SKR", "Swap $10 or more into USDC on Jupiter."),
            "airpay-recharge-again": ("recharge", "Up to 150 SKR", "Recharge a phone in AirPay 2 times, $2 or more each."),
            "airpay-pay-three": ("pay", "Up to 250 SKR", "Pay 3 different people in AirPay, $1 or more each."),
            "airpay-tap-a-friend": ("tap", "Flat 60 SKR", "Tap a friend's phone and pay them $1 or more."),
            "skr-stake": ("link", "Flat 100 SKR", "Stake 100 SKR or more."),
        }
        for oid, (target, reward_text, action) in want.items():
            o = rows[oid]
            self.assertEqual(target, o["cta"]["target"], oid)
            self.assertEqual(reward_text, o["reward_text"], oid)
            self.assertEqual(["Claim this offer.", action, "Scratch your card."], o["steps"], oid)
        self.assertEqual(["At least $2", "Finish within 30 days of claiming", "One per person",
                          "Counts from when you claim"], rows["airpay-top-up"]["rules"])
        self.assertIn("Finish within 3 days of claiming", rows["jupiter-first-swap"]["rules"])
        self.assertIn("Finish within 7 days of claiming", rows["skr-stake"]["rules"])
        self.assertIn("Every card pays 25 to 250 SKR", rows["airpay-pay-three"]["rules"])
        self.assertIn("3 different people", rows["airpay-pay-three"]["rules"])
        self.assertEqual(20, rows["airpay-gift-card"]["reward_min"])
        self.assertIsNone(rows["airpay-top-up"]["reward_min"])
        self.assertIsNone(rows["airpay-top-up"]["icon"])           # the app draws AirPay's own mark
        self.assertTrue(rows["jupiter-first-swap"]["icon"].startswith("https://"))
        self.assertEqual("https://stake.solanamobile.com", rows["skr-stake"]["link"])

    def test_progress_appears_only_on_a_claimed_offer_that_counts_something(self):
        self.seeded()
        for oid in ("airpay-pay-three", "airpay-recharge-again", "airpay-top-up"):
            self.claim(oid)
        self.pay(A, NOW + 60)
        rows = {o["id"]: o for o in self.at(NOW + 100, self.svc.offers_for, ME)}
        self.assertEqual({"done": 1, "need": 3}, rows["airpay-pay-three"]["progress"])
        self.assertEqual({"done": 0, "need": 2}, rows["airpay-recharge-again"]["progress"])
        self.assertIsNone(rows["airpay-top-up"]["progress"])       # claimed, but a single action
        self.assertIsNone(rows["airpay-gift-card"]["progress"])    # not claimed
        self.assertEqual("open", rows["airpay-pay-three"]["state"])
        # Somebody else, and nobody at all, see no progress.
        self.assertIsNone([o for o in self.at(NOW + 100, self.svc.offers_for, A)
                           if o["id"] == "airpay-pay-three"][0]["progress"])
        self.assertTrue(all(o["progress"] is None for o in self.at(NOW + 100, self.svc.offers_for, "")))

    def test_an_offer_can_bring_its_own_steps_and_button(self):
        self.put(kind="shop", params={"scope": "gift"}, steps=["Claim it.", "Buy a Hotstar code."],
                 cta={"label": "See codes", "target": "shop"})
        o = self.row()
        self.assertEqual(["Claim it.", "Buy a Hotstar code."], o["steps"])
        self.assertEqual({"label": "See codes", "target": "shop"}, o["cta"])

    def test_bad_steps_and_buttons_are_refused(self):
        for bad in ({"steps": ["only one"]}, {"steps": ["a", "b", "c", "d"]}, {"steps": "Claim it."},
                    {"steps": ["ok", ""]}, {"steps": ["ok", "x" * 101]}, {"cta": "browser"},
                    {"cta": {"label": "x" * 25, "target": "shop"}}, {"cta": "link"},
                    {"cta": "link", "link": "http://insecure.example"}):
            with self.assertRaises(ValueError, msg=str(bad)):
                self.put(id="bad", kind="shop", **bad)

    def test_a_swap_offer_can_require_usdc_to_be_what_is_received(self):
        f = json.load(open(os.path.join(FIX, "jupiter_swap_1.json")))       # SOL into ORE, not USDC
        who = f["payer"]
        self.put(id="any", kind="swap", params={"min_usd": 5})
        self.put(id="usdc", kind="swap", params={"min_usd": 5, "receive": "USDC"})
        self.claim("any", who)
        self.claim("usdc", who)
        self.rpc.signatures_with_time = lambda a, limit=20: [{"signature": f["signature"], "blockTime": NOW + 60}]
        self.rpc.transaction = lambda s: f["result"]
        self.check(NOW + 300)
        self.assertEqual("paid", self.store.claim("any", who)["state"])
        self.assertEqual(("open", "wrong_token"),
                         tuple(self.store.claim("usdc", who)[k] for k in ("state", "refused")))
        with self.assertRaises(ValueError):
            self.put(id="bad", kind="swap", params={"receive": "BONK"})


class FakeShop:
    """A shop that issues invoices without asking Bitrefill."""

    enabled = True

    def __init__(self):
        self.n = 0

    def invoice(self, product_id, value, payment_method="usdc_solana", **kw):
        self.n += 1
        # A SOL invoice is quoted in lamports; a USDC one in millionths of a dollar.
        amount = 12_000_000 if payment_method == "solana" else 2_450_000
        return {"invoice_id": f"inv{self.n}", "address": A, "amount": amount, "product_id": product_id}

    def category_of(self, product_id):
        return "recharge"

    def usd_estimate(self, product_id, package_id, value):
        return 2.4 if product_id == "jio" else None


class HttpTest(unittest.TestCase):
    """Through the real routes: what an invoice writes down, and what the app is sent."""

    def setUp(self):
        import threading
        from duffleserver.web import serve
        from tests.test_auth import wallet

        class EverySeeker:
            def is_seeker(self, a): return True
            def first_seen(self, a): return 0.0

        self.store = Store()
        self.svc = Service(self.store, FakeRpc(), facts=EverySeeker(), prices=Prices())
        self.httpd = serve(self.svc, "127.0.0.1", 0, admin_token="owner", shop=FakeShop())
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.key, self.me = wallet()
        self.token = self.login()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def call(self, path, body=None, token=None):
        import urllib.error
        import urllib.request
        hdr = {"Content-Type": "application/json"}
        if token:
            hdr["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(self.base + path, json.dumps(body).encode() if body is not None else None, hdr)
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            with e:
                return e.code, json.loads(e.read())

    def login(self):
        from tests.test_auth import sign
        _, c = self.call("/v1/auth/challenge", {"address": self.me})
        return self.call("/v1/auth/verify", {"nonce": c["nonce"], "signature": sign(self.key, c["message"])})[1]["token"]

    def invoice(self, method, product="jio"):
        code, out = self.call("/v1/shop/invoice", {"product_id": product, "value": "219", "refund_address": self.me,
                                                  "payment_method": method}, self.token)
        self.assertEqual(200, code, out)
        return self.store._db.execute("SELECT amount, usd, category FROM purchases WHERE invoice_id=?",
                                      (out["invoice_id"],)).fetchone()

    def test_a_sol_invoice_records_dollars_and_never_lamports_as_a_price(self):
        row = self.invoice("solana")
        self.assertIsNone(row["amount"])
        self.assertEqual(2.4, row["usd"])

    def test_a_usdc_invoice_records_exactly_what_was_charged(self):
        row = self.invoice("usdc_solana")
        self.assertEqual((2_450_000, 2.45), (row["amount"], row["usd"]))

    def test_a_sol_invoice_the_catalogue_cannot_price_records_no_value(self):
        row = self.invoice("solana", product="mystery")
        self.assertEqual((None, None), (row["amount"], row["usd"]))

    def test_a_recharge_paid_in_sol_earns_the_recharge_offer_end_to_end(self):
        for o in seed.launch_offers(time.time() - 10):
            self.svc.put_offer(o)
        code, out = self.call("/v1/offers/airpay-top-up/claim", {"address": self.me}, self.token)
        self.assertEqual((200, True), (code, out.get("ok")), out)
        self.invoice("solana")
        self.store.settle_purchase("inv1", "all_delivered", "CODE")
        self.assertEqual(1, self.svc.check_claims(time.time() + 61))
        _, got = self.call("/v1/offers", token=self.token)
        mine = [o for o in got["offers"] if o["id"] == "airpay-top-up"][0]
        self.assertEqual(("paid", 50, "inv1"), (mine["state"], mine["skr_earned"], mine["signature"]))

    def test_the_offers_route_sends_the_new_fields(self):
        for o in seed.launch_offers(time.time() - 10):
            self.svc.put_offer(o)
        self.call("/v1/offers/airpay-pay-three/claim", {"address": self.me}, self.token)
        _, got = self.call("/v1/offers", token=self.token)
        rows = {o["id"]: o for o in got["offers"]}
        self.assertEqual(9, len(rows))
        three = rows["airpay-pay-three"]
        self.assertEqual(("people", {"done": 0, "need": 3}, {"label": "Pay someone", "target": "pay"}, "Up to 250 SKR"),
                         (three["kind"], three["progress"], three["cta"], three["reward_text"]))
        self.assertIsNone(rows["airpay-top-up"]["progress"])
        for o in rows.values():
            self.assertTrue({"kind", "steps", "rules", "cta", "progress", "reward_text"} <= set(o))


class SeedTwiceTest(Base):
    def test_seeding_twice_replaces_and_never_duplicates(self):
        first = seed.launch_offers(NOW)
        for o in first:
            self.svc.put_offer(o)
        self.claim("airpay-pay-three")
        before = {o["id"]: o for o in self.store.offers()}
        for o in seed.launch_offers(NOW + 3600):                   # the next start, an hour later
            self.svc.put_offer(o)
        after = {o["id"]: o for o in self.store.offers()}
        self.assertEqual(9, len(after))
        self.assertEqual(set(before), set(after))
        self.assertEqual({"jupiter-first-swap", "airpay-top-up", "airpay-gift-card", "airpay-recharge-again",
                          "airpay-pay-three", "airpay-tap-a-friend", "skr-stake", "jito-stake-sol",
                          "marinade-stake-sol"}, set(after))
        # A claim made between the two runs is still there, still open.
        self.assertEqual("open", self.store.claim("airpay-pay-three", ME)["state"])
        for o in after.values():
            self.assertEqual("duffle", o["funder"])
            self.assertAlmostEqual(30 * DAY, o["ends"] - o["starts"], delta=1)

    def test_the_seeded_numbers_are_the_ones_agreed(self):
        got = {o["id"]: (o["kind"], o["reward"], o.get("reward_min"), o["budget"], o["window_hours"])
               for o in seed.launch_offers(NOW)}
        self.assertEqual({
            "airpay-top-up": ("shop", 50, None, 5000, 720),
            "airpay-gift-card": ("shop", 200, 20, 6000, 720),
            "jupiter-first-swap": ("swap", 75, None, 4500, 72),
            "airpay-recharge-again": ("shop", 150, 10, 4500, 720),
            "airpay-pay-three": ("people", 250, 25, 7500, 720),
            "airpay-tap-a-friend": ("tap", 60, None, 3600, 720),
            "skr-stake": ("stake", 100, None, 5000, 168),
            "jito-stake-sol": ("swap", 80, None, 4000, 72),
            "marinade-stake-sol": ("swap", 80, None, 4000, 72),
        }, got)


if __name__ == "__main__":
    unittest.main()
