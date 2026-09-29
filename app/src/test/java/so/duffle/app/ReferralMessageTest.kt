package so.duffle.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invite text. Pure, so it is the one part of the referral flow a test can hold.
 *
 * The case that matters is the empty name: `Identity.label` returns "" for anybody who has
 * never set one, which is the default state, and the invite this app shipped interpolated it
 * anyway — so every share went out starting with a space.
 */
class ReferralMessageTest {

    @Test fun `a named sender is named`() {
        val m = Referrals.message("dezloper.skr", "CXAGJME6", 250)
        assertTrue(m.startsWith("dezloper.skr is paying"))
    }

    @Test fun `an unnamed sender does not leave a gap`() {
        for (blank in listOf("", " ", "   ")) {
            val m = Referrals.message(blank, "CXAGJME6", 250)
            assertFalse("started with whitespace: '$m'", m.first().isWhitespace())
            assertFalse("left a dangling verb: '$m'", m.startsWith("is paying"))
            assertTrue(m.startsWith("Pay anyone by tapping"))
        }
    }

    @Test fun `a padded name is trimmed rather than passed through`() {
        assertTrue(Referrals.message("  Fahad  ", "CXAGJME6", 250).startsWith("Fahad is paying"))
    }

    @Test fun `the code and their reward are always carried`() {
        for (name in listOf("", "dezloper.skr")) {
            val m = Referrals.message(name, "CXAGJME6", 250)
            assertTrue(m.contains("CXAGJME6"))
            assertTrue(m.contains("250 SKR"))
        }
    }

    /** No link until duffle.so resolves — a share whose one link is dead is worse than none. */
    @Test fun `no dead link rides along`() {
        assertFalse(Referrals.message("Fahad", "CXAGJME6", 250).contains("http"))
    }
}
