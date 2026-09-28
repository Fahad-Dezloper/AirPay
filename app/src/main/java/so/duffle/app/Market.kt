package so.duffle.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
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
     */
    data class Info(val verified: Boolean, val usdPrice: Double?)

    private const val TTL_MS = 5 * 60_000L
    private var cache: Pair<Long, Map<String, Info>> = 0L to emptyMap()

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
        if (System.currentTimeMillis() - at < TTL_MS && held.keys.containsAll(wanted)) return held

        val fresh = withContext(Dispatchers.IO) { fetch(wanted) } ?: return held
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
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                    put(
                        id,
                        Info(
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
}
