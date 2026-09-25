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
 * **The balance is devnet SOL and devnet SOL is worth nothing.** This is the mainnet price
 * applied to a testnet balance, which makes the figure on the home screen notional — the number
 * that balance *would* be worth. That is the right call for a screen whose job is to show what
 * the product looks like, and the wrong one to carry into anything that spends money. Nothing
 * else in this app reads this: it decides a label, never an amount.
 *
 * Cached for five minutes in memory. A price that moves while somebody is looking at a home
 * screen is noise, and the only thing worse than a stale price here is a flickering one.
 */
object Prices {

    private const val TAG = "Prices"
    private const val ENDPOINT =
        "https://api.coingecko.com/api/v3/simple/price?ids=solana&vs_currencies=usd"
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

    /**
     * Dollars for one whole token, or null when there is no honest answer.
     *
     * SOL is priced live. Dollar stablecoins are a dollar by construction — that is the entire
     * promise of one, and pricing them through an exchange would add noise to a constant. Every
     * other token returns **null**, and null means the screen shows no dollar figure rather
     * than a zero: most of what is held on devnet is a test mint with no market at all, and
     * "$0.00" next to a balance reads as "worthless" when the truth is "unknown".
     */
    private val DOLLAR_PEGGED = setOf("USDC", "USDT", "PYUSD", "USDP", "DAI")

    suspend fun unitUsd(h: Tokens.Holding): Double? = when {
        h.isSol -> solUsd()
        h.symbol.uppercase() in DOLLAR_PEGGED -> 1.0
        else -> null
    }

    /** "1,234.56" — grouped, two places, which is how money is read. */
    fun money(amount: Double): String = String.format("%,.2f", amount)
}
