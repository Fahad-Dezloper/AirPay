package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-token market data layer — **the pure half of it.**
 *
 * The split is deliberate and is where the line falls: a scanner that reads a fixed remote path
 * is unverifiable, so every decision is pulled out of the fetch and tested here without a
 * network. Covered:
 *
 *  - [Market.price] — the three answers, and that "could not ask" never becomes "no market"
 *  - [MarketDay.parse] — a real `market_chart` body, an error payload arriving as a 200, an
 *    empty market, and a malformed body
 *  - [MarketDay.thin] — the downsample to ~48
 *  - [MarketDay.withLive] — appending the live price as the last point
 *  - [MarketDay.stale] — the five-minute cache-expiry decision
 *  - [MarketDay.geckoPath] — the hand-written mint → CoinGecko mapping, and that an unmapped
 *    mint resolves to nothing rather than to a guess
 *
 * **Not covered, and not coverable here:** `Market.of`, `Market.quotes` and `MarketDay.of` make
 * HTTP requests to Jupiter and CoinGecko. They hold no decisions of their own beyond which of
 * the functions above to hand a response body to, and which branch a failure takes — the
 * last-good-data behaviour is stated in their docs and must be checked on the device.
 *
 * The response shapes the fixtures below imitate were read off the live endpoints on 2026-10-01,
 * not guessed: Jupiter returns a bare array and **omits** a mint it does not know, and
 * CoinGecko's `days=1` came back with 486 `[millis, usd]` pairs.
 */
class MarketTest {

    // ---------------------------------------------------------------- Market.price

    private val priced = Market.Info(verified = true, usdPrice = 119.28, icon = "https://x/sol.png")
    private val unpriced = Market.Info(verified = false, usdPrice = null, icon = null)

    @Test
    fun `a mint Jupiter priced is priced`() {
        assertEquals(Market.Price.Usd(119.28), Market.price(priced, asked = true))
    }

    @Test
    fun `a mint Jupiter knows but cannot price has no market`() {
        assertSame(Market.Price.NoMarket, Market.price(unpriced, asked = true))
    }

    @Test
    fun `a mint Jupiter answered about and did not return has no market`() {
        // Jupiter omits unknown mints from its array entirely — verified against the live
        // endpoint — so "absent from an answer we did get" is the real negative.
        assertSame(Market.Price.NoMarket, Market.price(null, asked = true))
    }

    @Test
    fun `a mint nobody answered about is unknown, not worthless`() {
        // The whole point of the type. Were this NoMarket, a dropped connection would print
        // "No market price" under a token that has one.
        assertSame(Market.Price.Unknown, Market.price(null, asked = false))
    }

    @Test
    fun `could not ask and no market are not the same value`() {
        assertTrue(Market.price(null, asked = false) != Market.price(null, asked = true))
    }

    @Test
    fun `a quote exposes the figure for arithmetic and the negative for the label`() {
        val q = Market.Quote(Market.Price.Usd(2.5), null, true, MarketDay.Day.None)
        assertEquals(2.5, q.usd!!, 0.0)
        assertEquals(false, q.noMarket)
        assertNull(q.series)

        val cannotAsk = Market.Quote(Market.Price.Unknown, null, null, MarketDay.Day.Unknown)
        assertNull(cannotAsk.usd)
        // Not a negative: §3.3's "No market price" must not be printed for this one.
        assertEquals(false, cannotAsk.noMarket)
        assertNull(cannotAsk.verified)

        val none = Market.Quote(Market.Price.NoMarket, null, false, MarketDay.Day.None)
        assertEquals(true, none.noMarket)
    }

    // ---------------------------------------------------------------- MarketDay.parse

    /** `[millis, usd]` pairs, the shape CoinGecko's `market_chart` returns. */
    private fun chart(vararg usd: Double): String {
        val pairs = usd.mapIndexed { i, v -> "[${1790750400000L + i * 300_000L},$v]" }
        return """{"prices":[${pairs.joinToString(",")}],"market_caps":[],"total_volumes":[]}"""
    }

    @Test
    fun `a real market_chart body becomes a line`() {
        val day = MarketDay.parse(chart(0.0181, 0.0182, 0.0180, 0.0183))
        assertEquals(MarketDay.Day.Points(listOf(0.0181, 0.0182, 0.0180, 0.0183)), day)
    }

    @Test
    fun `an error payload arriving as a 200 is could-not-ask, not an empty market`() {
        // CoinGecko's rate-limit refusal. This codebase has already shipped a bug where a
        // JSON-RPC error came back with HTTP 200 and read as success.
        val refusal = """{"status":{"error_code":429,"error_message":"You've exceeded the Rate Limit"}}"""
        assertSame(MarketDay.Day.Unknown, MarketDay.parse(refusal))
        assertSame(MarketDay.Day.Unknown, MarketDay.parse("""{"error":"coin not found"}"""))
    }

    @Test
    fun `a body with no prices array is could-not-ask`() {
        assertSame(MarketDay.Day.Unknown, MarketDay.parse("""{"market_caps":[]}"""))
    }

    @Test
    fun `a malformed body is could-not-ask`() {
        assertSame(MarketDay.Day.Unknown, MarketDay.parse("<html>502 Bad Gateway</html>"))
        assertSame(MarketDay.Day.Unknown, MarketDay.parse(""))
    }

    @Test
    fun `an answered-but-empty market is None, which is a different answer`() {
        assertSame(MarketDay.Day.None, MarketDay.parse("""{"prices":[]}"""))
        // One point is not a line.
        assertSame(MarketDay.Day.None, MarketDay.parse(chart(0.0181)))
        assertTrue(MarketDay.parse("""{"prices":[]}""") != MarketDay.parse("""{"market_caps":[]}"""))
    }

    @Test
    fun `a zero price is not a price`() {
        // A drained pool reports 0.0. Two real points survive, so it is still a line.
        val day = MarketDay.parse(chart(0.0, 1.0, 0.0, 2.0)) as MarketDay.Day.Points
        assertEquals(listOf(1.0, 2.0), day.usd)
    }

    @Test
    fun `parse thins what it parses`() {
        val body = chart(*DoubleArray(486) { 100.0 + it })
        val day = MarketDay.parse(body) as MarketDay.Day.Points
        assertEquals(49, day.usd.size)
    }

    // ---------------------------------------------------------------- MarketDay.thin

    @Test
    fun `a day thins to about 48 points`() {
        // 486 points was the live answer for SKR: step 10, so 0, 10 … 480.
        val thinned = MarketDay.thin(List(486) { it.toDouble() })
        assertEquals(49, thinned.size)
        assertEquals(0.0, thinned.first(), 0.0)
        assertEquals(10.0, thinned[1], 0.0)
        assertEquals(480.0, thinned.last(), 0.0)
    }

    @Test
    fun `a short day is left alone`() {
        val short = List(12) { it.toDouble() }
        assertEquals(short, MarketDay.thin(short))
        assertEquals(List(48) { it.toDouble() }, MarketDay.thin(List(48) { it.toDouble() }))
    }

    @Test
    fun `thinning never drops to nothing`() {
        assertEquals(listOf(1.0, 2.0), MarketDay.thin(listOf(1.0, 2.0)))
        assertEquals(1, MarketDay.thin(listOf(1.0)).size)
        assertTrue(MarketDay.thin(List(1000) { it.toDouble() }).size >= 2)
    }

    // ---------------------------------------------------------------- MarketDay.withLive

    @Test
    fun `the live price becomes the day's last point`() {
        assertEquals(listOf(1.0, 2.0, 3.5), MarketDay.withLive(listOf(1.0, 2.0), 3.5))
    }

    @Test
    fun `no live price appends nothing rather than a hole`() {
        assertEquals(listOf(1.0, 2.0), MarketDay.withLive(listOf(1.0, 2.0), null))
        assertEquals(listOf(1.0, 2.0), MarketDay.withLive(listOf(1.0, 2.0), 0.0))
        assertEquals(listOf(1.0, 2.0), MarketDay.withLive(listOf(1.0, 2.0), Double.NaN))
    }

    @Test
    fun `appending a live price does not invent a day`() {
        // The two no-line answers stay themselves; a live price is not history.
        assertSame(MarketDay.Day.None, MarketDay.withLive(MarketDay.Day.None, 3.5))
        assertSame(MarketDay.Day.Unknown, MarketDay.withLive(MarketDay.Day.Unknown, 3.5))
    }

    @Test
    fun `the line's end agrees with the price tip`() {
        // Why withLive exists: the tip shows the live price, so the line has to end at it.
        val day = MarketDay.withLive(MarketDay.Day.Points(listOf(118.0, 119.0)), 119.28)
        assertEquals(119.28, (day as MarketDay.Day.Points).usd.last(), 0.0)
        // And the colour follows the appended end, not CoinGecko's stale tail.
        assertTrue(Prices.change(day.usd)!! > 0)
    }

    // ---------------------------------------------------------------- MarketDay.stale

    @Test
    fun `a day is good for five minutes`() {
        val at = 1_000_000L
        assertEquals(false, MarketDay.stale(at, at))
        assertEquals(false, MarketDay.stale(at, at + MarketDay.TTL_MS - 1))
        assertEquals(true, MarketDay.stale(at, at + MarketDay.TTL_MS))
        assertEquals(true, MarketDay.stale(at, at + MarketDay.TTL_MS + 1))
        // A never-fetched entry (at = 0) is stale against any real clock.
        assertEquals(true, MarketDay.stale(0L, System.currentTimeMillis()))
    }

    // ---------------------------------------------------------------- MarketDay.geckoPath

    @Test
    fun `the five hand-mapped mints resolve`() {
        assertEquals("coins/solana", MarketDay.geckoPath(Market.WSOL))
        assertEquals("coins/usd-coin", MarketDay.geckoPath("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"))
        assertEquals(
            "coins/jupiter-exchange-solana",
            MarketDay.geckoPath("JUPyiwrYJFskUPiHa7hkeR8VUtAeFoSYbKedZNsDvCN"),
        )
        assertEquals("coins/bonk", MarketDay.geckoPath("DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"))
        assertEquals("coins/seeker", MarketDay.geckoPath("SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"))
    }

    @Test
    fun `an unmapped mint resolves to nothing, not to a guess`() {
        // The open question (§3.6, §7): there is no general mint → CoinGecko id lookup wired in,
        // so an unmapped mint gets no line. Guessing one by symbol would eventually draw one
        // token's day under another token's name.
        assertNull(MarketDay.geckoPath("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"))
        assertNull(MarketDay.geckoPath(""))
        // Case matters: a mint is case-sensitive base58, and a near-miss is not a match.
        assertNull(MarketDay.geckoPath(Market.WSOL.lowercase()))
    }
    // -- the day on disk ---------------------------------------------------------------

    /**
     * The round trip, because a corrupt entry is worse than no entry: it would draw a line
     * made of whatever survived.
     */
    @Test fun `a day survives being written and read back`() {
        val day = MarketDay.Day.Points(listOf(1.5, 2.25, 3.0))
        val line = MarketDay.encode(1_700_000_000_000L, day)!!
        val (at, back) = MarketDay.decode(line)!!
        assertEquals(1_700_000_000_000L, at)
        assertEquals(day, back)
    }

    /**
     * "We could not ask" is never written. Persisting it would turn one dropped connection
     * into a fact every later launch believes — the same mistake as rendering Unknown as
     * NoMarket, one process further on.
     */
    @Test fun `unknown is not persisted`() {
        assertNull(MarketDay.encode(1L, MarketDay.Day.Unknown))
    }

    /** "This mint has no history" IS a real answer, and re-asking it every launch is the waste. */
    @Test fun `none is persisted and reads back as none`() {
        val line = MarketDay.encode(42L, MarketDay.Day.None)!!
        assertEquals(42L to MarketDay.Day.None, MarketDay.decode(line))
    }

    @Test fun `rubbish on disk is not an answer`() {
        assertNull(MarketDay.decode(""))
        assertNull(MarketDay.decode("not json"))
        assertNull(MarketDay.decode("""{"at":1}"""))
        // One point is not a line, and `parse` would have refused it on the way in too.
        assertNull(MarketDay.decode("""{"at":1,"p":[1.0]}"""))
        assertNull(MarketDay.decode("""{"p":[1.0,2.0]}"""))
    }

}
