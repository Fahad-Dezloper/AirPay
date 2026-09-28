package so.duffle.app

/**
 * Pulling the shape of a plan out of the one string the shop gives us for it.
 *
 * Bitrefill sends a bundle as a single line of prose — "2GB/Day 5G Plan – 84 Days" — with the
 * price in a separate field and nothing else broken out. A recharge screen needs those parts
 * side by side, because what somebody compares is validity against data against price, and
 * three columns of numbers are read in a glance where three sentences are not.
 *
 * So this reads the line rather than inventing anything. **Everything here is optional**: when a
 * part cannot be found it is null and the card simply does not draw that column. A guess in this
 * position would be a wrong number sitting beside a real price, which is worse than a gap.
 *
 * The lines really do vary this much, which is why [PlansTest] is built from actual catalogue
 * strings rather than tidy examples:
 *
 *   "2GB/Day 5G Plan – 84 Days"          Jio, the common shape
 *   "30GB – 30 Day Data Pack"            a pot of data rather than a daily allowance
 *   "10GB – 1 Hour Data Pack"            an hour, not a day
 *   "₹39.37 Talktime Top-Up"             no validity and no data at all
 *   "Unlimited voice … 100 SMS/day, 30 Days"   BSNL, a paragraph
 *   "500"                                a plain top-up, where the value is the money
 */
object Plans {

    /**
     * @param validity how long it lasts, as written ("84 days", "1 hour"), or null.
     * @param days the same thing as a number, for the per-day cost. Null when it is not in days.
     * @param data what you get ("2GB/day", "30GB", "Unlimited"), or null.
     * @param fast true when the line says 5G — the one badge worth carrying, because it is the
     *   thing people filter on and it is stated explicitly rather than inferred.
     */
    data class Detail(
        val validity: String?,
        val days: Int?,
        val data: String?,
        val fast: Boolean,
    )

    private val DAYS = Regex("""(\d+)\s*days?\b""", RegexOption.IGNORE_CASE)
    private val HOURS = Regex("""(\d+)\s*hours?\b""", RegexOption.IGNORE_CASE)
    /** "2GB/Day", "1.5 GB/day", "30GB", "100 MB" — the allowance, however it is written. */
    private val DATA = Regex("""(\d+(?:\.\d+)?)\s*(GB|MB|TB)\s*(/\s*day)?""", RegexOption.IGNORE_CASE)

    fun of(line: String): Detail {
        val fast = Regex("""\b5G\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)

        // Days win over hours: "10GB – 1 Hour Data Pack" has no days, and a plan carrying both
        // is quoting its length in days and something else in hours.
        val days = DAYS.find(line)?.groupValues?.get(1)?.toIntOrNull()
        val hours = if (days == null) HOURS.find(line)?.groupValues?.get(1)?.toIntOrNull() else null
        val validity = when {
            days != null -> if (days == 1) "1 day" else "$days days"
            hours != null -> if (hours == 1) "1 hour" else "$hours hours"
            else -> null
        }

        // The FIRST allowance in the line. A plan that mentions data twice is describing a
        // headline and then a caveat ("…reduced to 40 kbps after 2 GB/day"), and the headline
        // is the one being sold.
        val data = DATA.find(line)?.let { m ->
            // Trailing zeros go only AFTER a decimal point: "1.50" is "1.5", but "30" is 30
            // and trimming it unconditionally turned a 30GB pack into a 3GB one.
            val raw = m.groupValues[1]
            val size = if ('.' in raw) raw.trimEnd('0').trimEnd('.') else raw
            val unit = m.groupValues[2].uppercase()
            if (m.groupValues[3].isNotBlank()) "$size$unit/day" else "$size$unit"
        } ?: if (Regex("""\bunlimited\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
            "Unlimited"
        } else null

        return Detail(validity, days, data, fast)
    }

    /**
     * The shelves a plan list can honestly be split into.
     *
     * Every real recharge app has tabs like these and gets them from a plan API that ships the
     * categories with the data. Bitrefill does not: it sends one flat list. So these are
     * **derived from the line itself** — classification, not ranking. "Data" means the line
     * sells data, and that is checkable by reading it.
     *
     * What is deliberately missing is Trending and Popular. Those are not properties of a plan,
     * they are counts of what other people bought, and nobody here has that number. Sorting by
     * something else and calling it trending would be a fact invented on a screen where money
     * leaves. See [MainActivity]'s recharge screen for the one honest substitute: what **you**
     * last bought for this number.
     */
    enum class Shelf(val label: String) {
        All("All"),
        Data("Data"),
        Unlimited("Unlimited"),
        Ott("With OTT"),
        Talktime("Talktime"),
        Fast("5G"),
    }

    /** Streaming and music thrown in with a plan — the half of a bundle people choose on. */
    private val OTT = listOf(
        "hotstar", "netflix", "prime", "sony", "zee", "jiosaavn", "saavn", "swiggy",
        "ott", "fancode", "apps", "jiocinema", "jiotv",
    )

    /** Which shelves a plan belongs on. A plan can sit on several — most do. */
    fun shelves(line: String, detail: Detail = of(line)): Set<Shelf> {
        val l = line.lowercase()
        val out = mutableSetOf(Shelf.All)
        if (detail.fast) out += Shelf.Fast
        if (OTT.any { it in l }) out += Shelf.Ott
        if ("unlimited" in l) out += Shelf.Unlimited
        if (detail.data != null && detail.data != "Unlimited") out += Shelf.Data
        // Money onto the phone rather than an allowance: no data and no unlimited anything.
        if (detail.data == null && ("talktime" in l || "top-up" in l || line.toIntOrNull() != null)) {
            out += Shelf.Talktime
        }
        return out
    }

    /**
     * What it works out to per day, e.g. "₹12.45/day".
     *
     * Only when there is a real number of days to divide by. This is the figure that makes two
     * plans comparable when one is 28 days and the other 84, and a made-up denominator would
     * quietly rank them wrongly.
     */
    fun perDay(amount: Int, days: Int?, symbol: String): String? {
        // Nothing below two days. "₹19.00/day" under a ₹19 one-day pack restates the price in
        // smaller type, which is noise where every other card is using this to be compared.
        if (days == null || days < 2 || amount <= 0) return null
        // Always two places. Rounding to whole rupees above some threshold made ₹10.23 and
        // ₹10.49 print identically, which defeats the one thing this figure is for.
        return "$symbol${"%.2f".format(amount.toDouble() / days)}/day"
    }

    /**
     * Whether a plan matches what somebody typed.
     *
     * Deliberately loose, and matched against the price as well as the words, because the search
     * people actually run is the number they already know: "349". Digits typed on their own are
     * compared to the price as text so "34" finds ₹349, which is how a search box is used while
     * somebody is still typing.
     */
    fun matches(query: String, value: String, amount: Int): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        if (amount > 0 && amount.toString().contains(q)) return true
        return q.split(' ').filter { it.isNotBlank() }.all { value.lowercase().contains(it) }
    }
}
