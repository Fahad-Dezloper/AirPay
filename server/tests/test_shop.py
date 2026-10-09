"""
The shop's catalogue as the phone receives it: hand-written content, the brand colour, and what a
package costs.

Two things here can mislead somebody if they are wrong, so those are the ones held hardest. A guide
has to arrive under exactly the keys the app reads and **only** on the product it was written for —
a bare product must stay bare, because a page that invents content is worse than a thin one. And a
price estimate must never be dressed as exact: it is sent with `usd_price_estimate: true` every
time, and nothing is sent when Bitrefill gave no price, because a zero would read as free.
"""
from __future__ import annotations

import json
import os
import unittest

from duffleserver import shop
from duffleserver.shop import GUIDES, Shop


def raw(pid="google-play-india", **kw) -> dict:
    p = {
        "id": pid, "name": "Google Play India", "currency": "INR", "categories": ["apps"],
        "image": "google-play-india-svg", "recipient_type": None, "redemption_methods": ["code"],
        "packages": [
            {"id": f"{pid}<&>500", "value": "500", "amount": 500, "price": 6385},
            {"id": f"{pid}<&>5000", "value": "5000", "amount": 5000, "price": 63853},
        ],
        "in_stock": True,
    }
    p.update(kw)
    return p


class GuideTest(unittest.TestCase):
    def test_the_file_ships_and_loads(self):
        # The same file the sandbox was written against: 23 products, keyed by Bitrefill id.
        self.assertEqual(len(GUIDES), 24)
        self.assertIn("google-play-india", GUIDES)

    def test_a_guide_is_merged_under_the_keys_the_app_reads(self):
        out = Shop._trim(raw("google-play-india"))
        for key in ("kind", "line", "where_it_works", "how_used", "know", "steps", "amounts"):
            self.assertIn(key, out, key)
        self.assertEqual(out["kind"], "Gift card")
        self.assertIsInstance(out["know"], list)
        self.assertIsInstance(out["steps"], list)

    def test_a_missing_key_stays_missing(self):
        # Google Play's terms give no validity. The file leaves `valid_for` out and so must the
        # response: filling the gap would be inventing a fact about somebody's money.
        self.assertNotIn("valid_for", Shop._trim(raw("google-play-india")))

    def test_a_product_with_no_guide_is_sent_exactly_as_before(self):
        out = Shop._trim(raw("some-product-nobody-wrote-up"))
        for key in ("kind", "line", "where_it_works", "how_used", "know", "steps", "amounts",
                    "valid_for", "tab_label", "tab_line"):
            self.assertNotIn(key, out, key)
        self.assertEqual(out["id"], "some-product-nobody-wrote-up")
        self.assertEqual(out["values"], ["500", "5000"])

    def test_makemytrip_carries_its_tab_words(self):
        tabs = [(k, g) for k, g in GUIDES.items() if "tab_label" in g]
        self.assertEqual(len(tabs), 5)
        self.assertEqual(sorted(g["tab_label"] for _, g in tabs),
                         sorted(["Flights & hotels", "Holidays", "Rail", "Bus", "Hotel card"]))
        for _, g in tabs:
            self.assertTrue(g["tab_line"])

    def test_a_missing_or_broken_file_is_an_empty_shelf_not_an_exception(self):
        real = shop.os.path.dirname
        try:
            shop.os.path.dirname = lambda _p: "/nonexistent-dir"
            self.assertEqual(shop._load_guides(), {})
        finally:
            shop.os.path.dirname = real


class BrandColourTest(unittest.TestCase):
    def test_a_written_colour_wins(self):
        out = Shop._trim(raw("google-play-india", logo_background="#123456"))
        self.assertEqual(out["brand_colour"], GUIDES["google-play-india"]["brand_colour"])

    def test_the_catalogues_own_colour_fills_the_gap(self):
        out = Shop._trim(raw("vodafone-india", logo_background="#EE3023"))
        self.assertEqual(out["brand_colour"], "#EE3023")

    def test_no_colour_anywhere_sends_none(self):
        self.assertNotIn("brand_colour", Shop._trim(raw("nothing-here", logo_background=None)))


class UsdPriceTest(unittest.TestCase):
    def setUp(self):
        self._units = Shop.price_units
        Shop.price_units = 1165.0

    def tearDown(self):
        Shop.price_units = self._units

    def test_it_matches_what_two_real_purchases_were_charged(self):
        # Charged on mainnet: ₹50 Subway (price 629) = 0.54 USDC, ₹100 PhonePe (1294) = 1.11.
        # Charges are cut to the cent, so the estimate may sit one cent above, never far.
        for price, charged in ((629, 0.54), (1294, 1.11)):
            got = float(Shop._usd(price)["usd_price"])
            self.assertAlmostEqual(got, charged, delta=0.011, msg=str(price))

    def test_it_is_always_an_estimate(self):
        self.assertIs(Shop._usd(6385)["usd_price_estimate"], True)

    def test_no_price_sends_nothing_rather_than_zero(self):
        for bad in (None, 0, -5, "", "abc"):
            self.assertEqual(Shop._usd(bad), {}, repr(bad))

    def test_every_package_carries_it(self):
        pk = Shop._trim(raw())["packages"]
        self.assertEqual([p["usd_price"] for p in pk], ["5.48", "54.81"])
        self.assertTrue(all(p["usd_price_estimate"] for p in pk))

    def test_a_package_with_no_price_has_no_usd_keys(self):
        p = raw()
        del p["packages"][0]["price"]
        first = Shop._trim(p)["packages"][0]
        self.assertNotIn("usd_price", first)
        self.assertNotIn("usd_price_estimate", first)


class RateLearningTest(unittest.TestCase):
    def setUp(self):
        self._units = Shop.price_units
        Shop.price_units = 1165.0
        self.s = Shop("key")
        self.s._catalog = (10 ** 12, [raw()])           # fresh forever; no network

    def tearDown(self):
        Shop.price_units = self._units

    def test_a_real_invoice_re_measures_the_rate(self):
        # ₹500 priced 6385 units; the invoice asked 5.30 USDC -> 1204.7 units per USDC.
        self.s._learn_rate("google-play-india", "google-play-india<&>500", "500", 5_300_000)
        self.assertAlmostEqual(Shop.price_units, 6385 / 5.30, places=3)

    def test_a_wild_reading_is_discarded(self):
        # More than a third off is a bad invoice, not a market.
        self.s._learn_rate("google-play-india", "google-play-india<&>500", "500", 500_000)
        self.assertEqual(Shop.price_units, 1165.0)

    def test_an_unknown_product_changes_nothing(self):
        self.s._learn_rate("nope", None, "500", 5_300_000)
        self.assertEqual(Shop.price_units, 1165.0)

    def test_found_by_value_when_no_package_id_is_given(self):
        self.s._learn_rate("google-play-india", None, "500", 5_300_000)
        self.assertAlmostEqual(Shop.price_units, 6385 / 5.30, places=3)


if __name__ == "__main__":
    unittest.main()


class CatalogueReadTest(unittest.TestCase):
    """Bitrefill's product list is paged by offset and the pages overlap and skip, so one read of
    it silently drops a random few products. PhonePe's Home tile vanished for an hour at a time
    because of exactly that. These pin the behaviour that makes the shelf stable."""

    N = 120

    def make(self, flaky=True, hidden=(), only_direct=()):
        """A Shop whose list endpoint misbehaves the way the real one does."""
        ids = [f"p{i}" for i in range(self.N)] + ["phonepe-india", "hotstar-india"]
        rows = {i: raw(i, in_stock=True) for i in ids}
        total = len(ids)
        calls = {"list": 0, "single": []}

        class Fake(Shop):
            def _get(inner, path):
                if path.startswith("/products?"):
                    start = int(path.split("start=")[1])
                    if start == 0:
                        calls["list"] += 1
                    order = [i for i in ids if i not in hidden and i not in only_direct]
                    if flaky:
                        # Each read skips a different product and repeats its neighbour.
                        k = calls["list"] % len(order)
                        order = order[:k] + order[k + 1:] + [order[(k + 1) % len(order)]]
                    return {"meta": {"total": total}, "data": [rows[i] for i in order[start:start + 50]]}
                pid = path.rsplit("/", 1)[1]
                calls["single"].append(pid)
                if pid in rows and pid not in hidden:
                    return {"data": rows[pid]}
                raise RuntimeError(f"404 {pid}")

        return Fake("key"), calls, ids

    def test_one_flaky_read_would_miss_products_but_the_merge_does_not(self):
        s, calls, ids = self.make()
        items, complete = s._fetch_all()
        self.assertEqual({p["id"] for p in items}, set(ids))
        self.assertTrue(complete)
        self.assertGreater(calls["list"], 1, "a single read cannot be trusted to be whole")

    def test_every_read_is_checked_against_bitrefills_own_total(self):
        s, calls, ids = self.make(flaky=False)
        s._fetch_all()
        self.assertEqual(calls["list"], 1, "a clean read needs no repeats")

    def test_a_tile_product_the_list_never_returns_is_fetched_by_id(self):
        s, calls, ids = self.make(only_direct=("phonepe-india",))
        items, _ = s._fetch_all()
        self.assertIn("phonepe-india", {p["id"] for p in items})
        self.assertIn("phonepe-india", calls["single"])

    def test_a_pinned_product_that_is_not_sold_is_simply_absent(self):
        s, calls, ids = self.make(hidden=("phonepe-india",))
        items, _ = s._fetch_all()
        self.assertNotIn("phonepe-india", {p["id"] for p in items})

    def test_out_of_stock_products_are_still_left_out(self):
        s, calls, ids = self.make(flaky=False)
        orig = s._get
        def get(path):
            r = orig(path)
            for row in r.get("data", []) if isinstance(r.get("data"), list) else []:
                if row["id"] == "p5":
                    row["in_stock"] = False
            return r
        s._get = get
        items, _ = s._fetch_all()
        self.assertNotIn("p5", {p["id"] for p in items})

    def test_a_short_read_never_shrinks_what_the_last_good_read_had(self):
        s, calls, ids = self.make(flaky=False)
        first = s.products()
        self.assertEqual({p["id"] for p in first}, set(ids))
        # Next read: Bitrefill claims 122 products but only ever returns 119 of them.
        s2_hidden = ("p7", "p8", "p9")
        orig = s._get
        def short(path):
            r = orig(path)
            if isinstance(r.get("data"), list):
                r = {**r, "data": [row for row in r["data"] if row["id"] not in s2_hidden]}
            return r
        s._get = short
        second = s.products(refresh=True)
        self.assertEqual({p["id"] for p in second}, set(ids), "the shelf must not lose products")

    def test_the_home_tiles_products_are_pinned(self):
        for pid in ("phonepe-india", "hotstar-india", "pvr-cinemas-india", "bookmyshow-voucher-india"):
            self.assertIn(pid, shop.PINNED)
        self.assertTrue(any(p.startswith("makemytrip") for p in shop.PINNED))
