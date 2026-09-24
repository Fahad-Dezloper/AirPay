package so.duffle.app

import android.content.Context
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/**
 * The wallets this phone has linked, and which one is currently AirPay's.
 *
 * One wallet was never enough: a Seeker owner keeps the hardware-backed wallet for anything that
 * matters and a hot wallet for spending, and the whole point of tapping is that it is casual.
 * The selected wallet is the one a tap pays from and the one a request is addressed to — so
 * switching is not a preference, it is "which pocket am I using".
 *
 * **Only the auth token is stored, never a key.** AirPay cannot sign anything on its own; each
 * of these is a session with a wallet app that can.
 *
 * **How a wallet is reached** decides what this list can promise. The Seeker ships a persistent
 * preferred activity for the `solana-wallet` scheme, so every untargeted intent lands in Seeker
 * Wallet no matter which session is offered — and a token from one wallet means nothing to
 * another. Wallets linked through the picker therefore carry their [Linked.pkg] and are reached
 * by explicit intent, which skips that routing; see [WalletLink]. Anything linked before the
 * picker existed has no package and keeps the original path.
 */
data class Linked(
    val address: String,
    /** The MWA session token. Null once it goes stale — the wallet is still listed. */
    val token: String?,
    /** What the wallet called itself, e.g. "Phantom". */
    val name: String,
    /** `walletUriBase` from the authorization, which is how the wallet is recognised again. */
    val uri: String?,
    val addedAt: Long,
    /**
     * The wallet app's package, when it was linked through the picker.
     *
     * Its presence is what decides how this wallet is reached: with it, an explicit intent that
     * skips the Seeker's ROM-level default; without it, the original adapter. A wallet linked
     * before the picker existed has none, and keeps working the way it always did.
     */
    val pkg: String? = null,
) {
    val short: String get() = "${address.take(4)}…${address.takeLast(4)}"

    /**
     * A colour per wallet, keyed on what the wallet says it is.
     *
     * Brand colours where they are known, because a person recognises Phantom purple faster than
     * they read an address. Anything unknown gets a stable colour derived from its own address,
     * so two wallets never look the same and the same wallet never changes colour.
     */
    val colour: Color
        get() = when {
            matches("phantom") -> Color(0xFFAB9FF2)
            matches("solflare") -> Color(0xFFFC7227)
            matches("backpack") -> Color(0xFFE33E3F)
            matches("seeker", "solanamobile", "seed vault") -> Color(0xFF14F195)
            matches("ultimate") -> Color(0xFF3F8CFF)
            else -> SPARE[(address.hashCode().toUInt() % SPARE.size.toUInt()).toInt()]
        }

    private fun matches(vararg needles: String): Boolean {
        val hay = "$name ${uri.orEmpty()}".lowercase()
        return needles.any { it in hay }
    }

    private companion object {
        val SPARE = listOf(
            Color(0xFF008FFF), Color(0xFFFFC53D), Color(0xFF2BD9A6),
            Color(0xFFFF7A6B), Color(0xFF8A7BFF), Color(0xFF38BDF8),
        )
    }
}

class Wallets(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("wallets", Context.MODE_PRIVATE)

    fun all(): List<Linked> = try {
        val arr = JSONArray(prefs.getString(KEY_LIST, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val address = o.optString("a")
            if (!PayRequest.isBase58Address(address)) null
            else Linked(
                address = address,
                token = o.optString("t").ifBlank { null },
                name = o.optString("n").ifBlank { "Wallet" },
                uri = o.optString("u").ifBlank { null },
                addedAt = o.optLong("at"),
                pkg = o.optString("p").ifBlank { null },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** The wallet AirPay is using, or null when none is linked. */
    fun selected(): Linked? {
        val list = all()
        val chosen = prefs.getString(KEY_SELECTED, null)
        return list.firstOrNull { it.address == chosen } ?: list.firstOrNull()
    }

    fun select(address: String) {
        if (all().any { it.address == address }) prefs.edit().putString(KEY_SELECTED, address).apply()
    }

    /**
     * Links a wallet, or refreshes the session of one already linked. Selecting it is the
     * caller's decision: connecting a second wallet to look at it is not the same as choosing
     * to pay from it.
     */
    fun add(address: String, token: String?, name: String, uri: String?, pkg: String? = null): Linked {
        val existing = all().firstOrNull { it.address == address }
        val linked = Linked(
            address = address,
            token = token ?: existing?.token,
            // A wallet that has identified itself once keeps that name through a session it
            // declined to name — better than a card that turns into "Wallet" on a reconnect.
            name = name.ifBlank { existing?.name ?: "Wallet" },
            uri = uri ?: existing?.uri,
            addedAt = existing?.addedAt ?: System.currentTimeMillis(),
            pkg = pkg ?: existing?.pkg,
        )
        save(all().filterNot { it.address == address } + linked)
        if (prefs.getString(KEY_SELECTED, null) == null) select(address)
        return linked
    }

    /** The picker's name for a wallet beats a domain: "Phantom", not "phantom.app". */
    fun rename(address: String, name: String) {
        save(all().map { if (it.address == address) it.copy(name = name) else it })
    }

    /** The token went stale. The wallet stays listed — it is still theirs, it just needs a tap. */
    fun clearToken(address: String) {
        save(all().map { if (it.address == address) it.copy(token = null) else it })
    }

    fun remove(address: String) {
        save(all().filterNot { it.address == address })
        if (prefs.getString(KEY_SELECTED, null) == address) {
            prefs.edit().remove(KEY_SELECTED).apply()
            all().firstOrNull()?.let { select(it.address) }
        }
    }

    private fun save(list: List<Linked>) {
        val arr = JSONArray()
        list.sortedBy { it.addedAt }.forEach { w ->
            arr.put(JSONObject().apply {
                put("a", w.address)
                w.token?.let { put("t", it) }
                put("n", w.name)
                w.uri?.let { put("u", it) }
                w.pkg?.let { put("p", it) }
                put("at", w.addedAt)
            })
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private companion object {
        const val KEY_LIST = "list"
        const val KEY_SELECTED = "selected"
    }
}
