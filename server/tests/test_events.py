"""Everything that changes for a person arrives as an event, at the moment it changes."""
from __future__ import annotations

import time
import unittest

from duffleserver import rewards
from duffleserver.service import Service
from duffleserver.store import Store
from tests.test_payout import ADDR, FakePayer, OTHER, settled
from tests.test_server import FakeRpc


class Facts:
    def is_seeker(self, a): return True
    def first_seen(self, a): return 0.0


class EventTest(unittest.TestCase):
    def setUp(self):
        self.svc = Service(Store(), FakeRpc(), facts=Facts(), payer=FakePayer())
        self.got = []
        self.svc.hub.publish = lambda to, ev: self.got.append((to, ev)) or 1

    def of(self, who, type_, state=None):
        return [e for t, e in self.got if t == who and e["type"] == type_ and (state is None or e.get("state") == state)]

    def test_a_referral_claim_tells_both_people_at_once(self):
        code = self.svc.referral_summary(ADDR)["code"]
        self.assertTrue(self.svc.claim_referral(code, OTHER)["ok"])
        self.assertEqual(OTHER, self.of(ADDR, "referral", "joined")[0]["invitee"])
        self.assertEqual(ADDR, self.of(OTHER, "referral", "claimed")[0]["inviter"])

    def test_a_refused_referral_claim_tells_nobody(self):
        self.svc.claim_referral("NOSUCHCODE", OTHER)
        self.svc.claim_referral(self.svc.referral_summary(ADDR)["code"], ADDR)
        self.assertEqual([], self.got)

    def test_the_inviter_is_told_when_the_friend_makes_their_first_paid_tap(self):
        self.svc.claim_referral(self.svc.referral_summary(ADDR)["code"], OTHER)
        self.got.clear()
        self.svc._pay_referrer(OTHER, "ref-first", time.time(), self.svc.reward_config())
        paid = self.of(ADDR, "referral", "paid")
        self.assertEqual((OTHER, 300), (paid[0]["invitee"], paid[0]["skr"]))
        # One thing happened, so one notice: it used to be followed by a "reward" event for the
        # same SKR, which the phone showed as a second notification beside the first.
        self.assertEqual(1, len(paid))
        self.assertEqual([], self.of(ADDR, "reward"))
        self.assertEqual("ref-first", paid[0]["reference"])

    def test_rewards_that_become_claimable_say_so(self):
        now = time.time()
        self.svc.store.record_rewards("r1", ADDR, OTHER, [("per_payment", 50)], 1, now - 9 * 86400, now - 1)
        self.svc.patrol(now)
        ev = self.of(ADDR, "reward", "settled")
        self.assertEqual(50, ev[0]["skr"])

    def test_nothing_maturing_sends_nothing(self):
        self.svc.patrol(time.time())
        self.assertEqual([], self.of(ADDR, "reward"))

    def test_a_payout_announces_sent_then_confirmed(self):
        settled(self.svc, ADDR, 300, "r1")
        self.svc.payer.script = [{"state": "sending", "signature": "s" * 88}, {"state": "confirmed", "signature": "s" * 88}]
        self.svc.claim_rewards(ADDR)
        self.svc.settle_payouts()
        self.assertEqual(["sent", "confirmed"], [e["state"] for e in self.of(ADDR, "payout")])

    def test_events_go_only_to_the_person_they_are_about(self):
        settled(self.svc, ADDR, 300, "r1")
        self.svc.claim_rewards(ADDR)
        self.assertEqual([], [e for t, e in self.got if t == OTHER])


if __name__ == "__main__":
    unittest.main()
