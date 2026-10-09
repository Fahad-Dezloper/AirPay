package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The caption under a price pill: only when the value is not itself the price. */
class PackageLabelTest {

    private fun label(value: String, amount: Int = 1, name: String = "Amazon Prime India") =
        packageLabel(Shop.Package(value, amount), name)

    @Test fun `a whole number is already the price`() = assertNull(label("399"))

    /**
     * The regression. `toIntOrNull()` called 1.8 a name, so the $1.80 Tinder pill printed
     * "1.8" underneath itself.
     */
    @Test fun `a fractional number is still a price, not a name`() {
        assertNull(label("1.8"))
        assertNull(label("3"))
        for (v in listOf("2.59", "4.44", "6.07", "9.2")) assertNull(label(v))  // the eSIM
    }

    @Test fun `a named package keeps its name`() =
        assertEquals("Subscription 3M", label("Amazon Prime Subscription 3M"))

    @Test fun `the product name is trimmed off the front`() =
        assertEquals("Gold 12M", label("Tinder Gold 12M", name = "Tinder India"))

    /** A value with nothing left after trimming says nothing, so it is not drawn. */
    @Test fun `a value that is only the product name is dropped`() =
        assertNull(label("Amazon Prime", name = "Amazon Prime India"))

    /** amount 0 marks a price we synthesised from `values`, with no package behind it. */
    @Test fun `no package means no caption`() = assertNull(label("Prime 3M", amount = 0))
}
