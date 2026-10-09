package so.duffle.app

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two shop rules that can mislead a buyer if they are wrong (ui/PORT-2026-10-02-shop.md).
 *
 * **Tab words.** MakeMyTrip is five products sold as one row with five tabs. The hand-written
 * labels have to win over the derived ones — trimming the first product's own name gives
 * "Voucher", which is what the tab said before the server sent `tab_label`.
 *
 * **"Not enough USDC".** Said only when it is certain. An estimate is a cent or two off, and
 * refusing a buyer who can afford a card is the worse mistake, because the invoice's real price is
 * checked against the balance again before anything is signed.
 */
class ShopGuideTest {

    private fun product(id: String, name: String, label: String? = null) = Shop.Product(
        id = id, name = name, currency = "INR", recipient = "none", redemption = emptyList(),
        values = listOf("500"),
        guide = Shop.Guide(tabLabel = label),
    )

    /** The five real ids and the labels the server sends for them (shop-guides.json). */
    private val mmt = listOf(
        "makemytrip-voucher-india" to "Flights & hotels",
        "makemytrip-holiday-india" to "Holidays",
        "makemytrip-rail-india" to "Rail",
        "makemytrip-bus-india" to "Bus",
        "makemytrip-hotel-card-india" to "Hotel card",
    )

    @Test fun `a hand-written tab label wins over the derived one`() {
        val items = mmt.map { (id, label) -> product(id, "MakeMyTrip Voucher India", label) }
        val svc = Shop.services(items).single()
        assertTrue(svc.tabbed)
        // In the order the tabs are written, with the server's words and not a trimmed name.
        assertEquals(mmt.map { it.second }, svc.cards.map { it.label })
    }

    @Test fun `without a label the derived one is used, as before`() {
        val items = mmt.map { (id, _) -> product(id, "MakeMyTrip Rail India") }
        val svc = Shop.services(items).single()
        assertEquals(listOf("Rail"), svc.cards.map { it.label }.distinct())
    }

    @Test fun `with no label the product name is trimmed as before`() {
        val items = listOf(product("hotstar-india", "Hotstar India"))
        val svc = Shop.services(items).single()
        assertEquals("Hotstar", svc.name)
        assertFalse(svc.tabbed)
    }

    @Test fun `an exact price is compared as it is`() {
        assertTrue(notEnough(BigDecimal("5.49"), BigDecimal("5.00"), estimate = false))
        assertFalse(notEnough(BigDecimal("5.49"), BigDecimal("5.49"), estimate = false))
        assertFalse(notEnough(BigDecimal("5.49"), BigDecimal("6.00"), estimate = false))
    }

    @Test fun `an estimate a cent above the balance does not refuse`() {
        // 5.49 estimated, 5.40 held: the real invoice may well be 5.40.
        assertFalse(notEnough(BigDecimal("5.49"), BigDecimal("5.40"), estimate = true))
        // But one that is plainly out of reach still does.
        assertTrue(notEnough(BigDecimal("53.00"), BigDecimal("0.60"), estimate = true))
        assertTrue(notEnough(BigDecimal("5.49"), BigDecimal("5.00"), estimate = true))
    }

    @Test fun `an unknown cost or balance is never not enough`() {
        assertFalse(notEnough(null, BigDecimal("1.00"), estimate = false))
        assertFalse(notEnough(BigDecimal("1.00"), null, estimate = false))
        assertFalse(notEnough(null, null, estimate = true))
    }
}
