package so.duffle.app

import android.icu.number.NumberFormatter
import android.icu.number.Precision
import android.icu.text.NumberFormat
import android.icu.util.Currency
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * How AirPay writes numbers. Ported from `ui/lib/format.ts`; the rules were settled on the
 * sandbox's /design page and are the same on both sides.
 *
 * Every amount on screen goes through here. Three rules, and each one was a defect first:
 *
 *  - **The phone's locale groups the digits.** 1,234,567.89 in the US, 12,34,567.89 in India,
 *    1.234.567,89 in Germany. ICU knows all of this; nobody hand-formats a number. The app was
 *    calling `String.format("%,.2f")`, which is the US grouping wherever you are.
 *  - **Cut, never round up.** A fixed number of decimals per unit and the rest is dropped, so a
 *    balance can never read higher than what is actually on chain. `"%.4f".format(sol)` is
 *    HALF_UP: it turned 0.00009 SOL into 0.0001 and claimed money that is not there.
 *  - **A value that cuts to nothing says so.** `<0.0001`, never `0` — zero is a claim, and it
 *    would be false.
 *
 * **Amounts arrive as decimal strings and are held as [BigDecimal], never [Double].** A double
 * cannot represent 0.1, so the moment money touches one the number on screen stops being the
 * number on chain.
 */
object Money {

    /** Decimals per token. Fiat is absent on purpose: its decimals come from the currency. */
    private val TOKEN_DECIMALS = mapOf("SOL" to 4, "USDC" to 2, "USDT" to 2, "EURC" to 2, "SKR" to 0)
    private const val DEFAULT_TOKEN_DECIMALS = 4

    /**
     * The true minus, U+2212 — not the hyphen ICU emits.
     *
     * A hyphen is narrower than a plus, so a column of tabular figures stops lining up exactly
     * where a minus matters most.
     */
    const val MINUS = "−"

    /** A non-breaking space, so "2.00 USDC" never wraps between the number and its code. */
    private const val NBSP = " "

    fun decimalsFor(unit: String): Int = TOKEN_DECIMALS[unit] ?: DEFAULT_TOKEN_DECIMALS

    /**
     * A token amount with no code: "0.0255", "12,34,567.8912", "<0.0001".
     *
     * @param sign shows + for money in as well as − for money out.
     */
    fun tokenAmount(
        value: BigDecimal,
        unit: String,
        locale: Locale = Locale.getDefault(),
        sign: Boolean = false,
    ): String {
        val d = decimalsFor(unit)
        if (cutsToZero(value, d)) {
            // Cut to nothing, but not nothing. Saying "0" here would be the one lie this
            // object exists to prevent.
            val lead = if (value.signum() < 0) MINUS else if (sign) "+" else ""
            return "$lead<${smallest(d, locale)}"
        }
        val cut = value.setScale(d, RoundingMode.DOWN)
        val out = NumberFormatter.withLocale(locale)
            .precision(Precision.fixedFraction(d))
            .roundingMode(RoundingMode.DOWN)
            .format(cut)
            .toString()
        return (if (sign && cut.signum() > 0) "+$out" else out).replace("-", MINUS)
    }

    /** A token amount with its code after it: "2.00 USDC", "+0.5000 SOL". */
    fun token(
        value: BigDecimal,
        unit: String,
        locale: Locale = Locale.getDefault(),
        sign: Boolean = false,
    ): String = "${tokenAmount(value, unit, locale, sign)}$NBSP$unit"

    /**
     * A fiat value in the person's own currency: "$3.09", "₹258.01", "2,84 €".
     *
     * Symbol placement, grouping and decimals all come from the locale and the currency — which
     * is why the symbol is not concatenated here. **`value` is already in `currency`**:
     * conversion happens at the price feed, never in a formatter.
     */
    fun fiat(
        value: BigDecimal,
        currency: String,
        locale: Locale = Locale.getDefault(),
    ): String {
        val cur = Currency.getInstance(currency)
        val d = cur.defaultFractionDigits.coerceAtLeast(0)
        if (cutsToZero(value, d)) {
            val zero = NumberFormat.getCurrencyInstance(locale).apply {
                this.currency = cur
                maximumFractionDigits = d
                minimumFractionDigits = d
            }.format(BigDecimal.ONE.movePointLeft(d))
            return "<$zero"
        }
        return NumberFormatter.withLocale(locale)
            .unit(cur)
            .precision(Precision.fixedFraction(d))
            .roundingMode(RoundingMode.DOWN)
            .format(value.setScale(d, RoundingMode.DOWN))
            .toString()
            .replace("-", MINUS)
    }

    /**
     * Is this a non-zero amount that cuts to zero at `d` decimals?
     *
     * Asked of the number itself rather than of any formatted output. The web version probes a
     * plain en-US string for the same reason: in Arabic or Devanagari digits, a check for the
     * characters "1"–"9" would call every amount dust.
     */
    internal fun cutsToZero(value: BigDecimal, d: Int): Boolean =
        value.signum() != 0 && value.setScale(d, RoundingMode.DOWN).signum() == 0

    /**
     * The amount as it will be shown, before a locale touches it.
     *
     * Internal so a JVM test can hold the rules. **`android.icu` is an Android framework class
     * and does not exist on the unit-test classpath**, so the grouping and the currency symbol
     * can only be checked on a device — but the two things that can lose money are here, and
     * they are testable: how it is cut, and whether it is dust.
     */
    internal fun cut(value: BigDecimal, unit: String): BigDecimal =
        value.setScale(decimalsFor(unit), RoundingMode.DOWN)

    /** The smallest step a unit can show, e.g. "0.0001". */
    private fun smallest(d: Int, locale: Locale): String =
        NumberFormatter.withLocale(locale)
            .precision(Precision.fixedFraction(d))
            .format(BigDecimal.ONE.movePointLeft(d))
            .toString()
}
