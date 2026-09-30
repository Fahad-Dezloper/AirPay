package so.duffle.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What a SOL is worth in dollars.
 *
 * The balance is mainnet SOL now, so this figure is no longer notional — it is what the money
 * on screen is actually worth. It still only ever decides a label: every amount that moves is
 * computed in lamports and never from this.
 *
 * Cached for five minutes in memory. A price that moves while somebody is looking at a home
 * screen is noise, and the only thing worse than a stale price here is a flickering one.
 */
object Prices {

    private const val TAG = "Prices"
    private const val HOST = "https://api.coingecko.com/api/v3"
    private const val ENDPOINT = "$HOST/simple/price?ids=solana&vs_currencies=usd"
    private const val TTL_MS = 5 * 60_000L

    private var usd: Double? = null
    private var at = 0L

    /** Dollars per SOL, or null when it could not be asked. Never an invented number. */
    suspend fun solUsd(): Double? {
        usd?.takeIf { System.currentTimeMillis() - at < TTL_MS }?.let { return it }
        val fresh = withContext(Dispatchers.IO) { fetch() } ?: return usd  // stale beats nothing
        usd = fresh
        at = System.currentTimeMillis()
        return fresh
    }

    private fun fetch(): Double? = try {
        val c = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 6_000
            setRequestProperty("Accept", "application/json")
        }
        if (c.responseCode != 200) null
        else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            .getJSONObject("solana")
            .getDouble("usd")
            .takeIf { it > 0 }
    } catch (e: Exception) {
        Log.i(TAG, "no price: ${e.message}")
        null
    }

    // unitUsd and its list of dollar-pegged symbols lived here. They priced SOL live, pinned
    // five stablecoin symbols at a dollar and returned null for everything else — which is how
    // SKR, a real token with real liquidity, showed "No market price". [Market] asks Jupiter
    // for every held mint in one call instead, so a token is priced because it has a market
    // rather than because somebody remembered to add it to a list.

    /**
     * A day of prices for a token, or null when there is none to be had.
     *
     * **Only SOL has one.** A devnet mint has no market, no history and therefore no line to
     * draw — and a flat or invented sparkline beside a balance is a claim about price movement
     * that nobody made. Cards without history say so by not drawing one.
     *
     * Roughly five-minute samples over 24h, thinned to what a 90dp-wide chart can show.
     */
    private var series: Pair<Long, List<Double>>? = null

    suspend fun solDay(): List<Double>? {
        series?.takeIf { System.currentTimeMillis() - it.first < TTL_MS }?.let { return it.second }
        val fresh = withContext(Dispatchers.IO) { fetchDay() } ?: return series?.second
        series = System.currentTimeMillis() to fresh
        return fresh
    }

    private fun fetchDay(): List<Double>? = try {
        val c = (URL("$HOST/coins/solana/market_chart?vs_currency=usd&days=1")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
        }
        if (c.responseCode != 200) null else {
            val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                .getJSONArray("prices")
            // Thinned: a hundred points on a 90dp line is a hundred points nobody can see.
            val step = maxOf(1, arr.length() / 48)
            (0 until arr.length() step step)
                .map { arr.getJSONArray(it).getDouble(1) }
                .takeIf { it.size >= 4 }
        }
    } catch (e: Exception) {
        Log.i(TAG, "no history: ${e.message}")
        null
    }

    /** How much a series moved end to end, as a fraction. Null when there is nothing to compare. */
    fun change(series: List<Double>?): Double? {
        val s = series ?: return null
        val first = s.firstOrNull() ?: return null
        val last = s.lastOrNull() ?: return null
        return if (first <= 0.0) null else (last - first) / first
    }

    // `money()` was here — a fiat figure with no currency and no symbol, which left both
    // callers to concatenate a "$" of their own. One of them put it outside the "less than"
    // and rendered "$<0.01". Money.fiat and Money.fiatParts own the symbol now.
}
