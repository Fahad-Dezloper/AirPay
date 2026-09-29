package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The two rules in [Money] that can cost money, held by a test.
 *
 * **What this deliberately does not cover:** the grouping, the currency symbol and its
 * placement. Those are `android.icu`, which is an Android framework class with nothing on the
 * unit-test classpath — so they are verified on the phone instead. What is here is the half
 * that decides whether a number on screen is the number on chain.
 */
class MoneyTest {

    private fun cut(v: String, unit: String) = Money.cut(BigDecimal(v), unit).toPlainString()

    // -- cut, never round up -----------------------------------------------------------

    /** The defect this replaces: `"%.4f".format(sol)` is HALF_UP and rounds a balance *up*. */
    @Test fun `a balance is never rounded up`() {
        assertEquals("0.0255", cut("0.025590001", "SOL"))
        assertEquals("12.99", cut("12.999", "USDC"))
        assertEquals("0.0000", cut("0.00009", "SOL"))
    }

    @Test fun `each token keeps its own decimals`() {
        assertEquals(4, Money.decimalsFor("SOL"))
        assertEquals(2, Money.decimalsFor("USDC"))
        assertEquals(0, Money.decimalsFor("SKR"))
        assertEquals("450", cut("450.9", "SKR"))
    }

    /** An unlisted mint gets the safest option, not a guess at two. */
    @Test fun `an unknown token falls back to four`() = assertEquals(4, Money.decimalsFor("9WzD"))

    // -- dust -------------------------------------------------------------------------

    /**
     * Zero is a claim. Something that cuts to nothing is not nothing, and saying "0" would be
     * the one lie this object exists to prevent.
     */
    @Test fun `a non-zero amount that cuts away is dust, not zero`() {
        assertTrue(Money.cutsToZero(BigDecimal("0.00000042"), 4))
        assertTrue(Money.cutsToZero(BigDecimal("0.004"), 2))
        assertTrue(Money.cutsToZero(BigDecimal("-0.00000042"), 4))
    }

    @Test fun `actual zero is not dust`() = assertFalse(Money.cutsToZero(BigDecimal("0"), 4))

    @Test fun `an amount that survives the cut is not dust`() {
        assertFalse(Money.cutsToZero(BigDecimal("0.0001"), 4))
        assertFalse(Money.cutsToZero(BigDecimal("0.01"), 2))
    }

    // -- the minus ---------------------------------------------------------------------

    /**
     * U+2212, not the hyphen ICU emits. A hyphen is narrower than a plus, so a column of
     * tabular figures stops lining up exactly where a minus matters most.
     */
    @Test fun `the minus is the real one`() {
        assertEquals("−", Money.MINUS)
        assertFalse("it must not be a hyphen", Money.MINUS == "-")
    }

    // -- money is never a double -------------------------------------------------------

    /** The reason every signature takes a BigDecimal: a double cannot hold 0.1. */
    @Test fun `the string is preserved exactly`() {
        assertEquals("0.3000", cut("0.3", "SOL"))
        // The same value through a double is not 0.3, and never was.
        assertFalse(BigDecimal(0.1 + 0.2).toPlainString() == "0.3")
        assertTrue(BigDecimal(0.1 + 0.2).toPlainString().startsWith("0.30000000000000004"))
    }
}
