"""
One person, several wallets.

Signing in once covers every wallet the person has proven is theirs; switching wallets asks for
nothing. The rules that make that safe are the ones pinned here:

  * a wallet joins an account only by SIGNING for itself — nobody can attach a wallet they do not
    control, or lock a stranger out of their own sign-up reward;
  * a wallet is never moved between accounts, so one that has been through the app before comes
    back to the same account on any phone;
  * one-time and capped rewards are counted per account, not per wallet, so two wallets are not
    two people;
  * the `X-Wallet` header chooses among the account's wallets and can never name anyone else's.
"""
from __future__ import annotations

import base64
import json
import threading
import unittest
import urllib.error
import urllib.request

from nacl.signing import SigningKey

from duffleserver import rewards
from duffleserver.auth import b58encode
from duffleserver.service import Service
from duffleserver.store import Store
from duffleserver.web import serve
from tests.test_reward_flow import FRIEND, REF1, REF2, SEEKER, FakeFacts, SIG
from tests.test_server import FakeRpc
from tests.revealutil import scratch_all


def new_wallet():
    k = SigningKey.generate()
    return k, b58encode(bytes(k.verify_key))


def sign(key, text: str) -> str:
    return base64.b64encode(key.sign(text.encode()).signature).decode()


class HttpBase(unittest.TestCase):
    def setUp(self):
        self.svc = Service(Store(), FakeRpc())
        self.svc.set_reward_config({"lock_days": 7, "min_payout": 100})
        self.httpd = serve(self.svc, "127.0.0.1", 0, admin_token="owner")
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def call(self, path, body=None, token=None, wallet=None):
        hdr = {"Content-Type": "application/json"}
        if token:
            hdr["Authorization"] = f"Bearer {token}"
        if wallet:
            hdr["X-Wallet"] = wallet
        req = urllib.request.Request(self.base + path, json.dumps(body).encode() if body is not None else None, hdr)
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            with e:
                return e.code, json.loads(e.read())

    def sign_in(self, key, addr):
        _, c = self.call("/v1/auth/challenge", {"address": addr})
        code, s = self.call("/v1/auth/verify", {"nonce": c["nonce"], "signature": sign(key, c["message"])})
        self.assertEqual(200, code)
        return s

    def link(self, token, key, addr, wallet=None):
        code, c = self.call("/v1/auth/challenge", {"address": addr, "purpose": "link"}, token)
        if code != 200:
            return code, c
        return self.call("/v1/auth/link", {"nonce": c["nonce"], "signature": sign(key, c["message"])}, token, wallet)


class LinkingTest(HttpBase):
    def setUp(self):
        super().setUp()
        self.ka, self.a = new_wallet()   # the first wallet, signed in
        self.kb, self.b = new_wallet()   # a second one of the same person
        self.s = self.sign_in(self.ka, self.a)
        self.token = self.s["token"]

    def test_signing_in_makes_an_account_with_that_wallet_in_it(self):
        self.assertEqual([self.a], self.s["wallets"])

    def test_a_second_wallet_joins_by_signing_for_itself(self):
        code, body = self.link(self.token, self.kb, self.b)
        self.assertEqual(200, code)
        self.assertEqual({self.a, self.b}, set(body["wallets"]))

    def test_after_linking_one_token_acts_as_either_wallet(self):
        self.link(self.token, self.kb, self.b)
        self.assertEqual(200, self.call(f"/v1/rewards/{self.a}", token=self.token, wallet=self.a)[0])
        self.assertEqual(200, self.call(f"/v1/rewards/{self.b}", token=self.token, wallet=self.b)[0])

    def test_without_linking_the_second_wallet_is_a_stranger(self):
        self.assertEqual(403, self.call(f"/v1/rewards/{self.b}", token=self.token, wallet=self.b)[0])

    def test_someone_elses_signature_cannot_link_a_wallet(self):
        stranger_key, _ = new_wallet()
        code, _ = self.link(self.token, stranger_key, self.b)   # signed by the wrong key
        self.assertEqual(401, code)
        self.assertEqual(403, self.call(f"/v1/rewards/{self.b}", token=self.token, wallet=self.b)[0])

    def test_a_link_needs_a_session_even_to_ask_for_its_challenge(self):
        code, _ = self.call("/v1/auth/challenge", {"address": self.b, "purpose": "link"})
        self.assertEqual(401, code)

    def test_a_sign_in_challenge_cannot_be_used_to_link_and_the_reverse(self):
        _, c = self.call("/v1/auth/challenge", {"address": self.b})              # sign-in challenge
        code, _ = self.call("/v1/auth/link", {"nonce": c["nonce"], "signature": sign(self.kb, c["message"])}, self.token)
        self.assertEqual(401, code)
        _, c2 = self.call("/v1/auth/challenge", {"address": self.b, "purpose": "link"}, self.token)  # link one
        code, _ = self.call("/v1/auth/verify", {"nonce": c2["nonce"], "signature": sign(self.kb, c2["message"])})
        self.assertEqual(401, code)

    def test_the_wallet_header_cannot_name_a_wallet_outside_the_account(self):
        other_key, other = new_wallet()
        self.sign_in(other_key, other)   # a different person, with their own account
        # Acting as them with MY token: ignored, and I am treated as myself.
        self.assertEqual(403, self.call(f"/v1/rewards/{other}", token=self.token, wallet=other)[0])
        self.assertEqual(200, self.call(f"/v1/rewards/{self.a}", token=self.token, wallet=other)[0])

    def test_a_wallet_that_belongs_to_another_account_cannot_be_taken(self):
        kc, c = new_wallet()
        other = self.sign_in(kc, c)                       # c has its own account
        code, body = self.link(self.token, kc, c)         # my account tries to take it
        self.assertEqual(409, code)
        self.assertEqual("wallet_in_use", body["error"])

    def test_signing_in_with_a_linked_wallet_on_a_new_phone_returns_the_same_account(self):
        self.link(self.token, self.kb, self.b)
        again = self.sign_in(self.kb, self.b)             # a fresh install, signed in with wallet B only
        self.assertEqual({self.a, self.b}, set(again["wallets"]))
        self.assertEqual(self.svc.store.account_of(self.a), self.svc.store.account_of(self.b))

    def test_linking_twice_is_harmless(self):
        self.link(self.token, self.kb, self.b)
        code, body = self.link(self.token, self.kb, self.b)
        self.assertEqual(200, code)
        self.assertEqual(2, len(body["wallets"]))


class OneTimeRewardsTest(unittest.TestCase):
    """Two wallets are one person: the sign-up bonus, the caps and 'paying yourself' all count
    across the account."""

    def setUp(self):
        self.store = Store()
        self.svc = Service(self.store, FakeRpc(), facts=FakeFacts(seekers=(SEEKER, SEEKER2)))
        now = 1_700_000_000.0
        self.store.ensure_account(SEEKER, now)
        self.store.link_wallet(self.store.account_of(SEEKER), SEEKER2, now)

    def pay(self, reference, payer, recipient=FRIEND):
        self.svc.register(reference, recipient, "0.1", payer=payer, origin="tap")
        self.assertTrue(self.svc._settle(self.store.get(reference), SIG[:-4] + reference[:4]))

    def kinds(self, wallet):
        return sorted(r["kind"] for r in self.store.rewards_for(wallet))

    def test_the_first_payment_bonus_is_paid_once_per_account_not_per_wallet(self):
        self.pay(REF1, SEEKER)
        self.assertIn("first_payment", self.kinds(SEEKER))
        self.pay(REF2, SEEKER2, recipient=OTHER_FRIEND)    # the same person's other wallet, another payee
        both = [r["kind"] for r in self.store.rewards_for(SEEKER)]   # the account's rewards, from either wallet
        self.assertEqual(1, both.count("first_payment"))

    def test_paying_your_own_other_wallet_earns_nothing(self):
        self.svc.register(REF1, SEEKER2, "0.1", payer=SEEKER, origin="tap")
        self.svc._settle(self.store.get(REF1), SIG[:-4] + REF1[:4])
        self.assertEqual([], self.store.rewards_for(SEEKER))
        self.assertEqual(rewards.SELF_PAYMENT, self.store.refusals_for(SEEKER)[0]["refused"])

    def test_the_weekly_cap_is_shared_by_both_wallets(self):
        cfg = self.svc.reward_config()
        # Four rewarded payments this week fill it; the fifth, from the other wallet, is refused.
        refs = [new_wallet()[1] for _ in range(cfg.weekly_payments)]
        peers = [new_wallet()[1] for _ in range(cfg.weekly_payments + 1)]
        self.svc.facts.ages.update({p: 1_000_000_000.0 for p in peers})
        for ref, peer in zip(refs, peers):
            self.pay(ref, SEEKER, recipient=peer)
        self.pay(new_wallet()[1], SEEKER2, recipient=peers[-1])
        self.assertEqual(rewards.WEEKLY_CAP, self.store.refusals_for(SEEKER2)[0]["refused"])

    def test_either_wallet_sees_the_whole_balance(self):
        self.pay(REF1, SEEKER)
        scratch_all(self.store)
        self.assertEqual(self.store.reward_balance(SEEKER, 1e12)["total"], self.store.reward_balance(SEEKER2, 1e12)["total"])
        scratch_all(self.store)
        self.assertGreater(self.store.reward_balance(SEEKER2, 1e12)["total"], 0)

    def test_one_person_cannot_refer_their_own_other_wallet(self):
        out = self.svc.claim_referral(self.store.referral_code(SEEKER, 1.0), SEEKER2)
        self.assertEqual(rewards.SELF_REFERRAL, out["refused"])

    def test_a_person_is_invited_once_even_through_a_second_wallet(self):
        invite = self.store.referral_code(FRIEND, 1.0)
        self.assertTrue(self.svc.claim_referral(invite, SEEKER)["ok"])
        again = self.svc.claim_referral(invite, SEEKER2)
        self.assertEqual(rewards.ALREADY_REFERRED, again["refused"])


SEEKER2 = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
OTHER_FRIEND = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"


if __name__ == "__main__":
    unittest.main()


class WelcomeCardTest(HttpBase):
    """Every NEW account gets one normal card worth 10 SKR — once, however it comes back."""

    def cards(self, wallet):
        return [r for r in self.svc.store.rewards_for(wallet) if r["kind"] == "signup"]

    def test_a_new_account_gets_one_welcome_card_of_ten_skr(self):
        k, a = new_wallet()
        self.sign_in(k, a)
        cards = self.cards(a)
        self.assertEqual(1, len(cards))
        self.assertEqual(10, cards[0]["skr"])
        self.assertEqual("locked", cards[0]["state"])

    def test_signing_in_again_gives_nothing_more(self):
        k, a = new_wallet()
        self.sign_in(k, a)
        self.sign_in(k, a)
        self.assertEqual(1, len(self.cards(a)))

    def test_a_second_wallet_joining_the_account_gets_no_card(self):
        ka, a = new_wallet()
        kb, b = new_wallet()
        token = self.sign_in(ka, a)["token"]
        self.link(token, kb, b)
        # Counted once for the account (either wallet sees the same cards), not once per wallet.
        self.assertEqual(1, len(self.cards(a)))
        self.assertEqual(1, len(self.cards(b)))
        self.assertEqual(self.cards(a)[0]["id"], self.cards(b)[0]["id"])

    def test_a_wallet_already_in_an_account_signing_in_on_a_new_phone_gets_no_second_card(self):
        ka, a = new_wallet()
        kb, b = new_wallet()
        token = self.sign_in(ka, a)["token"]
        self.link(token, kb, b)
        self.sign_in(kb, b)                       # a fresh install, signed in with B only
        self.assertEqual(1, len(self.cards(a)))
        self.assertEqual(1, len(self.cards(b)))

    def test_two_different_people_each_get_their_own(self):
        k1, a1 = new_wallet()
        k2, a2 = new_wallet()
        self.sign_in(k1, a1)
        self.sign_in(k2, a2)
        self.assertEqual((1, 1), (len(self.cards(a1)), len(self.cards(a2))))

    def test_the_weekly_budget_bounds_what_making_many_wallets_costs(self):
        cfg = self.svc.reward_config()
        for _ in range(cfg.signup_pool // cfg.signup + 3):
            k, a = new_wallet()
            # Straight to the service: the HTTP sign-in is rate limited per address, which is its
            # own protection and not what this is about.
            c = self.svc.auth.challenge(a)
            self.svc.sign_in(c["nonce"], sign(k, c["message"]))
        import time
        from duffleserver import rewards
        given = self.svc.store.signup_spent(rewards.week_of(time.time()))
        self.assertEqual(cfg.signup_pool, given)

    def test_the_welcome_card_is_not_a_special_one_and_does_not_eat_the_tap_caps(self):
        import time
        from duffleserver import rewards
        k, a = new_wallet()
        self.sign_in(k, a)
        now = time.time()
        past = self.svc.store.reward_history(a, "", now, rewards.week_of(now), now - 86_400)
        self.assertEqual(0, past["earned_in_campaign"])
        self.assertEqual(0, past["earned_this_month"])
        self.assertEqual(0, past["week_spent"])
        self.assertFalse(past["had_first_payment"])

    def test_it_is_not_in_the_total_until_it_is_scratched(self):
        """The bug a new person hit: the Rewards page already said 10 SKR before they had opened
        the card. Nothing is summed — and nothing is claimable — until the card is scratched."""
        k, a = new_wallet()
        token = self.sign_in(k, a)["token"]
        code, body = self.call(f"/v1/rewards/{a}", token=token, wallet=a)
        self.assertEqual(200, code)
        card = body["recent"][0]
        # The card is there to scratch, with its amount fixed...
        self.assertEqual(("signup", 10), (card["kind"], card["skr"]))
        # ...but it is not counted anywhere yet.
        self.assertEqual({"total": 0, "locked": 0, "ready": 0, "claimable": 0, "claiming": 0, "paid": 0},
                         {k2: body["balance"][k2] for k2 in ("total", "locked", "ready", "claimable", "claiming", "paid")})
        # Scratching it shows the amount in the total — and changes nothing about what was earned.
        self.assertEqual(200, self.call("/v1/rewards/reveal", {"id": card["id"]}, token, a)[0])
        code, after = self.call(f"/v1/rewards/{a}", token=token, wallet=a)
        self.assertEqual(10, after["balance"]["total"])
        self.assertEqual(10, after["balance"]["locked"])
        self.assertEqual(("signup", 10), (after["recent"][0]["kind"], after["recent"][0]["skr"]))

    def test_an_unscratched_card_is_never_part_of_a_payout(self):
        from duffleserver import rewards
        k, a = new_wallet()
        self.sign_in(k, a)
        import time
        now = time.time()
        # Matured and settled, but still covered: Claim must not pay it.
        self.svc.store.record_rewards("cover-1", a, "", [("per_payment", 500)], rewards.week_of(now), now - 9 * 86400, now - 2 * 86400)
        self.svc.store.settle_matured(now)
        out = self.svc.store.begin_payout(a, 1, now, "pid-1")
        self.assertEqual({"below": 0}, out)
