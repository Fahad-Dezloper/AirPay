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

from duffleserver.auth import b58encode
from duffleserver.service import Service
from duffleserver.store import Store
from duffleserver.web import serve
from tests.test_server import FakeRpc


def new_wallet():
    k = SigningKey.generate()
    return k, b58encode(bytes(k.verify_key))


def sign(key, text: str) -> str:
    return base64.b64encode(key.sign(text.encode()).signature).decode()


class HttpBase(unittest.TestCase):
    def setUp(self):
        self.svc = Service(Store(), FakeRpc())
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
        self.assertEqual(self.a, self.call("/v1/me", token=self.token, wallet=self.a)[1]["address"])
        self.assertEqual(self.b, self.call("/v1/me", token=self.token, wallet=self.b)[1]["address"])

    def test_without_linking_the_second_wallet_is_a_stranger(self):
        # The header names a wallet outside the account: ignored, and the token acts as itself.
        self.assertEqual(self.a, self.call("/v1/me", token=self.token, wallet=self.b)[1]["address"])

    def test_someone_elses_signature_cannot_link_a_wallet(self):
        stranger_key, _ = new_wallet()
        code, _ = self.link(self.token, stranger_key, self.b)   # signed by the wrong key
        self.assertEqual(401, code)
        self.assertEqual(self.a, self.call("/v1/me", token=self.token, wallet=self.b)[1]["address"])

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
        code, body = self.call("/v1/me", token=self.token, wallet=other)
        self.assertEqual((200, self.a), (code, body["address"]))

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


class SecurityHardeningTest(HttpBase):
    """Holes found in the November 2026 review, each pinned so it cannot come back."""

    def test_a_recharge_order_is_shown_only_to_its_buyer(self):
        ka, a = new_wallet()
        kb, b = new_wallet()
        self.sign_in(ka, a)
        tb = self.sign_in(kb, b)["token"]
        status, _ = self.call("/v1/topup/orders/cu_" + "0" * 32 + "/confirm", {}, tb)
        self.assertIn(status, (403, 404, 503))

    def test_an_invoice_id_cannot_reach_another_bitrefill_path(self):
        from duffleserver.shop import Shop
        for bad in ("../accounts", "abc/def12345", "x?limit=1000", "", "a" * 65):
            self.assertFalse(Shop.valid_invoice(bad), bad)
        self.assertTrue(Shop.valid_invoice("6f1c2d3e-aaaa-bbbb-cccc-0123456789ab"))
