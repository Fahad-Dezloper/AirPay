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
     * SOL's day of prices, or null when there is none to be had.
     *
     * **Kept for its existing callers only.** The day of a token's price is now [MarketDay]'s
     * job, per mint rather than SOL-only, and the CoinGecko client that used to live here moved
     * there with it: one copy of the thinning, the five-minute cache and the rate-limit handling
     * rather than two that can drift.
     *
     * This wrapper flattens three answers into two, which is exactly what the token cards must
     * not do — "no day" and "could not ask" render differently (§3.5). New callers want
     * [Market.quotes] or [MarketDay.of] and the [MarketDay.Day] they return.
     */
    suspend fun solDay(): List<Double>? =
        (MarketDay.of(Market.WSOL) as? MarketDay.Day.Points)?.usd

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
