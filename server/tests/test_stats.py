"""The stats view counts what is in the database and never hands out who."""
import json
import time
import unittest

from duffleserver import stats
from duffleserver.store import Store
from duffleserver.topups import SCHEMA

A = "7xKpQ3FnA1b2C3d4E5f6G7h8J9k1L2m3N4p5Q6r7S8tU"
B = "Bq2mWd8TA1b2C3d4E5f6G7h8J9k1L2m3N4p5Q6r7S8tV"


class StatsTest(unittest.TestCase):
    def setUp(self):
        self.store = Store(":memory:")
        db, now = self.store._db, time.time()
        db.executescript(SCHEMA)
        with db:
            db.execute("INSERT INTO accounts(id, created_at) VALUES ('acc1', ?)", (now - 3600,))
            db.execute("INSERT INTO accounts(id, created_at) VALUES ('acc2', ?)", (now - 40 * 86400,))
            db.execute("INSERT INTO account_wallets VALUES (?, 'acc1', ?)", (A, now))
            db.execute("INSERT INTO sessions(token_hash, address, created_at, expires_at) VALUES ('h1', ?, ?, ?)", (A, now - 60, now + 86400))
            for i, (buyer, state, usdc) in enumerate(((A, "delivered", "5.00"), (B, "refunded", "1.00"), (A, "waiting", "2.00"))):
                db.execute("INSERT INTO topups(id, buyer, phone, country, operator, plan_id, label, usdc, state, created_at, updated_at, supplier)"
                           " VALUES (?, ?, '+911234567890', 'IN', 'jio', 'p', 'Plan', ?, ?, ?, ?, 'cryptorefills')",
                           (f"cu_{i}", buyer, usdc, state, now - 100, now - 100))

    def test_counts(self):
        s = self.store.stats()
        self.assertEqual(s["people"]["accounts"], 2)
        self.assertEqual(s["people"]["new_accounts"]["7d"], 1)
        self.assertEqual(s["people"]["signed_in"]["24h"], 1)
        rc = s["recharges"]
        self.assertEqual((rc["orders"], rc["delivered"], rc["buyers"]), (3, 1, 1))
        self.assertEqual(rc["usdc_delivered"], 5.0)
        self.assertEqual(rc["by_state"], {"delivered": 1, "refunded": 1, "waiting": 1})
        self.assertEqual(len(s["days"]), 14)
        self.assertEqual(s["days"][-1]["orders"], 3)

    def test_no_addresses_or_numbers_leave(self):
        text = json.dumps(self.store.stats())
        self.assertNotIn(A, text)
        self.assertNotIn(B, text)
        self.assertNotIn("1234567890", text)

    def test_table_renders_without_github(self):
        s = self.store.stats()
        s["downloads"] = None
        out = stats._table(s)
        self.assertIn("could not reach GitHub", out)
        self.assertIn("RECHARGES", out)


if __name__ == "__main__":
    unittest.main()
