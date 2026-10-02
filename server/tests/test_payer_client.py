"""The main server's signed client agrees with the payer's verifier — one signature scheme, two
implementations, so a test that proves they match is the cheapest way to never debug it live."""
import os, sys, tempfile, threading, unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "payer"))

from duffleserver.payer_client import Payer, PayerError, sign as client_sign   # noqa: E402
from skrpayer.server import sign as payer_sign                                   # noqa: E402


class SchemeTest(unittest.TestCase):
    def test_both_sides_sign_identically(self):
        s = b"k" * 40
        for m, p, b in (("POST", "/pay", b'{"a":1}'), ("GET", "/pay/x", b""), ("post", "/pay", b"")):
            self.assertEqual(client_sign(s, m, p, "123.5", b), payer_sign(s, m, p, "123.5", b))

    def test_a_missing_or_short_secret_disables_payouts_rather_than_signing_with_nothing(self):
        self.assertFalse(Payer("http://x", None).enabled)
        with tempfile.NamedTemporaryFile("w", delete=False) as f:
            f.write("short")
        self.assertFalse(Payer("http://x", f.name).enabled)
        with self.assertRaises(PayerError) as c:
            Payer("http://x", f.name).pay("reward-0001", "b" * 44, 1)
        self.assertEqual("payouts_disabled", c.exception.code)


if __name__ == "__main__":
    unittest.main()
