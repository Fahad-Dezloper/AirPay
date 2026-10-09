"""
The offer page's descriptive text, and the two liquid staking offers.

The text half checks that every offer, seeded or not, tells a newcomer what the offer is, where it
is done, how, what they need and when the reward arrives. The staking half attacks the Jito and
Marinade offers the same way the swap and SKR stake offers are attacked: with transactions copied
from mainnet, each a way to be paid without doing the thing or refused after doing it.
"""
from __future__ import annotations

import copy
import json
import os
import unittest

from duffleserver import offers, swaps
from duffleserver.seed import launch_offers
from duffleserver.service import Service
from duffleserver.store import Store
from tests.test_offer_conditions import Base, NOW, DAY
from tests.test_server import FakeRpc

FIX = os.path.join(os.path.dirname(__file__), "fixtures")
EM_DASH = "—"
COPY = ("about", "where", "guide", "payout", "needs")


def fixture(name):
    with open(os.path.join(FIX, f"{name}.json")) as f:
        return json.load(f)


def strings(value):
    """Every piece of text inside a row, however deeply it is nested."""
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for v in value.values():
            yield from strings(v)
    elif isinstance(value, (list, tuple)):
        for v in value:
            yield from strings(v)


def seeded_rows():
    svc = Service(Store(), FakeRpc())
    for o in launch_offers(NOW):
        svc.put_offer({**o, "starts": 0, "ends": NOW * 10})
    return svc.offers_for("")


class SeededCopyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = seeded_rows()

    def test_there_are_nine_offers_and_the_two_new_companies_are_among_them(self):
        ids = [r["id"] for r in self.rows]
        self.assertEqual(9, len(ids))
        self.assertIn("jito-stake-sol", ids)
        self.assertIn("marinade-stake-sol", ids)

    def test_every_seeded_offer_has_every_part_of_the_page_and_none_is_empty(self):
        for r in self.rows:
            for key in COPY:
                self.assertTrue(r.get(key), f"{r['id']} has no {key}")
            for key in ("about", "where", "payout"):
                self.assertIsInstance(r[key], str, r["id"])
                self.assertTrue(r[key].strip(), r["id"])
            self.assertTrue(1 <= len(r["needs"]) <= 4, r["id"])
            self.assertTrue(all(isinstance(n, str) and n.strip() for n in r["needs"]), r["id"])

    def test_every_guide_is_three_to_five_steps_each_with_a_short_title_and_a_detail(self):
        for r in self.rows:
            self.assertTrue(3 <= len(r["guide"]) <= 5, r["id"])
            for g in r["guide"]:
                self.assertEqual({"title", "detail"}, set(g), r["id"])
                self.assertTrue(0 < len(g["title"]) <= 40, (r["id"], g["title"]))
                self.assertTrue(g["detail"].strip(), (r["id"], g["title"]))

    def test_every_guide_opens_with_the_claim_and_ends_with_the_reward(self):
        for r in self.rows:
            self.assertIn("Claim", r["guide"][0]["title"], r["id"])
            self.assertIn("Rewards", r["guide"][-1]["detail"], r["id"])

    def test_no_text_anywhere_in_a_row_has_an_em_dash(self):
        for r in self.rows:
            for text in strings(r):
                self.assertNotIn(EM_DASH, text, r["id"])

    def test_the_old_fields_are_all_still_there_for_older_apps(self):
        for r in self.rows:
            for key in ("kind", "steps", "rules", "cta", "progress", "reward_text", "reward_min"):
                self.assertIn(key, r, r["id"])
            self.assertTrue(2 <= len(r["steps"]) <= 3, r["id"])

    def test_the_page_text_is_sent_once_not_again_inside_params(self):
        for r in self.rows:
            self.assertFalse(set(COPY) & set(r["params"]), r["id"])

    def test_the_page_mixes_airpay_and_other_companies(self):
        ours = [r["app"] == "AirPay" for r in self.rows]
        self.assertEqual(sorted(r["order"] for r in self.rows), [r["order"] for r in self.rows])
        # Never three of AirPay's own in a row, and not all of them first.
        self.assertFalse(any(all(ours[i:i + 3]) for i in range(len(ours) - 2)), ours)
        self.assertFalse(ours[0])

    def test_on_chain_offers_say_the_reward_can_take_a_few_minutes(self):
        for r in self.rows:
            if r["kind"] in ("swap", "stake"):
                self.assertIn("few minutes", r["payout"], r["id"])
                self.assertIn("chain", r["payout"], r["id"])

    def test_the_skr_stake_page_is_honest_about_locking_and_about_choice(self):
        r = [x for x in self.rows if x["id"] == "skr-stake"][0]
        text = " ".join(strings({k: r[k] for k in COPY}))
        self.assertIn("stake.solanamobile.com", text)
        self.assertIn("same wallet", text)
        self.assertIn("unstaking takes about two days", text)
        self.assertIn("only stake what you are happy to lock", text)
        self.assertTrue(r["icon"].startswith("https://"))

    def test_the_new_offers_have_a_logo_a_link_and_a_link_button(self):
        for oid in ("jito-stake-sol", "marinade-stake-sol"):
            r = [x for x in self.rows if x["id"] == oid][0]
            self.assertTrue(r["icon"].startswith("https://"), oid)
            self.assertTrue(r["link"].startswith("https://"), oid)
            self.assertEqual("link", r["cta"]["target"], oid)
            self.assertEqual((80, 4000, 72.0), (r["reward"], r["budget"], r["window_hours"]), oid)
            self.assertEqual("Flat 80 SKR", r["reward_text"], oid)


class DefaultCopyTest(Base):
    """An offer that writes none of its own page still gets all of it."""

    BARE = (
        {"kind": "shop", "params": {"scope": "recharge", "min_usd": 2}},
        {"kind": "shop", "params": {"scope": "gift", "min_usd": 5, "count": 2}},
        {"kind": "shop"},
        {"kind": "swap", "params": {"min_usd": 10, "receive": "USDC"}, "link": "https://jup.ag", "app": "Jupiter"},
        {"kind": "swap", "params": {"min_sol": 0.1, "receive": swaps.MSOL}, "app": "Marinade"},
        {"kind": "swap", "app": "Somewhere"},
        {"kind": "people", "params": {"count": 3, "min_usd": 1}},
        {"kind": "tap", "params": {"min_usd": 1}},
        {"kind": "tap"},
        {"kind": "stake", "link": "https://stake.solanamobile.com", "app": "SKR Staking"},
        {"kind": "pay", "address": "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU", "app": "Some App"},
        {"kind": "people", "reward": 250, "reward_min": 25, "app": "A" * 48},
    )

    def test_every_kind_gets_a_full_page_from_its_own_numbers(self):
        for bare in self.BARE:
            self.put(**bare)
            r = self.row()
            self.assertFalse(set(COPY) & set(r["params"]), bare)       # nothing was supplied
            for key in ("about", "where", "payout"):
                self.assertTrue(r[key].strip(), (bare, key))
            self.assertTrue(3 <= len(r["guide"]) <= 5, bare)
            for g in r["guide"]:
                self.assertTrue(0 < len(g["title"]) <= 40, (bare, g["title"]))
                self.assertTrue(g["detail"].strip(), bare)
            self.assertEqual("Claim this offer", r["guide"][0]["title"])
            self.assertIn("Rewards", r["guide"][-1]["detail"])
            self.assertEqual("A Seeker phone", r["needs"][0])
            self.assertTrue(2 <= len(r["needs"]) <= 4, bare)
            for text in strings({k: r[k] for k in (*COPY, "steps", "rules", "cta")}):
                self.assertNotIn(EM_DASH, text, bare)
                self.assertNotIn("  ", text, bare)

    def test_the_defaults_carry_the_offers_own_numbers(self):
        self.put(kind="shop", params={"scope": "recharge", "min_usd": 2, "count": 2}, window_hours=72)
        r = self.row()
        self.assertIn("$4 or more in USDC or SOL", r["needs"])
        self.assertIn("3 days", r["guide"][0]["detail"])
        self.assertIn("$2 or more", r["guide"][2]["detail"])
        self.put(kind="stake", params={"min_skr": 250}, link="https://stake.solanamobile.com")
        r = self.row()
        self.assertIn("At least 250 SKR in this wallet", r["needs"])
        self.assertEqual("On stake.solanamobile.com", r["where"])
        self.assertIn("few minutes", r["payout"])

    def test_a_scratch_offer_names_its_range_and_a_flat_one_its_amount(self):
        self.put(kind="tap", reward=200, reward_min=20)
        self.assertIn("between 20 and 200 SKR", self.row()["guide"][-1]["detail"])
        self.put(kind="tap", reward=60)
        self.assertIn("60 SKR", self.row()["guide"][-1]["detail"])

    def test_an_offer_can_override_any_part_and_keeps_the_default_for_the_rest(self):
        guide = [{"title": "One", "detail": "Do one."}, {"title": "Two", "detail": "Do two."},
                 {"title": "Three", "detail": "Get it in Rewards."}]
        self.put(kind="tap", where="At the cafe", guide=guide, params={"needs": ["A coffee"]})
        r = self.row()
        self.assertEqual(("At the cafe", guide, ["A coffee"]), (r["where"], r["guide"], r["needs"]))
        self.assertIn("holding your two phones together", r["about"])   # still the default

    def test_bad_page_text_is_refused(self):
        step = {"title": "T", "detail": "D"}
        for bad in ({"about": "Stake now " + EM_DASH + " earn"}, {"about": ""}, {"about": "x" * 281},
                    {"where": "x" * 61}, {"where": "two\nlines"}, {"payout": 5},
                    {"guide": [step, step]}, {"guide": [step] * 6}, {"guide": ["a", "b", "c"]},
                    {"guide": [step, step, {"title": "x" * 41, "detail": "D"}]},
                    {"guide": [step, step, {"title": "T"}]},
                    {"guide": [step, step, {"title": "T", "detail": "a " + EM_DASH + " b"}]},
                    {"needs": []}, {"needs": ["a"] * 5}, {"needs": ["x" * 61]}, {"needs": "a phone"},
                    {"order": -1}, {"order": "first"}, {"order": True}):
            with self.assertRaises(ValueError, msg=str(bad)):
                self.put(kind="tap", **bad)

    def test_order_decides_the_page_and_an_offer_without_one_comes_last(self):
        self.put(id="c", kind="tap")
        self.put(id="b", kind="tap", order=20)
        self.put(id="a", kind="tap", order=10, featured=False)
        self.put(id="d", kind="tap", featured=True)
        got = [o["id"] for o in self.at(NOW + 100, self.svc.offers_for, "")]
        self.assertEqual(["a", "b", "d", "c"], got)


class SwapParamsTest(Base):
    def test_receive_may_be_usdc_or_a_mint_and_nothing_else(self):
        self.assertEqual("USDC", self.put(kind="swap", params={"receive": "USDC"})["offer"]["params"]["receive"])
        self.assertEqual(swaps.JITOSOL,
                         self.put(kind="swap", params={"receive": swaps.JITOSOL})["offer"]["params"]["receive"])
        with self.assertRaises(ValueError):
            self.put(kind="swap", params={"receive": "JitoSOL"})

    def test_program_may_be_one_or_a_short_list_of_real_addresses(self):
        both = [swaps.SPL_STAKE_POOL, swaps.JUPITER_V6]
        self.assertEqual(both, self.put(kind="swap", params={"program": both})["offer"]["params"]["program"])
        self.assertEqual(swaps.MARINADE,
                         self.put(kind="swap", params={"program": [swaps.MARINADE]})["offer"]["params"]["program"])
        for bad in ([], ["nope"], [swaps.JUPITER_V6] * 5, [swaps.JUPITER_V6, 5]):
            with self.assertRaises(ValueError, msg=str(bad)):
                self.put(kind="swap", params={"program": bad})

    def test_min_sol_is_a_positive_amount(self):
        for bad in (0, -1, 1001, "0.1", True):
            with self.assertRaises(ValueError, msg=str(bad)):
                self.put(kind="swap", params={"min_sol": bad})

    def test_the_rules_name_the_token_and_the_sol_floor(self):
        self.put(kind="swap", params={"min_sol": 0.1, "receive": swaps.JITOSOL})
        rules = self.row()["rules"]
        self.assertIn("At least 0.1 SOL, paid in SOL", rules)
        self.assertIn("You must receive JitoSOL", rules)
        self.assertNotIn("Swaps between two stablecoins do not count", rules)
        # The Jupiter offer reads exactly as it did.
        self.put(kind="swap", params={"min_usd": 10, "receive": "USDC"})
        rules = self.row()["rules"]
        self.assertIn("You must receive USDC", rules)
        self.assertIn("Swaps between two stablecoins do not count", rules)


def flipped(tx):
    """The same transaction run backwards: the token leaves and SOL comes back. An unstake."""
    tx = copy.deepcopy(tx)
    m = tx["meta"]
    m["preBalances"], m["postBalances"] = m["postBalances"], m["preBalances"]
    m["preTokenBalances"], m["postTokenBalances"] = m["postTokenBalances"], m["preTokenBalances"]
    return tx


class LiquidStakeTest(Base):
    """Shared by Jito and Marinade: the seeded offer itself, against real mainnet transactions."""

    OFFER = ""
    REAL = ""           # a real stake above the floor
    SMALL = ""          # a real stake below it
    MINT = ""
    __test__ = False

    def setUp(self):
        super().setUp()
        seeded = [o for o in launch_offers(NOW - 1000) if o["id"] == self.OFFER][0]
        self.svc.put_offer(seeded)
        self.f = fixture(self.REAL)
        self.who = self.f["payer"]

    def serve(self, tx, at, sig=None):
        sig = sig or self.f["signature"]
        self.rpc.signatures_with_time = lambda a, limit=20: [{"signature": sig, "blockTime": at}]
        self.rpc.transaction = lambda s: tx if s == sig else None
        return sig

    def refusal(self):
        return self.store.claim(self.OFFER, self.who)["refused"]

    def test_the_fixture_is_a_real_stake_of_more_than_the_floor(self):
        sw = swaps.swap_of(self.f["result"], self.who, self.program())
        self.assertEqual((swaps.WSOL, self.MINT), (sw.sold, sw.bought))
        self.assertGreaterEqual(sw.sold_units, 100_000_000)
        self.assertGreater(sw.bought_units, 0)

    def program(self):
        return [o for o in launch_offers(NOW) if o["id"] == self.OFFER][0]["params"]["program"]

    def test_a_real_stake_after_the_claim_pays_80_with_the_signature_as_proof(self):
        self.claim(self.OFFER, who=self.who)
        sig = self.serve(self.f["result"], NOW + 120)
        self.assertEqual(1, self.check(NOW + 300))
        got = self.store.claim(self.OFFER, self.who)
        self.assertEqual(("paid", 80, sig), (got["state"], got["skr"], got["signature"]))
        self.assertEqual(80, self.balance(self.who))

    def test_it_pays_with_no_price_feed_because_the_floor_is_in_sol(self):
        self.prices.sol = None
        self.claim(self.OFFER, who=self.who)
        self.serve(self.f["result"], NOW + 120)
        self.assertEqual(1, self.check(NOW + 300))

    def test_a_real_stake_below_the_floor_does_not_pay(self):
        small = fixture(self.SMALL)
        self.who = small["payer"]
        self.claim(self.OFFER, who=self.who)
        self.serve(small["result"], NOW + 120, small["signature"])
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual("below_minimum", self.refusal())

    def test_a_stake_made_before_the_claim_does_not_pay(self):
        self.claim(self.OFFER, who=self.who)
        self.serve(self.f["result"], NOW - 500)
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual("open", self.store.claim(self.OFFER, self.who)["state"])
        # And when the chain gives no time for the list entry, the transaction's own time is used.
        tx = copy.deepcopy(self.f["result"])
        tx["blockTime"] = NOW - 500
        self.serve(tx, None)
        self.assertEqual(0, self.check(NOW + 600))
        self.assertEqual("before_claim", self.refusal())

    def test_a_stake_made_after_the_window_shut_does_not_pay(self):
        self.claim(self.OFFER, who=self.who)
        self.serve(self.f["result"], NOW + 73 * 3600)
        self.assertEqual(0, self.check(NOW + 74 * 3600))
        self.assertEqual("expired", self.store.claim(self.OFFER, self.who)["state"])

    def test_an_unstake_does_not_pay(self):
        self.claim(self.OFFER, who=self.who)
        self.serve(flipped(self.f["result"]), NOW + 120)
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual("wrong_token", self.refusal())

    def test_sol_going_in_with_a_different_token_coming_out_does_not_pay(self):
        # The same program can mint other tokens (the stake pool program runs many pools), so the
        # token that arrives is what makes it this company's.
        tx = json.loads(json.dumps(self.f["result"]).replace(self.MINT, swaps.USDT))
        self.claim(self.OFFER, who=self.who)
        self.serve(tx, NOW + 120)
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual("wrong_token", self.refusal())

    def test_a_real_jupiter_swap_into_something_else_does_not_pay(self):
        other = fixture("jupiter_swap_1")                 # 10 SOL into another token, on Jupiter
        self.who = other["payer"]
        self.claim(self.OFFER, who=self.who)
        self.serve(other["result"], NOW + 120, other["signature"])
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual("wrong_token", self.refusal())

    def test_the_token_bought_on_jupiter_with_sol_also_pays(self):
        # A real Jupiter swap of 10 SOL, with what it bought renamed to this offer's token: the
        # person ends up holding the staking token either way.
        other = fixture("jupiter_swap_1")
        bought = swaps.swap_of(other["result"], other["payer"]).bought
        tx = json.loads(json.dumps(other["result"]).replace(bought, self.MINT))
        self.who = other["payer"]
        self.claim(self.OFFER, who=self.who)
        self.serve(tx, NOW + 120, other["signature"])
        self.assertEqual(1, self.check(NOW + 300))

    def test_the_token_arriving_without_either_program_does_not_pay(self):
        # A plain transfer of the token into the wallet is not a stake.
        tx = copy.deepcopy(self.f["result"])
        for ix in tx["transaction"]["message"]["instructions"]:
            ix["programIdIndex"] = 0
        self.claim(self.OFFER, who=self.who)
        self.serve(tx, NOW + 120)
        self.assertEqual(0, self.check(NOW + 300))
        self.assertIsNone(self.refusal())

    def test_a_failed_stake_or_somebody_elses_does_not_pay(self):
        failed = copy.deepcopy(self.f["result"])
        failed["meta"]["err"] = {"InstructionError": [0, "Custom"]}
        self.claim(self.OFFER, who=self.who)
        self.serve(failed, NOW + 120)
        self.assertEqual(0, self.check(NOW + 300))
        from tests.test_offer_conditions import ME
        self.claim(self.OFFER, who=ME)
        self.serve(self.f["result"], NOW + 120)
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual("open", self.store.claim(self.OFFER, ME)["state"])

    def test_one_stake_pays_once(self):
        self.claim(self.OFFER, who=self.who)
        self.serve(self.f["result"], NOW + 120)
        self.assertEqual(1, self.check(NOW + 300))
        self.assertEqual(0, self.check(NOW + 600))
        self.assertEqual(80, self.balance(self.who))


class JitoOfferTest(LiquidStakeTest):
    __test__ = True
    OFFER, REAL, SMALL, MINT = "jito-stake-sol", "jito_stake", "jito_stake_small", swaps.JITOSOL

    def test_the_real_stake_went_through_jitos_own_pool(self):
        self.assertIn(swaps.JITO_POOL, self.f["result"]["transaction"]["message"]["accountKeys"])


class MarinadeOfferTest(LiquidStakeTest):
    __test__ = True
    OFFER, REAL, SMALL, MINT = "marinade-stake-sol", "marinade_stake", "marinade_stake_small", swaps.MSOL

    def test_a_marinade_stake_does_not_pay_the_jito_offer(self):
        jito = [o for o in launch_offers(NOW - 1000) if o["id"] == "jito-stake-sol"][0]
        self.svc.put_offer(jito)
        self.claim("jito-stake-sol", who=self.who)
        self.serve(self.f["result"], NOW + 120)
        self.assertEqual(0, self.check(NOW + 300))
        self.assertEqual("open", self.store.claim("jito-stake-sol", self.who)["state"])


def load_tests(loader, tests, pattern):
    """The shared class holds the tests and is not itself a case: it has no offer to test."""
    suite = unittest.TestSuite()
    for case in (SeededCopyTest, DefaultCopyTest, SwapParamsTest, JitoOfferTest, MarinadeOfferTest):
        suite.addTests(loader.loadTestsFromTestCase(case))
    return suite


if __name__ == "__main__":
    unittest.main()
