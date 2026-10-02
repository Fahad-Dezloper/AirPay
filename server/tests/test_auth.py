"""
Sign-in, attacked.

Every test here is a way somebody would try to get a session they are not entitled to, or keep
one they should not have. The happy path is one test; the rest are refusals, because the thing
to prove about an auth layer is what it will not do.
"""
from __future__ import annotations

import base64
import json
import threading
import unittest
import urllib.error
import urllib.request

from nacl.signing import SigningKey

from duffleserver import auth
from duffleserver.auth import Auth, AuthError, RateLimited, b58decode, b58encode
from duffleserver.service import Service
from duffleserver.store import Store
from duffleserver.web import serve
from tests.test_server import FakeRpc


def wallet():
    k = SigningKey.generate()
    return k, b58encode(bytes(k.verify_key))


def sign(key, text: str) -> str:
    return base64.b64encode(key.sign(text.encode()).signature).decode()


class Base58Test(unittest.TestCase):
    def test_round_trips_including_leading_zeros(self):
        for raw in (b"\x00\x00abc", bytes(range(32)), b"\xff" * 32, b"\x00" * 32):
            self.assertEqual(raw, b58decode(b58encode(raw)))

    def test_known_solana_address_is_32_bytes(self):
        self.assertEqual(32, len(b58decode("11111111111111111111111111111111")))
        self.assertEqual(32, len(b58decode("4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D")))

    def test_rejects_characters_outside_the_alphabet(self):
        for bad in ("0OIl", "abc!", "é"):
            with self.assertRaises(ValueError):
                b58decode(bad)


class SignInTest(unittest.TestCase):
    def setUp(self):
        self.store = Store()
        self.a = Auth(self.store)
        self.key, self.addr = wallet()

    def sign_in(self, now=1_000.0):
        c = self.a.challenge(self.addr, now)
        return c, self.a.verify(c["nonce"], sign(self.key, c["message"]), now + 1)

    def test_a_valid_signature_gives_a_session_for_that_address(self):
        _, s = self.sign_in()
        self.assertEqual(self.addr, s["address"])
        self.assertEqual(self.addr, self.a.session(s["token"], 1_002.0))

    def test_the_message_names_the_address_nonce_and_expiry(self):
        c = self.a.challenge(self.addr, 1_000.0)
        for part in (self.addr, c["nonce"], "Chain ID: mainnet", "Expiration Time:"):
            self.assertIn(part, c["message"])

    def test_the_token_is_not_stored_only_its_hash(self):
        _, s = self.sign_in()
        rows = self.store._db.execute("SELECT token_hash FROM sessions").fetchall()
        self.assertEqual(1, len(rows))
        self.assertNotIn(s["token"], rows[0]["token_hash"])
        self.assertEqual(auth.token_hash(s["token"]), rows[0]["token_hash"])

    def test_a_signature_cannot_be_replayed(self):
        c = self.a.challenge(self.addr, 1_000.0)
        sig = sign(self.key, c["message"])
        self.a.verify(c["nonce"], sig, 1_001.0)
        with self.assertRaises(AuthError):
            self.a.verify(c["nonce"], sig, 1_002.0)

    def test_someone_elses_key_does_not_sign_in_as_you(self):
        other, _ = wallet()
        c = self.a.challenge(self.addr, 1_000.0)
        with self.assertRaises(AuthError):
            self.a.verify(c["nonce"], sign(other, c["message"]), 1_001.0)

    def test_signing_a_different_message_is_refused(self):
        c = self.a.challenge(self.addr, 1_000.0)
        with self.assertRaises(AuthError):
            self.a.verify(c["nonce"], sign(self.key, c["message"] + " "), 1_001.0)

    def test_an_expired_challenge_is_refused(self):
        c = self.a.challenge(self.addr, 1_000.0)
        with self.assertRaises(AuthError):
            self.a.verify(c["nonce"], sign(self.key, c["message"]), 1_000.0 + auth.NONCE_TTL + 1)

    def test_a_nonce_that_was_never_issued_is_refused(self):
        with self.assertRaises(AuthError):
            self.a.verify("0" * 32, sign(self.key, "anything"), 1_000.0)

    def test_malformed_signatures_are_refused_not_crashes(self):
        c = self.a.challenge(self.addr, 1_000.0)
        for bad in ("", "not base64!!", base64.b64encode(b"short").decode(), "A" * 5000):
            fresh = self.a.challenge(self.addr, 1_000.0) if False else c
            with self.assertRaises(AuthError):
                self.a.verify(fresh["nonce"], bad, 1_001.0)

    def test_a_challenge_is_burnt_after_a_few_wrong_guesses(self):
        c = self.a.challenge(self.addr, 1_000.0)
        other, _ = wallet()
        for _ in range(auth.MAX_ATTEMPTS):
            with self.assertRaises(AuthError):
                self.a.verify(c["nonce"], sign(other, c["message"]), 1_001.0)
        # Even the right signature no longer works: the challenge cannot be ground down.
        with self.assertRaises(AuthError):
            self.a.verify(c["nonce"], sign(self.key, c["message"]), 1_002.0)

    def test_every_refusal_looks_the_same(self):
        c = self.a.challenge(self.addr, 1_000.0)
        msgs = set()
        for nonce, sig in (("0" * 32, "x"), (c["nonce"], "x"), (c["nonce"], sign(wallet()[0], c["message"]))):
            try:
                self.a.verify(nonce, sig, 1_001.0)
            except AuthError as e:
                msgs.add(str(e))
        self.assertEqual({"denied"}, msgs)

    def test_a_bad_address_gets_no_challenge(self):
        for bad in ("", "nope", "1" * 31, "z" * 60, None):
            with self.assertRaises(AuthError):
                self.a.challenge(bad, 1_000.0)

    def test_challenges_per_address_are_capped_without_locking_anyone_out(self):
        first = self.a.challenge(self.addr, 1_000.0)
        for i in range(auth.MAX_OPEN_NONCES):
            self.a.challenge(self.addr, 1_001.0 + i)
        # Never more than the cap stays open, so the table is bounded...
        self.assertEqual(auth.MAX_OPEN_NONCES, self.store.open_nonces(self.addr, 1_100.0))
        # ...the oldest was the one retired, so it can no longer be signed...
        with self.assertRaises(AuthError):
            self.a.verify(first["nonce"], sign(self.key, first["message"]), 1_100.0)
        # ...and a fresh challenge always works: a wallet is not locked out for five minutes
        # by a few abandoned prompts.
        newest = self.a.challenge(self.addr, 1_200.0)
        self.assertIn("token", self.a.verify(newest["nonce"], sign(self.key, newest["message"]), 1_201.0))

    def test_sessions_expire(self):
        _, s = self.sign_in()
        self.assertIsNone(self.a.session(s["token"], 1_001.0 + auth.SESSION_TTL + 1))

    def test_a_revoked_session_stops_working_at_once(self):
        _, s = self.sign_in()
        self.assertTrue(self.a.revoke(s["token"]))
        self.assertIsNone(self.a.session(s["token"], 1_002.0))

    def test_garbage_tokens_never_authenticate(self):
        for bad in (None, "", "Bearer", "ap1_", "ap1_" + "A" * 300, "x" * 40, 5):
            self.assertIsNone(self.a.session(bad, 1_000.0))

    def test_only_the_newest_sessions_stay_live(self):
        tokens = []
        for i in range(auth.MAX_SESSIONS + 3):
            c = self.a.challenge(self.addr, 1_000.0 + i)
            tokens.append(self.a.verify(c["nonce"], sign(self.key, c["message"]), 1_000.5 + i)["token"])
            self.store._db.execute("DELETE FROM auth_nonces")
        live = [t for t in tokens if self.a.session(t, 1_100.0)]
        self.assertEqual(auth.MAX_SESSIONS, len(live))
        self.assertIsNone(self.a.session(tokens[0], 1_100.0))


class HttpAuthTest(unittest.TestCase):
    """The lock on the doors: every user route refuses a stranger, and refuses a signed-in user
    acting as somebody else."""

    def setUp(self):
        self.svc = Service(Store(), FakeRpc())
        self.httpd = serve(self.svc, "127.0.0.1", 0, admin_token="owner")
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.key, self.me = wallet()
        self.other_key, self.other = wallet()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()

    def call(self, path, body=None, token=None, method=None):
        hdr = {"Content-Type": "application/json"}
        if token:
            hdr["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(self.base + path, json.dumps(body).encode() if body is not None else None, hdr,
                                     method=method)
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            with e:
                return e.code, json.loads(e.read())

    def login(self, key, addr):
        _, c = self.call("/v1/auth/challenge", {"address": addr})
        _, s = self.call("/v1/auth/verify", {"nonce": c["nonce"], "signature": sign(key, c["message"])})
        return s["token"]

    def test_full_sign_in_over_http(self):
        token = self.login(self.key, self.me)
        self.assertEqual(200, self.call(f"/v1/rewards/{self.me}", token=token)[0])

    def test_wrong_key_over_http_is_401_denied(self):
        _, c = self.call("/v1/auth/challenge", {"address": self.me})
        code, body = self.call("/v1/auth/verify", {"nonce": c["nonce"], "signature": sign(self.other_key, c["message"])})
        self.assertEqual((401, {"error": "denied"}), (code, body))

    def test_every_user_route_refuses_a_stranger(self):
        posts = ["/v1/requests", "/v1/rpc", "/v1/rpc/mainnet", "/v1/withdrawals", "/v1/rejections",
                 "/v1/deferrals", "/v1/taps", "/v1/messages", "/v1/rewards/preview", "/v1/rewards/reveal",
                 "/v1/referrals/claim", "/v1/devices", "/v1/shop/invoice", "/v1/shop/order",
                 "/v1/shop/operator", "/v1/offers/some-offer/claim"]
        for p in posts:
            self.assertEqual(401, self.call(p, {"x": 1})[0], p)
        for g in (f"/v1/rewards/{self.me}", f"/v1/referrals/{self.me}", "/v1/messages", "/v1/events"):
            self.assertEqual(401, self.call(g)[0], g)

    def test_reading_somebody_elses_rewards_is_403(self):
        token = self.login(self.key, self.me)
        self.assertEqual(403, self.call(f"/v1/rewards/{self.other}", token=token)[0])
        self.assertEqual(403, self.call(f"/v1/referrals/{self.other}", token=token)[0])

    def test_a_message_cannot_be_sent_as_somebody_else(self):
        token = self.login(self.key, self.me)
        code, row = self.call("/v1/messages", {"from": self.other, "to": self.other, "body": "hi"}, token)
        self.assertEqual(200, code)
        self.assertEqual(self.me, row["from"])

    def test_a_push_token_registers_for_the_caller_only(self):
        token = self.login(self.key, self.me)
        self.call("/v1/devices", {"recipient": self.other, "push_token": "t" * 40}, token)
        self.assertEqual([], self.svc.store.devices(self.other))
        self.assertEqual(["t" * 40], self.svc.store.devices(self.me))

    def test_a_request_cannot_be_registered_for_two_strangers(self):
        token = self.login(self.key, self.me)
        third = wallet()[1]
        code, _ = self.call("/v1/requests", {"reference": third, "recipient": self.other, "payer": third, "amount": "1"}, token)
        self.assertEqual(403, code)

    def test_a_tap_cannot_claim_to_come_from_somebody_else(self):
        token = self.login(self.key, self.me)
        self.assertEqual(403, self.call("/v1/taps", {"to": self.other, "address": self.other}, token)[0])

    def test_a_refund_address_must_be_the_buyers_own(self):
        token = self.login(self.key, self.me)
        self.assertEqual(403, self.call("/v1/shop/invoice", {"product_id": "x", "refund_address": self.other}, token)[0])

    def test_logout_revokes(self):
        token = self.login(self.key, self.me)
        self.assertEqual(200, self.call("/v1/auth/logout", {}, token)[0])
        self.assertEqual(401, self.call(f"/v1/rewards/{self.me}", token=token)[0])

    def test_the_admin_token_is_not_a_user_session_and_vice_versa(self):
        self.assertEqual(401, self.call(f"/v1/rewards/{self.me}", token="owner")[0])
        token = self.login(self.key, self.me)
        self.assertEqual(403, self.call("/v1/rewards/config", {"per_payment": 1}, token)[0])

    def test_sign_in_is_rate_limited(self):
        codes = {self.call("/v1/auth/challenge", {"address": wallet()[1]})[0] for _ in range(40)}
        self.assertIn(429, codes)


if __name__ == "__main__":
    unittest.main()
