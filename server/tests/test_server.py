"""The HTTP surface as it is now: health, sign-in gating, the RPC proxy, push registration."""
from __future__ import annotations

import json
import threading
import unittest
import urllib.error
import urllib.request

from duffleserver import chain
from duffleserver.service import Service, is_address
from duffleserver.store import Store
from duffleserver.web import RateLimit, serve

TO = "2qxPntVgWyxU2jQz2MLcUuCp71D9D1ZSTcbFvCcXWrUc"
PAYER = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
PUSH = "f" * 40


class FakeRpc:
    """Stands in for the provider: records what was forwarded and answers it."""

    def __init__(self):
        self.forwarded = []

    def forward(self, payload):
        self.forwarded.append(payload["method"])
        return {"jsonrpc": "2.0", "id": 1, "result": "ok"}


class FakeFcm:
    enabled = True

    def __init__(self, outcome="ok"):
        self.sent, self.outcome = [], outcome

    def send(self, token, data):
        self.sent.append((token, data))
        return self.outcome


class Http(unittest.TestCase):
    def setUp(self):
        self.rpc = FakeRpc()
        self.svc = Service(Store(), self.rpc, fcm=FakeFcm())
        self.httpd = serve(self.svc, "127.0.0.1", 0, admin_token="owner")
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        from tests.authutil import session_for
        self.tokens = {a: session_for(self.svc, a) for a in (TO, PAYER)}

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def call(self, path, body=None, as_=TO, headers=None, raw=None):
        hdr = {"Content-Type": "application/json", **(headers or {})}
        if as_:
            hdr["Authorization"] = f"Bearer {self.tokens[as_]}"
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(self.base + path, data, hdr)
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            with e:
                return e.code, json.loads(e.read())


class HealthTest(Http):
    def test_health_says_what_is_switched_on_and_nothing_secret(self):
        code, body = self.call("/healthz", as_=None)
        self.assertEqual(200, code)
        self.assertEqual({"ok", "push", "recharges", "small_recharges", "support_mail"}, set(body))

    def test_removed_features_are_gone(self):
        for path in ("/v1/requests", "/v1/rewards/claim", "/v1/offers", "/v1/sponsor/submit",
                     "/v1/shop/order", "/v1/taps", "/v1/messages", "/v1/helius", "/v1/handles"):
            self.assertEqual(404, self.call(path, {"x": 1})[0], path)


class GatingTest(Http):
    def test_everything_but_number_detection_needs_a_session(self):
        for path in ("/v1/rpc", "/v1/devices", "/v1/topup/orders"):
            self.assertEqual(401, self.call(path, {"x": 1}, as_=None)[0], path)

    def test_a_forged_token_is_not_a_session(self):
        code, _ = self.call("/v1/devices", {"push_token": PUSH}, as_=None,
                            headers={"Authorization": "Bearer ap1_" + "a" * 43})
        self.assertEqual(401, code)

    def test_admin_stats_need_the_admin_token(self):
        self.assertEqual(403, self.call("/v1/admin/stats", as_=None)[0])
        code, body = self.call("/v1/admin/stats", as_=None, headers={"Authorization": "Bearer owner"})
        self.assertEqual(200, code)
        self.assertIn("recharges", body)


class InputTest(Http):
    def test_bad_json_is_400_and_says_nothing_internal(self):
        code, body = self.call("/v1/devices", raw=b"{nope")
        self.assertEqual((400, {"error": "bad request"}), (code, body))

    def test_a_json_list_is_refused(self):
        self.assertEqual(400, self.call("/v1/devices", [1, 2])[0])

    def test_deep_nesting_is_400_not_a_dead_thread(self):
        self.assertEqual(400, self.call("/v1/devices", raw=b"[" * 50_000 + b"]" * 50_000)[0])

    def test_an_oversized_body_is_refused(self):
        self.assertEqual(400, self.call("/v1/devices", raw=b'{"a":"' + b"x" * 70_000 + b'"}')[0])


class RpcProxyTest(Http):
    def test_only_what_a_payment_needs_is_forwarded(self):
        for m in ("getProgramAccounts", "requestAirdrop", "getSignaturesForAddress", "getTransaction"):
            self.assertEqual(400, self.call("/v1/rpc", {"method": m, "params": []})[0], m)
        self.assertEqual([], self.rpc.forwarded)

    def test_the_payment_methods_go_through(self):
        for m in ("getLatestBlockhash", "getBalance", "getAccountInfo", "getTokenAccountBalance", "sendTransaction"):
            self.assertEqual(200, self.call("/v1/rpc", {"method": m, "params": []})[0], m)
        self.assertEqual(5, len(self.rpc.forwarded))

    def test_the_real_client_refuses_unlisted_methods_too(self):
        with self.assertRaises(ValueError):
            chain.Rpc("http://127.0.0.1:9").forward({"method": "getProgramAccounts"})


class PushTest(Http):
    def test_a_device_is_registered_for_the_signed_in_wallet_only(self):
        self.assertEqual(200, self.call("/v1/devices", {"push_token": PUSH, "recipient": PAYER})[0])
        self.assertEqual([PUSH], self.svc.store.devices(TO))
        self.assertEqual([], self.svc.store.devices(PAYER))

    def test_a_junk_token_is_refused(self):
        self.assertEqual(400, self.call("/v1/devices", {"push_token": "x"})[0])

    def test_a_recharge_update_reaches_the_phone(self):
        self.svc.add_device(TO, PUSH)
        self.assertEqual(1, self.svc._deliver(TO, {"type": "topup", "id": "cu_1", "status": "delivered"}))
        self.assertEqual(PUSH, self.svc.fcm.sent[0][0])

    def test_a_dead_token_is_forgotten(self):
        self.svc.fcm = FakeFcm("gone")
        self.svc.add_device(TO, PUSH)
        self.assertEqual(0, self.svc._deliver(TO, {"type": "topup"}))
        self.assertEqual([], self.svc.store.devices(TO))


class RateLimitTest(unittest.TestCase):
    def test_a_burst_is_cut_off(self):
        rl = RateLimit(per_minute=5)
        self.assertEqual([True] * 5 + [False], [rl.allow("1.2.3.4") for _ in range(6)])
        self.assertTrue(rl.allow("5.6.7.8"))

    def test_a_forged_forwarded_header_does_not_pick_the_bucket(self):
        import os
        from duffleserver.web import make_handler
        os.environ["TRUST_PROXY"] = "1"
        try:
            h = make_handler(Service(Store(), FakeRpc()))
            fake = h.__new__(h)
            fake.client_address = ("10.0.0.1", 1)
            fake.headers = {"X-Forwarded-For": "6.6.6.6, 203.0.113.9"}
            self.assertEqual("203.0.113.9", fake._ip())
            fake.headers = {"CF-Connecting-IP": "198.51.100.7", "X-Forwarded-For": "6.6.6.6"}
            self.assertEqual("198.51.100.7", fake._ip())
        finally:
            del os.environ["TRUST_PROXY"]


class CleanupTest(unittest.TestCase):
    def test_tables_of_removed_features_are_dropped_and_the_rest_kept(self):
        import os, sqlite3, tempfile
        from duffleserver.store import RETIRED_TABLES
        path = os.path.join(tempfile.mkdtemp(), "old.db")
        db = sqlite3.connect(path)
        for t in RETIRED_TABLES:
            db.execute(f"CREATE TABLE {t} (x)")
        db.execute("CREATE TABLE topups (id TEXT)")
        db.execute("INSERT INTO topups VALUES ('cu_keep')")
        db.commit()
        db.close()
        store = Store(path)
        names = {r[0] for r in store._db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertFalse(names & set(RETIRED_TABLES))
        self.assertIn("topups", names)
        self.assertEqual(1, store._db.execute("SELECT COUNT(*) FROM topups").fetchone()[0])


class AddressTest(unittest.TestCase):
    def test_shape(self):
        self.assertTrue(is_address(TO))
        for bad in ("", "0" * 44, "x", None, 5, TO + "1"):
            self.assertFalse(is_address(bad), bad)


if __name__ == "__main__":
    unittest.main()
