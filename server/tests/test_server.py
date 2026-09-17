"""
Offline tests for the Tap server. The RPC is faked with the REAL devnet payment the Android
tests also use, so the Kotlin and Python verifiers are held to the same transaction.
"""
from __future__ import annotations

import copy
import json
import pathlib
import threading
import time
import unittest
import urllib.error
import urllib.request

from tapserver import chain, verify
from tapserver.helius import AddressSync, signatures_in, webhook_body
from tapserver.service import Service, next_check_after
from tapserver.store import Conflict, Store
from tapserver.web import serve

FIX = pathlib.Path(__file__).parent / "fixtures"
TX = json.loads((FIX / "devnet_tap_payment.json").read_text())["result"]
SIG = "5gN5R1etzG8tUbZGqJdG5aS47gmRbGEJ4cSb9nCrnZiqt63ySjwSNNnUUcqbENQYYvfRuBfdkLQe3RPGeY9RXS5o"
REF = "GwQqYKapoH4mS1M4G8NFvLBWcWNQTpoJBGBDyKi92KrU"
TO = "2qxPntVgWyxU2jQz2MLcUuCp71D9D1ZSTcbFvCcXWrUc"
PAYER = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"


class FakeRpc:
    def __init__(self, txs=None, by_address=None, offline=False):
        self.txs = txs if txs is not None else {SIG: TX}
        self.by_address = by_address if by_address is not None else {REF: [SIG]}
        self.offline = offline
        self.calls = 0

    def signatures(self, address):
        self.calls += 1
        if self.offline:
            raise chain.Offline("no network")
        return list(self.by_address.get(address, []))

    def transaction(self, sig):
        self.calls += 1
        if self.offline:
            raise chain.Offline("no network")
        return self.txs.get(sig)


class FakeFcm:
    enabled = True

    def __init__(self, outcome="ok"):
        self.sent, self.outcome = [], outcome

    def send(self, token, data):
        self.sent.append((token, data))
        return self.outcome


def wait_for(pred, timeout=3.0):
    end = time.time() + timeout
    while time.time() < end:
        if pred():
            return True
        time.sleep(0.02)
    return pred()


# --------------------------------------------------------------------------- verification

class VerifyTest(unittest.TestCase):
    def test_real_payment_is_paid_in_full(self):
        self.assertEqual("paid", verify.verify_transfer(TX, REF, TO, 10_000_000).kind)

    def test_one_lamport_more_than_arrived_is_not_paid(self):
        v = verify.verify_transfer(TX, REF, TO, 10_000_001)
        self.assertEqual(("rejected", "paid 10000000 of 10000001 lamports"), (v.kind, v.why))

    def test_reference_attached_to_a_payment_to_someone_else_is_not_paid(self):
        self.assertEqual("rejected", verify.verify_transfer(TX, REF, PAYER, 1).kind)

    def test_wrong_reference_is_not_paid(self):
        self.assertEqual("rejected", verify.verify_transfer(TX, "8eYAaFEyuwxKwpa9qSbCTWj8VStg5MeAE9GPMyyMTfSn", TO, 1).kind)

    def test_failed_transaction_is_never_paid(self):
        failed = copy.deepcopy(TX)
        failed["meta"]["err"] = {"InstructionError": [2, "Custom"]}
        self.assertEqual("rejected", verify.verify_transfer(failed, REF, TO, 1).kind)

    def test_unserved_transaction_is_pending(self):
        self.assertEqual("pending", verify.verify_transfer(None, REF, TO, 1).kind)

    def test_lookup_table_addresses_count(self):
        v0 = copy.deepcopy(TX)
        keys = v0["transaction"]["message"]["accountKeys"]
        i = keys.index(TO)
        keys.pop(i)
        pre, post = v0["meta"]["preBalances"], v0["meta"]["postBalances"]
        pre.append(pre.pop(i)); post.append(post.pop(i))
        v0["meta"]["loadedAddresses"] = {"writable": [TO], "readonly": []}
        self.assertEqual("paid", verify.verify_transfer(v0, REF, TO, 10_000_000).kind)

    def test_lamports_are_exact(self):
        # Same table as the Kotlin test. 0.00013 * 1e9 is 129999 through a float.
        for amount, want in [("0.00013", 130_000), ("0.00207", 2_070_000), ("0.01", 10_000_000),
                             ("1", 1_000_000_000), ("0.000000001", 1)]:
            self.assertEqual(want, verify.lamports(amount), amount)
        self.assertEqual(129_999, int(0.00013 * 1e9), "the float path really is wrong")
        for bad in ["0.0000000001", "0", "", "abc", "-1", "NaN", "Infinity"]:
            self.assertIsNone(verify.lamports(bad), bad)


# ---------------------------------------------------------------------------------- store

class StoreTest(unittest.TestCase):
    def test_first_registration_wins(self):
        s = Store()
        s.register(REF, TO, "0.01", 10_000_000, "Seeker")
        s.register(REF, TO, "0.01", 10_000_000, "Seeker")  # identical repeat is fine
        with self.assertRaises(Conflict):
            s.register(REF, PAYER, "0.01", 10_000_000, "attacker")
        with self.assertRaises(Conflict):
            s.register(REF, TO, "0.001", 1_000_000, "cheaper")
        self.assertEqual(TO, s.get(REF)["recipient"])

    def test_one_signature_settles_one_request(self):
        s = Store()
        other = "8eYAaFEyuwxKwpa9qSbCTWj8VStg5MeAE9GPMyyMTfSn"
        s.register(REF, TO, "0.01", 10_000_000, "")
        s.register(other, TO, "0.01", 10_000_000, "")
        self.assertTrue(s.mark_paid(REF, SIG))
        self.assertFalse(s.mark_paid(other, SIG))
        self.assertEqual("open", s.get(other)["status"])

    def test_repeat_registration_nudges_an_immediate_check(self):
        s = Store()
        s.register(REF, TO, "0.01", 10_000_000, "")
        s.schedule(REF, time.time() + 3600)
        self.assertEqual([], s.due(time.time()))
        s.register(REF, TO, "0.01", 10_000_000, "")
        self.assertEqual([REF], [r["reference"] for r in s.due(time.time())])


# -------------------------------------------------------------------------------- service

class ServiceTest(unittest.TestCase):
    def make(self, rpc=None, fcm=None):
        return Service(Store(), rpc or FakeRpc(), fcm=fcm)

    def test_nothing_expires(self):
        self.assertEqual(1.0, next_check_after(5))
        self.assertEqual(3600.0, next_check_after(30 * 86400))  # a month old: still watched

    def test_registered_request_is_found_paid_by_the_watcher(self):
        svc = self.make()
        q = svc.hub.subscribe(TO)
        svc.register(REF, TO, "0.01", "Seeker")
        self.assertEqual(1, svc.sweep())
        self.assertTrue(wait_for(lambda: svc.store.get(REF)["status"] == "paid"))
        self.assertEqual({"type": "paid", "reference": REF, "signature": SIG, "amount": "0.01"}, q.get(timeout=1))

    def test_underpaid_request_stays_open(self):
        svc = self.make()
        svc.register(REF, TO, "0.02", "")
        self.assertEqual("short", svc.check_reference(REF))
        self.assertEqual("open", svc.store.get(REF)["status"])

    def test_offline_is_unknown_not_unpaid(self):
        svc = self.make(FakeRpc(offline=True))
        svc.register(REF, TO, "0.01", "")
        self.assertEqual("unknown", svc.check_reference(REF))
        self.assertEqual("open", svc.store.get(REF)["status"])

    def test_invalid_registrations_are_refused(self):
        svc = self.make()
        for args in [("nope", TO, "0.01"), (REF, "nope", "0.01"), (REF, TO, "0.0000000001"), (REF, TO, "abc")]:
            with self.assertRaises(ValueError, msg=str(args)):
                svc.register(*args)

    def test_webhook_is_a_hint_verified_against_our_own_rpc(self):
        svc = self.make()
        svc.register(REF, TO, "0.01", "")
        self.assertEqual([REF], svc.check_signature(SIG))

    def test_forged_webhook_cannot_mark_a_request_paid(self):
        # The webhook names a signature, but OUR rpc shows it paid less than asked.
        svc = self.make()
        svc.register(REF, TO, "0.05", "")
        self.assertEqual(1, svc.on_webhook([{"signature": SIG}]))
        time.sleep(0.3)
        self.assertEqual("open", svc.store.get(REF)["status"])

    def test_duplicate_webhook_delivery_is_ignored(self):
        svc = self.make()
        self.assertEqual(1, svc.on_webhook([{"signature": SIG}]))
        self.assertEqual(0, svc.on_webhook([{"transaction": {"signatures": [SIG]}}]))

    def test_push_is_sent_and_dead_tokens_are_dropped(self):
        fcm = FakeFcm(outcome="gone")
        svc = self.make(fcm=fcm)
        svc.add_device(TO, "t" * 40)
        svc.register(REF, TO, "0.01", "")
        self.assertEqual("paid", svc.check_reference(REF))
        self.assertEqual("paid", fcm.sent[0][1]["type"])
        self.assertEqual([], svc.store.devices(TO))


class DeliveryTest(unittest.TestCase):
    """Requests and introductions that travel through the server after the phones part."""

    def test_a_request_addressed_to_a_payer_reaches_them_live_and_by_push(self):
        fcm = FakeFcm()
        svc = Service(Store(), FakeRpc(), fcm=fcm)
        svc.add_device(PAYER, "p" * 40)
        q = svc.hub.subscribe(PAYER)
        svc.register(REF, TO, "0.01", "Seeker", payer=PAYER)
        event = q.get(timeout=1)
        self.assertEqual(("request", REF, TO, "0.01", "Seeker"),
                         (event["type"], event["reference"], event["recipient"], event["amount"], event["label"]))
        self.assertEqual("request", fcm.sent[0][1]["type"])

    def test_a_repeat_registration_does_not_deliver_twice(self):
        svc = Service(Store(), FakeRpc())
        q = svc.hub.subscribe(PAYER)
        svc.register(REF, TO, "0.01", "", payer=PAYER)
        svc.register(REF, TO, "0.01", "")  # the payer's own phone registering it to watch
        q.get(timeout=1)
        self.assertTrue(q.empty())

    def test_requests_to_yourself_or_garbage_payers_are_refused(self):
        svc = Service(Store(), FakeRpc())
        for payer in (TO, "nope"):
            with self.assertRaises(ValueError):
                svc.register(REF, TO, "0.01", "", payer=payer)

    def test_a_tap_is_relayed_when_hello_could_not_cross(self):
        svc = Service(Store(), FakeRpc())
        q = svc.hub.subscribe(TO)
        self.assertEqual(1, svc.relay_tap(TO, PAYER, "CPH2401"))
        self.assertEqual({"type": "tap", "address": PAYER, "label": "CPH2401", "genesis": ""}, q.get(timeout=1))
        with self.assertRaises(ValueError):
            svc.relay_tap(TO, TO, "self")


class ProxyTest(unittest.TestCase):
    """The phones borrow the server's Solana provider; it must not become an open relay."""

    class RecordingRpc(FakeRpc):
        def __init__(self):
            super().__init__()
            self.forwarded = []
        PROXYABLE = chain.Rpc.PROXYABLE
        def forward(self, payload):
            return chain.Rpc.forward(self, payload)

    def test_only_listed_methods_are_forwarded(self):
        rpc = self.RecordingRpc()
        with self.assertRaises(ValueError):
            rpc.forward({"method": "getProgramAccounts", "params": []})
        with self.assertRaises(ValueError):
            rpc.forward({"method": "requestAirdrop", "params": []})

    def test_the_methods_the_app_needs_are_allowed(self):
        for m in ["getLatestBlockhash", "sendTransaction", "getSignaturesForAddress",
                  "getTransaction", "getSignatureStatuses", "getBalance"]:
            self.assertIn(m, chain.Rpc.PROXYABLE, m)


class HeliusTest(unittest.TestCase):
    def test_signatures_are_extracted_from_either_payload_shape(self):
        self.assertEqual([SIG], signatures_in([{"signature": SIG}]))
        self.assertEqual([SIG], signatures_in({"transaction": {"signatures": [SIG]}}))
        self.assertEqual([], signatures_in(["junk", 42, {"signature": "short"}]))

    def test_webhook_body_matches_the_documented_fields(self):
        b = webhook_body("https://tap.example/", [TO], "rawDevnet", "secret")
        self.assertEqual({"webhookURL": "https://tap.example/v1/helius", "transactionTypes": ["ANY"],
                          "accountAddresses": [TO], "webhookType": "rawDevnet", "authHeader": "secret"}, b)

    def test_sync_is_off_without_configuration(self):
        self.assertFalse(AddressSync(Store(), None, None, None).enabled)


# ------------------------------------------------------------------------------------ http

class HttpTest(unittest.TestCase):
    def setUp(self):
        self.svc = Service(Store(), FakeRpc())
        self.httpd = serve(self.svc, "127.0.0.1", 0, helius_auth="secret")
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def post(self, path, body, headers=None):
        req = urllib.request.Request(self.base + path, json.dumps(body).encode(),
                                     {"Content-Type": "application/json", **(headers or {})})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            with e:
                return e.code, json.loads(e.read())

    def test_register_then_status(self):
        self.assertEqual(200, self.post("/v1/requests", {"reference": REF, "recipient": TO, "amount": "0.01"})[0])
        with urllib.request.urlopen(f"{self.base}/v1/requests/{REF}", timeout=5) as r:
            self.assertEqual("open", json.loads(r.read())["status"])
        code, body = self.post("/v1/requests", {"reference": REF, "recipient": PAYER, "amount": "0.01"})
        self.assertEqual(409, code)

    def test_bad_input_is_400_not_500(self):
        self.assertEqual(400, self.post("/v1/requests", {"reference": "x"})[0])

    def test_webhook_without_the_secret_is_403(self):
        # 403 specifically: Helius retries every other 4xx.
        self.assertEqual(403, self.post("/v1/helius", [{"signature": SIG}])[0])
        self.assertEqual(200, self.post("/v1/helius", [{"signature": SIG}], {"Authorization": "secret"})[0])

    def test_sse_delivers_the_paid_event_end_to_end(self):
        self.post("/v1/requests", {"reference": REF, "recipient": TO, "amount": "0.01"})
        stream = urllib.request.urlopen(f"{self.base}/v1/events?recipient={TO}", timeout=5)
        self.assertEqual(b": connected\n", stream.readline())
        stream.readline()
        self.assertTrue(wait_for(lambda: self.svc.hub.count() == 1))
        # A signed webhook arrives; the server verifies via its RPC and rings the stream.
        self.post("/v1/helius", [{"signature": SIG}], {"Authorization": "secret"})
        lines = [stream.readline(), stream.readline()]
        stream.close()
        self.assertEqual(b"event: paid\n", lines[0])
        self.assertEqual({"reference": REF, "signature": SIG, "amount": "0.01"},
                         json.loads(lines[1].decode().removeprefix("data: ")))

    def test_a_request_made_while_the_payer_was_offline_is_replayed_on_connect(self):
        self.post("/v1/requests", {"reference": REF, "recipient": TO, "amount": "0.01", "label": "Seeker", "payer": PAYER})
        stream = urllib.request.urlopen(f"{self.base}/v1/events?recipient={PAYER}", timeout=5)
        self.assertEqual(b": connected\n", stream.readline())
        stream.readline()
        kind, data = stream.readline(), stream.readline()
        stream.close()
        self.assertEqual(b"event: request\n", kind)
        self.assertEqual(REF, json.loads(data.decode().removeprefix("data: "))["reference"])

    def test_rpc_proxy_refuses_unlisted_methods(self):
        code, body = self.post("/v1/rpc", {"method": "getProgramAccounts", "params": []})
        self.assertEqual(400, code)
        self.assertIn("not allowed", body["error"])

    def test_tap_relay_endpoint(self):
        code, body = self.post("/v1/taps", {"to": TO, "address": PAYER, "label": "CPH2401"})
        self.assertEqual((200, {"live": 0}), (code, body))
        self.assertEqual(400, self.post("/v1/taps", {"to": TO, "address": "nope"})[0])

    def test_sse_rejects_a_bad_address(self):
        try:
            urllib.request.urlopen(f"{self.base}/v1/events?recipient=nope", timeout=5)
            self.fail("expected 400")
        except urllib.error.HTTPError as e:
            with e:
                self.assertEqual(400, e.code)


if __name__ == "__main__":
    unittest.main()
