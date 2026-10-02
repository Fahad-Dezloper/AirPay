package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Scanned code" placeholder must become an address start, and nothing real may be touched. */
class PlaceholderNameTest {
    private val addr = "FRB4kEabcdefghijklmnopqrstuvwxyz1234567890AB"

    @Test fun placeholderBecomesSixCharsOfAddress() {
        assertTrue(isPlaceholderName("Scanned code"))
        assertEquals("FRB4kE", displayName("Scanned code", addr))
    }

    @Test fun realNameIsUnchanged() {
        assertFalse(isPlaceholderName("Aashu"))
        assertEquals("Aashu", displayName("Aashu", addr))
        assertEquals("Scanned codes", displayName("Scanned codes", addr))
    }

    @Test fun blankLabelIsNotAPlaceholder() {
        assertFalse(isPlaceholderName(""))
        assertEquals("", displayName("", addr))
    }

    @Test fun shortAddressIsNotPadded() {
        assertEquals("abc", displayName("Scanned code", "abc"))
    }
}
