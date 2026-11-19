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
 * Push notifications for recharges that finish while the app is closed.
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

    /** One channel: a recharge finishing is the only thing AirPay tells you about. */
    enum class Level(val channel: String, val title: String, val about: String, val importance: Int, val priority: Int) {
        MONEY("money_v2", "Recharges",
            "When a recharge is delivered, fails, or is refunded.",
            NotificationManager.IMPORTANCE_HIGH, NotificationCompat.PRIORITY_HIGH),
    }

    /** Channels from earlier versions, which no longer carry anything. */
    private val RETIRED = listOf("paid", "taps", "people_v2", "activity_v2", "offers_v2")

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

    private fun home(context: Context) =
        Intent(context, CellUpActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun showTopup(context: Context, id: String, status: String, label: String, operator: String, phoneEnd: String) {
        val delivered = status == "delivered"
        val failed = status in setOf("expired", "cancelled", "failed", "refunded")
        if (!delivered && !failed) return
        notify(
            context, Level.MONEY, tapId("topup$id"),
            title = when {
                delivered -> "Recharge done"
                status == "refunded" -> "Recharge refunded"
                else -> "Recharge not completed"
            },
            text = when {
                delivered -> "$label from $operator is on the number ending $phoneEnd."
                status == "refunded" -> "Your $operator recharge could not be delivered. Open AirPay to see how your refund comes back."
                else -> "Your $operator recharge for the number ending $phoneEnd did not go through."
            },
            open = home(context),
            publicText = "News about your recharge",
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

}

class TapMessagingService : FirebaseMessagingService() {

    // See Push.token for why the deprecated token callback is still the one used.
    @Deprecated("Tokens are deprecated in firebase-messaging 25.1; see Push.token")
    override fun onNewToken(token: String) {
        getSharedPreferences("push", Context.MODE_PRIVATE).edit().putString("token", token).apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        // A recharge ended (server/duffleserver/topups.py). Nothing else is sent any more.
        if (d["type"] != "topup") return
        val id = d["id"]?.takeIf { it.startsWith("cu_") && it.length <= 40 } ?: return
        Push.showTopup(this, id, d["status"].orEmpty(), d["label"].orEmpty().take(60),
            d["operator"].orEmpty().take(40), d["phone"].orEmpty().takeLast(4))
    }
}
