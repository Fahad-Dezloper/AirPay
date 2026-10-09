package so.duffle.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pyth price source — **the pure half of it.**
 *
 * The split is the project's standing one, the same line `MarketTest` draws: every decision is
 * pulled out of the fetch and checked here on the JVM with no network, and the fetch keeps only
 * the choice of which of these functions to hand a body to. Covered:
 *
 *  - [Pyth.scale] — the exponent, which is the one mistake in this file that misprices everything
 *    rather than merely looking wrong
 *  - [Pyth.spot] — one `price` object, and the fields whose absence makes it not a price
 *  - [Pyth.parse] — a real `/v2/updates/price/latest` body, the empty answer, and the several
 *    shapes of 200 that are not a success
 *  - [Pyth.unentitled] — reading the feed id out of the plain-text 403 that the entitlement
 *    ladder is built on
 *  - [Pyth.trustworthy] — stale, clock-skewed, and a confidence band that has blown out
 *  - [Pyth.choose] — **the Pyth-then-Jupiter fallback**, in all four of its branches
 *  - [Pyth.feedId] — the hand-written mint → feed map, and that an unmapped mint resolves to
 *    nothing rather than to a guess
 *  - [Market.price] — that a Pyth figure reaches the screen, and that neither source answering
 *    still never becomes "no market"
 *
 * **Not covered, and not coverable here:** [Pyth.of] and its two private helpers make HTTP
 * requests to Hermes, hold the last-good cache and climb the entitlement ladder. The ladder's
 * *decision* is [Pyth.unentitled], which is tested; the loop around it, the TTL and the
 * last-good-on-failure behaviour are stated in their docs and must be checked on the device.
 * [Pyth.configured] reads `BuildConfig`, which is build-time, not testable.
 *
 * Every fixture below was copied from a live response on 2026-10-01, not imagined: `price` and
 * `conf` really do arrive as decimal **strings** while `expo` arrives as a number, and the 403
 * really is plain text rather than JSON.
 */
class PythTest {

    /** SOL/USD. */
    private val sol = "ef0d8b6fda2ceba41da15d4095d1da392a0d2f8ed0c6c7bc0f4cfac8c280b56d"

    /** USDC/USD. */
    private val usdc = "eaa020c61cc479712813461ce153894a96a6c00b21ed0cfc2798d1f9a9e9c94a"

    /**
     * A two-feed body, verbatim in shape from `pyth.dourolabs.app/hermes/v2/updates/price/latest`.
     * The `binary` blob is truncated — nothing reads it — but it is present, because it is always
     * present on the wire and a parser that assumed otherwise would be tested on a fiction.
     */
    private val body = """
        {"binary":{"data":["504e41550100000001240100"],"encoding":"base64"},
         "parsed":[
          {"id":"$sol",
           "price":{"price":"11921084818","conf":"1416736","expo":-8,"publish_time":1790838394},
           "ema_price":{"price":"11920357610","conf":"1334798","expo":-8,"publish_time":1790838394},
           "metadata":{"slot":35816767880,"prev_publish_time":1790838393,"proof_available_time":1790838394}},
          {"id":"$usdc",
           "price":{"price":"99995034","conf":"48466","expo":-8,"publish_time":1790838394},
           "ema_price":{"price":"99996000","conf":"50000","expo":-8,"publish_time":1790838394},
           "metadata":{"slot":35816767880,"prev_publish_time":1790838393,"proof_available_time":1790838394}}]}
    """.trimIndent()

    private val publishedAtMs = 1790838394L * 1000L

    // ---------------------------------------------------------------- the exponent

    @Test
    fun `the exponent puts the decimal point where Pyth meant it`() {
        // 11921084818e-8. Wrong by one place is $1192 a SOL; wrong by the whole exponent is
        // eleven billion. This is the assertion the file exists for.
        assertEquals(119.21084818, Pyth.scale("11921084818", -8)!!, 1e-9)
    }

    @Test
    fun `a stablecoin just under a dollar stays just under a dollar`() {
        assertEquals(0.99995034, Pyth.scale("99995034", -8)!!, 1e-9)
    }

    @Test
    fun `a token worth a millionth of a cent survives the exponent`() {
        // BONK territory. A Double can hold it; the point is that scaling does not round it to 0.
        val usd = Pyth.scale("3822200", -12)!!
        assertEquals(3.8222e-6, usd, 1e-15)
        assertTrue(usd > 0)
    }

    @Test
    fun `a positive exponent scales up, not down`() {
        assertEquals(1200.0, Pyth.scale("12", 2)!!, 1e-9)
    }

    @Test
    fun `an exponent of zero is the figure itself`() {
        assertEquals(42.0, Pyth.scale("42", 0)!!, 1e-9)
    }

    @Test
    fun `the exponent is not applied in floating point`() {
        // `price * 10.0.pow(expo)` drifts, because a tenth is not representable as a Double:
        // 3 * pow(10, -1) is 0.30000000000000004. BigDecimal's scaleByPowerOfTen is exact, so
        // this holds with a delta of zero — which is the assertion that says which one ran.
        assertEquals(0.3, Pyth.scale("3", -1)!!, 0.0)
        assertTrue(3 * Math.pow(10.0, -1.0) != 0.3)
    }

    @Test
    fun `a price of zero is not a price`() {
        assertNull(Pyth.scale("0", -8))
    }

    @Test
    fun `a negative price is not a price`() {
        assertNull(Pyth.scale("-11921084818", -8))
    }

    @Test
    fun `a missing, blank or non-numeric price is not a price`() {
        assertNull(Pyth.scale(null, -8))
        assertNull(Pyth.scale("", -8))
        assertNull(Pyth.scale("   ", -8))
        assertNull(Pyth.scale("eleven", -8))
        // "null" — the four characters Android hands back for a JSON null. See Json.kt.
        assertNull(Pyth.scale("null", -8))
    }

    @Test
    fun `an exponent far outside anything Pyth publishes is refused`() {
        assertNull(Pyth.scale("1", -99))
        assertNull(Pyth.scale("1", 99))
    }

    // ---------------------------------------------------------------- one price object

    @Test
    fun `a price object becomes a spot with its confidence in dollars`() {
        val s = Pyth.spot(
            JSONObject("""{"price":"11921084818","conf":"1416736","expo":-8,"publish_time":1790838394}"""),
        )!!
        assertEquals(119.21084818, s.usd, 1e-9)
        // 1416736e-8 — about 1.4 cents on a $119 token.
        assertEquals(0.01416736, s.confUsd, 1e-9)
        // Seconds on the wire, milliseconds in the app.
        assertEquals(publishedAtMs, s.publishedAtMs)
    }

    @Test
    fun `a zero confidence band is a real answer, not a missing one`() {
        val s = Pyth.spot(JSONObject("""{"price":"100000000","conf":"0","expo":-8,"publish_time":1790838394}"""))!!
        assertEquals(0.0, s.confUsd, 0.0)
        assertTrue(Pyth.trustworthy(s, nowMs = publishedAtMs))
    }

    @Test
    fun `a missing confidence does not throw the price away`() {
        val s = Pyth.spot(JSONObject("""{"price":"100000000","expo":-8,"publish_time":1790838394}"""))!!
        assertEquals(1.0, s.usd, 1e-9)
        assertEquals(0.0, s.confUsd, 0.0)
    }

    @Test
    fun `a price with no publish time is not a price`() {
        // Defaulting this to "now" would make a dead feed look live for as long as the app runs.
        assertNull(Pyth.spot(JSONObject("""{"price":"100000000","conf":"1","expo":-8}""")))
        assertNull(Pyth.spot(JSONObject("""{"price":"100000000","conf":"1","expo":-8,"publish_time":0}""")))
    }

    @Test
    fun `a price with no exponent is not a price`() {
        // Not defaulted to zero: that would misprice by whatever the real exponent was.
        assertNull(Pyth.spot(JSONObject("""{"price":"100000000","conf":"1","publish_time":1790838394}""")))
    }

    @Test
    fun `no price object at all is no spot`() {
        assertNull(Pyth.spot(null))
    }

    // ---------------------------------------------------------------- the body

    @Test
    fun `a real two-feed body parses to two spots keyed by feed id`() {
        val m = Pyth.parse(body)!!
        assertEquals(setOf(sol, usdc), m.keys)
        assertEquals(119.21084818, m[sol]!!.usd, 1e-9)
        assertEquals(0.99995034, m[usdc]!!.usd, 1e-9)
    }

    @Test
    fun `ema_price is not mistaken for price`() {
        // Both objects sit side by side with the same field names. Reading the wrong one shows a
        // smoothed average where the live price should be, which looks entirely plausible.
        assertEquals(119.21084818, Pyth.parse(body)!![sol]!!.usd, 1e-9)
    }

    @Test
    fun `a 0x-prefixed or upper-case feed id still matches the map`() {
        val b = """{"parsed":[{"id":"0x${sol.uppercase()}","price":{"price":"100000000","conf":"1","expo":-8,"publish_time":1790838394}}]}"""
        assertEquals(setOf(sol), Pyth.parse(b)!!.keys)
    }

    @Test
    fun `an empty parsed array is an answer, not a failure`() {
        // Hermes really does return this for an id it does not recognise when
        // ignore_invalid_price_ids is set. Verified live: {"binary":{"data":[]},"parsed":[]}.
        val m = Pyth.parse("""{"binary":{"data":[],"encoding":"base64"},"parsed":[]}""")
        assertNotNull(m)
        assertTrue(m!!.isEmpty())
    }

    @Test
    fun `a 200 with no parsed array is nobody answering`() {
        // A 200 is not proof of success — the rule this codebase learned twice over. Null here
        // means "keep the last good prices", which is not what an empty map means.
        assertNull(Pyth.parse("""{"binary":{"data":[],"encoding":"base64"}}"""))
    }

    @Test
    fun `a 200 carrying an error payload is nobody answering`() {
        assertNull(Pyth.parse("""{"error":"upstream unavailable","parsed":[]}"""))
    }

    @Test
    fun `a body that is not JSON at all is nobody answering`() {
        assertNull(Pyth.parse(""))
        assertNull(Pyth.parse("Not entitled: feed"))
        assertNull(Pyth.parse("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun `one unreadable entry does not take the readable ones down with it`() {
        val b = """{"parsed":[
            {"id":"$sol","price":{"price":"0","conf":"1","expo":-8,"publish_time":1790838394}},
            {"id":"$usdc","price":{"price":"99995034","conf":"48466","expo":-8,"publish_time":1790838394}}]}"""
        assertEquals(setOf(usdc), Pyth.parse(b)!!.keys)
    }

    // ---------------------------------------------------------------- the 403 ladder

    @Test
    fun `the refusal names the feed the key cannot see`() {
        // Verbatim, and plain text rather than JSON — which is why this is a regex and not a parse.
        val refusal = "Not entitled: feed 0a0408d619e9380abad35060f9192039ed5042fa6f82301d0e48bb52" +
            "be830996 (no grant accepts this feed (asset type 'crypto', instrument type 'spot'))"
        assertEquals(
            "0a0408d619e9380abad35060f9192039ed5042fa6f82301d0e48bb52be830996",
            Pyth.unentitled(refusal),
        )
    }

    @Test
    fun `a 0x-prefixed or upper-case refusal names the same feed`() {
        assertEquals(sol, Pyth.unentitled("Not entitled: feed 0x${sol.uppercase()} (no grant)"))
    }

    @Test
    fun `some other 403 names no feed, so the ladder stops instead of spinning`() {
        // A wrong key, a revoked key, a gateway refusal. None of these get better by asking for
        // fewer feeds, so fetch must treat them as a plain failure and keep the last good prices.
        assertNull(Pyth.unentitled("Unauthorized"))
        assertNull(Pyth.unentitled("""{"detail":"invalid api key"}"""))
        assertNull(Pyth.unentitled(""))
        // Short of 64 hex characters: not a feed id, so not a feed to drop.
        assertNull(Pyth.unentitled("Not entitled: feed 0a0408d6"))
    }

    // ---------------------------------------------------------------- stale vs fresh

    private fun spotAt(ms: Long, usd: Double = 100.0, conf: Double = 0.01) =
        Pyth.Spot(usd = usd, confUsd = conf, publishedAtMs = ms)

    @Test
    fun `a price published now is fresh`() {
        assertTrue(Pyth.trustworthy(spotAt(publishedAtMs), nowMs = publishedAtMs))
    }

    @Test
    fun `a price just inside the window is fresh and just outside it is not`() {
        val now = publishedAtMs + Pyth.MAX_AGE_MS
        assertTrue(Pyth.trustworthy(spotAt(publishedAtMs), nowMs = now))
        assertFalse(Pyth.trustworthy(spotAt(publishedAtMs), nowMs = now + 1))
    }

    @Test
    fun `a price stamped in the future is not trusted either`() {
        // A phone whose clock is wrong must not be handed an oracle price it cannot date. Within
        // the window is ordinary skew and allowed; a day ahead is not.
        assertTrue(Pyth.trustworthy(spotAt(publishedAtMs), nowMs = publishedAtMs - 1_000))
        assertFalse(Pyth.trustworthy(spotAt(publishedAtMs), nowMs = publishedAtMs - 86_400_000))
    }

    @Test
    fun `a confidence band inside the limit is trusted`() {
        // The real ones measured: SOL +-0.019% of price, USDC +-0.048%. Nowhere near the limit.
        assertTrue(Pyth.trustworthy(spotAt(publishedAtMs, usd = 119.21, conf = 0.0142), publishedAtMs))
        assertTrue(Pyth.trustworthy(spotAt(publishedAtMs, usd = 100.0, conf = 5.0), publishedAtMs))
    }

    @Test
    fun `a confidence band wider than the limit means the oracle is shrugging`() {
        assertFalse(Pyth.trustworthy(spotAt(publishedAtMs, usd = 100.0, conf = 5.01), publishedAtMs))
        assertFalse(Pyth.trustworthy(spotAt(publishedAtMs, usd = 100.0, conf = 90.0), publishedAtMs))
    }

    // ---------------------------------------------------------------- the fallback

    @Test
    fun `a fresh Pyth price wins over Jupiter`() {
        val usd = Pyth.choose(spotAt(publishedAtMs, usd = 119.21), jupiterUsd = 119.16, nowMs = publishedAtMs)
        assertEquals(119.21, usd!!, 1e-9)
    }

    @Test
    fun `no Pyth feed falls back to the Jupiter price already on the wire`() {
        // JUP, BONK and SKR take this branch today: real feeds, outside this key's grant. So does
        // every mint with no feed at all, and every build with no key.
        assertEquals(0.3289, Pyth.choose(pyth = null, jupiterUsd = 0.3289, nowMs = publishedAtMs)!!, 1e-9)
    }

    @Test
    fun `a stale Pyth price loses to a live Jupiter price`() {
        val stale = spotAt(publishedAtMs, usd = 119.21)
        val now = publishedAtMs + Pyth.MAX_AGE_MS + 1
        assertEquals(119.16, Pyth.choose(stale, jupiterUsd = 119.16, nowMs = now)!!, 1e-9)
    }

    @Test
    fun `a stale Pyth price still beats nothing at all`() {
        // Section 3.6's rule: a price that stops updating beats one that disappears. It ranks
        // below Jupiter, not above — the test above is the other half of this one.
        val stale = spotAt(publishedAtMs, usd = 119.21)
        val now = publishedAtMs + Pyth.MAX_AGE_MS + 1
        assertEquals(119.21, Pyth.choose(stale, jupiterUsd = null, nowMs = now)!!, 1e-9)
    }

    @Test
    fun `a Jupiter price of zero is not a price to fall back to`() {
        // Jupiter returns 0 for a drained pool, and Market has always refused it.
        assertNull(Pyth.choose(pyth = null, jupiterUsd = 0.0, nowMs = publishedAtMs))
        assertNull(Pyth.choose(pyth = null, jupiterUsd = Double.NaN, nowMs = publishedAtMs))
    }

    @Test
    fun `neither source leaves no figure for Market to interpret`() {
        assertNull(Pyth.choose(pyth = null, jupiterUsd = null, nowMs = publishedAtMs))
    }

    // ---------------------------------------------------------------- the mint map

    @Test
    fun `the five mapped mints resolve to the feed ids read off the registry`() {
        assertEquals(sol, Pyth.feedId(Market.WSOL))
        assertEquals(usdc, Pyth.feedId("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"))
        assertEquals(
            "2b89b9dc8fdf9f34709a5b106b472f0f39bb6ca9ce04b0fd7f2e971688e2e53b",
            Pyth.feedId("Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"),
        )
        assertEquals(
            "0a0408d619e9380abad35060f9192039ed5042fa6f82301d0e48bb52be830996",
            Pyth.feedId("JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN"),
        )
        assertEquals(
            "72b021217ca3fe68922a19aaf990109cb9d84e9ad004b4d2025ad6f529314419",
            Pyth.feedId("DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"),
        )
        assertEquals(
            "38846ec4d0dbe808091817f5c0d6ab8058e25422348ddf97db52b6c378a93bf9",
            Pyth.feedId("SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"),
        )
    }

    @Test
    fun `an unmapped mint resolves to nothing rather than to a guess`() {
        // The same rule MarketDay.geckoPath follows. Picking a feed by matching a ticker would
        // eventually price one token at another token's price, which is worse than no price.
        assertNull(Pyth.feedId("4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"))
        assertNull(Pyth.feedId(""))
        // Mints are case-sensitive base58. A near miss is not a hit.
        assertNull(Pyth.feedId(Market.WSOL.lowercase()))
    }

    @Test
    fun `every mapped feed id is a bare 64-character hex string`() {
        // Hermes wants the id with no 0x and all lowercase; parse keys on that form, so a map
        // entry written any other way would silently never match an answer.
        assertTrue(Pyth.feeds.isNotEmpty())
        for (id in Pyth.feeds) assertTrue(id, Regex("^[0-9a-f]{64}$").matches(id))
    }

    @Test
    fun `no two mints share a feed`() {
        assertEquals(Pyth.feeds.size, Pyth.feeds.toSet().size)
    }

    // ---------------------------------------------------------------- what reaches the card

    @Test
    fun `a Pyth price reaches the card in place of Jupiter's`() {
        val jup = Market.Info(verified = true, usdPrice = 119.16, icon = "https://x/sol.png")
        val p = Market.price(jup, asked = true, pyth = spotAt(publishedAtMs, usd = 119.21), nowMs = publishedAtMs)
        assertEquals(Market.Price.Usd(119.21), p)
    }

    @Test
    fun `Pyth alone is enough when Jupiter could not be asked`() {
        val p = Market.price(null, asked = false, pyth = spotAt(publishedAtMs, usd = 119.21), nowMs = publishedAtMs)
        assertEquals(Market.Price.Usd(119.21), p)
    }

    @Test
    fun `Pyth having no feed does not turn a priced token into an unpriced one`() {
        val jup = Market.Info(verified = true, usdPrice = 0.0182, icon = null)
        assertEquals(Market.Price.Usd(0.0182), Market.price(jup, asked = true, pyth = null, nowMs = publishedAtMs))
    }

    @Test
    fun `neither source answering is still Unknown and never NoMarket`() {
        // The load-bearing distinction, and Pyth must not be able to blunt it. "We could not ask"
        // is drawn differently from "there is no market" (3.3), and Genesis fails closed the same
        // way. Pyth has no feed for most mints in existence, so its silence proves nothing.
        assertSame(Market.Price.Unknown, Market.price(null, asked = false, pyth = null, nowMs = publishedAtMs))
    }

    @Test
    fun `Jupiter saying there is no market still says so with Pyth silent`() {
        val unpriced = Market.Info(verified = false, usdPrice = null, icon = null)
        assertSame(
            Market.Price.NoMarket,
            Market.price(unpriced, asked = true, pyth = null, nowMs = publishedAtMs),
        )
    }
}
