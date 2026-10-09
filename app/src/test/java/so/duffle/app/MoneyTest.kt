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
 *
 * That split is why the newer rules are tested through [Money.plain], [Money.significant] and
 * [Money.plainTip] rather than through `tokenAmount`, `unitPrice` and `priceLabel` themselves.
 * Each of those three returns the digits the formatter will be handed, with no symbol and a
 * plain "." for the decimal mark:
 *
 *  - **covered here:** how many decimals a token gets and whether its padding zeros are kept,
 *    how many significant figures a sub-dollar price keeps and which are thrown away, and for
 *    a price label whether it rounds, how many figures survive and how many zeros collapse
 *    into the subscript.
 *  - **device-only:** the "$", which side of the figures it goes on, the locale's own decimal
 *    mark and digit grouping. §6 of the port brief checks those on the Seeker.
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

    /** A memecoin held by the million shows whole units; JUP, like a dollar, shows cents. */
    @Test fun `JUP shows cents and BONK shows whole units`() {
        assertEquals(2, Money.decimalsFor("JUP"))
        assertEquals(0, Money.decimalsFor("BONK"))
        assertEquals("1.23", cut("1.2399", "JUP"))
        assertEquals("1800499", cut("1800499.86", "BONK"))
    }

    /** An unlisted mint gets the safest option, not a guess at two. */
    @Test fun `an unknown token falls back to four`() = assertEquals(4, Money.decimalsFor("9WzD"))

    // -- padding zeros: a known token pads, an unknown mint does not --------------------

    /**
     * "25.0000" claims four places of precision about a token whose decimals were never looked
     * up. An unknown mint shows what it has and no more; a known one keeps its fixed places,
     * because "2 USDC" reads as a different number from "2.00 USDC".
     */
    @Test fun `an unknown token is not padded with zeros`() {
        assertEquals("25", Money.plain(BigDecimal("25"), "9WzD"))
        assertEquals("0.25", Money.plain(BigDecimal("0.25"), "9WzD"))
        // Still cut to four places at most, even unpadded.
        assertEquals("0.2599", Money.plain(BigDecimal("0.25999"), "9WzD"))
        assertEquals(0, Money.minDecimalsFor("9WzD"))
    }

    @Test fun `a known token keeps its padding zeros`() {
        assertEquals("2.00", Money.plain(BigDecimal("2"), "USDC"))
        assertEquals("0.5000", Money.plain(BigDecimal("0.5"), "SOL"))
        // A token whose own decimals are zero has nothing to pad.
        assertEquals("7", Money.plain(BigDecimal("7"), "SKR"))
        assertEquals(2, Money.minDecimalsFor("USDC"))
        assertEquals(4, Money.minDecimalsFor("SOL"))
    }

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

    // -- a price is not a balance ------------------------------------------------------

    /**
     * The rule behind [Money.unitPrice]. The formatting is ICU and is checked on the phone;
     * which branch a price takes is here, because getting it wrong is what costs: at two
     * decimals a $0.0195 token prices at $0.01 — off by half — and every sub-cent token on the
     * screen reads $0.00.
     */
    @Test fun `a dollar price keeps cents and a sub-dollar price does not`() {
        assertEquals(2, Money.priceDecimals(BigDecimal("120.49")))
        assertEquals(2, Money.priceDecimals(BigDecimal("1")))
        assertEquals(4, Money.priceDecimals(BigDecimal("0.9999")))
        assertEquals(4, Money.priceDecimals(BigDecimal("0.0195")))
        // Sign does not decide precision; magnitude does.
        assertEquals(2, Money.priceDecimals(BigDecimal("-120.49")))
    }

    /**
     * Under a dollar a price keeps four SIGNIFICANT figures, not four decimals. Four decimals
     * was the defect: it turned BONK's $0.0000037 into "<$0.0001", true and useless, so a whole
     * column of tokens priced at the same meaningless number.
     */
    @Test fun `a sub-dollar price keeps four significant figures, cut`() {
        fun figures(v: String) = Money.significant(BigDecimal(v), Money.PRICE_FIGURES).toPlainString()
        // The case from the brief: a price nine zeros deep still gets four real digits.
        assertEquals("0.000003736", figures("0.000003736"))
        assertEquals("0.9997", figures("0.99979999"))
        assertEquals("0.01764", figures("0.017649999"))
        // Cut, never rounded up — the figure thrown away is a 9 and nothing carries.
        assertEquals("0.1234", figures("0.12349999"))
        assertEquals(4, Money.PRICE_FIGURES)
    }

    /**
     * ...and never fewer than two DECIMALS, which is a separate bound in a different unit. Making
     * [Money.unitPrice] match the sandbox exactly gave both sides `minimumSignificantDigits = 1`,
     * and 0.5 started rendering "$0.5" — a price with one decimal reads unfinished. The two bounds
     * are reconciled in [Money.priceScale] on purpose rather than handed to the formatter, which
     * resolves them the wrong way: given a significant-figure maximum and a decimal minimum at
     * once, it keeps the figures and drops the minimum.
     */
    @Test fun `a sub-dollar price keeps at least two decimals`() {
        assertEquals("0.50", Money.plainPrice(BigDecimal("0.5")))
        // Same number, already carrying its zeros: still two, not four.
        assertEquals("0.50", Money.plainPrice(BigDecimal("0.5000")))
        assertEquals(2, Money.priceScale(BigDecimal("0.5")))
        assertEquals(2, Money.PRICE_MIN_DECIMALS)
        // The floor only ever pads a short price up. It never costs a real figure...
        assertEquals("0.9997", Money.plainPrice(BigDecimal("0.99979999")))
        assertEquals("0.01764", Money.plainPrice(BigDecimal("0.017649999")))
        assertEquals("0.000003736", Money.plainPrice(BigDecimal("0.000003736")))
        assertEquals(9, Money.priceScale(BigDecimal("0.000003736")))
        // ...and it does not pad every price out to four figures: 0.004 keeps its own three.
        assertEquals("0.004", Money.plainPrice(BigDecimal("0.004")))
        // Exactly zero is a price of nothing, not dust: cents, like a dollar price.
        assertEquals("0.00", Money.plainPrice(BigDecimal.ZERO))
        // A dollar and up is untouched by any of this — cents, cut.
        assertEquals("120.49", Money.plainPrice(BigDecimal("120.499")))
    }

    /** Four decimals of dust is still a real thing for a BALANCE, which is where this is used. */
    @Test fun `a balance under a hundredth of a cent is dust`() {
        assertTrue(Money.cutsToZero(BigDecimal("0.00001"), 4))
        assertFalse(Money.cutsToZero(BigDecimal("0.0195"), 4))
    }

    // -- the price label, the one thing here that rounds -------------------------------

    /**
     * [Money.priceLabel] is the single exception to "cut, never round up", because it labels a
     * reference price nobody holds. The exception is the thing worth pinning down: if this
     * started cutting, 117.9 would read "$117", and if a balance started rounding the way this
     * does, the app would claim money that is not there.
     */
    @Test fun `a price label rounds to the closest`() {
        assertEquals("117", Money.plainTip(BigDecimal("117.4")))
        assertEquals("118", Money.plainTip(BigDecimal("117.6")))
        // The same number as a balance is cut, not rounded. Both rules, side by side.
        assertEquals("117.60", Money.plain(BigDecimal("117.6"), "USDC"))
    }

    /** A dollar and up is the whole dollar; under a dollar is two significant figures. */
    @Test fun `a price label keeps two significant figures under a dollar`() {
        assertEquals("0.99", Money.plainTip(BigDecimal("0.9932")))
        assertEquals("0.32", Money.plainTip(BigDecimal("0.323")))
        assertEquals("0.018", Money.plainTip(BigDecimal("0.01771")))
        // Two figures can round across the dollar, and then it is a whole dollar: "$1".
        assertEquals("1", Money.plainTip(BigDecimal("0.996")))
    }

    /** Four or more zeros after the point collapse into a subscript count, DEX-screener style. */
    @Test fun `a tiny price label collapses its zeros`() {
        assertEquals("0.0₅37", Money.plainTip(BigDecimal("0.000003747")))
        // Three zeros is still short enough to write out; four is where it collapses.
        assertEquals("0.00018", Money.plainTip(BigDecimal("0.00018")))
        assertEquals("0.0₄18", Money.plainTip(BigDecimal("0.000018")))
        // Rounding up can shorten the run of zeros. The count has to follow the rounded
        // figure, not the raw one, or the label reads ten times the real price.
        assertEquals("0.0₄10", Money.plainTip(BigDecimal("0.0000099999")))
        assertEquals("₀₁₂₃₄₅₆₇₈₉", (0..9).joinToString("") { Money.subscript(it) })
    }

    /** Zero is zero: there are no zeros to count and nothing to resolve. */
    @Test fun `a zero price label is just zero`() = assertEquals("0", Money.plainTip(BigDecimal.ZERO))

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
