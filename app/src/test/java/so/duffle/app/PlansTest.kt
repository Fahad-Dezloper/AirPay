package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a plan out of its one line of prose.
 *
 * Every string here was copied from the live catalogue rather than written to suit the parser —
 * Jio's fifty bundles and BSNL's paragraph-long ones are genuinely this inconsistent, and a
 * parser tested on tidy examples would fall apart on the real list.
 *
 * The important half is what it refuses to answer. These numbers are drawn next to a real price
 * on a screen where somebody is choosing what to buy, so a wrong validity is worse than a blank
 * column — it is a specific claim nobody made.
 */
class PlansTest {

    @Test fun `the common Jio shape`() {
        val d = Plans.of("2GB/Day 5G Plan – 84 Days")
        assertEquals("84 days", d.validity)
        assertEquals(84, d.days)
        assertEquals("2GB/day", d.data)
        assertTrue(d.fast)
    }

    @Test fun `a pot of data is not a daily allowance`() {
        // The difference decides whether "30GB" means a month of browsing or a day of it.
        val d = Plans.of("30GB – 30 Day Data Pack")
        assertEquals("30 days", d.validity)
        assertEquals("30GB", d.data)
        assertFalse(d.fast)
    }

    @Test fun `hours are not days`() {
        val d = Plans.of("10GB – 1 Hour Data Pack")
        assertEquals("1 hour", d.validity)
        assertNull("an hour must not be counted as a day", d.days)
        assertEquals("10GB", d.data)
    }

    @Test fun `a fractional allowance keeps its point`() {
        val d = Plans.of("1.5GB/Day + JioSaavn – 84 Days")
        assertEquals("1.5GB/day", d.data)
        assertEquals(84, d.days)
    }

    @Test fun `talktime has neither validity nor data`() {
        val d = Plans.of("₹39.37 Talktime Top-Up")
        assertNull(d.validity)
        assertNull(d.days)
        assertNull(d.data)
    }

    @Test fun `a plain top-up says nothing at all`() {
        // Airtel and Vodafone sell bare amounts; the value IS the money.
        val d = Plans.of("500")
        assertNull(d.validity)
        assertNull(d.data)
        assertFalse(d.fast)
    }

    @Test fun `the headline allowance wins over the caveat`() {
        // BSNL states what you get, then what happens after it runs out. The first is the offer.
        val d = Plans.of(
            "Unlimited voice (Local/STD) any-net in Home LSA and national roaming incl. MTNL " +
                "network in Mumbai and Delhi, Unlimited data with speed reduced to 40 kbps " +
                "after 2 GB /day, 100 SMS/day, 30 Days"
        )
        assertEquals("30 days", d.validity)
        assertEquals("2GB/day", d.data)
    }

    @Test fun `unlimited counts as data when no number is given`() {
        val d = Plans.of("Unlimited, 7 Days")
        assertEquals("Unlimited", d.data)
        assertEquals("7 days", d.validity)
    }

    @Test fun `5G is only claimed when the line says so`() {
        assertTrue(Plans.of("2GB/Day 5G Plan – 14 Days").fast)
        assertTrue(Plans.of("5G Upgrade + 6GB").fast)
        assertFalse(Plans.of("1.5GB/Day – 28 Days").fast)
        // Not a 5G plan, and must not be read as one.
        assertFalse(Plans.of("15GB + JioHotstar – 30 Days").fast)
    }

    // -- per-day cost ---------------------------------------------------------------------------

    @Test fun `per-day makes two plans comparable`() {
        assertEquals("₹10.23/day", Plans.perDay(859, 84, "₹"))
        assertEquals("₹7.30/day", Plans.perDay(219, 30, "₹"))
        // Two places all the way up: rounding to whole rupees made ₹14.97 and ₹15.49 print
        // the same, which defeats the point of showing it.
        assertEquals("₹14.97/day", Plans.perDay(449, 30, "₹"))
    }

    @Test fun `per-day refuses when there is nothing to divide by`() {
        assertNull(Plans.perDay(349, null, "₹"))
        assertNull(Plans.perDay(349, 0, "₹"))
        assertNull(Plans.perDay(0, 28, "₹"))
        // A one-day pack's per-day cost IS its price, so saying it twice adds nothing.
        assertNull(Plans.perDay(19, 1, "₹"))
        assertEquals("₹14.50/day", Plans.perDay(29, 2, "₹"))
    }

    // -- search ---------------------------------------------------------------------------------

    @Test fun `searching by price finds the plan`() {
        // The search people actually run is the number they already know.
        assertTrue(Plans.matches("349", "2GB/Day 5G Plan – 28 Days", 349))
        assertTrue(Plans.matches("34", "2GB/Day 5G Plan – 28 Days", 349))
        assertFalse(Plans.matches("999", "2GB/Day 5G Plan – 28 Days", 349))
    }

    @Test fun `searching by words needs all of them`() {
        assertTrue(Plans.matches("84 days", "2GB/Day 5G Plan – 84 Days", 859))
        assertTrue(Plans.matches("hotstar", "15GB + JioHotstar – 30 Days", 195))
        assertFalse(Plans.matches("netflix hotstar", "15GB + JioHotstar – 30 Days", 195))
    }

    @Test fun `an empty search keeps everything`() {
        assertTrue(Plans.matches("", "anything at all", 0))
        assertTrue(Plans.matches("   ", "anything at all", 0))
    }
}

/**
 * The shelves a plan list is split into.
 *
 * Classification, not ranking — every assertion here can be checked by reading the line it is
 * made about, which is the whole reason these shelves exist and "Trending" does not.
 */
class ShelvesTest {

    private fun shelves(line: String) = Plans.shelves(line)

    @Test fun `a data pack is on the data shelf`() {
        assertTrue(Plans.Shelf.Data in shelves("30GB – 30 Day Data Pack"))
        assertFalse(Plans.Shelf.Talktime in shelves("30GB – 30 Day Data Pack"))
    }

    @Test fun `a plan can sit on several shelves`() {
        // This is one line selling three things, and hiding it from two of them would be wrong.
        val s = shelves("2GB/Day + JioHotstar – 84 Days")
        assertTrue(Plans.Shelf.Data in s)
        assertTrue(Plans.Shelf.Ott in s)
        assertTrue(Plans.Shelf.All in s)
    }

    @Test fun `5G is its own shelf and only when stated`() {
        assertTrue(Plans.Shelf.Fast in shelves("2GB/Day 5G Plan – 84 Days"))
        assertFalse(Plans.Shelf.Fast in shelves("1.5GB/Day – 28 Days"))
    }

    @Test fun `talktime is money on the phone, not an allowance`() {
        assertTrue(Plans.Shelf.Talktime in shelves("₹39.37 Talktime Top-Up"))
        assertTrue(Plans.Shelf.Talktime in shelves("500"))
        // A data pack is not talktime even though both are bought with money.
        assertFalse(Plans.Shelf.Talktime in shelves("12GB – 7 Day Data Pack"))
    }

    @Test fun `unlimited is not counted as a data allowance`() {
        val s = shelves("Unlimited, 7 Days")
        assertTrue(Plans.Shelf.Unlimited in s)
        assertFalse("'Unlimited' is not a number of GB", Plans.Shelf.Data in s)
    }

    @Test fun `everything is on the All shelf`() {
        listOf("500", "Unlimited, 7 Days", "2GB/Day 5G Plan – 84 Days", "₹39.37 Talktime Top-Up")
            .forEach { assertTrue(it, Plans.Shelf.All in shelves(it)) }
    }

    @Test fun `validity and daily data land on their shelves`() {
        val a = shelves("1.5GB/Day – 1 Month")
        assertTrue(Plans.Shelf.Daily in a); assertTrue(Plans.Shelf.Month in a)
        assertTrue(Plans.Shelf.Quarter in shelves("2GB/Day 5G Plan – 84 Days"))
        assertTrue(Plans.Shelf.Yearly in shelves("2.5GB/Day – 365 Days"))
        assertTrue(Plans.Shelf.Short in shelves("6GB – 7 Day Data Pack"))
        assertFalse("a pot of data is not daily", Plans.Shelf.Daily in shelves("30GB – 30 Day Data Pack"))
    }
}
