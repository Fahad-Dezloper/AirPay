package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bitrefill's order vocabulary, pinned because guessing it has now cost two bugs.
 *
 * Every string here was read off the live API, not the docs — which describe the terminal
 * invoice state as `complete` while the API actually returns `all_delivered`. The first guess
 * checked `"delivered"` alone and left a real ₹7.47 Jio top-up, which had genuinely arrived on
 * the phone, reading "On its way" forever: polled on every launch, never settled.
 *
 * `not_delivered` is the trap. It contains "delivered" as a substring, so any looser test than
 * an exact set inverts the one state that matters most.
 */
class OrderStatusTest {

    private fun order(status: String) = Orders.Order(
        invoiceId = "i", name = "Jio Bundles India", value = "₹7.47 Talktime Top-Up",
        paid = "0.11 USDC", signature = "sig", status = status, at = 0L,
    )

    @Test fun `all_delivered is what a finished invoice really says`() {
        // Observed on invoice 3133b845, a purchase that arrived on a real phone.
        assertTrue(order("all_delivered").delivered)
        assertFalse(order("all_delivered").failed)
        assertFalse(order("all_delivered").waiting)
    }

    @Test fun `the order inside it says delivered, and the docs say complete`() {
        assertTrue(order("delivered").delivered)
        assertTrue(order("complete").delivered)
    }

    @Test fun `not_delivered must never be read as delivered`() {
        // The whole reason the set is exact: "delivered" is inside "not_delivered".
        val o = order("not_delivered")
        assertFalse("not_delivered contains 'delivered' and must still be pending", o.delivered)
        assertFalse(o.failed)
        assertTrue("it has to stay on the list that gets asked again", o.waiting)
    }

    @Test fun `created is pending, not finished`() {
        assertTrue(order("created").waiting)
        assertFalse(order("created").delivered)
    }

    @Test fun `the documented failures are failures`() {
        for (s in listOf("denied", "payment_error", "failed", "expired")) {
            assertTrue(s, order(s).failed)
            assertFalse(s, order(s).delivered)
            assertFalse("a failed order must stop being polled", order(s).waiting)
        }
    }

    @Test fun `a partial delivery is neither done nor failed`() {
        val o = order("partially_delivered")
        assertTrue(o.partial)
        assertFalse(o.delivered)
        assertFalse(o.failed)
        assertTrue("some of it is still owed, so keep asking", o.waiting)
    }

    @Test fun `an unknown status keeps the order on the list`() {
        // A word Bitrefill adds later must not silently mark a purchase finished.
        val o = order("something_new")
        assertFalse(o.delivered)
        assertFalse(o.failed)
        assertTrue(o.waiting)
    }
}

/**
 * Reading a delivered gift card back out of storage.
 *
 * The real payload, from a PhonePe card that was actually bought:
 * `{"code":"9092609239069813","pin":"171129","expiration_date":"2027-09-28T00:00:00.000Z"}`
 *
 * The first version flattened that to "code\npin", which kept every character and lost which
 * was which — a sixteen-digit run above a six-digit run, on a row showing one line. Both
 * formats have to read back correctly now, because a card bought under the old one is still in
 * somebody's history.
 */
class RedemptionTest {

    @Test fun `the real PhonePe payload reads back in parts`() {
        val r = Shop.Redemption.of(
            """{"code":"9092609239069813","pin":"171129","expires":"2027-09-28T00:00:00.000Z"}"""
        )!!
        assertEquals("9092609239069813", r.code)
        assertEquals("171129", r.pin)
        assertEquals("2027-09-28T00:00:00.000Z", r.expires)
    }

    @Test fun `a card stored in the old flat format is split back`() {
        // Written by our own joiner, code first then pin, so this is a format we own rather
        // than an inference about somebody else's.
        val r = Shop.Redemption.of("9092609239069813\n171129")!!
        assertEquals("9092609239069813", r.code)
        assertEquals("171129", r.pin)
    }

    @Test fun `a bare single-line code stays a code`() {
        val r = Shop.Redemption.of("ABCD-EFGH-IJKL")!!
        assertEquals("ABCD-EFGH-IJKL", r.code)
        assertNull(r.pin)
    }

    @Test fun `nothing stored reads back as nothing`() {
        assertNull(Shop.Redemption.of(null))
        assertNull(Shop.Redemption.of(""))
        assertNull(Shop.Redemption.of("   "))
    }

    @Test fun `a round trip keeps every part`() {
        val before = Shop.Redemption(
            code = "C1", pin = "P2", link = "https://x.example", instructions = "Use it",
            expires = "2027-01-01", raw = "note: hello",
        )
        val after = Shop.Redemption.of(before.toJson())!!
        assertEquals(before, after)
    }
}

/**
 * Which number to print as a package's price.
 *
 * Bitrefill sends two, and they disagree. `value` is the clean face value; `amount` carries a
 * rounding artefact — KFC's ₹1000 card is `value "1000"` with `amount 999.92`, and Bigbasket's
 * ₹5000 is 4999.59. Showing `amount` put those on screen as the price of the card.
 *
 * But `amount` cannot simply be dropped: for a bundle the value is a sentence and the price
 * lives nowhere else.
 */
class PackagePriceTest {

    @Test fun `a clean face value wins over the rounding artefact`() {
        assertEquals("1000", Shop.Package("1000", 999).price)
        assertEquals("500", Shop.Package("500", 499).price)
        assertEquals("100", Shop.Package("100", 100).price)
    }

    @Test fun `a bundle falls back to the amount, because its value is a sentence`() {
        assertEquals("859", Shop.Package("2GB/Day 5G Plan – 84 Days", 859).price)
        assertEquals("219", Shop.Package("30GB – 30 Day Data Pack", 219).price)
    }

    @Test fun `a genuinely fractional value keeps its paise`() {
        assertEquals("249.15", Shop.Package("249.15", 249).price)
    }

    @Test fun `a whole number never prints a decimal point`() {
        // "1000.0" beside "₹" reads as a bug even when the arithmetic is right.
        assertEquals("1000", Shop.Package("1000.00", 1000).price)
    }
}
