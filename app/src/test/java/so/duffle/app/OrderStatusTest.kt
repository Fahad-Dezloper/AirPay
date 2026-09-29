package so.duffle.app

import org.junit.Assert.assertFalse
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
