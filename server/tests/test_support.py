import tempfile
import unittest

from duffleserver.store import Store
from duffleserver.support import Mailer, SupportDesk
from duffleserver.topups import TopupDesk, TopupRefused

ME = "Buyer1111111111111111111111111111111111111"
OTHER = "9xQeWvG816bUx9EPjHmaT23yvVM2ZWbrrpZb9PusVFin"


class FakeCR:
    enabled = True
    min_usdc = 0.56


class Support(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.store = Store(self.dir.name + "/t.db")
        self.now = [1_000_000.0]
        self.desk = TopupDesk(self.store, FakeCR(), now=lambda: self.now[0])
        with self.store._lock, self.store._db:
            cols = [r[1] for r in self.store._db.execute("PRAGMA table_info(topups)")]
            row = {"id": "cu_" + "a" * 32, "buyer": ME, "cr_id": "cr-123", "state": "delivering",
                   "raw_state": "WaitingForDelivery", "phone": "+919800000000", "country": "IN",
                   "operator": "Airtel", "label": "₹49", "usdc": "0.57", "address": "PayHere",
                   "created_at": self.now[0], "updated_at": self.now[0]}
            row = {k: v for k, v in row.items() if k in cols}
            for c in cols:  # fill any other NOT NULL columns with something plain
                row.setdefault(c, "x" if c not in ("created_at", "updated_at", "looked_at") else 0)
            self.store._db.execute(f"INSERT INTO topups ({','.join(row)}) VALUES ({','.join('?' * len(row))})",
                                   list(row.values()))
        self.sent = []
        self.support = SupportDesk(self.store, self.desk,
                                   Mailer("airpayhq@gmail.com", "app-pass", send=self.sent.append),
                                   now=lambda: self.now[0])
        self.oid = "cu_" + "a" * 32

    def tearDown(self):
        self.dir.cleanup()

    def test_mail_carries_our_records_and_replies_to_the_buyer(self):
        out = self.support.send(ME, self.oid, email="buyer@example.com", query="Paid but no data yet", app="CellUp/1")
        self.assertTrue(out["ok"])
        msg = self.sent[0]
        self.assertEqual(msg["To"], "airpayhq@gmail.com")
        self.assertEqual(msg["Reply-To"], "buyer@example.com")
        self.assertIn("Order AAAAAAAAAA", msg["Subject"])
        body = msg.get_content()
        for want in ("Paid but no data yet", "cr-123", "+919800000000", "0.57 USDC", ME):
            self.assertIn(want, body)

    def test_someone_elses_order_is_not_found(self):
        with self.assertRaises(TopupRefused) as e:
            self.support.send(OTHER, self.oid, email="x@example.com", query="hello there")
        self.assertEqual(e.exception.status, 404)
        self.assertEqual(self.sent, [])

    def test_header_injection_is_flattened_or_refused(self):
        with self.assertRaises(TopupRefused):
            self.support.send(ME, self.oid, email="a@b.co\r\nBcc: evil@x.com", query="hello there")
        self.assertEqual(self.sent, [])

    def test_limits(self):
        for _ in range(3):
            self.support.send(ME, self.oid, email="b@example.com", query="still waiting")
        with self.assertRaises(TopupRefused) as e:
            self.support.send(ME, self.oid, email="b@example.com", query="still waiting")
        self.assertEqual(e.exception.status, 429)
        with self.assertRaises(TopupRefused):
            self.support.send(ME, self.oid, email="b@example.com", query="hi")  # too short is checked first

    def test_mail_off_keeps_the_ticket_and_sends_nothing(self):
        off = SupportDesk(self.store, self.desk, Mailer(None, None), now=lambda: self.now[0])
        out = off.send(ME, self.oid, email="b@example.com", query="still waiting")
        self.assertFalse(out["mailed"])
        self.assertEqual(self.sent, [])
        self.assertFalse(off.preview(ME, self.oid)["mail"])

    def test_tickets_list_only_mine_newest_first_with_the_order(self):
        self.support.send(ME, self.oid, email="b@example.com", query="first one")
        self.now[0] += 10
        self.support.send(ME, self.oid, email="b@example.com", query="second one")
        mine = self.support.tickets(ME)
        self.assertEqual([t["query"] for t in mine], ["second one", "first one"])
        self.assertEqual(mine[0]["order"]["id"], self.oid)
        self.assertTrue(mine[0]["mailed"])
        self.assertEqual(self.support.tickets(OTHER), [])


if __name__ == "__main__":
    unittest.main()
