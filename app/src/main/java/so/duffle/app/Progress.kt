package so.duffle.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Something about the person's own rewards moved: a reward was earned, became claimable or was
 * taken back; an offer was completed or lapsed; a friend joined or paid; SKR was sent or arrived.
 *
 * One shape for all of them because the app does one thing on any of them — say so, and re-read —
 * and because the same event arrives two ways (the live stream while the app is open, a push while
 * it is closed) and has to read identically on both.
 */
data class Progress(
    val kind: String,        // reward | offer | referral | payout
    val state: String,       // earned | settled | reversed | paid | expired | joined | claimed | sent | confirmed
    val skr: Int = 0,
    val app: String = "",
    val reason: String = "",
    /** What the offer asks, in a line — for a "new offer" push. */
    val detail: String = "",
) {
    /** (title, text) for a notification or a log line. Null for an event with nothing to say. */
    fun words(): Pair<String, String>? = when (kind to state) {
        "reward" to "earned" -> "You earned $skr SKR" to "Scratch your card and it goes straight to your wallet"
        "reward" to "settled" -> "$skr SKR is on its way" to "It is being sent to your wallet"
        "reward" to "reversed" -> "$skr SKR taken back" to reason.ifBlank { "It did not meet the rules" }
        "offer" to "new" -> "Earn $skr SKR with ${app.ifBlank { "an offer" }}" to detail.ifBlank { "A new offer is waiting in AirPay" }
        "offer" to "paid" -> "Offer complete" to "You earned $skr SKR from ${app.ifBlank { "an offer" }}"
        "offer" to "expired" -> "Offer ended" to "${app.ifBlank { "An offer" }} ran out of time"
        "referral" to "joined" -> "A friend joined" to "They used your code. You earn when they make their first payment"
        "referral" to "claimed" -> "Code accepted" to "Your first payment earns SKR, and your friend earns too"
        "referral" to "paid" -> "Your friend paid" to "You earned $skr SKR for inviting them"
        "payout" to "sent" -> "$skr SKR is on its way" to "It will arrive in your wallet in a moment"
        "payout" to "confirmed" -> "$skr SKR arrived" to "It is in your wallet now"
        else -> null
    }

    companion object {
        /** From the server's field names. The server's older "reward" event has no state: earned. */
        fun from(type: String, f: (String) -> String?): Progress? {
            if (type !in setOf("reward", "offer", "referral", "payout")) return null
            val skr = f("skr")?.toDoubleOrNull()?.toInt() ?: 0
            val state = f("state")?.takeIf { it.isNotBlank() }
                ?: if (type == "offer") "paid" else "earned"
            return Progress(type, state, skr, f("app").orEmpty().take(48), f("reason").orEmpty().take(120),
                f("detail").orEmpty().take(120))
        }
    }
}

/** A counter screens key their loaders on, so any progress event re-reads them all at once. */
object Live {
    var tick by mutableIntStateOf(0)
        private set

    fun bump() { tick++ }
}
