"""
Claiming SKR, attacked. The promise: a reward is paid once or not at all — never twice, never
lost — whatever the payer or the network does.
"""
from __future__ import annotations

import threading
import time
import unittest

from tests.revealutil import scratch_all
from duffleserver.payer_client import PayerError
from duffleserver.service import Service
from duffleserver.store import Store
from tests.test_server import FakeRpc

ADDR = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
OTHER = "b7aXLq81gY1SSdRwn1jUCyQeB282GJwrzBVCsJ3nFK2"
SIG = "5" * 88


class FakePayer:
    enabled = True

    def __init__(self):
        self.calls: list[tuple[str, str, int]] = []
        self.script: list = []        # each entry: a dict result or an exception to raise
        self.default = {"state": "confirmed", "signature": SIG}

    def pay(self, pid, to, units):
        self.calls.append((pid, to, units))
        r = self.script.pop(0) if self.script else self.default
        if isinstance(r, Exception):
            raise r
        return {"id": pid, "to": to, "amount": units, **r}


def settled(svc, address, skr, ref, kind="per_payment"):
    now = time.time()
    svc.store.record_rewards(ref, address, OTHER, [(kind, skr)], 1, now - 9 * 86400, now - 2 * 86400)
    svc.store.settle_matured(now)
    scratch_all(svc.store)   # a person has opened the card; an unopened one is neither shown nor paid


class ClaimTest(unittest.TestCase):
    def setUp(self):
        self.payer = FakePayer()
        self.svc = Service(Store(), FakeRpc(), payer=self.payer)
        self.svc.set_reward_config({"lock_days": 7, "min_payout": 100})
        self.events = []
        self.svc.hub.publish = lambda to, ev: self.events.append((to, ev)) or 1

    def test_a_claim_pays_settled_rewards_once_and_marks_them_paid(self):
        settled(self.svc, ADDR, 250, "r1", "first_payment")
        settled(self.svc, ADDR, 50, "r2")
        out = self.svc.claim_rewards(ADDR)
        self.assertEqual(("confirmed", 300), (out["payout"]["state"], out["payout"]["skr"]))
        self.assertEqual([(out["payout"]["id"], ADDR, 300 * 10 ** 6)], self.payer.calls)
        bal = self.svc.store.reward_balance(ADDR, time.time())
        self.assertEqual((0, 0, 300), (bal["claimable"], bal["claiming"], bal["paid"]))
        self.assertEqual("payout", self.events[-1][1]["type"])

    def test_only_settled_rewards_are_claimable(self):
        now = time.time()
        self.svc.store.record_rewards("locked1", ADDR, OTHER, [("per_payment", 500)], 1, now, now + 86400)
        self.svc.store.record_rewards("rev1", ADDR, OTHER, [("per_payment", 500)], 1, now - 9 * 86400, now - 2 * 86400)
        self.svc.store.reverse_rewards("rev1", "test")
        settled(self.svc, ADDR, 120, "ok1")
        out = self.svc.claim_rewards(ADDR)
        self.assertEqual(120, out["payout"]["skr"])

    def test_under_the_minimum_changes_nothing(self):
        settled(self.svc, ADDR, 40, "r1")
        out = self.svc.claim_rewards(ADDR)
        self.assertEqual(("below_minimum", 40, 100), (out["refused"], out["have"], out["need"]))
        self.assertEqual([], self.payer.calls)
        self.assertEqual(40, self.svc.store.reward_balance(ADDR, time.time())["claimable"])

    def test_nothing_to_claim_is_not_an_error(self):
        out = self.svc.claim_rewards(ADDR)
        self.assertFalse(out["ok"])
        self.assertEqual([], self.payer.calls)

    def test_pressing_claim_again_never_starts_a_second_payout(self):
        self.payer.default = {"state": "sending", "signature": SIG}
        settled(self.svc, ADDR, 300, "r1")
        a = self.svc.claim_rewards(ADDR)
        b = self.svc.claim_rewards(ADDR)
        self.assertEqual(a["payout"]["id"], b["payout"]["id"])
        self.assertEqual({a["payout"]["id"]}, {c[0] for c in self.payer.calls})
        self.assertEqual(300, sum(r["skr"] for r in self.svc.store._db.execute(
            "SELECT skr FROM payouts").fetchall()))

    def test_many_simultaneous_claims_make_one_payout(self):
        settled(self.svc, ADDR, 300, "r1")
        ts = [threading.Thread(target=self.svc.claim_rewards, args=(ADDR,)) for _ in range(16)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        self.assertEqual(1, self.svc.store._db.execute("SELECT COUNT(*) FROM payouts").fetchone()[0])
        self.assertEqual({self.svc.store._db.execute("SELECT id FROM payouts").fetchone()[0]},
                         {c[0] for c in self.payer.calls})

    def test_a_reward_cannot_belong_to_two_payouts(self):
        settled(self.svc, ADDR, 300, "r1")
        self.payer.default = {"state": "sending", "signature": SIG}
        self.svc.claim_rewards(ADDR)
        rows = self.svc.store._db.execute("SELECT payout_id, COUNT(*) c FROM rewards GROUP BY payout_id").fetchall()
        self.assertEqual(1, len(rows))

    def test_the_payer_being_down_keeps_the_rewards_reserved_then_pays_on_retry(self):
        settled(self.svc, ADDR, 300, "r1")
        self.payer.script = [PayerError("payer_unreachable", retryable=True)] * 3
        out = self.svc.claim_rewards(ADDR)
        self.assertEqual("created", out["payout"]["state"])
        bal = self.svc.store.reward_balance(ADDR, time.time())
        self.assertEqual((0, 300, 0), (bal["claimable"], bal["claiming"], bal["paid"]))
        self.svc.settle_payouts(); self.svc.settle_payouts()   # still down
        self.assertEqual(300, self.svc.store.reward_balance(ADDR, time.time())["claiming"])
        self.svc.settle_payouts()                               # back up
        bal = self.svc.store.reward_balance(ADDR, time.time())
        self.assertEqual((0, 0, 300), (bal["claimable"], bal["claiming"], bal["paid"]))
        # Every attempt used the SAME id: the payer saw one payout, asked about four times.
        self.assertEqual(1, len({c[0] for c in self.payer.calls}))

    def test_a_payout_still_confirming_finishes_on_a_later_pass(self):
        settled(self.svc, ADDR, 300, "r1")
        self.payer.script = [{"state": "sending", "signature": SIG}, {"state": "sending", "signature": SIG}]
        self.assertEqual("sent", self.svc.claim_rewards(ADDR)["payout"]["state"])
        self.svc.settle_payouts()
        self.assertEqual(0, self.svc.store.reward_balance(ADDR, time.time())["paid"])
        self.svc.settle_payouts()
        self.assertEqual(300, self.svc.store.reward_balance(ADDR, time.time())["paid"])

    def test_a_deliberate_refusal_releases_the_rewards_untouched(self):
        settled(self.svc, ADDR, 300, "r1")
        self.payer.script = [PayerError("over_daily_cap")]
        out = self.svc.claim_rewards(ADDR)
        self.assertEqual("failed", out["payout"]["state"])
        self.assertEqual(300, self.svc.store.reward_balance(ADDR, time.time())["claimable"])

    def test_a_failed_payout_releases_and_the_retry_is_a_different_payout(self):
        settled(self.svc, ADDR, 300, "r1")
        self.payer.script = [{"state": "failed", "error": "insufficient_balance"}]
        first = self.svc.claim_rewards(ADDR)["payout"]["id"]
        second = self.svc.claim_rewards(ADDR)["payout"]
        self.assertNotEqual(first, second["id"])
        self.assertEqual("confirmed", second["state"])
        self.assertEqual(300, self.svc.store.reward_balance(ADDR, time.time())["paid"])

    def test_a_wrong_shared_secret_never_releases_or_pays(self):
        settled(self.svc, ADDR, 300, "r1")
        self.payer.script = [PayerError("denied")] * 12
        for _ in range(3):
            self.svc.claim_rewards(ADDR); self.svc.settle_payouts()
        bal = self.svc.store.reward_balance(ADDR, time.time())
        self.assertEqual((0, 300, 0), (bal["claimable"], bal["claiming"], bal["paid"]))

    def test_an_id_the_payer_remembers_differently_is_never_released(self):
        settled(self.svc, ADDR, 300, "r1")
        self.payer.script = [PayerError("id_reused")]
        self.svc.claim_rewards(ADDR)
        self.assertEqual(300, self.svc.store.reward_balance(ADDR, time.time())["claiming"])

    def test_without_a_payer_nothing_moves(self):
        svc = Service(Store(), FakeRpc(), payer=type("Off", (), {"enabled": False})())
        settled(svc, ADDR, 300, "r1")
        self.assertEqual("payouts_unavailable", svc.claim_rewards(ADDR)["refused"])
        self.assertEqual(300, svc.store.reward_balance(ADDR, time.time())["claimable"])

    def test_one_wallet_cannot_claim_another_wallets_rewards(self):
        settled(self.svc, ADDR, 300, "r1")
        self.assertFalse(self.svc.claim_rewards(OTHER)["ok"])
        self.assertEqual([], self.payer.calls)

    def test_payouts_go_to_the_wallet_that_earned_them_whatever_else_is_asked(self):
        settled(self.svc, ADDR, 300, "r1")
        self.svc.claim_rewards(ADDR)
        self.assertEqual(ADDR, self.payer.calls[0][1])

    def test_the_summary_shows_each_stage(self):
        settled(self.svc, ADDR, 300, "r1")
        s = self.svc.reward_summary(ADDR)
        self.assertEqual(300, s["balance"]["claimable"])
        self.assertTrue(s["payouts_enabled"])


class HttpClaimTest(unittest.TestCase):
    def test_claim_needs_a_session_and_pays_the_session_owner_only(self):
        import json, urllib.request, urllib.error
        from duffleserver.web import serve
        from tests.authutil import session_for
        payer = FakePayer()
        svc = Service(Store(), FakeRpc(), payer=payer)
        settled(svc, ADDR, 300, "r1")
        httpd = serve(svc, "127.0.0.1", 0)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{httpd.server_address[1]}"

        def post(token, body):
            h = {"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})}
            try:
                with urllib.request.urlopen(urllib.request.Request(base + "/v1/rewards/claim", json.dumps(body).encode(), h), timeout=5) as r:
                    return r.status, json.loads(r.read())
            except urllib.error.HTTPError as e:
                with e:
                    return e.code, json.loads(e.read())
        try:
            self.assertEqual(401, post(None, {})[0])
            # A body that names somebody else's wallet is ignored: the session decides who is paid.
            code, out = post(session_for(svc, ADDR), {"address": OTHER})
            self.assertEqual(200, code)
            self.assertEqual(ADDR, payer.calls[0][1])
            self.assertEqual("confirmed", out["payout"]["state"])
        finally:
            httpd.shutdown(); httpd.server_close()


if __name__ == "__main__":
    unittest.main()
