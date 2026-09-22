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

    fun canNotify(context: Context) =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun showPaid(context: Context, amount: String, reference: String, symbol: String = "SOL") {
        notify(
            context, CHANNEL_PAID, "Payments received", reference.hashCode(),
            title = if (amount.isNotBlank()) "Received $amount $symbol" else "Payment received",
            // Worded carefully: the app confirms on-chain when opened, not on the push's word.
            text = "Tap to confirm it on-chain.",
            open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    /** Someone touched phones with us. Unlock, tap this, and the popup opens. */
    fun showTapped(context: Context, peer: TapCard) {
        notify(
            context, CHANNEL_TAPS, "Taps", tapId(peer.address),
            title = "${peer.label}${if (peer.genesis != null) " ✓" else ""} tapped your phone",
            text = "Tap to pay or request.",
            open = TapPopupActivity.intent(context, TapPayload.Card(peer), cameFrom = "notification"),
            publicText = "Someone tapped your phone",
        )
    }

    /**
     * Somebody wrote to us. The body is shown on the lock screen, so `publicText` hides it
     * there: a message is the one push in this app whose content is nobody else's business.
     */
    fun showMessage(context: Context, who: String, body: String) {
        notify(
            context, CHANNEL_TAPS, "Taps", tapId("msg$who"),
            title = who.ifBlank { "Someone" },
            text = body,
            open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            publicText = "New message",
        )
    }

    /** Someone asked us for money through the server, after the phones had parted. */
    fun showRequested(context: Context, req: PayRequest) {
        notify(
            context, CHANNEL_TAPS, "Taps", tapId(req.reference),
            title = "${req.label} requests ${req.amount} ${req.symbol}",
            text = "Tap to pay.",
            open = TapPopupActivity.intent(context, TapPayload.Request(req), cameFrom = "notification"),
            publicText = "You have a payment request",
        )
    }

    /** They said no. The asker is probably still holding their phone out. */
    fun showRejected(context: Context, who: String) {
        notify(
            context, CHANNEL_TAPS, "Taps", tapId("rejected$who"),
            title = "$who declined your request",
            text = "Nothing was paid.",
            open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            publicText = "A request was declined",
        )
    }

    /** They said "later". Worth telling, quietly: it is news, but it is not a problem. */
    fun showDeferred(context: Context, who: String, amount: String, symbol: String) {
        notify(
            context, CHANNEL_TAPS, "Taps", tapId("later$who"),
            title = "$who will pay later",
            text = if (amount.isBlank()) "Still waiting for it." else "$amount $symbol · still waiting for it.",
            open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            publicText = "A request is pending",
        )
    }

    fun cancel(context: Context, key: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(tapId(key))
    }

    private fun tapId(key: String) = 0x7A9 xor key.hashCode()

    private fun notify(
        context: Context, channel: String, channelName: String, id: Int,
        title: String, text: String, open: Intent, publicText: String? = null,
    ) {
        if (!canNotify(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(
            context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        if (publicText != null) {
            // On the lock screen show only that something happened — not who, not how much.
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, channel)
                        .setSmallIcon(android.R.drawable.stat_notify_more)
                        .setContentTitle("Duffle")
                        .setContentText(publicText)
                        .build()
                )
        }
        runCatching { nm.notify(id, builder.build()) }
    }

    private const val CHANNEL_PAID = "paid"
    private const val CHANNEL_TAPS = "taps"

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
                Push.showRejected(this, who)
            }
            // The payer tapped "Later". Not an answer, so the row stays payable — but the
            // asker should know somebody looked, rather than being left with silence.
            "deferred" -> {
                val ref = d["reference"]?.takeIf { PayRequest.isBase58Address(it) } ?: return
                val who = d["label"].orEmpty().ifBlank { "They" }
                Watch(this).markDeferred(ref, who)
                Push.showDeferred(this, who, d["amount"].orEmpty(),
                    d["symbol"]?.takeIf { it.isNotBlank() && it.length <= 12 } ?: "SOL")
            }
            // A line of text from someone who has tapped us. Stored here, not only shown: the
            // app is usually closed when one arrives, and the live stream never saw it.
            "message" -> {
                val from = d["from"]?.takeIf { PayRequest.isBase58Address(it) } ?: return
                val body = d["body"].orEmpty().take(Messages.MAX_BODY).ifBlank { return }
                val at = d["at"]?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: System.currentTimeMillis()
                val fresh = Messages(this).add(Messages.Note(from, body, at, mine = false,
                    id = d["id"]?.toLongOrNull() ?: 0L))
                if (fresh) Push.showMessage(this, d["label"].orEmpty(), body)
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
