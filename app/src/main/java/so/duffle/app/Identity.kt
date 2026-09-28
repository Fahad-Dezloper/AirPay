package so.duffle.app

import android.content.Context

/**
 * This phone's card, readable when the app is not running.
 *
 * The HCE service is started by the NFC stack, not by the user, so it cannot ask the activity
 * who we are. It reads the same wallet prefs Wallet writes, and a Genesis mint cached the last
 * time it was looked up — never a network call inside a tap, which has ~100ms to answer.
 */
object Identity {

    private const val PREFS = "identity"

    fun card(context: Context): TapCard? {
        val address = context.getSharedPreferences("wallet", Context.MODE_PRIVATE)
            .getString("address", null) ?: return null
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // The cached mint only counts for the wallet it was looked up for.
        val genesis = p.getString("genesis", null)?.takeIf { p.getString("genesis_for", null) == address }
        return TapCard(address, label(context), genesis, Brand.mine()).takeIf { it.validate() == null }
    }

    fun rememberGenesis(context: Context, address: String, mint: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("genesis_for", address)
            .putString("genesis", mint)
            .apply()
    }

    /**
     * The name other people see, or "" when nobody has chosen one.
     *
     * It used to fall back to `Build.MODEL`, which is where "CPH2401" came from — and a phone
     * model is not a person. An empty name is better: the other phone then shows the address,
     * which is at least honestly anonymous rather than confidently wrong.
     *
     * This is **not** the name with the tick. That one is a `.skr` or `.sol` the other phone
     * resolves from the chain itself (see [Names]); this one is a claim we send them.
     */
    fun label(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("name", null)
            ?.takeIf { it.isNotBlank() } ?: ""

    /** Saves a chosen name, or clears it if the text is not usable. Returns what was stored. */
    fun setLabel(context: Context, name: String): String {
        val clean = Username.clean(name).orEmpty()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", clean)
            .apply()
        return clean
    }

    /**
     * The domain this wallet owns, remembered from the last successful lookup.
     *
     * Cached because the HCE service and the home screen both want it and neither can wait for
     * mainnet. Only ever written after [Names] gives a definite answer, and tied to the wallet
     * it was looked up for, so switching wallets cannot inherit the previous one's name.
     */
    fun domain(context: Context, address: String): String? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("domain", null)?.takeIf { p.getString("domain_for", null) == address }
    }

    fun rememberDomain(context: Context, address: String, domain: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("domain_for", address)
            .putString("domain", domain)
            .apply()
    }
}

/**
 * A name somebody typed for themselves.
 *
 * This is the weakest tier of identity in the app and the rules exist to keep it from looking
 * like a stronger one. A username travels over the radio, so the receiving phone has only the
 * sender's word for it — which is fine for remembering who someone was, and not fine for
 * deciding whether to trust them.
 *
 * **The dot is the important rule.** Without it somebody can call themselves `dezloper.skr` and
 * inherit the trust that an actual on-chain name earns, which would make the tick meaningless.
 * It is enforced here for what we send and again in [TapCard.validate] for what we receive,
 * because a modified client can put anything on the wire.
 */
object Username {

    const val MAX = 24

    /** The usable form of what someone typed, or null if there isn't one. */
    fun clean(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty() || name.length > MAX) return null
        if (looksLikeDomain(name)) return null
        if (name.any { it.code < 0x20 }) return null
        return name
    }

    /** A name pretending to be a `.skr` or `.sol`, which no username is allowed to do. */
    fun looksLikeDomain(name: String): Boolean = name.contains('.')

    /** Why a name was refused, for the field under the box. Null when it is fine. */
    fun problem(raw: String): String? {
        val name = raw.trim()
        return when {
            name.isEmpty() -> null                       // not an error, just unfinished
            looksLikeDomain(name) -> "No dots — those are for .skr and .sol names."
            name.length > MAX -> "A bit long — $MAX characters at most."
            name.any { it.code < 0x20 } -> "That has characters that won't show."
            else -> null
        }
    }
}
