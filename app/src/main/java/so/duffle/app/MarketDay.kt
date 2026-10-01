package so.duffle.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A day of a token's price — the line on the token card.
 *
 * [Market] answers what a token is worth *now*, in one call for every mint held. This answers
 * what it was worth over the last 24 hours, which is a different question with a different
 * source and a very different rate limit: CoinGecko's free API allows a handful of calls a
 * minute, so a day is kept for five minutes in memory. That costs nothing in accuracy — a day's
 * *shape* does not change between two polls ten seconds apart, only its last point does, and the
 * last point is the live price, appended on every read by [withLive].
 *
 * **Three answers, not two.** A chart has to distinguish "this token has no market, so there is
 * no line to draw" from "we could not ask, so we do not know whether there is a line" — §3.5
 * draws them differently (an empty band against a line, and the content panel only says
 * "No market price" for the first). Collapsing both into `null` is the bug this type exists to
 * prevent: it would state, over a dropped connection, that a token with a perfectly good market
 * has none. Same shape as [Genesis], which refuses to guess a badge off a network error.
 *
 * ### The two halves
 *
 * Everything above [of] is pure: the mint → source mapping, parsing, thinning, appending the
 * live point, and deciding whether a cache entry has expired. All of it is unit-tested in
 * `MarketTest` without a network. [of] and [fetch] are the network half and are not testable
 * here; they contain no decisions beyond which pure function to hand the body to.
 */
object MarketDay {

    private const val TAG = "MarketDay"
    private const val HOST = "https://api.coingecko.com/api/v3"

    /** How long a fetched day is good for. The free API allows only a few calls a minute. */
    const val TTL_MS = 5 * 60_000L

    /** Roughly how many points a 140dp-wide band can show. Four hundred would be four hundred nobody sees. */
    const val WANT_POINTS = 48

    /**
     * A day of prices, or the honest absence of one.
     *
     * [None] and [Unknown] are both "no line", and they are deliberately not the same value.
     * Render [None] as the empty band §3.5 describes; render [Unknown] as not-yet-known — never
     * as "No market price", which is a claim nobody made.
     */
    sealed interface Day {
        /** USD, oldest first. At least two points, or it would not be a line. */
        data class Points(val usd: List<Double>) : Day

        /** Asked, and there is no day to be had: no listing, or no history for it. A real answer. */
        data object None : Day

        /** Nobody answered, and there is no earlier answer to fall back on. Not a negative. */
        data object Unknown : Day
    }

    // ---------------------------------------------------------------- the pure half

    /**
     * Where to ask for this mint's day — **the seam for the open question.**
     *
     * CoinGecko keys its history by its own coin id, not by mint, and §3.6/§7 leave the general
     * mint → id lookup undecided, so this is a hand-written map of the five tokens the sandbox
     * shows. An unmapped mint is [Day.None] and never a guess: the alternative, picking a coin id
     * by matching a symbol, would eventually draw one token's day under another token's name,
     * which is worse than drawing nothing.
     *
     * The return value is a **path segment**, not an id, so a general source drops in here
     * without any caller changing. `coins/solana/contract/<mint>/market_chart` was verified to
     * answer keylessly for all five of these mints — see the report in the handover; it is left
     * unwired on purpose, because it costs one CoinGecko call per *unlisted* mint against a free
     * limit of a few a minute, and that trade is the user's to make, not this file's.
     */
    fun geckoPath(mint: String): String? = GECKO_IDS[mint]?.let { "coins/$it" }

    private val GECKO_IDS = mapOf(
        // Wrapped SOL: native SOL is asked about under this mint, as Market.WSOL says.
        Market.WSOL to "solana",
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to "usd-coin",
        "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN" to "jupiter-exchange-solana",
        "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263" to "bonk",
        "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3" to "seeker",
    )

    /** Whether a cache entry taken at [at] has expired by [now]. */
    fun stale(at: Long, now: Long, ttl: Long = TTL_MS): Boolean = now - at >= ttl

    /**
     * Reads a `market_chart` body.
     *
     * **A 200 is not a success.** CoinGecko hands back its rate-limit refusal as a JSON body
     * with a `status.error_code` inside it, and this codebase has already been bitten once by an
     * error payload arriving with an HTTP 200 (the `processed`-commitment RPC failure). A refusal
     * is [Day.Unknown] — we could not ask — and must not be mistaken for an empty market.
     *
     * - a refusal, or a body with no `prices` array at all → [Day.Unknown]
     * - a `prices` array with fewer than two usable points → [Day.None]; CoinGecko answered, and
     *   there is nothing to draw
     * - otherwise → [Day.Points], thinned to about [WANT_POINTS]
     */
    fun parse(body: String, want: Int = WANT_POINTS): Day {
        val root = try { JSONObject(body) } catch (e: Exception) { return Day.Unknown }
        // No logging in here on purpose: the pure half runs on the JVM under test, where
        // android.util.Log is unmocked and throws. The caller logs.
        if (root.has("error") || root.optJSONObject("status")?.has("error_code") == true) {
            return Day.Unknown
        }
        val arr = root.optJSONArray("prices") ?: return Day.Unknown
        val prices = ArrayList<Double>(arr.length())
        for (i in 0 until arr.length()) {
            // [ millis, usd ]. A zero or a missing second element is not a price.
            val usd = arr.optJSONArray(i)?.optDouble(1, 0.0) ?: 0.0
            if (usd > 0 && !usd.isNaN()) prices.add(usd)
        }
        return if (prices.size < 2) Day.None else Day.Points(thin(prices, want))
    }

    /**
     * Every nth point, keeping the first.
     *
     * The same step the sandbox's route uses, so the two draw the same line: `days=1` came back
     * with 486 points for SKR and 401 for the by-contract route, which thin to 49 and 41. The
     * tail can be dropped by the step — the day's final CoinGecko point is up to half an hour
     * old anyway, and [withLive] puts the real last point back.
     */
    fun thin(prices: List<Double>, want: Int = WANT_POINTS): List<Double> {
        if (want <= 0 || prices.size <= want) return prices
        val step = maxOf(1, prices.size / want)
        return prices.filterIndexed { i, _ -> i % step == 0 }
    }

    /**
     * Appends the live price as the day's last point, so the line's end and the price tip agree.
     *
     * Without this the tip reads one figure and the line ends at another, up to half an hour
     * stale, and the two visibly disagree on a card 200dp wide. A null live price appends
     * nothing: a day with no live point is still a true day.
     */
    fun withLive(day: List<Double>, liveUsd: Double?): List<Double> =
        if (liveUsd == null || liveUsd <= 0 || liveUsd.isNaN()) day else day + liveUsd

    /** [withLive] over a whole [Day]. [Day.None] and [Day.Unknown] stay exactly what they were. */
    fun withLive(day: Day, liveUsd: Double?): Day =
        if (day is Day.Points) Day.Points(withLive(day.usd, liveUsd)) else day

    // ---------------------------------------------------------------- the network half

    private val cache = HashMap<String, Pair<Long, Day>>()

    // ---------------------------------------------------------------- the day on disk

    private const val STORE = "market_day"

    private var store: SharedPreferences? = null

    /**
     * Give the day-cache somewhere to survive a cold start. Idempotent; safe to never call.
     *
     * Without this the cache is memory only, so every launch re-asked CoinGecko for a day per
     * token held. On the free tier that is a few calls a minute between every token in every
     * wallet, and the charts simply vanished once it started answering 429 — the token row
     * fell back to a bare price with no line. A day of prices is a day old by definition;
     * there is no reason for it not to outlive the process.
     *
     * Unattached (unit tests, or before `onCreate` runs) everything below degrades to the
     * in-memory behaviour rather than failing.
     */
    fun attach(context: Context) {
        if (store == null) store = context.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    }

    /**
     * A day as one line of JSON, or null for an answer not worth keeping.
     *
     * [Day.Unknown] is never written: it means "we could not ask", and persisting that would
     * turn a dropped connection into a fact the next launch believes. [Day.None] IS written,
     * because "this mint has no history" is a real answer and re-asking it every launch is the
     * waste this cache exists to stop.
     */
    internal fun encode(at: Long, day: Day): String? = when (day) {
        is Day.Points -> JSONObject()
            .put("at", at)
            .put("p", JSONArray().also { a -> day.usd.forEach { a.put(it) } })
            .toString()
        Day.None -> JSONObject().put("at", at).put("none", true).toString()
        Day.Unknown -> null
    }

    /** The inverse. Null for anything unreadable — a corrupt entry is simply not an answer. */
    internal fun decode(raw: String): Pair<Long, Day>? = try {
        val o = JSONObject(raw)
        val at = o.getLong("at")
        when {
            o.optBoolean("none") -> at to Day.None
            else -> {
                val a = o.getJSONArray("p")
                val points = (0 until a.length()).map { a.getDouble(it) }
                // Fewer than two points is not a line, and `parse` would have refused it too.
                if (points.size < 2) null else at to Day.Points(points)
            }
        }
    } catch (e: Exception) {
        null
    }

    /** What disk remembers for this mint, newest answer wins over an empty memory cache. */
    private fun remembered(mint: String): Pair<Long, Day>? =
        store?.getString(mint, null)?.let(::decode)

    private fun remember(mint: String, at: Long, day: Day) {
        val line = encode(at, day) ?: return
        store?.edit()?.putString(mint, line)?.apply()
    }

    /**
     * This mint's day, with [liveUsd] appended as its last point when there is one.
     *
     * Kept for [TTL_MS]. **On a failed fetch the last good day is returned** rather than blanked:
     * a line that stops extending is better than a line that disappears, and the only thing a
     * failure tells us is that we could not ask. With no earlier answer to keep, that is
     * [Day.Unknown].
     *
     * A real negative answer — an unmapped mint, or a listing with no history — is cached like
     * any other answer, so a token with no line does not re-ask every five minutes forever.
     */
    suspend fun of(mint: String, liveUsd: Double? = null): Day {
        val path = geckoPath(mint) ?: return Day.None
        // Memory first, then disk. A cold start has an empty memory cache, and going to disk
        // before the network is the whole point: a day that is minutes old is still a day.
        val held = synchronized(cache) { cache[mint] }
            ?: remembered(mint)?.also { synchronized(cache) { cache[mint] = it } }
        if (held != null && !stale(held.first, System.currentTimeMillis())) {
            return withLive(held.second, liveUsd)
        }
        val fresh = withContext(Dispatchers.IO) { fetch(path) }
        if (fresh == null || fresh == Day.Unknown) {
            // Could not ask. Keep whatever was last true — which, thanks to disk, now survives
            // a restart during a rate-limit instead of leaving the row with no lines at all.
            return withLive(held?.second ?: Day.Unknown, liveUsd)
        }
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[mint] = now to fresh }
        remember(mint, now, fresh)
        return withLive(fresh, liveUsd)
    }

    /** Null when the request itself failed. [Day.Unknown] when the body was a refusal. */
    private fun fetch(path: String): Day? = try {
        val c = (URL("$HOST/$path/market_chart?vs_currency=usd&days=1")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
        }
        when (val code = c.responseCode) {
            200 -> parse(c.inputStream.bufferedReader().use { it.readText() })
            // 404 is CoinGecko saying it has never heard of this one. That is an answer.
            404 -> Day.None
            else -> {
                Log.i(TAG, "no history: HTTP $code")
                null
            }
        }
    } catch (e: Exception) {
        Log.i(TAG, "no history: ${e.message}")
        null
    }
}
