"""
The payer, attacked. The promise under test: one payout id moves money at most once, whatever
breaks — timeouts, crashes, double calls, a slow chain, a hostile caller.
"""
from __future__ import annotations

import json
import os
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

from nacl.signing import SigningKey

from skrpayer import solana as sol
from skrpayer.engine import CONFIRMED, CREATED, FAILED, SENDING, Engine, Limits, Refused
from skrpayer.server import serve, sign

MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
ALICE = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
BOB = "b7aXLq81gY1SSdRwn1jUCyQeB282GJwrzBVCsJ3nFK2"
SKR = 10 ** 6


class FakeRpc:
    """A chain that can be told to misbehave."""

    def __init__(self, balance=10_000 * SKR):
        self.balance = balance
        self.sent: list[bytes] = []
        self.statuses: dict[str, dict | None] = {}
        self.send_error: Exception | None = None
        self.valid: bool | None = True
        self.n = 0

    def mint_info(self, mint):
        return {"owner": sol.TOKEN_PROGRAM, "decimals": 6, "extensions": False, "supply": "1"}

    def token_balance(self, account):
        return self.balance

    def blockhash(self):
        self.n += 1
        return sol.b58encode(bytes([self.n]) * 32), 100 + self.n

    def send(self, tx):
        self.sent.append(tx)
        if self.send_error:
            raise self.send_error
        return "x"

    def status(self, signature):
        return self.statuses.get(signature)

    def blockhash_valid(self, blockhash):
        return self.valid


def engine(tmp, rpc=None, **lim):
    limits = Limits(lim.get("per_payout", 1000 * SKR), lim.get("per_day", 10_000 * SKR),
                    lim.get("per_recipient_day", 1500 * SKR))
    return Engine(os.path.join(tmp, "p.db"), SigningKey.generate(), rpc or FakeRpc(), MINT, limits,
                  pause_file=os.path.join(tmp, "PAUSED"))


class SolanaTest(unittest.TestCase):
    def test_token_account_matches_the_one_the_chain_has(self):
        # Pinned from mainnet: this wallet's real SKR account (found on-chain with 456.9 SKR).
        self.assertEqual("4NhnHGeASs3r9oae5uofhLAUKWGLcGyb1DqTJb97dTzq", sol.ata(ALICE, MINT, sol.TOKEN_PROGRAM))

    def test_a_valid_public_key_is_on_the_curve_and_a_program_address_is_not(self):
        self.assertTrue(sol.on_curve(sol.b58decode(ALICE)))
        self.assertFalse(sol.on_curve(sol.b58decode(sol.ata(ALICE, MINT, sol.TOKEN_PROGRAM))))

    def test_compact_u16(self):
        self.assertEqual(b"\x00", sol.compact_u16(0))
        self.assertEqual(b"\x7f", sol.compact_u16(127))
        self.assertEqual(b"\x80\x01", sol.compact_u16(128))

    def test_message_refuses_nonsense(self):
        bh = sol.b58encode(b"\x01" * 32)
        for to, amt in ((ALICE, 1), (BOB, 0), (BOB, -5), (BOB, 2 ** 64)):
            with self.assertRaises(ValueError):
                sol.transfer_message(ALICE, to, MINT, 6, amt, bh, sol.TOKEN_PROGRAM)


class PayoutTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.rpc = FakeRpc()
        self.e = engine(self.tmp.name, self.rpc)

    def tearDown(self):
        self.tmp.cleanup()

    def land(self, p):
        self.rpc.statuses[p["signature"]] = {"err": None, "confirmationStatus": "confirmed"}

    def test_a_payout_goes_out_once_and_confirms(self):
        p = self.e.request("reward-0001", BOB, 50 * SKR)
        self.assertEqual(SENDING, p["state"])
        self.assertEqual(1, len(self.rpc.sent))
        self.land(p)
        self.assertEqual(CONFIRMED, self.e.advance("reward-0001")["state"])

    def test_asking_again_never_pays_again(self):
        for _ in range(5):
            self.e.request("reward-0001", BOB, 50 * SKR)
        self.assertEqual(1, len(self.rpc.sent))

    def test_the_same_id_with_a_different_payout_is_refused(self):
        self.e.request("reward-0001", BOB, 50 * SKR)
        for to, amt in ((ALICE, 50 * SKR), (BOB, 51 * SKR)):
            with self.assertRaises(Refused) as c:
                self.e.request("reward-0001", to, amt)
            self.assertEqual("id_reused", c.exception.code)
        self.assertEqual(1, len(self.rpc.sent))

    def test_two_threads_asking_at_once_pay_once(self):
        out = []
        ts = [threading.Thread(target=lambda: out.append(self.e.request("reward-race1", BOB, 5 * SKR))) for _ in range(12)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        self.assertEqual(1, len(self.rpc.sent))

    def test_a_timeout_on_send_stays_pending_and_is_not_resent(self):
        self.rpc.send_error = sol.RpcError("unreachable: timed out")
        p = self.e.request("reward-0001", BOB, 5 * SKR)
        self.assertEqual(SENDING, p["state"])
        for _ in range(4):
            self.e.advance("reward-0001")
        self.assertEqual(1, len(self.rpc.sent))
        # It had actually landed. We find out by asking, and never paid twice.
        self.land(p)
        self.assertEqual(CONFIRMED, self.e.advance("reward-0001")["state"])
        self.assertEqual(1, len(self.rpc.sent))

    def test_a_transaction_that_landed_late_is_not_resigned(self):
        p = self.e.request("reward-0001", BOB, 5 * SKR)
        self.rpc.valid = False                      # blockhash looks expired…
        self.land(p)                                # …but it did land after all
        self.assertEqual(CONFIRMED, self.e.advance("reward-0001")["state"])
        self.assertEqual(1, len(self.rpc.sent))

    def test_unknown_blockhash_validity_never_triggers_a_resend(self):
        self.e.request("reward-0001", BOB, 5 * SKR)
        self.rpc.valid = None
        for _ in range(5):
            self.e.advance("reward-0001")
        self.assertEqual(1, len(self.rpc.sent))

    def test_only_a_provably_expired_unlanded_payout_is_resigned(self):
        p = self.e.request("reward-0001", BOB, 5 * SKR)
        first = p["signature"]
        self.rpc.valid = False                       # expired, never landed
        p = self.e.advance("reward-0001")            # back to created
        self.assertEqual(CREATED, p["state"])
        self.rpc.valid = True
        p = self.e.advance("reward-0001")            # re-signed with a fresh blockhash
        self.assertNotEqual(first, p["signature"])
        self.assertEqual(2, len(self.rpc.sent))

    def test_a_failed_transaction_is_final_and_does_not_retry(self):
        p = self.e.request("reward-0001", BOB, 5 * SKR)
        self.rpc.statuses[p["signature"]] = {"err": {"InstructionError": [1, "x"]}}
        self.assertEqual(FAILED, self.e.advance("reward-0001")["state"])
        self.e.advance("reward-0001")
        self.assertEqual(1, len(self.rpc.sent))

    def test_a_node_that_rejects_it_outright_fails_without_ambiguity(self):
        err = sol.RpcError("simulation failed")
        err.data = {"code": -32002}
        self.rpc.send_error = err
        self.assertEqual(FAILED, self.e.request("reward-0001", BOB, 5 * SKR)["state"])

    def test_not_enough_balance_fails_before_anything_is_signed(self):
        self.rpc.balance = 1 * SKR
        p = self.e.request("reward-0001", BOB, 5 * SKR)
        self.assertEqual((FAILED, "insufficient_balance"), (p["state"], p["error"]))
        self.assertEqual(0, len(self.rpc.sent))

    def test_a_crash_after_signing_resumes_without_a_second_payment(self):
        p = self.e.request("reward-0001", BOB, 5 * SKR)
        key = self.e.key
        self.e._db.close()
        reborn = Engine(os.path.join(self.tmp.name, "p.db"), key, self.rpc, MINT,
                        Limits(1000 * SKR, 10_000 * SKR, 1500 * SKR))
        self.land(p)
        self.assertEqual(CONFIRMED, reborn.advance("reward-0001")["state"])
        self.assertEqual(1, len(self.rpc.sent))


class LimitTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.rpc = FakeRpc()
        self.e = engine(self.tmp.name, self.rpc, per_payout=100 * SKR, per_day=250 * SKR, per_recipient_day=150 * SKR)

    def tearDown(self):
        self.tmp.cleanup()

    def refused(self, *a):
        with self.assertRaises(Refused) as c:
            self.e.request(*a)
        return c.exception.code

    def test_over_the_single_payout_cap(self):
        self.assertEqual("over_payout_cap", self.refused("reward-0001", BOB, 101 * SKR))

    def test_over_the_daily_cap_across_everyone(self):
        carol = sol.b58encode(SigningKey.generate().verify_key.encode())
        dave = sol.b58encode(SigningKey.generate().verify_key.encode())
        self.e.request("reward-0001", BOB, 100 * SKR)
        self.e.request("reward-0002", carol, 100 * SKR)
        self.assertEqual("over_daily_cap", self.refused("reward-0003", dave, 100 * SKR))

    def test_over_the_per_recipient_cap(self):
        self.e.request("reward-0001", BOB, 100 * SKR)
        self.assertEqual("over_recipient_cap", self.refused("reward-0002", BOB, 100 * SKR))

    def test_failed_payouts_do_not_count_against_the_caps(self):
        self.rpc.balance = 0
        self.e.request("reward-0001", BOB, 100 * SKR)           # fails: nothing left
        self.rpc.balance = 10_000 * SKR
        self.e.request("reward-0002", BOB, 100 * SKR)           # still allowed

    def test_the_window_is_a_rolling_day(self):
        self.e.request("reward-0001", BOB, 100 * SKR, now=time.time() - 90_000)
        self.e.request("reward-0002", BOB, 100 * SKR)

    def test_bad_input_is_refused_not_crashed(self):
        for args in (("short", BOB, SKR), ("reward-0001", "nope", SKR), ("reward-0001", BOB, 0),
                     ("reward-0001", BOB, -1), ("reward-0001", BOB, 1.5), ("reward-0001", BOB, True),
                     ("reward-0001", BOB, "5"), ("bad id!!!", BOB, SKR), ("reward-0001", self.e.pubkey, SKR),
                     (None, BOB, SKR)):
            with self.assertRaises(Refused):
                self.e.request(*args)
        self.assertEqual(0, len(self.rpc.sent))

    def test_pause_stops_new_payouts_and_holds_unsent_ones(self):
        self.e.pause(True)
        self.assertEqual("paused", self.refused("reward-0001", BOB, SKR))
        self.e.pause(False)
        self.e.request("reward-0002", BOB, SKR)
        self.assertEqual(1, len(self.rpc.sent))

    def test_a_mint_with_extensions_is_refused_at_startup(self):
        class Ext(FakeRpc):
            def mint_info(self, m):
                return {"owner": sol.TOKEN_PROGRAM, "decimals": 6, "extensions": True, "supply": "1"}
        with self.assertRaises(RuntimeError):
            engine(self.tmp.name, Ext())


class HttpTest(unittest.TestCase):
    SECRET = b"s" * 40

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.rpc = FakeRpc()
        self.e = engine(self.tmp.name, self.rpc)
        self.httpd = serve(self.e, self.SECRET, "127.0.0.1", 0)
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.tmp.cleanup()

    def call(self, method, path, body=None, secret=None, ts=None, sig=None, tamper=None):
        raw = json.dumps(body).encode() if body is not None else b""
        ts = ts or str(time.time())
        sig = sig or sign(secret or self.SECRET, method, path, ts, raw)
        req = urllib.request.Request(self.base + path, tamper if tamper is not None else raw,
                                     {"X-Payer-Time": ts, "X-Payer-Sig": sig, "Content-Type": "application/json"},
                                     method=method)
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            with e:
                return e.code, json.loads(e.read())

    def test_a_signed_request_pays(self):
        code, out = self.call("POST", "/pay", {"id": "reward-0001", "to": BOB, "amount": 5 * SKR})
        self.assertEqual((200, "sending"), (code, out["state"]))

    def test_no_signature_wrong_secret_stale_time_and_tampered_body_are_all_401(self):
        body = {"id": "reward-0001", "to": BOB, "amount": 5 * SKR}
        self.assertEqual(401, self.call("POST", "/pay", body, sig="0" * 64)[0])
        self.assertEqual(401, self.call("POST", "/pay", body, secret=b"w" * 40)[0])
        self.assertEqual(401, self.call("POST", "/pay", body, ts=str(time.time() - 3600))[0])
        self.assertEqual(401, self.call("POST", "/pay", body, ts="not-a-time")[0])
        # Signed for 5 SKR, body swapped for 500 in flight:
        evil = json.dumps({"id": "reward-0001", "to": BOB, "amount": 500 * SKR}).encode()
        self.assertEqual(401, self.call("POST", "/pay", body, tamper=evil)[0])
        self.assertEqual(0, len(self.rpc.sent))

    def test_a_signature_for_one_path_does_not_work_on_another(self):
        ts = str(time.time())
        sig = sign(self.SECRET, "GET", "/status", ts, b"")
        self.assertEqual(401, self.call("POST", "/pause", {}, ts=ts, sig=sig)[0])

    def test_a_replayed_request_cannot_pay_twice(self):
        body = {"id": "reward-0001", "to": BOB, "amount": 5 * SKR}
        ts = str(time.time())
        sig = sign(self.SECRET, "POST", "/pay", ts, json.dumps(body).encode())
        for _ in range(4):
            self.assertEqual(200, self.call("POST", "/pay", body, ts=ts, sig=sig)[0])
        self.assertEqual(1, len(self.rpc.sent))

    def test_refusals_carry_a_stable_code(self):
        code, out = self.call("POST", "/pay", {"id": "reward-0001", "to": BOB, "amount": 10_000_000 * SKR})
        self.assertEqual((422, "over_payout_cap"), (code, out["error"]))

    def test_oversized_and_malformed_bodies_are_400(self):
        self.assertEqual(400, self.call("POST", "/pay", tamper=b"x" * 10_000)[0])
        self.assertEqual(401, self.call("POST", "/pay", tamper=b"{", sig="0" * 64)[0])

    def test_status_and_pause_need_the_secret_too(self):
        self.assertEqual(401, self.call("GET", "/status", secret=b"w" * 40)[0])
        code, out = self.call("GET", "/status")
        self.assertEqual(200, code)
        self.assertEqual(self.e.pubkey, out["public_key"])
        self.assertEqual(200, self.call("POST", "/pause", {})[0])
        self.assertTrue(self.e.paused)

    def test_health_is_open_and_says_nothing(self):
        with urllib.request.urlopen(self.base + "/healthz", timeout=5) as r:
            self.assertEqual({"ok": True}, json.loads(r.read()))


if __name__ == "__main__":
    unittest.main()
