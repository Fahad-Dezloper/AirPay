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
    /**
     * Which one: the payment's reference, the offer, the payout's signature, the friend.
     *
     * The same event reaches the phone twice when the app is open, once on the live stream and
     * once as a push, and both are built by [from] out of the same fields. This is what lets
     * the second be recognised as the first. Blank when the server sent nothing to tell two
     * of a kind apart.
     */
    val id: String = "",
) {
    /**
     * What this replaces in the notification shade and on the notifications page.
     *
     * A payout says "on its way" and then "arrived"; they share a slot so the second takes the
     * place of the first instead of sitting under it. Everything else is its own line.
     */
    val slot: String get() = if (kind == "payout") "payout:$id" else "$kind:$state:$id"

    /** Where on the scale from "money" to "advertising" this sits. See [Push.Level]. */
    val quiet: Boolean get() = when (kind to state) {
        // A nudge to go and do something, an offer that lapsed, and the half of a payout that
        // "arrived" is about to repeat. None of them is worth a sound.
        "offer" to "new", "offer" to "expired", "payout" to "sent" -> true
        else -> false
    }

    /** (title, text) for a notification or a log line. Null for an event with nothing to say. */
    fun words(): Pair<String, String>? = when (kind to state) {
        "reward" to "earned" -> "You earned $skr SKR" to "Scratch your card and it goes straight to your wallet"
        // Settled means the week in which it could be taken back is over. Nothing has been sent:
        // a card is paid when it is scratched. This used to say "is on its way", which was the
        // payout's sentence and was not true here.
        "reward" to "settled" -> "Your $skr SKR is ready" to "Scratch your card and it goes straight to your wallet"
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
            // Whichever the server sent, most specific first. An offer is named by the offer; a
            // reward by the payment it was for; a payout by its transaction; a referral by the
            // friend.
            val id = listOf("offer", "reference", "signature", "invitee", "inviter")
                .firstNotNullOfOrNull { k -> f(k)?.takeIf { it.isNotBlank() } }.orEmpty().take(100)
            return Progress(type, state, skr, f("app").orEmpty().take(48), f("reason").orEmpty().take(120),
                f("detail").orEmpty().take(120), id)
        }
    }
}

/** A counter screens key their loaders on, so any progress event re-reads them all at once. */
object Live {
    var tick by mutableIntStateOf(0)
        private set

    fun bump() { tick++ }
}
