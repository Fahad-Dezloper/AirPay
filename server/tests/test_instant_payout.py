"""Scratching a card pays it: no lock, no claim, no minimum."""
from __future__ import annotations

import time
import unittest

from duffleserver.service import Service
from duffleserver.store import Store
from tests.test_payout import ADDR, FakePayer, OTHER
from tests.test_server import FakeRpc


class InstantTest(unittest.TestCase):
    def setUp(self):
        self.payer = FakePayer()
        self.svc = Service(Store(), FakeRpc(), payer=self.payer)   # default config: no lock, no minimum

    def card(self, skr=10, ref="signup-1"):
        now = time.time()
        self.svc.store.record_rewards(ref, ADDR, OTHER, [("signup", skr)], 1, now, now)
        return self.svc.store.rewards_for(ADDR, 5)[0]["id"]

    def test_a_scratched_card_is_paid_at_once(self):
        rid = self.card(10)
        self.svc.store.reveal_reward(ADDR, rid, time.time())
        self.assertEqual(1, self.svc.pay_revealed(ADDR))
        self.assertEqual([(self.payer.calls[0][0], ADDR, 10 * 10 ** 6)], self.payer.calls)
        bal = self.svc.store.reward_balance(ADDR, time.time())
        self.assertEqual(10, bal["paid"])

    def test_an_unscratched_card_is_not_paid(self):
        self.card(10)
        self.assertEqual(0, self.svc.pay_revealed(ADDR))
        self.assertEqual([], self.payer.calls)

    def test_a_card_is_never_paid_twice(self):
        rid = self.card(10)
        self.svc.store.reveal_reward(ADDR, rid, time.time())
        self.svc.pay_revealed(ADDR)
        self.svc.pay_revealed(ADDR)
        self.svc.pay_revealed()
        self.assertEqual(1, len(self.payer.calls))

    def test_the_watcher_pass_catches_cards_scratched_before_this_change(self):
        rid = self.card(10)
        self.svc.store.reveal_reward(ADDR, rid, time.time())
        self.assertEqual(1, self.svc.pay_revealed())
        self.assertEqual(1, len(self.payer.calls))


if __name__ == "__main__":
    unittest.main()
