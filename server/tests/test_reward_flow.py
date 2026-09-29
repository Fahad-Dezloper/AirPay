"""
Rewards on a payment that really settled.

test_rewards.py holds the rules on their own; this is the wiring around them — that a settled
payment runs them exactly once, that the Seeker check is the server's own and not the phone's
claim, and that an unreachable chain defers instead of refusing.
"""
from __future__ import annotations

import json
import threading
import unittest
import urllib.error
import urllib.request

from duffleserver import rewards
from duffleserver.service import Service
from duffleserver.store import Store
from duffleserver.web import serve
from tests.test_server import FakeRpc

SEEKER = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
FRIEND = "2qxPntVgWyxU2jQz2MLcUuCp71D9D1ZSTcbFvCcXWrUc"
REF1 = "GwQqYKapoH4mS1M4G8NFvLBWcWNQTpoJBGBDyKi92KrU"
REF2 = "3QJmqCeJP7hHB9ipDrVdwCYT8yMYhD4dFoDrK7tHx5eL"
#: A wallet with no history at all — what a throwaway looks like.
OTHER = "8eYAaFEyuwxKwpa9qSbCTWj8VStg5MeAE9GPMyyMTfSn"
SIG = "5gN5R1etzG8tUbZGqJdG5aS47gmRbGEJ4cSb9nCrnZiqt63ySjwSNNnUUcqbENQYYvfRuBfdkLQe3RPGeY9RXS5o"
OLD = 1_000_000_000.0  # a wallet with years of history


class FakeFacts:
    """What the chain would say, without asking it."""

    def __init__(self, seekers=(SEEKER,), ages=None, unreachable=False):
        self.seekers = set(seekers)
        #: OTHER is deliberately absent: a wallet the chain has never seen.
        self.ages = {FRIEND: OLD} if ages is None else ages
        self.unreachable = unreachable

    def is_seeker(self, owner):
        return None if self.unreachable else owner in self.seekers

    def first_seen(self, address):
        return None if self.unreachable else self.ages.get(address)


class FlowTest(unittest.TestCase):
    def setUp(self, facts=None):
        self.store = Store()
        self.svc = Service(self.store, FakeRpc(), facts=facts or FakeFacts())

    def pay(self, reference=REF1, payer=SEEKER, recipient=FRIEND, amount="0.1", origin="tap"):
        """Register a request the way a phone does, then settle it the way the watcher does.
        One signature settles one request, so each payment carries its own."""
        self.svc.register(reference, recipient, amount, payer=payer, origin=origin)
        self.assertTrue(self.svc._settle(self.store.get(reference), SIG[:-4] + reference[:4]))
        return reference

    def rewards_of(self, payer=SEEKER):
        return self.store.rewards_for(payer)

    def test_a_settled_tap_earns_the_first_payment_bonus(self):
        self.pay()
        got = self.rewards_of()
        self.assertEqual([("first_payment", 250), ("per_payment", 50)],
                         sorted((r["kind"], r["skr"]) for r in got))

    def test_the_same_payment_checked_twice_pays_once(self):
        ref = self.pay()
        # A webhook and the sweep can both find the same signature.
        self.svc.reward_for_payment(self.store.get(ref))
        self.assertEqual(2, len(self.rewards_of()))

    def test_the_phones_word_is_not_enough_to_be_a_seeker(self):
        # The payer says nothing about Genesis here; the server asks the chain itself, and this
        # wallet does not hold one.
        self.setUp(FakeFacts(seekers=()))
        self.pay()
        self.assertEqual([], self.rewards_of())
        self.assertEqual(rewards.NOT_A_SEEKER, self.store.reward_attempts()[0]["refused"])

    def test_an_unreachable_chain_defers_rather_than_refusing(self):
        # A reward missed because mainnet was down must be retryable, so the reason has to be
        # distinguishable from "you are not a Seeker".
        self.setUp(FakeFacts(unreachable=True))
        self.pay()
        self.assertEqual([], self.rewards_of())
        self.assertEqual(rewards.CHAIN_UNREACHABLE, self.store.reward_attempts()[0]["refused"])

    def test_a_payment_that_did_not_come_from_a_tap_earns_nothing(self):
        self.pay(origin="")
        self.assertEqual([], self.rewards_of())
        self.assertEqual(rewards.NOT_A_TAP, self.store.reward_attempts()[0]["refused"])

    def test_a_request_nobody_claimed_has_nobody_to_pay(self):
        self.svc.register(REF1, FRIEND, "0.1", origin="tap")
        self.svc._settle(self.store.get(REF1), SIG)
        self.assertEqual(rewards.NO_PAYER, self.store.reward_attempts()[0]["refused"])

    def test_paying_the_same_person_again_inside_a_month_earns_nothing(self):
        # The check in R1's plan: one reward row for the first tap, none for the second.
        self.pay()
        self.pay(REF2)
        self.assertEqual(2, len(self.rewards_of()))
        self.assertEqual(rewards.COUNTERPARTY_COOLDOWN, self.store.reward_attempts()[-1]["refused"])

    def test_a_reward_reaches_the_payers_phone(self):
        sent = []
        self.svc.hub.publish = lambda addr, event: sent.append((addr, event))
        self.pay()
        earned = [e for a, e in sent if a == SEEKER and e["type"] == "reward"]
        self.assertEqual(1, len(earned))
        self.assertEqual(300, earned[0]["skr"])

    def test_the_summary_is_what_the_rewards_screen_shows(self):
        self.pay()
        s = self.svc.reward_summary(SEEKER)
        self.assertEqual(300, s["balance"]["total"])
        self.assertEqual(300, s["balance"]["locked"], "rewards are reversible for a week")
        self.assertEqual(1, s["this_week"]["rewarded_payments"])
        self.assertEqual(300, s["this_month"]["earned"])
        self.assertEqual(self.svc.reward_config().weekly_pool - 300, s["pool_left"])
        self.assertEqual(2, len(s["recent"]))

    # -- scratch cards ----------------------------------------------------------------

    def test_a_new_reward_has_not_been_looked_at(self):
        self.pay()
        self.assertTrue(all(r["revealed_at"] is None for r in self.rewards_of()),
                        "a freshly earned reward is a card nobody has scratched yet")

    def test_revealing_records_when_it_was_seen(self):
        self.pay()
        row = self.rewards_of()[0]
        out = self.svc.reveal_reward(SEEKER, row["id"])
        self.assertTrue(out["ok"])
        after = [r for r in self.rewards_of() if r["id"] == row["id"]][0]
        self.assertEqual(out["revealed_at"], after["revealed_at"])

    def test_revealing_twice_keeps_the_first_time(self):
        # Scratching the same card again — on this phone or another one — must not move the
        # time forward, or "new since you last looked" would never settle.
        self.pay()
        row = self.rewards_of()[0]
        first = self.svc.reveal_reward(SEEKER, row["id"])["revealed_at"]
        self.assertEqual(first, self.svc.reveal_reward(SEEKER, row["id"])["revealed_at"])

    def test_you_cannot_scratch_somebody_elses_card(self):
        # The id comes from a phone, so it is checked against the owner rather than trusted.
        self.pay()
        row = self.rewards_of()[0]
        self.assertEqual({"ok": False, "refused": "unknown_reward"},
                         self.svc.reveal_reward(FRIEND, row["id"]))
        self.assertIsNone(self.rewards_of()[0]["revealed_at"], "and it stays covered")

    def test_an_unknown_card_is_refused_rather_than_invented(self):
        self.assertFalse(self.svc.reveal_reward(SEEKER, 9999)["ok"])

    def test_scratching_does_not_change_what_was_earned(self):
        """The load-bearing property. The card is theatre over a decision already made.

        A reward whose amount depended on opening a screen would be a lottery, and this layer's
        only defence against farming is that every reward is a fixed consequence of a rule.
        """
        self.pay()
        before = self.svc.reward_summary(SEEKER)
        for r in self.rewards_of():
            self.svc.reveal_reward(SEEKER, r["id"])
        after = self.svc.reward_summary(SEEKER)
        self.assertEqual(before["balance"], after["balance"])
        self.assertEqual(before["pool_left"], after["pool_left"])
        self.assertEqual([(r["kind"], r["skr"]) for r in before["recent"]],
                         [(r["kind"], r["skr"]) for r in after["recent"]])

    def test_the_review_screen_is_told_before_they_pay_not_after(self):
        # The whole point of the preview: a new wallet is visible on the review screen.
        fresh = self.svc.reward_preview(SEEKER, OTHER, "SOL", 100_000_000)
        self.assertEqual(rewards.NEW_COUNTERPARTY, fresh["refused"])
        good = self.svc.reward_preview(SEEKER, FRIEND, "SOL", 100_000_000)
        self.assertEqual(300, good["skr"])
        self.assertIsNone(good["refused"])

    def test_a_preview_is_not_a_payment(self):
        self.svc.reward_preview(SEEKER, FRIEND, "SOL", 100_000_000)
        self.assertEqual([], self.rewards_of())
        self.assertEqual([], self.store.reward_attempts())
        # And having previewed does not use up the first-payment bonus.
        self.pay()
        self.assertEqual(300, sum(r["skr"] for r in self.rewards_of()))

    def test_the_preview_and_the_real_decision_agree(self):
        before = self.svc.reward_preview(SEEKER, FRIEND, "SOL", 100_000_000)
        self.pay()
        self.assertEqual(before["skr"], sum(r["skr"] for r in self.rewards_of()))

    def test_the_summary_carries_the_refusals_too(self):
        # The rewards screen shows these next to the earnings: a screen listing only what was
        # paid makes the rules look arbitrary the first time one refuses.
        self.pay()
        self.setUp(FakeFacts())  # a fresh store so the cooldown is not what refuses
        self.pay(REF2, recipient=OTHER)
        s = self.svc.reward_summary(SEEKER)
        self.assertEqual([], s["recent"])
        self.assertEqual([rewards.NEW_COUNTERPARTY], [r["refused"] for r in s["refused"]])
        self.assertEqual(OTHER, s["refused"][0]["counterparty"])

    def test_a_payment_that_earned_is_not_listed_as_a_refusal(self):
        self.pay()
        self.assertEqual([], self.svc.reward_summary(SEEKER)["refused"])

    def test_the_summary_states_the_rates_so_the_app_need_not_hardcode_them(self):
        self.svc.set_reward_config({"per_payment": 75})
        self.assertEqual(75, self.svc.reward_summary(SEEKER)["rates"]["per_payment"])

    def test_someone_who_never_earned_gets_an_empty_summary_not_an_error(self):
        s = self.svc.reward_summary(FRIEND)
        self.assertEqual(0, s["balance"]["total"])
        self.assertEqual([], s["recent"])


class EndpointTest(unittest.TestCase):
    """What the app actually reads: the amounts, and one person's earnings."""

    def setUp(self):
        self.svc = Service(Store(), FakeRpc(), facts=FakeFacts())
        self.httpd = serve(self.svc, "127.0.0.1", 0, admin_token="owner")
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def call(self, path, body=None, headers=None):
        req = urllib.request.Request(self.base + path,
                                     json.dumps(body).encode() if body is not None else None,
                                     {"Content-Type": "application/json", **(headers or {})})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_the_amounts_are_public_because_the_app_shows_them(self):
        status, body = self.call("/v1/rewards/config")
        self.assertEqual(200, status)
        self.assertEqual(rewards.Config().per_payment, body["per_payment"])

    def test_changing_the_amounts_needs_the_owners_token(self):
        self.assertEqual(403, self.call("/v1/rewards/config", {"per_payment": 5})[0])
        status, body = self.call("/v1/rewards/config", {"per_payment": 5},
                                 {"Authorization": "Bearer owner"})
        self.assertEqual(200, status)
        self.assertEqual({"per_payment": 5}, body["changed"])
        self.assertEqual(5, self.call("/v1/rewards/config")[1]["per_payment"])

    def test_a_wallet_can_read_what_it_has_earned(self):
        self.svc.register(REF1, FRIEND, "0.1", payer=SEEKER, origin="tap")
        self.svc._settle(self.svc.store.get(REF1), SIG)
        status, body = self.call(f"/v1/rewards/{SEEKER}")
        self.assertEqual(200, status)
        self.assertEqual(300, body["balance"]["total"])

    def test_a_path_that_is_not_an_address_is_not_a_reward_query(self):
        self.assertEqual(404, self.call("/v1/rewards/nope")[0])


if __name__ == "__main__":
    unittest.main()
