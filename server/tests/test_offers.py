"""
Offers: paying a particular app, and the proof that the offer caused it.

The payout is the small half. The valuable half is attribution — "this verified Seeker owner
opened your offer and then paid you, here is the signature" — which is what a dApp would fund
the next campaign for. So the tests that matter most are the ones about what does NOT count as
attribution: a payment made before the offer was opened, one to a different address, one after
the window closed. Each of those, counted wrongly, turns the proof into a number nobody should
believe.
"""
from __future__ import annotations

import time
import unittest

from duffleserver import offers
from duffleserver.offers import Claim, Offer, Payment
from duffleserver.service import Service
from duffleserver.store import Store
from tests.test_reward_flow import FakeFacts, FRIEND, SEEKER
from tests.test_server import FakeRpc

NOW = 1_800_000_000.0
HOUR = 3600.0
APP = "3QJmqCeJP7hHB9ipDrVdwCYT8yMYhD4dFoDrK7tHx5eL"  # the dApp's devnet address
USDC = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"


def offer(**kw) -> Offer:
    return Offer(**{"id": "try-app", "funder": "duffle", "app": "SomeApp",
                    "what": "Spend 5 USDC in SomeApp", "address": APP, "token": USDC,
                    "minimum": 5_000_000, "reward": 300, "budget": 3_000,
                    "starts": NOW - HOUR, "ends": NOW + 30 * 24 * HOUR, **kw})


def claim(at=NOW) -> Claim:
    return Claim("try-app", SEEKER, at)


def paid(to=APP, token=USDC, units=5_000_000, at=NOW + HOUR, sig="sig1") -> Payment:
    return Payment(sig, to, token, units, at)


class ClaimingTest(unittest.TestCase):
    def test_a_seeker_can_open_a_live_offer(self):
        self.assertIsNone(offers.can_claim(offer(), NOW, True, None, 0))

    def test_only_a_seeker_can(self):
        self.assertEqual(offers.NOT_A_SEEKER, offers.can_claim(offer(), NOW, False, None, 0))

    def test_an_offer_that_has_ended_cannot_be_opened(self):
        self.assertEqual(offers.NOT_LIVE, offers.can_claim(offer(), NOW + 60 * 24 * HOUR, True, None, 0))

    def test_an_offer_that_has_not_started_cannot_be_opened(self):
        self.assertEqual(offers.NOT_LIVE, offers.can_claim(offer(starts=NOW + HOUR), NOW, True, None, 0))

    def test_nobody_opens_the_same_offer_twice(self):
        self.assertEqual(offers.ALREADY_CLAIMED, offers.can_claim(offer(), NOW, True, claim(), 0))

    def test_an_expired_claim_can_be_reopened(self):
        stale = Claim("try-app", SEEKER, NOW - 100 * HOUR, offers.EXPIRED)
        self.assertIsNone(offers.can_claim(offer(), NOW, True, stale, 0))

    def test_an_offer_stops_taking_claims_it_could_not_pay(self):
        self.assertEqual(offers.OFFER_BUDGET, offers.can_claim(offer(), NOW, True, None, spent=2_800))


class AttributionTest(unittest.TestCase):
    """What counts as "the offer caused this payment", and what doesn't."""

    def test_paying_the_app_after_opening_the_offer_earns(self):
        d = offers.settle(offer(), claim(), [paid()], NOW + 2 * HOUR, 0)
        self.assertEqual(300, d.skr)
        self.assertEqual("sig1", d.signature)

    def test_paying_before_opening_it_is_not_attribution(self):
        # Honest behaviour, but the offer did not cause it — and a dApp paying for causation
        # would be right to reject this one.
        d = offers.settle(offer(), claim(), [paid(at=NOW - HOUR)], NOW, 0)
        self.assertEqual(offers.BEFORE_CLAIM, d.refused)

    def test_paying_after_the_window_closes_earns_nothing(self):
        d = offers.settle(offer(window_hours=48), claim(), [paid(at=NOW + 50 * HOUR)], NOW + 51 * HOUR, 0)
        self.assertEqual(offers.OUTSIDE_WINDOW, d.refused)

    def test_a_window_that_closed_with_no_payment_at_all_expires(self):
        d = offers.settle(offer(), claim(), [], NOW + 100 * HOUR, 0)
        self.assertEqual(offers.OUTSIDE_WINDOW, d.refused)

    def test_paying_someone_else_does_not_count(self):
        d = offers.settle(offer(), claim(), [paid(to=FRIEND)], NOW + HOUR, 0)
        self.assertEqual(offers.WRONG_RECIPIENT, d.refused)

    def test_the_wrong_token_does_not_count(self):
        d = offers.settle(offer(), claim(), [paid(token="SOL")], NOW + HOUR, 0)
        self.assertEqual(offers.WRONG_TOKEN, d.refused)

    def test_under_the_minimum_does_not_count(self):
        d = offers.settle(offer(), claim(), [paid(units=4_999_999)], NOW + HOUR, 0)
        self.assertEqual(offers.BELOW_MINIMUM, d.refused)

    def test_one_good_payment_among_several_is_enough(self):
        d = offers.settle(offer(), claim(),
                          [paid(to=FRIEND, sig="other"), paid(units=1, sig="dust"), paid(sig="real")],
                          NOW + HOUR, 0)
        self.assertEqual("real", d.signature)

    def test_nothing_is_paid_beyond_the_offer_budget(self):
        d = offers.settle(offer(budget=3_000), claim(), [paid()], NOW + HOUR, spent=3_000)
        self.assertEqual(offers.OFFER_BUDGET, d.refused)

    def test_the_last_claim_is_trimmed_to_what_the_budget_has_left(self):
        d = offers.settle(offer(budget=3_000), claim(), [paid()], NOW + HOUR, spent=2_900)
        self.assertEqual(100, d.skr)

    def test_the_weekly_pool_caps_an_unfunded_offer(self):
        # A Duffle-funded offer spends the same pool as tap rewards. That is exactly what stops
        # an unfunded campaign costing more than is already budgeted.
        d = offers.settle(offer(), claim(), [paid()], NOW + HOUR, 0, pool_left=40)
        self.assertEqual(40, d.skr)
        empty = offers.settle(offer(), claim(), [paid()], NOW + HOUR, 0, pool_left=0)
        self.assertEqual(offers.OFFER_BUDGET, empty.refused)


class ThroughTheServerTest(unittest.TestCase):
    def setUp(self):
        self.store = Store()
        self.svc = Service(self.store, FakeRpc(), facts=FakeFacts())
        self.svc.put_offer({"id": "try-app", "app": "SomeApp", "what": "Spend 5 USDC",
                            "address": APP, "token": USDC, "minimum": 5_000_000,
                            "reward": 300, "budget": 3_000})

    def test_an_offer_needs_an_address_that_could_be_paid(self):
        with self.assertRaises(ValueError):
            self.svc.put_offer({"id": "bad", "app": "X", "what": "y", "address": "not-an-address"})

    def test_an_offer_needs_to_say_what_to_do(self):
        with self.assertRaises(ValueError):
            self.svc.put_offer({"id": "bad", "app": "X", "what": "", "address": APP})

    def test_a_seeker_opens_it_and_the_list_shows_their_state(self):
        self.assertTrue(self.svc.claim_offer("try-app", SEEKER)["ok"])
        row = self.svc.offers_for(SEEKER)[0]
        self.assertEqual("open", row["state"])
        self.assertEqual(0, row["skr_earned"])

    def test_someone_who_is_not_a_seeker_is_told_why(self):
        self.assertEqual(offers.NOT_A_SEEKER, self.svc.claim_offer("try-app", FRIEND)["refused"])

    def test_an_unreachable_chain_is_not_a_refusal(self):
        svc = Service(Store(), FakeRpc(), facts=FakeFacts(unreachable=True))
        svc.put_offer({"id": "o", "app": "X", "what": "y", "address": APP})
        self.assertEqual("chain_unreachable", svc.claim_offer("o", SEEKER)["refused"])

    def test_the_list_is_the_same_for_someone_who_has_not_claimed(self):
        row = self.svc.offers_for(SEEKER)[0]
        self.assertEqual("available", row["state"])

    def test_the_proof_is_what_a_dapp_would_be_shown(self):
        self.svc.claim_offer("try-app", SEEKER)
        self.store.settle_claim("try-app", SEEKER, offers.PAID, 300, "sigABC", None, time.time())
        proof = self.store.offer_proof("try-app")
        self.assertEqual({"opened": 1, "paid": 1, "skr_paid": 300},
                         {k: proof[k] for k in ("opened", "paid", "skr_paid")})
        self.assertEqual(["sigABC"], proof["signatures"])

    def test_a_claim_nobody_satisfied_counts_as_reach_not_as_a_sale(self):
        # Two people opened it, neither paid. A dApp is shown both numbers, because a campaign
        # that reached people and converted none is exactly what they need to know.
        self.svc.claim_offer("try-app", SEEKER)
        self.store.claim_offer("try-app", FRIEND, time.time())
        proof = self.store.offer_proof("try-app")
        self.assertEqual((2, 0), (proof["opened"], proof["paid"]))


class OnChainTest(unittest.TestCase):
    """The wiring, against the real devnet payment the other suites use.

    Everything above is pure. This is the part that reads the chain — the join between a claim,
    a wallet's signatures and what an address actually received — and it is where a mistake
    would quietly pay for payments that never happened.
    """

    #: The moment that real transaction landed.
    AT = 1_789_327_934.0

    def setUp(self):
        from tests.test_server import SIG
        self.store = Store()
        # The claimer's wallet is where we look, not the app's.
        self.svc = Service(self.store, FakeRpc(by_address={SEEKER: [SIG]}), facts=FakeFacts())
        self.svc.put_offer({"id": "pay-app", "app": "SomeApp", "what": "Send 0.01 SOL to SomeApp",
                            "address": FRIEND, "token": "SOL", "minimum": 10_000_000,
                            "reward": 300, "budget": 3_000})

    def open_claim(self, at):
        self.store.claim_offer("pay-app", SEEKER, at)

    def test_a_real_payment_to_the_offer_address_settles_the_claim(self):
        self.open_claim(self.AT - 60)
        self.assertEqual(1, self.svc.check_claims(now=self.AT + 60))
        row = self.svc.offers_for(SEEKER)[0]
        self.assertEqual("paid", row["state"])
        self.assertEqual(300, row["skr_earned"])
        self.assertTrue(row["signature"])

    def test_the_same_claim_is_not_paid_twice(self):
        self.open_claim(self.AT - 60)
        self.svc.check_claims(now=self.AT + 60)
        self.assertEqual(0, self.svc.check_claims(now=self.AT + 120))
        self.assertEqual(300, self.store.offer_spent("pay-app"))

    def test_a_payment_made_before_the_claim_does_not_settle_it(self):
        # Same transaction, claim opened afterwards: the offer cannot have caused it.
        self.open_claim(self.AT + 60)
        self.assertEqual(0, self.svc.check_claims(now=self.AT + 120))
        self.assertEqual("open", self.svc.offers_for(SEEKER)[0]["state"])
        self.assertEqual(offers.BEFORE_CLAIM, self.store.claim("pay-app", SEEKER)["refused"])

    def test_a_payment_below_the_offers_minimum_does_not_settle_it(self):
        self.svc.put_offer({"id": "pay-app", "app": "SomeApp", "what": "Send 1 SOL",
                            "address": FRIEND, "token": "SOL", "minimum": 1_000_000_000,
                            "reward": 300, "budget": 3_000})
        self.open_claim(self.AT - 60)
        self.assertEqual(0, self.svc.check_claims(now=self.AT + 60))
        self.assertEqual(offers.BELOW_MINIMUM, self.store.claim("pay-app", SEEKER)["refused"])

    def test_an_offer_payout_lands_in_the_same_ledger_as_tap_rewards(self):
        """One ledger, not two. With a separate one the app showed a balance that left offers
        out, and the weekly pool never saw the spend — so the promise that an unfunded campaign
        costs nothing beyond the budget was not actually being kept."""
        self.open_claim(self.AT - 60)
        self.svc.check_claims(now=self.AT + 60)
        self.assertEqual(300, self.store.reward_balance(SEEKER, self.AT + 60)["total"])
        self.assertEqual(["offer"], [r["kind"] for r in self.store.rewards_for(SEEKER)])
        self.assertEqual(300, self.svc.reward_summary(SEEKER)["balance"]["total"])
        # It spends the pool — checked against the week the payout was recorded in, not the
        # wall clock, which is a different week in this fixture.
        from duffleserver import rewards as R
        at = self.AT + 60
        past = self.store.reward_history(SEEKER, "", at, R.week_of(at), 0)
        self.assertEqual(300, past["week_spent"])
        # ...but is not a payment, so it neither advances the weekly count nor the streak.
        self.assertEqual(0, past["rewarded_this_week"])

    def test_an_offer_does_not_eat_the_tap_reward_allowance(self):
        """Offers draw on the weekly pool — that is the budget — but not on the per-person
        monthly and campaign caps, which exist to stop one person taking the tap-reward share
        meant for twenty. Counting them twice would mean claiming an offer costs you your tap
        rewards, penalising the one behaviour that pays for itself."""
        from duffleserver import rewards as R
        self.open_claim(self.AT - 60)
        self.svc.check_claims(now=self.AT + 60)
        at = self.AT + 60
        past = self.store.reward_history(SEEKER, "", at, R.week_of(at), 0)
        self.assertEqual(300, past["week_spent"], "the pool sees it")
        self.assertEqual(0, past["earned_this_month"], "the monthly cap does not")
        self.assertEqual(0, past["earned_in_campaign"], "nor the campaign cap")

    def test_an_offer_is_not_paid_into_the_ledger_twice(self):
        self.open_claim(self.AT - 60)
        self.svc.check_claims(now=self.AT + 60)
        self.svc.check_claims(now=self.AT + 120)
        self.assertEqual(1, len(self.store.rewards_for(SEEKER)))

    def test_a_claim_nobody_satisfied_in_time_expires(self):
        self.open_claim(self.AT - 300 * HOUR)
        self.svc.check_claims(now=self.AT + 60)
        self.assertEqual("expired", self.store.claim("pay-app", SEEKER)["state"])

    def test_an_unreachable_chain_leaves_the_claim_open(self):
        from tests.test_server import SIG
        svc = Service(self.store, FakeRpc(by_address={SEEKER: [SIG]}, offline=True), facts=FakeFacts())
        self.open_claim(self.AT - 60)
        self.assertEqual(0, svc.check_claims(now=self.AT + 60))
        self.assertEqual("open", self.store.claim("pay-app", SEEKER)["state"])


if __name__ == "__main__":
    unittest.main()
