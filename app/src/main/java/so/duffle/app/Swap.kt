package so.duffle.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Swapping a token for SKR, through Jupiter.
 *
 * Jupiter is asked for an order: the best rate it can find across every market it knows, and a
 * transaction that performs it. The wallet signs that transaction and Jupiter lands it. Nothing
 * here chooses a route, sets a price or holds anybody's money: the rate shown is Jupiter's, and
 * the least the wallet can receive ([Order.minOut]) is written into the transaction it signs.
 *
 * Keyless, which Jupiter allows at one request every two seconds. [MIN_GAP_MS] keeps this phone
 * inside that, so a figure being typed is asked about once it has stopped changing and never
 * once per key.
 *
 * Swap API v2: `GET /order`, `POST /execute`. Field names are from Jupiter's published
 * specification for it, read 2026-10-06.
 */
object Swap {

    const val SKR_MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
    const val SKR_DECIMALS = 6

    private const val BASE = "https://api.jup.ag/swap/v2"

    /** The least time between two requests for an order. Jupiter's keyless allowance is 0.5/s. */
    const val MIN_GAP_MS = 2_100L

    /** How long an order is good to sign. Past this the rate is asked for again first. */
    const val FRESH_MS = 20_000L

    /**
     * @param inUnits what leaves the wallet, in the paying token's base units.
     * @param outUnits SKR base units expected.
     * @param minOut SKR base units the transaction guarantees, after slippage.
     * @param impact how far this trade moves the price, in percent, or null when not said.
     * @param gasless true when Jupiter pays the network fee for this swap itself.
     * @param transaction the transaction to sign, base64, or null when none could be built.
     * @param problem why there is no transaction, in words for the sheet. Null when there is one.
     */
    data class Order(
        val inUnits: Long,
        val outUnits: Long,
        val minOut: Long,
        val impact: Double?,
        val slippageBps: Int,
        val router: String,
        val gasless: Boolean,
        val transaction: String?,
        val requestId: String?,
        val problem: String?,
        val at: Long,
    ) {
        fun fresh(now: Long) = now - at < FRESH_MS
        val signable get() = !transaction.isNullOrBlank() && !requestId.isNullOrBlank()
    }

    sealed interface Asked {
        data class Ok(val order: Order) : Asked
        /** Jupiter answered and there is no swap to be had: no market, or an amount too small. */
        data class None(val why: String) : Asked
        /** Asked too often. Not an error to show: the next ask will be answered. */
        data object Busy : Asked
        data object Offline : Asked
    }

    sealed interface Done {
        /** Landed. [outUnits] is the SKR that actually arrived, when Jupiter says. */
        data class Ok(val signature: String, val outUnits: Long?) : Done
        data class Failed(val why: String) : Done
        data object Offline : Done
    }

    /** The best order Jupiter has for [units] of [inputMint] into SKR, signed for by [taker]. */
    fun order(inputMint: String, units: Long, taker: String, now: Long = System.currentTimeMillis()): Asked {
        if (units <= 0 || !PayRequest.isBase58Address(inputMint) || !PayRequest.isBase58Address(taker)) {
            return Asked.None("Enter an amount.")
        }
        val (code, body) = http("GET", "$BASE/order?inputMint=$inputMint&outputMint=$SKR_MINT&amount=$units&taker=$taker", null)
        return when {
            code == 429 -> Asked.Busy
            body == null -> Asked.Offline
            else -> parseOrder(body, now)
        }
    }

    /** Pure, so it can be tested without a network. */
    internal fun parseOrder(body: String, now: Long): Asked {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return Asked.Offline
        val out = o.optString("outAmount").toLongOrNull()
        val inn = o.optString("inAmount").toLongOrNull()
        if (out == null || inn == null || out <= 0 || inn <= 0) {
            // No figures at all: there is no route, or the request itself was refused.
            val said = o.optString("errorMessage").ifBlank { o.optString("error") }
            return Asked.None(
                if (said.contains("route", true) || said.contains("liquidity", true)) "There is no market for that swap right now."
                else if (said.contains("small", true) || said.contains("minimum", true)) "That amount is too small to swap."
                else "This swap is not available right now."
            )
        }
        val tx = o.optString("transaction").takeIf { it.isNotBlank() && it != "null" }
        // The codes Jupiter gives when it priced the swap and could not build it.
        val problem = if (tx != null) null else when (o.optInt("errorCode", 0)) {
            1 -> "You do not have enough for this swap."
            2 -> "You need a little SOL for the network fee."
            3 -> "That amount is too small to swap without SOL."
            else -> "This swap could not be prepared. Try another amount."
        }
        return Asked.Ok(Order(
            inUnits = inn,
            outUnits = out,
            minOut = o.optString("otherAmountThreshold").toLongOrNull() ?: out,
            impact = if (o.has("priceImpact") && !o.isNull("priceImpact")) o.optDouble("priceImpact").takeIf { !it.isNaN() } else null,
            slippageBps = o.optInt("slippageBps", 0),
            router = o.optString("router"),
            gasless = o.optBoolean("gasless", false),
            transaction = tx,
            requestId = o.optString("requestId").takeIf { it.isNotBlank() },
            problem = problem,
            at = now,
        ))
    }

    /** Hands Jupiter the transaction the wallet signed, to land. */
    fun execute(signedTx: ByteArray, requestId: String): Done {
        val b64 = android.util.Base64.encodeToString(signedTx, android.util.Base64.NO_WRAP)
        val (_, body) = http(
            "POST", "$BASE/execute",
            JSONObject().put("signedTransaction", b64).put("requestId", requestId).toString(),
            timeoutMs = 45_000,
        )
        return parseExecute(body ?: return Done.Offline)
    }

    internal fun parseExecute(body: String): Done {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return Done.Offline
        val signature = o.optString("signature").takeIf { it.length >= 60 }
        if (o.optString("status").equals("Success", true) && signature != null) {
            return Done.Ok(signature, o.optString("totalOutputAmount").toLongOrNull()
                ?: o.optString("outputAmountResult").toLongOrNull())
        }
        // What went wrong, by Jupiter's own code where it gives one. Nothing moved in any of them.
        return Done.Failed(when (o.optInt("code", 0)) {
            -1, -2003, -1004 -> "That rate ran out before the swap was sent. Nothing was swapped. Try again."
            -1000, -2000 -> "The swap did not go through. Nothing was swapped. Try again."
            -2004 -> "The price moved too far. Nothing was swapped. Try again."
            else -> "The swap did not go through. Nothing was swapped."
        })
    }

    /** One request. The status, and the body when there was one to read. Never throws. */
    private fun http(method: String, url: String, body: String?, timeoutMs: Int = 12_000): Pair<Int, String?> = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }
        c.disconnect()
        code to text
    } catch (e: Exception) {
        android.util.Log.i("Swap", "$method failed: ${e.message}")
        0 to null
    }
}
