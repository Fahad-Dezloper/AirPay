package so.duffle.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The optional backend: it watches the chain for our requests and tells us the moment one is
 * paid, including when this app is closed.
 *
 * **It is never the judge.** An event from the server says "look at reference X now"; the app
 * then checks the chain itself before showing ✓ Paid. The server is a faster doorbell, not a
 * source of truth — so a compromised or buggy server can make us check early, never pay out
 * on a payment that did not happen.
 *
 * Everything here is optional. With no server configured the app polls the chain directly and
 * loses nothing but speed and notifications while closed.
 */
object ServerClient {

    private val base = BuildConfig.TAP_SERVER_URL.trimEnd('/')

    val enabled get() = base.isNotBlank()

    /** Solana reads and sends, borrowed from the server's provider. See Chain.post. */
    val rpcUrl get() = "$base/v1/rpc"

    /**
     * The same, on mainnet — used only by the shop, where the money is real.
     *
     * Null without a server, and deliberately so: there is no public fallback here. A mainnet
     * transfer that quietly went somewhere else would cost someone actual USDC, so no server
     * means no purchase rather than a guess at an endpoint.
     */
    val mainnetRpcUrl get() = if (enabled) "$base/v1/rpc/mainnet" else null

    sealed interface Event
    data class Paid(val reference: String, val signature: String) : Event
    data class Requested(val request: PayRequest) : Event
    data class Tapped(val card: TapCard) : Event
    data class Withdrawn(val reference: String) : Event
    data class Rejected(val reference: String, val label: String) : Event
    /** The payer said "later". Not an answer — the request is still payable. */
    data class Deferred(val reference: String, val label: String, val amount: String) : Event
    /** A line of text from someone who has tapped this phone. See [Messages]. */
    data class Messaged(val from: String, val label: String, val body: String, val at: Long, val id: Long) : Event
    /**
     * A shop purchase finished, told to us by the server after Bitrefill called it.
     *
     * Carries the code, but the app re-asks rather than trusting it: the same catch-up that
     * runs on every launch is the one path, so there is a single way an order becomes settled
     * whether the news arrived by push or by polling.
     */
    data class Shopped(val invoice: String, val status: String) : Event

    /**
     * Tells the server to watch a request. Idempotent — both phones call it, the payee when
     * serving and the payer when reading, so the request is watched even if one is offline.
     *
     * With `payer` set it is also a delivery: the server passes the request on to that address,
     * which is how "Request" works from the tap popup after the phones have parted.
     */
    suspend fun register(
        reference: String, recipient: String, amount: String, label: String,
        payer: String? = null, genesis: String? = null,
        /** "SOL" or the SPL mint, as in [PayRequest]. */
        token: String = "SOL", decimals: Int = 9, symbol: String = "SOL",
        /**
         * How this payment started. "tap" means the two phones touched, which is the whole
         * basis of the reward rules — an online transfer cannot show that two people met.
         *
         * It is the phone's word, and a modified build could claim it, so the server treats it
         * as one condition among several rather than as proof. Left blank the server keeps
         * whatever it already knows, so the nudge after paying never overwrites the tap.
         */
        origin: String = "",
        /**
         * Whether the server should carry this request to `payer`.
         *
         * False means "I am the payer, I already have it". The paying phone registers the
         * request itself before paying — so the server is watching, knows who to tell if it is
         * withdrawn, and can attribute the reward — and delivering it back rang its own phone
         * with its own request. Only the relay after the phones have parted needs true.
         */
        deliver: Boolean = true,
    ): Boolean = post("/v1/requests", JSONObject().apply {
        put("reference", reference)
        put("recipient", recipient)
        put("amount", amount)
        put("label", label)
        payer?.let { put("payer", it) }
        genesis?.let { put("genesis", it) }
        put("token", token)
        put("decimals", decimals)
        put("symbol", symbol)
        if (origin.isNotEmpty()) put("origin", origin)
        if (!deliver) put("deliver", false)
    })

    /**
     * Takes a request back. Advisory by design: nothing can stop someone sending SOL to an
     * address, so this only stops the other phone asking — and the request stays watched, in
     * case they were already approving when it was withdrawn.
     */
    suspend fun withdraw(reference: String): Boolean =
        post("/v1/withdrawals", JSONObject().apply { put("reference", reference) })

    /**
     * The payer said "later". The asker's phone shows it as pending instead of as silence.
     *
     * Deliberately separate from [reject]: this does not answer the request, it only says
     * somebody saw it. The money can still arrive.
     */
    suspend fun defer(reference: String, label: String): Boolean =
        post("/v1/deferrals", JSONObject().apply {
            put("reference", reference)
            put("label", label)
        })

    /** The payer declined. The asker's phone stops showing it as expected money. */
    suspend fun reject(reference: String, label: String): Boolean =
        post("/v1/rejections", JSONObject().apply {
            put("reference", reference)
            put("label", label)
        })

    /** Relays an introduction the radio could not deliver, because the phones parted first. */
    suspend fun relayTap(from: TapCard, to: String): Boolean = post("/v1/taps", JSONObject().apply {
        put("to", to)
        put("address", from.address)
        put("label", from.label)
        from.genesis?.let { put("genesis", it) }
        from.brand?.let { put("brand", it) }
    })

    /** Sends one line of text to another phone. The server holds it until they collect it. */
    suspend fun sendMessage(from: String, to: String, body: String, label: String): Boolean =
        post("/v1/messages", JSONObject().apply {
            put("from", from)
            put("to", to)
            put("body", body)
            put("label", label)
        })

    /**
     * Everything written to us since [since] (server seconds). Read on launch, because the
     * live stream only carries what arrives while it is connected.
     */
    suspend fun messages(recipient: String, since: Double): List<Messages.Note> {
        val o = read("/v1/messages?recipient=${URLEncoder.encode(recipient, "UTF-8")}&since=$since")
            ?: return emptyList()
        val arr = o.optJSONArray("messages") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            val from = m.optString("from")
            val body = m.optString("body")
            if (!PayRequest.isBase58Address(from) || body.isBlank()) return@mapNotNull null
            Messages.Note(peer = from, body = body, at = (m.optDouble("at", 0.0) * 1000).toLong(),
                mine = false, id = m.optLong("id"))
        }
    }

    /** Where to send a push when a payment to `recipient` lands. */
    suspend fun registerDevice(recipient: String, pushToken: String): Boolean =
        post("/v1/devices", JSONObject().apply {
            put("recipient", recipient)
            put("push_token", pushToken)
        })

    /**
     * Server-sent events for `recipient` — payments to it, requests for it, introductions to
     * it — for as long as it is collected.
     *
     * SSE rather than a socket because the stream only ever flows one way and survives proxies.
     * It only lives while the app is open — a closed app hears about payments by push instead.
     * Reconnects on its own with backoff; a dropped stream is normal on a phone.
     */
    fun events(recipient: String): Flow<Event> = flow {
        var backoff = 1_000L
        while (true) {
            try {
                val url = URL("$base/v1/events?recipient=${URLEncoder.encode(recipient, "UTF-8")}")
                val c = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    // The server sends a heartbeat every 15s, so silence past this means dead.
                    readTimeout = 45_000
                    setRequestProperty("Accept", "text/event-stream")
                }
                if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                backoff = 1_000L
                c.inputStream.bufferedReader().use { r ->
                    var event = ""
                    val data = StringBuilder()
                    while (true) {
                        val line = r.readLine() ?: break
                        when {
                            line.isEmpty() -> {
                                parseEvent(event, data.toString())?.let { emit(it) }
                                event = ""; data.clear()
                            }
                            line.startsWith(":") -> Unit // heartbeat
                            line.startsWith("event:") -> event = line.substringAfter(":").trim()
                            line.startsWith("data:") -> data.append(line.substringAfter(":").trim())
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.i("TapServer", "event stream dropped: ${e.message}")
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }.flowOn(Dispatchers.IO)

    fun parseEvent(type: String, json: String): Event? = when (type) {
        "paid" -> parsePaid(json)
        "request" -> try {
            val o = JSONObject(json)
            val token = o.optString("token", "SOL").ifBlank { "SOL" }
            PayRequest(
                recipient = o.getString("recipient"), amount = o.getString("amount"), token = token,
                label = o.optString("label"), reference = o.getString("reference"),
                genesis = o.optString("genesis").takeIf { it.isNotBlank() },
                decimals = o.optInt("decimals", 9),
                symbol = o.optString("symbol").ifBlank { if (token == "SOL") "SOL" else token.take(4) },
            ).takeIf { it.validate() == null && it.units() != null }?.let { Requested(it) }
        } catch (e: Exception) { null }
        "withdrawn" -> try {
            JSONObject(json).getString("reference").takeIf { PayRequest.isBase58Address(it) }?.let { Withdrawn(it) }
        } catch (e: Exception) { null }
        "rejected" -> try {
            val o = JSONObject(json)
            o.getString("reference").takeIf { PayRequest.isBase58Address(it) }
                ?.let { Rejected(it, o.optString("label")) }
        } catch (e: Exception) { null }
        "deferred" -> try {
            val o = JSONObject(json)
            o.getString("reference").takeIf { PayRequest.isBase58Address(it) }
                ?.let { Deferred(it, o.optString("label"), o.optString("amount")) }
        } catch (e: Exception) { null }
        "message" -> try {
            val o = JSONObject(json)
            val from = o.getString("from")
            val body = o.getString("body")
            if (PayRequest.isBase58Address(from) && body.isNotBlank())
                Messaged(from, o.optString("label"), body.take(Messages.MAX_BODY),
                    (o.optDouble("at", 0.0) * 1000).toLong(), o.optLong("id"))
            else null
        } catch (e: Exception) { null }
        "tap" -> try {
            val o = JSONObject(json)
            TapCard(o.getString("address"), o.optString("label"), o.optString("genesis").takeIf { it.isNotBlank() },
                o.optString("brand").takeIf { Brand.isKnown(it) })
                .takeIf { it.validate() == null }?.let { Tapped(it) }
        } catch (e: Exception) { null }
        "shop" -> try {
            val o = JSONObject(json)
            o.getString("invoice").takeIf { it.isNotBlank() }
                ?.let { Shopped(it, o.optString("status")) }
        } catch (e: Exception) { null }
        else -> null
    }

    fun parsePaid(json: String): Paid? = try {
        val o = JSONObject(json)
        val ref = o.getString("reference")
        val sig = o.getString("signature")
        if (PayRequest.isBase58Address(ref) && sig.length in 60..100) Paid(ref, sig) else null
    } catch (e: Exception) {
        null
    }

    /**
     * A POST whose answer we need, not just its success. Null on anything that went wrong —
     * no server, no network, a refusal — because every caller's honest fallback is to say
     * nothing rather than to guess.
     */
    /**
     * @param timeoutMs how long to wait. Eight seconds suits our own endpoints, which answer out
     *   of sqlite. It is not enough for the ones that stand in front of somebody else's API: a
     *   cold operator lookup takes about three seconds upstream, and a phone on ordinary wifi
     *   ran past the budget and reported "could not check" for an answer that arrived fine.
     */
    internal suspend fun ask(path: String, body: JSONObject, timeoutMs: Int = 8_000): JSONObject? {
        if (!enabled) return null
        return withContext(Dispatchers.IO) {
            try {
                val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("Content-Type", "application/json")
                }
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                if (c.responseCode !in 200..299) return@withContext null
                JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            } catch (e: Exception) {
                null
            }
        }
    }

    /** The same, for a plain read. */
    internal suspend fun read(path: String): JSONObject? {
        if (!enabled) return null
        return withContext(Dispatchers.IO) {
            try {
                val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                }
                if (c.responseCode !in 200..299) return@withContext null
                JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun post(path: String, body: JSONObject): Boolean {
        if (!enabled) return false
        return withContext(Dispatchers.IO) {
            try {
                val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("Content-Type", "application/json")
                }
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                c.responseCode in 200..299
            } catch (e: Exception) {
                false
            }
        }
    }
}
