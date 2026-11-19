package so.duffle.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The AirPay backend: sign-in, the recharge catalogue and orders, push registration, and a
 * borrowed Solana RPC. The app still builds every payment itself and checks the amount against
 * the plan shown (TopUp.matchesPlan), so a wrong answer from here cannot change what is signed.
 */
object ServerClient {

    private val base = BuildConfig.TAP_SERVER_URL.trimEnd('/')

    val enabled get() = base.isNotBlank()

    /** Solana reads and sends, borrowed from the server's provider. See Chain.post. */
    val rpcUrl get() = "$base/v1/rpc"

    /** Where to send a push when a payment to `recipient` lands. */
    suspend fun registerDevice(recipient: String, pushToken: String): Boolean =
        post("/v1/devices", JSONObject().apply {
            put("recipient", recipient)
            put("push_token", pushToken)
        })

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
                Session.apply(c)
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                // A 401 anywhere but sign-in itself means the session died: sign in again.
                if (c.responseCode == 401 && !path.startsWith("/v1/auth/")) Session.stale()
                if (c.responseCode !in 200..299) return@withContext null
                JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * [ask], but telling the caller the status too — for the few answers where WHICH refusal it was
     * matters (a wallet that already belongs to another account is 409, not just "no").
     * Returns (status, body); status 0 when the server could not be reached.
     */
    internal suspend fun askRaw(path: String, body: JSONObject, timeoutMs: Int = 8_000): Pair<Int, JSONObject?> {
        if (!enabled) return 0 to null
        return withContext(Dispatchers.IO) {
            try {
                val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    setRequestProperty("Content-Type", "application/json")
                }
                Session.apply(c)
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                code to runCatching { JSONObject(text) }.getOrNull()
            } catch (e: Exception) {
                0 to null
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
                Session.apply(c)
                if (c.responseCode == 401) Session.stale()
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
                Session.apply(c)
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                if (c.responseCode == 401) Session.stale()
                c.responseCode in 200..299
            } catch (e: Exception) {
                false
            }
        }
    }
}
