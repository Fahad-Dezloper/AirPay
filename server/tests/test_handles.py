"""`.air` names: claim, one per account, found while typing."""
from __future__ import annotations

import unittest

from duffleserver.service import Service
from duffleserver.store import Store
from tests.test_server import FakeRpc

A = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
B = "b7aXLq81gY1SSdRwn1jUCyQeB282GJwrzBVCsJ3nFK2"


class HandleTest(unittest.TestCase):
    def setUp(self):
        self.svc = Service(Store(), FakeRpc())

    def test_a_name_is_claimed_and_found(self):
        self.assertTrue(self.svc.claim_handle(A, "Fahad")["ok"])
        self.assertEqual([{"name": "fahad.air", "address": A}], self.svc.resolve_handles("fah"))
        self.assertEqual([{"name": "fahad.air", "address": A}], self.svc.resolve_handles("fahad.air"))

    def test_a_taken_name_is_refused(self):
        self.svc.claim_handle(A, "fahad")
        out = self.svc.claim_handle(B, "fahad")
        self.assertFalse(out["ok"])
        self.assertEqual("taken", out["refused"])

    def test_one_name_per_account(self):
        self.svc.claim_handle(A, "fahad")
        self.svc.claim_handle(A, "fahad_two")
        self.assertEqual(["fahad_two.air"], [h["name"] for h in self.svc.resolve_handles("fah")])

    def test_bad_and_reserved_names_are_refused(self):
        for bad in ("ab", "has space", "x" * 30, "airpay", "a-b-c", "../etc"):
            with self.assertRaises(ValueError, msg=bad):
                self.svc.claim_handle(A, bad)

    def test_wildcards_in_a_search_find_nothing(self):
        self.svc.claim_handle(A, "fahad")
        self.assertEqual([], self.svc.resolve_handles("%"))
        self.assertEqual([], self.svc.resolve_handles("_"))


if __name__ == "__main__":
    unittest.main()
