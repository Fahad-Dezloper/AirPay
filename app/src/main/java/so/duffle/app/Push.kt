package so.duffle.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Push notifications for payments that land while the app is closed.
 *
 * Firebase is configured from local.properties rather than a google-services.json, so the
 * project builds and runs for anyone who clones it without a Firebase account — push is simply
 * off until the four `firebase.*` values are filled in.
 */
object Push {

    val configured get() = BuildConfig.FIREBASE_APP_ID.isNotBlank()

    /** Idempotent. Returns whether push is available on this build. */
    fun init(context: Context): Boolean {
        if (!configured) return false
        return runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build(),
                )
            }
            true
        }.getOrDefault(false)
    }

    /**
     * The FCM registration token.
     *
     * Deprecated since firebase-messaging 25.1 in favour of installation-ID registration
     * (`register()` / `onRegistered()`), but still working. It is used deliberately: the server
     * sends through the documented HTTP v1 API, which addresses a `token`, and how that API
     * targets an installation ID was not documented when this was written. Move both sides
     * together once it is.
     */
    @Suppress("DEPRECATION")
    suspend fun token(context: Context): String? {
        if (!init(context)) return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }.onFailure { cont.resume(null) }
        }
    }

    /**
     * Whether a notification posted now would be shown.
     *
     * Asks the system rather than checking the POST_NOTIFICATIONS permission. That permission only
     * exists from Android 13; on Android 12 and older the check answers "denied" for a permission
     * the phone has never heard of, which silently suppressed every notification on those phones —
     * the people most likely to be handed a friend's old handset to try the app on.
     */
    fun canNotify(context: Context) =
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * How loudly a notification arrives. Four levels, because the app says four kinds of thing.
     *
     * Everything used to go out at the top level: an offer to try an app made the same sound,
     * and dropped the same banner over whatever was on screen, as being paid. When everything
     * is urgent nothing is, and the usual answer to that is to switch the whole app off, which
     * loses the payments too. Each level is its own channel in the phone's settings, so the
     * offers can be muted and the money kept.
     */
    enum class Level(val channel: String, val title: String, val about: String, val importance: Int, val priority: Int) {
        /** Money arriving, somebody asking for it, somebody saying no. Sound and a banner. */
        MONEY("money_v2", "Payments and requests",
            "Money you receive, requests to pay, and declined requests.",
            NotificationManager.IMPORTANCE_HIGH, NotificationCompat.PRIORITY_HIGH),
        /** Somebody standing next to you, or writing to you. Sound and a banner. */
        PEOPLE("people_v2", "Taps and messages",
            "Someone tapped your phone or sent you a message.",
            NotificationManager.IMPORTANCE_HIGH, NotificationCompat.PRIORITY_HIGH),
        /** Worth knowing, not worth interrupting for. Sound, no banner. */
        ACTIVITY("activity_v2", "Rewards and activity",
            "Rewards, referrals, orders, and money you sent.",
            NotificationManager.IMPORTANCE_DEFAULT, NotificationCompat.PRIORITY_DEFAULT),
        /** A suggestion. In the shade when you look, and silent. */
        QUIET("offers_v2", "Offers",
            "New offers and small updates. Silent.",
            NotificationManager.IMPORTANCE_LOW, NotificationCompat.PRIORITY_LOW),
    }

    /**
     * The two channels every notification used to share, both at the top importance.
     *
     * A channel's importance cannot be changed once it exists on a phone: Android treats it as
     * the person's own setting from the first moment, and creating the channel again with a
     * different value is ignored. So the levels above are NEW channels with new ids, and these
     * are deleted. A phone that had muted one of the old ones starts again with the defaults,
     * which is the right way round: what it muted no longer exists as one thing.
     */
    private val RETIRED = listOf("paid", "taps")

    @Volatile private var channelsReady = false

    /** Idempotent, and cheap after the first call. Safe from the messaging service's thread. */
    fun ensureChannels(context: Context) {
        if (channelsReady) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            for (l in Level.values()) {
                nm.createNotificationChannel(
                    NotificationChannel(l.channel, l.title, l.importance).apply { description = l.about },
                )
            }
            for (old in RETIRED) nm.deleteNotificationChannel(old)
            channelsReady = true
        }
    }

    /** What the server can name. Anything else is a token it showed by its first four letters. */
    private val KNOWN_SYMBOLS = setOf("SOL", "USDC", "USDT", "EURC", "SKR")

    /** Opens the app where it was. For money: Home is where the balance is. */
    private fun home(context: Context) =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** Opens the notifications page, where the line this notification is about is listed. */
    private fun page(context: Context) = home(context).putExtra(EXTRA_OPEN, OPEN_ALERTS)

    /** Opens the rewards page, where a card that was just earned is waiting to be scratched. */
    private fun cards(context: Context) = home(context).putExtra(EXTRA_OPEN, OPEN_REWARDS)

    fun showPaid(context: Context, amount: String, reference: String, symbol: String = "SOL") {
        val title = if (amount.isNotBlank()) "Received $amount $symbol" else "Payment received"
        // Written down for the page as well. On the phone that made the request the page already
        // has a better line for this (confirmed on-chain, with a name) and `Alerts.build` drops
        // this one in its favour; on a second phone signed in to the same account there is no
        // such line, and without this the notification would be the only trace.
        val wrote = AlertLog.record(
            context, Alerts.Kind.MONEY, title, "For a request you made",
            soft = "paid:$reference", hard = "paid:$reference", topic = "in",
        )
        // This very payment has been announced on this phone already: a push delivered twice.
        if (wrote == AlertLog.Wrote.REPEAT) return
        notify(
            context, Level.MONEY, reference.hashCode(),
            title = title,
            // Worded carefully: the app confirms on-chain when opened, not on the push's word.
            text = "Tap to check it in AirPay.",
            open = home(context),
            publicText = "You received a payment",
        )
    }

    /** Money moved in one of our wallets, whoever moved it. */
    fun showWallet(context: Context, direction: String, amount: String, symbol: String, signature: String) {
        val incoming = direction == "in"
        // Written to the page first, and the answer decides whether to ring. A REPEAT is this
        // same transaction's push arriving a second time: already listed, already announced.
        // (The live stream writing it first is a TWIN, not a REPEAT, and still rings once.)
        if (AlertLog.wallet(context, direction, amount, symbol, signature) == AlertLog.Wrote.REPEAT) return
        notify(
            context,
            // Money IN, in a token we can name, is the reason this app has notifications. Money
            // out is something you just did yourself. And anyone can send any wallet a token
            // nobody has heard of: that is how spam arrives on this chain, and it does not get
            // to drop a banner over the screen.
            if (incoming && symbol in KNOWN_SYMBOLS) Level.MONEY else Level.ACTIVITY,
            // The symbol is part of the id: one transaction can move two tokens, and the second
            // line must not replace the first.
            tapId("wallet$signature$symbol"),
            title = if (incoming) "Received $amount $symbol" else "Sent $amount $symbol",
            text = if (incoming) "It is in your wallet. Tap to see your balance." else "It left your wallet. Tap to see your balance.",
            open = home(context),
            publicText = if (incoming) "You received a payment" else "A payment was sent",
        )
    }

    /** Someone touched phones with us. Unlock, tap this, and the popup opens. */
    fun showTapped(context: Context, peer: TapCard) {
        AlertLog.tapped(context, peer)
        notify(
            context, Level.PEOPLE, tapId(peer.address),
            title = "${peer.label.ifBlank { "Someone" }}${if (peer.genesis != null) " ✓" else ""} tapped your phone",
            text = "Tap to pay or request.",
            open = TapPopupActivity.intent(context, TapPayload.Card(peer), cameFrom = "notification"),
            publicText = "Someone tapped your phone",
        )
    }

    /**
     * Somebody wrote to us. The body is shown on the lock screen, so `publicText` hides it
     * there: a message is the one push in this app whose content is nobody else's business.
     *
     * One notification per sender, keyed on their address: a second message replaces the first
     * instead of stacking under it, and it is allowed to sound again because it is new words.
     * It was keyed on the display name, so two people with no name set shared one slot and the
     * second one's message silently replaced the first one's.
     */
    fun showMessage(context: Context, who: String, body: String, from: String = who) {
        notify(
            context, Level.PEOPLE, tapId("msg$from"),
            title = who.ifBlank { "Someone" },
            text = body,
            open = page(context),
            publicText = "New message",
            category = NotificationCompat.CATEGORY_MESSAGE,
            alertAgain = true,
        )
    }

    /** Someone asked us for money through the server, after the phones had parted. */
    fun showRequested(context: Context, req: PayRequest) {
        notify(
            context, Level.MONEY, tapId(req.reference),
            title = "${req.label.ifBlank { "Someone" }} requests ${req.amount} ${req.symbol}",
            text = "Tap to pay.",
            open = TapPopupActivity.intent(context, TapPayload.Request(req), cameFrom = "notification"),
            publicText = "You have a payment request",
        )
    }

    /**
     * They said no. The asker is probably still holding their phone out.
     *
     * [reference] keys the notification, so a decline delivered twice is one notification and
     * two different declines from one person are two.
     */
    fun showRejected(context: Context, who: String, reference: String = who) {
        notify(
            context, Level.MONEY, tapId("rejected$reference"),
            title = "Payment declined",
            text = "$who declined · nothing was paid",
            open = page(context),
            publicText = "A request was declined",
        )
    }

    /** They said "later". Worth telling, quietly: it is news, but it is not a problem. */
    fun showDeferred(context: Context, who: String, amount: String, symbol: String, reference: String = who) {
        notify(
            context, Level.ACTIVITY, tapId("later$reference"),
            title = "$who will pay later",
            text = if (amount.isBlank()) "Still waiting for it." else "$amount $symbol · still waiting for it.",
            open = page(context),
            publicText = "A request is pending",
        )
    }

    /**
     * A reward-side notification. The amount is not on the lock screen, only that there is news.
     *
     * Takes the [Progress] itself rather than its words, so the level, the slot it replaces and
     * the line on the notifications page are all decided from the same facts.
     */
    fun showProgress(context: Context, p: Progress) {
        val (title, text) = p.words() ?: return
        if (AlertLog.progress(context, p) == AlertLog.Wrote.REPEAT) return
        // A card to scratch is the one piece of reward news that asks for something now, and it
        // is money: it gets the banner, and it opens on the cards and not on the list of news.
        val card = p.kind == "reward" && (p.state == "earned" || p.state == "settled")
        notify(
            context, if (p.quiet) Level.QUIET else if (card) Level.MONEY else Level.ACTIVITY, tapId("progress:${p.slot}"),
            title = title, text = text,
            open = if (card) cards(context) else page(context),
            publicText = if (p.kind == "offer" && p.state == "new") "A new offer" else "News about your rewards",
            category = if (p.kind == "offer" && p.state == "new") NotificationCompat.CATEGORY_PROMO else null,
        )
    }

    /**
     * Something bought in the shop arrived, or did not.
     *
     * The line on the notifications page comes from [Orders], which the app brings up to date
     * when it is opened. This only rings: delivery usually lands after the phone was put away.
     */
    /**
     * The payment for something bought is confirmed. Said at once, because the delivery that
     * [showOrder] reports trails it, and until then nothing outside the app says the money went.
     *
     * The same id as [showOrder], so the news of the delivery takes this one's place in the
     * shade and one purchase is never two lines saying half a thing each.
     */
    fun showBought(context: Context, invoice: String, name: String, paid: String, number: String?) {
        val what = name.removeSuffix(" India").removeSuffix(" Bundles").trim().ifBlank { "your order" }
        val recharge = !number.isNullOrBlank()
        notify(
            context, Level.MONEY, tapId("order$invoice"),
            title = if (recharge) "Recharge paid" else "You bought $what",
            text = if (recharge) "$paid paid. Your $what recharge for $number is on its way."
                else "$paid paid. It is on its way to you.",
            open = page(context),
            publicText = "News about your order",
        )
    }

    fun showOrder(context: Context, invoice: String, order: Orders.Order?, delivered: Boolean) {
        val name = order?.name.orEmpty().removeSuffix(" India").removeSuffix(" Bundles").trim()
        val what = name.ifBlank { "Your order" }
        // A recharge says what it is and where it went: "Recharge done", then the network and
        // the number. Anything else is named by what was bought.
        val number = order?.recipient?.takeIf { it.isNotBlank() }
        val recharge = delivered && number != null
        notify(
            // A banner: this is the end of something that was paid for.
            context, Level.MONEY, tapId("order$invoice"),
            title = if (recharge) "Recharge done" else if (delivered) "$what delivered" else "$what did not go through",
            text = if (recharge) "${name.ifBlank { "Your" }} recharge delivered to $number"
                else if (delivered) "Tap to see it in AirPay." else "Tap to see what happened.",
            open = page(context),
            publicText = "News about your order",
            // It replaces "paid" in the shade and is news of its own, so it rings again.
            alertAgain = true,
        )
    }

    fun cancel(context: Context, key: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(tapId(key))
    }

    private fun tapId(key: String) = 0x7A9 xor key.hashCode()

    /**
     * Posts one notification.
     *
     * The [id] is the event's own (a reference, a signature, a sender), never a counter: the
     * same event posted twice REPLACES itself in the shade rather than stacking, and
     * `setOnlyAlertOnce` keeps that replacement from sounding a second time. That is the last
     * line of defence against a repeat, behind the server's and [AlertLog]'s.
     */
    private fun notify(
        context: Context, level: Level, id: Int,
        title: String, text: String, open: Intent, publicText: String? = null,
        category: String? = null, alertAgain: Boolean = false,
    ) {
        if (!canNotify(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannels(context)
        val pi = PendingIntent.getActivity(
            context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, level.channel)
            .setSmallIcon(R.drawable.ic_stat_airpay)
            .setContentTitle(title)
            .setContentText(text)
            // The whole sentence when pulled down: a reward's second line does not fit in one.
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alertAgain)
            .setPriority(level.priority)
        if (category != null) builder.setCategory(category)
        if (publicText != null) {
            // On the lock screen show only that something happened — not who, not how much.
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, level.channel)
                        .setSmallIcon(R.drawable.ic_stat_airpay)
                        .setContentTitle("AirPay")
                        .setContentText(publicText)
                        .build()
                )
        }
        runCatching { nm.notify(id, builder.build()) }
    }

    /** On the intent a notification opens the app with: where to go. Read by MainActivity. */
    const val EXTRA_OPEN = "so.duffle.app.open"
    const val OPEN_ALERTS = "alerts"
    /** The rewards page: where a card that was just earned is waiting. */
    const val OPEN_REWARDS = "rewards"

}

class TapMessagingService : FirebaseMessagingService() {

    // See Push.token for why the deprecated token callback is still the one used.
    @Deprecated("Tokens are deprecated in firebase-messaging 25.1; see Push.token")
    override fun onNewToken(token: String) {
        android.util.Log.i("TapPush", "new push token")
        getSharedPreferences("push", Context.MODE_PRIVATE).edit().putString("token", token).apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        android.util.Log.i("TapPush", "push received: ${d["type"]} ${d["reference"]?.take(8) ?: ""}")
        when (d["type"]) {
            "paid" -> {
                val ref = d["reference"]?.takeIf { PayRequest.isBase58Address(it) } ?: return
                Push.showPaid(this, d["amount"].orEmpty(), ref, d["symbol"]?.takeIf { it.isNotBlank() && it.length <= 12 } ?: "SOL")
            }
            "wallet" -> {
                val dir = d["direction"]?.takeIf { it == "in" || it == "out" } ?: return
                val amount = d["amount"]?.takeIf { it.length in 1..24 } ?: return
                // The amount is the server's own formatting of a number: digits and one point,
                // never a sign. Anything else is not shown as money.
                if (!amount.matches(Regex("[0-9]+(\\.[0-9]+)?"))) return
                // No guessing a token. A wallet event always names one; "SOL" as a fallback
                // would announce a token transfer as SOL.
                val sym = d["symbol"]?.takeIf { it.isNotBlank() && it.length <= 12 } ?: return
                Push.showWallet(this, dir, amount, sym, d["signature"].orEmpty())
            }
            // A request made in someone's popup after the phones parted. Validated like
            // anything off the radio — a push is no more trustworthy than a tap.
            "request" -> {
                val token = d["token"]?.takeIf { it.isNotBlank() } ?: "SOL"
                val req = PayRequest(
                    recipient = d["recipient"].orEmpty(), amount = d["amount"].orEmpty(), token = token,
                    label = d["label"].orEmpty(), reference = d["reference"].orEmpty(),
                    genesis = d["genesis"]?.takeIf { it.isNotBlank() },
                    decimals = d["decimals"]?.toIntOrNull() ?: 9,
                    symbol = d["symbol"]?.takeIf { it.isNotBlank() } ?: if (token == "SOL") "SOL" else token.take(4),
                )
                if (req.validate() != null || req.units() == null) return
                Inbox(this).add(req)
                Push.showRequested(this, req)
            }
            // The requester took it back. Marked quietly: the row explains itself when the
            // app is next opened, and a notification for something being cancelled is noise.
            "withdrawn" -> {
                val ref = d["reference"]?.takeIf { PayRequest.isBase58Address(it) } ?: return
                Inbox(this).markWithdrawn(ref)
                Push.cancel(this, ref)
            }
            // The payer declined. Worth a notification: the asker is probably still waiting.
            "rejected" -> {
                val ref = d["reference"]?.takeIf { PayRequest.isBase58Address(it) } ?: return
                val who = d["label"].orEmpty().ifBlank { "They" }
                Watch(this).markRejected(ref, who)
                Push.showRejected(this, who, ref)
            }
            // The payer tapped "Later". Not an answer, so the row stays payable — but the
            // asker should know somebody looked, rather than being left with silence.
            "deferred" -> {
                val ref = d["reference"]?.takeIf { PayRequest.isBase58Address(it) } ?: return
                val who = d["label"].orEmpty().ifBlank { "They" }
                Watch(this).markDeferred(ref, who)
                Push.showDeferred(this, who, d["amount"].orEmpty(),
                    d["symbol"]?.takeIf { it.isNotBlank() && it.length <= 12 } ?: "SOL", ref)
            }
            // A line of text from someone who has tapped us. Stored here, not only shown: the
            // app is usually closed when one arrives, and the live stream never saw it.
            "message" -> {
                val from = d["from"]?.takeIf { PayRequest.isBase58Address(it) } ?: return
                val body = d["body"].orEmpty().take(Messages.MAX_BODY).ifBlank { return }
                val at = d["at"]?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: System.currentTimeMillis()
                val fresh = Messages(this).add(Messages.Note(from, body, at, mine = false,
                    id = d["id"]?.toLongOrNull() ?: 0L))
                if (fresh) Push.showMessage(this, d["label"].orEmpty(), body, from)
            }
            // Rewards, offers, referrals and payouts. Same words as the live stream, so a person
            // sees one story whether the app was open or not.
            "reward", "offer", "referral", "payout" -> {
                val p = Progress.from(d["type"].orEmpty()) { d[it] } ?: return
                Push.showProgress(this, p)
            }
            // A shop order finished. This had no case here at all, so a recharge that arrived
            // with the app closed said nothing until the app was next opened. Only an ending is
            // announced: "paid" and "processing" are steps, not news.
            "shop" -> {
                val invoice = d["invoice"]?.takeIf { it.isNotBlank() && it.length <= 80 } ?: return
                val status = d["status"].orEmpty()
                val delivered = status in setOf("all_delivered", "complete", "delivered")
                val failed = status in setOf("denied", "payment_error", "failed", "expired")
                if (!delivered && !failed) return
                Push.showOrder(this, invoice, Orders(this).all().firstOrNull { it.invoiceId == invoice }, delivered)
            }
            // An introduction relayed because the phones parted before HELLO could cross.
            "tap" -> {
                val card = TapCard(d["address"].orEmpty(), d["label"].orEmpty(), d["genesis"]?.takeIf { it.isNotBlank() },
                    d["brand"]?.takeIf { Brand.isKnown(it) })
                if (card.validate() != null) return
                // false, not `genesis != null`: that is their claim. Contacts.verify writes the
                // chain's answer when the popup looks it up.
                Contacts(this).record(card.address, card.label, false, brand = card.brand)
                Push.showTapped(this, card)
            }
        }
    }
}
