package so.duffle.app

import android.content.Context

/**
 * Everything worth telling someone, in one list.
 *
 * **Derived, not stored.** Every line here is already a fact in [Inbox], [Watch], [Messages] or
 * [Orders] — a request that is waiting, one that was declined, a message, a recharge that has
 * not arrived. A separate notifications table would be a second copy of all of it, free to
 * disagree with the first: a row saying "they declined" after the decline was undone, or a
 * badge counting something the thread no longer shows. The only thing kept on disk is **when
 * this screen was last opened**, which is all an unread count needs.
 *
 * The consequence to keep in mind: an alert cannot be dismissed on its own, because there is
 * nothing to dismiss. It stops appearing when the thing it is about stops being true.
 */
object Alerts {

    /** What a line is about, which decides its icon and where tapping it goes. */
    enum class Kind { ASKED, DECLINED, LATER, RECEIVED, WITHDRAWN, MESSAGE, OFFER, ORDER }

    data class Alert(
        val kind: Kind,
        val title: String,
        val detail: String,
        val at: Long,
        /** The other phone, when there is one — tapping opens their thread. */
        val peer: String? = null,
    )

    private const val PREFS = "alerts"
    private const val SEEN = "seen_at"

    fun seenAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(SEEN, 0L)

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(SEEN, System.currentTimeMillis()).apply()
    }

    /**
     * When an offer first turned up on this phone.
     *
     * An offer carries no date of its own — the server publishes it and it is simply there —
     * so without this it would have to be stamped "now" on every build, sit permanently at the
     * top and never stop counting as unread. Recorded once, the first time it is seen.
     */
    fun firstSeen(context: Context, id: String): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "offer_$id"
        prefs.getLong(key, 0L).takeIf { it > 0 }?.let { return it }
        val now = System.currentTimeMillis()
        prefs.edit().putLong(key, now).apply()
        return now
    }

    /**
     * Builds the list, newest first.
     *
     * `name` turns an address into whatever the rest of the app calls that person, so a line
     * never reads as 44 base58 characters. Offers are passed in rather than fetched: they come
     * from the server and this is a pure function of what the phone already holds.
     */
    fun build(
        toPay: List<Inbox.Item>,
        owed: List<Watch.Entry>,
        notes: List<Messages.Note>,
        orders: List<Orders.Order>,
        offers: List<Rewards.Offer>,
        name: (String) -> String,
        offerAt: (String) -> Long,
    ): List<Alert> {
        val out = mutableListOf<Alert>()

        for (i in toPay) {
            val who = name(i.request.recipient)
            val money = "${i.request.amount} ${i.request.symbol}"
            when {
                // Someone asking for money is the only thing here that needs doing rather
                // than knowing, so it stays listed for as long as it is unanswered.
                i.payable -> out += Alert(Kind.ASKED, "$who asked you for $money",
                    "Waiting for you", i.readAt, i.request.recipient)
                i.withdrawn -> out += Alert(Kind.WITHDRAWN, "$who took back a request",
                    "$money — nothing is owed", i.readAt, i.request.recipient)
            }
        }

        for (e in owed) {
            val who = e.peer?.let(name) ?: "Someone"
            val money = "${e.amount} ${e.symbol}"
            when {
                e.paid -> out += Alert(Kind.RECEIVED, "$who paid you $money",
                    "Confirmed on-chain", e.paidAt ?: e.createdAt, e.peer)
                e.rejected -> out += Alert(Kind.DECLINED, "${e.rejectedBy} declined your request",
                    "$money — nothing was paid", e.createdAt, e.peer)
                e.pending -> out += Alert(Kind.LATER, "${e.deferredBy} will pay later",
                    "$money — still watching for it", e.createdAt, e.peer)
            }
        }

        // Ours are left out: a notification about something you just typed is noise.
        for (n in notes.filter { !it.mine }) {
            out += Alert(Kind.MESSAGE, name(n.peer), n.body, n.at, n.peer)
        }

        for (o in orders) {
            when {
                o.waiting -> out += Alert(Kind.ORDER, "${o.name} is on its way",
                    "${o.value} · paid ${o.paid}", o.at)
                o.delivered -> out += Alert(Kind.ORDER, "${o.name} delivered",
                    "${o.value} · paid ${o.paid}", o.at)
                else -> out += Alert(Kind.ORDER, "${o.name} didn't go through",
                    "Paid ${o.paid} — ${o.status}", o.at)
            }
        }

        // Only ones that can still be acted on. An offer already claimed, or spent, is history.
        for (o in offers.filter { it.available }) {
            val from = if (o.funder == "duffle") "from AirPay" else "from ${o.funder}"
            out += Alert(Kind.OFFER, o.what, "${o.reward} SKR · $from", offerAt(o.id))
        }

        return out.sortedByDescending { it.at }
    }

    fun unread(list: List<Alert>, seenAt: Long): Int = list.count { it.at > seenAt }
}
