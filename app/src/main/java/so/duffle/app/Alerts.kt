package so.duffle.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

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
 *
 * **The exception is [AlertLog].** Money moving in a wallet, a reward, a friend joining and a
 * tap are things that HAPPENED, and nothing else on the phone keeps them: they were shown as a
 * notification and then gone, so this page disagreed with the notification shade about what the
 * person had been told. Those are written down, once each, and listed beside the derived lines.
 */
object Alerts {

    /** What a line is about, which decides its icon and where tapping it goes. */
    enum class Kind {
        ASKED, DECLINED, LATER, RECEIVED, WITHDRAWN, MESSAGE, OFFER, ORDER,
        /** Money moved in one of our wallets outside an AirPay request. From [AlertLog]. */
        MONEY,
        /** A reward, a referral, a finished offer or a payout. From [AlertLog]. */
        REWARD,
        /** Somebody touched phones with us. From [AlertLog]. */
        TAPPED,
    }

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
         * An offer's app icon, or the logo of the brand a shop order was for.
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
        /**
         * For a [Kind.REWARD] line, which part of rewards it is about ("referral", "offer",
         * "reward", "payout"), so tapping it can open the referral sheet for a friend and the
         * rewards page for the rest. For a [Kind.MONEY] line, "in" or "out".
         */
        val topic: String? = null,
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
        /** What [AlertLog] has written down. Defaulted so the derived half can be built alone. */
        logged: List<AlertLog.Entry> = emptyList(),
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
                    "$money, nothing is owed", i.readAt, i.request.recipient,
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
                    "$money, still watching for it", e.createdAt, e.peer,
                    who = who, seed = e.peer)
            }
        }

        // Ours are left out: a notification about something you just typed is noise.
        for (n in notes.filter { !it.mine }) {
            out += Alert(Kind.MESSAGE, name(n.peer), n.body, n.at, n.peer,
                who = name(n.peer), seed = n.peer)
        }

        for (o in orders) {
            // The brand as people say it: "PhonePe", not the catalogue's "PhonePe India". The
            // same trim the system notification makes.
            val what = o.name.removeSuffix(" India").removeSuffix(" Bundles").trim().ifBlank { o.name }
            when {
                o.waiting -> out += Alert(Kind.ORDER, "$what is on its way",
                    "${o.value} · paid ${o.paid}", o.at, category = o.category, iconUrl = o.logoUrl)
                o.delivered -> out += Alert(Kind.ORDER, "$what delivered",
                    "${o.value} · paid ${o.paid}", o.at, category = o.category, iconUrl = o.logoUrl, done = true)
                else -> out += Alert(Kind.ORDER, "$what didn't go through",
                    "Paid ${o.paid}, ${o.status}", o.at, category = o.category, iconUrl = o.logoUrl)
            }
        }

        // Only ones that can still be acted on — and only ones the person has been TOLD about. An
        // offer that is live but has not been pushed yet stays off this page: they arrive one at a
        // time, hours apart, not all at once the moment somebody signs up. Stamped with the time
        // they were told, which is also what keeps them from counting as unread forever.
        for (o in offers.filter { it.available && it.notifiedAt > 0 }) {
            val from = if (o.funder == "duffle") "from AirPay" else "from ${o.funder}"
            out += Alert(Kind.OFFER, o.what, "${o.reward} SKR · $from", o.notifiedAt,
                who = o.app, seed = o.funder.ifBlank { o.app }.takeIf { it.isNotBlank() },
                iconUrl = o.icon)
        }

        // A payment this phone asked for and has confirmed already has its line above, with a
        // name and "Confirmed on-chain". The push's own note of the same payment gives way.
        val confirmed = owed.filter { it.paid }.map { "paid:${it.reference}" }.toSet()
        for (e in logged) {
            if (e.hard != null && e.hard in confirmed) continue
            val kind = runCatching { Kind.valueOf(e.kind) }.getOrNull() ?: continue
            // A tap is about a person, and their name may have been learned since it was
            // written down, so it is looked up now. The stored words are the fallback.
            val who = e.peer?.let(name)?.takeIf { it != "Someone" } ?: e.who
            out += Alert(
                kind,
                if (kind == Kind.TAPPED && who != null) "$who tapped your phone" else e.title,
                e.detail, e.at, peer = e.peer, who = who, seed = e.peer,
                done = e.done, topic = e.topic,
            )
        }

        return out.sortedByDescending { it.at }
    }

    fun unread(list: List<Alert>, seenAt: Long): Int = list.count { it.at > seenAt }
}

/**
 * The notifications nothing else on the phone remembers, written down once each.
 *
 * ### Why "once" needs two keys
 *
 * With the app open, one event reaches the phone twice: on the live stream and as a push. Both
 * end up here, and they must make one line.
 *
 * - [Entry.hard] is the event's own identity (a transaction signature, a payment reference). A
 *   second arrival with the same one is the same event, however much later it turns up. This is
 *   what stops a push that FCM redelivers after a day from becoming a second line.
 * - [Entry.soft] is what the line SAYS ("in 0.5 SOL"). The live stream's wallet event carries no
 *   signature, so the only way to match it to its push is that they say the same thing within
 *   [WINDOW] of each other.
 *
 * Two arrivals that both carry a hard key and disagree are two events, even if they say the
 * same thing a second apart: being paid 1 USDC twice is two payments. The cost of the soft key
 * is narrow and stated: two identical transfers inside [WINDOW], both heard ONLY on the live
 * stream (push switched off on the server), are listed as one. The balance is still right.
 */
object AlertLog {

    data class Entry(
        /** An [Alerts.Kind] name. A string so an entry written by a newer build is skipped, not a crash. */
        val kind: String,
        val title: String,
        val detail: String,
        val at: Long,
        val soft: String,
        val hard: String? = null,
        val peer: String? = null,
        val who: String? = null,
        val topic: String? = null,
        val done: Boolean = false,
    )

    private const val PREFS = "alerts"
    private const val KEY = "log"
    /** Enough to scroll back through a busy week; old lines fall off the end. */
    private const val KEEP = 60
    /** How far apart a push and its live-stream twin can land. Generous: a push can be slow. */
    private const val WINDOW = 2 * 60_000L

    /**
     * Bumped on every change, so the page redraws when a push lands while it is open.
     *
     * Written from the messaging service's thread as well as the main one, which snapshot state
     * allows. The same idea as [Live.tick].
     */
    var version by mutableIntStateOf(0)
        private set

    /** What [record] found. The push handler rings for everything except a [REPEAT]. */
    enum class Wrote {
        /** Never heard of it: a new line. */
        NEW,
        /** The live stream had already written it, and this arrival told us which event it was. */
        TWIN,
        /** The same slot saying something newer: "on its way" became "arrived". */
        REPLACED,
        /** This exact event, again. Nothing changed, and nothing should ring. */
        REPEAT,
    }

    /**
     * Writes one line, unless it is already there.
     *
     * With [replace], a line already present under the same key is rewritten in place when the
     * words differ: see [Progress.slot], where a payout's "on its way" becomes "arrived".
     */
    @Synchronized
    fun record(
        context: Context, kind: Alerts.Kind, title: String, detail: String, soft: String,
        hard: String? = null, peer: String? = null, who: String? = null, topic: String? = null,
        done: Boolean = false, replace: Boolean = false, at: Long = System.currentTimeMillis(),
    ): Wrote {
        val list = all(context).toMutableList()
        val fresh = Entry(kind.name, title.take(120), detail.take(200), at, soft, hard, peer, who?.take(64), topic, done)
        val i = list.indexOfFirst { e ->
            (hard != null && e.hard == hard) ||
                (e.soft == soft && kotlin.math.abs(at - e.at) < WINDOW && (hard == null || e.hard == null))
        }
        if (i >= 0) {
            val was = list[i]
            return when {
                // The same slot saying something newer: the payout that has now arrived.
                replace && (was.title != fresh.title || was.detail != fresh.detail) -> {
                    list[i] = fresh
                    save(context, list.sortedByDescending { it.at })
                    Wrote.REPLACED
                }
                // The live stream wrote it first; the push now tells us which event it was.
                was.hard == null && hard != null -> {
                    list[i] = was.copy(hard = hard)
                    save(context, list)
                    Wrote.TWIN
                }
                // The other way round (the push wrote it, the live stream now repeats it) and
                // two live-stream arrivals look the same from here, and neither is news.
                else -> Wrote.REPEAT
            }
        }
        list.add(0, fresh)
        save(context, list.sortedByDescending { it.at }.take(KEEP))
        return Wrote.NEW
    }

    @Synchronized
    fun all(context: Context): List<Entry> = try {
        val arr = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { n ->
            val o = arr.optJSONObject(n) ?: return@mapNotNull null
            Entry(
                kind = o.optString("kind"), title = o.optString("title"), detail = o.optString("detail"),
                at = o.optLong("at"), soft = o.optString("soft"),
                hard = o.optString("hard").takeIf { it.isNotBlank() },
                peer = o.optString("peer").takeIf { it.isNotBlank() },
                who = o.optString("who").takeIf { it.isNotBlank() },
                topic = o.optString("topic").takeIf { it.isNotBlank() },
                done = o.optBoolean("done"),
            ).takeIf { it.kind.isNotBlank() && it.title.isNotBlank() }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("kind", e.kind); put("title", e.title); put("detail", e.detail)
                put("at", e.at); put("soft", e.soft); put("done", e.done)
                e.hard?.let { put("hard", it) }
                e.peer?.let { put("peer", it) }
                e.who?.let { put("who", it) }
                e.topic?.let { put("topic", it) }
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        version++
    }

    // The words and keys for each kind live here, in one place, because every kind is written
    // from two places (the push handler and the live stream) and the two must agree to the
    // letter or the soft key does not match and the line appears twice.

    /** Money moved in a wallet. [signature] is null on the live stream, which does not carry it. */
    fun wallet(context: Context, direction: String, amount: String, symbol: String, signature: String?): Wrote {
        if (direction != "in" && direction != "out") return Wrote.REPEAT
        val incoming = direction == "in"
        return record(
            context, Alerts.Kind.MONEY,
            title = if (incoming) "Received $amount $symbol" else "Sent $amount $symbol",
            detail = if (incoming) "Arrived in your wallet" else "Left your wallet",
            soft = "wallet:$direction:$amount:$symbol",
            hard = signature?.takeIf { it.isNotBlank() }?.let { "wallet:$it:$symbol:$direction" },
            topic = direction, done = incoming,
        )
    }

    /** A reward, referral, finished offer or payout. A new offer is NOT written: `/v1/offers` lists it. */
    fun progress(context: Context, p: Progress): Wrote {
        // NEW, not REPEAT: it is not written here, but it is news, and the server sends each
        // offer to each person once (`offer_notices`).
        if (p.kind == "offer" && p.state == "new") return Wrote.NEW
        val (title, text) = p.words() ?: return Wrote.REPEAT
        return record(
            context, Alerts.Kind.REWARD, title, text,
            soft = "progress:${p.kind}:${p.state}:${p.skr}:${p.app}",
            hard = p.id.takeIf { it.isNotBlank() }?.let { "progress:${p.slot}" },
            topic = p.kind, done = p.state == "confirmed" || p.state == "paid",
            replace = p.kind == "payout",
        )
    }

    /** Somebody touched phones with us, by radio or relayed by the server. */
    fun tapped(context: Context, card: TapCard): Wrote = record(
        context, Alerts.Kind.TAPPED, "${card.label.ifBlank { "Someone" }} tapped your phone",
        "Open their page to pay or request", soft = "tap:${card.address}",
        peer = card.address, who = card.label.takeIf { it.isNotBlank() },
    )
}
