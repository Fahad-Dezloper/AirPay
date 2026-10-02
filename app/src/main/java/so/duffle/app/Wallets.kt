package so.duffle.app

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
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
     * What this ACCOUNT calls itself, when that is a name rather than the app's own brand.
     *
     * Seeker Wallet reports an SNS domain — "dezloper.skr" — which is the owner's name and
     * worth showing. Phantom reports "Phantom", which says nothing the icon beside it has not
     * already said. Null for the second kind, so the caller can fall back to the address.
     */
    val username: String?
        get() = name.trim().takeIf { it.isNotBlank() && BRANDS.none { b -> it.equals(b, true) } }

    /**
     * A colour per wallet, keyed on what the wallet says it is.
     *
     * Brand colours where they are known, because a person recognises Phantom purple faster than
     * they read an address. Anything unknown gets a stable colour derived from its own address,
     * so two wallets never look the same and the same wallet never changes colour.
     */
    val colour: Color
        //: These four are the values settled in the HTML sandbox (ui/src/data.js) — each card
        //: painted to agree with the wallet's own app icon rather than with its published brand
        //: hex, because the icon and the card sit a few pixels apart and a near-match reads
        //: worse than either one alone.
        get() = when {
            matches("phantom") -> Color(0xFF9886E5)
            matches("solflare") -> Color(0xFFFFEF47)
            matches("backpack") -> Color(0xFF212122)
            // ".skr" catches Seeker Wallet by the only thing it says about itself: the account
            // name it reports is an SNS domain on that TLD. A wallet with no package and no
            // brand in its name is the built-in one on this phone.
            matches("seeker", "solanamobile", "seed vault", ".skr") -> Color(0xFF14F195)
            matches("ledger") -> Color(0xFF2C323B)
            matches("ultimate") -> Color(0xFF3F8CFF)
            else -> SPARE[(address.hashCode().toUInt() % SPARE.size.toUInt()).toInt()]
        }

    /**
     * The card's fill in the fanned pile.
     *
     * A [Brush] rather than a [Color] because the Seeker's card is a gradient — black up to
     * the mint at the bottom, the phone's own colour run. Every other wallet is its flat brand
     * colour wrapped in a [SolidColor], so one call site paints them all.
     *
     * The gradient has to reach the card's OUTER edge: drawn inside the 1dp border instead, a
     * black hairline showed along the bottom where the last stop stopped short.
     */
    val fill: Brush
        get() = when {
            matches("seeker", "solanamobile", "seed vault", ".skr") -> Brush.verticalGradient(
                0f to Color(0xFF000000),
                .18f to Color(0xFF00080D),
                .38f to Color(0xFF00283B),
                .60f to Color(0xFF007D9F),
                .78f to Color(0xFF18C8D0),
                1f to Color(0xFF7FFFE9),
            )
            else -> SolidColor(colour)
        }

    /**
     * Seeker's entry is the original "Seeker + r" artwork (`public/seekerr.png`), centre-cropped
     * to a square so it needs no letterbox wherever a wallet mark is drawn in a square box.
     */
    val iconRes: Int
        get() = when {
            matches("phantom") -> R.drawable.ic_wallet_phantom
            matches("solflare") -> R.drawable.ic_wallet_solflare
            matches("backpack") -> R.drawable.ic_wallet_backpack
            matches("seeker", "solanamobile", "seed vault", ".skr") -> R.drawable.brand_seeker_classic
            matches("ledger") -> R.drawable.ic_wallet_ledger
            else -> R.drawable.ic_wallet_chip
        }

    /**
     * The mark on the wallet CARD in the pile beside the balance.
     *
     * Seeker has two marks and each has its own job. On the card it is `seeker.png` (the small
     * square icon); everywhere else the wallet appears — the line above the total balance, the
     * profile panel, the wallet details, a thread — it is the "Seeker + r" artwork,
     * [iconRes]. Every other wallet uses one mark for both.
     */
    val cardIconRes: Int
        get() = if (matches("seeker", "solanamobile", "seed vault", ".skr")) R.drawable.ic_wallet_seeker
        else iconRes

    val iconTint: Color?
        get() = when {
            matches("phantom") -> null // Intrinsic white with purple pupils
            // Null: this one is the wallet's real app icon, a tile with its own background.
            // Tinting a photograph flattens it to a silhouette — the thin vector it replaced
            // read as a scribble at 20dp, which is what this fixes.
            matches("solflare") -> null
            matches("backpack") -> null // Intrinsic crimson red backpack
            // Null for the same reason as Solflare: this is the wallet's real app icon, a tile
            // with its own background. Tinted, it was a set of loose black bars floating on
            // the page while the other three read as app icons.
            matches("seeker", "solanamobile", "seed vault", ".skr") -> null
            matches("ledger") -> Color.White
            else -> Color.White
        }

    /**
     * The package is checked first, and it is the only reliable half.
     *
     * A wallet's `name` is whatever it chose to call the ACCOUNT, not itself: Seeker Wallet
     * reports an SNS domain ("dezloper.skr"), which matches nothing, so the Seeker card came
     * out of the spare palette instead of Solana green. The package — app.phantom,
     * com.solflare.mobile, app.backpack.mobile.standalone — is the app's own identity and
     * cannot be renamed by its owner.
     */
    /**
     * The same brand colour, dark enough to be read as text on white.
     *
     * A card can be any colour; a word cannot. Backpack red passes as it is, but Phantom's
     * lavender and Seeker's Solana green are near-invisible at 13sp on a white page — they are
     * chosen to sit behind white text, not to be it. Anything too light is scaled down until it
     * carries, which keeps the hue and only takes the brightness.
     */
    val ink: Color
        get() {
            val c = if (matches("backpack")) Color(0xFFE33E3F) else colour
            val lum = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
            if (lum <= LEGIBLE) return c
            val k = LEGIBLE / lum
            return Color(c.red * k, c.green * k, c.blue * k)
        }

    private fun matches(vararg needles: String): Boolean {
        val hay = "$name ${uri.orEmpty()} ${pkg.orEmpty()}".lowercase()
        return needles.any { it in hay }
    }

    private companion object {
        /** Names a wallet app gives itself. Anything else in `name` is the account's own. */
        val BRANDS = listOf(
            "phantom", "solflare", "backpack", "ledger", "ultimate", "seeker", "seed vault",
            "solana mobile", "wallet",
        )

        /** Brightness a colour has to be under before it reads as text on white. */
        const val LEGIBLE = 0.45f

        val SPARE = listOf(
            Color(0xFFFFCF33), Color(0xFF00D2C4), Color(0xFF008FFF),
            Color(0xFFAB9FF2), Color(0xFFFF7A6B), Color(0xFF1B1D22),
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
