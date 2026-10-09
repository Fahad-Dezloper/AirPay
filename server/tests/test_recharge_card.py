"""
A scratch card for a delivered phone recharge.

The card is real SKR, so the rules that keep it from being bought cheaply are the ones pinned
here, each from the side that must earn and the side that must not:

  * a recharge that was DELIVERED earns one card; paid-for-but-not-delivered earns none;
  * a gift card earns none: this is a recharge reward;
  * only a Seeker earns it, and a chain that could not be asked gives nothing now and is asked
    again on the next look, so a hiccup does not cost somebody their card;
  * however many times the same order is looked at, it gives one card;
  * an account has a daily limit, and everyone shares a weekly budget;
  * it never counts against the tap pool.
"""
from __future__ import annotations

import unittest

from duffleserver import rewards
from duffleserver.service import Service
from duffleserver.store import Store
from tests.test_reward_flow import FRIEND, SEEKER, FakeFacts
from tests.test_server import FakeRpc


class Unsure(FakeFacts):
    """A chain that cannot say whether anybody is a Seeker, until told it can."""

    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self.down = True

    def is_seeker(self, address):
        return None if self.down else super().is_seeker(address)


def delivered(status="all_delivered"):
    return {"status": status, "orders": []}


class RechargeCard(unittest.TestCase):
    def setUp(self):
        self.store = Store()
        self.facts = FakeFacts(seekers={SEEKER})
        self.svc = Service(self.store, FakeRpc(), facts=self.facts)

    def buy(self, invoice, who=SEEKER, category="recharge"):
        self.store.remember_purchase(invoice, who, "jio-india", category, 210_000, 0.21)

    def cards(self, who=SEEKER):
        return [r for r in self.store.rewards_for(who) if r["kind"] == rewards.RECHARGE]

    def test_a_delivered_recharge_earns_one_card(self):
        self.buy("inv1")
        self.svc.settle_order("inv1", delivered())
        got = self.cards()
        self.assertEqual(1, len(got))
        self.assertEqual(10, got[0]["skr"])
        self.assertEqual("recharge-inv1", got[0]["reference"])

    def test_paid_but_not_delivered_earns_nothing(self):
        self.buy("inv1")
        self.svc.settle_order("inv1", delivered("not_delivered"))
        self.assertEqual([], self.cards())

    def test_a_gift_card_earns_nothing(self):
        self.buy("inv1", category="gift")
        self.svc.settle_order("inv1", delivered())
        self.assertEqual([], self.cards())

    def test_not_a_seeker_earns_nothing(self):
        self.buy("inv1", who=FRIEND)
        self.svc.settle_order("inv1", delivered())
        self.assertEqual([], self.cards(FRIEND))

    def test_the_same_order_looked_at_again_still_gives_one_card(self):
        self.buy("inv1")
        for _ in range(4):
            self.svc.settle_order("inv1", delivered())
        self.assertEqual(1, len(self.cards()))

    def test_a_chain_that_could_not_be_asked_is_asked_again(self):
        facts = Unsure(seekers={SEEKER})
        svc = Service(self.store, FakeRpc(), facts=facts)
        self.buy("inv1")
        svc.settle_order("inv1", delivered())
        self.assertEqual([], self.cards())          # no card on a guess
        facts.down = False
        svc.settle_order("inv1", delivered())       # the app asks about its order again
        self.assertEqual(1, len(self.cards()))

    def test_the_daily_limit_holds(self):
        self.svc.set_reward_config({"recharge_daily": 2})
        for n in range(4):
            self.buy(f"inv{n}")
            self.svc.settle_order(f"inv{n}", delivered())
        self.assertEqual(2, len(self.cards()))

    def test_the_weekly_budget_holds(self):
        self.svc.set_reward_config({"recharge_pool": 20, "recharge_daily": 9})
        for n in range(5):
            self.buy(f"inv{n}")
            self.svc.settle_order(f"inv{n}", delivered())
        self.assertEqual(2, len(self.cards()))      # 20 SKR of budget, 10 a card

    def test_zero_turns_it_off(self):
        self.svc.set_reward_config({"recharge": 0})
        self.buy("inv1")
        self.svc.settle_order("inv1", delivered())
        self.assertEqual([], self.cards())

    def test_it_does_not_spend_the_tap_pool(self):
        self.buy("inv1")
        self.svc.settle_order("inv1", delivered())
        summary = self.svc.reward_summary(SEEKER)
        self.assertEqual(self.svc.reward_config().weekly_pool, summary["pool_left"])
        self.assertEqual(0, summary["this_month"]["earned"])


class DemoVoucher(unittest.TestCase):
    """The demonstration voucher card: off unless asked for, worth nothing, and only for the one
    purchase it is a demonstration of."""

    def setUp(self):
        self.store = Store()
        self.svc = Service(self.store, FakeRpc(), facts=FakeFacts(seekers={SEEKER}))

    def buy(self, invoice, product="phonepe-india", who=SEEKER):
        self.store.remember_purchase(invoice, who, product, "gift", 1_110_000, 1.11)
        self.svc.settle_order(invoice, delivered())

    def vouchers(self, who=SEEKER):
        return [r for r in self.svc.reward_summary(who)["recent"] if r["kind"] == rewards.VOUCHER]

    def test_it_is_off_unless_asked_for(self):
        self.buy("inv1")
        self.assertEqual([], self.vouchers())

    def test_on_it_gives_one_card_that_says_what_it_opens_onto(self):
        self.svc.set_reward_config({"demo_voucher": 1})
        self.buy("inv1")
        self.svc.settle_order("inv1", delivered())
        got = self.vouchers()
        self.assertEqual(1, len(got))
        self.assertEqual(0, got[0]["skr"])
        self.assertEqual("Tinder", got[0]["gift"]["brand"])
        self.assertTrue(got[0]["gift"]["demo"])

    def test_only_the_purchase_it_demonstrates_earns_it(self):
        self.svc.set_reward_config({"demo_voucher": 1})
        self.buy("inv1", product="hotstar-india")
        self.assertEqual([], self.vouchers())

    def test_it_is_worth_nothing_and_starts_no_payout(self):
        self.svc.set_reward_config({"demo_voucher": 1})
        self.buy("inv1")
        card = self.vouchers()[0]
        self.assertTrue(self.svc.reveal_reward(SEEKER, card["id"])["ok"])
        self.svc.store.settle_revealed(SEEKER)
        self.assertEqual([], self.store.open_payouts())
        self.assertEqual(self.svc.reward_config().weekly_pool, self.svc.reward_summary(SEEKER)["pool_left"])


if __name__ == "__main__":
    unittest.main()
