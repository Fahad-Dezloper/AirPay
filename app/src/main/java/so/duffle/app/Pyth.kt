package so.duffle.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL

/**
 * The live price, from Pyth.
 *
 * [Market] used to take the dollar figure from Jupiter's token search, the same call that gives
 * the icon and the verified flag. Jupiter still gives those two — nothing here replaces them —
 * but the **price** now comes from Pyth, which publishes a first-party oracle price with a
 * publication timestamp and a confidence interval rather than a single number off an aggregator.
 *
 * ### What was read off the live endpoint, not remembered
 *
 * Verified against `pyth.dourolabs.app` on 2026-10-01 with the key in `local.properties`:
 *
 * ```
 * GET https://pyth.dourolabs.app/hermes/v2/updates/price/latest
 *       ?ids[]=<64-hex feed id>&ids[]=…&parsed=true&ignore_invalid_price_ids=true&encoding=base64
 * Authorization: Bearer <key>
 * ```
 * ```json
 * { "binary": { "data": ["504e4155…"], "encoding": "base64" },
 *   "parsed": [ { "id": "ef0d8b6f…b56d",
 *                 "price":     { "price": "11921084818", "conf": "1416736",
 *                                "expo": -8, "publish_time": 1790838394 },
 *                 "ema_price": { … },
 *                 "metadata":  { "slot": …, "prev_publish_time": …, "proof_available_time": … } } ] }
 * ```
 *
 * `price` and `conf` arrive as **decimal strings**, not numbers, and `expo` is the power of ten
 * they are scaled by — `11921084818` with `expo: -8` is $119.21. Getting that wrong does not
 * produce a slightly wrong price, it produces one off by a factor of a hundred million, which is
 * why [scale] goes through [BigDecimal.scaleByPowerOfTen] and never `Math.pow`.
 *
 * `binary` is the signed blob for submitting a price on-chain. Nothing here reads it and it
 * cannot be switched off, so `encoding=base64` is asked for rather than the default `hex`:
 * measured, two feeds came back as 2015 bytes base64 against 2650 hex.
 *
 * ### The key's grant does not cover every feed — and one bad feed kills the whole batch
 *
 * This is the finding that shaped the fallback. A batch asking for SOL, USDC, USDT, JUP, BONK and
 * SKR came back **403 with a plain-text body**, not JSON:
 *
 * ```
 * Not entitled: feed 0a0408d6…0996 (no grant accepts this feed (asset type 'crypto', instrument type 'spot'))
 * ```
 *
 * and *no* prices at all — not a partial answer. Asked one at a time, SOL, USDC and USDT were
 * served; JUP, BONK and SKR were refused on this key. `ignore_invalid_price_ids` does not help:
 * those ids are perfectly valid, they are simply outside the grant.
 *
 * So [fetch] runs a ladder: the refusal names one feed, that feed is dropped into [denied] and
 * the request is retried. Measured, a six-feed ask converged in four requests and every later
 * poll is a single request, because [denied] is remembered. A denied mint then falls back to the
 * Jupiter price that is already on the wire — see [choose].
 *
 * ### The two halves
 *
 * Everything above [of] is pure and unit-tested in `PythTest` without a network: the mint → feed
 * mapping, the exponent, parsing a body, reading the refusal, the stale decision and the
 * Pyth-then-Jupiter choice. [of], [fetch] and [once] are the network half; they hold no decision
 * beyond which pure function to hand a body to. The pure half contains no [Log] calls on purpose
 * — `android.util.Log` is unmocked on the JVM test classpath and throws.
 */
object Pyth {

    private const val TAG = "Pyth"

    /** Hermes, behind the keyed gateway. The keyless `hermes.pyth.network` now answers 401. */
    private const val HOST = "https://pyth.dourolabs.app/hermes"

    /**
     * Sent explicitly rather than left to the platform default.
     *
     * The gateway sits behind Cloudflare, which refuses some user agents outright with a 403
     * `browser_signature_banned` — `Python-urllib` was refused while researching this. A plain
     * Dalvik agent and this one were both served 200, so this is the one that is stated.
     */
    private const val UA = "Duffle/0.1 (Android)"

    /**
     * Whether this build has a Pyth key at all.
     *
     * Same shape as [Push.configured]: a clone with no `pyth.apiKey` in `local.properties`
     * builds, runs, and simply prices from Jupiter as the app did before. Pyth is an upgrade to
     * the price, never a requirement for having one.
     */
    val configured get() = BuildConfig.PYTH_API_KEY.isNotBlank()

    /** Matched to [Market]'s own ten seconds, so one poll costs one Pyth request. */
    const val TTL_MS = 10_000L

    /**
     * How old a Pyth publication may be before it stops being the live price.
     *
     * Observed lag between `publish_time` and the wall clock was **zero seconds** on every
     * reading taken, so a minute is generous by design: it is there to catch a feed that has
     * stopped publishing, not to second-guess normal jitter. Past it the figure is not thrown
     * away — it becomes last-good data, ranked below a live Jupiter price. See [choose].
     */
    const val MAX_AGE_MS = 60_000L

    /**
     * How wide Pyth's own confidence interval may be before its price is treated as a shrug.
     *
     * Pyth publishes `conf` as a ± band around the price, and a band that has blown out is the
     * oracle saying its publishers disagree. Measured bands were tiny — SOL ±0.019% of price,
     * USDC ±0.048% — so five percent is far outside anything normal and only trips on a feed in
     * real trouble, at which point the Jupiter price is the better of the two.
     */
    const val MAX_CONF_RATIO = 0.05

    /** The most requests one [fetch] will spend climbing the [denied] ladder. */
    private const val MAX_ATTEMPTS = 8

    /**
     * One feed's current price.
     *
     * @param usd dollars for one whole token, with [Spot.publishedAtMs]'s exponent already applied.
     * @param confUsd Pyth's ± band in the same units. Zero is a legitimate value.
     * @param publishedAtMs when Pyth published it, in milliseconds (the wire sends seconds).
     */
    data class Spot(val usd: Double, val confUsd: Double, val publishedAtMs: Long)

    // ---------------------------------------------------------------- the pure half

    /**
     * Which Pyth feed prices this mint — **the seam, and a hand-written map on purpose.**
     *
     * **There is no general mint → feed-id resolver in Pyth's API.** `GET /v2/price_feeds` was
     * read in full on 2026-10-01: 423 crypto feeds, and the only attributes on each are
     * `asset_type`, `description`, `display_symbol`, `min_channel`, `quote_currency`, `schedule`
     * and `symbol`. No mint, no contract address, no chain — Pyth is cross-chain and names a feed
     * `Crypto.SOL/USD`, which is an asset pair and not a Solana account. Resolving a mint by
     * matching its symbol against `display_symbol` is exactly the trap [MarketDay.geckoPath]
     * refuses: two mints can claim the same ticker, and the failure mode is one token priced at
     * another token's price, which is worse than no price at all.
     *
     * So this is the same shape as [MarketDay.geckoPath] — an explicit map, one line per mint,
     * and an unmapped mint resolves to nothing rather than to a guess. Every pair below was
     * verified in both directions on 2026-10-01: the feed id came out of `/v2/price_feeds` by
     * exact `symbol`, and the mint was confirmed against Jupiter's own search returning that
     * ticker for that mint. Replace the body of this function, not its callers, when a general
     * source appears.
     */
    fun feedId(mint: String): String? = FEEDS[mint]

    private val FEEDS = mapOf(
        // Crypto.SOL/USD. Native SOL is asked about under wrapped SOL, as Market.WSOL says.
        Market.WSOL to "ef0d8b6fda2ceba41da15d4095d1da392a0d2f8ed0c6c7bc0f4cfac8c280b56d",
        // Crypto.USDC/USD
        "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" to
            "eaa020c61cc479712813461ce153894a96a6c00b21ed0cfc2798d1f9a9e9c94a",
        // Crypto.USDT/USD
        "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" to
            "2b89b9dc8fdf9f34709a5b106b472f0f39bb6ca9ce04b0fd7f2e971688e2e53b",
        // Crypto.JUP/USD — a real feed, but refused on the current key. Kept so it starts
        // working the day the grant widens; until then the ladder denies it once and the
        // Jupiter price is used. Same for the two below.
        "JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN" to
            "0a0408d619e9380abad35060f9192039ed5042fa6f82301d0e48bb52be830996",
        // Crypto.BONK/USD
        "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263" to
            "72b021217ca3fe68922a19aaf990109cb9d84e9ad004b4d2025ad6f529314419",
        // Crypto.SKR/USD — "SEEKER / US DOLLAR". Pyth does list it.
        "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3" to
            "38846ec4d0dbe808091817f5c0d6ab8058e25422348ddf97db52b6c378a93bf9",
    )

    /** Every feed id this build knows how to ask for. For tests and for the ladder's ceiling. */
    val feeds: Collection<String> get() = FEEDS.values

    /**
     * Applies the feed's exponent to its integer price string.
     *
     * `"11921084818"` with `expo: -8` is `119.21084818`. The arithmetic is [BigDecimal]'s, not
     * `Double`'s: the string is an exact integer and the exponent is an exact power of ten, so
     * there is no reason to introduce a rounding error before the figure has even been shown —
     * and `price * 10.0.pow(expo)` does exactly that. The single [Double] at the end is the
     * boundary, because [Market.Price.Usd] carries one.
     *
     * Null for anything that is not a usable positive price: a missing or non-numeric string, a
     * zero or negative figure (a price of zero is not a price — [Market] says the same about
     * Jupiter's), or an exponent so far out that the feed is not one we understand.
     */
    fun scale(price: String?, expo: Int): Double? {
        val d = unsigned(price, expo) ?: return null
        return d.takeIf { it > 0 }
    }

    /**
     * [scale] for a figure that is allowed to be zero. `conf` legitimately is, on a feed its
     * publishers agree on exactly.
     */
    private fun unsigned(value: String?, expo: Int): Double? {
        if (value.isNullOrBlank() || expo < -32 || expo > 32) return null
        val raw = try { BigDecimal(value.trim()) } catch (e: NumberFormatException) { return null }
        val d = raw.scaleByPowerOfTen(expo).toDouble()
        return if (d.isNaN() || d.isInfinite() || d < 0) null else d
    }

    /**
     * One `parsed[].price` object as a [Spot], or null when it is not a price.
     *
     * A missing `conf` is not fatal — it is reported as zero, which [trustworthy] reads as "no
     * disagreement" rather than as a refusal. A missing or zero price is fatal, and a missing
     * `publish_time` is too: without it there is no way to tell a live price from a dead one, and
     * guessing "now" would make a stale feed look fresh forever.
     */
    fun spot(price: JSONObject?): Spot? {
        if (price == null) return null
        if (!price.has("expo") || !price.has("publish_time")) return null
        val expo = price.optInt("expo", Int.MIN_VALUE)
        if (expo == Int.MIN_VALUE) return null
        val usd = scale(price.text("price"), expo) ?: return null
        val seconds = price.optLong("publish_time", 0L)
        if (seconds <= 0L) return null
        return Spot(
            usd = usd,
            confUsd = unsigned(price.text("conf"), expo) ?: 0.0,
            publishedAtMs = seconds * 1000L,
        )
    }

    /**
     * Reads an `/updates/price/latest` body into feed id → [Spot].
     *
     * **A 200 is not a success.** This codebase has been bitten by an error payload arriving with
     * an HTTP 200 twice over (the `processed`-commitment RPC failure, CoinGecko's rate-limit
     * refusal), so the body is what decides:
     *
     * - unparseable, or an `error` key, or no `parsed` array at all → **null**, meaning nobody
     *   answered. The caller keeps whatever was last true.
     * - a `parsed` array → a map, which may be **empty**. That is an answer: Hermes returns
     *   `"parsed": []` for an id it does not recognise when `ignore_invalid_price_ids=true` is
     *   set (verified live), and it is not the same thing as a failure.
     *
     * Entries are keyed by the feed id lowercased, because the wire's `id` has no `0x` and the
     * map in [FEEDS] is written lowercase; a mixed-case id from either side would otherwise miss.
     */
    fun parse(body: String): Map<String, Spot>? {
        val root = try { JSONObject(body) } catch (e: Exception) { return null }
        if (root.has("error")) return null
        val arr = root.optJSONArray("parsed") ?: return null
        return buildMap {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // `text`, not `optString`: Android stringifies a JSON null into "null". See Json.kt.
                val id = o.text("id")?.removePrefix("0x")?.lowercase() ?: continue
                val s = spot(o.optJSONObject("price")) ?: continue
                put(id, s)
            }
        }
    }

    /**
     * The feed id named by a "not entitled" refusal, or null when the body is some other failure.
     *
     * The refusal is **plain text, not JSON** — read verbatim off a 403:
     * `Not entitled: feed 0a0408d6…0996 (no grant accepts this feed (…))`. It names one feed per
     * response even when several are outside the grant, which is why [fetch] loops.
     */
    fun unentitled(body: String): String? =
        UNENTITLED.find(body)?.groupValues?.get(1)?.lowercase()

    private val UNENTITLED = Regex("""[Nn]ot entitled:\s*feed\s*(?:0x)?([0-9a-fA-F]{64})""")

    /**
     * Whether this [Spot] is fit to be shown as the live price.
     *
     * Three ways it is not, and all three mean "prefer Jupiter", never "there is no price":
     *  - it is older than [MAX_AGE_MS] — the feed has stopped publishing
     *  - it is stamped further than [MAX_AGE_MS] in the *future* — the phone's clock and Pyth's
     *    disagree, and a clock-skewed device must not be handed an oracle price it cannot date
     *  - Pyth's own ± band is wider than [MAX_CONF_RATIO] of the price — the oracle is telling us
     *    its publishers do not agree
     */
    fun trustworthy(
        spot: Spot,
        nowMs: Long,
        maxAgeMs: Long = MAX_AGE_MS,
        maxConfRatio: Double = MAX_CONF_RATIO,
    ): Boolean {
        if (spot.usd <= 0) return false
        val age = nowMs - spot.publishedAtMs
        if (age > maxAgeMs || age < -maxAgeMs) return false
        return spot.confUsd <= spot.usd * maxConfRatio
    }

    /**
     * **The fallback, stated in one place: Pyth, then Jupiter, then last-good Pyth.**
     *
     * In order, and the order is the whole point:
     *  1. a [trustworthy] Pyth price — what this file exists to provide
     *  2. otherwise Jupiter's, which [Market] is fetching anyway for the icon and the verified
     *     flag, so it costs nothing extra. This is the branch a mint with no [feedId] takes, and
     *     the branch a feed outside the key's grant takes, and the branch a build with no key
     *     takes. All three are ordinary, not errors.
     *  3. otherwise a Pyth price that is merely stale, because §3.6's rule is that a price which
     *     stops updating beats one that disappears. It ranks *below* Jupiter, not above: when
     *     both are available the live figure wins.
     *  4. otherwise null — and only then does [Market.price] get to decide between "no market"
     *     and "could not ask".
     */
    fun choose(pyth: Spot?, jupiterUsd: Double?, nowMs: Long): Double? {
        if (pyth != null && trustworthy(pyth, nowMs)) return pyth.usd
        if (jupiterUsd != null && jupiterUsd > 0 && !jupiterUsd.isNaN()) return jupiterUsd
        if (pyth != null && pyth.usd > 0) return pyth.usd
        return null
    }

    // ---------------------------------------------------------------- the network half

    private var cache: Pair<Long, Map<String, Spot>> = 0L to emptyMap()

    /**
     * Feeds this key's grant does not cover, learned from a 403 and never re-asked.
     *
     * Not cleared, for the same reason [Market.answered] is not: "the key cannot see this feed"
     * is a real answer, and a grant does not widen inside one app session. Clearing it would turn
     * every poll back into the four-request ladder.
     */
    private val denied = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Feeds Hermes has successfully answered a request covering, whether or not it returned one.
     *
     * The same trap [Market.answered] documents: gating the cache on the *values* present would
     * re-ask on every single call forever for any feed Hermes omits from its answer.
     */
    private val probed = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Current prices for whichever of [mints] Pyth can price, keyed by **mint**.
     *
     * Empty when there is no key, which is the unconfigured build pricing from Jupiter exactly as
     * it did before this file existed. Mints with no [feedId], and mints whose feed is [denied],
     * are simply absent — [choose] reads absence as "use Jupiter".
     *
     * **On a failed fetch the last good prices are returned** rather than blanked, the same
     * last-good rule [MarketDay.of] follows. Merged rather than replaced, so a second wallet's
     * tokens do not evict the first's.
     */
    suspend fun of(mints: Collection<String>): Map<String, Spot> {
        if (!configured || mints.isEmpty()) return emptyMap()
        val byFeed = mints.asSequence()
            .mapNotNull { mint -> feedId(mint)?.let { it to mint } }
            .filter { it.first !in denied }
            .toMap()
        val (at, held) = cache
        if (byFeed.isEmpty()) return held
        if (System.currentTimeMillis() - at < TTL_MS && probed.containsAll(byFeed.keys)) return held

        val fresh = withContext(Dispatchers.IO) { fetch(byFeed.keys) } ?: return held
        probed.addAll(byFeed.keys)
        val merged = held + fresh.mapNotNull { (feed, spot) -> byFeed[feed]?.let { it to spot } }
        cache = System.currentTimeMillis() to merged
        return merged
    }

    /** What one request came back as. [Denied] is recoverable by asking again without that feed. */
    private sealed interface Attempt {
        data class Ok(val spots: Map<String, Spot>) : Attempt
        data class Denied(val feed: String) : Attempt
        data object Failed : Attempt
    }

    /**
     * The entitlement ladder. Null when the request itself failed, and the caller keeps last-good.
     *
     * A refusal names one feed; that feed is remembered as [denied] and the rest are asked again.
     * Bounded by [MAX_ATTEMPTS] so a gateway that answers 403 with a body we cannot read — or
     * that names a feed we did not ask for — can never spin.
     */
    private fun fetch(feeds: Set<String>): Map<String, Spot>? {
        var ask = feeds
        repeat(MAX_ATTEMPTS) {
            if (ask.isEmpty()) return emptyMap()
            when (val r = once(ask)) {
                is Attempt.Ok -> return r.spots
                is Attempt.Denied -> {
                    if (r.feed !in ask) return null
                    Log.i(TAG, "feed outside this key's grant: ${r.feed.take(8)}")
                    denied.add(r.feed)
                    ask = ask - r.feed
                }
                Attempt.Failed -> return null
            }
        }
        return null
    }

    private fun once(feeds: Set<String>): Attempt = try {
        // `ids[]` with the brackets percent-encoded: the gateway answered 403 on the literal form.
        // `ignore_invalid_price_ids` so one retired id cannot take every other price down with it.
        // `encoding=base64` only to shrink the `binary` blob nothing here reads; it cannot be off.
        val query = feeds.joinToString("&") { "ids%5B%5D=$it" }
        val c = (URL("$HOST/v2/updates/price/latest?$query&parsed=true&ignore_invalid_price_ids=true&encoding=base64")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", UA)
            // The key travels in a header and is never put in the URL, so it cannot end up in a
            // log line, a crash report or an error body that quotes the request back at us.
            setRequestProperty("Authorization", "Bearer ${BuildConfig.PYTH_API_KEY}")
        }
        when (val code = c.responseCode) {
            200 -> parse(c.inputStream.bufferedReader().use { it.readText() })
                ?.let { Attempt.Ok(it) }
                // A 200 whose body is not a price list. Treated as a failure, not as no prices.
                ?: Attempt.Failed.also { Log.i(TAG, "no price: 200 with an unreadable body") }
            // 403 is both "your key cannot see this feed" and "your key is wrong". The body is
            // read off errorStream — inputStream throws on an error response — and only the first
            // of those is recoverable.
            403 -> {
                val body = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                unentitled(body)?.let { Attempt.Denied(it) }
                    ?: Attempt.Failed.also { Log.i(TAG, "no price: HTTP 403, key refused") }
            }
            else -> Attempt.Failed.also { Log.i(TAG, "no price: HTTP $code") }
        }
    } catch (e: Exception) {
        Log.i(TAG, "no price: ${e.message}")
        Attempt.Failed
    }
}
