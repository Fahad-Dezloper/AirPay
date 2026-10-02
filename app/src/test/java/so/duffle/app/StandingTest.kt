package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** What the wallet sheet's Vouch row says in each situation, and when it nags. */
class StandingTest {
    private fun w(addr: String) = Linked(address = addr, token = null, name = "Phantom", uri = null, addedAt = 0)
    private fun link(days: Long) = Vouch.Link(
        "owner", "wallet", LocalDate.now(ZoneOffset.UTC).plusDays(days).toEpochDay(), "sig",
    )

    @Test fun ownerWithNothingToLendJustShowsTheName() {
        val s = Standing.Owns("dezloper.skr", emptyList())
        assertEquals("Your name" to "dezloper.skr", s.row())
        assertTrue(s.verified)
        assertFalse(s.expiringSoon)
    }

    @Test fun ownerSaysWhetherTheOthersCarryItYet() {
        val a = Lent(w("a"), null)
        val b = Lent(w("b"), link(100))
        assertEquals("dezloper.skr · lend it to your other wallets", Standing.Owns("dezloper.skr", listOf(a, b)).row().second)
        assertEquals("dezloper.skr · lent to your other wallets", Standing.Owns("dezloper.skr", listOf(b)).row().second)
    }

    @Test fun borrowedNameShowsItsExpiryUntilItIsClose() {
        val far = Standing.Borrowed("dezloper.skr", null, link(100), 100)
        assertTrue(far.row().second.startsWith("Vouched until "))
        assertFalse(far.expiringSoon)
        val near = Standing.Borrowed("dezloper.skr", null, link(10), 10)
        assertEquals("Seen as dezloper.skr" to "Lapses in 10 days · tap to renew", near.row())
        assertTrue(near.expiringSoon)
        assertTrue(near.verified)
    }

    @Test fun anExpiredOrLastDayLinkReadsAsADayNotAZero() {
        assertEquals("Lapses in a day · tap to renew", Standing.Borrowed(null, null, link(0), 0).row().second)
        assertEquals("Lapses in 1 day · tap to renew", Standing.Borrowed(null, null, link(1), 1).row().second)
    }

    @Test fun offeredAndBareAreNotVerifiedAndDoNotNag() {
        val offered = Standing.Offered(w("o"), "dezloper.skr")
        assertEquals("Use your name here" to "dezloper.skr can vouch for this wallet", offered.row())
        assertFalse(offered.verified)
        assertFalse(offered.expiringSoon)
        assertEquals("Your name" to "No wallet of yours has a .skr or .sol yet", Standing.Bare.row())
        assertFalse(Standing.Bare.verified)
    }

    @Test fun lentReportsDaysLeftOnlyWhenThereIsALink() {
        assertEquals(null, Lent(w("a"), null).daysLeft())
        assertEquals(100L, Lent(w("a"), link(100)).daysLeft())
    }
}
