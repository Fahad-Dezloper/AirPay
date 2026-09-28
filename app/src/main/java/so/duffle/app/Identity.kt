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
        // A link only exists for a wallet that borrowed its name from another, so the common
        // case — tapping from the wallet that owns the name — adds nothing to the payload.
        val link = Vouch.of(context, address)
        return TapCard(
            address = address,
            label = label(context),
            genesis = genesis,
            brand = Brand.mine(),
            owner = link?.owner,
            linkExpiry = link?.expiresDay ?: 0,
            linkSignature = link?.signature,
        ).takeIf { it.validate() == null }
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
     * Where gift-card codes should be sent, if anywhere.
     *
     * Bitrefill notifies whoever the invoice names. With no email on it that is the account
     * holding the API key — which is us, not the person who paid. The code is shown in the app
     * either way; this is the copy that survives a reinstall, a lost phone, or simply not
     * looking at the screen in the ten seconds after buying.
     *
     * Optional on purpose, and stored only on this phone. Nobody should have to hand over an
     * address to spend their own money.
     */
    fun email(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("email", null)
            ?.takeIf { it.isNotBlank() }

    fun setEmail(context: Context, email: String) {
        val clean = email.trim().takeIf { looksLikeEmail(it) }
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (clean == null) e.remove("email") else e.putString("email", clean)
        e.apply()
    }

    /**
     * Shape only, and deliberately loose.
     *
     * The address is handed to Bitrefill, which will do its own validation; the job here is to
     * catch a typo before somebody pays, not to adjudicate what an address may contain. Clever
     * regexes reject real addresses.
     */
    fun looksLikeEmail(s: String): Boolean {
        val t = s.trim()
        val at = t.indexOf('@')
        return at > 0 && at == t.lastIndexOf('@') && t.length > at + 3 &&
            '.' in t.substring(at) && !t.any { it.isWhitespace() }
    }

    /**
     * The domain this wallet owns, remembered from the last successful lookup.
     *
     * Cached because the HCE service and the home screen both want it and neither can wait for
     * mainnet. Only ever written after [Names] gives a definite answer, and tied to the wallet
     * it was looked up for, so switching wallets cannot inherit the previous one's name.
     */
    fun domain(context: Context, address: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("domain_$address", null)

    fun rememberDomain(context: Context, address: String, domain: String?) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (domain == null) e.remove("domain_$address") else e.putString("domain_$address", domain)
        e.apply()
    }

    /**
     * Which of these wallets carries a name, and what it is.
     *
     * The answer to "who am I" for the whole app. Your identity lives on one address — the
     * Seeker wallet holding the `.skr` — and every other wallet borrows it through a signature
     * ([Vouch]), so this is the wallet that does the vouching.
     *
     * Only consults what a previous lookup already established. It is called while building a
     * tap payload, and a tap has about 100ms.
     */
    fun named(context: Context, addresses: List<String>): Pair<String, String>? =
        addresses.firstNotNullOfOrNull { a -> domain(context, a)?.let { a to it } }
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
