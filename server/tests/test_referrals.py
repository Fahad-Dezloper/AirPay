"""
Referrals: who gets paid for bringing somebody, and every way that could be gamed.

A referral is the cheapest reward in the system to manufacture — a wallet is free, so "somebody
joined with my code" costs an attacker nothing. Most of what follows is therefore about refusal
rather than payment.
"""
from __future__ import annotations

import unittest

from duffleserver import rewards
from duffleserver.service import Service
from duffleserver.store import Store
from tests.test_reward_flow import FRIEND, OTHER, REF1, REF2, SEEKER, SIG, FakeFacts
from tests.test_server import FakeRpc

#: A second Seeker, so an inviter can be a real earner in their own right.
INVITER = "3XyQPGCGGKVdG9nVnkFhLWk9XBFVVkDqEFhAjTeCvxmn"


class ReferralTest(unittest.TestCase):
    """Deliberately not a subclass of FlowTest: inheriting it to borrow `pay()` would re-run
    every payment test a second time under this file's setUp, for nothing."""

    def setUp(self, facts=None):
        self.store = Store()
        # Both wallets hold Genesis: the invitee has to be able to earn at all for the
        # referral to have anything to hang off.
        self.svc = Service(self.store, FakeRpc(),
                           facts=facts or FakeFacts(seekers=(SEEKER, INVITER)))

    def pay(self, reference=REF1, payer=SEEKER, recipient=FRIEND, amount="0.1", origin="tap"):
        self.svc.register(reference, recipient, amount, payer=payer, origin=origin)
        self.svc._settle(self.store.get(reference), SIG[:-4] + reference[:4])
        return reference

    def code_of(self, address=INVITER):
        return self.svc.referral_summary(address)["code"]

    # -- the code -------------------------------------------------------------------

    def test_a_wallet_gets_the_same_code_every_time(self):
        # Two phones asking must not produce two codes for one wallet.
        self.assertEqual(self.code_of(), self.code_of())

    def test_different_wallets_get_different_codes(self):
        self.assertNotEqual(self.code_of(INVITER), self.code_of(SEEKER))

    def test_the_code_avoids_characters_that_are_read_wrong_aloud(self):
        # I/L/O/U are out: the first three are unreadable next to 1 and 0, and the last makes
        # short codes into words.
        self.assertTrue(set(self.code_of()).isdisjoint(set("ILOU")))

    def test_a_code_resolves_back_to_its_wallet(self):
        self.assertEqual(INVITER, self.store.address_for_code(self.code_of()))

    def test_a_code_is_read_case_insensitively(self):
        # Somebody typing it off a screenshot will not hold shift.
        self.assertEqual(INVITER, self.store.address_for_code(self.code_of().lower()))

    def test_an_unknown_code_resolves_to_nobody(self):
        self.assertIsNone(self.store.address_for_code("ZZZZZZZZ"))

    # -- claiming -------------------------------------------------------------------

    def test_claiming_records_who_invited_you(self):
        self.assertTrue(self.svc.claim_referral(self.code_of(), SEEKER)["ok"])
        self.assertEqual(INVITER, self.store.referral_of(SEEKER)["inviter"])

    def test_you_cannot_invite_yourself(self):
        out = self.svc.claim_referral(self.code_of(SEEKER), SEEKER)
        self.assertEqual(rewards.SELF_REFERRAL, out["refused"])

    def test_you_can_only_be_invited_once(self):
        self.svc.claim_referral(self.code_of(INVITER), SEEKER)
        out = self.svc.claim_referral(self.code_of(FRIEND), SEEKER)
        self.assertEqual(rewards.ALREADY_REFERRED, out["refused"])
        self.assertEqual(INVITER, self.store.referral_of(SEEKER)["inviter"], "the first one wins")

    def test_an_unknown_code_is_refused(self):
        self.assertEqual(rewards.UNKNOWN_CODE,
                         self.svc.claim_referral("ZZZZZZZZ", SEEKER)["refused"])

    def test_a_code_is_an_invitation_not_a_receipt(self):
        # Claimed after already earning: this person was here before the code existed, and
        # paying for them would make every existing user farmable.
        self.pay()
        out = self.svc.claim_referral(self.code_of(), SEEKER)
        self.assertEqual(rewards.REFERRAL_TOO_LATE, out["refused"])

    # -- getting paid ---------------------------------------------------------------

    def test_the_inviter_is_paid_on_the_invitees_first_payment(self):
        self.svc.claim_referral(self.code_of(), SEEKER)
        self.pay()
        got = [(r["kind"], r["skr"]) for r in self.store.rewards_for(INVITER)]
        self.assertEqual([(rewards.REFERRAL, 300)], got)

    def test_joining_alone_pays_nobody(self):
        """The load-bearing rule. A wallet is free, so a claim on its own is worth nothing."""
        self.svc.claim_referral(self.code_of(), SEEKER)
        self.assertEqual([], self.store.rewards_for(INVITER))

    def test_an_invitee_who_never_earns_never_pays_the_inviter(self):
        self.svc.claim_referral(self.code_of(), SEEKER)
        self.pay(origin="")  # not a tap, so it earns nothing
        self.assertEqual([], self.store.rewards_for(INVITER))

    def test_the_inviter_is_paid_once_however_many_payments_follow(self):
        self.svc.claim_referral(self.code_of(), SEEKER)
        self.pay(REF1)
        self.pay(REF2, recipient=OTHER)
        self.assertEqual(1, len([r for r in self.store.rewards_for(INVITER)
                                 if r["kind"] == rewards.REFERRAL]))

    def test_an_unreferred_payer_pays_nobody(self):
        self.pay()
        self.assertEqual([], self.store.rewards_for(INVITER))

    def test_the_referral_answers_to_the_pool(self):
        # The easiest reward to manufacture is the last one that should ignore the budget.
        self.svc.set_reward_config({"weekly_pool": 300})
        self.svc.claim_referral(self.code_of(), SEEKER)
        self.pay()  # the invitee's own awards drain the pool first
        self.assertEqual([], [r for r in self.store.rewards_for(INVITER)
                              if r["kind"] == rewards.REFERRAL])

    def test_an_unpaid_referral_is_not_marked_paid(self):
        # So it can still be paid when the pool refills, rather than being lost.
        self.svc.set_reward_config({"weekly_pool": 300})
        self.svc.claim_referral(self.code_of(), SEEKER)
        self.pay()
        self.assertIsNone(self.store.referral_of(SEEKER)["paid_at"])

    # -- what the screen shows ------------------------------------------------------

    def test_the_summary_counts_invited_and_paid_separately(self):
        self.svc.claim_referral(self.code_of(), SEEKER)
        s = self.svc.referral_summary(INVITER)
        self.assertEqual((1, 0, 0), (s["invited"], s["paid"], s["earned"]),
                         "invited but not yet earning is the honest state to show")
        self.pay()
        s = self.svc.referral_summary(INVITER)
        self.assertEqual((1, 1, 300), (s["invited"], s["paid"], s["earned"]))

    def test_the_summary_states_both_halves_of_the_offer(self):
        s = self.svc.referral_summary(INVITER)
        self.assertEqual(300, s["reward"])
        self.assertEqual(250, s["their_reward"], "what they get is their reason to accept")


if __name__ == "__main__":
    unittest.main()
