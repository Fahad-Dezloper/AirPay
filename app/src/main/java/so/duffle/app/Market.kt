package so.duffle.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * What a token is worth, and whether it is a real one.
 *
 * Two questions that turned out to be the same question. The app used to answer the first by
 * hand — SOL priced live, a short list of dollar stablecoins pinned at 1.00, everything else
 * null — which meant a token with a perfectly good market showed "No market price" purely
 * because nobody had added it to the list. SKR is exactly that: a real token, real liquidity,
 * and no dollar figure on screen.
 *
 * It could not answer the second at all, so anything airdropped into the wallet sat in the list
 * beside real holdings. Spam arrives constantly on Solana and is indistinguishable from the
 * genuine article when all you draw is a symbol and a balance.
 *
 * Jupiter answers both in one call. For a list of mints it returns each one's `usdPrice` and
 * its `isVerified` flag, so pricing and filtering come from the same place and cannot disagree.
 *
 * **Verification is Jupiter's, not ours.** Their verified set is the one the Solana ecosystem
 * actually uses for swaps, which is a stronger claim than anything this app could compute from
 * a mint account — and a much weaker one than "safe". It means the token is the well-known one
 * of that name, not that holding it is a good idea.
 *
 * **The price now comes from [Pyth], not from here.** Jupiter is still the source of the icon and
 * the verified flag — the same call, unchanged — and still the source of a *fallback* price, which
 * is what a mint with no Pyth feed is priced at. [Pyth.choose] is where the order between the two
 * is decided, in one place, and [price] is the only caller of it.
 */
object Market {

    private const val TAG = "Market"
    private const val SEARCH = "https://lite-api.jup.ag/tokens/v2/search?query="

    /** Wrapped SOL, which is how the market prices native SOL. */
    const val WSOL = "So11111111111111111111111111111111111111112"

    /**
     * @param verified Jupiter's own flag. False for anything it has never heard of, which is
     *   the normal state of an airdropped token.
     * @param usdPrice dollars for one whole token, or null when it has no market.
     * @param icon the token's own logo URL, or null when Jupiter has none. The app's own
     *   on-chain metadata read ([Tokens.Holding.image]) is the fallback, not this.
     */
    data class Info(val verified: Boolean, val usdPrice: Double?, val icon: String? = null)

    /**
     * Ten seconds, down from five minutes.
     *
     * A price used to be a label under a balance and a stale one was invisible. It is now the
     * end of a line and the figure in the chart's price tip (§3.5), and a tip that has not moved
     * in five minutes beside a line that claims to end at it is the two of them disagreeing.
     * This is one request for every mint held, which is what makes polling it affordable.
     */
    private const val TTL_MS = 10_000L
    private var cache: Pair<Long, Map<String, Info>> = 0L to emptyMap()

    /**
     * Mints Jupiter has successfully been **asked** about, whether or not it knew them.
     *
     * This is what separates "Jupiter answered and has never heard of this mint" from "nobody
     * answered". Both leave the mint absent from [cache] — Jupiter omits unknown mints from its
     * response entirely rather than returning a null-priced entry (verified against the live
     * endpoint) — so absence alone cannot tell them apart, and rendering the second as the first
     * would print "No market price" under a token that has one.
     *
     * It is never cleared. "There is no market for this mint" is a last-good answer like any
     * other, and keeping it is the §3.6 rule.
     */
    private val answered = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Looks up every mint at once.
     *
     * One request rather than one per token: a wallet holding a dozen things would otherwise
     * make a dozen calls on every refresh, and the endpoint takes a comma-separated list.
     *
     * Returns an empty map when it cannot be reached — never a map of nulls. The difference
     * matters upstream: "no answer" must not be allowed to look like "these are all worthless
     * and unverified", which would empty the token list over a dropped connection.
     */
    suspend fun of(mints: Collection<String>): Map<String, Info> {
        if (mints.isEmpty()) return emptyMap()
        val wanted = mints.toSortedSet()
        val (at, held) = cache
        // Against [answered], not against the cache's keys: a mint Jupiter does not know is
        // never a key, so keying the check on `held` re-asked on every single call for any
        // wallet holding one unlisted token.
        if (System.currentTimeMillis() - at < TTL_MS && answered.containsAll(wanted)) return held

        val fresh = withContext(Dispatchers.IO) { fetch(wanted) } ?: return held
        // Asked and answered — including about the mints it did not return. See [answered].
        answered.addAll(wanted)
        // Merged, not replaced: a second wallet with different tokens should not evict the first.
        val merged = held + fresh
        cache = System.currentTimeMillis() to merged
        return merged
    }

    private fun fetch(mints: Set<String>): Map<String, Info>? = try {
        val c = (URL(SEARCH + mints.joinToString(",")).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
        }
        if (c.responseCode != 200) null else {
            val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.text("id") ?: continue
                    put(
                        id,
                        Info(
                            // `text`, not `optString`: Android stringifies a JSON null into the
                            // four characters "null", which has reached the screen twice. See Json.kt.
                            icon = o.text("icon"),
                            verified = o.optBoolean("isVerified", false),
                            // A price of zero is not a price. It comes back for tokens whose
                            // pool has been drained, and showing "$0.00" beside a balance reads
                            // as a statement rather than the absence of one.
                            usdPrice = o.optDouble("usdPrice", 0.0).takeIf { it > 0 },
                        ),
                    )
                }
            }
        }
    } catch (e: Exception) {
        Log.i(TAG, "no market data: ${e.message}")
        null
    }

    // ------------------------------------------------------------ what a card needs, per mint

    /**
     * A token's dollar price, with **"we could not ask" kept apart from "there is no market".**
     *
     * The two render differently (§3.3: only a real negative says "No market price") and this is
     * the project's standing rule — [Genesis] fails closed the same way, showing no badge on a
     * network error rather than a guessed one. A single nullable `Double` cannot carry the
     * difference, which is why this type exists instead of one.
     */
    sealed interface Price {
        /** Jupiter answered with a usable figure. */
        data class Usd(val usd: Double) : Price

        /** Jupiter answered, and this mint has no market. The honest negative. */
        data object NoMarket : Price

        /** Nobody answered. **Never render this as [NoMarket].** */
        data object Unknown : Price
    }

    /**
     * Everything one token card needs, for one mint.
     *
     * @param verified Jupiter's verified flag, or **null when it could not be asked** — not
     *   false, which would state that a real token is unrecognised over a dropped connection.
     * @param icon the token's logo URL, or null when there is none to show. Absence of a logo is
     *   not a claim about anything, so it needs no third state.
     */
    data class Quote(
        val price: Price,
        val icon: String?,
        val verified: Boolean?,
        val day: MarketDay.Day,
    ) {
        /** The figure, or null. For arithmetic — worth, totals — where absence is just absence. */
        val usd: Double? get() = (price as? Price.Usd)?.usd

        /** True only for a real negative answer: the one case §3.3 writes "No market price" for. */
        val noMarket: Boolean get() = price is Price.NoMarket

        /** The line to draw, already ending at the live price, or null when there is none. */
        val series: List<Double>? get() = (day as? MarketDay.Day.Points)?.usd
    }

    /**
     * Reads one mint's price. Pure, and unit-tested in `MarketTest` and `PythTest`.
     *
     * The figure itself is [Pyth.choose]'s decision — Pyth first, Jupiter second, a stale Pyth
     * reading third. What is decided *here* is the thing that figure's absence means, and that is
     * still Jupiter's to say: Jupiter is the only one of the two sources that knows the difference
     * between a mint with no market and a mint nobody asked about. Pyth has no feed for most mints
     * in existence and never claims to, so "Pyth has nothing" is not evidence of anything.
     *
     * @param info Jupiter's entry for the mint, or null when it returned none.
     * @param asked whether Jupiter successfully answered a request covering this mint.
     * @param pyth Pyth's current reading for the mint, or null when it has none — no feed, no
     *   grant, no key, or nobody answered.
     */
    fun price(
        info: Info?,
        asked: Boolean,
        pyth: Pyth.Spot? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Price {
        val usd = Pyth.choose(pyth, info?.usdPrice, nowMs)
        return when {
            usd != null -> Price.Usd(usd)
            info != null || asked -> Price.NoMarket
            else -> Price.Unknown
        }
    }

    /** Whether Jupiter has ever successfully answered about this mint. */
    fun asked(mint: String): Boolean = mint in answered

    /**
     * The whole market answer for every mint held: Pyth and Jupiter together, then each mint's day.
     *
     * This is the entry point for the token cards, and its shape is unchanged — three sources now
     * feed it rather than two. The two live calls run **concurrently**, because they answer
     * different questions and neither needs the other: Pyth gives the price, Jupiter gives the
     * icon, the verified flag and the fallback price. Running them in series would double the
     * latency of a ten-second poll for nothing.
     *
     * Both are allowed to fail independently. Pyth failing leaves the Jupiter price, which is what
     * the app showed before Pyth existed; Jupiter failing leaves the Pyth price with no icon and
     * `verified = null`; both failing leaves [Price.Unknown], never [Price.NoMarket].
     *
     * The days then run concurrently too and are almost always cache hits — [MarketDay] keeps each
     * for five minutes. The live price is appended to each day as its last point, so the line's
     * end and the price tip read the same figure. A mint neither source could be asked about still
     * gets its day: a cached line is last-good data and is not thrown away because something else
     * failed.
     *
     * Native SOL is asked about as [WSOL], exactly as callers already do for [of].
     */
    suspend fun quotes(mints: Collection<String>): Map<String, Quote> {
        if (mints.isEmpty()) return emptyMap()
        val wanted = mints.toSet()
        val (info, spots) = coroutineScope {
            val jup = async { of(wanted) }
            val pyth = async { Pyth.of(wanted) }
            jup.await() to pyth.await()
        }
        val now = System.currentTimeMillis()
        return coroutineScope {
            wanted.map { mint ->
                async {
                    val p = price(info[mint], asked(mint), spots[mint], now)
                    mint to Quote(
                        price = p,
                        icon = info[mint]?.icon,
                        verified = if (asked(mint)) (info[mint]?.verified ?: false) else null,
                        day = MarketDay.of(mint, (p as? Price.Usd)?.usd),
                    )
                }
            }.map { it.await() }.toMap()
        }
    }
}
