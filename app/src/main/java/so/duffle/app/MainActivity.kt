package so.duffle.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.itemsIndexed
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.core.view.WindowCompat
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.drawBehind
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A tap hands over a payment request. Nothing else happens while the phones touch:
 *
 *   payee   Request → HCE serves {recipient, amount, reference}
 *   TAP     ~100ms, one direction                        ← phones part here
 *   payer   request lands in Requests — pay now, or whenever
 *   payer   Pay → fresh blockhash → wallet signs → we submit
 *   payee   the chain (or the server's doorbell) shows the reference paid in full → ✓
 *
 * **Requests never expire.** Only a transaction has a clock, and it starts when the payer
 * presses Pay. An earlier design put a blockhash inside the request so both phones could share
 * a deadline; the price was a ~28-second window to read, decide and approve, and a payer
 * who missed it had to find the other person and tap again.
 */
class MainActivity : ComponentActivity() {

    private lateinit var reader: TapReader
    private lateinit var wallet: Wallet
    private lateinit var contacts: Contacts
    private lateinit var messages: Messages
    private lateinit var orders: Orders
    private lateinit var inbox: Inbox
    private lateinit var watch: Watch

    /** Our own Genesis mint, if this phone's wallet holds one. Attached to every request. */
    private var myGenesis by mutableStateOf<String?>(null)
    private var contactCount by mutableIntStateOf(0)
    private val log = mutableStateListOf<String>()

    /** The request whose confirm sheet is open, if any. It is already saved in the inbox. */
    private var incoming by mutableStateOf<PayRequest?>(null)

    /**
     * Mode lives here rather than only in composition because reader mode is torn down by the
     * platform on every pause — a notification, a glance away, even a screenshot. Without
     * re-arming on resume the button stays lit while the radio is silent.
     */
    private var mode by mutableStateOf(Mode.IDLE)
    private var lastAmount = "0.01"
    private var connected by mutableStateOf<String?>(null)
    private var tab by mutableStateOf(Tab.TAP)
    /** The person whose thread is open, or null for the main page. */
    private var thread by mutableStateOf<Contact?>(null)
    /** Which item inside the open thread has its own page up (§8.4), by the row's id. */
    private var threadItem by mutableStateOf<String?>(null)
    /** "check" is open: the diagnostics and the exchange log, which are for us, not the user. */
    private var checking by mutableStateOf(false)
    /** A wallet app is open in front of us. Hoisted out of the composition because linking
     *  outlives it — the user is in another app while this is true. */
    private var busy by mutableStateOf(false)
    /** The wallet cards are open, covering the tab. */
    private var cards by mutableStateOf(false)
    private var payAnyone by mutableStateOf(false)
    /** The wallet whose details sheet is open, or null. */
    private var walletDetails by mutableStateOf<Linked?>(null)
    /** The wallet whose receive QR is showing. */
    private var receiving by mutableStateOf<Linked?>(null)
    /** The wallet being given a name of the person's own. */
    private var renamingWallet by mutableStateOf<Linked?>(null)
    /** The wallet whose name-vouching sheet is open, and a counter that says a vouch just changed. */
    private var vouchWallet by mutableStateOf<Linked?>(null)
    private var vouchTick by mutableStateOf(0)
    /** The reward card lifted to the middle of the screen to be scratched, if any. */
    private var scratching by mutableStateOf<ScratchTarget?>(null)
    /** The referral explainer, held here so its scrim covers the screen rather than the
     *  padded column the tab is drawn inside. Carries the summary because the numbers in
     *  it are the server's, and this Activity does not read them. */
    /**
     * Whether the Refer sheet is up.
     *
     * §2 of ui/PORT-2026-10-01-4.md makes Refer & earn a SHEET rather than a destination: it
     * opens over whatever you are already on, from four places — the Referrals card on Home,
     * "Invite a friend" on Rewards, the AirPay row on Offers, and "Refer and earn" in the
     * profile panel. So it is one flag at the root, not a tab.
     */
    private var refer by mutableStateOf(false)
    /** The offer whose conditions are open. Hosted here for the same reason as the
     *  explainer: the scrim belongs to the screen, not to the padded column. */
    private var offerDetail by mutableStateOf<Rewards.Offer?>(null)
    private var offerClaiming by mutableStateOf(false)
    private var offerProblem by mutableStateOf<String?>(null)
    /** Bumped after a claim so the list re-reads rather than showing what it had. */
    private var offersReload by mutableIntStateOf(0)
    /** The installed wallet apps, while choosing which one to link. Null when not choosing. */
    private var picker by mutableStateOf<List<WalletLink.App>?>(null)
    /** Every linked wallet, re-read whenever one is added, switched or dropped. */
    private var walletList by mutableStateOf<List<Linked>>(emptyList())
    /** Which linked wallet is in use, by [Linked.id]: two apps can hold the same address. */
    private var activeWalletId by mutableStateOf<String?>(null)
    private val activeWallet: Linked?
        get() = walletList.firstOrNull { it.id == activeWalletId } ?: walletList.firstOrNull { it.address == connected }
    /** The selected wallet's balance. Null while it is being read, or unreadable. */
    private var balance by mutableStateOf<Chain.Balance?>(null)
    /** Every token this wallet holds, for the breakdown under the balance. */
    private var holdings by mutableStateOf<List<Tokens.Holding>>(emptyList())
    /** What the market says about each held mint: verified, and what it is worth. */
    private var market by mutableStateOf<Map<String, Market.Info>>(emptyMap())

    // The token cards need a day of prices and an icon per mint, not just a price, so they take
    // `Market.Quote`. `market` stays because other callers only want the price.
    private var quotes by mutableStateOf<Map<String, Market.Quote>>(emptyMap())
    /** Every holding priced and added up — the figure under "Total balance". */
    private var totalUsd by mutableStateOf<Double?>(null)
    /** What this wallet has earned, for the strip on the home page. */
    private var rewardSummary by mutableStateOf<Rewards.Summary?>(null)
    private var rewardFor: String? = null
    /** The catalogue, once the server has been asked. Empty means nothing to show. */
    private var shopSections by mutableStateOf<List<Shop.Section>>(emptyList())
    /** Whether the catalogue has been asked for yet, and whether the server answered. */
    private enum class ShopLoad { LOADING, DONE, FAILED }
    private var shopLoad by mutableStateOf(ShopLoad.LOADING)
    /** The section being browsed, and the product opened inside it. */
    private var shopSection by mutableStateOf<Shop.Section?>(null)
    private var shopProduct by mutableStateOf<Shop.Product?>(null)
    /**
     * The open page was reached straight from a Home tile (Hotstar, PhonePe, MakeMyTrip), not by
     * choosing a row in a list. Back then goes Home: there is no list the person has seen to go
     * back to, and showing the full one they never asked for would be a detour.
     */
    private var shopDirect by mutableStateOf(false)
    /** The recharge screen's three answers: which number, which network, and what the SIMs say. */
    private var rechargeNumber by mutableStateOf("")
    private var rechargeOperator by mutableStateOf<Shop.Product?>(null)
    /** The network lookup: whether it is in flight, and what it said last. */
    private var findingOperator by mutableStateOf(false)
    private var operatorNote by mutableStateOf<String?>(null)
    /** What the other phone shows when you tap them. A model number is not a name. */
    private var myName by mutableStateOf("")
    /**
     * The `.skr` or `.sol` this wallet owns, once the chain has said.
     *
     * When it is set, nothing about [myName] matters: the other phone resolves this for itself
     * and shows it with a tick, which is the whole difference between the two. Held in state so
     * a name arriving from mainnet a second after launch appears without a reload.
     */
    private var myDomain by mutableStateOf<String?>(null)
    /** True when [myDomain] came from another wallet vouching for this one, not from this one. */
    private var myDomainBorrowed by mutableStateOf(false)
    /** True while a wallet is away getting a link signed, so the button can say so. */
    private var linking by mutableStateOf(false)
    private var renaming by mutableStateOf(false)
    /** The left panel: who you are, the code to be paid with, and every other screen. */
    private var drawerOpen by mutableStateOf(false)
    /** Where gift-card codes are also emailed, if anywhere. Optional, kept on this phone. */
    private var myEmail by mutableStateOf<String?>(null)
    private var editingEmail by mutableStateOf(false)
    /**
     * The purchase whose code should be put in front of somebody.
     *
     * Set when an order is bought and again when its code arrives, because those are seconds
     * apart and the screen showed "Sent" in between and then nothing at all. A gift card that
     * is delivered silently has not really been delivered.
     */
    private var showCodeFor by mutableStateOf<String?>(null)

    /** UI mirrors of the two on-disk lists. Disk is the truth; these are re-read after writes. */
    private val notes = mutableStateListOf<Messages.Note>()
    private val bought = mutableStateListOf<Orders.Order>()
    /** Offers, held here rather than only in the Earn tab: the bell counts them too. */
    private var liveOffers by mutableStateOf<List<Rewards.Offer>>(emptyList())
    /** When the panel was last opened. Bumped in state so the badge clears without a reload. */
    private var alertsSeen by mutableStateOf(0L)
    /**
     * What [alertsSeen] was the moment the panel was opened — which is what the rows are marked
     * against. Opening the panel counts everything as read, so using the live value would erase
     * the dots in the same frame that draws them, and the screen would never once show WHICH
     * of its rows were the new ones.
     */
    private var alertsWere by mutableStateOf(0L)
    /** Which month the history page is showing. Defaults to this one. */
    private var histMonth by mutableStateOf(YearMonth.now())
    private val toPay = mutableStateListOf<Inbox.Item>()
    private val owed = mutableStateListOf<Watch.Entry>()

    private var paidSignature by mutableStateOf<String?>(null)
    private var paying by mutableStateOf<String?>(null)

    /**
     * One place for "what should the user be told right now". Without this every failure ends
     * up as a grey line in a debug log, which is fine for us and useless for anyone else.
     */
    sealed interface Status {
        data object Idle : Status
        data class Working(val what: String) : Status
        data class Problem(val title: String, val detail: String) : Status
        data class Sent(val signature: String) : Status
    }
    private var status by mutableStateOf<Status>(Status.Idle)

    /**
     * Why a wallet could not be linked, for the screen. It used to go only to the log, so a link
     * that was refused looked exactly like one that had quietly done nothing.
     */
    private var walletNotice by mutableStateOf<String?>(null)

    /** A purchase from the shop, from the moment a plan is tapped to the moment it is paid. */
    private var purchase by mutableStateOf<Purchase?>(null)

    /** Set on resume so every open request is checked at once rather than on its schedule. */
    @Volatile private var checkAllNow = true
    private var offlineStreak = 0

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        push(if (granted) "✓ notifications on" else "- notifications declined")
        notifOff = !Push.canNotify(this)
        // Android stops showing its dialog once it has been refused twice, and answers "denied"
        // at once instead. From the button that looked like a button that does nothing. An
        // answer that fast was not a person, so the only place left that can change it is
        // opened. Only for the button: the quiet ask at launch must never open Settings.
        val asked = notifyAskedAt
        notifyAskedAt = 0L
        if (!granted && asked != 0L && android.os.SystemClock.elapsedRealtime() - asked < 600 &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            openNotificationSettings()
        }
    }

    /**
     * True while the phone would swallow our notifications. The notifications page reads it to
     * show its "Allow notifications" block, and the bell on Home to show a dot.
     */
    private var notifOff by mutableStateOf(false)

    /** When the "Allow notifications" button last asked the system, or 0 if it has not. */
    private var notifyAskedAt = 0L

    /**
     * What the "Allow notifications" button on the notifications page does.
     *
     * Two different things can be switching notifications off, and each has one cure. On
     * Android 13 and later it may be the permission, which only the system's own dialog can
     * grant. Otherwise (an older phone, or the permission is held and the app's notifications
     * were switched off in Settings) there is no dialog to show, and Settings is the answer.
     */
    private fun allowNotifications() {
        val needsPermission = android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            notifyAskedAt = android.os.SystemClock.elapsedRealtime()
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
    }

    /**
     * Opens the notifications page and counts everything on it as read.
     *
     * Marked on open, not on scroll: the list is short and opening it IS reading it. The badge
     * clears immediately; the rows keep their dots against where the count stood a moment ago.
     */
    private fun openAlerts() {
        tab = Tab.ALERTS
        alertsWere = alertsSeen
        Alerts.markSeen(this)
        alertsSeen = Alerts.seenAt(this)
        catchUpOrders()
    }

    /**
     * A notification that is about a line on the notifications page opens that page.
     *
     * The extra is removed once acted on, so turning the phone (which hands the same intent to
     * a new activity) does not drag the person back here from wherever they went next.
     */
    private fun openFromNotification(i: Intent?) {
        if (i == null || i.getStringExtra(Push.EXTRA_OPEN) != Push.OPEN_ALERTS) return
        i.removeExtra(Push.EXTRA_OPEN)
        if (connected == null) return
        thread = null; threadItem = null; drawerOpen = false
        openAlerts()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromNotification(intent)
    }

    /**
     * Links a wallet, or resumes the one already selected.
     *
     * @param fresh true from "Add wallet". Reusing the current session would send the user back
     *   to the wallet they already have, which is the opposite of what they asked for.
     *
     * lifecycleScope, not the composition's: this suspends inside another app for as long as
     * the person takes, and a disposed composition would cancel it between opening and
     * returning. `finally`, because cancellation skips the line after a suspend point.
     */
    /**
     * "Connect a wallet", decided by what is on this phone.
     *
     *  * **A Seeker**: Seeker Wallet is the system default for the connect route, so the plain
     *    route lands on it.
     *  * **Anything else**: there is no default, so the plain route would raise Android's "Open
     *    with…" chooser — and forget which app was picked, which is what made every later signature
     *    ask again and fail. So the wallet is chosen from our own list (or taken straight away when
     *    it is the only one), and linked BY NAME, which records the app.
     */
    private fun startConnect() {
        val apps = WalletLink.installed(this)
        when {
            apps.any { it.pkg == SEEKER_WALLET } -> connectWallet(fresh = false)
            apps.size == 1 -> linkWallet(apps[0])
            else -> picker = apps   // an empty list says "No wallet apps found on this phone."
        }
    }

    private fun connectWallet(fresh: Boolean) {
        lifecycleScope.launch {
            busy = true
            try {
                push(if (fresh) "choose a wallet…" else "opening wallet…")
                when (val r = wallet.connect(fresh)) {
                    is Wallet.Outcome.Ok -> {
                        myGenesis = null
                        connected = r.address
                        refreshWallets()
                        push("✓ wallet ${r.address.take(8)}…${r.address.takeLast(4)}")
                    }
                    is Wallet.Outcome.Failed -> { push("✗ ${r.why}"); walletNotice = r.why }
                    Wallet.Outcome.Declined -> push("- wallet declined")
                }
            } finally {
                busy = false
            }
        }
    }

    /** Links one named wallet app, through the explicit-intent path. */
    private fun linkWallet(app: WalletLink.App) {
        lifecycleScope.launch {
            busy = true
            try {
                push("opening ${app.label}…")
                when (val r = wallet.link(app)) {
                    is Wallet.Outcome.Ok -> {
                        myGenesis = null
                        connected = r.address
                        // The armed request carries the old address until it is rebuilt; the
                        // effect keyed on `connected` does that, so nothing to call here.
                        TapHostApduService.pending.value = null
                        refreshWallets()
                        push("✓ ${app.label} ${r.address.take(6)}…${r.address.takeLast(4)}")
                    }
                    is Wallet.Outcome.Failed -> { push("✗ ${r.why}"); walletNotice = r.why }
                    Wallet.Outcome.Declined -> push("- ${app.label} declined")
                }
            } finally {
                busy = false
            }
        }
    }

    /**
     * Buys one thing, with real USDC, on mainnet.
     *
     * The invoice is asked for at the moment of buying rather than held from when the list was
     * drawn: a price in USDC for goods priced in rupees is a quote against a moving rate, and
     * an old one is the difference between paying and being refused.
     */
    /**
     * Works out which network a number is on, and selects it.
     *
     * Bitrefill's answer names a product, which is not always one the Phone section carries —
     * it has several Jio products and the section shows the one with plans. So the id is used
     * to find our own equivalent, and the looked-up product is the fallback when we have none.
     *
     * Every outcome says something. A silent button that sometimes does nothing is worse than
     * one that says it could not tell.
     */
    private fun findOperator(phone: String, section: Shop.Section) {
        if (findingOperator) return
        lifecycleScope.launch {
            findingOperator = true
            operatorNote = null
            try {
                val r = Shop.operatorFor(phone)
                // The number was edited while this was out: the answer belongs to the old one.
                if (Sims.e164(rechargeNumber) != phone) return@launch
                when (r) {
                    is Shop.Operator.Found -> {
                        val ours = section.products.firstOrNull { it.id == r.product.id }
                            ?: section.products.filter { it.isMobileRecharge }
                                .firstOrNull { sameNetwork(it.id, r.product.id) }
                            ?: r.product
                        rechargeOperator = ours
                        val name = ours.name.removeSuffix(" India").removeSuffix(" Bundles")
                        operatorNote = "$name, from the number itself, so porting is accounted for."
                    }
                    Shop.Operator.Unknown -> operatorNote =
                        "Could not tell which network that is. Pick it below."
                    Shop.Operator.Unreachable -> operatorNote =
                        "Could not check just now. Pick the network below."
                }
            } finally {
                findingOperator = false
                // An edit made during the lookup was ignored then, so it is looked up now.
                Sims.e164(rechargeNumber)?.takeIf { it != phone }?.let { findOperator(it, section) }
            }
        }
    }

    /**
     * Whether two product ids are the same network.
     *
     * Bitrefill files one network under several products — "my-jio-store-india" and
     * "reliance-jio-india-bundles" are both Jio — so the lookup can name one the Phone section
     * does not carry. Matched on the operator word rather than the id, which is the only part
     * the two share.
     */
    private fun sameNetwork(ours: String, theirs: String): Boolean {
        val words = listOf("jio", "airtel", "vodafone", "vi-", "bsnl", "mtnl")
        val mine = words.firstOrNull { it in ours } ?: return false
        return mine in theirs || (mine == "jio" && "jio" in theirs)
    }

    private fun buy(
        product: Shop.Product, value: String, category: String, phone: String? = null,
        extra: Plans.Extra? = null, payWith: Shop.PayWith = Shop.PayWith.USDC,
        /** The price the sheet already showed. Used as it is while it is still fresh. */
        quoted: Shop.Invoice? = null,
    ) {        // One purchase at a time. Two taps in one frame used to start two.
        if (purchase is Purchase.Working) return

        // Everything this purchase says goes to its own sheet, not to a line of text at the top of
        // the page: what is being bought, what the wallet is being asked for, and how it ended.
        val item = Purchase.Item(product, value, category, phone, extra)
        // The server issues the invoice to the signed-in wallet, so this needs a session. Ask for it
        // first, then carry on with the same purchase.
        if (ServerClient.enabled && connected != null && !Session.valid(connected)) {
            purchase = null   // the authorization sheet takes over; the purchase resumes after it
            gated("complete this purchase") { buy(product, value, category, phone, extra, payWith, quoted) }
            return
        }
        val payer = connected
        if (payer == null) {
            purchase = Purchase.Problem(item, "Connect a wallet first", "The shop pays from your wallet.")
            return
        }
        // Still refused, but only when there is genuinely no number — the recharge screen
        // collects one, and anything reaching here without it would 400 at Bitrefill.
        if (product.needsPhone && phone.isNullOrBlank()) {
            purchase = Purchase.Problem(item, "Needs a phone number",
                "${product.name.removeSuffix(" India")} credits a number directly. Enter one first.")
            return
        }
        lifecycleScope.launch {
            busy = true
            // The sheet has usually asked for the price already, so the figure the buyer read is
            // the one that is paid. Only a missing or stale quote is asked for again.
            val fresh = quoted?.takeIf { !it.expired && it.currency == payWith.symbol && it.value == value }
            purchase = Purchase.Working(item, if (fresh != null) "Approve in your wallet" else "Getting the price…", payWith, fresh?.shown)
            try {
                val inv = fresh ?: quote(item, payWith)
                if (inv == null) {
                    purchase = Purchase.Problem(item, "Couldn't get a price", "The shop didn't answer. Try again.")
                    return@launch
                }
                push("shop: ${inv.shown} to ${inv.address.take(8)}…")
                // The exact price goes on the sheet's own "You pay" line, so the button only has
                // to say what to do next.
                purchase = Purchase.Working(item, "Approve in your wallet", payWith, inv.shown)
                // No SOL for the network fee: the fee wallet pays it and takes it back in USDC.
                // Only then. A wallet that has SOL pays its own fee as it always did.
                val sponsor = if (payWith == Shop.PayWith.USDC && needsFeePaid()) ServerClient.sponsor() else null
                val built = withContext(Dispatchers.IO) {
                    when (payWith) {
                        Shop.PayWith.USDC -> Chain.buildTokenTransfer(
                            payer = payer, recipient = inv.address, mint = Shop.USDC_MAINNET,
                            units = inv.amount, decimals = Shop.USDC_DECIMALS,
                            reference = PayRequest.newReference(), symbol = "USDC",
                            sponsor = sponsor,
                        )
                        // The shop priced this order in SOL itself; the invoice is in lamports
                        // and exactly that many are sent.
                        Shop.PayWith.SOL -> Chain.buildTransfer(
                            payer = payer, recipient = inv.address, lamports = inv.amount,
                            reference = PayRequest.newReference(),
                        )
                    }
                }
                when (built) {
                    is Chain.Built.Bad -> purchase = Purchase.Problem(item, "Can't pay this", built.why)
                    is Chain.Built.Offline -> purchase = Purchase.Problem(item, "You're offline", "Nothing was sent.")
                    is Chain.Built.NotEnough -> purchase = Purchase.Problem(item,
                        if (built.symbol.contains("fees")) "Not enough SOL for the fee" else "Not enough ${payWith.symbol}",
                        if (built.symbol.contains("fees")) "You need a little SOL for the network fee."
                        else "You don't have enough ${payWith.symbol}. This costs ${inv.shown}.")
                    is Chain.Built.Ok -> {
                        // The quote is checked again here rather than only when it was made:
                        // the wallet may have been open for a while.
                        if (inv.expired) {
                            purchase = Purchase.Problem(item, "Price expired",
                                "That quote is over two minutes old. Nothing was sent. Try again.")
                            return@launch
                        }
                        when (val signed = wallet.signOnly(built.tx)) {
                            is Wallet.SendOutcome.Signed -> {
                                if (inv.expired) {
                                    purchase = Purchase.Problem(item, "Price expired",
                                        "The quote died while your wallet was open. Nothing was sent.")
                                    return@launch
                                }
                                purchase = Purchase.Working(item, "Sending the payment…", payWith, inv.shown)
                                // A sponsored payment still needs the fee wallet's signature, so
                                // it goes to the server, which adds it and submits.
                                val outcome = if (sponsor != null) ServerClient.sponsorSubmit(signed.tx)
                                    else withContext(Dispatchers.IO) { Chain.submit(signed.tx) }
                                when (val sent = outcome) {
                                    is Chain.Submitted.Ok -> {
                                        push("shop: sent ${inv.shown}, ${sent.signature.take(12)}…")
                                        // Written down before it is confirmed or watched. Both
                                        // of those die with the screen; this is what survives
                                        // it, and what the next launch asks about.
                                        orders.add(Orders.Order(
                                            invoiceId = inv.id, name = product.name, value = value,
                                            paid = inv.shown, signature = sent.signature,
                                            category = category, recipient = phone,
                                            currency = product.currency, image = product.image,
                                            status = "paid", at = System.currentTimeMillis(),
                                        ))
                                        refreshLists()
                                        // "Submitted" is not "paid": a node took it, and it can
                                        // still fail or never land. The tick waits for Solana to
                                        // say so, as a payment to a person always has.
                                        purchase = Purchase.Working(item, "Confirming on Solana…", payWith, inv.shown)
                                        when (val c = Chain.confirm(sent.signature)) {
                                            Chain.Confirmation.Confirmed -> {
                                                push("✓ shop: paid ${inv.shown}")
                                                purchase = Purchase.Done(item, inv.shown, sent.signature, inv.id)
                                                // Delivery trails the payment by seconds.
                                                launch { watchOrder(inv) }
                                            }
                                            is Chain.Confirmation.Failed -> {
                                                push("✗ shop: the chain refused it, ${c.why}")
                                                orders.setStatus(inv.id, "failed")
                                                refreshLists()
                                                purchase = Purchase.Problem(item, "Payment refused",
                                                    "Solana refused it: ${c.why}. The payment did not go through, so nothing was bought.")
                                            }
                                            // No answer is not a refusal. It may still land, so
                                            // this must not offer "Try again" as if it had not.
                                            Chain.Confirmation.Unknown -> {
                                                push("shop: sent, no confirmation yet")
                                                purchase = Purchase.Problem(item, "Sent, still confirming",
                                                    "Solana has not confirmed it yet. If it lands, your order is delivered and shows in History. Check the explorer before paying again.",
                                                    unsure = sent.signature)
                                                launch { watchOrder(inv) }
                                            }
                                        }
                                    }
                                    is Chain.Submitted.Rejected -> purchase = Purchase.Problem(item, 
                                        "Payment refused",
                                        if (sent.why.contains("blockhash", true))
                                            "It took too long and expired. Nothing was sent. Try again."
                                        else sent.why)
                                    Chain.Submitted.Offline -> purchase = Purchase.Problem(item, 
                                        "Couldn't confirm it was sent",
                                        "Check your wallet's recent activity before trying again, so you don't pay twice.")
                                }
                            }
                            is Wallet.SendOutcome.Failed -> purchase = Purchase.Problem(item, "Not bought", signed.why)
                            Wallet.SendOutcome.NeedsReconnect -> purchase = Purchase.Problem(item, 
                                "Reconnect your wallet", "The session ended. Connect again and retry.")
                        }
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    /**
     * Out of every wallet, and back to the first screen.
     *
     * The server is told first, while there is still a session to end, so the token cannot be
     * used again from anywhere. It is best effort: with no connection the phone still forgets
     * everything, which is the half the person asked for. History, orders and codes stay on the
     * phone; they are not the wallet's.
     */
    private fun logout() {
        lifecycleScope.launch {
            runCatching { ServerClient.ask("/v1/auth/logout", org.json.JSONObject(), timeoutMs = 3_000) }
            Session.clear(this@MainActivity)
            wallet.logoutAll()
            connected = null
            purchase = null
            cards = false
            tab = Tab.TAP
            refreshWallets()
            applyMode()
            push("- logged out of all wallets")
        }
    }

    /** True when the wallet is known to hold too little SOL to pay a network fee itself. */
    private fun needsFeePaid(): Boolean =
        (balance as? Chain.Balance.Ok)?.let { it.sol * 1_000_000_000.0 < Chain.FEE_HEADROOM_LAMPORTS } == true

    /**
     * The shop's price for [item] in [payWith], or null when it cannot be asked for yet (no
     * wallet, not signed in, no number) or the shop did not answer.
     */
    private suspend fun quote(item: Purchase.Item, payWith: Shop.PayWith): Shop.Invoice? {
        val payer = connected ?: return null
        if (ServerClient.enabled && !Session.valid(payer)) return null
        if (item.product.needsPhone && item.phone.isNullOrBlank()) return null
        push("shop: asking for a price…")
        // The payer's own wallet as the refund address: if the order cannot be filled, the money
        // has somewhere to come back to.
        return Shop.invoice(
            item.product.id, item.value, item.phone,
            refund = payer,
            packageId = item.product.packages.firstOrNull { it.value == item.value }?.id,
            // Optional. Without it Bitrefill notifies the account that owns the API key rather
            // than the person who paid.
            email = Identity.email(this@MainActivity),
            payWith = payWith,
        )
    }

    /** Asks what became of an order until it is delivered, or until asking stops being useful. */
    private suspend fun watchOrder(inv: Shop.Invoice) {
        repeat(12) {
            delay(5_000)
            val d = Shop.order(inv.id) ?: return@repeat
            push("shop: order ${d.status}")
            orders.setStatus(inv.id, d.status)
            (purchase as? Purchase.Done)?.takeIf { it.invoiceId == inv.id }?.let {
                purchase = it.copy(delivered = d.delivered)
            }
            // The code, saved the moment it exists. This screen is often gone by the time
            // delivery lands, so it cannot be the only place it is kept.
            d.redemption?.let {
                orders.setCode(inv.id, it.toJson())
                // The code has its own sheet; the purchase sheet steps aside for it.
                if ((purchase as? Purchase.Done)?.invoiceId == inv.id) purchase = null
                showCodeFor = inv.id
            }
            refreshLists()
            if (d.delivered) {
                push("✓ delivered")
                return
            }
        }
    }

    /**
     * Where a history row goes when it is tapped.
     *
     * A payment between people belongs in that person's thread — the row is a summary of
     * something the thread holds in full. A shop purchase has no thread and no second screen,
     * so its signature goes to the explorer, which is the only place with more to say about it.
     */
    private fun openTxn(t: History.Txn, known: List<Contact>) {
        val person = t.peer?.let { p -> known.firstOrNull { it.address == p } }
        if (person != null) {
            thread = person; threadItem = null
            tab = Tab.TAP
            return
        }
        // A purchase that produced a code opens the code, not a block explorer. The signature
        // is a receipt for the money; the code is the thing that was bought.
        if (t.code != null) {
            bought.firstOrNull { it.signature == t.signature }?.let {
                showCodeFor = it.invoiceId
                return
            }
        }
        val sig = t.signature ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse(Chain.explorerUrl(sig))))
        }.onFailure { push("couldn't open the explorer") }
    }

    /** Hands the system an invite to pass on. Real, and no more than it says. */
    /**
     * Hands text to whatever the phone shares with.
     *
     * The message itself is [Referrals.message] rather than a literal here: it carries the
     * referral code and the amount the rules actually pay, neither of which this activity
     * knows. All that is left is the Android half.
     */
    private fun shareText(text: String) {
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }, "Invite a friend"))
        }
    }

    /**
     * [owner] signs a statement naming [target], so [target] shows the owner's name.
     * One approval in the owner's wallet; nothing is sent or spent.
     */
    private fun vouchFrom(owner: Linked, target: Linked, rearm: () -> Unit) {
        lifecycleScope.launch {
            linking = true
            val link = try { wallet.link(owner, target.address) } finally { linking = false }
            val named = owner.username ?: owner.name
            if (link == null) {
                Haptics.failure(this@MainActivity)
                push("- $named did not sign; nothing changed")
            } else {
                Haptics.success(this@MainActivity)
                val name = Identity.domain(this@MainActivity, owner.address) ?: named
                push("+ ${target.short} now shows $name until ${link.expiresOn}")
                afterVouchChange(target, rearm)
            }
        }
    }

    /** This phone stops presenting a borrowed name from [target]. */
    private fun forgetVouch(target: Linked, rearm: () -> Unit) {
        Vouch.forget(this, target.address)
        push("- ${target.short} is just an address again")
        afterVouchChange(target, rearm)
    }

    /** What changes when a link is made or dropped: the sheets, the name on Home, the armed request. */
    private fun afterVouchChange(target: Linked, rearm: () -> Unit) {
        vouchTick++
        if (target.address == connected) {
            val own = Identity.domain(this, target.address)
            val lent = own ?: Vouch.of(this, target.address)?.let { Identity.domain(this, it.owner) }
            myDomain = lent
            myDomainBorrowed = own == null && lent != null
        }
        // The armed request still carries the old payload.
        TapHostApduService.pending.value = null
        rearm()
    }

    /**
     * The details sheet on Home: lend a name from the first other wallet that owns one.
     * The Wallets screen has its own sheet and picks the owner explicitly.
     */
    private fun vouchFor(w: Linked, rearm: () -> Unit) {
        val owner = Identity.named(
            this,
            wallet.linked.all().map { it.address }.filter { it != w.address },
        )?.first
        if (owner == null) {
            push("- no wallet here owns a name to lend")
        } else {
            vouchFrom(wallet.linked.all().first { it.address == owner }, w, rearm)
        }
    }

    /** Re-reads the linked wallets and the selected one's balance. */
    private fun refreshWallets(quiet: Boolean = false) {
        walletList = wallet.linked.all(); activeWalletId = wallet.linked.selected()?.id
        // Quiet re-reads the balance in place. Blanking it first made every refresh flash dashes,
        // which is the opposite of "instant".
        if (!quiet) { balance = null; holdings = emptyList() }
        // Cleared only when the wallet changed: a live event re-reads the same wallet, and
        // blanking its rewards for the round trip made history rows blink out and back.
        // A different wallet: its predecessor's figures must not stand under its name.
        if (rewardFor != connected) { rewardSummary = null; totalUsd = null }
        rewardFor = connected
        val addr = connected ?: run { myDomain = null; return }

        // Whose name this wallet carries, borrowing one if it has none of its own. Seeded from
        // the last answer for *this* address — switching wallets must not leave the previous
        // one's name on screen for the second it takes mainnet to reply.
        myDomain = Identity.domain(this, addr)
        myDomainBorrowed = false
        if (myDomain == null) {
            myDomain = Vouch.of(this, addr)?.let { Identity.domain(this, it.owner) }
            myDomainBorrowed = myDomain != null
        }

        // Every linked wallet, not just the current one. Which wallet owns a name is what
        // decides who can vouch for the rest, and that question is asked from screens the
        // selected wallet has nothing to do with.
        lifecycleScope.launch {
            for (w in walletList) {
                when (val name = Names.of(w.address)) {
                    is Names.Name.Domain -> Identity.rememberDomain(this@MainActivity, w.address, name.text)
                    Names.Name.None -> Identity.rememberDomain(this@MainActivity, w.address, null)
                    // Keep whatever was cached. A bad connection is not evidence that somebody
                    // stopped owning their name.
                    Names.Name.Unreachable -> Unit
                }
            }
            if (connected == addr) {
                val own = Identity.domain(this@MainActivity, addr)
                val lent = own ?: Vouch.of(this@MainActivity, addr)
                    ?.let { Identity.domain(this@MainActivity, it.owner) }
                myDomain = lent
                myDomainBorrowed = own == null && lent != null
            }
        }

        lifecycleScope.launch {
            val b = withContext(Dispatchers.IO) { Chain.balance(addr) }
            // Only report a balance for the wallet still selected — switching twice quickly
            // would otherwise show one wallet's money under another's name.
            // One failed background read must not replace a good balance with a dash.
            if (connected == addr && (b is Chain.Balance.Ok || !quiet || balance !is Chain.Balance.Ok)) balance = b
        }
        lifecycleScope.launch {
            val r = Rewards.summary(addr)
            if (connected == addr) rewardSummary = r
        }
        lifecycleScope.launch {
            val h = withContext(Dispatchers.IO) { Tokens.holdings(this@MainActivity, addr) }
            // Null means the chain could not be reached — keep what is on screen rather than
            // replacing a real list with an empty one.
            if (connected == addr && h != null) {
                // Ask the market about everything held, in one call, before drawing any of it.
                // SOL is asked about as wrapped SOL, which is how it is priced.
                // One call for every mint held — price, icon and a day each. `quotes` carries
                // the day and the icon the cards draw; `info` is the same answer in the shape
                // the rest of this file already reads.
                val q = Market.quotes(h.map { it.mint ?: Market.WSOL })
                val info = q.mapValues { (_, v) -> Market.Info(v.verified ?: false, v.usd, v.icon) }
                if (connected != addr) return@launch
                market = info
                quotes = q
                // Summed once, here, where both the balances and the prices are in hand.
                val priced = h.mapNotNull { t ->
                    info[t.mint ?: Market.WSOL]?.usdPrice?.let { u ->
                        java.math.BigDecimal.valueOf(t.units, t.decimals).toDouble() * u
                    }
                }
                // A failed price read keeps the last good total rather than dropping it.
                if (priced.isNotEmpty()) totalUsd = priced.sum() else if (info.isNotEmpty()) totalUsd = null
                holdings = h.filter { t ->
                    // SOL always. Everything else has to be a token Jupiter both recognises
                    // AND has a logo for — which is what keeps airdropped rubbish out of a
                    // list of money. A mint with no icon draws as a letter on a hashed
                    // colour, and user call 2026-10-01: an unofficial or picture-less token
                    // should not be on this row at all.
                    //
                    // An empty map means the lookup FAILED rather than that nothing is
                    // verified, so nothing is hidden on a dropped connection: an empty token
                    // list would look exactly like a wallet that had been emptied. That is
                    // why the whole test is skipped when the map is empty rather than each
                    // token failing it individually.
                    val known = info[t.mint]
                    t.isSol || info.isEmpty() ||
                        (known?.verified == true && !known.icon.isNullOrBlank())
                }.sortedBy { t ->
                    // Dust goes last. A wallet collects tokens worth a fraction of a cent, and
                    // they were taking the first card in the row ahead of the money. Dust is
                    // "worth less than the smallest unit fiat can show" — the same test
                    // Money uses to decide whether to print "<$0.01" rather than a figure —
                    // and a token with no market at all counts as dust too, since there is no
                    // amount to compare.
                    //
                    // `sortedBy` is stable, so this only partitions: within each group the
                    // order the chain gave is untouched.
                    val worth = info[t.mint ?: Market.WSOL]?.usdPrice?.let { u ->
                        java.math.BigDecimal.valueOf(t.units, t.decimals).multiply(java.math.BigDecimal(u.toString()))
                    }
                    if (worth != null && !Money.cutsToZero(worth, 2)) 0 else 1
                }
            }
        }
        lifecycleScope.launch {
            // Also read here, not only when the Earn tab opens: the bell has to be able to
            // say "there is a new offer" from the home screen, which is the whole point of it.
            val o = Rewards.offers(addr)
            if (connected == addr && o != null) liveOffers = o
        }
    }

    /**
     * Asks what became of anything bought and not yet delivered.
     *
     * Run on every launch, not only right after paying: delivery trails the payment by seconds
     * and the app is often gone by then. This is the half that used to be missing — the
     * coroutine watching an order died with the screen and nothing remembered there was one.
     */
    private fun catchUpOrders() {
        val open = orders.waiting()
        if (open.isEmpty()) return
        lifecycleScope.launch {
            for (o in open) {
                val d = Shop.order(o.invoiceId) ?: continue
                // The catch-up matters more than the live watcher for this: the code usually
                // lands after the app is closed, so this is where most of them are collected.
                d.redemption?.let {
                    // Only raise the sheet for something bought in this session. A code found
                    // while catching up on a week-old order is not news worth interrupting for.
                    val isNew = o.code.isNullOrBlank()
                    orders.setCode(o.invoiceId, it.toJson())
                    if (isNew && o.at > System.currentTimeMillis() - 10 * 60_000L) {
                        showCodeFor = o.invoiceId
                    }
                }
                if (d.status != o.status) {
                    orders.setStatus(o.invoiceId, d.status)
                    push("shop: ${o.name}, ${d.status}")
                }
            }
            refreshLists()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Let the day-of-prices cache outlive the process. Without it every cold start
        // re-asked CoinGecko for a day per token, which on the free tier starts answering 429
        // and leaves the token row with no lines at all.
        MarketDay.attach(this)
        installCrashGuard()
        // Before anything talks to the server: the saved session, if there is one.
        Session.init(this)
        wallet = Wallet(this)
        connected = wallet.address   // a session persisted from last launch
        contacts = Contacts(this)
        messages = Messages(this)
        orders = Orders(this)
        alertsSeen = Alerts.seenAt(this)
        contactCount = contacts.count()
        inbox = Inbox(this)
        watch = Watch(this)
        myName = Identity.label(this)
        myEmail = Identity.email(this)
        refreshLists()
        refreshWallets()
        Push.init(this)
        // Made now, not at the first notification, so they are already listed in the phone's
        // settings when "Allow notifications" sends somebody there.
        Push.ensureChannels(this)
        openFromNotification(intent)

        // The screen is white now, so the system bars have to draw themselves dark. On a phone
        // in dark mode the clock and battery are white, and they vanished into our background
        // — "isAppearanceLight*" means "the background is light", not "the icons are light".
        // The app draws its own insets — `statusBarsPadding`, `navigationBarsPadding` and
        // `imePadding` below. Without this the window ALSO resized itself for the keyboard
        // (manifest `adjustResize`), and the two fought: every resize changed the ime inset,
        // which changed the padding, which resized again. That loop wedged the main thread and
        // the phone killed the app with "Input dispatching timed out" the moment anything was
        // tapped. One of the two has to own it; Compose does.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        reader = TapReader(
            activity = this,
            onLog = { line -> runOnUiThread { push(line) } },
            onRequest = { req -> runOnUiThread { onRead(req) } },
            // Their phone had no request, only its card: an introduction. The popup is where
            // you decide to pay them or ask them for money — the same one a closed app shows.
            onCard = { card -> runOnUiThread { startActivity(TapPopupActivity.intent(this, TapPayload.Card(card), cameFrom = "app")) } },
        )

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(background = D.White, surface = D.White)) {
                val scope = rememberCoroutineScope()
                val clipboard = LocalClipboardManager.current
                var amount by rememberSaveable { mutableStateOf(lastAmount) }
                // Re-read on every resume: NFC can be switched off while the app sits open,
                // and a stale "Ready" is worse than no indicator at all.

                LaunchedEffect(Unit) {
                    scope.launch { TapHostApduService.events.collect { push(it) } }
                    scope.launch {
                        TapHostApduService.served.collect { req -> onServed(req) }
                    }
                    scope.launch {
                        // Re-arm for the next person only once the phones have SEPARATED, then
                        // after a beat. Re-arming the instant a request was served meant two
                        // phones still resting back-to-back got re-read, and the payee gained a
                        // request nobody had actually received. A queue must never invent a payer.
                        TapHostApduService.parted.collect {
                            delay(REARM_AFTER_PART_MS)
                            if (mode == Mode.REQUEST && TapHostApduService.pending.value == null) {
                                applyMode(amount)
                            }
                        }
                    }
                }

                LaunchedEffect(mode, amount, connected) { applyMode(amount) }

                // No wallet linked (a fresh install, or cleared data): the landing page, and
                // nothing else. Everything below assumes an address.
                // Preview builds can open the landing page with a wallet still linked, on a
                // given page, so it can be looked at without logging out.
                val landingPreview = remember {
                    intent.getIntExtra("preview_landing", -1).takeIf { BuildConfig.PREVIEWS && it >= 0 }
                }
                // The way from the landing page into Home. The page used to be swapped for Home in
                // one frame the instant a wallet answered: a blue page, then a white one full of
                // things, with nothing between. Now the landing page stays for a moment after the
                // wallet is linked and thins out, and Home then comes up into its place.
                var onLanding by remember { mutableStateOf(connected == null) }
                val landingFade = remember { Animatable(1f) }
                val arrival = remember { Animatable(1f) }
                LaunchedEffect(connected) {
                    if (connected == null) { onLanding = true; landingFade.snapTo(1f) }
                    else if (onLanding) {
                        landingFade.animateTo(0f, tween(360, easing = FastOutSlowInEasing))
                        arrival.snapTo(0f)
                        onLanding = false
                        arrival.animateTo(1f, tween(620, easing = FastOutSlowInEasing))
                    }
                }
                if (connected == null || onLanding || landingPreview != null) {
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        alpha = landingFade.value
                        val grow = 1f + 0.04f * (1f - landingFade.value)
                        scaleX = grow; scaleY = grow
                    }) {
                        Landing(busy, startAt = landingPreview ?: 0) { if (landingPreview == null && connected == null) startConnect() }
                    }
                    // The wallet list is a sheet over the landing page too.
                    picker?.let { apps ->
                        Tray(onClose = { picker = null }) {
                            Spacer(Modifier.height(Space.lead))
                            WalletPicker(apps, onPick = { app -> picker = null; linkWallet(app) })
                        }
                    }
                    return@MaterialTheme
                }

                // Everything that depends on WHICH wallet is connected. Runs for a session
                // restored at launch as well as a fresh connect — the Genesis lookup used to
                // live only in the Connect button, so a relaunched app silently lost its
                // "Verified Seeker" badge. It was visible in a real tap's payload: no "g".
                LaunchedEffect(connected) {
                    val addr = connected ?: return@LaunchedEffect
                    if (myGenesis == null) {
                        val g = withContext(Dispatchers.IO) {
                            runCatching { Genesis.mintFor(addr) }.getOrNull()
                        }
                        myGenesis = g
                        // Cached for the HCE service, which answers taps with the app closed and
                        // has no time for a network call inside one.
                        Identity.rememberGenesis(this@MainActivity, addr, g)
                        push(if (g != null) "✓ Verified Seeker, genesis ${g.take(8)}…"
                             else "   no Genesis Token on this wallet")
                    }
                    // Needed without a server too: a tap on a locked phone arrives as a notification.
                    if (!Push.canNotify(this@MainActivity) && android.os.Build.VERSION.SDK_INT >= 33) {
                        askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                // Registered once the session is valid, and again whenever it changes or a wallet
                // is added. It used to run once at launch, before sign-in had finished, fail with
                // a 401 and never be retried — so a phone that signed in a moment later never
                // received a single push.
                LaunchedEffect(connected, Session.version, walletList.size) {
                    val addr = connected ?: return@LaunchedEffect
                    if (!ServerClient.enabled || !Push.configured || !Session.valid(addr)) return@LaunchedEffect
                    Push.token(this@MainActivity)?.let { t ->
                        push(if (ServerClient.registerDevice(addr, t)) "✓ push registered" else "✗ push registration failed")
                    }
                }

                // The payee's half, for every open request at once and with no deadline. A
                // young request is checked every second — its payer is probably in their
                // wallet right now — and an old one rarely, since it may be paid tomorrow.
                LaunchedEffect(Unit) {
                    val next = mutableMapOf<String, Long>()
                    while (true) {
                        if (checkAllNow) { next.clear(); checkAllNow = false }
                        val now = System.currentTimeMillis()
                        for (e in owed.filter { it.live }) {
                            if (now < (next[e.reference] ?: 0L)) continue
                            checkOwed(e)
                            val age = now - e.createdAt
                            next[e.reference] = now + when {
                                age < 60_000 -> 2_000L
                                age < 10 * 60_000 -> 8_000L
                                else -> 60_000L
                            }
                        }
                        // And the other direction: requests WE still owe. Paying one only
                        // marks it when the confirmation comes back in time — a payment that
                        // lands and confirms a moment too late leaves the row sitting there
                        // with Pay and Decline on it, for money that has already gone.
                        for (i in toPay.filter { it.payable }) {
                            val ref = i.request.reference
                            if (now < (next[ref] ?: 0L)) continue
                            checkOwing(i)
                            val age = now - i.readAt
                            next[ref] = now + when {
                                age < 60_000 -> 3_000L
                                age < 10 * 60_000 -> 15_000L
                                else -> 120_000L
                            }
                        }
                        delay(500)
                    }
                }

                // The server's doorbell: while the app is on screen, a payment to us is
                // announced the moment the server sees it. The announcement is only a reason
                // to look — checkOwed verifies it on-chain before anything turns green.
                // The shop, retried until it answers.
                //
                // It used to be fetched once, inside refreshWallets, and only when the list was
                // still empty — so a single slow or failed call meant no "Pay a bill" section
                // for the rest of the session, with nothing on screen to say why or any way to
                // ask again. The catalogue is the server's and the server can be cold (its
                // first call after a restart pages through Bitrefill), which is exactly when
                // someone opens the app and finds the section missing.
                //
                // Stale-while-revalidate: the copy from the last good answer is drawn first, so
                // "Pay a bill" is on the very first frame instead of arriving after the server
                // does, and the fresh answer replaces it when it comes. Asked again on every
                // resume, not only while empty — a catalogue that changed (a new guide, a
                // product out of stock) would otherwise wait for the app to be killed.
                LaunchedEffect(resumeTick) {
                    if (shopSections.isEmpty()) {
                        withContext(Dispatchers.IO) { Shop.cached(this@MainActivity) }
                            ?.let { shopSections = it }
                    }
                    val fresh = Shop.sections(this@MainActivity)
                    if (fresh == null) {
                        // The server did not answer. Only said on screen when there is nothing
                        // cached to draw instead — an old catalogue beats an apology.
                        if (shopSections.isEmpty()) shopLoad = ShopLoad.FAILED
                    } else {
                        // An empty answer never blanks what is already showing.
                        if (fresh.isNotEmpty()) shopSections = fresh
                        shopLoad = ShopLoad.DONE
                    }
                }

                // Anything written to us while the app was closed. The live stream below only
                // carries what arrives while it is connected, and a push only reaches a phone
                // that has one — so the catch-up is the only thing that covers both.
                // Signing in is never started from here. The wallet's sign sheet appears only after a
                // button — the authorization sheet that Rewards, Referrals and a purchase open when
                // nobody is signed in — so a prompt is always something the person just asked for.
                // It used to run on launch and on every resume, which surprised people with a wallet
                // sheet they could not account for, and failed quietly when they ignored it.

                LaunchedEffect(connected, resumeTick) {
                    val addr = connected ?: return@LaunchedEffect
                    if (!ServerClient.enabled || !Session.valid(addr)) return@LaunchedEffect
                    var added = false
                    ServerClient.messages(addr, messages.since()).forEach {
                        if (messages.add(it)) added = true
                    }
                    if (added) refreshLists()
                }

                // A slow poll as well, for the times the stream is down or the push never came.
                LaunchedEffect(connected) {
                    if (connected == null) return@LaunchedEffect
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (true) { delay(12_000); refreshWallets(quiet = true) }
                    }
                }

                // Leaving the recharge screen forgets the number: coming back starts from the empty
                // box, not from the last number and its plans.
                LaunchedEffect(tab, shopSection) {
                    if (!(tab == Tab.SHOP && shopSection?.isRechargeShelf() == true)) {
                        rechargeNumber = ""; rechargeOperator = null; operatorNote = null
                    }
                }

                // The session arriving is the moment every server-backed list can finally be read.
                LaunchedEffect(Session.version) { if (Session.valid(connected)) refreshWallets() }

                // Keyed on the session too: the stream is the session's, so it opens when sign-in
                // succeeds and reopens when the token is replaced.
                LaunchedEffect(connected, Session.version) {
                    val addr = connected ?: return@LaunchedEffect
                    if (!ServerClient.enabled || !Session.valid(addr)) return@LaunchedEffect
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        ServerClient.events(addr).collect { ev ->
                            when (ev) {
                                // Money moved in the wallet — read the balance again now rather
                                // than at the next poll.
                                is ServerClient.Activity -> {
                                    refreshWallets(quiet = true)
                                    push("wallet: ${ev.direction} ${ev.amount} ${ev.symbol}")
                                    // Onto the notifications page. The same transfer also comes
                                    // as a push; AlertLog makes one line of the two.
                                    AlertLog.wallet(this@MainActivity, ev.direction, ev.amount, ev.symbol, null)
                                }
                                is ServerClient.Paid -> {
                                    val e = owed.firstOrNull { it.reference == ev.reference && it.live }
                                    if (e != null) {
                                        push("server: ${ev.reference.take(8)}… paid, verifying")
                                        checkOwed(e)
                                    }
                                }
                                // Someone asked us for money from their popup, after the phones
                                // parted. With the app open, show it straight away.
                                is ServerClient.Requested -> {
                                    inbox.add(ev.request); refreshLists()
                                    push("server: ${ev.request.label} requests ${ev.request.amount} ${ev.request.symbol}")
                                    startActivity(TapPopupActivity.intent(this@MainActivity, TapPayload.Request(ev.request), cameFrom = "server"))
                                }
                                is ServerClient.Withdrawn -> {
                                    inbox.markWithdrawn(ev.reference); refreshLists()
                                    push("- ${ev.reference.take(8)}… was withdrawn by the requester")
                                }
                                is ServerClient.Deferred -> {
                                    watch.markDeferred(ev.reference, ev.label); refreshLists()
                                    push("- ${ev.label.ifBlank { "They" }} will pay ${ev.reference.take(8)}… later")
                                }
                                is ServerClient.Rejected -> {
                                    watch.markRejected(ev.reference, ev.label); refreshLists()
                                    push("- ${ev.label.ifBlank { "They" }} declined ${ev.reference.take(8)}…")
                                    Haptics.failure(this@MainActivity)
                                }
                                is ServerClient.Shopped -> {
                                    // Re-asks rather than believing the event. One path to a
                                    // settled order whether the news came by push or polling,
                                    // and the server is not the authority on Bitrefill anyway.
                                    push("shop: ${ev.status}")
                                    catchUpOrders()
                                }
                                is ServerClient.Messaged -> {
                                    if (messages.add(Messages.Note(ev.from, ev.body, ev.at, mine = false, id = ev.id))) {
                                        refreshLists()
                                        push("← ${ev.label.ifBlank { "They" }}: ${ev.body}")
                                        Haptics.contact(this@MainActivity)
                                    }
                                }
                                is ServerClient.Moved -> {
                                    // Say it, then re-read everything it could have changed: the
                                    // balance, the offer list, the referral sheet, the history.
                                    ev.progress.words()?.let { (title, text) -> push("$title, $text") }
                                    AlertLog.progress(this@MainActivity, ev.progress)
                                    when (ev.progress.state) {
                                        "confirmed", "paid", "settled" -> Haptics.success(this@MainActivity)
                                        "reversed" -> Haptics.failure(this@MainActivity)
                                    }
                                    Live.bump()
                                    refreshWallets()
                                }
                                is ServerClient.Tapped -> {
                                    push("server: ${ev.card.label} tapped you")
                                    AlertLog.tapped(this@MainActivity, ev.card)
                                    startActivity(TapPopupActivity.intent(this@MainActivity, TapPayload.Card(ev.card), cameFrom = "server"))
                                }
                            }
                        }
                    }
                }

                // How far an edge swipe has pulled the current screen away (0..1). Read inside a
                // graphicsLayer so a drag redraws the layer and never recomposes the screen.
                val backPull = remember { Animatable(0f) }
                // How far the scratch stage is up (0..1): the page behind blurs by it, the stage dims by it.
                val scratchT = remember { Animatable(0f) }
                val rootView = LocalView.current

                // One step back, in the order things were opened. Without this the system back
                // left the app from inside a thread — the one place it most looks like a screen
                // you can back out of.
                fun stepBack() {
                    when {
                        // The item page backs out before the thread it was opened from.
                        threadItem != null -> threadItem = null
                        thread != null -> thread = null
                        cards -> cards = false
                        shopProduct != null && shopDirect -> {
                            shopProduct = null; shopSection = null; shopDirect = false
                            tab = Tab.TAP
                        }
                        shopProduct != null -> shopProduct = null
                        // Leaving the section leaves the tab with it. The shop body is
                        // `shopSection?.let { … }`, so clearing the section while the tab stayed
                        // on SHOP drew an empty screen under the fallback "Pay a bill" title —
                        // one back press that looked like the app had broken.
                        shopSection != null -> { shopSection = null; tab = Tab.TAP }
                        else -> tab = Tab.TAP
                    }
                }

                // Predictive back, the way Google Pay and Photos do it: while the edge is dragged
                // the screen shrinks and slides away so the person sees they are leaving; letting
                // go commits with a tick, pulling back cancels and it eases home. Only the final
                // `stepBack()` changes state, so a cancelled swipe costs nothing.
                PredictiveBackHandler(enabled = thread != null || cards || tab != Tab.TAP) { events ->
                    try {
                        events.collect { backPull.snapTo(it.progress) }
                        rootView.backHaptic()
                        stepBack()
                        backPull.snapTo(0f)
                    } catch (e: CancellationException) {
                        backPull.animateTo(0f, tween(160))
                        throw e
                    }
                }

                // Every screen change eases in instead of cutting. The screens are swapped by `if`
                // branches, so with nothing between them each change was a one-frame hard cut —
                // the flicker. Keyed on what is on screen; a new key makes a new animation that
                // STARTS at 0, so the first frame of the new screen is already faded rather than
                // flashing at full strength before an effect can reset it.
                val screenKey = "$tab|${thread?.address}|$threadItem|$cards|${shopSection?.title}|${shopProduct?.id}"
                val enter = remember(screenKey) { Animatable(0f) }
                LaunchedEffect(screenKey) { enter.animateTo(1f, tween(150, easing = FastOutSlowInEasing)) }

                // Back at Home: every other screen forgets where it was scrolled to, so opening
                // one afresh starts at its top. Home keeps its place — it is what you return to.
                val atHome = tab == Tab.TAP && thread == null && !cards
                LaunchedEffect(atHome) { if (atHome) ScrollHold.keepOnly("home") }

                // Arriving from the landing page (see `arrival` above): the whole of Home, bar and
                // all, rises a little and comes into focus. At rest this is 1 and does nothing.
                Surface(Modifier.fillMaxSize().graphicsLayer {
                    alpha = arrival.value
                    translationY = (1f - arrival.value) * 28.dp.toPx()
                    val settle = 0.97f + 0.03f * arrival.value
                    scaleX = settle; scaleY = settle
                }, color = D.White) {
                    // statusBarsPadding, not a guessed top margin: the pill sits at the very
                    // top of the app and was drawing straight through the clock and battery.
                    Column(
                        // The page behind a lifted reward card. Read in the layer, so it follows the
                        // card's progress frame by frame without recomposing anything.
                        Modifier.fillMaxSize().graphicsLayer {
                            val r = 14.dp.toPx() * scratchT.value
                            renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Decal) else null
                        }.statusBarsPadding().graphicsLayer {
                            // Entering: fade up from 10dp below. Leaving by swipe: shrink a little
                            // and slide toward the edge being dragged.
                            // Starts at 35%, never 0: from nothing, the frame between two screens
                            // is plain white, which reads as a flash rather than a transition.
                            alpha = .35f + .65f * enter.value
                            translationY = (1f - enter.value) * 8.dp.toPx()
                            val pull = backPull.value
                            val scale = 1f - .06f * pull
                            scaleX = scale; scaleY = scale
                            translationX = pull * 20.dp.toPx()
                        },
                    ) {
                    // A Box, so the pay bar can float over the page rather than queue up at
                    // the end of it. Only the home page is scrollable behind it.
                    val home = tab == Tab.TAP && thread == null && !cards
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Built here, above everything, because two things read it: the bell's
                    // count in the header and the panel itself. Derived from the same lists
                    // the rest of the screen draws, so it cannot drift from them.
                    val known0 = contacts.all()
                    // Keyed on the log's own counter, so a push that lands while the app is open
                    // puts its line on the page (and its count on the bell) without a reload.
                    val logged = remember(AlertLog.version) { AlertLog.all(this@MainActivity) }
                    val alerts = Alerts.build(
                        toPay = toPay, owed = owed, notes = notes, orders = bought,
                        offers = liveOffers,
                        name = { addr -> known0.firstOrNull { it.address == addr }?.let { displayName(it.label, addr) } ?: "Someone" },
                        logged = logged,
                    )
                    val unread = Alerts.unread(alerts, alertsSeen)
                    // `spends` is read here, inside the composition that rebuilds the list, so
                    // a category picked a moment ago is on the next row without a restart.
                    val spends = remember { Spends(this@MainActivity) }
                    var filedAt by remember { mutableStateOf(0L) }
                    val history = remember(toPay, owed, bought, known0, filedAt, rewardSummary) {
                        History.build(
                            toPay = toPay, owed = owed, orders = bought,
                            name = { addr -> known0.firstOrNull { it.address == addr }?.let { displayName(it.label, addr) } ?: "Someone" },
                            known = { addr -> known0.any { it.address == addr } },
                            filed = { sig -> spends.of(sig) },
                            rewards = rewardSummary?.rows.orEmpty(),
                            payouts = rewardSummary?.payouts.orEmpty(),
                        )
                    }
                    /** Files a category and nudges the list to rebuild with it. */
                    val file: (History.Txn, History.Spend) -> Unit = { t, spend ->
                        spends.file(t.signature, spend)
                        filedAt = System.currentTimeMillis()
                    }

                    // `orphans` was here — a count of rows no thread can show, used only to
                    // decide whether §3 offered an "All activity" link. §4.1 removed that link,
                    // so the count had nothing left to answer. The rows it counted are still
                    // reachable: the Requests tab lists them.
                    Column(
                        Modifier.fillMaxSize()
                            .then(if (tab == Tab.TAP && !cards && !checking && thread == null)
                                Modifier.verticalScroll(rememberHeldScroll("home")) else Modifier)
                            // Top is deliberately smaller than the rest. statusBarsPadding
                            // has already cleared the clock and the cutout, so a further
                            // 20dp sat the wordmark a third of the way down the screen for
                            // no reason — and in a debug build the §N tag adds its own line
                            // on top of that. The bottom keeps 20dp: it is the end of a
                            // scroll, where content running into the edge reads as cut off.
                            .padding(start = Space.gutter, end = Space.gutter, top = 6.dp, bottom = Space.gutter)
                            // ONE inset modifier, not two. `navigationBarsPadding().imePadding()`
                            // chained is the documented way to wedge this: each consumes what it
                            // applies, the keyboard animation re-reports what is left, and the
                            // layout re-measures forever. It ANR'd the app on the first touch
                            // after the keyboard opened, twice, and never once without it.
                            // A union is resolved in a single pass.
                            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
                    ) {
                        // The AirPay bar belongs to HOME, not to every page.
                        //
                        // It carries your avatar and the notification bell — both of which are
                        // about the app as a whole, and neither of which a sub-page is asking
                        // about. Rewards, Offers and Referrals each bring their own title and
                        // back arrow, so the bar above them was a second header saying nothing
                        // (user call, 2026-10-01). Tabs that still rely on `BackHeader` get it
                        // from there; none of them needs this.
                        // Home only — and a thread is not Home, even though it lives in the Home
                        // tab. The thread draws its own header (back · face · name · Rename) and
                        // the item page inside it draws another, so leaving this one up stacked
                        // the AirPay bar above a screen that already said where it was. Same
                        // rule the shop's service page gets below: whoever draws a header owns
                        // the top of the screen.
                        if (tab == Tab.TAP && thread == null && !cards) Header(
                            checking = checking,
                            unread = unread,
                            // The on-chain name first: it is the one other people see, so it
                            // should be the one on your own avatar. Falls through to a chosen
                            // name, then to "Y" for you — never to a phone model again.
                            me = myDomain ?: myName,
                            // Editing the name IS the profile, for now: it is the only thing
                            // about yourself this app stores, and it is what other phones see.
                            onProfile = { drawerOpen = true },
                            onCheck = { checking = !checking },
                            onAlerts = { openAlerts() },
                            notifOff = notifOff,
                        )
                        // The header is chrome rather than a section, so the balance sits
                        // directly under it: 16, not the 40 between sections. Only drawn with
                        // the header, or a sub-page starts with a gap nothing explains.
                        if (tab == Tab.TAP) Spacer(Modifier.height(16.dp))

                        when (val st = status) {
                            is Status.Working -> { Banner("…", st.what, D.Quiet); Spacer(Modifier.height(Space.inline)) }
                            is Status.Problem -> {
                                // Ink, not red. Almost every Problem is "nothing was sent" —
                                // offline, no wallet, a withdrawn request — and red is
                                // reserved for a transaction the chain actually refused.
                                Banner(st.title, st.detail, D.Black, onDismiss = { status = Status.Idle })
                                Spacer(Modifier.height(Space.inline))
                            }
                            is Status.Sent -> {
                                Banner("✓ Sent", st.signature, D.Blue, mono = true,
                                    onDismiss = { status = Status.Idle })
                                Spacer(Modifier.height(Space.inline))
                            }
                            Status.Idle -> Unit
                        }
                        paidSignature?.let { sig ->
                            // Paid is the success ink; Sent above is still only confirming.
                            Banner("✓ Paid", sig, D.Success, mono = true, onDismiss = { paidSignature = null })
                            Spacer(Modifier.height(Space.inline))
                        }

                        if (cards) {
                            WalletsScreen(
                                list = walletList,
                                selected = activeWalletId,
                                busy = busy,
                                onUse = { w ->
                                    wallet.use(w)
                                    activeWalletId = w.id
                                    connected = w.address
                                    myGenesis = null
                                    // The armed request carries the old address until rebuilt.
                                    TapHostApduService.pending.value = null
                                    refreshWallets()
                                    applyMode(amount)
                                    push("- tapping with ${w.name} ${w.short}")
                                },
                                onAdd = { picker = WalletLink.installed(this@MainActivity) },
                                onClose = { cards = false },
                                onReceive = { w -> receiving = w },
                                onRename = { w -> renamingWallet = w },
                                onReconnect = { w ->
                                    // A fresh session with the wallet app itself, by explicit intent
                                    // where the picker linked it; the original path otherwise.
                                    val app = WalletLink.installed(this@MainActivity).firstOrNull { it.pkg == w.pkg }
                                    if (app != null) linkWallet(app) else connectWallet(fresh = true)
                                },
                                onVouch = { w -> vouchWallet = w },
                                vouchTick = vouchTick,
                                onRemove = { w ->
                                    wallet.linked.remove(w)
                                    afterWalletRemoved()
                                    connected = wallet.address
                                    refreshWallets()
                                    applyMode(amount)
                                    push("- removed ${w.name} ${w.short}")
                                },
                                onNote = { push(it) },
                            )
                            return@Column
                        }

                        thread?.let { who ->
                            // Read fresh from contacts: the stored copy in `thread` would keep
                            // the tap count and badge it had when the row was tapped.
                            val live = contacts.all().firstOrNull { it.address == who.address } ?: who
                            Thread(
                                who = live,
                                toPay = toPay,
                                owed = owed,
                                notes = notes,
                                paying = paying,
                                onRename = { name ->
                                    contacts.rename(live.address, name)
                                    refreshLists()
                                    // Their own claim is untouched; only our label for them moves.
                                    Names.forget(live.address)
                                },
                                onPay = { item -> incoming = item.request },
                                onDecline = { item ->
                                    val req = item.request
                                    // Marked, not deleted. The row stays in the thread in red,
                                    // so "they asked and I said no" is still answerable.
                                    val live = item.payable
                                    inbox.markDeclined(req.reference); refreshLists()
                                    push("- declined ${req.amount} ${req.symbol} from ${req.label}")
                                    if (live) {
                                        lifecycleScope.launch {
                                            ServerClient.reject(req.reference, Identity.label(this@MainActivity))
                                        }
                                    }
                                },
                                onWithdraw = { e ->
                                    watch.markWithdrawn(e.reference); refreshLists()
                                    push("- withdrew ${e.amount} ${e.symbol} request ${e.reference.take(8)}…")
                                    lifecycleScope.launch { ServerClient.withdraw(e.reference) }
                                },
                                onBack = { thread = null; threadItem = null },
                                onOpen = {
                                    // The same sheet a tap opens, addressed to someone already
                                    // met. Their Genesis mint is not stored, only whether they
                                    // had one; the sheet checks the chain itself, as after a tap.
                                    startActivity(TapPopupActivity.intent(
                                        this@MainActivity,
                                        TapPayload.Card(TapCard(live.address, live.label, null, null)),
                                        "contacts"))
                                },
                                onSay = { text -> say(live.address, text) },
                                // `Rewards.Row.counterparty` is an address, so the thread filters
                                // these itself rather than being handed a pre-filtered list.
                                rewards = rewardSummary?.rows.orEmpty(),
                                wallet = activeWallet,
                                openItem = threadItem,
                                onOpenItem = { threadItem = it },
                                // "Later" is not an answer. The request stays payable and the
                                // asker is told, so their phone shows it as pending rather than
                                // as silence — the same call the tap sheet's own Later makes.
                                onLater = { item ->
                                    lifecycleScope.launch {
                                        ServerClient.defer(
                                            item.request.reference,
                                            Identity.label(this@MainActivity),
                                        )
                                    }
                                    push("- told ${item.request.label} we'll pay later")
                                },
                            )
                            return@Column
                        }

                        // Rewards draws its OWN back row and title (§1 of
                        // ui/PORT-2026-10-01-3.md), so the generic one is skipped for it —
                        // otherwise the screen opens with "← Earn" above "← Rewards".
                        // Rewards and Offers both draw their OWN back row and title, so the
                        // generic one is skipped for them — otherwise the screen opens with
                        // "← Offers" above "← Offers".
                        // A service's page (§3.2) and an item's page (§8.4) each draw their own
                        // back row and title, so the generic header must stand down for them.
                        // Only the gift-card branch draws its own back row, so only that branch
                        // may stand this one down. Testing `shopProduct != null` alone took the
                        // header off the recharge screen too — which draws none — and left it
                        // with no way back but the system gesture.
                        val ownsHeader = tab == Tab.SHOP && shopProduct != null &&
                            shopSection?.isRechargeShelf() == false
                        if (tab != Tab.TAP && tab != Tab.REWARDS && tab != Tab.OFFERS &&
                            !ownsHeader
                        ) {
                            BackHeader(
                                when (tab) {
                                    Tab.ALERTS -> "Notifications"
                                    Tab.HISTORY -> "Transactions"
                                    else -> if (shopSection?.isRechargeShelf() == true)
                                        (if (rechargeOperator != null && Sims.e164(rechargeNumber) != null) "Select a plan" else "Recharge or pay mobile bill")
                                    else shopSection?.title ?: "Recharge and bills"
                                },
                            ) { tab = Tab.TAP; shopSection = null; shopProduct = null }
                            Spacer(Modifier.height(if (tab == Tab.SHOP) 4.dp else 16.dp))
                        }

                        if (tab == Tab.SHOP) {
                            shopSection?.let { sec ->
                                // Phone gets its own screen. The generic one lists products
                                // and then denominations, which is right for a gift card and
                                // wrong for a recharge: the number and the network have to be
                                // settled before a plan list means anything.
                                if (sec.isRechargeShelf()) {
                                    RechargeSection(
                                        section = sec,
                                        number = rechargeNumber,
                                        onNumber = {
                                            // A different number is a different network until
                                            // looked up: the old one's plans must not stay tappable.
                                            if (Sims.e164(it) != Sims.e164(rechargeNumber)) { rechargeOperator = null; operatorNote = null }
                                            rechargeNumber = it
                                        },
                                        operator = rechargeOperator,
                                        onOperator = { rechargeOperator = it },
                                        finding = findingOperator,
                                        found = operatorNote,
                                        onFind = { findOperator(it, sec) },
                                        past = bought,
                                        onBuy = { p, v, phone, extra -> purchase = Purchase.Review(Purchase.Item(p, v, sec.title, phone, extra)) },
                                    )
                                } else {
                                    ShopSection(
                                        section = sec,
                                        picked = shopProduct,
                                        onPick = {
                                            shopProduct = it
                                            // Closing a page opened from a tile goes Home; opening
                                            // one from a list is never "direct".
                                            if (it == null && shopDirect) {
                                                shopSection = null; shopDirect = false
                                                tab = Tab.TAP
                                            }
                                        },
                                        email = myEmail,
                                        onEmail = { editingEmail = true },
                                        onBuy = { p, v -> buy(p, v, sec.title) },
                                        // Matched on the mint, not on the symbol: the symbol is
                                        // whatever `Tokens` named a mint, and matching the mint
                                        // `buy()` actually builds its transfer against is the
                                        // only version that cannot disagree with what is spent.
                                        usdc = holdings.firstOrNull { it.mint == Shop.USDC_MAINNET }
                                            ?.let { BigDecimal.valueOf(it.units, it.decimals) },
                                    )
                                }
                            }
                            return@Column
                        }

                        if (tab == Tab.HISTORY) {
                            HistoryTab(
                                list = history,
                                month = histMonth,
                                onMonth = { histMonth = it },
                                onFile = file,
                                onOpen = { t -> openTxn(t, known0) },
                            )
                            return@Column
                        }

                        if (tab == Tab.ALERTS) {
                            AlertsTab(
                                alerts, alertsWere,
                                notifOff = notifOff, onAllow = { allowNotifications() },
                            ) { a ->
                                when {
                                    // Anything with a person behind it belongs in their thread,
                                    // which is where the state it refers to actually lives.
                                    a.peer != null ->
                                        known0.firstOrNull { it.address == a.peer }?.let {
                                            thread = it; threadItem = null; tab = Tab.TAP
                                        }
                                    a.kind == Alerts.Kind.OFFER -> openRewards()
                                    // A friend joining or paying is on the referral sheet; every
                                    // other reward line is on the rewards page.
                                    a.kind == Alerts.Kind.REWARD ->
                                        if (a.topic == "referral") openRefer() else openRewards()
                                    else -> tab = Tab.TAP
                                }
                            }
                            return@Column
                        }

                        if (tab == Tab.OFFERS) {
                            OffersTab(
                                connected = connected,
                                isSeeker = myGenesis != null,
                                onOpenOffer = { offerDetail = it },
                                onRefer = { openRefer() },
                                reloadKey = offersReload,
                                onBack = { tab = Tab.TAP },
                            )
                            return@Column
                        }

                        if (tab == Tab.REWARDS) {
                            RewardsTab(
                                connected, myGenesis != null,
                                onOpenCard = { row, rect -> scratching = ScratchTarget(row, rect) },
                                openId = scratching?.row?.id,
                                onInvite = { refer = true },
                                onOffers = { tab = Tab.OFFERS },
                                onBack = { tab = Tab.TAP; shopSection = null; shopProduct = null },
                                // §5's cards open the product in the shop, which is this
                                // screen's state to move.
                                // The shop body is `shopSection?.let { … }`, so setting only the
                                // product landed on a blank screen under the fallback "Pay a
                                // bill" header — a recommended card opened nothing at all. The
                                // product has to arrive with the shelf it came off; `More` is
                                // the fallback because that is where a product with no shelf of
                                // its own is listed.
                                onProduct = { p ->
                                    shopSection = shopSections.firstOrNull { sec ->
                                        sec.products.any { it.id == p.id }
                                    } ?: moreSection(shopSections)
                                    shopProduct = p
                                    shopDirect = false
                                    tab = Tab.SHOP
                                },
                                // The same shelf the More tile opens, from the same function.
                                onSeeAll = moreSection(shopSections)?.let { more ->
                                    { openShelf(more) }
                                },
                            )
                            return@Column
                        }

                        // RequestsTab (the Activity screen) was here. §9 removes it: nothing linked to
                        // it, and a request somebody deferred is reached through its notification
                        // and through that person's thread.

                        BalanceCard(
                            totalUsd = totalUsd,
                            wallet = activeWallet,
                            others = walletList.filter { it.address != connected },
                            balance = balance,
                            mode = mode == Mode.REQUEST,
                            onSend = { mode = Mode.PAY },
                            onRequest = {
                                mode = if (mode == Mode.REQUEST) Mode.IDLE else Mode.REQUEST
                                paidSignature = null
                            },
                            onCards = { cards = true },
                            onDetails = {
                                walletDetails = activeWallet
                            },
                            onSelectWallet = { w ->
                                wallet.use(w)
                                connected = w.address
                                myGenesis = null
                                TapHostApduService.pending.value = null
                                refreshWallets()
                                applyMode(amount)
                                push("- tapping with ${w.name} ${w.short}")
                            },
                        )
                        if (connected == null) {
                            Spacer(Modifier.height(12.dp))
                            PrimaryButton(if (busy) "Opening your wallet…" else "Connect a wallet",
                                onClick = { startConnect() }, loading = busy)
                        }

                        // "They see you as <name>" lived here. It was a second door to the
                        // rename dialog the avatar now opens, and it spent a line explaining
                        // something nobody had asked about yet.

                        val waiting = owed.count { !it.paid }

                        if (mode == Mode.REQUEST) {
                            val amountProblem = when {
                                amount.isBlank() -> "Enter an amount"
                                amount.toDoubleOrNull() == null -> "That is not a number"
                                amount.toDouble() <= 0 -> "Must be more than zero"
                                amount.toDouble() > PayRequest.MAX_AMOUNT -> "Too large for a tap"
                                PayRequest("", amount, "SOL", "", "").lamports() == null -> "At most 9 decimal places"
                                else -> null
                            }
                            OutlinedTextField(
                                value = amount,
                                // Filter at the keystroke so an unparseable value never reaches
                                // the wire in the first place.
                                onValueChange = { v -> if (v.length <= 12 && v.all { it.isDigit() || it == '.' }) amount = v },
                                label = { Text("Amount (SOL)") },
                                singleLine = true,
                                isError = amountProblem != null,
                                supportingText = amountProblem?.let { { Text(it, color = D.DangerInk) } },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            HowYouLook(myDomain, myDomainBorrowed, myName) { renaming = true }
                            Spacer(Modifier.height(12.dp))
                        }

                        // The breakdown behind the one figure above — behind a toggle, closed.
                        // What you hold is reference, not the reason you opened the app; the
                        // cards were the loudest thing on Home and pushed Pay a bill below the
                        // fold. The total stays up top, and one tap opens what it is made of.
                        if (holdings.isNotEmpty()) {
                            var assetsOpen by rememberSaveable { mutableStateOf(false) }
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    // No ripple: a grey box across the full width read as a glitch
                                    // next to the quiet press every other row here has.
                                    .clickable(
                                        remember { MutableInteractionSource() }, indication = null,
                                    ) { assetsOpen = !assetsOpen }
                                    .semantics {
                                        contentDescription =
                                            if (assetsOpen) "Hide your assets" else "Show your assets"
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Your assets · ${holdings.size}", color = D.Quiet,
                                    style = Type.caption,
                                )
                                Spacer(Modifier.width(Space.tight))
                                // Hugeicons' chevron (ArrowDown01), turned over when open.
                                val flip by animateFloatAsState(if (assetsOpen) 180f else 0f, label = "assets")
                                HiIcon(
                                    Hi.ArrowDown, D.Quiet, 20.dp,
                                    modifier = Modifier.graphicsLayer { rotationZ = flip },
                                )
                            }
                            // The token row deliberately runs OUT of the page's gutters to the
                            // screen edge, but `AnimatedVisibility` clips its content to the box
                            // it animates — which is the padded column. So while it opened the
                            // cards were cut off at the right gutter (a strip of "padding"), and
                            // the moment the animation ended the clip lifted and they ran out to
                            // the edge: the flicker. The default clip is turned off and replaced
                            // by one that is tight vertically, where the animation is, and wide
                            // horizontally, where the row is meant to go.
                            val bleed = with(LocalDensity.current) { Space.gutter.toPx() }
                            AnimatedVisibility(
                                assetsOpen,
                                modifier = Modifier.drawWithContent {
                                    clipRect(-bleed, 0f, size.width + bleed, size.height) {
                                        this@drawWithContent.drawContent()
                                    }
                                },
                                enter = fadeIn() + expandVertically(clip = false),
                                exit = fadeOut() + shrinkVertically(clip = false),
                            ) {
                                Column {
                                    Spacer(Modifier.height(Space.tight))
                                    TokenBadges(holdings, quotes) { mint ->
                                        clipboard.setText(AnnotatedString(mint))
                                        Haptics.tick(this@MainActivity)
                                        push("- copied $mint")
                                    }
                                }
                            }
                        }

                        // The "Notifications are off" banner that sat here is gone. Home is where
                        // the balance is read, and a standing complaint under it read as something
                        // wrong with the money. The question now lives on the notifications page
                        // (AlertsScreen.kt, AllowNotifications), and the bell carries a dot.

                        // The four things done most, one tap each, under what you hold.
                        Spacer(Modifier.height(Space.lead))
                        HomeActions(
                            isSeeker = myGenesis != null,
                            onBuy = { url ->
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                            },
                            onScan = { startActivity(ScanActivity.intent(this@MainActivity)) },
                            onPayAnyone = { payAnyone = true },
                            onHistory = {
                                histMonth = if (history.isEmpty()) History.monthOf(System.currentTimeMillis())
                                else History.monthOf(history.first().at)
                                tab = Tab.HISTORY
                            },
                        )

                        // Always on Home. The tiles come from the server, so while they are on
                        // their way — or the server is down — the section says so rather than
                        // vanishing and leaving a page that looks like it never had one. An
                        // answered-but-empty catalogue (shop switched off) still hides it.
                        if (shopSections.isNotEmpty() || shopLoad != ShopLoad.DONE) {
                            Spacer(Modifier.height(HOME_GAP))
                            Text("Recharge and bills", color = D.Black, style = Type.heading)
                            Spacer(Modifier.height(Space.lead))
                            if (shopSections.isNotEmpty()) {
                                ShopTiles(shopSections) { sec, product -> openShelf(sec, product) }
                            } else {
                                if (shopLoad == ShopLoad.LOADING) BillTilesSkeleton()
                                else Text(
                                    "Couldn't load bills right now. Check your connection, it tries again when you reopen the app.",
                                    color = D.Quiet, style = Type.caption,
                                    modifier = Modifier.padding(vertical = Space.row),
                                )
                            }
                        }
                        if (Rewards.enabled) {
                            Spacer(Modifier.height(HOME_GAP))
                            EarnStrip(rewardSummary) { openRewards() }
                            Spacer(Modifier.height(Space.lead))
                            EarnTiles(
                                onRewards = { openRewards() },
                                onOffers = { tab = Tab.OFFERS },
                                // Nothing on the server tracks a referral yet, so this shares an
                                // invite and claims nothing else. A tile that promised a bonus
                                // nobody is counting would be the one lie on this screen.
                                onReferrals = { openRefer() },
                            )
                        }

                        // Always on Home: with nothing yet it says so, centred, instead of the
                        // section being missing.
                        Spacer(Modifier.height(HOME_GAP))
                        HistoryStrip(
                            list = history,
                            onViewAll = {
                                // Opened on the newest month that HAS something, not on
                                // today: landing on an empty January because nothing has
                                // happened yet this month shows an empty screen to
                                // somebody who just asked to see everything.
                                histMonth = History.monthOf(history.first().at)
                                tab = Tab.HISTORY
                            },
                            onFile = file,
                            onOpen = { t -> openTxn(t, contacts.all()) },
                        )

                        // The log is ours, not theirs: it only exists while "check" is open.
                        if (checking) {
                            Spacer(Modifier.height(Space.section))
                            SectionLabel("EXCHANGE")
                            LogView(log)
                        }

                        // Room for the pay bar, which floats over this page rather than in it.
                        if (tab == Tab.TAP && thread == null && !cards) Spacer(Modifier.height(92.dp))
                    }

                    if (payAnyone) {
                        PayAnyoneSheet(
                            onClose = { payAnyone = false },
                            onFound = { address, label ->
                                payAnyone = false
                                // A name with a dot cannot ride inside the card (the sheet
                                // refuses it, and used to close without a word, so paying any
                                // .skr or .sol name from here did nothing). It goes beside the
                                // card and the sheet checks it against the chain itself.
                                startActivity(
                                    if (Username.looksLikeDomain(label)) TapPopupActivity.named(this@MainActivity, address, label)
                                    else TapPopupActivity.intent(
                                        this@MainActivity,
                                        TapPayload.Card(TapCard(address, label, null, null)),
                                        cameFrom = "name",
                                    )
                                )
                            },
                        )
                    }

                    if (home) {
                        PayBar(
                            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                            onTap = { openTapSheet() },
                            onScan = { startActivity(ScanActivity.intent(this@MainActivity)) },
                        )
                    }
                    }
                    }
                }

                if (drawerOpen) {
                    ProfileDrawer(
                        name = myDomain ?: myName,
                        address = connected,
                        walletName = activeWallet?.name,
                        isSeeker = myGenesis != null,
                        network = Chain.NETWORK,
                        version = BuildConfig.VERSION_NAME,
                        email = myEmail,
                        onRename = { drawerOpen = false; renaming = true },
                        onEmail = { drawerOpen = false; editingEmail = true },
                        onWallets = {
                            drawerOpen = false
                            // The wallets LIST, not one wallet's sheet. §3.3 makes this row the
                            // only way to change wallet, and `WalletSheet` cannot change one —
                            // it links and unlinks a single wallet. The Change wallet button
                            // that used to do this went with the old panel, so pointing here at
                            // the detail sheet would have left no route to switching at all.
                            cards = true
                        },
                        onShare = { drawerOpen = false; shareText(it) },
                        onGo = { drawerOpen = false; if (it == Tab.REWARDS) openRewards() else tab = it },
                        onRefer = { drawerOpen = false; openRefer() },
                        onDiagnostics = { drawerOpen = false; checking = true },
                        onLogout = { drawerOpen = false; logout() },
                        onClose = { drawerOpen = false },
                    )
                }

                offerDetail?.let { o ->
                    OfferSheet(
                        offer = o,
                        isSeeker = myGenesis != null,
                        claiming = offerClaiming,
                        problem = offerProblem,
                        onClaim = {
                            if (offerClaiming) return@OfferSheet
                            val me = connected ?: return@OfferSheet
                            offerClaiming = true
                            offerProblem = null
                            lifecycleScope.launch {
                                val why = Rewards.claim(o.id, me)
                                offerClaiming = false
                                if (why == null) {
                                    offersReload++
                                    // The sheet stays open and becomes the claimed offer, so the
                                    // person goes straight from claiming to doing the task. Closing
                                    // it sent them back to a list to find the offer they had just
                                    // picked. First what the phone already knows (claimed, now),
                                    // so the step ticks at once; then the server's own row, which
                                    // carries the real claim time, the button and the progress.
                                    // Only while this offer is still the one on screen: a slow
                                    // answer must not reopen a sheet that was closed meanwhile.
                                    if (offerDetail?.id == o.id) {
                                        offerDetail = o.copy(state = "open", claimedAt = System.currentTimeMillis())
                                    }
                                    val fresh = Rewards.offers(me)?.firstOrNull { it.id == o.id }
                                    if (fresh != null && offerDetail?.id == o.id) offerDetail = fresh
                                } else {
                                    offerProblem = Rewards.offerWhy(why)
                                    // A refusal often means the list is out of date (ended, fully claimed).
                                    offersReload++
                                }
                            }
                        },
                        onClose = { offerDetail = null; offerProblem = null },
                        onGo = { goFromOffer(it) },
                    )
                }

                // A reward card lifted out of the grid to be scratched, over a dimmed, blurred page.
                scratching?.let { tgt ->
                    ScratchStage(
                        target = tgt, t = scratchT,
                        onClose = { scratching = null },
                        onRevealed = { Live.bump() },
                        // The win is shown only once the server has taken the scratch, so nobody
                        // is shown a prize that was not paid. A practice card (an id below one)
                        // is never sent, and always "wins".
                        onReveal = { row, done ->
                            lifecycleScope.launch {
                                val me = connected
                                val why = when {
                                    row.id <= 0 -> null
                                    me == null -> Rewards.REVEAL_OFFLINE
                                    else -> Rewards.revealWhy(me, row.id)
                                }
                                Live.bump()
                                done(why)
                            }
                        },
                    )
                }

                // The authorization sheet, and what happens when it succeeds: the screen the person
                // asked for opens. Keyed on the session so it fires the moment it arrives.
                authGate?.let { gate ->
                    AuthorizeSheet(
                        what = gate.what,
                        wallet = activeWallet,
                        onClose = { authGate = null },
                    )
                }
                LaunchedEffect(Session.version, authGate) {
                    val gate = authGate
                    if (gate != null && Session.valid(connected)) {
                        authGate = null
                        gate.then()
                    }
                }

                // At the root, outside the page's gutter, like every other sheet here.
                if (refer) {
                    ReferSheet(
                        onClose = { refer = false },
                        // Only the Activity knows the resolved .sol/.skr name; the sheet falls
                        // back to the stored label when this is null.
                        myName = myDomain ?: Identity.label(this@MainActivity),
                    )
                }

                // `HowToReferSheet` was hosted here. §2.1 removed the "How to refer" link that
                // opened it and §2.3 replaced it with a second sheet stacked inside `ReferSheet`,
                // so nothing could set it any more — it went with the `Tab.REFERRALS` branch.

                walletNotice?.let { why ->
                    AlertDialog(
                        onDismissRequest = { walletNotice = null },
                        containerColor = D.White,
                        title = { Text("Wallet not linked", color = D.Black, style = Type.title) },
                        text = { Text(why, color = D.Black, style = Type.body) },
                        confirmButton = {
                            TextButton(onClick = { walletNotice = null }) { Text("OK", color = D.BlueInk, style = Type.heading) }
                        },
                    )
                }

                // A purchase lives in a sheet of its own: review, progress, and how it ended.
                purchase?.let { state ->
                    PurchaseSheet(
                        state = state,
                        onPay = { payWith, quoted -> with(state.item) { buy(product, value, category, phone, extra, payWith, quoted) } },
                        quote = { payWith -> quote(state.item, payWith) },
                        sponsor = { ServerClient.sponsor() },
                        // What the wallet holds, in base units. Null while it is not known, which
                        // is never treated as "nothing": an unknown balance blocks no one.
                        have = { payWith ->
                            when (payWith) {
                                Shop.PayWith.SOL -> (balance as? Chain.Balance.Ok)?.let { (it.sol * 1_000_000_000.0).toLong() }
                                Shop.PayWith.USDC -> if (balance !is Chain.Balance.Ok || holdings.isEmpty()) null
                                    else holdings.firstOrNull { it.mint == Shop.USDC_MAINNET }?.units ?: 0L
                            }
                        },
                        onRetry = { purchase = Purchase.Review(state.item) },
                        onClose = { purchase = null },
                        onExplorer = { sig -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Chain.explorerUrl(sig)))) } },
                    )
                }

                // Adding a wallet is a sheet over the Wallets screen, not a page that replaces it.
                picker?.let { apps ->
                    Tray(onClose = { picker = null }) {
                        Spacer(Modifier.height(Space.lead))
                        AddWalletFlow(apps, onPick = { app -> picker = null; linkWallet(app) })
                    }
                }

                vouchWallet?.let { w0 ->
                    // Read fresh from the list: a rename or a relink while the sheet is open.
                    val w = walletList.firstOrNull { it.address == w0.address } ?: w0
                    val standing = remember(w.address, vouchTick, walletList) {
                        standingOf(this@MainActivity, w, walletList)
                    }
                    VouchSheet(
                        w = w, standing = standing, linking = linking,
                        onVouch = { owner, target -> vouchFrom(owner, target) { applyMode(amount) } },
                        onForget = { target -> forgetVouch(target) { applyMode(amount) } },
                        onClose = { vouchWallet = null },
                    )
                }

                renamingWallet?.let { w ->
                    WalletNameSheet(
                        w, onClose = { renamingWallet = null },
                        onSave = { name ->
                            wallet.linked.setAlias(w, name)
                            // The list only: refreshWallets() also blanks the balance and tokens,
                            // which a rename has no reason to disturb.
                            walletList = wallet.linked.all(); activeWalletId = wallet.linked.selected()?.id
                            renamingWallet = null
                            push(if (name == null) "- ${w.short} is back to its own name" else "- renamed to $name")
                        },
                    )
                }

                receiving?.let { w ->
                    Tray(onClose = { receiving = null }) {
                        Spacer(Modifier.height(Space.lead))
                        Text("Receive", color = D.Black, style = Type.title)
                        Spacer(Modifier.height(Space.tight))
                        Text("Anyone can scan this to pay ${w.username ?: w.name}.", color = D.Quiet, style = Type.caption)
                        Spacer(Modifier.height(Space.lead))
                        QrCode("solana:${w.address}", Modifier.size(240.dp).align(Alignment.CenterHorizontally))
                        Spacer(Modifier.height(Space.lead))
                        Text(w.address, color = D.Quiet, style = Type.mono, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(Space.lead))
                    }
                }

                walletDetails?.let { w ->
                    WalletChecksSheet(
                        w = w,
                        linking = linking,
                        onLink = { vouchFor(w) { applyMode(amount) } },
                        onClose = { walletDetails = null },
                        onRemove = {
                            walletDetails = null
                            wallet.linked.remove(w)
                            afterWalletRemoved()
                            // The selected one may have just gone; refreshWallets re-reads
                            // which wallet is current and applyMode re-arms with it.
                            connected = wallet.address
                            refreshWallets()
                            applyMode(amount)
                            push("- removed ${w.name} ${w.short}")
                        },
                    )
                }

                showCodeFor?.let { id ->
                    bought.firstOrNull { it.invoiceId == id }?.let { o ->
                        CodeSheet(o, currency = o.currency, logoUrl = o.logoUrl) {
                            showCodeFor = null
                        }
                    }
                }

                if (editingEmail) {
                    EmailDialog(
                        current = myEmail.orEmpty(),
                        onDismiss = { editingEmail = false },
                        onSave = { e ->
                            Identity.setEmail(this@MainActivity, e)
                            myEmail = Identity.email(this@MainActivity)
                            editingEmail = false
                        },
                    )
                }

                if (renaming) {
                    NameDialog(
                        current = myName,
                        domain = myDomain,
                        onDismiss = { renaming = false },
                        onSave = { name ->
                            Identity.setLabel(this@MainActivity, name)
                            myName = Identity.label(this@MainActivity)
                            renaming = false
                            // The name you appear as is also your .air name, so people can pay you by
                            // typing it. Quietly: a name somebody else holds just stays a display name.
                            lifecycleScope.launch {
                                name.lowercase().filter { it.isLetterOrDigit() || it == '_' }.takeIf { it.length >= 3 }
                                    ?.let { runCatching { ServerClient.claimAirName(it) } }
                            }
                            // The armed request carries the old name until it is rebuilt.
                            TapHostApduService.pending.value = null
                            applyMode(amount)
                            push("- you now appear as $myName")
                        },
                    )
                }

                incoming?.let { req ->
                    ConfirmSheet(
                        req = req,
                        busy = paying != null,
                        onPay = {
                            val payer = connected
                            incoming = null
                            if (payer == null) {
                                status = Status.Problem("Connect a wallet first", "It's saved in Requests. Pay it after connecting.")
                            } else {
                                // lifecycleScope, not the composition's scope: paying suspends
                                // inside the wallet for as long as the user takes, and a
                                // composition disposed behind that dialog would cancel the
                                // payment between signing and submitting. `finally`, because
                                // cancellation skips the line after a suspend point.
                                lifecycleScope.launch {
                                    paying = req.reference
                                    try {
                                        pay(payer, req)
                                    } finally {
                                        paying = null
                                    }
                                }
                            }
                        },
                        onLater = {
                            incoming = null
                            push("- saved ${req.amount} ${req.symbol} for ${req.label} in Requests")
                        },
                        onDecline = {
                            incoming = null
                            inbox.remove(req.reference); refreshLists()
                            push("- declined ${req.amount} ${req.symbol} from ${req.label}")
                            lifecycleScope.launch {
                                ServerClient.reject(req.reference, Identity.label(this@MainActivity))
                            }
                        },
                    )
                }
            }
        }
    }

    /** A request just arrived over the radio. */
    private fun onRead(req: PayRequest) {
        // Reader mode fires repeatedly while the phones stay in contact.
        if (incoming?.reference == req.reference) return
        Haptics.contact(this)

        val known = inbox.get(req.reference)
        if (known?.withdrawn == true) {
            status = Status.Problem("Request withdrawn", "${req.label} took this request back.")
            return
        }
        if (known?.paid == true) {
            status = Status.Problem("Already paid", "You paid this request ${ago(known.paidAt ?: 0)}.")
            return
        }
        // Saved first, before anything can go wrong: from here the request survives a
        // declined sheet, a closed app, or a phone switched off.
        if (known == null) {
            inbox.add(req)
            refreshLists()
            // Kept only if the signature actually verifies — req.link() returns null otherwise —
            // and re-verified every time it is read back, so a stored link is never taken on
            // trust just because we were the ones who wrote it.
            req.link()?.let { Vouch.remember(this, it.owner, it.wallet, it.expiresDay, it.signature) }
            val isNew = contacts.record(req.recipient, req.label, false, brand = req.brand)
            contactCount = contacts.count()
            push(if (isNew) "+ new contact ${req.label}" else "- known contact ${req.label}")
            // The payer registers too, so the server watches this request even if the payee's
            // phone happened to be offline when it served it.
            // With our own address as payer: a request received by tapping was registered by
            // the phone ASKING, which cannot know who will pay, so the server had nobody to
            // tell when it was withdrawn.
            lifecycleScope.launch {
                ServerClient.register(req.reference, req.recipient, req.amount, req.label, payer = connected,
                    token = req.token, decimals = req.decimals, symbol = req.symbol, origin = "tap",
                    deliver = false)
            }
        }

        // Never swap the request under a sheet someone is reading — they might approve a
        // different one than they read. The new one waits in Requests.
        if (incoming != null) {
            push("- another request from ${req.label} saved in Requests")
            return
        }
        incoming = req
    }

    /**
     * Sends a line of text to someone we have tapped.
     *
     * Written locally first and unconditionally: the thread has to show what was typed the
     * instant it is typed. If the server refuses it — no connection, or the hourly ceiling —
     * the message is still there and the exchange log says why, rather than the line silently
     * never appearing. Nothing here moves money, so a failed send costs a repeat, not a payment.
     */
    private fun say(to: String, body: String) {
        val from = connected ?: run {
            status = Status.Problem("Connect a wallet first", "A message is sent from your address.")
            return
        }
        messages.add(Messages.Note(to, body, System.currentTimeMillis(), mine = true))
        refreshLists()
        lifecycleScope.launch {
            val sent = ServerClient.sendMessage(from, to, body, Identity.label(this@MainActivity))
            if (!sent) {
                push("✗ message not delivered, it stays here, send it again")
                status = Status.Problem("Message not delivered",
                    "It's still in the thread. Check your connection and send it again.")
            }
        }
    }

    /** Our request was just handed to a payer. */
    private fun onServed(req: PayRequest) {
        watch.add(
            Watch.Entry(
                reference = req.reference,
                recipient = req.recipient,
                // Whoever just read it. They introduced themselves with HELLO moments before
                // taking the request, which is the only point in a tap where we learn who the
                // other phone belongs to.
                peer = TapHostApduService.lastHello?.address,
                amount = req.amount,
                createdAt = System.currentTimeMillis(),
                token = req.token,
                decimals = req.decimals,
                symbol = req.symbol,
            )
        )
        refreshLists()
        checkAllNow = true
        push("watching chain for ${req.reference.take(8)}… (${owed.count { !it.paid }} open)")
        lifecycleScope.launch {
            ServerClient.register(req.reference, req.recipient, req.amount, req.label,
                token = req.token, decimals = req.decimals, symbol = req.symbol, origin = "tap")
        }
    }

    /** One look at the chain for one open request. The only path to ✓ Paid. */
    private suspend fun checkOwed(e: Watch.Entry) {
        val units = e.units() ?: 1L
        when (val found = withContext(Dispatchers.IO) { Chain.findPayment(e.reference, e.recipient, units, e.mint) }) {
            is Chain.Found.Yes -> {
                offlineStreak = 0
                settle(e, found.signature)
            }
            is Chain.Found.Short -> {
                offlineStreak = 0
                // Shown once per signature — it will be found again on every poll.
                val line = "✗ ${found.signature.take(8)}… used this request but ${found.why}"
                if (log.lastOrNull() != line && line !in log) {
                    push(line)
                    status = Status.Problem("Payment didn't cover the request", "Still waiting, ${found.why}.")
                }
            }
            is Chain.Found.Broken -> status = Status.Problem(
                "Can't check for payment", "Solana rejected our query: ${found.why}",
            )
            Chain.Found.Offline -> if (++offlineStreak == 3) status = Status.Problem(
                "Can't reach Solana", "Still waiting, we'll check again when you're back online.",
            )
            Chain.Found.NotYet -> offlineStreak = 0
        }
    }

    /**
     * Has a request we owe already been paid?
     *
     * The chain is the only thing that can answer this, and it is asked because the moment of
     * paying cannot be trusted to record it. [Payments.pay] returns `Sent` only when the
     * confirmation comes back Confirmed; a payment that settles but confirms slowly — a
     * rate-limited public node is enough — comes back `Failed("Sent, still confirming")`, and
     * nothing marked the row. It then sat in the thread offering Pay and Decline for money
     * that had already left, which is the single worst thing this screen can say.
     *
     * Cheap to be wrong in the safe direction: this only ever turns a row from unpaid to paid,
     * and only on a signature the chain shows reaching the right address for the right amount.
     */
    private suspend fun checkOwing(item: Inbox.Item) {
        val req = item.request
        val units = req.units() ?: return
        val found = withContext(Dispatchers.IO) {
            Chain.findPayment(req.reference, req.recipient, units, req.mint)
        }
        if (found !is Chain.Found.Yes) return
        inbox.markPaid(req.reference, found.signature)
        refreshLists()
        push("✓ ${req.amount} ${req.symbol} to ${req.label} was already paid, ${found.signature.take(10)}…")
    }

    /** One payment arrived, in full. */
    private fun settle(e: Watch.Entry, signature: String) {
        if (!watch.markPaid(e.reference, signature)) {
            // Found again on every poll, so said once.
            val line = "✗ ${signature.take(8)}… was already counted for another request"
            if (line !in log) push(line)
            return
        }
        refreshLists()
        paidSignature = signature
        if (status is Status.Problem) status = Status.Idle
        push("✓ PAID ${e.amount} ${e.symbol}, $signature")
        Haptics.success(this)
    }

    /**
     * The payer's half, from the Requests tab or a tap sheet. The work is in [Payments], shared
     * with the tap popup so the two can never report the same payment differently.
     */
    private suspend fun pay(payer: String, req: PayRequest) {
        val result = Payments.pay(wallet, payer, req, stage = { status = Status.Working(it) }, log = ::push)
        when (result) {
            is PayResult.Sent -> {
                inbox.markPaid(req.reference, result.signature); refreshLists()
                status = Status.Sent(result.signature)
                Haptics.success(this)
                push("✓ CONFIRMED, ${Chain.explorerUrl(result.signature)}")
                contacts.record(req.recipient, req.label, false, paid = req.amount.toDoubleOrNull() ?: 0.0, brand = req.brand)
                contactCount = contacts.count()
                // Nudge the server to look now rather than on its next sweep.
                lifecycleScope.launch {
            ServerClient.register(req.reference, req.recipient, req.amount, req.label,
                token = req.token, decimals = req.decimals, symbol = req.symbol)
        }
            }
            is PayResult.AlreadyPaid -> {
                inbox.markPaid(req.reference, result.signature); refreshLists()
                status = Status.Problem("Already paid", "This request was paid before, nothing new was sent.")
            }
            is PayResult.Error -> {
                status = Status.Problem(result.title, "${result.detail} It's still in Requests.")
                Haptics.failure(this)
            }
            is PayResult.Failed -> {
                status = Status.Problem(result.title, "${result.detail} It's still in Requests.")
                Haptics.failure(this)
            }
            // Not reachable from here: this screen never asks to hand a payment over, because
            // it has no reader to carry one back with. Said honestly all the same.
            is PayResult.SignedOffline -> {
                status = Status.Problem("You're offline", "Nothing was sent. It's still in Requests.")
                Haptics.failure(this)
            }
        }
    }

    private fun refreshLists() {
        toPay.clear(); toPay.addAll(inbox.all())
        owed.clear(); owed.addAll(watch.all())
        notes.clear(); notes.addAll(messages.all())
        bought.clear(); bought.addAll(orders.all())
    }

    private fun applyMode(amount: String = lastAmount) {
        lastAmount = amount
        // Ready to pay whenever a wallet is connected and this phone is not requesting. Making
        // someone press "Pay" before a tap was a step with no information in it.
        if (mode != Mode.REQUEST && connected != null) reader.start() else reader.stop()
        val addr = connected
        if (mode != Mode.REQUEST || addr == null) {
            TapHostApduService.pending.value = null
            return
        }
        val live = TapHostApduService.pending.value
        val link = Vouch.of(this, addr)
        // Keep the same reference across a pause/resume. This runs again on every onResume,
        // and minting a fresh reference each time meant a notification — or the screen simply
        // sleeping — silently swapped the key under a request already on screen.
        if (live != null &&
            live.recipient == addr && live.amount == amount && live.genesis == myGenesis &&
            live.owner == link?.owner
        ) return

        TapHostApduService.pending.value = PayRequest(
            recipient = addr,
            amount = amount,
            token = "SOL",
            label = Identity.label(this),
            reference = PayRequest.newReference(),
            genesis = myGenesis,
            brand = Brand.mine(),
            // If this wallet borrowed its name from another, the proof rides along, so the
            // payer's confirm sheet can say who they are paying instead of showing hex.
            owner = link?.owner,
            linkExpiry = link?.expiresDay ?: 0,
            linkSignature = link?.signature,
        )
    }

    /** Bumped on resume so composables that cache device state can re-read it. */
    private var resumeTick by mutableIntStateOf(0)

    /** A screen that needs a session, waiting for one: what it is for, and what to do once signed in. */
    private class AuthGate(val what: String, val then: () -> Unit)
    private var authGate by mutableStateOf<AuthGate?>(null)

    /**
     * Runs [go] now when signed in; otherwise opens the authorization sheet and runs it the moment
     * the session arrives. Rewards and Referrals are reached through here, so nobody lands on a
     * screen that can only tell them it cannot read anything.
     */
    private fun gated(what: String, go: () -> Unit) {
        if (!ServerClient.enabled || connected == null || Session.valid(connected)) go()
        else authGate = AuthGate(what, go)
    }
    private fun openRewards() = gated("see your rewards") { tab = Tab.REWARDS }
    private fun openRefer() = gated("see your referral code") { refer = true }

    /**
     * Opens one shelf of the shop, and a product on top of it when a tile is a single brand.
     * One function for the Home tiles, "See all" and an offer's button, because the shop body is
     * `shopSection?.let { … }`: a route that set the tab without the shelf landed on a blank page.
     */
    private fun openShelf(sec: Shop.Section, product: Shop.Product? = null) {
        shopSection = sec
        shopProduct = product
        shopDirect = product != null
        tab = Tab.SHOP
    }

    /** The tap and pay sheet, armed. What the pay bar's Tap opens. */
    private fun openTapSheet() {
        // The sheet does the waiting now. Arming the radio from here
        // left the home screen looking exactly as it had a second
        // earlier — nothing said the phone was listening, or that the
        // next move was physical.
        paidSignature = null
        // The sheet blurs what it covers, and has to be handed the picture.
        Backdrop.capture(this) {
            startActivity(TapPopupActivity.armed(this))
            // The sheet animates itself. A window slide under it made
            // the blurred page look as if it rose from the bottom.
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        }
    }

    /**
     * Where a claimed offer's button leads when the task is done inside AirPay. Each target is
     * the same call its own door on Home makes, so the two cannot drift.
     *
     * The sheet closes first, except when the shop has not loaded: then there is no page to
     * open, and closing onto the Offers list with nothing having happened would look like a
     * button that does nothing. The sheet stays and says why.
     */
    private fun goFromOffer(target: String) {
        val shelf = when (target) {
            "recharge" -> shopSections.firstOrNull { it.isRechargeShelf() }
            "shop" -> moreSection(shopSections)
            else -> null
        }
        if ((target == "recharge" || target == "shop") && shelf == null) {
            offerProblem = "That page hasn't loaded yet. Check your connection and try again."
            return
        }
        offerDetail = null
        offerProblem = null
        when (target) {
            "recharge", "shop" -> shelf?.let { openShelf(it) }
            "tap" -> openTapSheet()
            "pay" -> payAnyone = true
        }
    }

    private var signInRunning = false
    init {
        // A screen's Authorize button: ask now, without waiting out the pause after a failed try.
        Session.authorize = { connected?.let { signInFailedAt = 0L; requestSignIn(it) } }
        // Which wallet each request is about: whichever is active.
        Session.acting = { connected }
    }
    private var signInFailedAt = 0L
    private var signInFor: String? = null

    /**
     * Asks for a session if there is none: one attempt at a time, and not again for a while after
     * one failed or was declined.
     *
     * Runs in [lifecycleScope], not in a Compose effect, so a recomposition or a resume cannot
     * cancel it half way. Waits until the app is in front again (and a beat after) before opening
     * the wallet: the wallet's connect sheet may still be closing, and a prompt launched into it
     * is torn down at once. Never during a payment — the sign sheet would land on top of it.
     */
    private fun requestSignIn(addr: String) {
        if (signInRunning || busy || !ServerClient.enabled || Session.valid(addr)) return
        if (signInFor != addr) { signInFor = addr; signInFailedAt = 0L }
        if (System.currentTimeMillis() - signInFailedAt < SIGN_IN_RETRY_MS) return
        signInRunning = true
        Session.signingIn = true
        Session.failed = false
        lifecycleScope.launch {
            try {
                while (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) delay(100)
                delay(500)
                if (connected != addr || Session.valid(addr)) return@launch
                // Signed in for ANOTHER wallet of the same person: this one joins that account with
                // one proof from itself, and from then on switching asks for nothing. Only with no
                // session at all is it a plain sign-in.
                val ok = if (Session.hasToken) {
                    when (Session.link(this@MainActivity, wallet, addr)) {
                        Session.Link.OK -> true
                        // Already another account's (another phone, an earlier sign-up): it cannot be
                        // taken over, so it signs in as itself.
                        Session.Link.IN_USE -> Session.signIn(this@MainActivity, wallet, addr)
                        Session.Link.FAILED -> false
                    }
                } else Session.signIn(this@MainActivity, wallet, addr)
                if (ok) {
                    signInFailedAt = 0L
                    push("signed in as ${addr.take(6)}…")
                } else {
                    signInFailedAt = System.currentTimeMillis()
                    Session.failed = true
                    push("- sign-in did not complete; will try again shortly")
                }
            } catch (e: Exception) {
                signInFailedAt = System.currentTimeMillis()
                Session.failed = true
            } finally {
                signInRunning = false
                Session.signingIn = false
            }
        }
    }


    /**
     * What has to follow a wallet being removed, whichever screen removed it. The badge and the
     * server session belonged to the wallet that left: kept, the next wallet would wear its
     * "Verified Seeker" and be signed into its account. With none left, the Wallets screen has
     * nothing to show and the first screen takes over.
     */
    private fun afterWalletRemoved() {
        myGenesis = null
        if (wallet.address == null) {
            cards = false
            Session.clear(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // The tap sheet is another activity with its own wallet, and can switch which one is in use.
        wallet.address.let { if (it != connected) { connected = it; myGenesis = null; refreshWallets() } }
        resumeTick++
        // Back from the wallet, or back after hours away: anything could have been paid.
        checkAllNow = true
        refreshLists()
        notifOff = !Push.canNotify(this)
        // The popup is another activity and records contacts there; Home reads the count here.
        contactCount = contacts.count()
        // And anything bought could have been delivered while the app was gone.
        catchUpOrders()
        applyMode()
    }

    override fun onPause() {
        super.onPause()
        reader.stop()
        TapHostApduService.pending.value = null
    }

    private fun push(line: String) {
        android.util.Log.i("TapUI", line)
        log.add(line)
        if (log.size > 200) log.removeAt(0)
    }

    /**
     * Last resort. Everything above is written not to throw, but a crash on camera during a
     * three-minute demo is unrecoverable, and a background thread dying takes the app with it.
     */
    private fun installCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            android.util.Log.e("AirPay", "uncaught on ${thread.name}", error)
            if (thread == mainLooper.thread) {
                previous?.uncaughtException(thread, error)
            } else {
                runOnUiThread {
                    // Leads with what happened to the money, not with the machinery
                    // (ui/PORT.md §7) — and "Nothing was sent" is one of the seven state
                    // wordings, so a crash reads the same as every other no-money-moved end.
                    status = Status.Problem(
                        "Nothing was sent",
                        "That step stopped before anything left your wallet. Try again from Requests.",
                    )
                }
            }
        }
    }

    private enum class Mode { IDLE, REQUEST, PAY }

    private companion object {
        /**
         * Pause between the phones separating and offering a request to the next person —
         * long enough that a wobble in two phones still resting together is not read as a
         * second tap, short enough that the next person in line never notices it.
         */
        const val REARM_AFTER_PART_MS = 1_200L
        /** After a sign-in that failed or was declined, how long before the app asks the wallet again by itself. */
        const val SIGN_IN_RETRY_MS = 45_000L
        /** Seeker Wallet's package: present only on a Seeker, where it is the system default wallet. */
        const val SEEKER_WALLET = "com.solanamobile.wallet"
    }
}

/** Whether Home's balance is hidden. Hidden at launch; a choice to show it lasts until the app is closed. */
private val BalanceHidden = mutableStateOf(true)

enum class Tab { TAP, REQUESTS, REWARDS, REFERRALS, OFFERS, SHOP, ALERTS, HISTORY }

fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        s < 45 -> "just now"
        s < 3600 -> "${(s + 30) / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        else -> "${s / 86_400}d ago"
    }
}



/**
 * One shape for every piece of news the app has to deliver.
 *
 * No tinted well behind it (ui/PORT.md §1 rule 3). The headline in its tone's INK is what
 * marks it; a wash is the one thing the palette forbids. The tone arrives as a FILL, so every
 * word here takes `.ink` — a fill is never text, and "OK" used to be drawn in the raw fill.
 */
@Composable
private fun Banner(
    title: String,
    detail: String,
    tone: Color,
    mono: Boolean = false,
    onDismiss: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = tone.ink, style = Type.body)
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(Space.tight))
                // A signature is read character by character; anything else is a sentence.
                Text(detail, color = D.Quiet, style = if (mono) Type.mono else Type.caption)
            }
        }
        if (onDismiss != null) {
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onDismiss)
                    .heightIn(min = 48.dp).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("OK", color = tone.ink, style = Type.body) }
        }
    }
}

/**
 * The wallet you are spending from, what it is worth, and the other linked wallets behind it.
 *
 * Start side: the wallet's logo and whatever identifies the ACCOUNT, the uppercase "total
 * balance" label, and the figure. The Send and Request pills this comment used to promise are
 * gone (see the note where they stood), and so is the grey chip that boxed the address.
 *
 * End side: the interactive card pile, brand logos showing on each card's lip, floating over
 * the space the balance is not using.
 */
/**
 * How far the currency sign is lifted, as a fraction of its own size.
 *
 * The sign is CENTRED on the figures, not sat on their baseline. From the fonts: the centre of
 * a Geist 800 digit is 0.355em above the baseline, the centre of Bricolage's "$" is 0.329em of
 * its own size, and the sign is half the balance — so lifting it 0.381em of ITS size puts the
 * two centres level. In em, so it holds when the balance shrinks to fit or the text scale grows.
 *
 * This only works because all three runs share a baseline (`alignByBaseline`), which in turn
 * only works because [FlowFigure] now reports one. Bottom-aligned instead, the same shift left
 * the sign 32px low — measured off a screenshot, because this is not a difference the eye can
 * judge reliably: an earlier reading of these same two builds got it backwards.
 */
private const val SIGN_LIFT = 0.381f

@Composable
internal fun BalanceCard(
    /** Everything held, priced and summed. Null when nothing could be priced. */
    totalUsd: Double?,
    wallet: Linked?,
    others: List<Linked>,
    balance: Chain.Balance?,
    mode: Boolean,
    onSend: () -> Unit,
    onRequest: () -> Unit,
    onCards: () -> Unit,
    onSelectWallet: (Linked) -> Unit = {},
    onDetails: () -> Unit = {},
    /**
     * True on the landing page, where this is a picture of Home and not Home: the figure is
     * shown, since it is an example and nobody's to hide, and no price is asked for.
     */
    sample: Boolean = false,
) {
    val context = LocalContext.current
    val stack = others.take(3)
    // Geometry settled in the sandbox — ui/src/components/WalletSection.js. One number decides
    // the pile: `step` is the offset between cards AND the width of every visible sliver.
    val step = 35.dp
    val cardWidth = 72.dp
    val cardHeight = 140.dp

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left Column: Active wallet header + Balance + Action Pills
        Column(
            Modifier.weight(1f),
        ) {
            // Top: Active wallet name and address badge
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    // The logo still goes to the wallets screen — that is where you SWITCH.
                    // The address beside it opens this wallet's own details, which is a
                    // different question and used to have nowhere to be asked.
                    .clickable(onClick = onDetails)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // No no-wallet branch. Onboarding makes you pick a wallet before Home can
                // open, so this screen has no such state to draw (ui/PORT-2026-10-01.md §1.4).
                // Tap, Rewards, Referrals, Offers and the drawer keep theirs — they can be
                // reached in other ways.
                if (wallet != null) {
                    // No coloured disc behind it: the icon is its own tile with its own
                    // background, so a circle of the same colour underneath only showed as a
                    // ring of it poking out from behind the corners.
                    WalletLogo(wallet, 22.dp, 7.dp)
                    Spacer(Modifier.width(Space.inline))
                    // The icon already says which wallet this is, so the name beside it was
                    // saying it twice. What goes here instead is whatever identifies the
                    // ACCOUNT: the owner's name when the wallet reports one — Seeker Wallet
                    // gives its SNS domain — and the address when it does not.
                    //
                    // The grey chip behind it is gone (no containers). A username is caption
                    // text; a bare address is caption MONO, because it is read character by
                    // character and 0/O have to stay apart.
                    Text(
                        wallet.username ?: wallet.short,
                        color = if (wallet.username != null) D.Black else D.Quiet,
                        style = if (wallet.username == null) Type.mono else Type.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(Space.inline))

            // The scale's step for an uppercase label. Uppercased in code rather than in the
            // string: it is a typographic treatment, not the words.
            Text("Total balance".uppercase(), color = D.Quiet, style = Type.micro)
            Spacer(Modifier.height(Space.tight))
            // In dollars, because "total balance" is a question about money and SOL is not an
            // answer to it for most people.
            //
            // EVERYTHING priced, not just SOL. It used to be the SOL figure alone, which meant
            // a wallet holding 456 SKR reported only the nine dollars of SOL beside it and
            // called that the total. Any amount actually paid is still counted in SOL or in
            // the token itself; this only decides a label.
            //
            // When nothing can be priced the SOL figure is shown instead, rather than a dollar
            // sign in front of a number nobody checked.
            val sol = (balance as? Chain.Balance.Ok)?.sol
            val rate by produceState<Double?>(initialValue = null) { if (!sample) value = Prices.solUsd() }
            // Three runs now, not one, because each rolls on its own: the sign holds still
            // while the whole number and the decimals animate their own digits.
            //
            // A single Text put them on a shared baseline for free, and that is given up here
            // — the row aligns to Bottom and the sign and cents are lifted by hand instead.
            // Worth it: DUFFLE_DESIGN.md asks for the amount to roll, and a figure that swaps
            // whole gives no sense that one balance BECAME another.
            val dollars = totalUsd ?: sol?.let { s0 -> rate?.let { s0 * it } }
            // Money decides every part of this, symbol and side included. It used to be
            // `Prices.money(dollars)` split on a literal '.', which broke twice: a dust balance
            // came back "<0.01" and split into "<0" and ".01" (so the phone read "$ <0 .01"),
            // and a locale whose decimal mark is ',' never split at all.
            val parts = dollars?.let { Money.fiatParts(java.math.BigDecimal(it.toString()), "USD") }
            // No price feed: SOL, at SOL's own precision. `"%.4f".format(sol)` was HALF_UP and
            // claimed 0.0001 SOL that was not there.
            val bare = if (parts == null && sol != null) Money.tokenAmount(java.math.BigDecimal(sol.toString()), "SOL") else null
            // The balance is ONE fixed composition, not three steps on the scale. The figure
            // is `Type.display`'s 60/800; the sign is half of it (30) and the cents are 26/60
            // of it, so if the balance ever shrinks to fit they shrink with it. Those two
            // sizes, and the bottom paddings that put the three runs on one optical baseline,
            // belong to the shape — they are neither scale steps nor spacing.
            //
            // The face is `Figure` (Geist), which is what `Type.display` is set in; the
            // currency symbol takes `CurrencyFace`, the Bricolage subset that answers for
            // `$ ₹ €`. Both were Bricolage-the-display-face, which is the wordmark's font.
            // Still waiting for the first answer (no balance, no total, not a failure): the figure's
            // own shape, not a dash. A failed lookup is `Offline`, not null, and keeps its "—".
            // Held outside the composition: leaving Home and coming back kept re-hiding a
            // balance the person had chosen to show. It still starts hidden on every launch.
            var hidden by if (sample) remember { mutableStateOf(false) } else BalanceHidden
            // The reveal animates only when it means something: the first time, and again after
            // the person has been away long enough to have forgotten it. Flipping the eye over and
            // over just changes the figure, so the motion keeps its value instead of becoming noise.
            val animCtx = LocalContext.current
            val reveal = remember { androidx.compose.animation.core.Animatable(1f) }
            var firstRun by remember { mutableStateOf(true) }
            LaunchedEffect(hidden) {
                if (firstRun) { firstRun = false; return@LaunchedEffect }
                val prefs = animCtx.getSharedPreferences("ui", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                if (now - prefs.getLong("balance_anim_at", 0L) >= BALANCE_ANIM_GAP_MS) {
                    prefs.edit().putLong("balance_anim_at", now).apply()
                    reveal.snapTo(0f)
                    reveal.animateTo(1f, androidx.compose.animation.core.tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                }
            }
            val fx = Modifier.graphicsLayer {
                val v = reveal.value
                alpha = v
                translationY = (1f - v) * 16.dp.toPx()
                scaleX = 0.94f + 0.06f * v; scaleY = 0.94f + 0.06f * v
            }
            if (balance == null && totalUsd == null) BalanceSkeleton() else Row(verticalAlignment = Alignment.Bottom) { // EXPERIMENT: alignByBaseline
                if (hidden) {
                    // Hidden until asked: a balance on a screen anybody can glance at is the first
                    // thing a stranger reads. The dots keep the figure's size so nothing jumps.
                    // The currency sign stays: only the amount is private.
                    if (parts != null && parts.symbolFirst) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(baselineShift = BaselineShift(SIGN_LIFT))) { append(parts.symbol) }
                            },
                            color = D.Quiet, fontSize = 30.sp,
                            fontWeight = FontWeight.Bold, fontFamily = CurrencyFace, style = Figures,
                            modifier = Modifier.alignByBaseline().then(fx).padding(end = 8.dp),
                        )
                    }
                    Text(
                        "••••••", color = D.Black, fontSize = 60.sp, fontWeight = FontWeight.ExtraBold,
                        fontFamily = Figure, modifier = Modifier.alignByBaseline().then(fx),
                    )
                } else {
                // The sign is CENTRED on the figures, not sat on their baseline. Measured
                // from the fonts: the centre of a Geist 800 digit is 0.355em above the
                // baseline, the centre of Bricolage's "$" is 0.329em of its own size, and the
                // sign is half the balance — so lifting it 0.381em of ITS size puts the two
                // centres level. In em, so it holds when the balance shrinks to fit or the
                // text scale grows. Gap to the digits is 8 (ui/PORT-2026-10-01.md §2.1).
                if (parts != null && parts.symbolFirst) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(baselineShift = BaselineShift(SIGN_LIFT))) {
                                append(parts.symbol)
                            }
                        },
                        color = D.Quiet, fontSize = 30.sp,
                        fontWeight = FontWeight.Bold, fontFamily = CurrencyFace, style = Figures,
                        modifier = Modifier.alignByBaseline().then(fx).padding(end = 8.dp),
                    )
                }
                FlowFigure(
                    text = parts?.whole ?: bare ?: "-",
                    fontSize = 60.sp, color = D.Black,
                    fontWeight = FontWeight.ExtraBold, fontFamily = Figure,
                    modifier = Modifier.alignByBaseline().then(fx),
                )
                parts?.fraction?.takeIf { it.isNotEmpty() }?.let { frac ->
                    FlowFigure(
                        text = frac,
                        fontSize = 26.sp, color = D.Quiet,
                        fontWeight = FontWeight.Bold, fontFamily = Figure,
                        modifier = Modifier.alignByBaseline().then(fx),
                    )
                }
                // "2,84 €" — the locale puts it after the figures, so the app does too.
                if (parts != null && !parts.symbolFirst) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(baselineShift = BaselineShift(SIGN_LIFT))) {
                                append(parts.symbol)
                            }
                        },
                        color = D.Quiet, fontSize = 30.sp,
                        fontWeight = FontWeight.Bold, fontFamily = CurrencyFace, style = Figures,
                        modifier = Modifier.alignByBaseline().then(fx).padding(start = 8.dp),
                    )
                }
                if (parts == null && sol != null) {
                    // A token code after the amount, at the nearest step rather than a fourth
                    // hand-picked size.
                    Text(
                        " SOL", color = D.Quiet, style = Type.body,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                }
                Spacer(Modifier.width(6.dp))
                // Centred on the figures' own middle, not the row's bottom: the digits sit above
                // the baseline by 0.355em, so the box is lined up to that, not to the row.
                val lift = with(LocalDensity.current) { (0.355f * 60.sp.toPx()) }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { hidden = !hidden }
                        .alignBy { it.measuredHeight / 2 + lift.toInt() },
                    contentAlignment = Alignment.Center,
                ) {
                    HiIcon(if (hidden) Hi.EyeOff else Hi.Eye, D.Quiet, 24.dp,
                        contentDescription = if (hidden) "Show balance" else "Hide balance")
                }
            }

            // Send and Request were here.
            //
            // Send did nothing the pay bar at the bottom does not do. Request is a different
            // matter and worth knowing about: `Mode.REQUEST` is the ONLY thing that arms
            // TapHostApduService.pending, so with no control for it this phone can no longer
            // be the payee in a tap from the home screen. The machinery is left intact —
            // `mode`, applyMode(), the amount field — so putting a way back in is one line
            // wherever it belongs. Asking is still reachable through a person: their thread's
            // "Pay or ask", and "Ask instead" on the tap sheet.

        }

        // The stack, which floats over this Row rather than sitting beside it — the balance
        // keeps the full width and the cards lie on top of the space it is not using.
        //
        // Drawn FRONT LAST: the rightmost card is the one on top, so each card leaves the lip
        // of the one before it showing on its left. Bottom-aligned, not centred, so the pile
        // has a straight edge along the bottom the way a fanned deck does.
        if (stack.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.CenterVertically)
                    // 20dp clears this column's gutter, another 20dp runs off the screen edge.
                    .offset(x = 40.dp)
                    .width(cardWidth + step * (stack.size - 1))
                    .height(cardHeight + 2.dp * stack.size),
            ) {
                for (i in stack.indices) {
                    val c = stack[i]
                    val behind = stack.size - 1 - i
                    val cardShape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp)
                    val cardInteraction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = step * i)
                            .width(cardWidth)
                            // A hair taller towards the front, so the top edges fan.
                            .height(cardHeight - 1.dp * behind)
                            .then(PressScale(cardInteraction))
                            .shadow(
                                elevation = 6.dp,
                                shape = cardShape,
                                spotColor = Color(0x26000000),
                                ambientColor = Color(0x14000000),
                            )
                            .clip(cardShape)
                            // `c.fill`, so the Seeker's card runs black to mint. Painted
                            // BEFORE the border and over the whole clipped shape, so the
                            // gradient reaches the outer edge — under the 1dp border rather
                            // than inside it, which is where a black hairline appeared.
                            .background(c.fill)
                            .border(1.dp, Color.White.copy(alpha = 0.20f), cardShape)
                            // Opens the wallets screen rather than switching on the spot.
                            // A tap that silently changed which wallet you are about to spend
                            // from — and reshuffled the pile while your finger was on it — is
                            // the one gesture here that should ask before it acts. Choosing
                            // happens there, against a list, and is confirmed with Done.
                            .clickable(cardInteraction, indication = null) {
                                Haptics.tick(context)
                                onCards()
                            }
                            .padding(start = 8.dp, top = 12.dp),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        // 28 at corner 8, up from 20/6. The card's padding is unchanged, so
                        // 8 + 28 still fits inside the 35dp sliver of each card that shows.
                        WalletLogo(c, 28.dp, 8.dp, card = true)
                    }
                }
            }
        }
    }
}

/**
 * Every linked wallet, one card under another.
 *
 * Simple on purpose: a colour, a name, an address and whether it is the one in use. A card that
 * pretends to be a bank card invites questions this does not answer — there is no balance on it,
 * no number to copy, and nothing to freeze.
 */
/**
 * Which wallet app to link, listed from what is actually installed.
 *
 * Android will not offer this choice on a Seeker — the ROM routes the whole `solana-wallet`
 * scheme to Seeker Wallet — so the app has to ask. Real labels and real icons, because a row
 * saying "app.phantom" is a worse chooser than the one we are replacing.
 */


/**
 * A wallet's mark.
 *
 * Two kinds live behind [Linked.iconRes] and they cannot be drawn the same way: a line vector
 * that must be tinted to be visible, and the wallet's real app icon, which is a tile with its
 * own background and its own colours. Tinting the second flattens it to a silhouette, so it is
 * drawn as an image and clipped to a rounded square — a small app icon, which is what it is.
 */
@Composable
private fun WalletLogo(w: Linked, size: Dp, radius: Dp = 6.dp, card: Boolean = false) {
    val tint = w.iconTint
    // On the pile of cards the mark is the wallet's card icon; everywhere else its regular one.
    val res = if (card) w.cardIconRes else w.iconRes
    if (tint == null) {
        Image(
            painterResource(res), contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(radius)),
            // Fit, not Crop. These icons are not all square — Backpack is 117x142, Seeker
            // 681x607 — and filling a square box with them zoomed in and cut the edges off
            // the artwork. Fit shows the whole thing.
            contentScale = ContentScale.Fit,
        )
    } else {
        HIcon(res, tint, size)
    }
}

/**
 * What this phone can actually verify about a linked wallet.
 *
 * Every line is the result of a check that runs, not a reassurance. The three that can be made
 * locally and instantly:
 *
 *  * the wallet app is still installed — if it is gone the session is dead and no payment will
 *    ever complete, which is worth knowing before standing in front of somebody;
 *  * where that app came from — Android records the installer, so "from the Solana dApp Store"
 *    is a fact rather than a claim, and a sideloaded wallet is named as one;
 *  * whether the authorization token is held here.
 *
 * What is deliberately NOT claimed: that the token still works. Only the wallet can answer
 * that, and only by being asked to sign something. A green tick for something unverified is
 * worth less than no tick at all.
 */
private data class Check(val ok: Boolean, val label: String, val detail: String? = null)

private fun checksFor(context: Context, w: Linked): List<Check> {
    val pm = context.packageManager
    val checks = mutableListOf<Check>()

    val pkg = w.pkg?.takeIf { it.isNotBlank() }
    if (pkg == null) {
        // Linked before the picker existed, so there is no package to look up. Said plainly
        // rather than shown as a failure — nothing is wrong, we simply cannot check.
        checks += Check(false, "Wallet app not recorded", "Linked before AirPay tracked this")
    } else {
        val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        checks += Check(installed,
            if (installed) "Wallet app installed" else "Wallet app is gone",
            if (installed) pkg else "Reinstall $pkg to use this wallet")

        if (installed) {
            // "Could not ask" and "came from nowhere" are different answers and must not
            // collapse into the same one. Android blanks the installer's name when the caller
            // cannot see that package, so a swallowed failure here reads as "sideloaded" —
            // an accusation, against a wallet that came from the dApp Store.
            val source = runCatching { pm.getInstallSourceInfo(pkg) }
            val from = source.getOrNull()?.installingPackageName
            checks += when {
                source.isFailure -> Check(true, "Install source not disclosed",
                    "Android did not say where it came from")
                from == "com.solanamobile.dappstore" -> Check(true, "From the Solana dApp Store")
                from == "com.android.vending" -> Check(true, "From Google Play")
                from == null -> Check(false, "Sideloaded", "Not installed from a store")
                else -> Check(true, "Installed by", from)
            }
        }
    }

    checks += Check(w.token != null,
        if (w.token != null) "Session held on this phone" else "No session held",
        if (w.token != null) null else "The wallet will be asked to authorize again")

    return checks
}

/**
 * One linked wallet: what it is, what holds up when checked, and the way to unlink it.
 *
 * On the app's own [Tray] — the same chrome, scrim and `arrive` spring as the tap sheet, rather
 * than Material's, which is a different scrim, a different spring and a different corner in the
 * same app. The checks block is concentric with the tray: [Tray.INNER] is the outer radius
 * minus the padding it sits inside, so the curves nest instead of merely both being round.
 *
 * The rows arrive staggered. Frequency decides how much a thing animates and this screen is
 * rare, so it gets the entrance; everything on it is still interruptible.
 */
@Composable
private fun WalletChecksSheet(
    w: Linked,
    linking: Boolean,
    onLink: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val clip = LocalClipboardManager.current
    var confirming by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val checks = remember(w.address, w.token, w.pkg) { checksFor(context, w) }

    Tray(onClose = onClose) {
        Spacer(Modifier.height(Space.lead))

        Row(verticalAlignment = Alignment.CenterVertically) {
            WalletLogo(w, 46.dp, 14.dp)
            Spacer(Modifier.width(Space.inline))
            Column(Modifier.weight(1f)) {
                Text(w.username ?: w.name, color = D.Black, style = Type.heading, maxLines = 1)
                Spacer(Modifier.height(Space.tight))
                val copy = remember { MutableInteractionSource() }
                // Short, and tappable. The full 44 characters wrap to two lines and are read by
                // nobody; what people do with an address is paste it.
                Text(
                    if (copied) "Copied" else w.short,
                    color = if (copied) D.SuccessInk else D.Quiet,
                    // An address is an identifier; "Copied" is a word, so it drops the mono.
                    style = if (copied) Type.caption else Type.mono,
                    modifier = Modifier.then(PressScale(copy)).clip(RoundedCornerShape(6.dp))
                        .clickable(copy, indication = null) {
                            clip.setText(AnnotatedString(w.address))
                            copied = true
                            Haptics.tick(context)
                        }
                        .padding(vertical = 2.dp),
                )
            }
            Text("Linked ${whenLinked(w.addedAt)}", color = D.Quiet, style = Type.caption,
                textAlign = TextAlign.End)
        }

        Spacer(Modifier.height(16.dp))

        // No grey well behind the checks (no containers): the marks carry them, and each
        // check is a row with a hairline under it.
        Column(Modifier.fillMaxWidth()) {
            checks.forEachIndexed { i, c ->
                // Staggered, nearest the cause first. Nature is not synchronous.
                var here by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { delay(120L + i * 60L); here = true }
                val fade by animateFloatAsState(if (here) 1f else 0f, Settle, label = "check")
                Row(
                    Modifier.heightIn(min = 48.dp).padding(vertical = Space.row)
                        .graphicsLayer { alpha = fade; translationY = (1f - fade) * 8.dp.toPx() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // A check that did not pass is not a transaction the chain refused, so it
                    // is the quiet ink beside its alert mark rather than red.
                    HIcon(
                        if (c.ok) R.drawable.hi_tick else R.drawable.hi_alert,
                        if (c.ok) D.SuccessInk else D.Quiet, 16.dp,
                    )
                    Spacer(Modifier.width(Space.inline))
                    Column(Modifier.weight(1f)) {
                        Text(c.label, color = D.Black, style = Type.body)
                        c.detail?.let {
                            // A package name is an identifier; the rest are sentences.
                            Text(it, color = D.Quiet,
                                style = if (it.contains('.')) Type.mono else Type.caption,
                                maxLines = 1)
                        }
                    }
                }
                if (i < checks.lastIndex) RowLine()
            }
        }

        Spacer(Modifier.height(12.dp))
        WalletIdentity(w, linking, onLink)

        Spacer(Modifier.height(12.dp))
        // The strongest thing on this sheet, and the one line that is not a check — because it
        // is not a property of the wallet. It is what this app does not do.
        Text(
            "AirPay never receives your key. Every payment is signed inside the wallet and handed back already signed.",
            color = D.Quiet, style = Type.caption,
        )

        Spacer(Modifier.height(Space.section))
        // Two taps, because relinking means going back through the wallet. Nothing is spent
        // either way: this forgets a token, it does not touch the account.
        val remove = remember { MutableInteractionSource() }
        // An ink outline on white, not a red well. Removing a wallet forgets a token: nothing
        // is spent and the chain refuses nothing, so it is not one of the red states. The
        // weight is carried by the second tap and by the words, which is where it belongs.
        Box(
            Modifier.fillMaxWidth().then(PressScale(remove))
                .clip(RoundedCornerShape(Tray.INNER))
                .border(1.5.dp, D.Black, RoundedCornerShape(Tray.INNER))
                .clickable(remove, indication = null) {
                    if (confirming) onRemove() else {
                        confirming = true
                        Haptics.tick(context)
                    }
                }
                .heightIn(min = 52.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (confirming) "Tap again to remove" else "Remove wallet",
                color = D.Black, style = Type.heading, textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(Space.lead))
    }
}

/** "Today", "3 days ago", or a date once that stops being the useful answer. */
private fun whenLinked(at: Long): String {
    if (at <= 0) return "a while ago"
    val days = (System.currentTimeMillis() - at) / 86_400_000L
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        days < 30L -> "$days days ago"
        else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(at))
    }
}

/**
 * The people you can pay without tapping.
 *
 * A tap is the fastest way to pay someone standing in front of you and the slowest way to pay
 * someone who isn't. These are the faces already met — one press opens the same sheet a tap
 * would, addressed to them.
 *
 * The dark pill they used to sit in, and the overlapping fan of faces inside it, are gone: they
 * are a plain row of avatars on the page now (see [People]).
 */
/**
 * Someone's phone, or their initial when we don't know it.
 *
 * The brand is what a person recognises across a table — "the OnePlus", "the Seeker" — which
 * is the whole reason the payload carries it. A letter is the fallback, not the default.
 */



/**
 * Somebody's face: their initial in a white disc with a ring.
 *
 * One avatar for you and for everybody else, so "this is them" is said in the same terms as
 * "this is you". The ring carries the claim — **2dp blue for a verified Seeker**, a hairline
 * for everyone else — replacing the tinted disc it used to sit in.
 *
 * The phone maker's artwork no longer stands in for a person. It survives as a small corner
 * chip in [People], where it is one fact beside the name rather than the whole picture of them.
 * The letter is 0.38 of the disc: a fraction of the shape, not a step on the type scale.
 */
@Composable
internal fun Face(c: Contact, size: Dp, ring: Color? = null) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .then(if (ring != null) Modifier.background(ring).padding(2.dp) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        // The disc is `Initial` (Design.kt). `Face` stays because it knows what a Contact is
        // — which letter, whether it is a Seeker, and the outer ring a caller can put behind
        // it — but it no longer redraws the disc. Five places drew that disc, and they had
        // drifted apart on the letter's weight.
        Initial(c.label.take(1).uppercase(), size, seeker = c.seeker, modifier = Modifier.fillMaxSize())
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun Header(
    checking: Boolean,
    unread: Int,
    /** What this phone calls itself — the initial goes in the avatar. */
    me: String,
    onCheck: () -> Unit,
    onAlerts: () -> Unit,
    onProfile: () -> Unit,
    /** The phone would swallow our notifications: the bell says so with a dot. */
    notifOff: Boolean = false,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Their initial rather than a generic silhouette: this app already draws every
            // OTHER person as a letter in a circle, and drawing yourself the same way says
            // "this is you, in the same terms as them". It is also the only picture of
            // somebody the app is ever entitled to — nobody uploaded a photo.
            val avatar = remember { MutableInteractionSource() }
            // A white disc with a hairline, not a grey one: the same avatar everybody else
            // gets, which is the whole point. The letter is 0.38 of the disc.
            Initial(
                me.take(1).uppercase().ifBlank { "Y" }, 38.dp,
                modifier = Modifier.then(PressScale(avatar))
                    .clickable(avatar, indication = null, onClick = onProfile)
                    .semantics { contentDescription = "You" },
            )
            Spacer(Modifier.width(Space.inline))
            // Long-press the wordmark for diagnostics and the exchange log. The status line
            // that used to carry them is gone, and they are a builder's tool, not a user's —
            // but they are how every NFC problem in this app has been read, so they keep a
            // door. An invisible one, which is the right size for the audience.
            // 30sp in Bricolage is the LOGO's own size, not a step on the type scale — the
            // wordmark is a fixed composition and is exempt (ui/PORT.md §3).
            Text("AirPay", color = D.Black, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
                fontFamily = Display, letterSpacing = (-0.5).sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).combinedClickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onLongClick = onCheck, onClick = {},
                ))
            Spacer(Modifier.weight(1f))
            val interaction = remember { MutableInteractionSource() }
            Box(contentAlignment = Alignment.TopEnd) {
                // 24 in a 48dp hit area: 24 is the action size, 48 the touch minimum. Named
                // with its count, because that is what it has to say out loud.
                Box(
                    Modifier.then(PressScale(interaction)).size(48.dp).clip(CircleShape)
                        .clickable(interaction, indication = null, onClick = onAlerts)
                        .semantics {
                            contentDescription = when {
                                unread > 0 -> "Notifications, $unread unread"
                                notifOff -> "Notifications, turned off"
                                else -> "Notifications"
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) { HiIcon(Hi.Bell, D.Black, 24.dp) }
                // A count, not a dot: "three things happened" is worth opening, "something
                // happened" is what a dot says and it says it identically for one and for ten.
                if (unread > 0) {
                    // On ink, not on blue: an 11sp white number on #008FFF is 3.30:1 and
                    // fails; on ink it is 12.46:1. The 2dp white rim is what holds the badge
                    // off the bell's own stroke.
                    Box(
                        Modifier.padding(top = 3.dp, end = 1.dp).defaultMinSize(18.dp, 18.dp)
                            .clip(CircleShape).background(D.White).padding(2.dp)
                            .clip(CircleShape).background(D.Black)
                            .padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (unread > 9) "9+" else "$unread", color = D.White, style = CountStyle)
                    }
                } else if (notifOff) {
                    // Nothing unread, but the phone is not allowed to tell them when there is.
                    // A dot and not a count: there is nothing to count, only somewhere to go.
                    // The count wins when there is one, because it leads to the same page and
                    // the page asks the question at its top either way.
                    Box(
                        Modifier.padding(top = 8.dp, end = 8.dp).size(12.dp)
                            .clip(CircleShape).background(D.White).padding(2.dp)
                            .clip(CircleShape).background(D.Black),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, active: Boolean, enabled: Boolean, m: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    // Active is the blue fill. Inactive is an ink outline on white rather than a grey body
    // (no containers), and disabled is the hairline, so a dead control cannot be mistaken for
    // a live one that happens to be pale.
    val bg = if (enabled && active) D.Blue else Color.Transparent
    val edge = when {
        !enabled -> D.Line
        active -> D.Blue
        else -> D.Black
    }
    Box(
        m.then(PressScale(interaction)).heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
            .background(bg).border(1.5.dp, edge, RoundedCornerShape(16.dp))
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active && enabled) D.White else if (enabled) D.Black else D.Quiet,
            style = Type.heading)
    }
}

@Composable
private fun LogView(log: List<String>) {
    val state = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) state.animateScrollToItem(log.size - 1) }
    // A hairline box rather than a grey well: the log is still a bounded thing, but the
    // boundary is drawn, not filled.
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, D.Line, RoundedCornerShape(14.dp)).padding(16.dp),
    ) {
        items(log) { line ->
            Text(line,
                color = when {
                    line.startsWith("✓") -> D.SuccessInk
                    // A failed step in the exchange log IS the chain or the radio refusing,
                    // which is the one thing the danger ink is for.
                    line.startsWith("✗") -> D.DangerInk
                    else -> D.Quiet
                },
                style = Type.mono,
                modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

@Composable
private fun ConfirmSheet(
    req: PayRequest,
    busy: Boolean,
    onPay: () -> Unit,
    onLater: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        // Tapping outside is "later", never "gone": the request is already saved.
        onDismissRequest = onLater,
        containerColor = D.White,
        title = { Text("Pay ${req.label}?", color = D.Black, style = Type.title) },
        text = {
            Column {
                // The amount on a sheet is the `amount` step, and it is INK: the amount is the
                // thing being read, not a link.
                Text("${req.amount} ${req.token}", color = D.Black, style = Type.amount.figure())
                Spacer(Modifier.height(Space.tight))
                Text("to ${req.recipient.take(8)}…${req.recipient.takeLast(6)}",
                    color = D.Quiet, style = Type.mono)
                if (req.genesis != null) {
                    Spacer(Modifier.height(Space.tight))
                    Text("✓ Verified Seeker", color = D.BlueInk, style = Type.caption)
                }
                Spacer(Modifier.height(Space.inline))
                Text("No rush, this stays in Requests until you pay it.",
                    color = D.Quiet, style = Type.caption)
            }
        },
        confirmButton = {
            TextButton(onClick = onPay, enabled = !busy) {
                Text(if (busy) "…" else "Pay", color = D.BlueInk, style = Type.heading)
            }
        },
        dismissButton = {
            Row {
                // Declining is not a chain refusal: nothing was sent, so nothing is red.
                TextButton(onClick = onDecline, enabled = !busy) {
                    Text("Decline", color = D.Black, style = Type.body)
                }
                TextButton(onClick = onLater) { Text("Later", color = D.Quiet, style = Type.body) }
            }
        },
    )
}

@Composable
private fun BackHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BackArrow(onBack)
        Text(title, color = D.Black, style = Type.title)
    }
}

/**
 * A row that stands for a whole screen.
 *
 * Google Pay's shape rather than a bank's: one page, with the depth a tap away. Three tabs of
 * equal weight would have claimed AirPay has three destinations, and it has one — the tap.
 * These say what is behind them, so opening them is a decision rather than a look.
 */
@Composable
private fun PageStrip(
    @DrawableRes icon: Int,
    tint: Color,
    title: String,
    detail: String,
    onOpen: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // A row split by space and a hairline, not a grey slab, and the mark sits on the page
    // rather than in a tinted disc of its own colour. The tint arrives as a FILL, so the mark
    // takes its ink.
    Row(
        Modifier.fillMaxWidth().then(PressScale(interaction)).heightIn(min = 48.dp)
            .clickable(interaction, indication = null, onClick = onOpen)
            .padding(vertical = Space.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { HIcon(icon, tint.ink, 24.dp) }
        Spacer(Modifier.width(Space.inline))
        Column(Modifier.weight(1f)) {
            Text(title, color = D.Black, style = Type.body)
            Text(detail, color = D.Quiet, style = Type.caption,
                modifier = Modifier.padding(top = Space.tight))
        }
        HiIcon(Hi.ArrowUpRight, D.Quiet, 20.dp)
    }
}

/**
 * A heading for the three tiles. Just the words.
 *
 * It began as a row with an icon, a subtitle and an arrow — a fourth thing to read directly
 * above three things to read, all of which said the same. The tiles are the controls; this
 * only names them.
 */
@Composable
private fun EarnStrip(summary: Rewards.Summary?, onOpen: () -> Unit) {
    Text("Offers and rewards", color = D.Black, style = Type.heading)
}


internal enum class Mark { Coins, Ticket, Rings, Signal, Screen, Receipt, Basket, Plane, Cup, Game, Dots, Play, Cinema }

/**
 * Recharging a phone, which is a different job from buying a gift card.
 *
 * The generic shop screen lists products and then their denominations, which works when the
 * denominations are money. A mobile plan is not money: "2GB/Day 5G Plan - 84 Days" is a thing
 * you choose on its merits, and the price is a consequence. Drawn through the generic screen it
 * came out as a pill reading "₹2GB/Day 5G Plan - 84 Days", fifty times.
 *
 * So this asks the three questions in the order anybody actually answers them:
 *
 *  1. **Which number.** Prefilled from the SIM where the phone will say, because recharging
 *     your own number is the common case and the device already knows it.
 *  2. **Which network.** Chosen from the SIM rather than from the number — India has had
 *     portability since 2011, so a prefix says who *issued* a number, not who runs it. Getting
 *     this wrong sends money to the wrong network and cannot be undone, so it is always
 *     visible and always changeable.
 *  3. **Which plan.** With its real price, cheapest first.
 *
 * **What this cannot do, and does not pretend to:** show the plan the number is currently on,
 * or what it costs to keep. Bitrefill sells recharges; it has no subscriber lookup, and nothing
 * else in reach does either. A "current plan" panel here would be invented, so there isn't one.
 */
@Composable
private fun RechargeSection(
    section: Shop.Section,
    number: String,
    onNumber: (String) -> Unit,
    operator: Shop.Product?,
    onOperator: (Shop.Product) -> Unit,
    finding: Boolean,
    found: String?,
    onFind: (String) -> Unit,
    /** Everything bought before, so the screen can say what was chosen for this number last. */
    past: List<Orders.Order>,
    /** Product, value, the number as +91…, and what the network publishes about the plan, if known. */
    onBuy: (Shop.Product, String, String, Plans.Extra?) -> Unit,
) {
    // What the network itself says about its plans, where the server can read it (Jio today).
    // It only describes: an empty list just means the plans are drawn the plain way.
    val operatorKey = operator?.id?.let { if ("jio" in it) "jio" else null }
    val catalogue by produceState(emptyList<Plans.Extra>(), operatorKey) {
        value = operatorKey?.let { ServerClient.planCatalogue(it) } ?: emptyList()
    }
    // Only the products that actually credit a number. The Phone section also carries eSIMs
    // and Viber credit, which are not recharges and would only be in the way here.
    val operators = remember(section) { section.products.filter { it.isMobileRecharge } }
    val e164 = Sims.e164(number)

    Column(Modifier.fillMaxSize().verticalScroll(rememberHeldScroll("recharge"))) {
        var picking by rememberSaveable { mutableStateOf(false) }
        // Two states, as the reference apps have them: a bare number box until a network is
        // known, then the box steps aside for a header that says who is being recharged. It
        // comes back only when somebody taps Change, because the number is as correctable as
        // the network.
        if (operator == null || picking) {
            val bad = number.isNotBlank() && e164 == null
            val pill = RoundedCornerShape(999.dp)
            Row(
                Modifier.fillMaxWidth().height(56.dp).clip(pill)
                    .border(1.5.dp, if (bad) D.DangerInk else D.Line, pill),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(18.dp))
                HiIcon(Hi.Search, D.Quiet, 20.dp)
                Spacer(Modifier.width(10.dp))
                Text("+91", color = D.Quiet, style = Type.body)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (number.isEmpty()) Text("Enter mobile number", color = D.Quiet, style = Type.body, maxLines = 1)
                    BasicTextField(
                        value = number,
                        onValueChange = { v -> onNumber(v.filter { it.isDigit() }.take(12)) },
                        singleLine = true,
                        textStyle = Type.body.copy(color = D.Black),
                        cursorBrush = SolidColor(D.Blue),
                        // The plain number pad: digits and nothing else. `Phone` is the dialler
                        // layout, with * # + and pause keys that have no place in a recharge.
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(16.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (bad) "That is not a 10-digit number." else "The recharge is credited to this number.",
                color = if (bad) D.DangerInk else D.Quiet, style = Type.caption,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            // Numbers this phone has recharged before, newest first, one row per number — with the
            // network each is on and what was bought last, read from this phone's own purchases.
            val recents = remember(past) {
                past.filter { o -> o.recipient?.removePrefix("+91")?.length == 10 }
                    .distinctBy { it.recipient?.removePrefix("+91") }.take(4)
            }
            if (recents.isNotEmpty() && number.isEmpty()) {
                Spacer(Modifier.height(Space.section))
                Text("Recent recharges", color = D.Black, style = Type.heading)
                Spacer(Modifier.height(Space.lead))
                val cardShape = RoundedCornerShape(22.dp)
                Column(Modifier.fillMaxWidth().clip(cardShape).background(PLAN_WASH).border(1.5.dp, PLAN_EDGE, cardShape)) {
                    recents.forEachIndexed { i, o ->
                        val n = o.recipient!!.removePrefix("+91")
                        val mark = operatorMark(o.name)
                        // The network's own logo filling the whole circle. Until a network is one of the
                        // four we carry, the drawn mark stands in and the name is written beside the number.
                        val logo = operatorLogoRes(o.name)
                        if (i > 0) RowLine()
                        Row(
                            Modifier.fillMaxWidth().clickable { onNumber(n) }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (logo != null) {
                                androidx.compose.foundation.Image(
                                    androidx.compose.ui.res.painterResource(logo), contentDescription = mark.name,
                                    modifier = Modifier.size(48.dp).clip(CircleShape),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            } else {
                                Box(Modifier.size(48.dp).clip(CircleShape).background(mark.color), contentAlignment = Alignment.Center) {
                                    Text(mark.letters, color = D.White, style = Type.heading, maxLines = 1)
                                }
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(n, color = D.Black, style = Type.mono)
                                    if (logo == null) {
                                        Spacer(Modifier.width(10.dp))
                                        PlanPill(mark.name, outlined = true)
                                    }
                                }
                                Spacer(Modifier.height(Space.tight))
                                Row {
                                    Text("Last recharged ", color = D.Quiet, style = Type.caption)
                                    Text(ago(o.at), color = D.Black, style = Type.caption.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                            HiIcon(Hi.ArrowRight, D.Quiet, 20.dp)
                        }
                    }
                }
            }
        }

        // The lookup runs itself, the moment the number is complete. It used to be a button,
        // which asked somebody to tell the app to do the one thing it was always going to do.
        //
        // Keyed on the normalised number, so it fires once per number rather than once per
        // keystroke, and the pause lets somebody finish typing — or correct a digit — before
        // anything is asked. Repeats cost nothing anyway: the server caches the answer.
        LaunchedEffect(e164) {
            val number = e164 ?: return@LaunchedEffect
            delay(350)
            onFind(number)
        }
        if (finding) {
            Spacer(Modifier.height(12.dp))
            Text("Checking which network…", color = D.Quiet, style = Type.caption)
        }

        // Once the network is known it is a FACT, not a question, so it stops being a row of
        // choices and becomes one line saying what it is. A list of every network with one
        // highlighted asks somebody to check the app's work on something the app now knows
        // better than they do.
        //
        // The list only comes back when it has to: when the lookup could not tell, or when
        // somebody taps the answer to disagree with it. That path stays, because this decides
        // where real money goes and an app that cannot be corrected is worse than a wrong one.
        var query by rememberSaveable(operator?.id) { mutableStateOf("") }
        val chosen = operator

        if (chosen != null && !picking) {
            // One card for the whole question "who, on what, and what next": the number and its
            // network with the one way to disagree, what this number bought last time, and the
            // small data top-ups that people reach for when they have run out mid-day.
            Spacer(Modifier.height(8.dp))
            val symbol = symbolFor(chosen.currency)
            val headPlans = remember(chosen.id) {
                chosen.packages.ifEmpty { chosen.values.map { Shop.Package(it, it.toIntOrNull() ?: 0) } }
                    .sortedBy { it.amount }
            }
            val last = remember(e164, past) {
                past.firstOrNull { it.recipient != null && it.recipient == e164 }
            }
            val again = last?.let { l -> headPlans.firstOrNull { it.value == l.value } }
            val quick = remember(headPlans) {
                if (!chosen.sellsPlans) emptyList() else headPlans.filter {
                    val sh = Plans.shelves(it.value)
                    Plans.Shelf.Short in sh && Plans.Shelf.Data in sh && Plans.of(it.value).days != null
                }.take(8)
            }
            val mark = chosen.name.removeSuffix(" India").removeSuffix(" Bundles")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val netLogo = operatorLogoRes(chosen.name)
                if (netLogo != null) {
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(netLogo), contentDescription = mark,
                        modifier = Modifier.size(56.dp).clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else Box(
                    Modifier.size(56.dp).clip(CircleShape).background(D.Blue),
                    contentAlignment = Alignment.Center,
                ) { Text(mark.take(1).uppercase(), color = D.White, style = Type.title) }
                Spacer(Modifier.width(Space.inline))
                Column(Modifier.weight(1f)) {
                    // The number is the half that cannot be checked afterwards, so it is set to
                    // be read character by character.
                    Text("+91 ${e164?.removePrefix("+91") ?: number}", color = D.Black, style = Type.mono)
                    Spacer(Modifier.height(Space.tight))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$mark Prepaid", color = D.Quiet, style = Type.caption)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Change", color = D.BlueInk, style = Type.caption,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            // No tall padding here: it made this line taller than the number above
                            // it, so the two lines sat off-centre against the logo.
                            modifier = Modifier.clickable { picking = true }.padding(vertical = 2.dp),
                        )
                    }
                }
            }
            // Where to start. Picked by rule from this operator's own list, never by what is
            // popular: what this number bought before, the smallest data pack, and the cheapest
            // 5G plan that lasts about a month.
            val picks = remember(headPlans, again) {
                val bundles = headPlans.filter { chosen.sellsPlans }
                val data = bundles.firstOrNull { Plans.Shelf.Short in Plans.shelves(it.value) && Plans.Shelf.Data in Plans.shelves(it.value) }
                val fiveG = bundles.firstOrNull {
                    val sh = Plans.shelves(it.value); Plans.Shelf.Fast in sh && Plans.Shelf.Month in sh && Plans.Shelf.Daily in sh
                }
                listOfNotNull(again, data, fiveG).distinctBy { it.value }.take(2)
            }
            if (picks.isNotEmpty() && e164 != null) {
                Spacer(Modifier.height(PLAN_GAP))
                Text("Suggested plans", color = D.Black, style = Type.heading)
                Spacer(Modifier.height(Space.lead))
                // One height for both: the row is as tall as its taller card, and each card fills it.
                Row(
                    Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    picks.forEach { pack ->
                        val d = Plans.of(pack.value)
                        val shape = RoundedCornerShape(18.dp)
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(shape).background(PLAN_WASH).border(1.5.dp, PLAN_EDGE, shape)
                                .clickable { onBuy(chosen, pack.value, e164, Plans.extrasFor(pack.value, pack.amount, catalogue).firstOrNull()) }
                                .padding(14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(currencyStyled("$symbol${pack.price}"), color = D.Black, style = Type.title.figure(), modifier = Modifier.weight(1f))
                                Box(Modifier.size(28.dp).clip(CircleShape).background(D.Blue), contentAlignment = Alignment.Center) {
                                    HiIcon(Hi.ArrowUpRight, D.White, 16.dp)
                                }
                            }
                            Spacer(Modifier.height(Space.tight))
                            Text(
                                if (pack === again) "You bought this before" else if (d.data != null && d.days != null && d.days < 28) "Data pack" else Plans.benefits(pack.value).firstOrNull() ?: "Plan",
                                color = D.Quiet, style = Type.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOfNotNull(d.data, d.validity?.replaceFirstChar { it.uppercase() }).joinToString(" • ").ifEmpty { pack.value },
                                color = D.Black, style = Type.body, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (picks.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else if (picking || (found != null && !finding && chosen == null)) {
            // Either the lookup could not tell, or somebody disagreed with it.
            Spacer(Modifier.height(Space.section))
            // Ink: the lookup could not say, and being asked to choose is not an error.
            found?.takeIf { chosen == null }?.let {
                Text(it, color = D.Black, style = Type.body)
                Spacer(Modifier.height(Space.lead))
            }
            Text("NETWORK", color = D.Quiet, style = Type.micro)
            Spacer(Modifier.height(Space.lead))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.tight),
                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                operators.forEach { p ->
                    val on = chosen?.id == p.id
                    // Outline chips on white rather than grey ones, and the chosen one is the
                    // INK fill: white on ink is 12.46:1 at any size, where white on D.Blue
                    // would need 19sp bold for a chip this small.
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (on) D.Black else Color.Transparent)
                            .border(1.5.dp, if (on) D.Black else D.Line, RoundedCornerShape(999.dp))
                            .clickable { onOperator(p); picking = false }
                            .heightIn(min = 48.dp).padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            p.name.removeSuffix(" India").removeSuffix(" Bundles"),
                            color = if (on) D.White else D.Black, style = Type.body,
                        )
                    }
                }
            }
        }

        if (chosen == null) {
            // Nothing to say yet. Before a number is typed the screen is one box, which is the
            // whole instruction — a "pick a network" prompt above an empty field is a second
            // question asked before the first one has been answered.
            if (found != null && !finding) {
                Spacer(Modifier.height(Space.section))
                Text("Pick a network to see what it sells.", color = D.Black, style = Type.body)
            }
        } else {
            Spacer(Modifier.height(PLAN_GAP))
            val plans = remember(chosen.id) {
                chosen.packages.ifEmpty { chosen.values.map { Shop.Package(it, it.toIntOrNull() ?: 0) } }
                    .sortedBy { it.amount }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (chosen.sellsPlans) "PLANS" else "TOP-UP",
                    color = D.Quiet, style = Type.micro, modifier = Modifier.weight(1f),
                )
                // Who sells every recharge on this page.
                HiIcon(Hi.Info, D.Quiet, 14.dp)
                Spacer(Modifier.width(6.dp))
                Text("Sold by Bitrefill", color = D.Quiet, style = Type.micro)
            }
            if (!chosen.sellsPlans) {
                Spacer(Modifier.height(Space.lead))
                // Two things said plainly rather than left to be discovered.
                //
                // The first is that there is no plan list here — for some operators the shop
                // only carries the top-up integration, which is not the same as the operator
                // not selling plans.
                //
                // The second is that the number on the card is what you PAY, and the talktime
                // that lands is less. Every operator that publishes the figure shows a gap:
                // Vi credits ₹423.73 for ₹500, which is exactly ₹500 ÷ 1.18 — the GST — and
                // Jio credits ₹39.37 for ₹50, so it takes a fee on top. These products publish
                // nothing at all, so the size cannot be shown without inventing it. What can
                // be said is that it exists, which beats a screen implying it does not.
                Text(
                    "${chosen.name.removeSuffix(" India")} sells credit here, not plans, it " +
                        "goes to the number's main balance.",
                    color = D.Quiet, style = Type.caption,
                )
                Spacer(Modifier.height(Space.tight))
                Text(
                    "What lands as talktime is less than what you pay: tax and the operator's " +
                        "fee come off first, and neither is published here. Credit on its own " +
                        "does not add validity, a plan does that.",
                    color = D.Quiet, style = Type.caption,
                )
            }
            Spacer(Modifier.height(Space.lead))

            // The list. Search first, then what narrows it, then the shelves, as the reference
            // apps order them; each only when there is something for it to act on.
            if (chosen.sellsPlans && plans.size > 8) {
                SearchField(query, "Search e.g. 349 or 28 days") { query = it }
                Spacer(Modifier.height(12.dp))
            }
            val shelved = remember(chosen.id) { plans.associateWith { Plans.shelves(it.value) } }
            // What the network publishes about each plan sold here, matched on price and checked.
            val extraOf = remember(chosen.id, catalogue) {
                plans.associateWith { Plans.extrasFor(it.value, it.amount, catalogue) }
            }
            // The network's own shelves are used once most plans have a description; until then
            // (or for a network with no catalogue) the shelves are read off the plan's own line.
            val described = extraOf.values.count { it.isNotEmpty() }
            val useNetwork = described > 0 && described * 2 >= plans.size
            val netTabs = remember(extraOf) {
                catalogue.map { it.category }.distinct().filter { c -> extraOf.values.any { l -> l.any { it.category == c } } }
            }
            var netTab by rememberSaveable(chosen.id) { mutableStateOf<String?>(null) }

            val validityShelves = setOf(Plans.Shelf.Short, Plans.Shelf.Month, Plans.Shelf.Quarter, Plans.Shelf.Yearly)
            val present = remember(shelved) {
                Plans.Shelf.entries.filter { sh -> shelved.values.any { sh in it } }
                    .filter { it != Plans.Shelf.All || shelved.size > 4 }
            }
            val tabs = present.filter { it !in validityShelves }
            val lengths = present.filter { it in validityShelves }
            var shelf by rememberSaveable(chosen.id) { mutableStateOf(Plans.Shelf.All) }
            var length by rememberSaveable(chosen.id) { mutableStateOf<Plans.Shelf?>(null) }
            if (lengths.size > 1) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    lengths.forEach { t ->
                        val sel = length == t
                        Box(
                            Modifier.clip(RoundedCornerShape(999.dp))
                                .background(if (sel) D.Black else D.White)
                                .border(1.5.dp, if (sel) D.Black else D.Line, RoundedCornerShape(999.dp))
                                .clickable { length = if (sel) null else t }
                                .heightIn(min = 44.dp).padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(t.label, color = if (sel) D.White else D.Black, style = Type.body) }
                    }
                }
            }
            // Underlined tabs, not pills: this row switches the LIST, where the chips above only
            // narrow it. The names are the network's own shelves when it publishes them.
            val tabLabels: List<Pair<String, () -> Unit>> =
                if (useNetwork) listOf("All" to { netTab = null }) + netTabs.map { c -> Plans.tabName(c) to { netTab = c } }
                else tabs.map { t -> t.label to { shelf = t } }
            val tabOn: (Int) -> Boolean = { i ->
                if (useNetwork) (if (i == 0) netTab == null else netTabs.getOrNull(i - 1) == netTab) else tabs.getOrNull(i) == shelf
            }
            if (tabLabels.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tabLabels.forEachIndexed { i, (label, pick) -> PlanTab(label, tabOn(i), pick) }
                }
                Spacer(Modifier.height(12.dp))
            }

            val ready = e164 != null
            val shown = plans.filter { p ->
                Plans.matches(query, p.value, p.amount) &&
                    (length == null || length in (shelved[p] ?: emptySet())) &&
                    (if (useNetwork) netTab == null || extraOf[p].orEmpty().any { it.category == netTab }
                    else shelf in (shelved[p] ?: emptySet()))
            }
            if (shown.isEmpty()) {
                Text(
                    if (query.isBlank()) "Nothing here." else "Nothing matches “$query”.",
                    color = D.Black, style = Type.body,
                )
            }
            val symbol = symbolFor(chosen.currency)
            val planShape = RoundedCornerShape(20.dp)
            shown.forEach { pack ->
                val detail = remember(pack.value) { Plans.of(pack.value) }
                // The row for the shelf being looked at, so a plan on two shelves shows its own.
                val ex = extraOf[pack].orEmpty().let { l -> l.firstOrNull { it.category == netTab } ?: l.firstOrNull() }
                // The apps bundled with the plan: the network's own list where there is one, else
                // the ones named in the shop's line, which come without pictures.
                val perks = ex?.subscriptions?.takeIf { it.isNotEmpty() }
                    ?: remember(pack.value) { Plans.benefits(pack.value).map { Plans.Sub(it, null) } }
                val tag = ex?.tag?.takeIf { it.isNotBlank() } ?: if (detail.fast) "True 5G" else null
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(planShape)
                        .background(PLAN_WASH).border(1.5.dp, PLAN_EDGE, planShape)
                        .clickable(enabled = ready) { onBuy(chosen, pack.value, e164!!, ex) },
                ) {
                    // The plan's own headline, flush in the top corner like a tab on a folder.
                    if (tag != null) {
                        Text(
                            tag, color = D.Black, style = Type.micro, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clip(RoundedCornerShape(bottomEnd = 12.dp)).background(D.Highlight)
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                    Column(Modifier.padding(start = 16.dp, end = 12.dp, top = if (tag != null) 12.dp else 16.dp, bottom = 16.dp)) {
                        // One way in: the whole card opens the plan, and the arrow says so.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.width(98.dp)) {
                                Text(
                                    currencyStyled("$symbol${pack.price}"),
                                    color = if (ready) D.Black else D.Quiet, style = Type.title.figure(), maxLines = 1,
                                )
                                Plans.perDay(pack.amount, detail.days, symbol)?.let {
                                    Text(currencyStyled(it), color = D.Quiet, style = Type.caption.figure(), maxLines = 1)
                                }
                            }
                            Box(Modifier.width(1.5.dp).height(40.dp).background(PLAN_EDGE))
                            Spacer(Modifier.width(14.dp))
                            val validity = ex?.validity?.takeIf { it.isNotBlank() } ?: detail.validity?.replaceFirstChar { c -> c.uppercase() }
                            val data = ex?.data?.takeIf { it.isNotBlank() } ?: detail.data
                            if (validity != null || data != null) {
                                Labelled("Validity", validity ?: "-", Modifier.width(92.dp))
                                // A top-up buys talktime, not data: the same column, named for what it holds.
                                val talktime = "talktime" in pack.value.lowercase() || "top-up" in pack.value.lowercase()
                                Labelled(if (talktime) "Talktime" else "Data", data ?: "-", Modifier.weight(1f))
                            } else {
                                Text(pack.value, color = D.Black, style = Type.body, modifier = Modifier.weight(1f))
                            }
                            Box(Modifier.size(32.dp).clip(CircleShape).background(D.White), contentAlignment = Alignment.Center) {
                                HiIcon(Hi.ArrowRight, if (ready) D.BlueInk else D.Quiet, 18.dp)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.fillMaxWidth().height(1.5.dp).background(PLAN_EDGE))
                        Spacer(Modifier.height(10.dp))
                        // The important things, one to a line and lined up, instead of the network's
                        // whole description run together.
                        val lines = remember(pack.value, ex) { planSummary(ex, pack.value) }
                        lines.take(2).forEach { BulletLine(it, maxLines = 1) }
                        if (lines.size > 2) {
                            Text("+${lines.size - 2} more", color = D.Quiet, style = Type.caption, modifier = Modifier.padding(start = 14.dp, top = 2.dp))
                        }
                        if (perks.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            SubscriptionIcons(perks)
                        }
                    }
                }
            }
            if (!ready) {
                Text("Enter a number to recharge.", color = D.Quiet, style = Type.caption)
            }
        }
        Spacer(Modifier.height(Space.section))
    }
}

/** A small label on a plan: filled for a fact about the network, outlined for a perk. */
@Composable
private fun PlanPill(text: String, outlined: Boolean = false) {
    val shape = RoundedCornerShape(999.dp)
    Text(
        text, color = D.Black, style = Type.micro,
        modifier = Modifier.clip(shape)
            .then(if (outlined) Modifier.border(1.5.dp, D.Line, shape) else Modifier.background(D.Highlight))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** A caption over a value — the shape that lets validity and data be compared down a column. */
@Composable
private fun Labelled(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(end = 8.dp)) {
        Text(label.uppercase(), color = D.Quiet, style = Type.micro)
        Spacer(Modifier.height(Space.tight))
        Text(value, color = D.Black, style = Type.body)
    }
}

/** Collecting it. Blank clears, because "no email" has to stay reachable once one is set. */
@Composable
private fun EmailDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    val bad = text.isNotBlank() && !Identity.looksLikeEmail(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = D.White,
        title = { Text("Email for codes", color = D.Black, style = Type.title) },
        text = {
            Column {
                Text(
                    "Gift-card codes are shown in this app when they arrive. An email is a " +
                        "second copy, in case you close the app before reading one.",
                    color = D.Quiet, style = Type.caption,
                )
                Spacer(Modifier.height(Space.lead))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.trim() },
                    singleLine = true,
                    isError = bad,
                    placeholder = { Text("you@example.com", color = D.Quiet) },
                    supportingText = {
                        Text(
                            if (bad) "That does not look like an email address."
                            else "Kept on this phone. Leave it empty for no email.",
                            color = if (bad) D.DangerInk else D.Quiet,
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = !bad) {
                Text("Save", color = D.BlueInk, style = Type.heading)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = D.Quiet, style = Type.body) }
        },
    )
}



/** One labelled part of a code, big enough to read off and tappable to copy. */
@Composable
internal fun CodeRow(label: String, value: String, mono: Boolean = true) {
    val clip = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    // A row split by a hairline, not a grey field. A code, a PIN and a redeem link are all
    // identifiers rather than money, so none of them is an amount and all of them are mono.
    // The copy control is named, because "Copy" three times over says nothing about which.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable { clip.setText(AnnotatedString(value)); copied = true }
            .padding(vertical = Space.row)
            .semantics { contentDescription = "Copy $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = D.Quiet, style = Type.caption)
            Spacer(Modifier.height(Space.tight))
            Text(value, color = D.Black, style = if (mono) MonoBody else Type.body)
        }
        Spacer(Modifier.width(Space.inline))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HiIcon(Hi.Copy, D.BlueInk, 16.dp)
            Spacer(Modifier.width(Space.tight))
            Text(if (copied) "Copied" else "Copy", color = D.BlueInk, style = Type.caption)
        }
    }
    RowLine()
}

/** The one line that has to be on every shop screen: this is the real-money corner of the app. */
@Composable
internal fun MainnetNote() {
    // No tinted box any more: the mark and the words in blue INK are enough to be noticed
    // without a wash behind them. 16 is the icon size that sits beside a caption.
    Row(
        Modifier.fillMaxWidth().padding(bottom = Space.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HiIcon(Hi.Info, D.BlueInk, 16.dp)
        Spacer(Modifier.width(Space.tight))
        Text("Real money on Solana mainnet.", color = D.BlueInk, style = Type.caption)
    }
}



/**
 * What the price buys, when the value says more than the number already does.
 *
 * 399 and 1499 are three months of Prime and twelve, and a row of bare prices does not say
 * which is which — so a value like "Amazon Prime Subscription 3M" is printed under the price
 * as a caption, with the product's own name trimmed off the front of it.
 *
 * A value that is **just a number** is already on the pill and must not be repeated. The test
 * for that was `toIntOrNull()`, which is true of 399 and false of **1.8** — so Tinder's $1.80
 * pill carried a second line reading "1.8", and the eSIM's four fractional prices would each
 * have done the same. A price is a number whether or not it is a whole one.
 */
internal fun packageLabel(pack: Shop.Package, productName: String): String? = pack.value
    .takeIf { pack.amount > 0 && it.toDoubleOrNull() == null }
    ?.removePrefix(productName.removeSuffix(" India"))
    ?.trim()?.takeIf { it.isNotBlank() }

internal fun symbolFor(currency: String) = when (currency) {
    "INR" -> "₹"
    "USD" -> "$"
    "EUR" -> "€"
    else -> "$currency "
}


/**
 * The hand-drawn category marks, on their own so a 38dp history row and a 34dp tile can use
 * the same shape. Everything inside is sized from the canvas width, so it scales.
 */
@Composable
/**
 * A drawn mark.
 *
 * `internal`, and [notch] and [tint] are parameters, because three screens were copying this
 * geometry rather than calling it — the ticket, the rings and the coins each had a second,
 * slightly wrong version living in another file, which is the drift PARITY.md keeps a record
 * of. They could not call it: it was private, and it hard-coded a white notch and a blue mark,
 * neither of which is right on a blue poster.
 *
 * [notch] is whatever sits BEHIND the mark. The marks that punch holes — the ticket's bites,
 * the receipt's torn edge, the controller's thumb buttons — are drawn in it, so it has to be
 * told: white on the page, `D.Blue` on a poster. Get it wrong and the holes either fill with
 * grey bites or vanish into the card.
 */
internal fun MarkCanvas(mark: Mark, side: Dp, notch: Color = D.White, tint: Color = D.Blue) {
    // `side`, not `size`: inside a Canvas, `size` is the draw scope's own and shadowing it
    // turns every `size.width` below into a Dp.
    Canvas(Modifier.size(side)) {
            val w = size.width
            val blue = tint
            when (mark) {
                // Three discs seen from the side: a balance, stacked.
                Mark.Coins -> repeat(3) { i ->
                    drawOval(
                        color = if (i == 0) blue else blue.copy(alpha = .3f + .2f * (2 - i)),
                        topLeft = Offset(w * .12f, w * (.52f - i * .19f)),
                        size = Size(w * .76f, w * .3f),
                    )
                }
                // Signal arcs, the same idea as the tap mark: something reaching a phone.
                Mark.Signal -> repeat(3) { i ->
                    val r = w * (0.2f + i * 0.17f)
                    drawArc(
                        color = blue.copy(alpha = 1f - i * 0.25f),
                        startAngle = -55f, sweepAngle = 110f, useCenter = false,
                        topLeft = Offset(w * .22f - r, w * .5f - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(w * .09f, cap = StrokeCap.Round),
                    )
                }
                // A screen on a stand.
                Mark.Screen -> {
                    drawRoundRect(blue, Offset(w * .1f, w * .18f), Size(w * .8f, w * .52f),
                        CornerRadius(w * .1f))
                    drawRoundRect(blue.copy(alpha = .45f), Offset(w * .36f, w * .74f),
                        Size(w * .28f, w * .08f), CornerRadius(w * .04f))
                }
                // A receipt: a slab with a torn edge along the bottom.
                Mark.Receipt -> {
                    drawRoundRect(blue, Offset(w * .18f, w * .12f), Size(w * .64f, w * .62f),
                        CornerRadius(w * .08f))
                    repeat(4) { i ->
                        drawCircle(notch, radius = w * .07f,
                            center = Offset(w * (.24f + i * .17f), w * .76f))
                    }
                }
                // A basket, for everything that is not one of the three above.
                Mark.Basket -> {
                    drawRoundRect(blue, Offset(w * .12f, w * .36f), Size(w * .76f, w * .44f),
                        CornerRadius(w * .1f))
                    drawArc(blue.copy(alpha = .5f), 180f, 180f, false,
                        Offset(w * .28f, w * .12f), Size(w * .44f, w * .44f),
                        style = Stroke(w * .1f, cap = StrokeCap.Round))
                }
                // A ticket: a rounded slab with a bite out of each side.
                Mark.Ticket -> {
                    drawRoundRect(blue, Offset(w * .08f, w * .22f), Size(w * .84f, w * .56f),
                        CornerRadius(w * .14f))
                    drawCircle(notch, radius = w * .12f, center = Offset(w * .08f, w * .5f))
                    drawCircle(notch, radius = w * .12f, center = Offset(w * .92f, w * .5f))
                }
                // Two rings meeting, the way the avatars meet when phones touch.
                Mark.Rings -> {
                    val r = w * .26f
                    drawCircle(blue.copy(alpha = .35f), r, Offset(w * .34f, w * .5f), style = Stroke(w * .11f))
                    drawCircle(blue, r, Offset(w * .66f, w * .5f), style = Stroke(w * .11f))
                }
                // A paper plane, banking. The tail is the lighter half so the nose leads.
                Mark.Plane -> {
                    drawPath(
                        Path().apply {
                            moveTo(w * .90f, w * .14f); lineTo(w * .10f, w * .50f)
                            lineTo(w * .44f, w * .60f); close()
                        },
                        blue,
                    )
                    drawPath(
                        Path().apply {
                            moveTo(w * .90f, w * .14f); lineTo(w * .44f, w * .60f)
                            lineTo(w * .52f, w * .88f); close()
                        },
                        blue.copy(alpha = .45f),
                    )
                }
                // A cup: a lid, and a body that tapers the way a paper one does.
                Mark.Cup -> {
                    drawRoundRect(blue.copy(alpha = .45f), Offset(w * .16f, w * .16f),
                        Size(w * .68f, w * .13f), CornerRadius(w * .06f))
                    drawPath(
                        Path().apply {
                            moveTo(w * .24f, w * .34f); lineTo(w * .76f, w * .34f)
                            lineTo(w * .66f, w * .86f); lineTo(w * .34f, w * .86f); close()
                        },
                        blue,
                    )
                }
                // A controller, seen head on: a slab with two thumb buttons punched out.
                Mark.Game -> {
                    drawRoundRect(blue, Offset(w * .08f, w * .30f), Size(w * .84f, w * .40f),
                        CornerRadius(w * .18f))
                    drawCircle(notch, radius = w * .08f, center = Offset(w * .31f, w * .50f))
                    drawCircle(notch, radius = w * .08f, center = Offset(w * .69f, w * .50f))
                }
                // A strip of film: sprocket holes down both edges. Distinct from Ticket, which
                // is the Offers mark, and from Play, which is streaming — three neighbours that
                // would otherwise all be "a rectangle about watching something".
                Mark.Cinema -> {
                    drawRoundRect(blue, Offset(w * .14f, w * .12f), Size(w * .72f, w * .76f),
                        CornerRadius(w * .12f))
                    repeat(4) { i ->
                        val y = w * (.22f + i * .18f)
                        drawRoundRect(notch, Offset(w * .20f, y), Size(w * .09f, w * .09f),
                            CornerRadius(w * .02f))
                        drawRoundRect(notch, Offset(w * .71f, y), Size(w * .09f, w * .09f),
                            CornerRadius(w * .02f))
                    }
                }
                // A play triangle in a rounded frame: the thing you press, not the box it
                // comes out of. Deliberately unlike Screen, which is the set-top box shelf
                // sitting right beside it.
                Mark.Play -> {
                    drawRoundRect(blue, Offset(w * .08f, w * .18f), Size(w * .84f, w * .64f),
                        CornerRadius(w * .2f))
                    drawPath(
                        Path().apply {
                            moveTo(w * .40f, w * .34f); lineTo(w * .70f, w * .50f)
                            lineTo(w * .40f, w * .66f); close()
                        },
                        notch,
                    )
                }
                // Three dots: everything that did not need a shape of its own.
                Mark.Dots -> repeat(3) { i ->
                    drawCircle(
                        blue.copy(alpha = if (i == 1) 1f else .45f),
                        radius = w * .10f,
                        center = Offset(w * (.22f + i * .28f), w * .5f),
                    )
                }
            }
    }
}




/**
 * The two ways to pay somebody, pinned to the bottom of the home screen.
 *
 * Floating over the page rather than sitting at the end of it: these are what the app is FOR,
 * and everything above them — balance, people, offers, bills, history — is context for the
 * moment somebody actually wants to pay. Scrolling to reach the point of the app is the wrong
 * shape. Home only, because on any other screen there is already something to do.
 *
 * **One object, split in two**, rather than two buttons side by side. Two separate pills read as
 * a pair of unrelated choices; a single capsule divided by a hairline reads as one control with
 * two ways in, which is what this is — both halves start a payment, and the only difference is
 * whether the other person is in front of you or on a screen.
 *
 * The layout is mirrored about that divider: each icon sits on its own OUTER edge with the
 * labels meeting in the middle. It keeps the two marks as far apart as the bar allows, so
 * neither is read as belonging to the wrong side, and it gives the whole thing a centre.
 *
 * Labels break onto two lines deliberately. One long line forces small type across a wide bar;
 * two short ones let the type be big enough to read at a glance, which is the only way anybody
 * reads a control they are reaching for rather than looking at.
 *
 * Tap is the dark half — the heavier of the two, because it is the thing AirPay does that
 * nothing else does. A QR reader is table stakes, and making them look equal would be a claim
 * about the product that is not true.
 */
@Composable
internal fun PayBar(modifier: Modifier, onTap: () -> Unit, onScan: () -> Unit) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // No floor and no backdrop: the capsule floats and the page runs under it. It used to
        // sit on a white strip with a gradient above it, which stopped content showing through
        // — at the cost of a white slab across the bottom of every screenshot.

        // A white ring around the capsule, under a soft shadow. On a white page the ring is
        // invisible as a colour and does its work as a gap: it holds the dark shape off the
        // shadow so the bar reads as lifted rather than smudged.
        //
        // Width comes from the content, not the page. Stretched edge to edge the two halves
        // were mostly empty and the labels ended up far from the seam that joins them — which
        // is the one detail making this read as a single control.
        // At a large font scale the two-line labels stop fitting across the screen. Measured
        // rather than guessed from `fontScale`: the labels, the icon, the padding and the ring
        // are all real numbers, and a threshold on the scale would be a different wrong answer
        // on every device. When they will not fit, the second line goes and "Pay" · "Scan"
        // carry it alone.
        val measurer = rememberTextMeasurer()
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val widest: Int = remember(measurer, maxWidth) {
                listOf("and pay", "any QR").maxOf { label ->
                    val laid = measurer.measure(label, PayBarLabel)
                    laid.size.width
                }
            }
            val needed = with(LocalDensity.current) {
                // Each half: 16dp + icon 24 + gap 12 + label + 16dp. Plus the seam and the ring.
                (2 * ((16 + 24 + 12 + 16).dp.roundToPx() + widest) +
                    PayBarGap.roundToPx() + 2 * PayBarRing.roundToPx()).toDp()
            }
            // Against the page's content width, not the screen. The bar floats over a page
            // with a 20dp gutter, and a control that runs edge to edge reads as stuck to the
            // sides rather than floating over them.
            val twoLine = needed <= maxWidth - Space.gutter * 2

        Row(
            Modifier.cardShadow(PayBarRadius)
                .clip(RoundedCornerShape(PayBarRadius)).background(D.White)
                .padding(PayBarRing),
            // The seam is the ring carried through the middle, only tighter — narrow enough
            // that the halves read as joined, wide enough to still be a join and not a crack.
            horizontalArrangement = Arrangement.spacedBy(PayBarGap),
        ) {
            PayChoice(
                first = "Tap", second = if (twoLine) "and pay" else null,
                icon = R.drawable.hi_nfc, iconFirst = true,
                fill = D.Black, shape = PayBarLeft, onClick = onTap,
            )
            PayChoice(
                first = "Scan", second = if (twoLine) "any QR" else null,
                icon = R.drawable.hi_scan, iconFirst = false,
                fill = D.Blue, shape = PayBarRight, onClick = onScan,
            )
        }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** Concentric, the way every other tray and card in the app is: outer = inner + the ring. */
private val PayBarHeight = 60.dp
private val PayBarRing = 5.dp
/** The seam, narrower than the ring so the two halves sit closer without touching. */
private val PayBarGap = 3.dp
private val PayBarRadius = PayBarHeight / 2 + PayBarRing

/**
 * The label, defined once because it is both measured and drawn.
 *
 * It is `Type.heading` — 19/24 bold. 19, not 17, because white on `D.Blue` is 3.30:1 and
 * passes only as large text, so the label on the blue half has to BE large text; and the step
 * rather than a hand-built copy of it, because a bar with its own TextStyle is a seventh size
 * on a six-size scale. Two lines of 24 are 48 inside the 72 bar, 12 to spare above and below.
 * If the measured and the drawn label ever drift, the bar measures one thing and draws another.
 */
private val PayBarLabel = Type.heading

/**
 * The corners where the two halves meet.
 *
 * Square inner edges made the seam look like a cut through one shape rather than the join
 * between two, and a hard corner against a soft outer one is the sort of mismatch that reads as
 * unfinished even when nobody can say why.
 *
 * Small, though — just enough to take the edge off. At 24dp the halves started looking like two
 * separate pills that had been pushed together, which undoes the thing the bar is for: one
 * control with two ways in, not two buttons that happen to touch.
 */
private val PayBarInner = 10.dp

/** How long the balance's reveal animation stays quiet after it has played. */
private const val BALANCE_ANIM_GAP_MS = 60 * 60 * 1000L
private val PayBarEnd = PayBarHeight / 2
private val PayBarLeft = RoundedCornerShape(
    topStart = PayBarEnd, bottomStart = PayBarEnd, topEnd = PayBarInner, bottomEnd = PayBarInner,
)
private val PayBarRight = RoundedCornerShape(
    topStart = PayBarInner, bottomStart = PayBarInner, topEnd = PayBarEnd, bottomEnd = PayBarEnd,
)

/**
 * One half of the bar.
 *
 * [iconFirst] is what mirrors the two sides about the divider — it decides whether the mark
 * leads or trails, and the label hugs the middle either way.
 */
@Composable
private fun PayChoice(
    first: String,
    /** Null when the two-line labels no longer fit across the screen. */
    second: String?,
    @DrawableRes icon: Int,
    iconFirst: Boolean,
    fill: Color,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.heightIn(min = PayBarHeight).clip(shape).background(fill)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.inline),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pressing dims the half rather than scaling it. A scale would shrink one end of a
        // shape the eye reads as whole, which looks like it broke.
        val pressed by interaction.collectIsPressedAsState()
        val ink by animateColorAsState(
            if (pressed) D.White.copy(alpha = .55f) else D.White,
            spring(Snap.dampingRatio, Snap.stiffness), label = "ink",
        )
        // 24 is the action size on the icon scale — it was 26, which is not a size on it.
        if (iconFirst) HIcon(icon, ink, 24.dp)
        Column {
            Text(first, color = ink, style = PayBarLabel, maxLines = 1)
            if (second != null) Text(second, color = ink, style = PayBarLabel, maxLines = 1)
        }
        if (!iconFirst) HIcon(icon, ink, 24.dp)
    }
}

/**
 * The five categories, as the drawable that draws each one.
 *
 * These are the bill-tile vectors from checkpoint 1 §5.4, with both colours baked in — an ink
 * outline and one detail in blue. They are never tinted: a `ColorFilter` would flatten the pair
 * to one colour and lose the detail that tells them apart.
 */
internal fun spendIcon(spend: History.Spend): Int = when (spend) {
    History.Spend.PHONE -> R.drawable.ic_recharge
    History.Spend.MOVIES -> R.drawable.ic_movie
    History.Spend.FOOD -> R.drawable.ic_meal
    History.Spend.SHOPPING -> R.drawable.ic_bag
    History.Spend.OTHER -> R.drawable.ic_more
}

internal fun spendLabel(spend: History.Spend): String = when (spend) {
    History.Spend.PHONE -> "Phone"
    History.Spend.MOVIES -> "Movies"
    History.Spend.FOOD -> "Food"
    History.Spend.SHOPPING -> "Shopping"
    History.Spend.OTHER -> "Other"
}

/** The order the picker offers them in (ui/PORT-2026-10-01-2.md §1.4). */
internal val SPEND_ORDER = listOf(
    History.Spend.FOOD, History.Spend.SHOPPING, History.Spend.PHONE,
    History.Spend.MOVIES, History.Spend.OTHER,
)

/** Every row's icon sits in this box, so every title starts at the same x. */
private val ICON_BOX = 40.dp

/**
 * One line of history.
 *
 * **The icon says WHO or WHAT, never which way the money went** (§1.2). The arrow for money out
 * and the green tick disc for money in are both gone: the amount already carries the sign and
 * the colour, and an icon repeating it spent the one place on the row that could have said
 * something new. A person is their face, a shop is its brand or its category, and a payment to
 * an address nobody has filed is a dashed "+" that asks.
 *
 * The amount carries the sign because that is the thing being scanned for — a column of figures
 * where in and out look alike has to be read word by word.
 */
@Composable
private fun TxnRow(t: History.Txn, onFile: (History.Spend) -> Unit = {}, onOpen: () -> Unit) {
    var asking by remember(t.signature) { mutableStateOf(false) }
    // Only a payment OUT to an address with no category can be filed. Money in is not an
    // expense, and a shop order came off a shelf that already said what it was.
    val fileable = t.who == History.Who.ADDRESS && !t.incoming && t.spend == null

    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onOpen)
            .padding(vertical = Space.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(ICON_BOX), contentAlignment = Alignment.Center) {
            when {
                // A face for anyone with a wallet — a contact, or a stranger who paid us. The
                // same coloured disc §4.4 gives People, seeded on the address so one person is
                // one colour everywhere.
                t.who == History.Who.PERSON || (t.who == History.Who.ADDRESS && t.incoming) ->
                    FaceDisc(t.title, t.address, ICON_BOX)
                t.kind == History.Kind.REWARD -> Box(
                    Modifier.size(36.dp).clip(CircleShape).background(D.Blue),
                    contentAlignment = Alignment.Center,
                ) { HiIcon(Hi.Gift, D.White, 20.dp) }
                t.brand != null -> Image(
                    painterResource(t.brand), contentDescription = null,
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)),
                    contentScale = ContentScale.Crop,
                )
                t.spend != null -> CategoryIcon(t.spend, ICON_BOX)
                else -> AddCategory(enabled = fileable) { asking = true }
            }
            // Where their name comes from, as a small mark on the face: the Seeker mark for a
            // .skr name (so they hold a Genesis Token), the name service's for .sol, AirPay's own
            // for .air. The face keeps their initial and colour, so two .skr people still differ.
            val isPerson = t.who == History.Who.PERSON || (t.who == History.Who.ADDRESS && t.incoming)
            val named = if (!isPerson) null else {
                val ctx = LocalContext.current
                t.title.takeIf { registryOf(it) != null }
                    ?: t.address?.let { Identity.domain(ctx, it) }?.takeIf { registryOf(it) != null }
            }
            val busyBadge = t.state == History.State.CONFIRMING || t.state == History.State.PREPARING
            if (named != null && !busyBadge) {
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp)
                        .size(22.dp).clip(CircleShape).background(D.White).padding(2.dp),
                ) { RegistryMark(named, 18.dp) }
            }
            // The in-progress badge, on the icon's bottom end corner and pushed 2dp outside it.
            // Finished rows carry none — a badge on everything says nothing.
            if (t.state == History.State.CONFIRMING || t.state == History.State.PREPARING) {
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp)
                        .size(20.dp).clip(CircleShape).background(D.White).padding(2.dp)
                        .clip(CircleShape).background(D.Blue),
                    contentAlignment = Alignment.Center,
                ) { HiIcon(Hi.Clock, D.White, 12.dp) }
            }
        }
        Spacer(Modifier.width(Space.inline))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = D.Black, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Space.tight))
            StateLine(t, fileable) { asking = true }
        }
        Spacer(Modifier.width(Space.inline))
        Column(horizontalAlignment = Alignment.End) {
            // Money in is the success ink. Money out is INK, never red — spending is not an
            // error, and the sign is what is being scanned for.
            Text(
                (if (t.incoming) "+" else "\u2212") + t.amount,
                color = if (t.incoming) D.SuccessInk else D.Black,
                style = Type.body.figure(), maxLines = 1,
            )
            Spacer(Modifier.height(Space.tight))
            Text(ago(t.at), color = D.Quiet, style = Type.caption.figure(), maxLines = 1)
        }
    }

    if (asking) {
        SpendPicker(t.title, onPick = { onFile(it); asking = false }) { asking = false }
    }
}

/**
 * The line under the title: one fixed sentence per state, the same in both lists (§1.3).
 *
 * Fixed, because the same transaction read two different ways in two places is the thing that
 * makes somebody check their wallet against the app.
 */
@Composable
private fun StateLine(t: History.Txn, fileable: Boolean, onFile: () -> Unit) {
    val quiet = Type.caption
    when {
        // Rewards first: they are not shop orders, and the lines below ("Confirming on Solana",
        // "Paid · getting your code") are about shop orders. A reward row was reading the second
        // one because its state matched before it was ever asked what it was.
        t.kind == History.Kind.REWARD && t.category == "Payout" ->
            Text(if (t.state == History.State.CONFIRMING) "Sending" else "Sent", color = D.Quiet, style = quiet, maxLines = 1)
        t.kind == History.Kind.REWARD ->
            Text("Earned", color = D.Quiet, style = quiet, maxLines = 1)
        // An order that did not arrive must not read "You paid" like one that did.
        t.state == History.State.FAILED ->
            Text("Didn't go through", color = D.DangerInk, style = quiet, maxLines = 1)
        t.state == History.State.CONFIRMING ->
            Text("Confirming on Solana", color = D.BlueInk, style = quiet, maxLines = 1)
        t.state == History.State.PREPARING ->
            Text("Paid · getting your code", color = D.BlueInk, style = quiet, maxLines = 1)
        t.state == History.State.DELIVERED && t.code != null ->
            Text("Code ready · tap to see it", color = D.Quiet, style = quiet, maxLines = 1)
        t.state == History.State.DELIVERED ->
            Text("Delivered", color = D.Quiet, style = quiet, maxLines = 1)
        t.state == History.State.REFUNDED ->
            Text("Refunded to your wallet", color = D.Quiet, style = quiet, maxLines = 1)
        t.incoming ->
            Text("Paid you", color = D.Quiet, style = quiet, maxLines = 1)
        // "You paid · Add a category" — the second half is a link, so it is its own Text.
        fileable -> Row {
            Text("You paid · ", color = D.Quiet, style = quiet, maxLines = 1)
            Text(
                "Add a category", color = D.BlueInk, style = quiet, maxLines = 1,
                modifier = Modifier.clickable(onClick = onFile),
            )
        }
        t.spend != null ->
            Text("You paid · ${spendLabel(t.spend)}", color = D.Quiet, style = quiet, maxLines = 1)
        else ->
            Text("You paid", color = D.Quiet, style = quiet, maxLines = 1)
    }
}

/**
 * The "+" that asks what a payment was for.
 *
 * Dashed, because the circle is a blank waiting to be filled rather than a thing in its own
 * right — every other icon on this row is a statement, and this one is a question.
 */
@Composable
private fun AddCategory(enabled: Boolean, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
    Box(
        Modifier.then(if (enabled) PressScale(press) else Modifier)
            .size(ICON_BOX)
            .clickable(press, indication = null, enabled = enabled, onClick = onClick)
            .drawBehind {
                drawCircle(
                    D.Neutral,
                    radius = size.minDimension / 2 - 0.75.dp.toPx(),
                    style = Stroke(1.5.dp.toPx(), pathEffect = dash),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val arm = w * .3f
            val stroke = Stroke(markStroke(20.dp) * w / 24f, cap = StrokeCap.Round)
            drawLine(D.BlueInk, Offset(w / 2 - arm, w / 2), Offset(w / 2 + arm, w / 2),
                strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(D.BlueInk, Offset(w / 2, w / 2 - arm), Offset(w / 2, w / 2 + arm),
                strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

/**
 * A category icon at its optical size.
 *
 * The five vectors were drawn at one scale and their inked areas differ a lot, so at one box
 * size the phone and More look small beside the ticket. [opticalScale] sizes each so they cover
 * the same area (§1.5).
 */
/**
 * A category icon at its optical size, from Icons.kt.
 *
 * `CategoryMark` and not a local `Image`: the five vectors were drawn at one scale and their
 * inked areas differ by nearly half, so each has to be scaled so `sqrt(w * h)` matches or the
 * phone reads a size smaller than the ticket beside it (§1.5). It also uses `requiredSize`
 * rather than `size` — `size` is coerced into the parent's already-fixed constraints and
 * clamps the scaled grid straight back to the box, which silently undoes the whole thing. The
 * first version here made exactly that mistake.
 */
@Composable
private fun CategoryIcon(spend: History.Spend, box: Dp, optical: Float = CatMark.ROW_OPTICAL) {
    val mark = CatMark.forCategory(spendLabel(spend)) ?: return
    CategoryMark(mark, box, optical)
}


/**
 * "What was this?" — the five categories, one tap to file.
 *
 * It only ever appears for a payment the app cannot explain by itself, and the answer never
 * leaves the phone, which is what the second line says.
 */
@Composable
private fun SpendPicker(title: String, onPick: (History.Spend) -> Unit, onClose: () -> Unit) {
    Tray(onClose) {
        Text("What was this?", color = D.Black, style = Type.heading)
        Spacer(Modifier.height(Space.tight))
        Text(
            "Your payment to $title. Only you see this.",
            color = D.Quiet, style = Type.caption,
        )
        Spacer(Modifier.height(Space.section))
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SPEND_ORDER.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEach { spend ->
                        val press = remember { MutableInteractionSource() }
                        Column(
                            Modifier.weight(1f).then(PressScale(press))
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(press, indication = null) { onPick(spend) }
                                .padding(vertical = Space.tight),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CatMark.forCategory(spendLabel(spend))?.let {
                                CategoryMark(it, CatMark.PickerBox, CatMark.PICKER_OPTICAL)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(spendLabel(spend), color = D.Black, style = Type.caption)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Spacer(Modifier.height(Space.section))
    }
}

/** The home screen's tail: the last three things that moved money, and a way to the rest. */
@Composable
private fun HistoryStrip(
    list: List<History.Txn>,
    onViewAll: () -> Unit,
    onFile: (History.Txn, History.Spend) -> Unit,
    onOpen: (History.Txn) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Transaction history", color = D.Black, style = Type.heading)
            Spacer(Modifier.weight(1f))
            // Nothing to view yet, so no link to it.
            if (list.isNotEmpty()) Box(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onViewAll)
                    .heightIn(min = 48.dp).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text("View all", color = D.BlueInk, style = Type.body) }
        }
        Spacer(Modifier.height(Space.lead))
        if (list.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = Space.section), contentAlignment = Alignment.Center) {
                Text("No transactions yet", color = D.Quiet, style = Type.body, textAlign = TextAlign.Center)
            }
            return@Column
        }
        // Split by a hairline rather than boxed, and no trailing line under the last row.
        val rows = list.take(3)
        rows.forEachIndexed { i, t ->
            TxnRow(t, onFile = { onFile(t, it) }) { onOpen(t) }
            if (i < rows.lastIndex) RowLine()
        }
    }
}

private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL yyyy")

/**
 * The whole history, one month at a time.
 *
 * A month rather than an endless scroll because that is the unit people ask a payments app
 * about — "what did I spend in August" — and it gives the totals at the top something honest
 * to be totals OF. The chevrons walk; the title opens the list, for when the answer is eleven
 * months back.
 */
@Composable
private fun HistoryTab(
    list: List<History.Txn>,
    onFile: (History.Txn, History.Spend) -> Unit,
    month: YearMonth,
    onMonth: (YearMonth) -> Unit,
    onOpen: (History.Txn) -> Unit,
) {
    val months = History.months(list)
    val here = months.indexOf(month).takeIf { it >= 0 } ?: 0
    // The list is newest first, so "back a month" is forward through it.
    val older = months.getOrNull(here + 1)
    val newer = months.getOrNull(here - 1)
    val rows = list.filter { it.month == month }
    var picking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Chevron(R.drawable.hi_arrow_left, older != null, "Previous month") { older?.let(onMonth) }
            Text(
                month.format(MONTH),
                color = D.Black, style = Type.heading,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .clickable { picking = true }.heightIn(min = 48.dp).padding(vertical = 12.dp),
            )
            // Mirrored, because there is no right chevron in the set and it is the same arrow.
            Chevron(R.drawable.hi_arrow_left, newer != null, "Next month", flip = true) { newer?.let(onMonth) }
        }

        if (rows.isEmpty()) {
            // In the middle of what is left of the screen, like the notifications page's empty
            // state. It sat at the top left under the month, where it read as a stray line.
            Column(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = Space.section),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Nothing in ${month.format(MONTH)}", color = D.Black, style = Type.body, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Space.tight))
                Text("Payments, requests you were paid, and anything you bought will show up here.",
                    color = D.Quiet, style = Type.caption, textAlign = TextAlign.Center)
            }
        } else {
            // Counted, not summed: the rows carry several tokens and a single figure adding
            // SOL to USDC to rupees would be a number that means nothing.
            Text(
                "${rows.size} transaction${if (rows.size == 1) "" else "s"}".uppercase(),
                color = D.Quiet, style = Type.micro,
                modifier = Modifier.padding(top = Space.tight, bottom = Space.lead),
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = rememberHeldList("history:$month")) {
                itemsIndexed(rows) { i, t ->
                    TxnRow(t, onFile = { onFile(t, it) }) { onOpen(t) }
                    if (i < rows.lastIndex) RowLine()
                }
            }
        }
    }

    if (picking) {
        MonthSheet(months, month, onPick = { picking = false; onMonth(it) }, onClose = { picking = false })
    }
}

@Composable
private fun Chevron(
    @DrawableRes icon: Int,
    enabled: Boolean,
    /** Said out loud: an arrow on its own says nothing about which way it walks. */
    label: String,
    flip: Boolean = false,
    onClick: () -> Unit,
) {
    // 24 in a 48dp hit area, on the page rather than in a grey disc.
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        HIcon(icon, if (enabled) D.Black else D.Line, 24.dp,
            if (flip) Modifier.graphicsLayer { rotationZ = 180f } else Modifier)
    }
}

/** Every month since the first transaction, for when walking back one at a time is silly. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonthSheet(
    months: List<YearMonth>,
    current: YearMonth,
    onPick: (YearMonth) -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = D.White, dragHandle = null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 20.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(D.Line, CircleShape))
            Spacer(Modifier.height(16.dp))
            Text("Jump to a month", color = D.Black, style = Type.title)
            Spacer(Modifier.height(Space.lead))
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(months) { m ->
                    val on = m == current
                    // The pale blue wash behind the current month is gone: the blue INK and
                    // the tick say which one it is, and the tick says it without colour.
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable { onPick(m) }.padding(vertical = Space.row),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(m.format(MONTH), color = if (on) D.BlueInk else D.Black,
                            style = Type.body)
                        Spacer(Modifier.weight(1f))
                        if (on) HiIcon(Hi.Tick, D.BlueInk, 20.dp)
                    }
                }
            }
            Spacer(Modifier.height(Space.lead))
        }
    }
}



/**
 * The gap between Home's sections. `Space.section` is 40: right between the parts of a long
 * reading page, too much on a screen that is a stack of short separate rows. Home-only on
 * purpose — every other screen keeps the system's role.
 */
private val HOME_GAP = 28.dp

@Composable
private fun SectionLabel(text: String) {
    // `Type.micro` IS 11/14/700 at +1.5 tracking — this was the same thing spelled out by
    // hand, which is how a step drifts from the scale without anyone touching the scale.
    Text(text, color = D.Quiet, style = Type.micro)
    Spacer(Modifier.height(8.dp))
}

/**
 * A list row, now that the grey body is gone (ui/PORT.md §9).
 *
 * The box is what used to say "these belong together"; with no containers that job goes to
 * space and a [RowLine] under the row. `Space.row` top and bottom makes a one-line row 52dp,
 * clear of the 48dp touch minimum, and `heightIn` keeps it clear when text grows to 2x.
 */
private val RowStyle = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = Space.row)

/** The hairline that replaced the row box. Decorative — never the only boundary. */
@Composable
internal fun RowLine() = Box(Modifier.fillMaxWidth().height(1.dp).background(D.Line))

/**
 * An amount: the figure face with tabular figures, at whatever step the caller chose.
 *
 * The one place "is this money?" is answered, so it is not re-decided on every call site. An
 * amount inside a sentence is NOT one of these — switching face mid-sentence reads as a
 * rendering fault.
 */

/** An identifier at body size — a card number, a redeem link: read character by character. */
private val MonoBody = Type.body.monospaced()

/**
 * A count on a badge. Tabular, so 9 → 10 does not shift the disc, and without micro's label
 * tracking, which pushes a lone digit off centre.
 */
// `internal`: the header's unread badge and §3's waiting count are the same object — a
// figure on a disc — and now live in different files.
internal val CountStyle = Type.micro.copy(letterSpacing = 0.sp, fontFeatureSettings = "tnum")

/**
 * The name this wallet carries, and how it got one.
 *
 * A `.skr` belongs to the address that holds it, so a second wallet is a stranger by default —
 * you receive money into it and the payer sees hex. The fix is a signature from the wallet that
 * *does* hold the name, naming this one ([Vouch]). One approval, and it holds for months.
 *
 * Four states, and the wording of each is the point:
 *
 *  * **Owns it** — the name is on this address. Nothing to do.
 *  * **Borrowed** — a verified link, drawn as a fact but a *different* fact, with the wallet it
 *    came from named and the date it lapses shown rather than hidden.
 *  * **Can be linked** — you own a name elsewhere, so this offers to carry it over.
 *  * **Nothing to borrow** — no wallet here has a name, so there is nothing to offer and the
 *    panel says what would change that instead of dangling a button that cannot work.
 */
@Composable
private fun WalletIdentity(w: Linked, linking: Boolean, onLink: () -> Unit) {
    val context = LocalContext.current
    val mine = Identity.domain(context, w.address)
    val link = remember(w.address, linking) { Vouch.of(context, w.address) }
    val owner = remember(w.address, linking) {
        // The wallet that could vouch — any linked wallet with a name that is not this one.
        Identity.named(context, Wallets(context).all().map { it.address }.filter { it != w.address })
    }

    // No well behind it, tinted or grey: the label, the name and the tick carry this block,
    // and a pale blue wash for the verified case was the last thing on the sheet saying
    // "verified" in colour alone.
    Column(Modifier.fillMaxWidth()) {
        Text("SEEN AS", color = D.Quiet, style = Type.micro)
        Spacer(Modifier.height(Space.tight))

        when {
            mine != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(mine, color = D.Black, style = Type.heading, maxLines = 1)
                    Spacer(Modifier.width(Space.tight))
                    HiIcon(Hi.Verified, D.BlueInk, 20.dp)
                }
                Spacer(Modifier.height(Space.tight))
                Text(
                    "This wallet owns the name, so everyone you tap sees it. It can also lend " +
                        "it to your other wallets.",
                    color = D.Quiet, style = Type.caption,
                )
            }

            link != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Identity.domain(context, link.owner) ?: link.owner.take(6) + "…",
                        color = D.Black, style = Type.heading, maxLines = 1,
                    )
                    Spacer(Modifier.width(Space.tight))
                    HiIcon(Hi.Verified, D.BlueInk, 20.dp)
                }
                Spacer(Modifier.height(Space.tight))
                Text(
                    "Lent to this wallet by ${link.owner.take(4)}…${link.owner.takeLast(4)}, " +
                        "and signed, the other phone checks it rather than taking our word. " +
                        "Good until ${link.expiresOn}.",
                    color = D.Quiet, style = Type.caption,
                )
            }

            owner != null -> {
                Text(w.short, color = D.Quiet, style = MonoBody)
                Spacer(Modifier.height(Space.tight))
                Text(
                    "Just an address right now. ${owner.second} can vouch for it, so people " +
                        "you tap from this wallet see your name instead. One approval in that " +
                        "wallet, nothing spent.",
                    color = D.Quiet, style = Type.caption,
                )
                Spacer(Modifier.height(Space.lead))
                PrimaryButton(
                    if (linking) "Waiting for ${owner.second}…" else "Use ${owner.second} here",
                    enabled = !linking,
                    onClick = onLink,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> {
                Text(w.short, color = D.Quiet, style = MonoBody)
                Spacer(Modifier.height(Space.tight))
                Text(
                    "Just an address. None of your wallets owns a .skr or .sol yet, every " +
                        "Seeker comes with a .skr, and any wallet that has one can lend it to " +
                        "the rest.",
                    color = D.Quiet, style = Type.caption,
                )
            }
        }
    }
}

/**
 * How you are about to appear to whoever pays this request.
 *
 * Placed here rather than in onboarding on purpose: asking to be paid is the moment you most
 * want to be trusted, so it is the moment the difference between the two kinds of name is worth
 * anything. Everywhere else it is a nag.
 *
 * Three states, and it never blocks any of them. Asking for money is not gated on owning a
 * domain — a wall there would undo the whole point of a tap working with any Android phone, and
 * it would put the friction on the person who has done nothing wrong. The friction belongs on
 * the *payer's* screen, where an unverified name is drawn as the claim it is.
 */
@Composable
private fun HowYouLook(domain: String?, borrowed: Boolean, username: String, onEdit: () -> Unit) {
    val verified = domain != null
    // No card behind it: neither a pale blue one for the verified case nor a grey one for the
    // rest. The tick is what marks the difference, which is the whole point of this block.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(onClick = onEdit).padding(vertical = Space.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("They will see", color = D.Quiet, style = Type.caption)
            Spacer(Modifier.height(Space.tight))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    domain ?: username.ifBlank { "your address" },
                    color = if (verified || username.isNotBlank()) D.Black else D.Quiet,
                    style = Type.body, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (verified) {
                    Spacer(Modifier.width(Space.tight))
                    HiIcon(Hi.Verified, D.BlueInk, 20.dp)
                }
            }
            Spacer(Modifier.height(Space.tight))
            Text(
                when {
                    // A borrowed name is just as checkable and is not the same claim, so it
                    // says where it came from instead of implying this wallet owns it.
                    verified && borrowed -> "Lent by your other wallet, and signed, their phone checks it."
                    verified -> "Yours on chain, their phone checks it, you can't be copied."
                    username.isNotBlank() -> "No tick: they only have your word for this name."
                    else -> "Nobody will know who asked. Tap to pick a name."
                },
                color = D.Quiet, style = Type.caption,
            )
        }
        if (!verified) {
            Spacer(Modifier.width(Space.inline))
            Text("Change", color = D.BlueInk, style = Type.body)
        }
    }
}

/**
 * Naming somebody else — the only name in the app that cannot be wrong.
 *
 * It never crossed the radio, nobody claimed it about themselves, and you wrote it while they
 * were standing in front of you. That is why it beats both the name their phone sent and the
 * one the chain resolved: those answer "who does this address say it is", and this answers
 * "who is this, to me".
 *
 * It stays on this phone. A directory mapping people to wallet addresses is exactly the kind of
 * thing that should never leave the device, which is already [Contacts]' rule.
 */
@Composable
internal fun ContactNameDialog(
    called: Called,
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current) }
    val problem = Username.problem(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = D.White,
        title = { Text("Name this person", color = D.Black, style = Type.title) },
        text = {
            Column {
                // What everyone else would see, so it is clear what is being overridden.
                if (called.domain != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(called.domain, color = D.Black, style = Type.body)
                        Spacer(Modifier.width(Space.tight))
                        HiIcon(Hi.Verified, D.BlueInk, 20.dp)
                    }
                    Spacer(Modifier.height(Space.tight))
                    Text(
                        "Their name on chain. Yours is just for you, this one still shows " +
                            "underneath.",
                        color = D.Quiet, style = Type.caption,
                    )
                } else {
                    Text(
                        "Kept on this phone, and only you see it.",
                        color = D.Quiet, style = Type.caption,
                    )
                }
                Spacer(Modifier.height(Space.lead))
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= Username.MAX) text = it },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = {
                        Text(
                            problem ?: "Leave it empty to go back to their own name.",
                            color = if (problem != null) D.DangerInk else D.Quiet,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = problem == null) {
                Text("Save", color = D.BlueInk, style = Type.heading)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = D.Quiet, style = Type.body) }
        },
    )
}

/**
 * Naming yourself.
 *
 * Two quite different things can happen here, and the dialog says which one you got:
 *
 *  * **You own a `.skr` or `.sol`.** Then there is nothing to type. The other phone resolves it
 *    from the chain and shows it with a tick, and a username would only sit underneath saying
 *    something weaker. Every Seeker gets a `.skr` with the device, so this is the normal case
 *    for the phones this app was built for.
 *  * **You don't.** Then you can type a name so people have something to remember you by — but
 *    it travels over the radio as your word for it, arrives without a tick, and the dialog says
 *    so rather than letting it look like the first case.
 *
 * The dot rule is enforced here and again on the wire: a username containing one could pose as
 * a domain and borrow a tick it never earned. See [Username].
 */
@Composable
private fun NameDialog(
    current: String,
    domain: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current) }
    val problem = Username.problem(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = D.White,
        title = {
            Text(if (domain != null) "Your name" else "Pick a name",
                color = D.Black, style = Type.title)
        },
        text = {
            Column {
                if (domain != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(domain, color = D.Black, style = Type.heading)
                        Spacer(Modifier.width(Space.tight))
                        HiIcon(Hi.Verified, D.BlueInk, 20.dp)
                    }
                    Spacer(Modifier.height(Space.tight))
                    Text(
                        "This is yours on chain, so everyone you tap sees it with the tick. " +
                            "Nothing to set up.",
                        color = D.Quiet, style = Type.caption,
                    )
                } else {
                    Text(
                        "Shown on the other phone when you tap someone.",
                        color = D.Quiet, style = Type.caption,
                    )
                    Spacer(Modifier.height(Space.lead))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { if (it.length <= Username.MAX) text = it },
                        singleLine = true,
                        isError = problem != null,
                        supportingText = {
                            Text(
                                problem ?: "${text.trim().length}/${Username.MAX}",
                                color = if (problem != null) D.DangerInk else D.Quiet,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Space.lead))
                    // The nudge, stated as what it actually buys rather than as "security":
                    // a username only exists inside this app, and a domain does not.
                    Text(
                        "A name you type arrives without a tick, they only have your word " +
                            "for it. A .skr or .sol is yours on chain: it can't be copied, it " +
                            "works in every Solana app, and people can pay you by name from " +
                            "anywhere. Every Seeker comes with a .skr.",
                        color = D.Quiet, style = Type.caption,
                    )
                }
            }
        },
        confirmButton = {
            if (domain != null) {
                TextButton(onClick = onDismiss) {
                    Text("Done", color = D.BlueInk, style = Type.heading)
                }
            } else {
                TextButton(
                    onClick = { onSave(text) },
                    enabled = problem == null && Username.clean(text) != null,
                ) { Text("Save", color = D.BlueInk, style = Type.heading) }
            }
        },
        dismissButton = {
            if (domain == null) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = D.Quiet, style = Type.body) }
            }
        },
    )
}


/**
 * Scan, pay by name, history, and a fourth that depends on the phone: a Seeker owner can buy
 * SKR, anybody else is shown the Seeker. Round buttons with a label under each — blue with a
 * white icon for the first three, the pay bar's black for the last.
 */
@Composable
internal fun HomeActions(
    isSeeker: Boolean,
    onBuy: (String) -> Unit,
    onScan: () -> Unit,
    onPayAnyone: () -> Unit,
    onHistory: () -> Unit,
) {
    class Action(val icon: Int, val label: String, val fill: Color, val white: Boolean, val size: Int, val click: () -> Unit)
    val actions = listOf(
        Action(R.drawable.ic_action_scan, "Scan", D.Blue, true, 30, onScan),
        Action(R.drawable.ic_action_pay, "Pay anyone", D.Blue, true, 34, onPayAnyone),
        Action(R.drawable.ic_action_history, "History", D.Blue, true, 30, onHistory),
        if (isSeeker) Action(R.drawable.skr_token, "Buy SKR", D.Black, false, 40) {
            onBuy("https://jup.ag/swap/SOL-SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3")
        } else Action(R.drawable.ic_action_seeker, "Buy Seeker", D.Black, true, 30) { onBuy("https://solanamobile.com/seeker") },
    )
    Row(Modifier.fillMaxWidth()) {
        actions.forEach { a ->
            val press = remember { MutableInteractionSource() }
            Column(
                Modifier.weight(1f).then(PressScale(press)).clickable(press, indication = null, onClick = a.click),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(62.dp).clip(CircleShape).background(a.fill), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(a.icon), null, Modifier.size(a.size.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(a.label, color = D.Black, style = Type.caption, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}


/** How a network looks in a list: a short mark on its colour. Drawn, so it needs no network. */
private class OperatorMark(val name: String, val letters: String, val color: Color)

private fun operatorMark(product: String): OperatorMark {
    val p = product.lowercase()
    return when {
        "jio" in p -> OperatorMark("Jio", "Jio", Color(0xFF0A4DA2))
        "airtel" in p -> OperatorMark("Airtel", "a", Color(0xFFE40000))
        "vodafone" in p || p.startsWith("vi ") || p == "vi" || "idea" in p -> OperatorMark("Vi", "Vi", Color(0xFFEE3023))
        "bsnl" in p -> OperatorMark("BSNL", "B", Color(0xFF1B7F3A))
        else -> OperatorMark(product.removeSuffix(" India").removeSuffix(" Bundles"), product.take(1).uppercase(), D.Blue)
    }
}


/** The four networks' real logos, prepared to fill a circle edge to edge. Null for any other. */
private fun operatorLogoRes(product: String): Int? {
    val p = product.lowercase()
    return when {
        "jio" in p -> R.drawable.net_jio
        "airtel" in p -> R.drawable.net_airtel
        "vodafone" in p || p.startsWith("vi ") || p == "vi" || "idea" in p -> R.drawable.net_vi
        "bsnl" in p -> R.drawable.net_bsnl
        else -> null
    }
}


/**
 * One purchase from the shop, as the four things it can be: about to be paid, being paid, paid,
 * or stopped. It has a sheet of its own ([PurchaseSheet]) because each of those used to be a line
 * of text pushed onto the top of the page, where it looked like a log entry and not a receipt.
 */
internal sealed interface Purchase {
    val item: Item

    /** What is being bought. [phone] is the number a recharge credits, as +91…; null otherwise. */
    data class Item(
        val product: Shop.Product, val value: String, val category: String, val phone: String?,
        /** What the network publishes about this plan, when the server could read it. */
        val extra: Plans.Extra? = null,
    )

    data class Review(override val item: Item) : Purchase
    /** [price] is the invoice's own figure once there is one: "1.21 USDC". */
    data class Working(
        override val item: Item, val text: String,
        val payWith: Shop.PayWith = Shop.PayWith.USDC, val price: String? = null,
    ) : Purchase
    data class Done(
        override val item: Item, val paid: String, val signature: String, val invoiceId: String,
        val delivered: Boolean = false,
    ) : Purchase
    /**
     * [unsure] is the signature of a payment that was sent and not yet confirmed. Then this is
     * not a refusal, and the sheet must not offer to pay again.
     */
    data class Problem(
        override val item: Item, val title: String, val detail: String, val unsure: String? = null,
    ) : Purchase
}

@Composable
private fun PurchaseSheet(
    state: Purchase,
    onPay: (Shop.PayWith, Shop.Invoice?) -> Unit,
    quote: suspend (Shop.PayWith) -> Shop.Invoice?,
    sponsor: suspend () -> Chain.Sponsor?,
    have: (Shop.PayWith) -> Long?,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onExplorer: (String) -> Unit,
) {
    val item = state.item
    val name = item.product.name.removeSuffix(" India").removeSuffix(" Bundles")
    val recharge = item.phone != null
    val symbol = symbolFor(item.product.currency)
    // "₹500 top-up" for a bare amount, the plan's own words for a plan.
    val what = item.value.toDoubleOrNull()?.let { "$symbol${it.toLong()}" + if (recharge) " top-up" else "" } ?: item.value
    val pack = item.product.packages.firstOrNull { it.value == item.value }

    // Not dismissible mid-payment: closing the sheet would not stop the payment, only hide it.
    Tray(onClose = onClose, dismissible = state !is Purchase.Working) {
        Spacer(Modifier.height(Space.lead))
        val logo = if (recharge) operatorLogoRes(item.product.name) else null
        // What it is paid in. Kept by the sheet, so it survives a failed try and "Try again".
        var chosenPay by remember { mutableStateOf(Shop.PayWith.USDC) }
        val working = state as? Purchase.Working
        if (state is Purchase.Review || working != null) {
            // The review reads like a receipt written in advance: what it is, what it gives, what
            // comes with it, the small print, and one button that says the amount.
            //
            // Paying keeps this same layout. It used to swap to a spinner page, then swap its words
            // twice (price, approve), which read as the sheet flickering. Now only the button and
            // the "You pay" line change.
            val payWith = working?.payWith ?: chosenPay
            val ex = item.extra
            val price = pack?.let { "$symbol${it.price}" }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (recharge && price != null) "$price plan details" else if (price != null && item.value.toDoubleOrNull() != null) "$price $name" else name,
                    color = D.Black, style = Type.title, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                // No way out while it is being paid: closing would hide the payment, not stop it.
                if (working == null) Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { HiIcon(Hi.Close, D.Black, 22.dp, contentDescription = "Close") }
                else Spacer(Modifier.size(44.dp))
            }
            if (recharge) {
                Spacer(Modifier.height(Space.tight))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (logo != null) {
                        Image(painterResource(logo), null, Modifier.size(22.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("$name  ·  ${item.phone!!.replace("+91", "+91 ")}", color = D.Quiet, style = Type.caption)
                }
            }
            Spacer(Modifier.height(Space.lead))

            // Everything between the title and the button scrolls, so a plan with a long list of
            // benefits never pushes the button off the screen.
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                val detail = Plans.of(item.value)
                val validity = ex?.validity?.takeIf { it.isNotBlank() } ?: detail.validity?.replaceFirstChar { it.uppercase() }
                val data = ex?.data?.takeIf { it.isNotBlank() } ?: detail.data
                if (validity != null || data != null) {
                    Row(Modifier.fillMaxWidth()) {
                        validity?.let { SheetStat("Validity", it, Modifier.weight(1f)) }
                        data?.let {
                            val talktime = "talktime" in item.value.lowercase() || "top-up" in item.value.lowercase()
                            SheetStat(if (talktime) "Talktime" else "Data", it, Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(Space.lead))
                    RowLine()
                } else {
                    Text(what, color = D.Black, style = Type.body)
                    Spacer(Modifier.height(Space.lead))
                    RowLine()
                }

                // What comes with the plan, each with its own picture.
                if (ex != null && ex.subscriptions.isNotEmpty()) {
                    Spacer(Modifier.height(Space.lead))
                    Text("Additional benefits", color = D.Black, style = Type.heading)
                    val loader = svgCapable()
                    ex.subscriptions.forEachIndexed { i, sub ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            val shape = RoundedCornerShape(10.dp)
                            Box(
                                Modifier.size(40.dp).clip(shape).background(D.White).border(1.dp, PLAN_EDGE, shape),
                                contentAlignment = Alignment.Center,
                            ) {
                                var loaded by remember(sub.icon) { mutableStateOf(false) }
                                if (!loaded) Text(sub.title.take(1).uppercase(), color = D.Quiet, style = Type.caption)
                                if (sub.icon != null) {
                                    coil.compose.AsyncImage(
                                        model = sub.icon, contentDescription = null, imageLoader = loader,
                                        modifier = Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit,
                                        onSuccess = { loaded = true },
                                    )
                                }
                            }
                            Spacer(Modifier.width(Space.inline))
                            Text(sub.title, color = D.Black, style = Type.body, modifier = Modifier.weight(1f))
                        }
                        if (i < ex.subscriptions.lastIndex) RowLine()
                    }
                    RowLine()
                }

                // The plan in the network's own words, set out so it can be read down a column:
                // the facts as label and value, then each point of the description on its own line.
                val facts = ex?.details.orEmpty()
                val points = Plans.points(ex?.description?.removePrefix("Benefits:"))
                val notes = ex?.notes.orEmpty()
                if (facts.isNotEmpty() || points.isNotEmpty() || notes.isNotEmpty()) {
                    Spacer(Modifier.height(Space.lead))
                    Text("Plan details", color = D.Black, style = Type.heading)
                    Spacer(Modifier.height(Space.tight))
                    facts.forEach { (label, value) -> PurchaseFact(label, value) }
                    if (points.isNotEmpty()) {
                        Spacer(Modifier.height(Space.inline))
                        points.forEach { BulletLine(it, color = D.Black, style = Type.body) }
                    }
                    notes.forEach { note ->
                        // A note is often a heading followed by its own list.
                        val parts = Plans.points(note)
                        Spacer(Modifier.height(Space.inline))
                        if (parts.size > 1) {
                            Text(parts.first().trimEnd(':'), color = D.Black, style = Type.body)
                            Spacer(Modifier.height(Space.tight))
                            parts.drop(1).forEach { BulletLine(it) }
                        } else {
                            BulletLine(note)
                        }
                    }
                }
                Spacer(Modifier.height(Space.lead))
            }

            RowLine()
            Spacer(Modifier.height(Space.inline))

            // The shop's own price in each currency, asked for as soon as one is chosen, so the
            // figure on this sheet is the figure that is paid and not a guess at it.
            val quotes = remember { mutableStateMapOf<Shop.PayWith, Shop.Invoice>() }
            var asking by remember { mutableStateOf(false) }
            LaunchedEffect(payWith, working == null, quotes[payWith] == null) {
                if (working != null || quotes[payWith]?.expired == false) return@LaunchedEffect
                asking = true
                try { quote(payWith)?.let { quotes[payWith] = it } } finally { asking = false }
            }
            // A quote is dropped the moment it expires, which asks for a new one above. Nothing
            // used to recompose at expiry, so an old figure stayed on screen as the price.
            LaunchedEffect(quotes[payWith]) {
                val q = quotes[payWith] ?: return@LaunchedEffect
                delay((Shop.QUOTE_LIFE_MS - (System.currentTimeMillis() - q.quotedAt)).coerceAtLeast(0L) + 250L)
                quotes.remove(payWith)
            }
            val quoted = quotes[payWith]?.takeIf { !it.expired }
            val solRate by produceState<Double?>(initialValue = null) { value = Prices.solUsd() }
            // Base units this costs in [p]: the quote when there is one, else worked out from the
            // listed dollar price. The second is only for checking the wallet, never shown as the price.
            fun cost(p: Shop.PayWith): Long? = quotes[p]?.amount ?: pack?.usdPrice?.toBigDecimalOrNull()?.let { usd ->
                when (p) {
                    Shop.PayWith.USDC -> usd.movePointRight(p.decimals).toLong()
                    Shop.PayWith.SOL -> solRate?.takeIf { it > 0 }?.let {
                        usd.divide(BigDecimal.valueOf(it), 9, java.math.RoundingMode.UP).movePointRight(9).toLong()
                    }
                }
            }
            // No SOL at all for a network fee. Then AirPay's fee wallet pays it and takes the
            // same back in USDC, which is asked about only in that case.
            val noSol = have(Shop.PayWith.SOL)?.let { it < Chain.FEE_HEADROOM_LAMPORTS } == true
            val helper by produceState<Chain.Sponsor?>(initialValue = null, noSol) {
                value = if (noSol) sponsor() else null
            }
            val covered = payWith == Shop.PayWith.USDC && noSol && helper != null
            // Whether the wallet can pay in [p]. Null means not known yet, which blocks nothing.
            fun canPay(p: Shop.PayWith): Boolean? {
                val need = cost(p) ?: return null
                val held = have(p) ?: return null
                return held >= need + when {
                    p == Shop.PayWith.SOL -> Chain.FEE_HEADROOM_LAMPORTS
                    noSol -> helper?.feeUnits ?: 0L
                    else -> 0L
                }
            }
            val other = Shop.PayWith.entries.first { it != payWith }
            val short = working == null && canPay(payWith) == false
            // A USDC payment needs a little SOL for the network fee, unless that is covered.
            val noFee = working == null && !short && payWith == Shop.PayWith.USDC && noSol && helper == null

            // What it is paid in. The shop prices the same order in either, so choosing SOL costs
            // the same value and nothing is swapped.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pay with", color = D.Black, style = Type.body, modifier = Modifier.weight(1f))
                Shop.PayWith.entries.forEach { p ->
                    val on = p == payWith
                    val shape = RoundedCornerShape(999.dp)
                    Row(
                        Modifier.padding(start = 8.dp).clip(shape)
                            .background(if (on) D.Black else Color.Transparent)
                            .border(1.5.dp, if (on) D.Black else D.Line, shape)
                            .clickable(enabled = working == null) { chosenPay = p }
                            .heightIn(min = 40.dp).padding(start = 8.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painterResource(if (p == Shop.PayWith.USDC) R.drawable.token_usdc else R.drawable.token_sol),
                            contentDescription = null, modifier = Modifier.size(24.dp).clip(CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(p.symbol, color = if (on) D.White else D.Black, style = Type.caption)
                    }
                }
            }
            Spacer(Modifier.height(Space.inline))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("You pay", color = D.Black, style = Type.body, modifier = Modifier.weight(1f))
                val figure = working?.price ?: quoted?.shown
                when {
                    figure != null -> Text(figure, color = D.Black, style = Type.heading.figure())
                    asking || working != null -> androidx.compose.material3.CircularProgressIndicator(
                        color = D.Quiet, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    else -> Text("Shown when you proceed", color = D.Quiet, style = Type.caption)
                }
            }
            if (covered) {
                // The fee as its own line, in the money the buyer actually holds. They are never
                // asked to go and find SOL.
                Spacer(Modifier.height(Space.tight))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Network fee", color = D.Quiet, style = Type.caption, modifier = Modifier.weight(1f))
                    Text(
                        Money.token(BigDecimal.valueOf(helper!!.feeUnits, Shop.USDC_DECIMALS), "USDC"),
                        color = D.Quiet, style = Type.caption,
                    )
                }
            }
            // Said here, before anything is ordered: the wallet's balance is already known, so
            // there is no reason to let somebody press the button and be refused afterwards.
            if (short || noFee) {
                Spacer(Modifier.height(Space.inline))
                val shape = RoundedCornerShape(14.dp)
                val switch = short && canPay(other) != false
                Row(
                    Modifier.fillMaxWidth().clip(shape).background(D.DangerInk.copy(alpha = 0.08f))
                        .then(if (switch) Modifier.clickable { chosenPay = other } else Modifier)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            noFee -> "You need a little SOL for the network fee."
                            switch -> "You don't have enough ${payWith.symbol}."
                            else -> "You don't have enough USDC or SOL for this."
                        },
                        color = D.DangerInk, style = Type.caption, modifier = Modifier.weight(1f),
                    )
                    if (switch) Text("Pay with ${other.symbol}", color = D.BlueInk, style = Type.caption,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)
                }
            }
            Spacer(Modifier.height(Space.inline))
            PrimaryButton(
                working?.text ?: "Proceed with payment",
                { onPay(chosenPay, quoted) }, Modifier.fillMaxWidth(),
                enabled = !short && !noFee, loading = working != null,
            )
            Spacer(Modifier.height(Space.inline))
            // Who actually sells this, in one line. Required wherever their catalogue is sold.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                HiIcon(Hi.Info, D.Quiet, 14.dp)
                Spacer(Modifier.width(6.dp))
                Text("Sold and delivered by Bitrefill. No AirPay fee.", color = D.Quiet, style = Type.caption, maxLines = 1)
            }
            Spacer(Modifier.height(Space.lead))
            return@Tray
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (logo != null) {
                Image(
                    painterResource(logo), contentDescription = null,
                    modifier = Modifier.size(48.dp).clip(CircleShape), contentScale = ContentScale.Crop,
                )
            } else {
                Box(Modifier.size(48.dp).clip(CircleShape).background(D.Blue), contentAlignment = Alignment.Center) {
                    Text(name.take(1).uppercase(), color = D.White, style = Type.heading)
                }
            }
            Spacer(Modifier.width(Space.inline))
            Column(Modifier.weight(1f)) {
                Text(if (recharge) "$name recharge" else name, color = D.Black, style = Type.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.phone?.let { Text(it.replace("+91", "+91 "), color = D.Quiet, style = Type.mono) }
            }
        }
        Spacer(Modifier.height(Space.lead))
        RowLine()

        when (state) {
            is Purchase.Review -> Unit   // drawn above
            is Purchase.Working -> Unit  // drawn above, in the review's own layout
            is Purchase.Done -> {
                Spacer(Modifier.height(Space.section))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(D.Success), contentAlignment = Alignment.Center) {
                        HiIcon(Hi.Tick, D.White, 32.dp)
                    }
                    Spacer(Modifier.height(Space.lead))
                    Text("Paid ${state.paid}", color = D.Black, style = Type.title, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Space.tight))
                    Text(
                        when {
                            recharge && state.delivered -> "Recharge delivered to ${item.phone}"
                            recharge -> "Your recharge is on its way to ${item.phone}"
                            state.delivered -> "Delivered"
                            else -> "Your code is being prepared. It will open here when it is ready."
                        },
                        color = D.Quiet, style = Type.body, textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(Space.section))
                PrimaryButton("Done", onClose, Modifier.fillMaxWidth())
                TextLink("View on explorer", { onExplorer(state.signature) }, modifier = Modifier.fillMaxWidth())
            }
            is Purchase.Problem -> {
                Spacer(Modifier.height(Space.lead))
                Text(state.title, color = if (state.unsure != null) D.Black else D.DangerInk, style = Type.heading)
                Spacer(Modifier.height(Space.tight))
                Text(state.detail, color = D.Black, style = Type.body)
                Spacer(Modifier.height(Space.section))
                if (state.unsure != null) {
                    PrimaryButton("Done", onClose, Modifier.fillMaxWidth())
                    TextLink("View on explorer", { onExplorer(state.unsure) }, modifier = Modifier.fillMaxWidth())
                } else {
                    PrimaryButton("Try again", onRetry, Modifier.fillMaxWidth())
                    TextLink("Close", onClose, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(Space.lead))
    }
}

/** A label over a value, for the two things a plan is chosen on: how long, and how much. */
@Composable
private fun SheetStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(end = 12.dp)) {
        Text(label, color = D.Quiet, style = Type.caption)
        Spacer(Modifier.height(Space.tight))
        Text(value, color = D.Black, style = Type.heading)
    }
}

/** One labelled fact: the label in a fixed column, the value beside it, so a list of them lines up. */
@Composable
private fun PurchaseFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = D.Quiet, style = Type.body, modifier = Modifier.width(150.dp).padding(end = 12.dp))
        Text(value, color = D.Black, style = Type.body, modifier = Modifier.weight(1f))
    }
}

/** One point of a list: a dot in its own narrow column, and the text hanging to the right of it. */
@Composable
private fun BulletLine(
    text: String, color: Color = D.Quiet, style: androidx.compose.ui.text.TextStyle = Type.caption,
    maxLines: Int = Int.MAX_VALUE,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text("\u2022", color = color, style = style, modifier = Modifier.width(14.dp))
        Text(text, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/**
 * What a plan card says under its figures. Validity and data are already in the columns above, so
 * this is everything else that matters: the description's own points when it has several, else
 * the facts it does not repeat (voice, SMS), else the description as it is.
 */
private fun planSummary(ex: Plans.Extra?, fallback: String): List<String> {
    val points = Plans.points(ex?.description?.removePrefix("Benefits:"))
    if (points.size >= 2) return points
    val facts = ex?.details.orEmpty()
        .filterNot { (label, _) -> label.lowercase().let { "validity" in it || "data" in it } }
        .map { (label, value) -> "$label: $value" }
    return facts.ifEmpty { points.ifEmpty { listOf(fallback) } }
}


/**
 * The recharge screens' card ground: the brand blue at a few percent, with an edge a step darker.
 * A card on white with only a hairline read as empty space; this lifts each plan off the page
 * without adding a shadow, and the lime tag and the ink still carry on it.
 */
/** Between the parts of the plan screen. They are one task, so they sit closer than sections do. */
private val PLAN_GAP = 20.dp
private val PLAN_WASH = Color(0xFFF2F8FF)
private val PLAN_EDGE = Color(0xFFDCEAF9)

/**
 * One tab over the plan list. The label keeps ONE size and weight whether it is chosen or not, so
 * choosing a tab never changes its width; only its colour and the bar under it move. The bar is as
 * wide as the word, which is what it used not to be: a fixed 28dp stub under a label that had just
 * grown.
 */
@Composable
private fun PlanTab(label: String, selected: Boolean, onPick: () -> Unit) {
    val ink by androidx.compose.animation.animateColorAsState(if (selected) D.Black else D.Quiet, tween(180), label = "tabInk")
    val bar by animateFloatAsState(if (selected) 1f else 0f, tween(220, easing = FastOutSlowInEasing), label = "tabBar")
    Column(
        Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max)
            .clip(RoundedCornerShape(10.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onPick)
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(10.dp))
        Text(label, color = ink, style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(3.dp)
                .graphicsLayer { scaleX = bar; alpha = bar }
                .background(D.Blue, RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * The apps a plan comes with, as their own pictures: up to five, then "+N". A picture that has
 * not loaded, or an app the network gave no picture for, shows its first letter instead, so the
 * row never has holes in it.
 */
@Composable
private fun SubscriptionIcons(subs: List<Plans.Sub>) {
    val loader = svgCapable()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        subs.take(5).forEach { sub ->
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier.size(36.dp).clip(shape).background(D.White).border(1.dp, PLAN_EDGE, shape)
                    .semantics { contentDescription = sub.title },
                contentAlignment = Alignment.Center,
            ) {
                // The letter stands in only until the picture is really there. It is then taken
                // away, because some of these pictures are see-through and it would show behind.
                var loaded by remember(sub.icon) { mutableStateOf(false) }
                if (!loaded) Text(sub.title.take(1).uppercase(), color = D.Quiet, style = Type.caption)
                if (sub.icon != null) {
                    coil.compose.AsyncImage(
                        model = sub.icon, contentDescription = null, imageLoader = loader,
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                        contentScale = ContentScale.Fit,
                        onSuccess = { loaded = true },
                    )
                }
            }
        }
        if (subs.size > 5) Text("+${subs.size - 5}", color = D.Black, style = Type.body)
    }
}
