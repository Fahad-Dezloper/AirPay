package so.duffle.app

import android.content.Context

/**
 * The category somebody filed a payment under, kept so it survives a restart.
 *
 * A payment to an address that is not a contact and not a shop has nothing in it that says what
 * it was FOR — only the two wallets and an amount. So the row asks, and the answer is stored
 * here rather than derived, because there is nothing to derive it from
 * (ui/PORT-2026-10-01-2.md §1.4).
 *
 * **Keyed by signature.** It is the one identifier a settled payment is guaranteed to have, it
 * is unique, and it cannot be confused with another payment to the same address for the same
 * amount — which is exactly the pair a wallet produces when somebody pays the same person twice.
 *
 * Only this phone ever sees it. "Only you see this" is on the picker, and it is true: nothing
 * here is sent anywhere.
 */
class Spends(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** What this payment was filed under, or null if nobody has said. */
    fun of(signature: String?): History.Spend? {
        val key = signature?.takeIf { it.isNotBlank() } ?: return null
        val name = prefs.getString(key, null) ?: return null
        // An unreadable value is not an answer. The enum could be renamed in a later version,
        // and a row with no category is honest where a crash is not.
        return runCatching { History.Spend.valueOf(name) }.getOrNull()
    }

    fun file(signature: String?, spend: History.Spend) {
        val key = signature?.takeIf { it.isNotBlank() } ?: return
        prefs.edit().putString(key, spend.name).apply()
    }

    private companion object {
        const val FILE = "spends"
    }
}
