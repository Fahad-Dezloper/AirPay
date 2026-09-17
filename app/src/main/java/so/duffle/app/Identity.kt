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
        return TapCard(address, label(context), genesis).takeIf { it.validate() == null }
    }

    fun rememberGenesis(context: Context, address: String, mint: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("genesis_for", address)
            .putString("genesis", mint)
            .apply()
    }

    /** The name other people see. The phone model until the user picks one. */
    fun label(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("name", null)
            ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

    fun setLabel(context: Context, name: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", name.trim().take(40))
            .apply()
    }
}
