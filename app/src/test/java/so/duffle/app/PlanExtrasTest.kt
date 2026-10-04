package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A plan sold by the shop is described by the network's own catalogue only when the two really are
 * the same plan. Price alone is not enough: different plans share prices.
 */
class PlanExtrasTest {
    private fun extra(amount: Int, category: String, validity: String) = Plans.Extra(
        amount = amount, category = category, data = "", validity = validity, tag = "",
        description = "", subscriptions = emptyList(), details = emptyList(), notes = emptyList(),
    )

    private val all = listOf(
        extra(349, "Popular Plans", "28 Days"),
        extra(349, "True 5G Unlimited Plans", "28 Days"),
        extra(1000, "Popular Plans", "365 Days"),
        extra(1000, "Top-up Voucher", "Unlimited"),
        extra(11, "Data Packs", "1 Hour"),
    )

    @Test fun aPlanTakesEveryShelfItIsOn() {
        val got = Plans.extrasFor("2GB/Day + 5G + JioHotstar – 28 Days", 349, all)
        assertEquals(listOf("Popular Plans", "True 5G Unlimited Plans"), got.map { it.category })
    }

    @Test fun aTopUpNeverTakesAPlansDescription() {
        val got = Plans.extrasFor("₹844.46 Talktime Top-Up", 1000, all)
        assertEquals(listOf("Top-up Voucher"), got.map { it.category })
    }

    @Test fun aPlanNeverTakesATopUpsDescription() {
        val got = Plans.extrasFor("2.5GB/Day Annual Plan – 365 Days", 1000, all)
        assertEquals(listOf("Popular Plans"), got.map { it.category })
    }

    @Test fun differentDaysAtTheSamePriceAreNotTheSamePlan() {
        assertTrue(Plans.extrasFor("2GB/Day 5G Plan – 84 Days", 349, all).isEmpty())
    }

    @Test fun anHourlyPackStillMatches() {
        assertEquals(1, Plans.extrasFor("10GB – 1 Hour Data Pack", 11, all).size)
    }

    @Test fun shelfNamesAreShortenedForTabs() {
        assertEquals("Popular", Plans.tabName("Popular Plans"))
        assertEquals("Data Packs", Plans.tabName("Data Packs"))
    }
}

/** A description the network wrote as one string comes apart into the points it holds. */
class PlanPointsTest {
    @Test fun numberedPointsAreSeparated() {
        val got = Plans.points("1. Data -5 GB / 28 days 2. Select any one Premium OTT app 3) Voice unlimited")
        assertEquals(listOf("Data -5 GB / 28 days", "Select any one Premium OTT app", "Voice unlimited"), got)
    }

    @Test fun aDecimalIsNotAListNumber() {
        assertEquals(1, Plans.points("Unlimited 5G data, 2.5 GB/Day, 100 SMS/Day").size)
    }

    @Test fun bulletsFromTheServerAreSeparated() {
        assertEquals(listOf("Offer:", "One", "Two"), Plans.points("Offer: • One • Two"))
    }

    @Test fun nothingInNothingOut() {
        assertTrue(Plans.points(null).isEmpty())
        assertTrue(Plans.points("  ").isEmpty())
    }
}
