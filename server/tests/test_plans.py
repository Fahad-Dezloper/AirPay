"""The network's own plan catalogue, read to DESCRIBE plans the shop sells."""
from __future__ import annotations

import json
import os
import unittest

from duffleserver.plans import PlanCatalogue, parse_jio

HERE = os.path.dirname(__file__)


def recorded() -> dict:
    with open(os.path.join(HERE, "fixtures", "jio_plans_slice.json")) as f:
        return json.load(f)


class ParseTest(unittest.TestCase):
    def test_a_recorded_answer_becomes_flat_rows(self):
        rows = parse_jio(recorded())
        self.assertEqual(9, len(rows))
        top = rows[0]
        self.assertEqual(3599, top["amount"])
        self.assertEqual("Popular Plans", top["category"])
        self.assertEqual("365 Days", top["validity"])
        self.assertIn("2.5 GB/Day", top["data"])
        self.assertIn("JioTV", [s["title"] for s in top["subscriptions"]])
        self.assertTrue(top["subscriptions"][0]["icon"].endswith("ic_jiotv-n.svg"))
        self.assertTrue(any(d["label"] == "Voice" for d in top["details"]))

    def test_markup_never_reaches_the_app(self):
        for row in parse_jio(recorded()):
            for text in [row["description"], row["tag"], *row["notes"]]:
                self.assertNotIn("<", text)

    def test_an_icon_name_that_is_not_a_plain_file_is_refused(self):
        from duffleserver.plans import _icon
        self.assertIsNone(_icon("../../etc/passwd.svg"))
        self.assertIsNone(_icon("https://evil.example/x.svg"))
        self.assertIsNone(_icon("thing.png"))
        self.assertTrue(_icon("Z0127.svg").startswith("https://myjiostatic.cdn.jio.com/"))

    def test_list_items_survive_as_bullets(self):
        from duffleserver.plans import _text
        self.assertEqual("Offer: \u2022 One \u2022 Two", _text("Offer:<ul><li>One</li><li>Two</li></ul>"))

    def test_a_plan_without_an_amount_is_dropped(self):
        doc = {"planCategories": [{"type": "X", "subCategories": [{"type": "Y", "plans": [{"amount": "soon"}]}]}]}
        self.assertEqual([], parse_jio(doc))


class CatalogueTest(unittest.TestCase):
    def test_it_is_fetched_once_and_then_kept(self):
        calls = []
        cat = PlanCatalogue(fetch=lambda url: calls.append(url) or recorded())
        self.assertEqual(9, len(cat.plans("jio", now=1000.0)))
        self.assertEqual(9, len(cat.plans("Jio", now=2000.0)))
        self.assertEqual(1, len(calls))

    def test_a_failed_refresh_keeps_the_last_good_answer(self):
        state = {"ok": True}

        def fetch(url):
            if not state["ok"]:
                raise OSError("down")
            return recorded()

        cat = PlanCatalogue(fetch=fetch)
        cat.plans("jio", now=0.0)
        state["ok"] = False
        self.assertEqual(9, len(cat.plans("jio", now=10 * 3600.0)))

    def test_a_source_that_is_down_is_not_asked_on_every_request(self):
        calls = []

        def fetch(url):
            calls.append(url)
            raise OSError("down")

        cat = PlanCatalogue(fetch=fetch)
        self.assertEqual([], cat.plans("jio", now=0.0))
        self.assertEqual([], cat.plans("jio", now=60.0))
        self.assertEqual(1, len(calls))

    def test_other_networks_have_no_catalogue(self):
        cat = PlanCatalogue(fetch=lambda url: self.fail("must not be asked"))
        self.assertEqual([], cat.plans("airtel"))


if __name__ == "__main__":
    unittest.main()
