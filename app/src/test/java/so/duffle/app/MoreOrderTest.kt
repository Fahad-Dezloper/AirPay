package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The More page's two rules: which companies come first, and what a category filter lets through.
 *
 * The order is matched by name, so the thing to get wrong is matching too much: "jio" inside
 * "JioMart", or "prime" inside another word. Both are checked from the side that must stay put
 * as well as the side that must move.
 */
class MoreOrderTest {

    private fun product(id: String, name: String, vararg categories: String) = Shop.Product(
        id = id, name = name, currency = "INR", recipient = "none", redemption = emptyList(),
        categories = categories.toList(), values = listOf("500"),
    )

    @Test fun `the ten named companies lead, in the order asked for`() {
        // Sent in the opposite order, with strangers between them.
        val sent = listOf(
            product("z1", "Zara India", "apparel"),
            product("ccd", "Cafe Coffee Day India", "restaurants"),
            product("kfc", "KFC India", "restaurants"),
            product("jio", "Jio India", "refill"),
            product("sw", "Swiggy India", "food-delivery"),
            product("ub", "Uber India", "travel"),
            product("apv", "Amazon Prime Video India", "streaming"),
            product("tinder-plus-and-gold-india", "Tinder Plus & Gold India", "experiences"),
            product("makemytrip-voucher-india", "MakeMyTrip Voucher India", "travel"),
            product("phonepe-india", "PhonePe India", "digital-wallet"),
            product("hotstar-india", "Hotstar India", "streaming"),
            product("z2", "IKEA India", "retail"),
        )
        val names = Shop.services(sent).map { it.name }
        assertEquals(
            listOf("Hotstar", "PhonePe", "MakeMyTrip", "Tinder Plus & Gold", "Amazon Prime Video", "Uber", "Swiggy", "Jio", "KFC", "Cafe Coffee Day"),
            names.take(10),
        )
        // The rest follow, none lost.
        assertEquals(setOf("Zara", "IKEA"), names.drop(10).toSet())
    }

    @Test fun `a name is matched as whole words, not as letters inside another`() {
        assertEquals(7, Shop.firstRank("Jio"))
        assertEquals(-1, Shop.firstRank("JioMart Gift Card"))
        assertEquals(4, Shop.firstRank("Amazon Prime Video"))
        assertEquals(-1, Shop.firstRank("Primetime Cinemas"))
        assertEquals(-1, Shop.firstRank("Zara"))
    }

    @Test fun `a filter lets through what is in it and nothing else`() {
        val services = Shop.services(listOf(
            product("a", "Hotstar India", "streaming", "entertainment"),
            product("b", "Zara India", "apparel"),
            product("c", "Swiggy India", "food-delivery"),
        ))
        fun named(n: String) = services.first { it.name == n }
        assertTrue(Shop.inFilter(named("Hotstar"), "Streaming"))
        assertFalse(Shop.inFilter(named("Zara"), "Streaming"))
        assertTrue(Shop.inFilter(named("Zara"), "Clothes"))
        assertFalse(Shop.inFilter(named("Swiggy"), "Clothes"))
        assertTrue(Shop.inFilter(named("Swiggy"), "Food"))
    }
}
