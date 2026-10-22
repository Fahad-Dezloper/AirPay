"""The stats view counts what is in the database and never hands out who."""
import json
import time
import unittest

from duffleserver import stats
from duffleserver.store import Store

A = "7xKpQ3FnA1b2C3d4E5f6G7h8J9k1L2m3N4p5Q6r7S8tU"
B = "Bq2mWd8TA1b2C3d4E5f6G7h8J9k1L2m3N4p5Q6r7S8tV"


class StatsTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(":memory:")
        db, now = self.store._db, time.time()
        with db:
            db.execute("INSERT INTO accounts(id, created_at) VALUES ('acc1', ?)", (now - 3600,))
            db.execute("INSERT INTO accounts(id, created_at) VALUES ('acc2', ?)", (now - 40 * 86400,))
            db.execute("INSERT INTO account_wallets VALUES (?, 'acc1', ?)", (A, now))
            db.execute("INSERT INTO sessions(token_hash, address, created_at, expires_at) VALUES ('h1', ?, ?, ?)", (A, now - 60, now + 86400))
            db.execute(
                "INSERT INTO requests(reference, recipient, amount, lamports, created_at, status, signature, paid_at, payer, symbol, origin)"
                " VALUES ('r1', ?, '1', 1000, ?, 'paid', 's1', ?, ?, 'USDC', 'tap')", (B, now - 100, now - 50, A))
            db.execute(
                "INSERT INTO requests(reference, recipient, amount, lamports, created_at, status)"
                " VALUES ('r2', ?, '1', 1000, ?, 'open')", (B, now - 100))

    def test_counts(self):
        s = self.store.stats()
        self.assertEqual(s["people"]["accounts"], 2)
        self.assertEqual(s["people"]["new_accounts"]["7d"], 1)
        self.assertEqual(s["people"]["signed_in"]["24h"], 1)
        self.assertEqual(s["payments"]["paid"], 1)
        self.assertEqual(s["payments"]["by_tap"], 1)
        self.assertEqual(s["payments"]["by_token"], {"USDC": 1})
        self.assertEqual(s["payments"]["requests_open"], 1)
        self.assertEqual(len(s["days"]), 14)
        self.assertEqual(s["days"][-1]["payments"], 1)

    def test_no_addresses_leave(self):
        text = json.dumps(self.store.stats())
        self.assertNotIn(A, text)
        self.assertNotIn(B, text)

    def test_table_renders_without_github(self):
        s = self.store.stats()
        s["downloads"] = None
        out = stats._table(s)
        self.assertIn("could not reach GitHub", out)
        self.assertIn("PAYMENTS", out)


if __name__ == "__main__":
    unittest.main()
