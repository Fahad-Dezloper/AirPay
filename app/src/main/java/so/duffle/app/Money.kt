package so.duffle.app

import android.icu.number.NumberFormatter
import android.icu.number.Precision
import android.icu.text.DecimalFormatSymbols
import android.icu.text.NumberFormat
import android.icu.util.Currency
import java.math.BigDecimal
import java.math.MathContext
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
 *    HALF_UP: it turned 0.00009 SOL into 0.0001 and claimed money that is not there. The one
 *    exception in this file is [priceLabel], a chart's reference price that nobody holds, and
 *    it says so on itself.
 *  - **A value that cuts to nothing says so.** `<0.0001`, never `0` — zero is a claim, and it
 *    would be false.
 *
 * **Amounts arrive as decimal strings and are held as [BigDecimal], never [Double].** A double
 * cannot represent 0.1, so the moment money touches one the number on screen stops being the
 * number on chain.
 */
object Money {

    /** Decimals per token. Fiat is absent on purpose: its decimals come from the currency. */
    // A memecoin held by the million shows whole units; JUP, like a dollar, shows cents.
    private val TOKEN_DECIMALS =
        mapOf("SOL" to 4, "USDC" to 2, "USDT" to 2, "EURC" to 2, "JUP" to 2, "SKR" to 0, "BONK" to 0)
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

    /** Are this token's decimals something we actually know, or the 4-place fallback? */
    internal fun isKnown(unit: String): Boolean = unit in TOKEN_DECIMALS

    /**
     * The FEWEST decimals a token shows — which is not the same as the most.
     *
     * A known token pads to its own fixed places, because "2.00 USDC" is how a dollar-like
     * amount is written and "2 USDC" looks like a different number. An unknown mint pads to
     * nothing: showing "25.0000" claims four places of precision about a token whose decimals
     * we never looked up. It shows what it has, up to four places, and no padding zeros.
     */
    internal fun minDecimalsFor(unit: String): Int = if (isKnown(unit)) decimalsFor(unit) else 0

    /**
     * A token amount with no code: "0.0255", "12,34,567.8912", "<0.0001".
     *
     * A known token keeps its fixed decimals ("2.00 USDC"); an unknown mint shows what it has
     * with no padding zeros ("25", not "25.0000") — see [minDecimalsFor].
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
            // Min and max apart, so an unknown mint drops its padding zeros while a known
            // token keeps them. For a known token the two are equal and this is fixedFraction.
            .precision(Precision.minMaxFraction(minDecimalsFor(unit), d))
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
     * A fiat value in the person's currency, split for display.
     *
     * The balance sets each run at its own size — the figures at 60, the symbol at 30, the cents
     * at 26 — and it cannot do that from one string. The split is taken back OUT of a formatted
     * figure rather than assembled by hand, so the locale still decides which side the symbol
     * goes on, how the digits group and what the decimal mark is.
     *
     * [symbol] carries every affix, the minus included: it is one run on screen, so a negative
     * value reads "−$3.09" with the sign and the symbol at the same size rather than a hyphen
     * orphaned at the figures' size.
     *
     * Dust is folded in here rather than left to the caller. `$0.00` on a balance that holds
     * something is the false claim this whole file exists to stop, and a caller that has to
     * remember to ask [cutsToZero] first will one day forget — the sandbox's version did, and
     * rendered exactly that.
     */
    data class FiatParts(
        /** The currency symbol with any sign, e.g. "$", "€", "<$", "−$". */
        val symbol: String,
        /** Whether it goes before the figures. "$3.09" is true; "2,84 €" is false. */
        val symbolFirst: Boolean,
        /** The whole part, grouped: "12,34,567". */
        val whole: String,
        /** The decimal mark and the digits after it: ".09", ",84" — empty for a whole currency. */
        val fraction: String,
    )

    fun fiatParts(
        value: BigDecimal,
        currency: String,
        locale: Locale = Locale.getDefault(),
    ): FiatParts {
        val cur = Currency.getInstance(currency)
        val d = cur.defaultFractionDigits.coerceAtLeast(0)
        val dust = cutsToZero(value, d)
        // Dust shows the smallest step it COULD have shown, marked "<". Formatting the real
        // value would give 0.00, which is the claim being avoided.
        val shown = if (dust) BigDecimal.ONE.movePointLeft(d) else value.setScale(d, RoundingMode.DOWN)
        val text = NumberFormatter.withLocale(locale)
            .unit(cur)
            .precision(Precision.fixedFraction(d))
            .roundingMode(RoundingMode.DOWN)
            .format(shown)
            .toString()

        // The digits locate themselves. Anything outside the first and last of them is affix —
        // which is true of "$3.09", "2,84 €" and "US$ 3.09" alike, and needs no table of
        // symbols per locale and no second format to diff against.
        val first = text.indexOfFirst { it.isDigit() }
        val last = text.indexOfLast { it.isDigit() }
        if (first < 0) return FiatParts("", true, text, "")
        val before = text.substring(0, first)
        val after = text.substring(last + 1)
        val number = text.substring(first, last + 1)

        val mark = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val at = number.lastIndexOf(mark)
        val whole = if (at < 0) number else number.substring(0, at)
        val fraction = if (at < 0) "" else number.substring(at)

        val symbolFirst = before.isNotBlank() || after.isBlank()
        val affix = (if (symbolFirst) before else after).trim()
        return FiatParts(
            symbol = if (dust) (if (symbolFirst) "<$affix" else affix) else affix,
            symbolFirst = symbolFirst,
            // With the symbol trailing there is nowhere in front of it to put the "<", so it
            // goes in front of the figures: "<2,84 €".
            whole = if (dust && !symbolFirst) "<$whole" else whole,
            fraction = fraction,
        )
    }

    /**
     * What ONE of a token costs: "$120.49", "$0.50", "$0.9997", "$0.01764", "$0.000003736".
     *
     * A price is not a balance. From a dollar up it is money like any other — cents, cut. Under
     * a dollar a fixed number of decimals fails both ways: two cut SKR's $0.0176 to $0.01, which
     * is off by half, and even four turn BONK's $0.0000037 into "<$0.0001", true and useless. So
     * under a dollar it keeps four SIGNIFICANT figures however small the price, and every token
     * gets the same resolution. Still cut, never rounded: a price that reads high is a price
     * somebody acts on. A price of exactly 0 is "$0.00" — nothing to resolve, so it is not dust.
     *
     * **The rule in full:** under a dollar, at most [PRICE_FIGURES] significant figures, cut; the
     * zeros past the last real figure are dropped, but never below [PRICE_MIN_DECIMALS] decimals.
     * That floor is what makes 0.5 "$0.50" and not "$0.5" — one decimal on a price reads
     * unfinished, like a price tag somebody stopped typing. 0.004 keeps its own three ("$0.004"):
     * the floor pads a short price up, it does not pad every price out to four figures.
     *
     * **The two bounds are in different units, so one of them has to be arithmetic rather than a
     * formatter setting.** They cannot actually contradict each other: four significant figures of
     * a value below a dollar always reach the fourth decimal or deeper, so the floor can only ever
     * ADD zeros to a figure that has fewer than two decimals of its own, and a padding zero never
     * creates a fifth significant figure. Where they DO collide is inside the formatter — handed a
     * significant-figure maximum and a decimal minimum at once, ICU lets the significant figures
     * win and drops the minimum, which is exactly how "$0.5" reached the screen. So the decimal
     * count is settled here by [priceScale] and ICU is handed one fixed number of decimals, with
     * nothing left for it to arbitrate.
     */
    fun unitPrice(
        value: BigDecimal,
        currency: String,
        locale: Locale = Locale.getDefault(),
    ): String {
        if (priceDecimals(value) == 2) return fiat(value, currency, locale)
        if (value.signum() == 0) return fiat(BigDecimal.ZERO, currency, locale)
        val cur = Currency.getInstance(currency)
        return NumberFormatter.withLocale(locale)
            .unit(cur)
            // Cut to four figures first so the number ICU is handed is already the number to
            // show, and hand it the decimal count [priceScale] already worked out. Fixed, not a
            // min/max pair: that is what keeps the padding of "$0.50" and re-rounds nothing.
            .precision(Precision.fixedFraction(priceScale(value)))
            .roundingMode(RoundingMode.DOWN)
            .format(significant(value, PRICE_FIGURES))
            .toString()
            .replace("-", MINUS)
    }

    /** Significant figures a sub-dollar unit price keeps. Internal so a test uses the real one. */
    internal const val PRICE_FIGURES = 4

    /** The FEWEST decimals a sub-dollar unit price shows, so 0.5 is "$0.50" and never "$0.5". */
    internal const val PRICE_MIN_DECIMALS = 2

    /**
     * How many decimals a sub-dollar price prints: as many as [PRICE_FIGURES] significant figures
     * need, with nothing padded past the last real figure, but never fewer than
     * [PRICE_MIN_DECIMALS] — the one place where "at most four figures" and "at least two
     * decimals" are reconciled, deliberately, instead of being left to the formatter.
     *
     * Read off the ALREADY-CUT figure rather than the raw value, the same discipline [priceTip]
     * needs for its zero count. Internal because this is the half of [unitPrice] a JVM test can
     * hold — the formatting is ICU and only a device can check that.
     */
    internal fun priceScale(value: BigDecimal): Int =
        significant(value, PRICE_FIGURES).stripTrailingZeros().scale()
            .coerceAtLeast(PRICE_MIN_DECIMALS)

    /**
     * Where a unit PRICE's resolution comes from: cents at a dollar and above, four significant
     * figures below.
     *
     * Returns the decimals for the first case only — below a dollar there is no ONE number of
     * decimals any more (it comes per value from [priceScale]), and 4 here reads as "not the cents
     * branch". Split out so the rule is testable without ICU, which is the whole reason [cut] and
     * [cutsToZero] are internal too.
     * The rule is the load-bearing part: at two decimals a $0.0195 token prices at $0.01, which
     * is off by half, and every sub-cent token on the screen prices at $0.00.
     */
    internal fun priceDecimals(value: BigDecimal): Int =
        if (value.abs() >= BigDecimal.ONE) 2 else 4

    /**
     * [value] cut — never rounded up — to [figures] significant figures: 0.000003736 stays
     * 0.000003736, 0.9997123 becomes 0.9997, 0.01764999 becomes 0.01764.
     *
     * The decision half of [unitPrice], so a JVM test can hold it: the formatting is ICU and
     * only a device can check that, but the digits that are kept and the digits that are thrown
     * away are plain arithmetic.
     */
    internal fun significant(value: BigDecimal, figures: Int): BigDecimal =
        value.round(MathContext(figures, RoundingMode.DOWN))

    /**
     * The figure [unitPrice] will show, with "." for the decimal mark and no currency symbol:
     * "120.49", "0.50", "0.9997", "0.000003736". Same split as [plainTip] — the symbol, the side
     * it sits on and the locale's own decimal mark are ICU and are checked on the phone, the
     * digits are here. Written for a two-decimal currency, since the cents branch takes its
     * decimals from the currency and that part is ICU's.
     */
    internal fun plainPrice(value: BigDecimal): String {
        if (priceDecimals(value) == 2 || value.signum() == 0) {
            return value.setScale(PRICE_MIN_DECIMALS, RoundingMode.DOWN).toPlainString()
        }
        return significant(value, PRICE_FIGURES)
            .setScale(priceScale(value), RoundingMode.DOWN)
            .toPlainString()
    }

    /** Subscript digits, for the count of zeros in a tiny price: 0.0₅37. */
    private const val SUB = "₀₁₂₃₄₅₆₇₈₉"

    /** Significant figures a sub-dollar price LABEL keeps. */
    private const val LABEL_FIGURES = 2

    /** From this many leading zeros, a price label collapses them into a subscript count. */
    private const val TIP_SUBSCRIPT_FROM = 4

    /**
     * A price as a LABEL — the live figure on a chart's price tip. Short over precise, because
     * it rides a moving line and is read in a glance:
     *
     *  - a dollar and up: the whole dollar — "$117" for 117.4.
     *  - under a dollar: two significant figures — 0.9932 → "$0.99", 0.323 → "$0.32",
     *    0.01771 → "$0.018".
     *  - four or more zeros after the point: the zeros collapse into a subscript count, the way
     *    DEX screeners write it — 0.000003747 → "$0.0₅37", five zeros and then 37.
     *
     * **This one ROUNDS to the closest, and it is the only thing in this file that does.** The
     * "cut, never round up" rule at the top of this file protects a number that says what you
     * HOLD: rounding a balance up claims money that is not there. A price tip is a reference
     * price nobody holds — nothing is claimed by it — so the closest figure is the honest one,
     * and cutting would only make the label wrong in the other direction. Balances, worth and
     * amounts still go through [token] and [fiat] and are still cut. Do not reach for this one
     * because it looks tidier.
     *
     * [value] is a [BigDecimal] like everything else here. A price arriving as a `Double` from
     * a feed is converted at the edge with `BigDecimal(d.toString())`, not here — the moment
     * money touches a double the number on screen stops being the number on chain.
     */
    fun priceLabel(
        value: BigDecimal,
        currency: String = "USD",
        locale: Locale = Locale.getDefault(),
    ): String {
        val cur = Currency.getInstance(currency)
        return when (val tip = priceTip(value)) {
            is PriceTip.Whole -> NumberFormatter.withLocale(locale)
                .unit(cur)
                .precision(Precision.maxFraction(0))
                .roundingMode(RoundingMode.HALF_UP)
                .format(tip.value)
                .toString()

            is PriceTip.Near -> NumberFormatter.withLocale(locale)
                .unit(cur)
                .precision(Precision.maxSignificantDigits(LABEL_FIGURES))
                .roundingMode(RoundingMode.HALF_UP)
                .format(tip.value)
                .toString()

            // No formatter writes "0.0₅37", so this figure is assembled by hand — but the
            // symbol, the side it sits on and the decimal mark are still read back out of ICU
            // rather than guessed, which is the same trick [fiatParts] uses.
            is PriceTip.Tiny -> {
                val probe = NumberFormatter.withLocale(locale)
                    .unit(cur)
                    .precision(Precision.fixedFraction(1))
                    .format(BigDecimal("0.1"))
                    .toString()
                val first = probe.indexOfFirst { it.isDigit() }
                val last = probe.indexOfLast { it.isDigit() }
                val before = if (first < 0) "" else probe.substring(0, first)
                val after = if (last < 0) "" else probe.substring(last + 1)
                val symbolFirst = before.isNotBlank() || after.isBlank()
                val affix = (if (symbolFirst) before else after).trim()
                val mark = DecimalFormatSymbols.getInstance(locale).decimalSeparator
                val body = "0$mark" + "0" + subscript(tip.zeros) + tip.digits
                if (symbolFirst) "$affix$body" else "$body$NBSP$affix"
            }
        }.replace("-", MINUS)
    }

    /**
     * What [priceLabel] shows, decided before any locale touches it.
     *
     * The decision and the formatting are split because only the decision can be tested off a
     * device: `android.icu` is an Android framework class with nothing on the unit-test
     * classpath, so the symbol, its side and the grouping are checked on the phone, while how
     * many figures survive and how many zeros collapse are checked here.
     */
    internal sealed interface PriceTip {
        /** Whole dollars, grouped by the locale: 117.4 → 117. */
        data class Whole(val value: BigDecimal) : PriceTip

        /** Two significant figures: 0.9932 → 0.99, 0.01771 → 0.018. */
        data class Near(val value: BigDecimal) : PriceTip

        /** "0.0", a subscript count of zeros, then two digits: 0.000003747 → 0.0₅37. */
        data class Tiny(val zeros: Int, val digits: String) : PriceTip
    }

    internal fun priceTip(value: BigDecimal): PriceTip {
        // Rounded, not cut — see [priceLabel]. Zero takes this branch too: there is nothing to
        // resolve in it, so it is "$0" rather than a count of zeros.
        if (value.abs() >= BigDecimal.ONE || value.signum() == 0) {
            return PriceTip.Whole(value.setScale(0, RoundingMode.HALF_UP))
        }
        val near = value.round(MathContext(LABEL_FIGURES, RoundingMode.HALF_UP))
        // Rounding can cross the dollar on the way up: 0.996 rounds to 1.0, which reads "$1".
        if (near.abs() >= BigDecimal.ONE) {
            return PriceTip.Whole(near.setScale(0, RoundingMode.HALF_UP))
        }
        // A price is never negative, and the subscript form has nowhere to put a sign, so the
        // zero count and the digits are taken off the magnitude.
        val stripped = near.abs().stripTrailingZeros()
        // The zeros between the point and the first significant digit, counted without going
        // anywhere near a double: 3.7E−6 has scale 7 and precision 2, and 7 − 2 is the five
        // zeros of 0.0000037.
        val zeros = stripped.scale() - stripped.precision()
        if (zeros < TIP_SUBSCRIPT_FROM) return PriceTip.Near(near)
        // The rounding already happened, so a single digit here means 0.000010, whose two
        // digits are "10" — not "1" with a zero count one too high.
        val digits = stripped.unscaledValue().toString()
            .padEnd(LABEL_FIGURES, '0')
            .take(LABEL_FIGURES)
        return PriceTip.Tiny(zeros, digits)
    }

    /** 5 → "₅". */
    internal fun subscript(n: Int): String =
        n.toString().map { SUB[it.digitToInt()] }.joinToString("")

    /**
     * The figure [priceLabel] will show, with "." for the decimal mark and no currency symbol:
     * "117", "0.99", "0.018", "0.0₅37". The symbol, which side it goes on and the locale's own
     * decimal mark are ICU and are checked on the phone; everything else is here.
     */
    internal fun plainTip(value: BigDecimal): String = when (val tip = priceTip(value)) {
        is PriceTip.Whole -> tip.value.toPlainString()
        is PriceTip.Near -> tip.value.toPlainString()
        is PriceTip.Tiny -> "0.0" + subscript(tip.zeros) + tip.digits
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

    /**
     * The digits [tokenAmount] will show, before a locale groups them: "0.0255", "2.00", "25".
     *
     * [cut] answers how far the number is cut; this answers what is then printed, which is the
     * other half of the same decision — a known token's padding zeros are kept and an unknown
     * mint's are dropped. Same reason it is internal: the grouping is ICU and belongs on a
     * device, the digits do not.
     */
    internal fun plain(value: BigDecimal, unit: String): String {
        val stripped = cut(value, unit).stripTrailingZeros()
        val min = minDecimalsFor(unit)
        // stripTrailingZeros can go past the decimal point ("25.0000" → 2.5E+1, scale −1), so
        // the scale is pulled back up to the minimum rather than left negative.
        return (if (stripped.scale() < min) stripped.setScale(min) else stripped).toPlainString()
    }

    /** The smallest step a unit can show, e.g. "0.0001". */
    private fun smallest(d: Int, locale: Locale): String =
        NumberFormatter.withLocale(locale)
            .precision(Precision.fixedFraction(d))
            .format(BigDecimal.ONE.movePointLeft(d))
            .toString()
}
