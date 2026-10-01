package so.duffle.app

import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * `Shop.Invoice.shown` is the string a buyer is charged, written once and then stored verbatim on
 * `Orders.Order.paid`. ui/PORT-2026-10-02.md §4 puts it on the receipt at heading size, which is
 * what made the three faults in one line worth a test.
 *
 * It was `"%.2f %s".format(amount / 1_000_000.0, currency)`:
 *
 *  - `%.2f` rounds HALF_UP, so a price could be written *lower* than it was charged;
 *  - the division went through `Double`, which holds no decimal cent exactly;
 *  - `format` with no locale writes the JVM default's separators, so the one figure the receipt
 *    exists for was the only figure in the app not written the way the phone writes numbers.
 *
 * **What this covers and what it cannot.** Following [MoneyTest]'s own split: the grouping, the
 * separators and the no-break space are `android.icu`, which has nothing on the unit-test
 * classpath, so `shown` itself cannot be called here and is checked on the phone. What is here is
 * the half that decides whether the figure is the figure — base units to a decimal, and cut
 * rather than rounded. Each case below is the same arithmetic `shown` performs before it hands
 * the value to [Money], plus the old line's answer for the same input, so the test states the
 * difference rather than asserting the new code against itself.
 */
class InvoiceShownTest {

    /** Exactly what `shown` computes before the formatter sees it. */
    private fun decimal(base: Long): BigDecimal =
        BigDecimal.valueOf(base).movePointLeft(Shop.USDC_DECIMALS)

    /** The old line, kept so the fault is demonstrated and not merely described. */
    @Suppress("DEPRECATION")
    private fun oldWay(base: Long): String =
        String.format(Locale.US, "%.2f", base / 1_000_000.0)

    private fun cut(base: Long) = Money.cut(decimal(base), "USDC").toPlainString()

    @Test fun `base units convert exactly, with no Double in the path`() {
        // `movePointLeft` keeps the scale it was given, so these carry all six decimals —
        // which is the point: nothing has been thrown away yet when `Money` receives them.
        assertEquals("2.000000", decimal(2_000_000).toPlainString())
        assertEquals("11.960000", decimal(11_960_000).toPlainString())
        assertEquals("5.499999", decimal(5_499_999).toPlainString())
        // One base unit: 0.000001 USDC. A real, if tiny, charge.
        assertEquals("0.000001", decimal(1).toPlainString())
    }

    @Test fun `a part-cent is cut, never rounded up`() {
        assertEquals("5.49", cut(5_499_999))
        assertEquals("5.49", cut(5_495_000))
        assertEquals("11.96", cut(11_960_000))
        assertEquals("2.00", cut(2_000_000))
    }

    /**
     * The fault, stated as a difference. Both halves matter: the old line wrote a figure the
     * buyer was never charged, and it wrote it in the direction that favours us.
     */
    @Test fun `the old line rounded a charge up, and this one does not`() {
        assertEquals("5.50", oldWay(5_499_999))
        assertEquals("5.49", cut(5_499_999))
        assertNotEquals(oldWay(5_499_999), cut(5_499_999))

        assertEquals("0.01", oldWay(5_000))
        assertEquals("0.00", cut(5_000))
    }

    @Test fun `padding zeros survive, so a receipt reads 2-00 and not 2`() {
        // `cut` keeps the unit's scale; without it the receipt would read "2 USDC" for a
        // two-dollar charge and "0 USDC" for a sub-cent one.
        assertEquals(2, Money.cut(decimal(2_000_000), "USDC").scale())
        assertEquals("0.00", cut(1))
    }
}
