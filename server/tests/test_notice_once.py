"""One thing that happened is told once.

Each test here is a way the same notification used to be able to reach a phone twice: a server
restart, a person's two wallets, the wallet watcher racing the payment sweep, our own SKR payout
seen a second time as "money arrived", and a phone retrying a POST it got no answer to.
"""
from __future__ import annotations

import time
import unittest

from duffleserver import notify
from duffleserver.service import Service
from duffleserver.store import Store
from tests.test_activity import ME, OTHER, tx
from tests.test_server import PAYER, REF, SIG, TO, TX, FakeFcm, FakeRpc


class ListRpc(FakeRpc):
    """An RPC whose answer to "what touched this address" is scripted, one list per look."""

    def __init__(self, txs=None):
        super().__init__(txs=txs if txs is not None else {})
        self.lists: list[list[str]] = []
        self.when: dict[str, float] = {}

    def call(self, method, params):
        sigs = self.lists.pop(0) if self.lists else []
        return [{"signature": s, "err": None, "blockTime": self.when.get(s, time.time())} for s in sigs]

    def transaction(self, sig):
        return self.txs.get(sig, tx([OTHER, ME], [5_000_000_000, 0], [4_499_995_000, 500_000_000]))


class Base(unittest.TestCase):
    def service(self, store=None, rpc=None):
        self.rpc = rpc or getattr(self, "rpc", None) or ListRpc()
        svc = Service(store or Store(), self.rpc)
        self.sent = []
        real = svc._deliver
        svc._deliver = lambda to, ev: self.sent.append((to, ev)) or real(to, ev)
        return svc

    def look(self, svc, sigs):
        self.rpc.lists.append(sigs)
        return svc.watch_wallets()

    def kinds(self, who=None):
        return [ev["type"] for to, ev in self.sent if who is None or to == who]


class RestartTest(Base):
    def test_a_restart_does_not_announce_a_transfer_a_second_time(self):
        store = Store()
        store.add_device(ME, "t" * 40)
        svc = self.service(store)
        self.look(svc, ["b", "a"])
        self.assertEqual(1, self.look(svc, ["c", "b", "a"]))
        # The server restarts: everything it held in memory is gone, the database is not. Its
        # first look is answered by a node that is behind and has not got "c" yet; the next
        # look has it, and "c" is seconds old, so it is "new" and "recent" all over again.
        again = self.service(store)
        self.assertEqual(0, self.look(again, ["b", "a"]))
        self.assertEqual(0, self.look(again, ["c", "b", "a"]))
        self.assertEqual([], self.sent)

    def test_a_transfer_that_lands_after_the_restart_is_still_announced(self):
        store = Store()
        store.add_device(ME, "t" * 40)
        self.look(self.service(store), ["a"])
        again = self.service(store)
        self.look(again, ["a"])
        self.assertEqual(1, self.look(again, ["d", "a"]))
        self.assertEqual(["wallet"], self.kinds())

    def test_what_was_announced_is_forgotten_only_after_it_is_far_too_old_to_matter(self):
        store = Store()
        self.assertTrue(store.claim_wallet_notice("acct", "s1", 100.0))
        self.assertFalse(store.claim_wallet_notice("acct", "s1", 200.0))
        self.assertTrue(store.claim_wallet_notice("other", "s1", 200.0), "another person, another notice")
        self.assertEqual(1, store.prune_wallet_notices(150.0))
        self.assertTrue(store.claim_wallet_notice("acct", "s1", 300.0))


class TwoWalletsTest(Base):
    def test_a_move_between_your_own_wallets_is_one_notice_not_two(self):
        store = Store()
        acct = store.ensure_account(ME, time.time())
        store.link_wallet(acct, OTHER, time.time())
        # One phone, registered under both wallets, which is what /v1/devices does.
        store.add_device(ME, "t" * 40)
        store.add_device(OTHER, "t" * 40)
        svc = self.service(store)
        for _ in store.device_recipients():
            self.rpc.lists.append(["a"])
        svc.watch_wallets()
        for _ in store.device_recipients():
            self.rpc.lists.append(["own", "a"])
        self.assertEqual(1, svc.watch_wallets())
        self.assertEqual(1, len(self.sent))

    def test_two_phones_on_one_account_each_hear_it_once(self):
        store = Store()
        store.add_device(ME, "a" * 40)
        store.add_device(ME, "b" * 40)
        fcm = FakeFcm()
        self.rpc = ListRpc()
        svc = Service(store, self.rpc, fcm=fcm)
        self.look(svc, ["a"])
        self.look(svc, ["c", "a"])
        self.look(svc, ["c", "a"])
        self.assertEqual(sorted(["a" * 40, "b" * 40]), sorted(t for t, _ in fcm.sent))


class OneStoryTest(Base):
    def test_a_payment_the_watcher_sees_first_is_told_as_paid_and_not_also_as_money_arriving(self):
        """The wallet look runs every few seconds and the payment sweep on its own schedule, so
        the look can reach a transfer while its request is still open. It used to announce
        "Received" then, and the sweep announced "paid" a moment later for the same money."""
        store = Store()
        store.add_device(TO, "t" * 40)
        svc = self.service(store, ListRpc(txs={SIG: TX}))
        svc.register(REF, TO, "0.01", "Seeker", payer=PAYER, deliver=False)
        self.look(svc, [])
        self.assertEqual(0, self.look(svc, [SIG]))
        self.assertEqual(["paid"], self.kinds(TO))
        self.assertEqual("paid", store.get(REF)["status"])
        # And the sweep, arriving late, has nothing left to say.
        svc.check_reference(REF)
        self.assertEqual(["paid"], self.kinds(TO))

    def test_our_own_skr_payout_is_not_also_announced_as_money_arriving(self):
        store = Store()
        store.add_device(ME, "t" * 40)
        with store._lock, store._db:
            store._db.execute(
                "INSERT INTO payouts(id,address,skr,state,signature,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                ("p1", ME, 300, "sent", "payoutsig", 1.0, 1.0))
        svc = self.service(store)
        self.look(svc, ["a"])
        self.assertEqual(0, self.look(svc, ["payoutsig", "a"]))
        self.assertEqual([], self.sent)


class RetryTest(Base):
    def setUp(self):
        self.svc = self.service(Store(), FakeRpc())
        self.svc.register(REF, TO, "0.01", "Seeker", payer=PAYER, deliver=False)

    def test_later_tapped_three_times_is_one_notice(self):
        for _ in range(3):
            self.svc.defer(REF, "CPH2401")
        self.assertEqual(["deferred"], self.kinds())

    def test_a_retried_decline_is_one_notice(self):
        for _ in range(3):
            self.svc.reject(REF, "CPH2401")
        self.assertEqual(["rejected"], self.kinds())

    def test_later_after_a_decline_says_nothing(self):
        self.svc.reject(REF, "CPH2401")
        self.svc.defer(REF, "CPH2401")
        self.assertEqual(["rejected"], self.kinds())

    def test_a_retried_withdrawal_is_one_notice(self):
        for _ in range(3):
            self.svc.withdraw(REF)
        self.assertEqual(["withdrawn"], self.kinds())

    def test_a_withdrawal_that_reached_nobody_does_not_use_up_the_one_that_can(self):
        svc = self.service(Store(), FakeRpc())
        svc.register(REF, TO, "0.01", "Seeker")              # nobody known to be paying yet
        self.assertEqual(0, svc.withdraw(REF))
        svc.register(REF, TO, "0.01", "Seeker", payer=PAYER, deliver=False)
        svc.withdraw(REF)
        self.assertEqual(["withdrawn"], self.kinds())

    def test_registering_the_same_request_twice_asks_the_payer_once(self):
        svc = self.service(Store(), FakeRpc())
        for _ in range(3):
            svc.register(REF, TO, "0.01", "Seeker", payer=PAYER)
        self.assertEqual(["request"], self.kinds())

    def test_a_payment_found_twice_is_announced_once(self):
        self.assertEqual("paid", self.svc.check_reference(REF))
        self.svc.check_reference(REF)
        self.svc.check_signature(SIG)
        self.assertEqual(1, self.kinds().count("paid"))


class UrgencyTest(unittest.TestCase):
    def test_money_and_people_wake_the_phone(self):
        for kind in ("paid", "wallet", "request", "rejected", "deferred", "tap", "message"):
            self.assertEqual("high", notify.android_config(kind)["priority"], kind)

    def test_rewards_and_offers_do_not(self):
        for kind in ("reward", "offer", "referral", "payout"):
            self.assertEqual("normal", notify.android_config(kind)["priority"], kind)

    def test_a_tap_is_not_delivered_long_after_they_have_gone(self):
        self.assertEqual("300s", notify.android_config("tap")["ttl"])
        self.assertNotIn("ttl", notify.android_config("paid"), "money is news however late")


if __name__ == "__main__":
    unittest.main()
