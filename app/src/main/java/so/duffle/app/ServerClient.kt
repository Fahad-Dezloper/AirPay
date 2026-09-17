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

    sealed interface Event
    data class Paid(val reference: String, val signature: String) : Event
    data class Requested(val request: PayRequest) : Event
    data class Tapped(val card: TapCard) : Event
    data class Withdrawn(val reference: String) : Event
    data class Rejected(val reference: String, val label: String) : Event

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
    ): Boolean = post("/v1/requests", JSONObject().apply {
        put("reference", reference)
        put("recipient", recipient)
        put("amount", amount)
        put("label", label)
        payer?.let { put("payer", it) }
        genesis?.let { put("genesis", it) }
    })

    /**
     * Takes a request back. Advisory by design: nothing can stop someone sending SOL to an
     * address, so this only stops the other phone asking — and the request stays watched, in
     * case they were already approving when it was withdrawn.
     */
    suspend fun withdraw(reference: String): Boolean =
        post("/v1/withdrawals", JSONObject().apply { put("reference", reference) })

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
    })

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
            PayRequest(
                recipient = o.getString("recipient"), amount = o.getString("amount"), token = "SOL",
                label = o.optString("label"), reference = o.getString("reference"),
                genesis = o.optString("genesis").takeIf { it.isNotBlank() },
            ).takeIf { it.validate() == null && it.lamports() != null }?.let { Requested(it) }
        } catch (e: Exception) { null }
        "withdrawn" -> try {
            JSONObject(json).getString("reference").takeIf { PayRequest.isBase58Address(it) }?.let { Withdrawn(it) }
        } catch (e: Exception) { null }
        "rejected" -> try {
            val o = JSONObject(json)
            o.getString("reference").takeIf { PayRequest.isBase58Address(it) }
                ?.let { Rejected(it, o.optString("label")) }
        } catch (e: Exception) { null }
        "tap" -> try {
            val o = JSONObject(json)
            TapCard(o.getString("address"), o.optString("label"), o.optString("genesis").takeIf { it.isNotBlank() })
                .takeIf { it.validate() == null }?.let { Tapped(it) }
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
