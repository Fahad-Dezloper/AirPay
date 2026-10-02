"""
Offline tests for the Duffle server. The RPC is faked with the REAL devnet payment the Android
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

from duffleserver import chain, verify
from duffleserver.helius import AddressSync, signatures_in, webhook_body
from duffleserver.service import Service, next_check_after
from duffleserver.store import Conflict, Store
from duffleserver.web import serve

FIX = pathlib.Path(__file__).parent / "fixtures"
TX = json.loads((FIX / "devnet_tap_payment.json").read_text())["result"]
SIG = "5gN5R1etzG8tUbZGqJdG5aS47gmRbGEJ4cSb9nCrnZiqt63ySjwSNNnUUcqbENQYYvfRuBfdkLQe3RPGeY9RXS5o"
REF = "GwQqYKapoH4mS1M4G8NFvLBWcWNQTpoJBGBDyKi92KrU"
TO = "2qxPntVgWyxU2jQz2MLcUuCp71D9D1ZSTcbFvCcXWrUc"
PAYER = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
USDC = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"


class FakeRpc:
    def __init__(self, txs=None, by_address=None, offline=False):
        self.txs = txs if txs is not None else {SIG: TX}
        self.by_address = by_address if by_address is not None else {REF: [SIG]}
        self.offline = offline
        self.calls = 0
        self.mints = {USDC: 6}

    def mint_decimals(self, mint):
        if self.offline:
            raise chain.Offline("no network")
        return self.mints.get(mint)

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
        self.assertEqual({"type": "paid", "reference": REF, "signature": SIG, "amount": "0.01", "symbol": "SOL"}, q.get(timeout=1))

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

    def test_the_paying_phone_is_not_rung_with_its_own_request(self):
        """The payer registers the request itself before paying, so the server is watching and
        knows who to tell if it is withdrawn. Delivering it back rang the payer's own phone with
        its own request — "Seeker requests 1 USDC" on the Seeker, while it paid the OnePlus."""
        fcm = FakeFcm()
        svc = Service(Store(), FakeRpc(), fcm=fcm)
        svc.add_device(PAYER, "p" * 40)
        q = svc.hub.subscribe(PAYER)
        svc.register(REF, TO, "0.01", "OnePlus", payer=PAYER, deliver=False)
        self.assertTrue(q.empty(), "the payer already has this request — it made it")
        self.assertEqual([], fcm.sent)
        # It is still recorded as theirs, which is what the withdrawal and the reward need.
        self.assertEqual(PAYER, svc.store.get(REF)["payer"])

    def test_a_relayed_request_still_reaches_the_payer(self):
        # The other half: "Request instead" after the phones parted has nobody but the server
        # to carry it, and must keep working.
        svc = Service(Store(), FakeRpc())
        q = svc.hub.subscribe(PAYER)
        svc.register(REF, TO, "0.01", "OnePlus", payer=PAYER)
        self.assertEqual("request", q.get(timeout=1)["type"])

    def test_later_tells_the_asker_without_answering_them(self):
        """"Later" is not a no. The asker stops wondering, and the request stays payable."""
        fcm = FakeFcm()
        svc = Service(Store(), FakeRpc(), fcm=fcm)
        svc.add_device(TO, "t" * 40)
        q = svc.hub.subscribe(TO)
        svc.register(REF, TO, "0.01", "Seeker")
        self.assertEqual(1, svc.defer(REF, "CPH2401"))
        event = q.get(timeout=1)
        self.assertEqual(("deferred", REF, "CPH2401", "0.01"),
                         (event["type"], event["reference"], event["label"], event["amount"]))
        self.assertEqual("deferred", fcm.sent[0][1]["type"])
        # Still open, still watched, still payable — that is the whole difference from a decline.
        self.assertEqual("open", svc.store.get(REF)["status"])
        self.assertEqual("paid", svc.check_reference(REF))

    def test_later_on_an_already_paid_request_tells_nobody(self):
        svc = Service(Store(), FakeRpc())
        svc.register(REF, TO, "0.01", "")
        svc.check_reference(REF)
        self.assertEqual(0, svc.defer(REF, "CPH2401"))

    def test_later_on_a_reference_nobody_registered_is_an_error(self):
        svc = Service(Store(), FakeRpc())
        with self.assertRaises(KeyError):
            svc.defer(REF, "CPH2401")

    def test_requests_to_yourself_or_garbage_payers_are_refused(self):
        svc = Service(Store(), FakeRpc())
        for payer in (TO, "nope"):
            with self.assertRaises(ValueError):
                svc.register(REF, TO, "0.01", "", payer=payer)

    def test_withdrawing_tells_the_payer_but_still_watches_for_a_late_payment(self):
        svc = Service(Store(), FakeRpc())
        q = svc.hub.subscribe(PAYER)
        svc.register(REF, TO, "0.01", "Seeker", payer=PAYER)
        q.get(timeout=1)  # the request itself
        self.assertEqual(1, svc.withdraw(REF))
        self.assertEqual({"type": "withdrawn", "reference": REF, "label": "Seeker"}, q.get(timeout=1))
        # Withdrawing is "stop asking", not "refuse money": a payment landing a second later
        # must still be recognised, because the payer may already be inside their wallet.
        self.assertEqual("paid", svc.check_reference(REF))
        self.assertEqual("paid", svc.store.get(REF)["status"])

    def test_the_paying_phone_can_fill_in_a_payer_the_asker_never_knew(self):
        """A request made by tapping is registered by the phone asking for money, which cannot
        know who will pay. Without the paying phone filling that in, a withdrawal reaches nobody
        — which is exactly what happened on real hardware."""
        svc = Service(Store(), FakeRpc())
        svc.register(REF, TO, "0.01", "Seeker")                    # the asker, no payer known
        self.assertIsNone(svc.store.get(REF)["payer"])
        svc.register(REF, TO, "0.01", "Seeker", payer=PAYER)       # the payer, registering to watch
        self.assertEqual(PAYER, svc.store.get(REF)["payer"])
        q = svc.hub.subscribe(PAYER)
        self.assertEqual(1, svc.withdraw(REF))
        self.assertEqual("withdrawn", q.get(timeout=1)["type"])

    def test_a_known_payer_is_never_overwritten(self):
        svc = Service(Store(), FakeRpc())
        svc.register(REF, TO, "0.01", "", payer=PAYER)
        svc.register(REF, TO, "0.01", "", payer="8eYAaFEyuwxKwpa9qSbCTWj8VStg5MeAE9GPMyyMTfSn")
        self.assertEqual(PAYER, svc.store.get(REF)["payer"])

    def test_rejecting_tells_the_asker_and_keeps_watching(self):
        svc = Service(Store(), FakeRpc())
        q = svc.hub.subscribe(TO)
        svc.register(REF, TO, "0.01", "Seeker", payer=PAYER)
        self.assertEqual(1, svc.reject(REF, "CPH2401"))
        self.assertEqual({"type": "rejected", "reference": REF, "label": "CPH2401"}, q.get(timeout=1))
        self.assertTrue(svc.store.get(REF)["rejected"])
        # Declining is not a refusal of money already on its way.
        self.assertEqual("paid", svc.check_reference(REF))

    def test_rejecting_something_unknown_is_an_error(self):
        svc = Service(Store(), FakeRpc())
        with self.assertRaises(KeyError):
            svc.reject(REF, "x")

    def test_withdrawing_something_unknown_is_an_error_not_a_silent_ok(self):
        svc = Service(Store(), FakeRpc())
        with self.assertRaises(KeyError):
            svc.withdraw(REF)
        with self.assertRaises(ValueError):
            svc.withdraw("nope")

    def test_a_tap_is_relayed_when_hello_could_not_cross(self):
        svc = Service(Store(), FakeRpc())
        q = svc.hub.subscribe(TO)
        self.assertEqual(1, svc.relay_tap(TO, PAYER, "CPH2401"))
        self.assertEqual({"type": "tap", "address": PAYER, "label": "CPH2401", "genesis": "", "brand": ""}, q.get(timeout=1))
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
        from tests.authutil import session_for
        self.tokens = {a: session_for(self.svc, a) for a in (TO, PAYER)}

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def stream(self, who):
        """An event stream opened as `who`; the address comes from the session, not the URL."""
        return urllib.request.urlopen(urllib.request.Request(
            f"{self.base}/v1/events", headers={"Authorization": f"Bearer {self.tokens[who]}"}), timeout=5)

    def post(self, path, body, headers=None, as_=TO):
        hdr = {"Authorization": f"Bearer {self.tokens[as_]}"} if as_ else {}
        req = urllib.request.Request(self.base + path, json.dumps(body).encode(),
                                     {"Content-Type": "application/json", **hdr, **(headers or {})})
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
        code, body = self.post("/v1/requests", {"reference": REF, "recipient": PAYER, "amount": "0.01"}, as_=PAYER)
        self.assertEqual(409, code)

    def test_bad_input_is_400_not_500(self):
        self.assertEqual(400, self.post("/v1/requests", {"reference": "x", "recipient": TO})[0])

    def test_webhook_without_the_secret_is_403(self):
        # 403 specifically: Helius retries every other 4xx.
        self.assertEqual(403, self.post("/v1/helius", [{"signature": SIG}])[0])
        self.assertEqual(200, self.post("/v1/helius", [{"signature": SIG}], {"Authorization": "secret"})[0])

    def test_sse_delivers_the_paid_event_end_to_end(self):
        self.post("/v1/requests", {"reference": REF, "recipient": TO, "amount": "0.01"})
        stream = self.stream(TO)
        self.assertEqual(b": connected\n", stream.readline())
        stream.readline()
        self.assertTrue(wait_for(lambda: self.svc.hub.count() == 1))
        # A signed webhook arrives; the server verifies via its RPC and rings the stream.
        self.post("/v1/helius", [{"signature": SIG}], {"Authorization": "secret"})
        lines = [stream.readline(), stream.readline()]
        stream.close()
        self.assertEqual(b"event: paid\n", lines[0])
        self.assertEqual({"reference": REF, "signature": SIG, "amount": "0.01", "symbol": "SOL"},
                         json.loads(lines[1].decode().removeprefix("data: ")))

    def test_a_request_made_while_the_payer_was_offline_is_replayed_on_connect(self):
        self.post("/v1/requests", {"reference": REF, "recipient": TO, "amount": "0.01", "label": "Seeker", "payer": PAYER})
        stream = self.stream(PAYER)
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

    def test_withdrawal_endpoint(self):
        self.post("/v1/requests", {"reference": REF, "recipient": TO, "amount": "0.01", "payer": PAYER})
        self.assertEqual((200, {"told": 0}), self.post("/v1/withdrawals", {"reference": REF}))
        self.assertEqual(404, self.post("/v1/withdrawals", {"reference": PAYER})[0])

    def test_rejection_endpoint(self):
        self.post("/v1/requests", {"reference": REF, "recipient": TO, "amount": "0.01", "payer": PAYER})
        self.assertEqual((200, {"told": 0}), self.post("/v1/rejections", {"reference": REF, "label": "CPH2401"}, as_=PAYER))
        self.assertEqual(404, self.post("/v1/rejections", {"reference": PAYER, "label": "x"}, as_=PAYER)[0])

    def test_tap_relay_endpoint(self):
        code, body = self.post("/v1/taps", {"to": TO, "address": PAYER, "label": "CPH2401"}, as_=PAYER)
        self.assertEqual((200, {"live": 0}), (code, body))
        self.assertEqual(400, self.post("/v1/taps", {"to": "nope", "address": PAYER}, as_=PAYER)[0])

    def test_sse_needs_a_session(self):
        try:
            urllib.request.urlopen(f"{self.base}/v1/events?recipient={TO}", timeout=5)
            self.fail("expected 401")
        except urllib.error.HTTPError as e:
            with e:
                self.assertEqual(401, e.code)


# ---------------------------------------------------------------------------------- tokens

def token_tx(pre, post, owner=TO, mint=USDC, err=None, reference=REF):
    """A token payment as getTransaction reports it. The recipient's wallet key is absent on
    purpose: tokens land in their token account, and the check must not need the wallet."""
    bal = lambda n: {"accountIndex": 1, "mint": mint, "owner": owner, "uiTokenAmount": {"amount": str(n), "decimals": 6}}
    return {
        "meta": {"err": err, "preBalances": [1, 2, 3], "postBalances": [1, 2, 3],
                 "preTokenBalances": [] if pre is None else [bal(pre)], "postTokenBalances": [bal(post)]},
        "transaction": {"message": {"accountKeys": [PAYER, "7dQhNaFiV579YSWeCczUgqQFzRB6YgUn8coqan8f4n7L", reference]}},
    }


class TokenTest(unittest.TestCase):
    def test_amounts_convert_in_the_tokens_decimals(self):
        self.assertEqual(500_000, verify.units("0.5", 6))
        self.assertEqual(7, verify.units("7", 0))
        self.assertIsNone(verify.units("0.0000001", 6))
        self.assertIsNone(verify.units("1", 40))

    def test_a_token_payment_in_full_is_paid(self):
        self.assertEqual("paid", verify.verify_transfer(token_tx(1_000_000, 1_500_000), REF, TO, 500_000, USDC).kind)

    def test_a_first_ever_token_payment_is_paid(self):
        self.assertEqual("paid", verify.verify_transfer(token_tx(None, 500_000), REF, TO, 500_000, USDC).kind)

    def test_short_wrong_token_wrong_owner_failed_and_wrong_reference_are_not_paid(self):
        cases = {
            "short": token_tx(0, 499_999),
            "other mint": token_tx(0, 500_000, mint="So11111111111111111111111111111111111111112"),
            "other owner": token_tx(0, 500_000, owner=PAYER),
            "failed": token_tx(0, 500_000, err={"InstructionError": [0, "Custom"]}),
            "other reference": token_tx(0, 500_000, reference="11111111111111111111111111111112"),
        }
        for name, tx in cases.items():
            self.assertEqual("rejected", verify.verify_transfer(tx, REF, TO, 500_000, USDC).kind, name)

    def test_tokens_dont_satisfy_a_sol_request(self):
        self.assertEqual("rejected", verify.verify_transfer(token_tx(0, 500_000_000), REF, TO, 500_000).kind)

    def test_a_token_request_is_watched_and_settled_by_token_balance(self):
        sig = "3" * 88
        svc = Service(Store(), FakeRpc(txs={sig: token_tx(0, 2_500_000)}, by_address={REF: [sig]}))
        svc.register(REF, TO, "2.5", "Shop", token=USDC, decimals=6, symbol="USDC")
        row = svc.store.get(REF)
        self.assertEqual((USDC, 6, "USDC", 2_500_000), (row["token"], row["decimals"], row["symbol"], row["lamports"]))
        self.assertEqual("paid", svc.check_reference(REF))

    def test_the_same_amount_in_sol_does_not_settle_a_token_request(self):
        svc = Service(Store(), FakeRpc())  # the real devnet SOL payment of 0.01 under REF
        svc.register(REF, TO, "0.01", "", token=USDC, decimals=6, symbol="USDC")
        self.assertNotEqual("paid", svc.check_reference(REF))

    def test_decimals_that_dont_match_the_mint_are_refused(self):
        svc = Service(Store(), FakeRpc())
        with self.assertRaises(ValueError):
            svc.register(REF, TO, "1", "", token=USDC, decimals=9, symbol="USDC")

    def test_a_token_that_is_not_a_mint_is_refused(self):
        svc = Service(Store(), FakeRpc())
        with self.assertRaises(ValueError):
            svc.register(REF, TO, "1", "", token=PAYER, decimals=6, symbol="X")
        with self.assertRaises(ValueError):
            svc.register(REF, TO, "1", "", token="not-an-address", decimals=6, symbol="X")

    def test_a_reference_cannot_be_reregistered_as_a_different_token(self):
        svc = Service(Store(), FakeRpc())
        svc.register(REF, TO, "0.01", "")
        with self.assertRaises(Conflict):
            svc.register(REF, TO, "0.01", "", token=USDC, decimals=6, symbol="USDC")

    def test_the_payer_is_told_which_token_is_asked_for(self):
        fcm = FakeFcm()
        svc = Service(Store(), FakeRpc(), fcm=fcm)
        svc.add_device(PAYER, "p" * 40)
        svc.register(REF, TO, "2.5", "Shop", payer=PAYER, token=USDC, decimals=6, symbol="USDC")
        data = fcm.sent[0][1]
        self.assertEqual((USDC, 6, "USDC"), (data["token"], data["decimals"], data["symbol"]))

    def test_a_sol_request_ignores_whatever_decimals_the_phone_sent(self):
        svc = Service(Store(), FakeRpc())
        svc.register(REF, TO, "0.01", "", decimals=2, symbol="FAKE")
        row = svc.store.get(REF)
        self.assertEqual(("SOL", 9, "SOL", 10_000_000), (row["token"], row["decimals"], row["symbol"], row["lamports"]))

    def test_token_reads_are_proxied_for_the_phones(self):
        for m in ("getTokenAccountsByOwner", "getAccountInfo", "getTokenAccountBalance"):
            self.assertIn(m, chain.Rpc.PROXYABLE)


class FallbackTest(unittest.TestCase):
    """A revoked provider key answers 401 to everything; the server must carry on without it."""

    def _endpoint(self, status, doc=None):
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", 0)))
                body = json.dumps(doc or {}).encode()
                self.send_response(status)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.shutdown)
        return f"http://127.0.0.1:{srv.server_address[1]}"

    def test_a_refused_provider_falls_back_to_the_public_endpoint(self):
        bad = self._endpoint(401)
        good = self._endpoint(200, {"jsonrpc": "2.0", "id": 1, "result": {"value": 42}})
        rpc = chain.Rpc(bad, timeout=3, fallback=good)
        self.assertEqual({"value": 42}, rpc.call("getBalance", [PAYER]))
        self.assertEqual({"value": 42}, rpc.forward({"method": "getBalance", "params": [PAYER]})["result"])

    def test_both_down_is_offline_not_unpaid(self):
        bad = self._endpoint(401)
        with self.assertRaises(chain.Offline):
            chain.Rpc(bad, timeout=3, fallback=bad).call("getBalance", [PAYER])


class RealUsdcPaymentTest(unittest.TestCase):
    """The first token payment Duffle made — 1 USDC, Seeker to OnePlus — as it landed."""
    TX = json.loads((FIX / "devnet_usdc_payment.json").read_text())["result"]
    REF = "8sRMPp6pyRP3Lq6nxmQ719CMAZXud68x1W3QmTecme38"

    def test_paid_in_full(self):
        self.assertEqual("paid", verify.verify_transfer(self.TX, self.REF, TO, 1_000_000, USDC).kind)

    def test_not_more_not_sol_not_another_token(self):
        self.assertEqual("rejected", verify.verify_transfer(self.TX, self.REF, TO, 1_000_001, USDC).kind)
        self.assertEqual("rejected", verify.verify_transfer(self.TX, self.REF, TO, 1_000_000).kind)
        self.assertEqual("rejected", verify.verify_transfer(
            self.TX, self.REF, TO, 1_000_000, "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr").kind)


class BrandRelayTest(unittest.TestCase):
    def test_a_relayed_tap_carries_the_phone_maker(self):
        svc = Service(Store(), FakeRpc())
        q = svc.hub.subscribe(TO)
        svc.relay_tap(TO, PAYER, "Seeker", brand="seeker")
        self.assertEqual("seeker", q.get(timeout=1)["brand"])

    def test_anything_but_a_short_key_is_dropped(self):
        svc = Service(Store(), FakeRpc())
        q = svc.hub.subscribe(TO)
        svc.relay_tap(TO, PAYER, "x", brand="<b>OnePlus</b>")
        self.assertEqual("", q.get(timeout=1)["brand"])


if __name__ == "__main__":
    unittest.main()
