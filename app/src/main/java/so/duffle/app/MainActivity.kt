package so.duffle.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
    /** "check" is open: the diagnostics and the exchange log, which are for us, not the user. */
    private var checking by mutableStateOf(false)
    /** A wallet app is open in front of us. Hoisted out of the composition because linking
     *  outlives it — the user is in another app while this is true. */
    private var busy by mutableStateOf(false)
    /** The wallet cards are open, covering the tab. */
    private var cards by mutableStateOf(false)
    /** The wallet whose details sheet is open, or null. */
    private var walletDetails by mutableStateOf<Linked?>(null)
    /** The installed wallet apps, while choosing which one to link. Null when not choosing. */
    private var picker by mutableStateOf<List<WalletLink.App>?>(null)
    /** Every linked wallet, re-read whenever one is added, switched or dropped. */
    private var walletList by mutableStateOf<List<Linked>>(emptyList())
    /** The selected wallet's balance. Null while it is being read, or unreadable. */
    private var balance by mutableStateOf<Chain.Balance?>(null)
    /** What this wallet has earned, for the strip on the home page. */
    private var rewardSummary by mutableStateOf<Rewards.Summary?>(null)
    /** The catalogue, once the server has been asked. Empty means nothing to show. */
    private var shopSections by mutableStateOf<List<Shop.Section>>(emptyList())
    /** The section being browsed, and the product opened inside it. */
    private var shopSection by mutableStateOf<Shop.Section?>(null)
    private var shopProduct by mutableStateOf<Shop.Product?>(null)
    /** What the other phone shows when you tap them. A model number is not a name. */
    private var myName by mutableStateOf("")
    private var renaming by mutableStateOf(false)

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

    /** Set on resume so every open request is checked at once rather than on its schedule. */
    @Volatile private var checkAllNow = true
    private var offlineStreak = 0

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        push(if (granted) "✓ notifications on" else "— notifications declined")
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
                    is Wallet.Outcome.Failed -> push("✗ ${r.why}")
                    Wallet.Outcome.Declined -> push("— wallet declined")
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
                    is Wallet.Outcome.Failed -> push("✗ ${r.why}")
                    Wallet.Outcome.Declined -> push("— ${app.label} declined")
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
    private fun buy(product: Shop.Product, value: String, category: String) {
        val payer = connected
        if (payer == null) {
            status = Status.Problem("Connect a wallet first", "The shop pays from your wallet.")
            return
        }
        if (product.needsPhone) {
            // The number is the product's own requirement, and Bitrefill says so plainly.
            // Collecting it properly is the next piece; refusing honestly beats a 400.
            status = Status.Problem("Needs a phone number",
                "${product.name.removeSuffix(" India")} credits a number directly — that's not wired up yet.")
            return
        }
        lifecycleScope.launch {
            busy = true
            try {
                push("shop: asking for a price…")
                val inv = Shop.invoice(product.id, value)
                if (inv == null) {
                    status = Status.Problem("Couldn't get a price", "The shop didn't answer. Try again.")
                    return@launch
                }
                push("shop: ${inv.shown} to ${inv.address.take(8)}…")
                status = Status.Working("Approve ${inv.shown} in your wallet")
                val built = withContext(Dispatchers.IO) {
                    Chain.buildTokenTransfer(
                        payer = payer, recipient = inv.address, mint = Shop.USDC_MAINNET,
                        units = inv.amount, decimals = Shop.USDC_DECIMALS,
                        reference = PayRequest.newReference(), symbol = "USDC",
                        mainnet = true,
                    )
                }
                when (built) {
                    is Chain.Built.Bad -> status = Status.Problem("Can't pay this", built.why)
                    is Chain.Built.Offline -> status = Status.Problem("You're offline", "Nothing was sent.")
                    is Chain.Built.NotEnough -> status = Status.Problem(
                        "Not enough USDC",
                        "This needs ${inv.shown} of real USDC on mainnet, in the wallet you have selected.")
                    is Chain.Built.Ok -> {
                        // The quote is checked again here rather than only when it was made:
                        // the wallet may have been open for a while.
                        if (inv.expired) {
                            status = Status.Problem("Price expired",
                                "That quote is over two minutes old. Nothing was sent — try again.")
                            return@launch
                        }
                        when (val signed = wallet.signOnly(built.tx)) {
                            is Wallet.SendOutcome.Signed -> {
                                if (inv.expired) {
                                    status = Status.Problem("Price expired",
                                        "The quote died while your wallet was open. Nothing was sent.")
                                    return@launch
                                }
                                when (val sent = withContext(Dispatchers.IO) { Chain.submit(signed.tx, mainnet = true) }) {
                                    is Chain.Submitted.Ok -> {
                                        push("✓ shop: paid ${inv.shown} — ${sent.signature.take(12)}…")
                                        status = Status.Sent(sent.signature)
                                        // Written down before it is watched. The watcher below
                                        // dies with the screen; this is what survives it, and
                                        // what the next launch asks about.
                                        orders.add(Orders.Order(
                                            invoiceId = inv.id, name = product.name, value = value,
                                            paid = inv.shown, signature = sent.signature,
                                            category = category,
                                            status = "paid", at = System.currentTimeMillis(),
                                        ))
                                        refreshLists()
                                        // Delivery trails the payment by seconds.
                                        launch { watchOrder(inv) }
                                    }
                                    is Chain.Submitted.Rejected -> status = Status.Problem(
                                        "Payment refused",
                                        if (sent.why.contains("blockhash", true))
                                            "It took too long and expired. Nothing was sent — try again."
                                        else sent.why)
                                    Chain.Submitted.Offline -> status = Status.Problem(
                                        "You're offline", "It was signed but never sent.")
                                }
                            }
                            is Wallet.SendOutcome.Failed -> status = Status.Problem("Not bought", signed.why)
                            Wallet.SendOutcome.NeedsReconnect -> status = Status.Problem(
                                "Reconnect your wallet", "The session ended. Connect again and retry.")
                        }
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    /** Asks what became of an order until it is delivered, or until asking stops being useful. */
    private suspend fun watchOrder(inv: Shop.Invoice) {
        repeat(12) {
            delay(5_000)
            val st = Shop.order(inv.id) ?: return@repeat
            push("shop: order $st")
            orders.setStatus(inv.id, st)
            refreshLists()
            if (st == "delivered") {
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
            thread = person
            tab = Tab.TAP
            return
        }
        val sig = t.signature ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse(Chain.explorerUrl(sig, mainnet = t.kind == History.Kind.SHOP))))
        }.onFailure { push("couldn't open the explorer") }
    }

    /** Hands the system an invite to pass on. Real, and no more than it says. */
    private fun shareInvite() {
        val me = Identity.label(this)
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT,
                    // No link until there is a domain to link to. duffle.so does not resolve
                    // (Phantom's favicon fetch for it fails), and a share message whose one
                    // link is dead is worse than one with none. See pending.txt.
                    "$me is paying people by tapping phones with AirPay. " +
                        "Tap to pay anyone — no address to type.")
            }, "Invite a friend"))
        }
    }

    /** Re-reads the linked wallets and the selected one's balance. */
    private fun refreshWallets() {
        walletList = wallet.linked.all()
        balance = null
        rewardSummary = null
        val addr = connected ?: return
        lifecycleScope.launch {
            val b = withContext(Dispatchers.IO) { Chain.balance(addr) }
            // Only report a balance for the wallet still selected — switching twice quickly
            // would otherwise show one wallet's money under another's name.
            if (connected == addr) balance = b
        }
        lifecycleScope.launch {
            val r = Rewards.summary(addr)
            if (connected == addr) rewardSummary = r
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
                val st = Shop.order(o.invoiceId) ?: continue
                if (st != o.status) {
                    orders.setStatus(o.invoiceId, st)
                    push("shop: ${o.name} — $st")
                }
            }
            refreshLists()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashGuard()
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
        refreshLists()
        refreshWallets()
        Push.init(this)

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
                        push(if (g != null) "✓ Verified Seeker — genesis ${g.take(8)}…"
                             else "   no Genesis Token on this wallet")
                    }
                    // Needed without a server too: a tap on a locked phone arrives as a notification.
                    if (!Push.canNotify(this@MainActivity)) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                    if (ServerClient.enabled && Push.configured) {
                        Push.token(this@MainActivity)?.let { t ->
                            push(if (ServerClient.registerDevice(addr, t)) "✓ push registered" else "✗ push registration failed")
                        }
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
                LaunchedEffect(resumeTick) {
                    if (shopSections.isEmpty()) Shop.sections()?.let { shopSections = it }
                }

                // Anything written to us while the app was closed. The live stream below only
                // carries what arrives while it is connected, and a push only reaches a phone
                // that has one — so the catch-up is the only thing that covers both.
                LaunchedEffect(connected, resumeTick) {
                    val addr = connected ?: return@LaunchedEffect
                    if (!ServerClient.enabled) return@LaunchedEffect
                    var added = false
                    ServerClient.messages(addr, messages.since()).forEach {
                        if (messages.add(it)) added = true
                    }
                    if (added) refreshLists()
                }

                LaunchedEffect(connected) {
                    val addr = connected ?: return@LaunchedEffect
                    if (!ServerClient.enabled) return@LaunchedEffect
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        ServerClient.events(addr).collect { ev ->
                            when (ev) {
                                is ServerClient.Paid -> {
                                    val e = owed.firstOrNull { it.reference == ev.reference && it.live }
                                    if (e != null) {
                                        push("server: ${ev.reference.take(8)}… paid — verifying")
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
                                    push("— ${ev.reference.take(8)}… was withdrawn by the requester")
                                }
                                is ServerClient.Deferred -> {
                                    watch.markDeferred(ev.reference, ev.label); refreshLists()
                                    push("— ${ev.label.ifBlank { "They" }} will pay ${ev.reference.take(8)}… later")
                                }
                                is ServerClient.Rejected -> {
                                    watch.markRejected(ev.reference, ev.label); refreshLists()
                                    push("— ${ev.label.ifBlank { "They" }} declined ${ev.reference.take(8)}…")
                                    Haptics.failure(this@MainActivity)
                                }
                                is ServerClient.Messaged -> {
                                    if (messages.add(Messages.Note(ev.from, ev.body, ev.at, mine = false, id = ev.id))) {
                                        refreshLists()
                                        push("← ${ev.label.ifBlank { "They" }}: ${ev.body}")
                                        Haptics.contact(this@MainActivity)
                                    }
                                }
                                is ServerClient.Tapped -> {
                                    push("server: ${ev.card.label} tapped you")
                                    startActivity(TapPopupActivity.intent(this@MainActivity, TapPayload.Card(ev.card), cameFrom = "server"))
                                }
                            }
                        }
                    }
                }

                // Back walks the page back out, in the order it was opened. Without this the
                // system back left the app from inside a thread — the one place it most looks
                // like a screen you can back out of.
                BackHandler(enabled = thread != null || cards || tab != Tab.TAP) {
                    when {
                        thread != null -> thread = null
                        cards -> cards = false
                        shopProduct != null -> shopProduct = null
                        shopSection != null -> shopSection = null
                        else -> tab = Tab.TAP
                    }
                }

                Surface(Modifier.fillMaxSize(), color = D.White) {
                    // statusBarsPadding, not a guessed top margin: the pill sits at the very
                    // top of the app and was drawing straight through the clock and battery.
                    Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    // A Box, so the pay bar can float over the page rather than queue up at
                    // the end of it. Only the home page is scrollable behind it.
                    val home = tab == Tab.TAP && thread == null && !cards
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Built here, above everything, because two things read it: the bell's
                    // count in the header and the panel itself. Derived from the same lists
                    // the rest of the screen draws, so it cannot drift from them.
                    val known0 = contacts.all()
                    val alerts = Alerts.build(
                        toPay = toPay, owed = owed, notes = notes, orders = bought,
                        offers = liveOffers,
                        name = { addr -> known0.firstOrNull { it.address == addr }?.label ?: "Someone" },
                        offerAt = { id -> Alerts.firstSeen(this@MainActivity, id) },
                    )
                    val unread = Alerts.unread(alerts, alertsSeen)
                    val history = History.build(
                        toPay = toPay, owed = owed, orders = bought,
                        name = { addr -> known0.firstOrNull { it.address == addr }?.label ?: "Someone" },
                    )

                    // Rows no thread can show: a request we asked before peers were recorded,
                    // or either side of one with somebody who is not in contacts.
                    val known = contacts.all().map { it.address }.toSet()
                    val orphans = toPay.count { it.request.recipient !in known } +
                        owed.count { it.peer == null || it.peer !in known }
                    Column(
                        Modifier.fillMaxSize()
                            .then(if (tab == Tab.TAP && !cards && !checking && thread == null)
                                Modifier.verticalScroll(rememberScrollState()) else Modifier)
                            .padding(horizontal = 20.dp, vertical = 20.dp)
                            // ONE inset modifier, not two. `navigationBarsPadding().imePadding()`
                            // chained is the documented way to wedge this: each consumes what it
                            // applies, the keyboard animation re-reports what is left, and the
                            // layout re-measures forever. It ANR'd the app on the first touch
                            // after the keyboard opened, twice, and never once without it.
                            // A union is resolved in a single pass.
                            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
                    ) {
                        Header(
                            checking = checking,
                            unread = unread,
                            me = myName,
                            // Editing the name IS the profile, for now: it is the only thing
                            // about yourself this app stores, and it is what other phones see.
                            onProfile = { renaming = true },
                            onCheck = { checking = !checking },
                            onAlerts = {
                                tab = Tab.ALERTS
                                // Marked on open, not on scroll: the list is short and opening
                                // it IS reading it. The badge clears immediately; the rows keep
                                // their dots against where the count stood a moment ago.
                                alertsWere = alertsSeen
                                Alerts.markSeen(this@MainActivity)
                                alertsSeen = Alerts.seenAt(this@MainActivity)
                                catchUpOrders()
                            },
                        )
                        Spacer(Modifier.height(14.dp))

                        when (val st = status) {
                            is Status.Working -> { Banner("…", st.what, D.Quiet); Spacer(Modifier.height(10.dp)) }
                            is Status.Problem -> {
                                Banner(st.title, st.detail, D.Danger, onDismiss = { status = Status.Idle })
                                Spacer(Modifier.height(10.dp))
                            }
                            is Status.Sent -> {
                                Banner("✓ Sent", st.signature, D.Blue, mono = true,
                                    onDismiss = { status = Status.Idle })
                                Spacer(Modifier.height(10.dp))
                            }
                            Status.Idle -> Unit
                        }
                        paidSignature?.let { sig ->
                            Banner("✓ Paid", sig, D.Blue, mono = true, onDismiss = { paidSignature = null })
                            Spacer(Modifier.height(10.dp))
                        }

                        if (cards) {
                            WalletsScreen(
                                list = walletList,
                                selected = connected,
                                busy = busy,
                                onPick = { w ->
                                    wallet.use(w)
                                    connected = w.address
                                    myGenesis = null
                                    // The armed request carries the old address until rebuilt.
                                    TapHostApduService.pending.value = null
                                    refreshWallets()
                                    applyMode(amount)
                                    push("— tapping with ${w.name} ${w.short}")
                                },
                                onAdd = { picker = WalletLink.installed(this@MainActivity) },
                                onClose = { cards = false },
                                picker = picker,
                                onPickApp = { app ->
                                    picker = null
                                    linkWallet(app)
                                },
                                onClosePicker = { picker = null },
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
                                onPay = { item -> incoming = item.request },
                                onDecline = { item ->
                                    val req = item.request
                                    // Marked, not deleted. The row stays in the thread in red,
                                    // so "they asked and I said no" is still answerable.
                                    val live = item.payable
                                    inbox.markDeclined(req.reference); refreshLists()
                                    push("— declined ${req.amount} ${req.symbol} from ${req.label}")
                                    if (live) {
                                        lifecycleScope.launch {
                                            ServerClient.reject(req.reference, Identity.label(this@MainActivity))
                                        }
                                    }
                                },
                                onWithdraw = { e ->
                                    watch.markWithdrawn(e.reference); refreshLists()
                                    push("— withdrew ${e.amount} ${e.symbol} request ${e.reference.take(8)}…")
                                    lifecycleScope.launch { ServerClient.withdraw(e.reference) }
                                },
                                onBack = { thread = null },
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
                            )
                            return@Column
                        }

                        if (tab != Tab.TAP) {
                            BackHeader(
                                when (tab) {
                                    Tab.REQUESTS -> "Activity"
                                    Tab.ALERTS -> "Notifications"
                                    Tab.HISTORY -> "Transactions"
                                    Tab.REWARDS -> "Earn"
                                    else -> shopSection?.title ?: "Pay a bill"
                                },
                            ) { tab = Tab.TAP; shopSection = null; shopProduct = null }
                            Spacer(Modifier.height(16.dp))
                        }

                        if (tab == Tab.SHOP) {
                            shopSection?.let { sec ->
                                ShopSection(
                                    section = sec,
                                    picked = shopProduct,
                                    onPick = { shopProduct = it },
                                    onBuy = { p, v -> buy(p, v, sec.title) },
                                )
                            }
                            return@Column
                        }

                        if (tab == Tab.HISTORY) {
                            HistoryTab(
                                list = history,
                                month = histMonth,
                                onMonth = { histMonth = it },
                                onOpen = { t -> openTxn(t, known0) },
                            )
                            return@Column
                        }

                        if (tab == Tab.ALERTS) {
                            AlertsTab(alerts, alertsWere) { a ->
                                when {
                                    // Anything with a person behind it belongs in their thread,
                                    // which is where the state it refers to actually lives.
                                    a.peer != null ->
                                        known0.firstOrNull { it.address == a.peer }?.let {
                                            thread = it; tab = Tab.TAP
                                        }
                                    a.kind == Alerts.Kind.OFFER -> tab = Tab.REWARDS
                                    else -> tab = Tab.TAP
                                }
                            }
                            return@Column
                        }

                        if (tab == Tab.REWARDS) {
                            RewardsTab(connected, myGenesis != null)
                            return@Column
                        }

                        if (tab == Tab.REQUESTS) {
                            RequestsTab(
                                toPay = toPay,
                                owed = owed,
                                paying = paying,
                                onPay = { item -> incoming = item.request },
                                onDismissToPay = { item ->
                                    val req = item.request
                                    inbox.remove(req.reference); refreshLists()
                                    push("— declined ${req.amount} ${req.symbol} from ${req.label}")
                                    // Only a live request is a rejection; one they already took
                                    // back, or already paid, is just tidying up.
                                    if (item.payable) {
                                        lifecycleScope.launch {
                                            ServerClient.reject(req.reference, Identity.label(this@MainActivity))
                                        }
                                    }
                                },
                                onDismissOwed = { e ->
                                    watch.remove(e.reference); refreshLists()
                                    push("— withdrew ${e.amount} ${e.symbol} request ${e.reference.take(8)}…")
                                    // Tell the phone we asked, so it stops offering a Pay button
                                    // for something we took back. Advisory: money sent anyway
                                    // still arrives, and the server keeps watching for it.
                                    lifecycleScope.launch { ServerClient.withdraw(e.reference) }
                                },
                            )
                            return@Column
                        }

                        Tag("2", checking)
                        BalanceCard(
                            wallet = walletList.firstOrNull { it.address == connected },
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
                                walletDetails = walletList.firstOrNull { it.address == connected }
                            },
                            onSelectWallet = { w ->
                                wallet.use(w)
                                connected = w.address
                                myGenesis = null
                                TapHostApduService.pending.value = null
                                refreshWallets()
                                applyMode(amount)
                                push("— tapping with ${w.name} ${w.short}")
                            },
                        )
                        if (connected == null) {
                            Spacer(Modifier.height(12.dp))
                            PrimaryButton(if (busy) "Opening your wallet…" else "Connect a wallet",
                                onClick = { connectWallet(fresh = false) }, loading = busy)
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
                                label = { Text("Amount (SOL, devnet)") },
                                singleLine = true,
                                isError = amountProblem != null,
                                supportingText = amountProblem?.let { { Text(it, color = D.Danger) } },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        if (contactCount > 0) {
                            Spacer(Modifier.height(24.dp))
                            Tag("3", checking)
                            People(
                                list = contacts.all(),
                                waiting = { addr ->
                                    toPay.count { it.payable && it.request.recipient == addr } +
                                        owed.count { it.live && !it.rejected && it.peer == addr }
                                },
                                // Only offered when something genuinely cannot be reached
                                // through a person: a request served before peers were
                                // recorded, or one from someone no longer in contacts.
                                onAll = if (orphans > 0) ({ tab = Tab.REQUESTS }) else null,
                                onPick = { c -> thread = c },
                            )
                        }

                        if (Rewards.enabled) {
                            Spacer(Modifier.height(22.dp))
                            Tag("4", checking)
                            EarnStrip(rewardSummary) { tab = Tab.REWARDS }
                            Spacer(Modifier.height(10.dp))
                            EarnTiles(
                                onRewards = { tab = Tab.REWARDS },
                                onOffers = { tab = Tab.REWARDS },
                                // Nothing on the server tracks a referral yet, so this shares an
                                // invite and claims nothing else. A tile that promised a bonus
                                // nobody is counting would be the one lie on this screen.
                                onReferrals = { shareInvite() },
                            )
                        }
                        if (shopSections.isNotEmpty()) {
                            Spacer(Modifier.height(22.dp))
                            Tag("5", checking)
                            Text("Pay a bill", color = D.Black, fontSize = 17.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                            Spacer(Modifier.height(10.dp))
                            ShopTiles(shopSections) { sec ->
                                shopSection = sec
                                shopProduct = null
                                tab = Tab.SHOP
                            }
                        }

                        if (history.isNotEmpty()) {
                            Spacer(Modifier.height(22.dp))
                            Tag("6", checking)
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
                                onOpen = { t -> openTxn(t, contacts.all()) },
                            )
                        }

                        // The log is ours, not theirs: it only exists while "check" is open.
                        if (checking) {
                            Spacer(Modifier.height(16.dp))
                            SectionLabel("EXCHANGE")
                            LogView(log)
                        }

                        // Room for the pay bar, which floats over this page rather than in it.
                        if (tab == Tab.TAP && thread == null && !cards) Spacer(Modifier.height(116.dp))
                    }

                    if (home) {
                        PayBar(
                            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                            onTap = {
                                // The sheet does the waiting now. Arming the radio from here
                                // left the home screen looking exactly as it had a second
                                // earlier — nothing said the phone was listening, or that the
                                // next move was physical.
                                paidSignature = null
                                startActivity(TapPopupActivity.armed(this@MainActivity))
                            },
                            onScan = { startActivity(ScanActivity.intent(this@MainActivity)) },
                        )
                    }
                    }
                    }
                }

                walletDetails?.let { w ->
                    WalletSheet(
                        w = w,
                        onClose = { walletDetails = null },
                        onRemove = {
                            walletDetails = null
                            wallet.linked.remove(w.address)
                            // The selected one may have just gone; refreshWallets re-reads
                            // which wallet is current and applyMode re-arms with it.
                            connected = wallet.address
                            refreshWallets()
                            applyMode(amount)
                            push("— removed ${w.name} ${w.short}")
                        },
                    )
                }

                if (renaming) {
                    NameDialog(
                        current = myName,
                        onDismiss = { renaming = false },
                        onSave = { name ->
                            Identity.setLabel(this@MainActivity, name)
                            myName = Identity.label(this@MainActivity)
                            renaming = false
                            // The armed request carries the old name until it is rebuilt.
                            TapHostApduService.pending.value = null
                            applyMode(amount)
                            push("— you now appear as $myName")
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
                                status = Status.Problem("Connect a wallet first", "It's saved in Requests — pay it after connecting.")
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
                            push("— saved ${req.amount} ${req.symbol} for ${req.label} in Requests")
                        },
                        onDecline = {
                            incoming = null
                            inbox.remove(req.reference); refreshLists()
                            push("— declined ${req.amount} ${req.symbol} from ${req.label}")
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
            val isNew = contacts.record(req.recipient, req.label, false, brand = req.brand)
            contactCount = contacts.count()
            push(if (isNew) "+ new contact ${req.label}" else "— known contact ${req.label}")
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
            push("— another request from ${req.label} saved in Requests")
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
                push("✗ message not delivered — it stays here, send it again")
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
                    status = Status.Problem("Payment didn't cover the request", "Still waiting — ${found.why}.")
                }
            }
            is Chain.Found.Broken -> status = Status.Problem(
                "Can't check for payment", "Solana rejected our query: ${found.why}",
            )
            Chain.Found.Offline -> if (++offlineStreak == 3) status = Status.Problem(
                "Can't reach Solana", "Still waiting — we'll check again when you're back online.",
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
        push("✓ ${req.amount} ${req.symbol} to ${req.label} was already paid — ${found.signature.take(10)}…")
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
        push("✓ PAID ${e.amount} ${e.symbol} — $signature")
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
                push("✓ CONFIRMED — ${Chain.explorerUrl(result.signature)}")
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
                status = Status.Problem("Already paid", "This request was paid before — nothing new was sent.")
            }
            is PayResult.Error -> {
                status = Status.Problem(result.title, "${result.detail} It's still in Requests.")
                Haptics.failure(this)
            }
            is PayResult.Failed -> {
                status = Status.Problem(result.title, "${result.detail} It's still in Requests.")
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
        // Keep the same reference across a pause/resume. This runs again on every onResume,
        // and minting a fresh reference each time meant a notification — or the screen simply
        // sleeping — silently swapped the key under a request already on screen.
        if (live != null &&
            live.recipient == addr && live.amount == amount && live.genesis == myGenesis
        ) return

        TapHostApduService.pending.value = PayRequest(
            recipient = addr,
            amount = amount,
            token = "SOL",
            label = Identity.label(this),
            reference = PayRequest.newReference(),
            genesis = myGenesis,
            brand = Brand.mine(),
        )
    }

    /** Bumped on resume so composables that cache device state can re-read it. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick++
        // Back from the wallet, or back after hours away: anything could have been paid.
        checkAllNow = true
        refreshLists()
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
                    status = Status.Problem(
                        "Something went wrong",
                        "That step failed unexpectedly. Nothing was sent — try again from Requests.",
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
    }
}

enum class Tab { TAP, REQUESTS, REWARDS, SHOP, ALERTS, HISTORY }

fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - at) / 1000).coerceAtLeast(0)
    return when {
        s < 45 -> "just now"
        s < 3600 -> "${(s + 30) / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        else -> "${s / 86_400}d ago"
    }
}


/** One shape for every piece of news the app has to deliver. */
@Composable
private fun Banner(
    title: String,
    detail: String,
    tone: Color,
    mono: Boolean = false,
    onDismiss: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = .08f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = tone, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(detail, color = D.Quiet, fontSize = if (mono) 10.sp else 13.sp,
                    fontFamily = if (mono) FontFamily.Monospace else Ui, lineHeight = 17.sp)
            }
        }
        if (onDismiss != null) {
            Text("OK", color = tone, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onDismiss)
                    .padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun WalletRow(
    address: String?,
    busy: Boolean,
    onDisconnect: () -> Unit,
    onConnect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(D.Fill)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HIcon(R.drawable.hi_wallet, if (address == null) D.Danger else D.Quiet, 18.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                address?.let { "${it.take(6)}…${it.takeLast(4)}" } ?: "No wallet connected",
                color = if (address == null) D.Danger else D.Black,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = Figures,
            )
        }
        if (address == null) {
            Box(
                Modifier.clip(RoundedCornerShape(11.dp)).background(D.Blue)
                    .clickable(enabled = !busy, onClick = onConnect)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) { Text(if (busy) "…" else "Connect", color = D.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        } else {
            // The escape hatch for a session the wallet no longer honours. Without it the only
            // cure for a stale token is reinstalling the app.
            Text("Disconnect", color = D.Quiet, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !busy, onClick = onDisconnect)
                    .padding(horizontal = 8.dp, vertical = 6.dp))
        }
    }
}

/**
 * The wallet, its balance, and the two things you can do with it.
 *
 * Left: active wallet name + address badge, Total balance label, large tabular numbers,
 * and pill actions (Send & Request).
 * Right: clean interactive multi-card stack with brand logos peeking out from the right edge.
 */
@Composable
private fun BalanceCard(
    wallet: Linked?,
    others: List<Linked>,
    balance: Chain.Balance?,
    mode: Boolean,
    onSend: () -> Unit,
    onRequest: () -> Unit,
    onCards: () -> Unit,
    onSelectWallet: (Linked) -> Unit = {},
    onDetails: () -> Unit = {},
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
                if (wallet != null) {
                    // No coloured disc behind it: the icon is its own tile with its own
                    // background, so a circle of the same colour underneath only showed as a
                    // ring of it poking out from behind the corners.
                    WalletLogo(wallet, 22.dp, 7.dp)
                    Spacer(Modifier.width(8.dp))
                    // The icon already says which wallet this is, so the name beside it was
                    // saying it twice. What goes here instead is whatever identifies the
                    // ACCOUNT: the owner's name when the wallet reports one — Seeker Wallet
                    // gives its SNS domain — and the address when it does not.
                    Text(
                        wallet.username ?: wallet.short,
                        color = if (wallet.username != null) D.Black else D.Quiet,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = if (wallet.username == null) Figures else TextStyle.Default,
                        maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(D.Fill)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                } else {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(D.Fill),
                        contentAlignment = Alignment.Center,
                    ) {
                        HIcon(R.drawable.hi_wallet, D.Quiet, 12.dp)
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "No wallet connected",
                        color = D.Quiet,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                "Total balance",
                color = D.Quiet,
                fontSize = 11.sp,
                letterSpacing = 0.9.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            // In dollars, because "total balance" is a question about money and SOL is not an
            // answer to it for most people.
            //
            // **This is devnet SOL priced at the mainnet rate**, so the figure is notional —
            // what the balance would be worth. It decides a label and nothing else; every
            // amount that is actually paid is still counted in SOL or in the token itself.
            // When the price cannot be fetched the SOL figure is shown instead, rather than a
            // dollar sign in front of a number nobody checked.
            val sol = (balance as? Chain.Balance.Ok)?.sol
            val rate by produceState<Double?>(initialValue = null) { value = Prices.solUsd() }
            // ONE text run, not three Texts in a Row.
            //
            // Laid out as a row they were aligned by their bottom EDGES, and a glyph's bottom
            // edge includes room for descenders it does not have — so the dollar sign and the
            // decimals sank below the digits by however much their own font reserved. Spans
            // inside a single Text share a baseline, which is the thing that actually needs to
            // line up.
            val dollars = sol?.let { s0 -> rate?.let { s0 * it } }
            val shown = when {
                dollars != null -> Prices.money(dollars)
                sol != null -> "%.4f".format(sol)
                else -> "—"
            }
            Text(
                buildAnnotatedString {
                    if (dollars != null) {
                        // Lifted part of the way, not all of it. A baseline is right for the
                        // decimals — the same kind of mark as the digits — but a currency sign
                        // is a label ON the number, so it rides a little above the baseline
                        // and a little below the digit tops. `letterSpacing` here is the gap
                        // after the sign: the font's own sidebearing is too tight at this size.
                        withStyle(SpanStyle(
                            fontSize = 30.sp, color = D.Quiet, fontWeight = FontWeight.Bold,
                            baselineShift = BaselineShift(0.32f), letterSpacing = 3.sp,
                        )) {
                            append("$")
                        }
                    }
                    withStyle(SpanStyle(fontSize = 60.sp, color = D.Black, fontWeight = FontWeight.ExtraBold)) {
                        append(shown.substringBefore('.'))
                    }
                    shown.substringAfter('.', "").takeIf { it.isNotEmpty() }?.let { frac ->
                        withStyle(SpanStyle(fontSize = 26.sp, color = D.Quiet, fontWeight = FontWeight.Bold)) {
                            append(".$frac")
                        }
                    }
                    if (dollars == null && sol != null) {
                        withStyle(SpanStyle(fontSize = 14.sp, color = D.Quiet, fontWeight = FontWeight.SemiBold)) {
                            append(" SOL")
                        }
                    }
                },
                fontFamily = Display,
                style = Figures,
                lineHeight = 64.sp,
                maxLines = 1,
            )

            // Send and Request were here.
            //
            // Send did nothing the pay bar at the bottom does not do. Request is a different
            // matter and worth knowing about: `Mode.REQUEST` is the ONLY thing that arms
            // TapHostApduService.pending, so with no control for it this phone can no longer
            // be the payee in a tap from the home screen. The machinery is left intact —
            // `mode`, applyMode(), the amount field — so putting a way back in is one line
            // wherever it belongs. Asking is still reachable through a person: their thread's
            // "Pay or request", and "Request instead" on the tap sheet.

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
                            .background(c.colour)
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
                        WalletLogo(c, 20.dp)
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
private fun WalletLogo(w: Linked, size: Dp, radius: Dp = 6.dp) {
    val tint = w.iconTint
    if (tint == null) {
        Image(
            painterResource(w.iconRes), contentDescription = null,
            modifier = Modifier.size(size).clip(RoundedCornerShape(radius)),
            // Fit, not Crop. These icons are not all square — Backpack is 117x142, Seeker
            // 681x607 — and filling a square box with them zoomed in and cut the edges off
            // the artwork. Fit shows the whole thing.
            contentScale = ContentScale.Fit,
        )
    } else {
        HIcon(w.iconRes, tint, size)
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
 * Sized to fit without scrolling. A sheet you have to scroll to reach the one button on it is
 * a sheet that hid the button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletSheet(w: Linked, onRemove: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val clip = LocalClipboardManager.current
    var confirming by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val checks = remember(w.address, w.token, w.pkg) { checksFor(context, w) }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = D.White, dragHandle = null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(D.Line, CircleShape))
            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                WalletLogo(w, 46.dp, 14.dp)
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(w.username ?: w.name, color = D.Black, fontSize = 19.sp,
                        fontWeight = FontWeight.Bold, fontFamily = Display, maxLines = 1)
                    Spacer(Modifier.height(2.dp))
                    // Short, and tappable. The full address is 44 characters that wrap to two
                    // lines and are read by nobody; what people do with it is paste it.
                    Text(
                        if (copied) "Copied" else w.short,
                        color = if (copied) D.Success else D.Quiet,
                        fontSize = 12.5.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp))
                            .clickable {
                                clip.setText(AnnotatedString(w.address))
                                copied = true
                            }
                            .padding(vertical = 1.dp),
                    )
                }
                Text("Linked ${whenLinked(w.addedAt)}", color = D.Quiet, fontSize = 11.sp,
                    textAlign = TextAlign.End)
            }

            Spacer(Modifier.height(16.dp))

            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(D.Fill)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            ) {
                checks.forEach { c ->
                    Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        HIcon(
                            if (c.ok) R.drawable.hi_tick else R.drawable.hi_alert,
                            if (c.ok) D.Success else D.Danger, 16.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.label, color = D.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            c.detail?.let {
                                Text(it, color = D.Quiet, fontSize = 11.sp,
                                    fontFamily = if (it.startsWith("app.") || it.contains('.')) FontFamily.Monospace else FontFamily.Default,
                                    maxLines = 1)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            // The strongest thing on this sheet, and the one line that is not a check because
            // it is not a property of the wallet — it is what this app does not do.
            Text(
                "AirPay never receives your key. Every payment is signed inside the wallet and handed back already signed.",
                color = D.Quiet, fontSize = 11.5.sp, lineHeight = 16.sp,
            )

            Spacer(Modifier.height(18.dp))
            // Two taps, because relinking means going back through the wallet. Nothing is
            // spent either way: this forgets a token, it does not touch the account.
            Text(
                if (confirming) "Tap again to remove" else "Remove wallet",
                color = D.Danger, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(D.Danger.copy(alpha = if (confirming) .16f else .08f))
                    .clickable { if (confirming) onRemove() else confirming = true }
                    .padding(vertical = 15.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
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

@Composable
private fun WalletPicker(apps: List<WalletLink.App>, onPick: (WalletLink.App) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Add a wallet", color = D.Black, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Display)
        Spacer(Modifier.height(4.dp))
        Text("AirPay never sees your keys — the wallet signs.", color = D.Quiet, fontSize = 13.5.sp)
        Spacer(Modifier.height(16.dp))
        if (apps.isEmpty()) {
            Text("No wallet apps found on this phone.", color = D.Quiet, fontSize = 14.sp)
        }
        apps.forEach { app ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(16.dp))
                    .background(D.Fill).clickable { onPick(app) }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = remember(app.pkg) { app.icon?.let { runCatching { it.toBitmap().asImageBitmap() }.getOrNull() } }
                if (icon != null) {
                    Image(BitmapPainter(icon), contentDescription = null, modifier = Modifier.size(34.dp).clip(CircleShape))
                } else {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(D.Blue.copy(alpha = .15f)),
                        contentAlignment = Alignment.Center) {
                        HIcon(R.drawable.hi_wallet, D.Blue, 17.dp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(app.label, color = D.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                HIcon(R.drawable.hi_arrow_up_right, D.Quiet, 18.dp)
            }
        }
        Spacer(Modifier.height(14.dp))
        SecondaryButton("Cancel", onClose, Modifier.fillMaxWidth())
    }
}

@Composable
private fun WalletsScreen(
    list: List<Linked>,
    selected: String?,
    busy: Boolean,
    onPick: (Linked) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
    picker: List<WalletLink.App>? = null,
    onPickApp: (WalletLink.App) -> Unit = {},
    onClosePicker: () -> Unit = {},
) {
    if (picker != null) {
        WalletPicker(picker, onPickApp, onClosePicker)
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wallets", color = D.Black, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Display)
            Spacer(Modifier.weight(1f))
            Text(if (busy) "…" else "Add wallet", color = D.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(D.Fill)
                    .clickable(enabled = !busy, onClick = onAdd)
                    .padding(horizontal = 16.dp, vertical = 10.dp))
        }
        Spacer(Modifier.height(18.dp))
        list.forEach { w ->
            val on = w.address == selected
            Column(
                Modifier.fillMaxWidth().padding(bottom = 14.dp).height(150.dp)
                    .clip(RoundedCornerShape(22.dp)).background(w.colour)
                    .clickable { onPick(w) }
                    .padding(18.dp),
            ) {
                // Black on a light card, white on a dark one. The same brightness test the
                // wallet name uses, applied to the card it is written on.
                val lum = 0.2126f * w.colour.red + 0.7152f * w.colour.green + 0.0722f * w.colour.blue
                val ink = if (lum > 0.5f) D.Black else D.White
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Ink chosen against the card, not assumed. These used to be dark on the
                    // understanding that every brand colour was light — true until Backpack
                    // became #212122, at which point its name, its address and its button all
                    // went invisible on their own card.
                    Text(w.name, color = ink.copy(alpha = .8f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("•••• ${w.address.takeLast(4)}", color = ink.copy(alpha = .6f),
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = Figures)
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(CircleShape).background(ink.copy(alpha = .16f)),
                        contentAlignment = Alignment.Center) {
                        HIcon(R.drawable.hi_wallet, ink.copy(alpha = .75f), 17.dp)
                    }
                    Spacer(Modifier.weight(1f))
                    // What "selected" means, said rather than implied by a tick: this is the
                    // wallet a tap pays from.
                    if (on) {
                        Row(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(D.White)
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            HIcon(R.drawable.hi_tick, D.Black, 15.dp)
                            Spacer(Modifier.width(5.dp))
                            Text("Tapping with this", color = D.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text("Use this one", color = ink.copy(alpha = .7f), fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (list.isEmpty()) {
            Text("No wallets linked yet.", color = D.Quiet, fontSize = 14.sp)
        }
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Done", onClose, Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * The people you can pay without tapping, as one dark pill across the top.
 *
 * A tap is the fastest way to pay someone standing in front of you and the slowest way to pay
 * someone who isn't. These are the faces already met — one press opens the same sheet a tap
 * would, addressed to them.
 *
 * The faces overlap in the order they are listed, each ringed in the pill's own colour so they
 * read as a stack rather than a smear. The ring is the pill, not white: on a dark pill a white
 * ring would draw more than the faces do.
 */
/**
 * Someone's phone, or their initial when we don't know it.
 *
 * The brand is what a person recognises across a table — "the OnePlus", "the Seeker" — which
 * is the whole reason the payload carries it. A letter is the fallback, not the default.
 */
/**
 * The people already met, under the wallet.
 *
 * It replaces both the dark "Send to" pill and the "hold your phone to theirs" panel. The pill
 * hid these behind three initials, and the panel explained a gesture to someone who has already
 * done it — this shows who can be paid and lets them be paid, which is what both were for.
 *
 * Each row names them the best way available: their `.sol` domain if they have one, since that
 * is the only one of the three they chose for themselves, then the phone's label, then nothing.
 * The address sits under it, short enough to recognise and too short to read out.
 */
@Composable
private fun People(list: List<Contact>, waiting: (String) -> Int, onAll: (() -> Unit)?, onPick: (Contact) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("PEOPLE", color = D.Quiet, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            // Anything whose payer or payee this phone never learned cannot be filed under a
            // person, so the flat list stays reachable — but only when it holds something.
            onAll?.let {
                Text("All activity", color = D.Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = it)
                        .padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            list.take(5).forEach { c ->
                // Asked once per person, on mainnet where SNS lives. Null stays null: an
                // address with no domain is the ordinary case, not a failure worth a
                // placeholder.
                val name by produceState<String?>(initialValue = null, c.address) { value = Names.sol(c.address) }
                val interaction = remember { MutableInteractionSource() }
                Column(
                    Modifier.width(66.dp).then(PressScale(interaction)).clip(RoundedCornerShape(14.dp))
                        .clickable(interaction, indication = null) { onPick(c) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                        // The initial, because there is no picture of a person to show and a
                        // stock silhouette would only pretend there was.
                        Box(
                            Modifier.fillMaxSize().clip(CircleShape).background(D.Fill),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text((name ?: c.label).take(1).uppercase(), color = D.Quiet,
                                fontSize = 21.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
                        }
                        // Their phone, small, in the corner: it says whose it is without
                        // competing with the initial for the middle of the circle.
                        Brand.look(c.brand)?.let { look ->
                            Box(
                                Modifier.align(Alignment.TopEnd).size(21.dp).clip(CircleShape)
                                    .background(D.White).padding(1.5.dp).clip(CircleShape)
                                    .background(look.colour),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (look.picture) {
                                    Image(painterResource(look.drawable), contentDescription = null,
                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                } else {
                                    HIcon(look.drawable, D.White, 12.dp)
                                }
                            }
                        }
                        if (c.seeker) {
                            Box(
                                Modifier.align(Alignment.BottomEnd).size(19.dp).clip(CircleShape)
                                    .background(D.White),
                                contentAlignment = Alignment.Center,
                            ) { HIcon(R.drawable.hi_verified, D.Blue, 17.dp) }
                        }
                        // What is outstanding with this person, on this person. The home page
                        // used to carry one "12 waiting" row for everybody, which said how much
                        // was open and never with whom.
                        val open = waiting(c.address)
                        if (open > 0) {
                            Box(
                                Modifier.align(Alignment.BottomStart).size(19.dp).clip(CircleShape)
                                    .background(D.Blue),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(if (open > 9) "9+" else "$open", color = D.White, fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold, style = Figures)
                            }
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(name ?: c.label.ifBlank { "Unknown" }, color = D.Black, fontSize = 12.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * Everything that has ever passed between this phone and one person, as a conversation.
 *
 * Requests used to live in one "Activity" list, ordered by what needed attention, with the
 * other party reduced to a label on the row. That list answered "what is outstanding" and
 * could not answer "what is going on with them" — which is the question anyone actually has
 * when they open a payments app, and the reason every messaging app files by person.
 *
 * Two directions, drawn as two sides:
 *
 *   * **Left** — what they asked of us, read off a tap and kept in [Inbox]. Payable ones carry
 *     their own Pay and Decline, here rather than on a separate screen.
 *   * **Right** — what we asked of them, kept in [Watch] and settled by the chain.
 *
 * Oldest at the top, like a chat, so the newest thing is where the thumb already is.
 *
 * **A request with no peer recorded cannot appear here.** Only requests served since the field
 * existed know who took them; older ones stay in the Activity list rather than being filed
 * under a guess.
 */
@Composable
private fun Thread(
    who: Contact,
    toPay: List<Inbox.Item>,
    owed: List<Watch.Entry>,
    notes: List<Messages.Note>,
    paying: String?,
    onPay: (Inbox.Item) -> Unit,
    onDecline: (Inbox.Item) -> Unit,
    onWithdraw: (Watch.Entry) -> Unit,
    onBack: () -> Unit,
    onOpen: () -> Unit,
    onSay: (String) -> Unit,
) {
    val name by produceState<String?>(initialValue = null, who.address) { value = Names.sol(who.address) }
    val theirs = toPay.filter { it.request.recipient == who.address }
    val ours = owed.filter { it.peer == who.address }
    val said = notes.filter { it.peer == who.address }
    // One list, one clock. `Any` rather than a sealed wrapper because the two shapes already
    // carry everything a row needs and copying them into a third type would be a place for the
    // two to drift apart.
    val lines: List<Any> = (theirs + ours + said).sortedBy {
        when (it) {
            is Inbox.Item -> it.readAt
            is Watch.Entry -> it.createdAt
            is Messages.Note -> it.at
            else -> 0L
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(D.Fill).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { HIcon(R.drawable.hi_arrow_left, D.Black, 19.dp) }
            Spacer(Modifier.width(10.dp))
            Face(who, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name ?: who.label.ifBlank { "Unknown" }, color = D.Black, fontSize = 19.sp,
                        fontWeight = FontWeight.Bold, fontFamily = Display, maxLines = 1)
                    if (who.seeker) {
                        Spacer(Modifier.width(5.dp))
                        HIcon(R.drawable.hi_verified, D.Blue, 16.dp)
                    }
                }
                Text(who.short, color = D.Quiet, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // Built from the bottom up, newest first, which is what `reverseLayout` means: the list
        // is ANCHORED to the end instead of being scrolled there afterwards. Two attempts at
        // scrolling to the end — by pixel and by item — both landed a bubble short, because the
        // height they aim at is only known after layout. There is nothing to aim at here.
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // One Column, because a lazy item lays its children out on top of each other.
            if (lines.isEmpty()) item {
                Column {
                    Spacer(Modifier.height(30.dp))
                    Text("Nothing between you yet", color = D.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Text("Say hello, ask ${who.label} for money, or tap phones to pay them.",
                        color = D.Quiet, fontSize = 13.sp)
                }
            }
            items(lines.asReversed()) { line ->
                when (line) {
                    is Inbox.Item -> Bubble(
                        mine = false,
                        amount = "${line.request.amount} ${line.request.symbol}",
                        at = line.paidAt ?: line.readAt,
                        state = when {
                            line.paid -> "You paid this"
                            line.declined -> "You declined"
                            line.withdrawn -> "They took it back"
                            else -> "Asked you"
                        },
                        mood = when {
                            line.paid -> Mood.DONE
                            line.declined -> Mood.REFUSED
                            line.withdrawn -> Mood.DROPPED
                            else -> Mood.OPEN
                        },
                    ) {
                        if (line.payable) {
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onPay(line) },
                                    enabled = paying == null,
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = D.Blue, contentColor = D.White),
                                ) {
                                    Text(if (paying == line.request.reference) "…" else "Pay",
                                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                                TextButton(
                                    onClick = { onDecline(line) },
                                    enabled = paying == null,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                ) { Text("Decline", color = D.Danger, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                    }
                    is Messages.Note -> Say(line)
                    is Watch.Entry -> Bubble(
                        mine = true,
                        amount = "${line.amount} ${line.symbol}",
                        at = line.paidAt ?: line.withdrawnAt ?: line.createdAt,
                        state = when {
                            line.paid -> "They paid you"
                            line.rejected -> "${line.rejectedBy} declined"
                            line.withdrawn -> "You took this back"
                            line.pending -> "${line.deferredBy} will pay later"
                            else -> "Waiting for them"
                        },
                        mood = when {
                            line.paid -> Mood.DONE
                            line.rejected -> Mood.REFUSED
                            line.withdrawn -> Mood.DROPPED
                            else -> Mood.OPEN
                        },
                    ) {
                        // Only while it can still be paid. A request they declined, or one
                        // already taken back, has nothing left to take back.
                        if (line.live && !line.rejected) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Take it back",
                                color = D.White.copy(alpha = .85f), fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { onWithdraw(line) }.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(D.Line))

        // Words before money. Checking you have the right person is the thing everyone does
        // before handing over cash in person, and the only alternative this app offered was
        // reading 44 base58 characters off a screen — which nobody does.
        var draft by remember(who.address) { mutableStateOf("") }
        val sendable = draft.isNotBlank()
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(D.Fill)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (draft.isEmpty()) {
                    Text("Message ${who.label}", color = D.Quiet, fontSize = 14.sp, maxLines = 1)
                }
                BasicTextField(
                    value = draft,
                    // Capped where the server caps it, so nothing is typed that cannot be sent.
                    onValueChange = { if (it.length <= Messages.MAX_BODY) draft = it },
                    textStyle = TextStyle(color = D.Black, fontSize = 14.sp, fontFamily = Ui),
                    cursorBrush = SolidColor(D.Blue),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (sendable) D.Blue else D.Fill)
                    .clickable(enabled = sendable) { onSay(draft.trim()); draft = "" },
                contentAlignment = Alignment.Center,
            ) { HIcon(R.drawable.hi_arrow_up_right, if (sendable) D.White else D.Quiet, 20.dp) }
        }

        // One button, because the sheet it opens is one screen: a Pay form with "Request
        // instead" on it. Two buttons here would promise two destinations and deliver one.
        PrimaryButton("Pay or request", onClick = onOpen, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
    }
}

/**
 * One item in a thread. Ours on the right in blue, theirs on the left in grey — the arrangement
 * everybody already reads without being taught it.
 *
 * The corner nearest the speaker is squared off, which is what makes a stack of these read as
 * two voices rather than a list of cards.
 */
/**
 * A line of text in a thread. No amount, no state, no check — a message is only ever said.
 *
 * Deliberately quieter than a [Bubble]: money is what this screen is for, and if words were
 * drawn at the same weight the amounts would stop standing out in a scroll.
 */
@Composable
private fun Say(note: Messages.Note) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (note.mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 260.dp)
                .clip(RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (note.mine) 18.dp else 5.dp,
                    bottomEnd = if (note.mine) 5.dp else 18.dp,
                ))
                .background(if (note.mine) D.Blue else D.Fill)
                .padding(horizontal = 15.dp, vertical = 10.dp),
        ) {
            Text(note.body, color = if (note.mine) D.White else D.Black, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(ago(note.at), color = if (note.mine) D.White.copy(alpha = .7f) else D.Quiet, fontSize = 10.sp)
        }
    }
}

/** How a row ended, which is the only thing that decides how it is coloured. */
private enum class Mood { OPEN, DONE, REFUSED, DROPPED }

@Composable
private fun Bubble(
    mine: Boolean,
    amount: String,
    at: Long,
    state: String,
    mood: Mood,
    extra: @Composable ColumnScope.() -> Unit,
) {
    // Blue is only ever "ours, and still alive". A refusal is red on both sides — whoever said
    // no, the row means the same thing — and something taken back is grey, because nothing
    // happened and nothing will.
    val ground = when {
        mood == Mood.REFUSED -> D.Danger.copy(alpha = .10f)
        mood == Mood.DROPPED -> D.Fill
        mine -> D.Blue
        else -> D.Fill
    }
    val ink = when {
        mood == Mood.REFUSED -> D.Danger
        mood == Mood.DROPPED -> D.Quiet
        mine -> D.White
        else -> D.Black
    }
    val faint = when {
        mood == Mood.REFUSED -> D.Danger.copy(alpha = .75f)
        mood == Mood.DROPPED -> D.Quiet
        mine -> D.White.copy(alpha = .8f)
        else -> D.Quiet
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 260.dp)
                .clip(RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (mine) 18.dp else 5.dp,
                    bottomEnd = if (mine) 5.dp else 18.dp,
                ))
                .background(ground)
                .padding(horizontal = 15.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(amount, color = ink, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                    fontFamily = Display, style = Figures,
                    textDecoration = if (mood == Mood.DROPPED) TextDecoration.LineThrough else null)
                if (mood == Mood.DONE) {
                    Spacer(Modifier.width(7.dp))
                    HIcon(R.drawable.hi_verified, if (mine) D.White else D.Success, 16.dp)
                }
            }
            Spacer(Modifier.height(3.dp))
            Text("$state · ${ago(at)}", color = faint, fontSize = 11.sp)
            extra()
        }
    }
}

@Composable
private fun Face(c: Contact, size: Dp, ring: Color? = null) {
    val look = Brand.look(c.brand)
    val tint = if (c.seeker) D.Blue else D.Quiet
    Box(
        Modifier.size(size).clip(CircleShape)
            .then(if (ring != null) Modifier.background(ring).padding(2.dp) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape)
                .background(look?.colour ?: tint.copy(alpha = .16f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                look == null -> Text(c.label.take(1).uppercase(), color = tint,
                    fontSize = size.value.times(0.38f).sp, fontWeight = FontWeight.Bold, fontFamily = Display)
                look.picture -> Image(painterResource(look.drawable), contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else -> HIcon(look.drawable, D.White, size * 0.5f)
            }
        }
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
) {
    Column {
        Tag("1", checking)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Their initial rather than a generic silhouette: this app already draws every
            // OTHER person as a letter in a circle, and drawing yourself the same way says
            // "this is you, in the same terms as them". It is also the only picture of
            // somebody the app is ever entitled to — nobody uploaded a photo.
            val avatar = remember { MutableInteractionSource() }
            Box(
                Modifier.then(PressScale(avatar)).size(38.dp).clip(CircleShape).background(D.Fill)
                    .clickable(avatar, indication = null, onClick = onProfile),
                contentAlignment = Alignment.Center,
            ) {
                Text(me.take(1).uppercase().ifBlank { "Y" }, color = D.Quiet, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, fontFamily = Display)
            }
            Spacer(Modifier.width(11.dp))
            // Long-press the wordmark for diagnostics and the exchange log. The status line
            // that used to carry them is gone, and they are a builder's tool, not a user's —
            // but they are how every NFC problem in this app has been read, so they keep a
            // door. An invisible one, which is the right size for the audience.
            Text("AirPay", color = D.Black, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
                fontFamily = Display, letterSpacing = (-0.5).sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).combinedClickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onLongClick = onCheck, onClick = {},
                ))
            Spacer(Modifier.weight(1f))
            val interaction = remember { MutableInteractionSource() }
            Box(contentAlignment = Alignment.TopEnd) {
                Box(
                    Modifier.then(PressScale(interaction)).size(42.dp).clip(CircleShape)
                        .clickable(interaction, indication = null, onClick = onAlerts),
                    contentAlignment = Alignment.Center,
                ) { HIcon(R.drawable.hi_bell, D.Black, 23.dp) }
                // A count, not a dot: "three things happened" is worth opening, "something
                // happened" is what a dot says and it says it identically for one and for ten.
                if (unread > 0) {
                    Box(
                        Modifier.padding(top = 3.dp, end = 1.dp).defaultMinSize(18.dp, 18.dp)
                            .clip(CircleShape).background(D.White).padding(2.dp)
                            .clip(CircleShape).background(D.Blue)
                            .padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (unread > 9) "9+" else "$unread", color = D.White, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold, style = Figures)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, active: Boolean, enabled: Boolean, m: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val bg = when {
        !enabled -> D.Fill
        active -> D.Blue
        else -> D.Fill
    }
    Box(
        m.then(PressScale(interaction)).height(56.dp).clip(RoundedCornerShape(16.dp)).background(bg)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active && enabled) D.White else if (enabled) D.Black else D.Quiet,
            fontSize = 16.sp, fontWeight = FontWeight.Bold, style = Figures)
    }
}

@Composable
private fun LogView(log: List<String>) {
    val state = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) state.animateScrollToItem(log.size - 1) }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(D.Fill).padding(14.dp),
    ) {
        items(log) { line ->
            Text(line,
                color = when {
                    line.startsWith("✓") -> D.Success
                    line.startsWith("✗") -> D.Danger
                    else -> D.Quiet
                },
                fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 1.dp))
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
        title = { Text("Pay ${req.label}?", color = D.Black) },
        text = {
            Column {
                Text("${req.amount} ${req.token}", color = D.Blue,
                    fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("to ${req.recipient.take(8)}…${req.recipient.takeLast(6)}",
                    color = D.Quiet, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                if (req.genesis != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("✓ Verified Seeker", color = D.Blue, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text("No rush — this stays in Requests until you pay it.", color = D.Quiet, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onPay, enabled = !busy) {
                Text(if (busy) "…" else "Pay", color = D.Blue, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDecline, enabled = !busy) { Text("Decline", color = D.Danger) }
                TextButton(onClick = onLater) { Text("Later", color = D.Quiet) }
            }
        },
    )
}

@Composable
private fun BackHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(D.Fill).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { HIcon(R.drawable.hi_arrow_left, D.Black, 19.dp) }
        Spacer(Modifier.width(12.dp))
        Text(title, color = D.Black, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Display)
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
    Row(
        Modifier.fillMaxWidth().then(PressScale(interaction)).clip(RoundedCornerShape(16.dp))
            .background(D.Fill).clickable(interaction, indication = null, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = .14f)),
            contentAlignment = Alignment.Center) { HIcon(icon, tint, 17.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = D.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = D.Quiet, fontSize = 12.5.sp)
        }
        HIcon(R.drawable.hi_arrow_up_right, D.Quiet, 17.dp)
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
    Text(
        "Offers and rewards", color = D.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/**
 * Rewards, offers, referrals — three ways in, side by side.
 *
 * Drawn rather than iconed, and square rather than round, because the obvious version of this
 * row is three flat circles and that is a shape people have already seen in every payments app.
 * The marks are built from AirPay's own vocabulary: the discs of a balance, the notch of a
 * ticket, two rings meeting the way the avatars do on a tap.
 */
@Composable
private fun EarnTiles(onRewards: () -> Unit, onOffers: () -> Unit, onReferrals: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EarnTile("Rewards", Mark.Coins, Modifier.weight(1f), onRewards)
        EarnTile("Offers", Mark.Ticket, Modifier.weight(1f), onOffers)
        EarnTile("Referrals", Mark.Rings, Modifier.weight(1f), onReferrals)
    }
}

private enum class Mark { Coins, Ticket, Rings, Signal, Screen, Receipt, Basket }

/**
 * One section of the shop: what is in it, and what it costs.
 *
 * Everything here is bought with **real USDC on mainnet**, which is the only place in this app
 * where the money is real — so the screen says so once at the top and again on the button. A
 * devnet transfer to an invoice address would be a payment into nothing.
 */
@Composable
private fun ShopSection(
    section: Shop.Section,
    picked: Shop.Product?,
    onPick: (Shop.Product?) -> Unit,
    onBuy: (Shop.Product, String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(14.dp))
                .background(D.Blue.copy(alpha = .08f)).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HIcon(R.drawable.hi_info, D.Blue, 17.dp)
            Spacer(Modifier.width(8.dp))
            Text("Paid with real USDC on mainnet — taps stay on devnet.",
                color = D.Blue, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        }
        section.products.forEach { p ->
            val open = picked?.id == p.id
            Column(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp))
                    .background(D.Fill).clickable { onPick(if (open) null else p) }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name.removeSuffix(" India"), color = D.Black, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            when {
                                p.needsPhone -> "Credited to a phone number"
                                "online" in p.redemption -> "Code delivered to you"
                                else -> "${p.values.size} amounts"
                            },
                            color = D.Quiet, fontSize = 12.sp,
                        )
                    }
                    HIcon(if (open) R.drawable.hi_arrow_down else R.drawable.hi_arrow_up_right, D.Quiet, 17.dp)
                }
                // The denominations only appear for the row that was opened, because 156
                // products with every price showing is a list nobody reads.
                AnimatedVisibility(visible = open) {
                    Column(Modifier.padding(top = 12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            p.values.forEach { v ->
                                Text(
                                    "${symbolFor(p.currency)}$v", color = D.White, fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold, style = Figures,
                                    modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                        .background(D.Blue).clickable { onBuy(p, v) }
                                        .padding(horizontal = 15.dp, vertical = 9.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun symbolFor(currency: String) = when (currency) {
    "INR" -> "₹"
    "USD" -> "$"
    "EUR" -> "€"
    else -> "$currency "
}

/**
 * What can actually be bought, as tiles.
 *
 * The catalogue has 156 products in eight sections; a person opening this wants "top up my
 * phone", not a shop. So the sections are the doorway and the products live behind them —
 * and a section with nothing in it is not drawn at all, because a tile that opens an empty
 * shelf is worse than no tile.
 */
@Composable
private fun ShopTiles(sections: List<Shop.Section>, onOpen: (Shop.Section) -> Unit) {
    val marks = mapOf(
        "Phone" to Mark.Signal, "TV & streaming" to Mark.Screen,
        "Bills & wallets" to Mark.Receipt,
    )
    val shown = sections.filter { it.products.isNotEmpty() }.take(4)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.forEach { sec ->
            EarnTile(
                label = when (sec.title) {
                    "TV & streaming" -> "TV"
                    "Bills & wallets" -> "Bills"
                    else -> sec.title
                },
                mark = marks[sec.title] ?: Mark.Basket,
                modifier = Modifier.weight(1f),
            ) { onOpen(sec) }
        }
    }
}

/**
 * The hand-drawn category marks, on their own so a 38dp history row and a 34dp tile can use
 * the same shape. Everything inside is sized from the canvas width, so it scales.
 */
@Composable
private fun MarkCanvas(mark: Mark, side: Dp) {
    // `side`, not `size`: inside a Canvas, `size` is the draw scope's own and shadowing it
    // turns every `size.width` below into a Dp.
    Canvas(Modifier.size(side)) {
            val w = size.width
            val blue = D.Blue
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
                        drawCircle(D.Fill, radius = w * .07f,
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
                    drawCircle(D.Fill, radius = w * .12f, center = Offset(w * .08f, w * .5f))
                    drawCircle(D.Fill, radius = w * .12f, center = Offset(w * .92f, w * .5f))
                }
                // Two rings meeting, the way the avatars meet when phones touch.
                Mark.Rings -> {
                    val r = w * .26f
                    drawCircle(blue.copy(alpha = .35f), r, Offset(w * .34f, w * .5f), style = Stroke(w * .11f))
                    drawCircle(blue, r, Offset(w * .66f, w * .5f), style = Stroke(w * .11f))
                }
            }
    }
}

@Composable
private fun EarnTile(label: String, mark: Mark, modifier: Modifier, onClick: () -> Unit) {  // also the shop's tiles
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.then(PressScale(interaction)).clip(RoundedCornerShape(18.dp)).background(D.Fill)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MarkCanvas(mark, 34.dp)
        Spacer(Modifier.height(10.dp))
        Text(label, color = D.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Everything waiting on someone, in one place: requests this phone can still pay, requests
 * others still owe it, and what has already settled.
 *
 * Nothing here counts down. A request is good until its reference is used, so the list is
 * ordered by what needs attention, not by what is about to vanish.
 */
@Composable
private fun RequestsTab(
    toPay: List<Inbox.Item>,
    owed: List<Watch.Entry>,
    paying: String?,
    onPay: (Inbox.Item) -> Unit,
    onDismissToPay: (Inbox.Item) -> Unit,
    onDismissOwed: (Watch.Entry) -> Unit,
) {
    val openToPay = toPay.filter { !it.paid }
    val openOwed = owed.filter { !it.paid }
    val history = (toPay.filter { it.paid }.map { Triple(it.paidAt ?: 0L, "Paid ${it.request.amount} ${it.request.symbol} to ${it.request.label}", it.signature!!) } +
        owed.filter { it.paid }.map { Triple(it.paidAt ?: 0L, "Received ${it.amount} ${it.symbol}", it.signature!!) })
        .sortedByDescending { it.first }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (openToPay.isEmpty() && openOwed.isEmpty() && history.isEmpty()) {
            Spacer(Modifier.height(40.dp))
            Text("Nothing here yet", color = D.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Requests you tap land here, and stay until they're paid.", color = D.Quiet, fontSize = 13.sp)
            return@Column
        }

        if (openToPay.isNotEmpty()) {
            SectionLabel("TO PAY")
            openToPay.forEach { item ->
                // A withdrawn request is greyed and explained rather than deleted. Removing the
                // row under someone who is mid-payment — or has just approved in their wallet —
                // is more confusing than leaving it with a reason.
                val gone = item.withdrawn
                Row(RowStyle, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${item.request.amount} ${item.request.symbol}", color = if (gone) D.Quiet else D.Black,
                                fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            if (gone) {
                                Spacer(Modifier.width(8.dp))
                                Text("WITHDRAWN BY ${item.request.label.uppercase()}", color = D.Danger,
                                    fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.background(D.Danger.copy(alpha = .12f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp))
                            } else if (item.request.genesis != null) {
                                Spacer(Modifier.width(6.dp))
                                Text("SEEKER", color = D.Blue, fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            if (gone) "${item.request.label} took this back"
                            else "${item.request.label} · tapped ${ago(item.readAt)}",
                            color = D.Quiet, fontSize = 11.sp,
                        )
                    }
                    TextButton(onClick = { onDismissToPay(item) }, enabled = paying == null) {
                        Text("Remove", color = D.Quiet, fontSize = 12.sp)
                    }
                    if (!gone) {
                        Button(
                            onClick = { onPay(item) }, enabled = paying == null,
                            colors = ButtonDefaults.buttonColors(containerColor = D.Blue, contentColor = D.White),
                        ) { Text(if (paying == item.request.reference) "…" else "Pay", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (openOwed.isNotEmpty()) {
            SectionLabel("WAITING FOR PAYMENT")
            openOwed.forEach { e ->
                // Declined requests stay, greyed: they may still tap again or pay anyway, and a
                // row that vanishes tells the asker nothing about what happened.
                val no = e.rejected
                // "Later" is its own state, between waiting and declined: somebody saw the
                // request and said something, and the money can still arrive. Without it the
                // asker cannot tell "they will pay tonight" from "they never looked".
                val later = e.pending
                Row(RowStyle, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${e.amount} ${e.symbol}", color = if (no) D.Quiet else D.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            if (no || later) {
                                val tag = if (no) "DECLINED BY ${e.rejectedBy!!.uppercase()}" else "PENDING"
                                val tone = if (no) D.Danger else D.Blue
                                Spacer(Modifier.width(8.dp))
                                Text(tag, color = tone, fontSize = 8.sp,
                                    letterSpacing = 1.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.background(tone.copy(alpha = .12f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        Text(
                            when {
                                no -> "${e.rejectedBy} said no · nothing was paid"
                                later -> "${e.deferredBy} will pay later · still watching for it"
                                else -> "requested ${ago(e.createdAt)} · ${e.reference.take(8)}…"
                            },
                            color = D.Quiet, fontSize = 11.sp,
                            fontFamily = if (no || later) FontFamily.Default else FontFamily.Monospace,
                        )
                    }
                    TextButton(onClick = { onDismissOwed(e) }) { Text("Remove", color = D.Quiet, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (history.isNotEmpty()) {
            SectionLabel("DONE")
            history.forEach { (at, what, sig) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("✓", color = D.Blue, fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(what, color = D.Black, fontSize = 13.sp)
                        Text("${ago(at)} · ${sig.take(10)}…", color = D.Quiet, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
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
 * Tap is the filled one. It is the thing AirPay does that nothing else does; a QR reader is
 * table stakes, and making them look equal would be a claim about the product that is not true.
 */
@Composable
private fun PayBar(modifier: Modifier, onTap: () -> Unit, onScan: () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        // The page scrolls UNDER this, so it needs a floor. Without one the buttons sat on
        // top of whatever happened to be behind them — the bill tiles, half of a history row —
        // and read as two stickers dropped on the page rather than a bar it scrolls beneath.
        // A fade rather than a hard edge, so nothing is ever abruptly cut in half.
        Box(
            Modifier.fillMaxWidth().height(28.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, D.White))),
        )
        Row(
            Modifier.fillMaxWidth().background(D.White).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // §7 — see SECTIONS.md. Shown with the rest of them.

            PayChoice("Pay via Tap", R.drawable.hi_nfc, filled = true, modifier = Modifier.weight(1f), onClick = onTap)
            PayChoice("Scan any QR", R.drawable.hi_qr, filled = false, modifier = Modifier.weight(1f), onClick = onScan)
        }
        // Carries the white down to the very bottom, so nothing shows in the gap beneath it.
        Box(Modifier.fillMaxWidth().height(48.dp).background(D.White))
    }
}

@Composable
private fun PayChoice(
    label: String,
    @DrawableRes icon: Int,
    filled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val ink = if (filled) D.White else D.Black
    Row(
        modifier.then(PressScale(interaction)).height(56.dp).clip(RoundedCornerShape(18.dp))
            .background(if (filled) D.Blue else D.White)
            .then(if (filled) Modifier else Modifier.border(1.5.dp, D.Line, RoundedCornerShape(18.dp)))
            .clickable(interaction, indication = null, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HIcon(icon, ink, 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * The shelf a purchase came off. Null for a payment between people — those get an arrow
 * instead, because the useful thing about them is which way the money went, and a mark that
 * looked like a category would say they were all the same kind of thing.
 */
private fun markFor(t: History.Txn): Mark? = when {
    t.kind != History.Kind.SHOP -> null
    else -> when (t.category) {
        "Phone" -> Mark.Signal
        "TV & streaming" -> Mark.Screen
        "Bills & wallets" -> Mark.Receipt
        else -> Mark.Basket
    }
}

/**
 * One line of history. The amount carries the sign, because that is the thing being scanned
 * for — a column of figures where in and out look alike has to be read word by word.
 */
@Composable
private fun TxnRow(t: History.Txn, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen)
            .padding(vertical = 9.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(D.Fill),
            contentAlignment = Alignment.Center,
        ) {
            val mark = markFor(t)
            when {
                mark != null -> MarkCanvas(mark, 21.dp)
                t.incoming -> HIcon(R.drawable.hi_arrow_down, D.Success, 19.dp)
                else -> HIcon(R.drawable.hi_arrow_up_right, D.Black, 19.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = D.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.height(1.dp))
            Text(t.note ?: t.category, color = if (t.note != null) D.Blue else D.Quiet, fontSize = 12.sp, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (t.incoming) "+" else "−") + t.amount,
                color = if (t.incoming) D.Success else D.Black,
                fontSize = 14.sp, fontWeight = FontWeight.Bold, style = Figures, maxLines = 1,
            )
            Spacer(Modifier.height(1.dp))
            Text(ago(t.at), color = D.Quiet, fontSize = 10.sp, style = Figures)
        }
    }
}

/** The home screen's tail: the last three things that moved money, and a way to the rest. */
@Composable
private fun HistoryStrip(list: List<History.Txn>, onViewAll: () -> Unit, onOpen: (History.Txn) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Transaction history", color = D.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("View all", color = D.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onViewAll)
                    .padding(horizontal = 6.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(6.dp))
        list.take(3).forEach { TxnRow(it) { onOpen(it) } }
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
            Chevron(R.drawable.hi_arrow_left, older != null) { older?.let(onMonth) }
            Text(
                month.format(MONTH),
                color = D.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = Display,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .clickable { picking = true }.padding(vertical = 8.dp),
            )
            // Mirrored, because there is no right chevron in the set and it is the same arrow.
            Chevron(R.drawable.hi_arrow_left, newer != null, flip = true) { newer?.let(onMonth) }
        }

        if (rows.isEmpty()) {
            Spacer(Modifier.height(34.dp))
            Text("Nothing in ${month.format(MONTH)}", color = D.Black, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text("Payments, requests you were paid, and anything you bought will show up here.",
                color = D.Quiet, fontSize = 13.sp)
        } else {
            // Counted, not summed: the rows carry several tokens and a single figure adding
            // SOL to USDC to rupees would be a number that means nothing.
            Text(
                "${rows.size} transaction${if (rows.size == 1) "" else "s"}",
                color = D.Quiet, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
            )
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(rows) { t -> TxnRow(t) { onOpen(t) } }
            }
        }
    }

    if (picking) {
        MonthSheet(months, month, onPick = { picking = false; onMonth(it) }, onClose = { picking = false })
    }
}

@Composable
private fun Chevron(@DrawableRes icon: Int, enabled: Boolean, flip: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(if (enabled) D.Fill else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        HIcon(icon, if (enabled) D.Black else D.Line, 18.dp,
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
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(D.Line, CircleShape))
            Spacer(Modifier.height(16.dp))
            Text("Jump to a month", color = D.Black, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                fontFamily = Display)
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(months) { m ->
                    val on = m == current
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (on) D.Blue.copy(alpha = .1f) else Color.Transparent)
                            .clickable { onPick(m) }.padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(m.format(MONTH), color = if (on) D.Blue else D.Black, fontSize = 14.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        if (on) HIcon(R.drawable.hi_tick, D.Blue, 16.dp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * The notification panel: what has happened, and what is still waiting on someone.
 *
 * One list rather than a badge per section. A request waiting on you, a decline, a message and
 * a recharge that has not arrived are all "something you would want to know", and splitting
 * them by which part of the app they came from asks the reader to know that first.
 *
 * Unread is drawn as a rail down the left of the row rather than a coloured background: the
 * rows already use colour to say what KIND of thing happened, and a second colour meaning
 * "new" on top of it made both unreadable.
 */
@Composable
private fun AlertsTab(list: List<Alerts.Alert>, seenAt: Long, onOpen: (Alerts.Alert) -> Unit) {
    if (list.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 40.dp)) {
            Text("Nothing new", color = D.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Requests, replies, messages and anything you've bought show up here.",
                color = D.Quiet, fontSize = 13.sp)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(list) { a ->
            val look = alertLook(a.kind)
            val fresh = a.at > seenAt
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .clickable { onOpen(a) }.padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape).background(look.second.copy(alpha = .12f)),
                    contentAlignment = Alignment.Center,
                ) { HIcon(look.first, look.second, 19.dp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.title, color = D.Black, fontSize = 14.sp,
                        fontWeight = if (fresh) FontWeight.Bold else FontWeight.SemiBold, maxLines = 2)
                    Spacer(Modifier.height(1.dp))
                    Text(a.detail, color = D.Quiet, fontSize = 12.sp, maxLines = 2)
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(ago(a.at), color = D.Quiet, fontSize = 10.sp, style = Figures)
                    if (fresh) {
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.size(7.dp).clip(CircleShape).background(D.Blue))
                    }
                }
            }
        }
    }
}

/** The icon and colour for each kind. Money in is green, a refusal is red, the rest are calm. */
private fun alertLook(kind: Alerts.Kind): Pair<Int, Color> = when (kind) {
    Alerts.Kind.ASKED -> R.drawable.hi_exchange to D.Blue
    Alerts.Kind.DECLINED -> R.drawable.hi_cancel to D.Danger
    Alerts.Kind.LATER -> R.drawable.hi_clock to D.Blue
    Alerts.Kind.RECEIVED -> R.drawable.hi_tick to D.Success
    Alerts.Kind.WITHDRAWN -> R.drawable.hi_cancel to D.Quiet
    Alerts.Kind.MESSAGE -> R.drawable.hi_info to D.Blue
    Alerts.Kind.OFFER -> R.drawable.hi_gift to D.Blue
    Alerts.Kind.ORDER -> R.drawable.hi_wallet to D.Blue
}

/**
 * What paying in person has earned, and what it hasn't.
 *
 * Every number here is the server's, read fresh each time the tab opens — the phone keeps no
 * running total of its own, because a total the phone invented would drift from the one that
 * decides payouts. When the server can't be reached the screen says so instead of showing a
 * stale or hopeful figure.
 *
 * The refusals are shown next to the earnings on purpose. A rewards screen that only lists
 * what was paid makes the rules look arbitrary the first time one of them refuses, and this
 * layer's whole defence against farming is rules that refuse.
 */
@Composable
private fun RewardsTab(connected: String?, isSeeker: Boolean) {
    var summary by remember { mutableStateOf<Rewards.Summary?>(null) }
    var offers by remember { mutableStateOf<List<Rewards.Offer>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var claiming by remember { mutableStateOf<String?>(null) }
    /** Why a claim was refused. Shown under the list rather than in a dialog: it is
     *  information, not an interruption. */
    var claimProblem by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(connected, refresh) {
        loading = true
        // Both at once: they are independent reads, and one after the other made the screen
        // wait twice — long enough that the offers looked absent rather than pending.
        val me = connected
        if (me == null) {
            summary = null; offers = emptyList()
        } else coroutineScope {
            val s = async { Rewards.summary(me) }
            val o = async { Rewards.offers(me) }
            summary = s.await()
            offers = o.await() ?: emptyList()
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (connected == null) {
            Spacer(Modifier.height(40.dp))
            Text("Connect a wallet", color = D.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Rewards are earned by the wallet that pays, so there is nothing to show until one is connected.",
                color = D.Quiet, fontSize = 13.sp)
            return@Column
        }
        val s = summary
        if (s == null) {
            Spacer(Modifier.height(40.dp))
            Text(if (loading) "Checking…" else "Can't reach the server", color = D.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (!loading) {
                Spacer(Modifier.height(6.dp))
                Text("Your rewards are safe on the server — this screen just can't read them right now.",
                    color = D.Quiet, fontSize = 13.sp)
            }
            return@Column
        }

        // Offers first: they are the thing to do, the balance is the thing that happened.
        if (loading && offers.isEmpty()) {
            SectionLabel("OFFERS")
            Text("Checking…", color = D.Quiet, fontSize = 13.sp, modifier = Modifier.padding(bottom = 14.dp))
        }
        if (offers.isNotEmpty()) {
            SectionLabel("OFFERS")
            offers.forEach { offer ->
                Column(RowStyle) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(offer.what, color = D.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (offer.funder == "duffle") "${offer.app} · from AirPay"
                                else "${offer.app} · from ${offer.funder}",
                                color = D.Quiet, fontSize = 12.sp,
                            )
                        }
                        if (offer.reward > 0) {
                            Text("${offer.reward} SKR", color = D.Blue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    when {
                        offer.state == "paid" ->
                            Text("✓ Earned ${offer.earned} SKR", color = D.Blue, fontSize = 13.sp)
                        offer.waiting -> {
                            // Claimed and waiting. The reason it hasn't paid is worth showing:
                            // "pay again" is actionable, "nothing happened" is not.
                            Text(Rewards.offerWhy(offer.refused)
                                ?: "Claimed · pay ${offer.app} within ${offer.windowHours}h",
                                color = D.Quiet, fontSize = 13.sp)
                        }
                        offer.state == "expired" ->
                            Text("The window closed — claim it again", color = D.Quiet, fontSize = 13.sp)
                        // Say it before, not after. A green Claim button on a phone that can
                        // never claim is the same mistake as a reward rule nobody hears about
                        // until they are not paid — and this one is answerable on the spot,
                        // because the app already knows whether this wallet holds Genesis.
                        !isSeeker -> Text("Seeker only — this wallet holds no Genesis Token",
                            color = D.Quiet, fontSize = 13.sp)
                        else -> Button(
                            onClick = {
                                val me = connected ?: return@Button
                                claiming = offer.id
                                scope.launch {
                                    val why = Rewards.claim(offer.id, me)
                                    claiming = null
                                    if (why == null) refresh++ else claimProblem = Rewards.offerWhy(why)
                                }
                            },
                            enabled = claiming == null,
                            colors = ButtonDefaults.buttonColors(containerColor = D.Blue, contentColor = D.White),
                            shape = RoundedCornerShape(8.dp),
                        ) { Text(if (claiming == offer.id) "…" else "Claim", fontWeight = FontWeight.Bold) }
                    }
                }
            }
            claimProblem?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = D.Danger, fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
        }

        // The total, with the split underneath. "Locked" is not a footnote: a reward can still
        // be reversed for a week, and saying so now is cheaper than explaining it after.
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(D.Fill).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${s.total}", color = D.Blue, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text("SKR", color = D.Blue, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    s.total == 0 && !isSeeker ->
                        "Rewards are for Seeker owners — this wallet holds no Genesis Token"
                    s.total == 0 -> "Nothing yet — a payment made by tapping earns ${s.perPayment} SKR"
                    s.locked > 0 && s.ready > 0 -> "${s.ready} ready · ${s.locked} still locked"
                    s.locked > 0 -> "Locked for ${s.lockDays} days, then yours"
                    else -> "${s.ready} ready"
                },
                color = D.Quiet, fontSize = 13.sp,
            )
            if (s.settled > 0) {
                Spacer(Modifier.height(2.dp))
                Text("${s.settled} paid out", color = D.Quiet, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        // This week, as a row of pips rather than a number: four is small enough to see, and
        // the streak is the thing worth finishing.
        Column(RowStyle) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("This week", color = D.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${s.weekPayments} of ${s.weekAllows}", color = D.Quiet, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                repeat(s.weekAllows) { i ->
                    Box(
                        Modifier.weight(1f).height(6.dp)
                            .background(if (i < s.weekPayments) D.Blue else D.Line, RoundedCornerShape(3.dp)),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (s.weekPayments >= s.streakNeeded) "Streak complete — ${s.streak} SKR earned"
                else "${s.streakNeeded - s.weekPayments} more this week for the ${s.streak} SKR streak",
                color = D.Quiet, fontSize = 12.sp,
            )
        }

        Column(RowStyle) {
            Row {
                Text("This month", color = D.Quiet, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${s.monthEarned} of ${s.monthCap} SKR", color = D.Black, fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Text("Left in this week's pool", color = D.Quiet, fontSize = 13.sp, modifier = Modifier.weight(1f))
                // An empty pool pays nobody, and the screen says so rather than letting the
                // next payment quietly earn nothing.
                Text(if (s.poolLeft > 0) "${s.poolLeft} SKR" else "empty — nothing earns until it refills",
                    color = if (s.poolLeft > 0) D.Black else D.Danger, fontSize = 13.sp)
            }
        }

        if (s.rows.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionLabel("EARNED")
            s.rows.forEach { row ->
                Row(RowStyle, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Rewards.nameOf(row.kind), color = D.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        // A reward taken back says why, in the same place the reward was. It
                        // is not deleted: someone who saw 300 SKR and later sees 0 deserves
                        // the reason on the same screen rather than a number that changed.
                        Text(
                            when {
                                row.state == "reversed" -> row.reason?.substringAfter(": ") ?: "taken back"
                                row.state == "locked" -> "with ${row.counterparty.take(4)}…${row.counterparty.takeLast(4)} · locked"
                                else -> "with ${row.counterparty.take(4)}…${row.counterparty.takeLast(4)}"
                            },
                            color = if (row.state == "reversed") D.Danger else D.Quiet, fontSize = 12.sp,
                        )
                    }
                    Text(if (row.state == "reversed") "−${row.skr}" else "+${row.skr}",
                        color = if (row.state == "reversed") D.Danger else D.Blue,
                        fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (s.refusals.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionLabel("EARNED NOTHING")
            s.refusals.forEach { ref ->
                Row(RowStyle, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(refusalText(ref.why), color = D.Black, fontSize = 13.sp)
                        Text("${ref.counterparty.take(4)}…${ref.counterparty.takeLast(4)}", color = D.Quiet, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

/** Every refusal the server can give, said plainly. Unknown ones are shown as-is rather than
 *  hidden — a reason nobody can read is still better than a payment that silently earned
 *  nothing. */
private fun refusalText(why: String): String = when (why) {
    "new_counterparty" -> "Their wallet was too new"
    "counterparty_cooldown" -> "Already earned with them this month"
    "below_minimum" -> "Under the minimum amount"
    "not_a_tap" -> "Not paid by tapping"
    "not_a_seeker" -> "Only a Seeker earns"
    "self_payment" -> "Paid your own wallet"
    "unknown_token" -> "That token doesn't earn"
    "weekly_cap" -> "This week's four were already earned"
    "monthly_cap" -> "This month's cap was reached"
    "campaign_cap" -> "The campaign cap was reached"
    "pool_empty" -> "The pool was empty"
    "chain_unreachable" -> "Couldn't check the chain — will retry"
    "no_payer" -> "We never learned who paid"
    else -> why.replace('_', ' ')
}

/**
 * A section's number, shown on the device so a change can be asked for by number instead of
 * described. Debug switch only — it rides on `checking`, the same long-press that shows
 * diagnostics, and never appears otherwise. The map is in SECTIONS.md.
 */
@Composable
private fun Tag(n: String, on: Boolean) {
    if (!on) return
    Text(
        "§$n", color = D.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, style = Figures,
        modifier = Modifier.padding(top = 2.dp)
            .clip(RoundedCornerShape(5.dp)).background(D.Blue)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = D.Quiet, fontSize = 11.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

private val RowStyle = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    .clip(RoundedCornerShape(14.dp))
    .background(D.Fill)
    .padding(horizontal = 14.dp, vertical = 12.dp)

/**
 * Naming yourself. Until someone does, they are a phone model to everybody they tap — and
 * "CPH2401 wants 0.5 SOL" is a worse thing to approve than "Fahad wants 0.5 SOL".
 */
@Composable
private fun NameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = D.White,
        title = { Text("Your name", color = D.Black) },
        text = {
            Column {
                Text("Shown on the other phone when you tap someone.", color = D.Quiet, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 40) text = it },
                    singleLine = true,
                    placeholder = { Text(android.os.Build.MODEL, color = D.Quiet) },
                    supportingText = { Text("${text.trim().length}/40", color = D.Quiet) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                Text("Save", color = D.Blue, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = D.Quiet) } },
    )
}
