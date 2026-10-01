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

    /**
     * One line.
     *
     * The fields below [peer] exist for ui/PORT-2026-10-02.md §7: **the icon says who or what
     * the notification is about, and a badge on its corner says what happened** — the same rule
     * the transaction rows took. Drawing that needs facts this list did not carry. They are
     * facts and not looks: a name, a colour seed, a shelf, a URL, whether the thing arrived.
     * The screen decides what each one is drawn as.
     */
    data class Alert(
        val kind: Kind,
        val title: String,
        val detail: String,
        val at: Long,
        /** The other phone, when there is one — tapping opens their thread. */
        val peer: String? = null,
        /**
         * What the icon is OF, as a display name: the person, or the app an offer is in.
         *
         * The face needs a letter, and the title is a sentence ("Aashu paid you 2.00 USDC",
         * "Payment declined"). The sandbox cuts the name back out of the title with a regular
         * expression; that works on its four fixed samples and not on a name containing the
         * words it strips. [build] already has the name in hand, so it is kept.
         */
        val who: String? = null,
        /**
         * What the face colour is seeded on: a wallet address, or an app's funder id.
         *
         * Never a name — `faceColour` is seeded on an id precisely because a display name can
         * change and the id cannot, so one person (or one app) keeps one colour. Usually the
         * same string as [peer], and separate from it because [peer] means "tapping opens this
         * thread": an offer has a colour and no thread.
         */
        val seed: String? = null,
        /**
         * A shop order's shelf ("Phone", "TV & streaming"), which picks its category mark.
         *
         * Stored on the order rather than looked up, so it survives the product going away.
         */
        val category: String? = null,
        /**
         * An offer's app icon.
         *
         * `/v1/offers` sends no such key today (see `Rewards.Offer.icon`), so this is almost
         * always null and the row falls back to the app's initial on its own colour. Wired
         * anyway, so the real icon appears the moment the server sends one; nothing maps a name
         * to a bundled asset, which would be right for the sample apps and wrong for the next.
         */
        val iconUrl: String? = null,
        /**
         * Whether the thing this is about actually arrived — the success badge.
         *
         * Only a true "it happened" earns the tick. An order still on its way, or one that did
         * not go through, is the same [Kind.ORDER] line, and a tick on it would claim a
         * delivery that has not happened.
         */
        val done: Boolean = false,
        /**
         * The money a declined ask was for, already formatted: "0.50 USDC".
         *
         * Shown struck through at the row's end, which is the only place a declined ask still
         * says a number — the detail line says nothing was paid, and the amount is what makes
         * that sentence mean something.
         */
        val amount: String? = null,
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
                    "Waiting for you", i.readAt, i.request.recipient,
                    who = who, seed = i.request.recipient)
                i.withdrawn -> out += Alert(Kind.WITHDRAWN, "$who took back a request",
                    "$money — nothing is owed", i.readAt, i.request.recipient,
                    who = who, seed = i.request.recipient)
            }
        }

        for (e in owed) {
            val who = e.peer?.let(name) ?: "Someone"
            val money = "${e.amount} ${e.symbol}"
            when {
                e.paid -> out += Alert(Kind.RECEIVED, "$who paid you $money",
                    "Confirmed on-chain", e.paidAt ?: e.createdAt, e.peer,
                    who = who, seed = e.peer, done = true)
                e.rejected -> out += Alert(Kind.DECLINED, "Payment declined",
                    "${e.rejectedBy} declined · nothing was paid", e.createdAt, e.peer,
                    who = who, seed = e.peer, amount = money)
                e.pending -> out += Alert(Kind.LATER, "${e.deferredBy} will pay later",
                    "$money — still watching for it", e.createdAt, e.peer,
                    who = who, seed = e.peer)
            }
        }

        // Ours are left out: a notification about something you just typed is noise.
        for (n in notes.filter { !it.mine }) {
            out += Alert(Kind.MESSAGE, name(n.peer), n.body, n.at, n.peer,
                who = name(n.peer), seed = n.peer)
        }

        for (o in orders) {
            when {
                o.waiting -> out += Alert(Kind.ORDER, "${o.name} is on its way",
                    "${o.value} · paid ${o.paid}", o.at, category = o.category)
                o.delivered -> out += Alert(Kind.ORDER, "${o.name} delivered",
                    "${o.value} · paid ${o.paid}", o.at, category = o.category, done = true)
                else -> out += Alert(Kind.ORDER, "${o.name} didn't go through",
                    "Paid ${o.paid} — ${o.status}", o.at, category = o.category)
            }
        }

        // Only ones that can still be acted on. An offer already claimed, or spent, is history.
        for (o in offers.filter { it.available }) {
            val from = if (o.funder == "duffle") "from AirPay" else "from ${o.funder}"
            out += Alert(Kind.OFFER, o.what, "${o.reward} SKR · $from", offerAt(o.id),
                who = o.app, seed = o.funder.ifBlank { o.app }.takeIf { it.isNotBlank() },
                iconUrl = o.icon)
        }

        return out.sortedByDescending { it.at }
    }

    fun unread(list: List<Alert>, seenAt: Long): Int = list.count { it.at > seenAt }
}
