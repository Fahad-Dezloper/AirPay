"""
The look-back that can take a reward away.

The must-not-reverse tests are the important half. A rule that fires on honest behaviour is
worse than the farming it prevents: four friends splitting a bill in five minutes are not a
farm, and clawing back their SKR costs more trust than the SKR was worth. Every pattern here
has a case that must fire and a neighbouring case that must not.
"""
from __future__ import annotations

import unittest

from duffleserver import patrol
from duffleserver.patrol import BURST, ROUND_TRIP, UNCORROBORATED_TAP, Watch
from duffleserver.service import Service
from duffleserver.store import Store
from tests.test_reward_flow import FakeFacts, FRIEND, OTHER, REF1, REF2, SEEKER, SIG
from tests.test_server import FakeRpc
from tests.revealutil import scratch_all

NOW = 1_800_000_000.0
HOUR = 3600.0
MINUTE = 60.0
SOL = 100_000_000


def reward(reference="r1", payer=SEEKER, counterparty=FRIEND, at=NOW, units=SOL, **kw):
    return {"reference": reference, "payer": payer, "counterparty": counterparty,
            "created_at": at, "units": units, "token": "SOL", "skr": 300,
            "corroborated": True, **kw}


def payment(payer=FRIEND, counterparty=SEEKER, at=NOW + HOUR, units=SOL, token="SOL"):
    return {"reference": "p", "payer": payer, "counterparty": counterparty,
            "at": at, "units": units, "token": token}


class RoundTripTest(unittest.TestCase):
    def test_money_sent_straight_back_is_a_wash(self):
        out = patrol.round_trips([reward()], [payment()], NOW)
        self.assertEqual([ROUND_TRIP], [r.reason for r in out])

    def test_a_repayment_the_next_day_is_just_a_repayment(self):
        # People do pay each other back. Six hours is the line; a day later is ordinary life.
        self.assertEqual([], patrol.round_trips([reward()], [payment(at=NOW + 25 * HOUR)], NOW))

    def test_paying_back_a_different_amount_is_not_a_wash(self):
        # Splitting a bill: they paid 1.0, you send back 0.4. Nothing round-tripped.
        self.assertEqual([], patrol.round_trips([reward()], [payment(units=40_000_000)], NOW))

    def test_a_slightly_different_amount_is_still_a_wash(self):
        # 0.099 back for 0.1 out is the same money, minus a fee.
        self.assertEqual(1, len(patrol.round_trips([reward()], [payment(units=99_000_000)], NOW)))

    def test_paying_someone_else_is_not_a_round_trip(self):
        self.assertEqual([], patrol.round_trips([reward()], [payment(payer=OTHER)], NOW))

    def test_a_payment_before_the_reward_is_not_a_return(self):
        # They paid you first, then you paid them. That is two people, not a loop.
        self.assertEqual([], patrol.round_trips([reward()], [payment(at=NOW - HOUR)], NOW))

    def test_a_different_token_coming_back_is_not_the_same_money(self):
        self.assertEqual([], patrol.round_trips([reward()], [payment(token="USDC")], NOW))


class BurstTest(unittest.TestCase):
    def rapid(self, n, gap=MINUTE):
        return [reward(reference=f"r{i}", counterparty=f"cp{i}", at=NOW + i * gap) for i in range(n)]

    def test_four_rewarded_payments_in_ten_minutes_is_a_farm(self):
        out = patrol.bursts(self.rapid(4))
        self.assertEqual([BURST], [r.reason for r in out])

    def test_the_first_of_a_flurry_is_left_alone(self):
        # It looked like an ordinary payment when it happened, and probably was.
        out = patrol.bursts(self.rapid(5))
        self.assertNotIn("r0", [r.reference for r in out])
        self.assertEqual(["r3", "r4"], sorted(r.reference for r in out))

    def test_three_payments_in_ten_minutes_is_a_group_of_friends(self):
        self.assertEqual([], patrol.bursts(self.rapid(3)))

    def test_four_payments_spread_over_an_hour_is_not_a_burst(self):
        self.assertEqual([], patrol.bursts(self.rapid(4, gap=20 * MINUTE)))

    def test_two_people_paying_at_once_are_not_one_burst(self):
        # A queue at a stall: several payers, each paying once. Counted per payer.
        rows = [reward(reference=f"r{i}", payer=f"payer{i}", at=NOW + i) for i in range(6)]
        self.assertEqual([], patrol.bursts(rows))


class CorroborationTest(unittest.TestCase):
    def test_one_registration_is_noticed(self):
        out = patrol.uncorroborated([reward(corroborated=False)])
        self.assertEqual([UNCORROBORATED_TAP], [r.reason for r in out])

    def test_an_uncorroborated_tap_alone_does_not_lose_the_reward(self):
        # An honest payer whose phone was offline at the wrong moment produces one
        # registration too. It is evidence, not an accusation.
        self.assertEqual([], patrol.reversals([reward(corroborated=False)], [], NOW))

    def test_it_is_added_to_a_pattern_that_did_fire(self):
        out = patrol.reversals([reward(corroborated=False)], [payment()], NOW)
        self.assertEqual(1, len(out))
        self.assertIn("only one phone registered it", out[0].detail)

    def test_one_reversal_per_payment_even_when_two_patterns_fire(self):
        rows = [reward(reference=f"r{i}", counterparty=f"cp{i}", at=NOW + i * MINUTE) for i in range(4)]
        back = [payment(payer="cp3", counterparty=SEEKER, at=NOW + 4 * MINUTE)]
        out = patrol.reversals(rows, back, NOW)
        self.assertEqual(len(out), len({r.reference for r in out}))


class EndToEndTest(unittest.TestCase):
    """Through the store, where the corroboration flag is actually derived."""

    def setUp(self):
        self.store = Store()
        self.svc = Service(self.store, FakeRpc(), facts=FakeFacts())
        self.svc.set_reward_config({"lock_days": 7, "min_payout": 100})

    def settle(self, reference, recipient=FRIEND, payer=SEEKER, amount="0.1", both_phones=True):
        # A real tap registers twice: the phone that served the request, then the phone that
        # received it over the radio and knows who is paying.
        if both_phones:
            self.svc.register(reference, recipient, amount, origin="tap")
            self.svc.register(reference, recipient, amount, payer=payer, origin="tap")
        else:
            self.svc.register(reference, recipient, amount, payer=payer, origin="tap")
        self.svc._settle(self.store.get(reference), SIG[:-4] + reference[:4])

    def test_a_tap_registered_by_both_phones_is_corroborated(self):
        self.settle(REF1)
        self.assertTrue(self.store.locked_rewards(NOW)[0]["corroborated"])

    def test_a_payment_only_one_phone_mentioned_is_not(self):
        self.settle(REF1, both_phones=False)
        self.assertFalse(self.store.locked_rewards(NOW)[0]["corroborated"])

    def test_a_reward_settles_once_its_week_is_up(self):
        self.settle(REF1)
        self.assertEqual({"reversed": 0, "settled": 0}, self.svc.patrol())
        later = __import__("time").time() + 8 * 86_400
        self.assertEqual(2, self.svc.patrol(later)["settled"])
        self.assertEqual({"settled"}, {r["state"] for r in self.store.rewards_for(SEEKER)})

    def test_a_settled_reward_can_no_longer_be_taken_back(self):
        # Once the week is up it is theirs, whatever we notice afterwards.
        self.settle(REF1)
        self.svc.patrol(__import__("time").time() + 8 * 86_400)
        self.assertEqual(0, self.store.reverse_rewards(REF1, "too late"))

    def test_a_real_round_trip_is_reversed_through_the_store(self):
        """The wiring test. The pure tests above would still pass if locked_rewards joined the
        payment details wrongly, or settled_payments missed the return leg."""
        self.settle(REF1)                       # Seeker pays friend 0.1, earns 300
        scratch_all(self.store)
        self.assertEqual(300, self.store.reward_balance(SEEKER, NOW)["total"])
        # The friend sends the same 0.1 straight back. Their own payment earns nothing (they
        # are not a Seeker), but it is what makes the first one a wash.
        self.svc.register(REF2, SEEKER, "0.1", payer=FRIEND, origin="tap")
        self.svc._settle(self.store.get(REF2), SIG[:-4] + REF2[:4])

        out = self.svc.patrol()
        self.assertEqual(2, out["reversed"], "both awards for that payment come back")
        rows = self.store.rewards_for(SEEKER)
        self.assertEqual({"reversed"}, {r["state"] for r in rows})
        self.assertTrue(all("round_trip" in (r["reason"] or "") for r in rows))
        scratch_all(self.store)
        self.assertEqual(0, self.store.reward_balance(SEEKER, NOW)["total"])

    def test_an_ordinary_payment_survives_the_patrol(self):
        self.settle(REF1)
        self.assertEqual({"reversed": 0, "settled": 0}, self.svc.patrol())
        scratch_all(self.store)
        self.assertEqual(300, self.store.reward_balance(SEEKER, NOW)["total"])

    def test_the_ledger_counts_what_was_paid_and_what_was_refused(self):
        self.settle(REF1)
        self.settle(REF2, recipient=OTHER)  # OTHER has no history: refused
        led = self.svc.reward_ledger()
        self.assertEqual({("first_payment", 250), ("per_payment", 50)},
                         {(r["kind"], r["skr"]) for r in led["by_rule"]})
        self.assertEqual([("new_counterparty", 1)], [(r["refused"], r["n"]) for r in led["refused"]])
        self.assertEqual(1, led["people"])


if __name__ == "__main__":
    unittest.main()
