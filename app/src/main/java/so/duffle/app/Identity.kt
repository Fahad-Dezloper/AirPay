package so.duffle.app

import android.content.Context

/** The one thing AirPay remembers about you: the email recharge updates and refunds go to. */
object Identity {

    private const val PREFS = "identity"

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
}
