package so.duffle.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.HttpURLConnection
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Who the server believes this phone is — proven by a signature, not claimed.
 *
 * The server used to trust the address in every request. Now the wallet signs a one-time
 * challenge once, the server hands back a random session token, and every later call carries it;
 * the server takes the address from the token and ignores any the body names.
 *
 * **The token is a bearer secret**, so it is never stored in the clear: it is encrypted with an
 * AES-GCM key that lives in the Android Keystore and cannot be exported, and what lands in the
 * app's private preferences is only ciphertext. Losing the token costs one wallet prompt, not
 * money, but it should still not sit in a readable file.
 */
object Session {
    private const val ALIAS = "airpay.session.key"
    private const val PREFS = "session"

    @Volatile private var token: String? = null
    /** The wallet that signed in. Which wallets the session covers is [wallets]. */
    @Volatile var address: String? = null
        private set
    /**
     * Every wallet the server knows to be this person's. One signature covers all of them: the
     * session belongs to the account, so switching wallets asks for nothing.
     */
    @Volatile private var wallets: Set<String> = emptySet()

    /** Which wallet a request is about. Set by the activity; sent as `X-Wallet`. */
    @Volatile var acting: (() -> String?)? = null

    /** A session exists, for some wallet of the account — the starting point for linking another. */
    val hasToken: Boolean get() = token != null

    /** Bumped whenever the session appears or goes away, so screens and streams can restart on it. */
    var version by mutableIntStateOf(0)
        private set

    /** True once an attempt ended without a session (declined, closed, or unreachable); cleared when the next one starts. */
    var failed by mutableStateOf(false)

    /** True while the wallet is being asked to sign, so a screen can say so instead of offering the button again. */
    var signingIn by mutableStateOf(false)

    /**
     * Set by the activity: ask the wallet to sign in now. A screen that finds nobody signed in
     * calls this from a button — the app never opens the wallet for it behind the person's back.
     */
    var authorize: (() -> Unit)? = null

    fun header(): String? = token?.let { "Bearer $it" }

    fun valid(forAddress: String?): Boolean =
        token != null && forAddress != null && forAddress in wallets

    /** Attach the session to one HTTP call to OUR server. A no-op without a session. */
    fun apply(c: HttpURLConnection) {
        header()?.let {
            c.setRequestProperty("Authorization", it)
            // A choice among wallets the server already knows are this person's — never a claim:
            // it ignores one outside the account.
            acting?.invoke()?.let { w -> c.setRequestProperty("X-Wallet", w) }
        }
    }

    /** The server said 401: the token is dead (expired, revoked, or the server was reset). */
    fun stale() {
        if (token == null) return
        token = null
        version++
    }

    fun init(context: Context) {
        val blob = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val addr = blob.getString("address", null)
        val cipher = blob.getString("token", null)
        if (addr != null && cipher != null) {
            decrypt(cipher)?.let {
                token = it; address = addr
                // A session saved before accounts covers just the wallet that signed.
                wallets = parseWallets(blob.getString("wallets", null)) + addr
                version++
            }
        }
    }

    fun clear(context: Context) {
        token = null; address = null; wallets = emptySet()
        // The last attempt's outcome goes with the session it was for. Kept, the next wallet's
        // sign-in sheet opened already saying "That didn't go through".
        failed = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        version++
    }

    private fun save(context: Context, addr: String, t: String, covered: Set<String>) {
        token = t; address = addr; wallets = covered + addr
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("address", addr).putString("token", encrypt(t))
            .putString("wallets", JSONArray(wallets.toList()).toString()).apply()
        version++
    }

    /** The account gained wallets (a link just succeeded): remember them, same session. */
    private fun addWallets(context: Context, more: Set<String>) {
        wallets = wallets + more
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("wallets", JSONArray(wallets.toList()).toString()).apply()
        version++
    }

    private fun parseWallets(raw: String?): Set<String> = try {
        val a = JSONArray(raw ?: "[]")
        (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    private fun walletsOf(o: JSONObject): Set<String> =
        parseWallets(o.optJSONArray("wallets")?.toString())

    /**
     * One challenge, one wallet prompt, one session. False if the wallet declined, the server
     * refused, or either could not be reached — in every case nothing is stored.
     */
    suspend fun signIn(context: Context, wallet: Wallet, addr: String): Boolean {
        val challenge = ServerClient.ask("/v1/auth/challenge", JSONObject().put("address", addr)) ?: return false
        val nonce = challenge.optString("nonce")
        val message = challenge.optString("message")
        // What gets signed is the server's own text, byte for byte. Anything that does not name
        // THIS address is refused here too: a wallet should never be asked to sign somebody else's.
        if (nonce.length != 32 || !message.contains(addr)) return false
        val sig = wallet.signMessage(addr, message.toByteArray(Charsets.UTF_8)) ?: return false
        val out = ServerClient.ask(
            "/v1/auth/verify",
            JSONObject().put("nonce", nonce).put("signature", Base64.encodeToString(sig, Base64.NO_WRAP)),
        ) ?: return false
        val t = out.optString("token")
        if (!t.startsWith("ap1_") || out.optString("address") != addr) return false
        save(context, addr, t, walletsOf(out))
        return true
    }

    /** How adding a wallet to the signed-in account came out. */
    enum class Link { OK, IN_USE, FAILED }

    /**
     * Proves to the server that [addr] is this person's too, so the session covers it.
     *
     * The wallet signs a sentence that names it — once, when it is first used. That proof is what
     * stops anybody attaching a wallet they do not control. After it, switching between wallets
     * never asks again. [Link.IN_USE]: this wallet already belongs to a DIFFERENT account (another
     * phone, another sign-up) and cannot be taken over; the caller signs in with it instead.
     */
    suspend fun link(context: Context, wallet: Wallet, addr: String): Link {
        if (token == null) return Link.FAILED
        val (cc, challenge) = ServerClient.askRaw(
            "/v1/auth/challenge", JSONObject().put("address", addr).put("purpose", "link"),
        )
        if (cc !in 200..299 || challenge == null) return Link.FAILED
        val nonce = challenge.optString("nonce")
        val message = challenge.optString("message")
        if (nonce.length != 32 || !message.contains(addr)) return Link.FAILED
        val sig = wallet.signMessage(addr, message.toByteArray(Charsets.UTF_8)) ?: return Link.FAILED
        val (lc, out) = ServerClient.askRaw(
            "/v1/auth/link",
            JSONObject().put("nonce", nonce).put("signature", Base64.encodeToString(sig, Base64.NO_WRAP)),
        )
        if (lc == 409) return Link.IN_USE
        if (lc !in 200..299 || out == null) return Link.FAILED
        addWallets(context, walletsOf(out) + addr)
        return Link.OK
    }

    // -- the Keystore key ---------------------------------------------------------------------

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** Null on any failure — a key that was wiped, or a blob that was tampered with, is "no session". */
    private fun decrypt(blob: String): String? = try {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) {
        null
    }
}
