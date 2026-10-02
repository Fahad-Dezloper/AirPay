"""
Offers are told to a person one at a time, a few hours apart, by push — not all at once on the
notification page the moment they sign up. An offer reaches that page only after it has been sent.
"""
from __future__ import annotations

import time
import unittest

from duffleserver import seed
from duffleserver.service import Service
from duffleserver.store import Store
from tests.test_reward_flow import SEEKER, FakeFacts
from tests.test_server import FakeFcm, FakeRpc

OTHER_SEEKER = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
H = 3600.0


class NoticeBase(unittest.TestCase):
    def setUp(self, facts=None):
        self.t0 = time.time()
        self.fcm = FakeFcm()
        self.svc = Service(Store(), FakeRpc(), fcm=self.fcm, facts=facts or FakeFacts(seekers=(SEEKER, OTHER_SEEKER)))
        for o in seed.launch_offers(self.t0):
            self.svc.put_offer(o)
        self.svc.add_device(SEEKER, "t" * 30)
        self.svc.store.ensure_account(SEEKER, self.t0)

    def run_at(self, hours):
        return self.svc.send_offer_notices(self.t0 + hours * H)

    def told(self):
        return [d["offer"] for _, d in self.fcm.sent if d.get("state") == "new"]


class DripTest(NoticeBase):
    def test_nothing_is_sent_before_the_first_delay(self):
        self.assertEqual(0, self.run_at(0.1))
        self.assertEqual(0, self.run_at(2.9))
        self.assertEqual([], self.told())

    def test_the_first_offer_goes_out_after_the_delay_and_is_the_featured_one(self):
        self.assertEqual(1, self.run_at(3.1))
        self.assertEqual(["jupiter-first-swap"], self.told())

    def test_one_at_a_time_with_a_gap_between(self):
        self.run_at(3.1)
        self.assertEqual(0, self.run_at(6.0))            # only ~3h since the last
        self.assertEqual(1, self.run_at(9.2))
        self.assertEqual(0, self.run_at(10.0))
        self.assertEqual(1, self.run_at(15.4))
        self.assertEqual(3, len(set(self.told())))

    def test_once_every_offer_has_been_told_nothing_more_is_sent(self):
        for h in (3.1, 9.2, 15.4, 21.6, 27.8):
            self.run_at(h)
        self.assertEqual(3, len(self.told()))

    def test_the_push_says_what_the_offer_is(self):
        self.run_at(3.1)
        data = self.fcm.sent[0][1]
        self.assertEqual({"type": "offer", "state": "new"}, {k: data[k] for k in ("type", "state")})
        self.assertEqual(300, data["skr"])
        self.assertEqual("Jupiter", data["app"])
        self.assertIn("Jupiter", data["detail"])

    def test_the_notification_page_only_has_offers_that_have_been_told(self):
        before = {o["id"]: o["notified_at"] for o in self.svc.offers_for(SEEKER)}
        self.assertEqual({None}, set(before.values()))
        self.run_at(3.1)
        after = {o["id"]: o["notified_at"] for o in self.svc.offers_for(SEEKER)}
        self.assertIsNotNone(after["jupiter-first-swap"])
        self.assertIsNone(after["airpay-gift-card"])
        self.assertIsNone(after["airpay-top-up"])

    def test_an_offer_already_claimed_is_not_pushed(self):
        self.svc.claim_offer("jupiter-first-swap", SEEKER)
        self.run_at(3.1)
        self.assertNotIn("jupiter-first-swap", self.told())


class WhoGetsThemTest(NoticeBase):
    def test_a_wallet_with_no_phone_registered_for_push_is_not_scheduled(self):
        self.svc.store.ensure_account(OTHER_SEEKER, self.t0)    # no device for it
        self.run_at(3.1)
        self.assertEqual(1, len(self.told()))                    # SEEKER's only

    def test_a_non_seeker_is_not_pushed_offers_and_is_looked_at_again_much_later(self):
        super().setUp(facts=FakeFacts(seekers=()))
        self.assertEqual(0, self.run_at(3.1))
        self.assertEqual(0, self.run_at(20))
        self.assertEqual([], self.told())

    def test_when_the_chain_cannot_be_asked_nothing_is_sent_and_it_is_retried_soon(self):
        super().setUp(facts=FakeFacts(unreachable=True))
        self.assertEqual(0, self.run_at(3.1))
        self.assertLess(self.svc.store.notice_due(self.svc.store.account_key(SEEKER)), self.t0 + 3.1 * H + 0.3 * H)

    def test_a_person_with_two_wallets_and_one_phone_hears_each_offer_once(self):
        store = self.svc.store
        store.link_wallet(store.account_of(SEEKER), OTHER_SEEKER, self.t0)
        self.svc.add_device(OTHER_SEEKER, "t" * 30)              # the same phone, under the second wallet
        self.run_at(3.1)
        self.assertEqual(1, len(self.fcm.sent))

    def test_two_wallets_of_one_person_share_the_same_schedule(self):
        store = self.svc.store
        store.link_wallet(store.account_of(SEEKER), OTHER_SEEKER, self.t0)
        self.svc.add_device(OTHER_SEEKER, "u" * 30)              # a second phone
        self.run_at(3.1)
        self.assertEqual(0, self.run_at(4.0))
        told_a = {o["id"]: o["notified_at"] for o in self.svc.offers_for(SEEKER)}
        told_b = {o["id"]: o["notified_at"] for o in self.svc.offers_for(OTHER_SEEKER)}
        self.assertEqual(told_a, told_b)


if __name__ == "__main__":
    unittest.main()
