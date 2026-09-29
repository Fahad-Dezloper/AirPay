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
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
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
    /** The referral explainer, held here so its scrim covers the screen rather than the
     *  padded column the tab is drawn inside. Carries the summary because the numbers in
     *  it are the server's, and this Activity does not read them. */
    private var howToRefer by mutableStateOf<Referrals.Summary?>(null)
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
    /** The selected wallet's balance. Null while it is being read, or unreadable. */
    private var balance by mutableStateOf<Chain.Balance?>(null)
    /** Every token this wallet holds, for the breakdown under the balance. */
    private var holdings by mutableStateOf<List<Tokens.Holding>>(emptyList())
    /** What the market says about each held mint: verified, and what it is worth. */
    private var market by mutableStateOf<Map<String, Market.Info>>(emptyMap())
    /** Every holding priced and added up — the figure under "Total balance". */
    private var totalUsd by mutableStateOf<Double?>(null)
    /** What this wallet has earned, for the strip on the home page. */
    private var rewardSummary by mutableStateOf<Rewards.Summary?>(null)
    /** The catalogue, once the server has been asked. Empty means nothing to show. */
    private var shopSections by mutableStateOf<List<Shop.Section>>(emptyList())
    /** The section being browsed, and the product opened inside it. */
    private var shopSection by mutableStateOf<Shop.Section?>(null)
    private var shopProduct by mutableStateOf<Shop.Product?>(null)
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
                when (val r = Shop.operatorFor(phone)) {
                    is Shop.Operator.Found -> {
                        val ours = section.products.firstOrNull { it.id == r.product.id }
                            ?: section.products.filter { it.isMobileRecharge }
                                .firstOrNull { sameNetwork(it.id, r.product.id) }
                            ?: r.product
                        rechargeOperator = ours
                        val name = ours.name.removeSuffix(" India").removeSuffix(" Bundles")
                        operatorNote = "$name — from the number itself, so porting is accounted for."
                    }
                    Shop.Operator.Unknown -> operatorNote =
                        "Could not tell which network that is. Pick it below."
                    Shop.Operator.Unreachable -> operatorNote =
                        "Could not check just now. Pick the network below."
                }
            } finally {
                findingOperator = false
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

    private fun buy(product: Shop.Product, value: String, category: String, phone: String? = null) {
        val payer = connected
        if (payer == null) {
            status = Status.Problem("Connect a wallet first", "The shop pays from your wallet.")
            return
        }
        // Still refused, but only when there is genuinely no number — the recharge screen
        // collects one, and anything reaching here without it would 400 at Bitrefill.
        if (product.needsPhone && phone.isNullOrBlank()) {
            status = Status.Problem("Needs a phone number",
                "${product.name.removeSuffix(" India")} credits a number directly. Enter one first.")
            return
        }
        lifecycleScope.launch {
            busy = true
            try {
                push("shop: asking for a price…")
                // The payer's own wallet as the refund address: if the order cannot be
                // filled, the money has somewhere to come back to.
                val inv = Shop.invoice(
                    product.id, value, phone,
                    refund = payer,
                    packageId = product.packages.firstOrNull { it.value == value }?.id,
                    // Optional. Without it Bitrefill notifies the account that owns the API
                    // key rather than the person who paid.
                    email = Identity.email(this@MainActivity),
                )
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
                                when (val sent = withContext(Dispatchers.IO) { Chain.submit(signed.tx) }) {
                                    is Chain.Submitted.Ok -> {
                                        push("✓ shop: paid ${inv.shown} — ${sent.signature.take(12)}…")
                                        status = Status.Sent(sent.signature)
                                        // Written down before it is watched. The watcher below
                                        // dies with the screen; this is what survives it, and
                                        // what the next launch asks about.
                                        orders.add(Orders.Order(
                                            invoiceId = inv.id, name = product.name, value = value,
                                            paid = inv.shown, signature = sent.signature,
                                            category = category, recipient = phone,
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
            val d = Shop.order(inv.id) ?: return@repeat
            push("shop: order ${d.status}")
            orders.setStatus(inv.id, d.status)
            // The code, saved the moment it exists. This screen is often gone by the time
            // delivery lands, so it cannot be the only place it is kept.
            d.redemption?.let {
                orders.setCode(inv.id, it.toJson())
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
            thread = person
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

    /** Re-reads the linked wallets and the selected one's balance. */
    private fun refreshWallets() {
        walletList = wallet.linked.all()
        balance = null
        holdings = emptyList()
        rewardSummary = null
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
            if (connected == addr) balance = b
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
                val info = Market.of(h.map { it.mint ?: Market.WSOL })
                if (connected != addr) return@launch
                market = info
                // Summed once, here, where both the balances and the prices are in hand.
                val priced = h.mapNotNull { t ->
                    info[t.mint ?: Market.WSOL]?.usdPrice?.let { u ->
                        java.math.BigDecimal.valueOf(t.units, t.decimals).toDouble() * u
                    }
                }
                totalUsd = if (priced.isEmpty()) null else priced.sum()
                holdings = h.filter { t ->
                    // SOL always. Everything else has to be a token Jupiter recognises —
                    // which is what keeps airdropped rubbish out of a list of money.
                    //
                    // An empty map means the lookup failed rather than that nothing is
                    // verified, so nothing is hidden on a dropped connection: an empty token
                    // list would look exactly like a wallet that had been emptied.
                    t.isSol || info.isEmpty() || info[t.mint]?.verified == true
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
                    push("shop: ${o.name} — ${d.status}")
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
        myEmail = Identity.email(this)
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
                            // The on-chain name first: it is the one other people see, so it
                            // should be the one on your own avatar. Falls through to a chosen
                            // name, then to "Y" for you — never to a phone model again.
                            me = myDomain ?: myName,
                            // Editing the name IS the profile, for now: it is the only thing
                            // about yourself this app stores, and it is what other phones see.
                            onProfile = { drawerOpen = true },
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
                                    Tab.REFERRALS -> "Refer & earn"
                                    Tab.OFFERS -> "Offers"
                                    else -> shopSection?.title ?: "Pay a bill"
                                },
                            ) { tab = Tab.TAP; shopSection = null; shopProduct = null }
                            Spacer(Modifier.height(16.dp))
                        }

                        if (tab == Tab.SHOP) {
                            shopSection?.let { sec ->
                                // Phone gets its own screen. The generic one lists products
                                // and then denominations, which is right for a gift card and
                                // wrong for a recharge: the number and the network have to be
                                // settled before a plan list means anything.
                                if (sec.products.any { it.isMobileRecharge }) {
                                    RechargeSection(
                                        section = sec,
                                        number = rechargeNumber,
                                        onNumber = { rechargeNumber = it },
                                        operator = rechargeOperator,
                                        onOperator = { rechargeOperator = it },
                                        finding = findingOperator,
                                        found = operatorNote,
                                        onFind = { findOperator(it, sec) },
                                        past = bought,
                                        onBuy = { p, v, phone -> buy(p, v, sec.title, phone) },
                                    )
                                } else {
                                    ShopSection(
                                        section = sec,
                                        picked = shopProduct,
                                        onPick = { shopProduct = it },
                                        email = myEmail,
                                        onEmail = { editingEmail = true },
                                        onBuy = { p, v -> buy(p, v, sec.title) },
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

                        if (tab == Tab.OFFERS) {
                            OffersTab(
                                connected = connected,
                                isSeeker = myGenesis != null,
                                onOpenOffer = { offerDetail = it },
                                onReferrals = { tab = Tab.REFERRALS },
                                reloadKey = offersReload,
                            )
                            return@Column
                        }

                        if (tab == Tab.REFERRALS) {
                            ReferralsTab(
                                connected = connected,
                                onShare = { shareText(it) },
                                onHowToRefer = { howToRefer = it },
                                myName = myDomain ?: Identity.label(this@MainActivity),
                            )
                            return@Column
                        }

                        if (tab == Tab.REWARDS) {
                            RewardsTab(
                                connected, myGenesis != null,
                                onInvite = { tab = Tab.REFERRALS },
                                onOffers = { tab = Tab.OFFERS },
                            )
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

                        Tag("2")
                        BalanceCard(
                            totalUsd = totalUsd,
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
                                label = { Text("Amount (SOL)") },
                                singleLine = true,
                                isError = amountProblem != null,
                                supportingText = amountProblem?.let { { Text(it, color = D.Danger) } },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            HowYouLook(myDomain, myDomainBorrowed, myName) { renaming = true }
                            Spacer(Modifier.height(12.dp))
                        }

                        // §2d — the breakdown behind the one figure above.
                        if (holdings.isNotEmpty()) {
                            Spacer(Modifier.height(18.dp))
                            Tag("2d")
                            val rate by produceState<Double?>(initialValue = null) { value = Prices.solUsd() }
                            TokenBadges(holdings, rate, market) { mint ->
                                clipboard.setText(AnnotatedString(mint))
                                Haptics.tick(this@MainActivity)
                                push("— copied $mint")
                            }
                        }

                        if (contactCount > 0) {
                            Spacer(Modifier.height(24.dp))
                            Tag("3")
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

                        if (shopSections.isNotEmpty()) {
                            Spacer(Modifier.height(22.dp))
                            Tag("4")
                            Text("Pay a bill", color = D.Black, fontSize = 17.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                            Spacer(Modifier.height(10.dp))
                            ShopTiles(shopSections) { sec ->
                                shopSection = sec
                                shopProduct = null
                                tab = Tab.SHOP
                            }
                        }
                        if (Rewards.enabled) {
                            Spacer(Modifier.height(22.dp))
                            Tag("5")
                            EarnStrip(rewardSummary) { tab = Tab.REWARDS }
                            Spacer(Modifier.height(10.dp))
                            EarnTiles(
                                onRewards = { tab = Tab.REWARDS },
                                onOffers = { tab = Tab.OFFERS },
                                // Nothing on the server tracks a referral yet, so this shares an
                                // invite and claims nothing else. A tile that promised a bonus
                                // nobody is counting would be the one lie on this screen.
                                onReferrals = { tab = Tab.REFERRALS },
                            )
                        }

                        if (history.isNotEmpty()) {
                            Spacer(Modifier.height(22.dp))
                            Tag("6")
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

                if (drawerOpen) {
                    ProfileDrawer(
                        name = myDomain ?: myName,
                        address = connected,
                        walletName = walletList.firstOrNull { it.address == connected }?.name,
                        isSeeker = myGenesis != null,
                        network = Chain.NETWORK,
                        version = BuildConfig.VERSION_NAME,
                        email = myEmail,
                        onRename = { drawerOpen = false; renaming = true },
                        onEmail = { drawerOpen = false; editingEmail = true },
                        onWallets = {
                            drawerOpen = false
                            walletDetails = walletList.firstOrNull { it.address == connected }
                        },
                        onShare = { drawerOpen = false; shareText(it) },
                        onGo = { drawerOpen = false; tab = it },
                        onDiagnostics = { drawerOpen = false; checking = true },
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
                            val me = connected ?: return@OfferSheet
                            offerClaiming = true
                            offerProblem = null
                            lifecycleScope.launch {
                                val why = Rewards.claim(o.id, me)
                                offerClaiming = false
                                if (why == null) {
                                    offersReload++
                                    offerDetail = null
                                } else {
                                    offerProblem = Rewards.offerWhy(why)
                                }
                            }
                        },
                        onClose = { offerDetail = null; offerProblem = null },
                    )
                }

                howToRefer?.let { s ->
                    HowToReferSheet(
                        s = s,
                        onShare = {
                            howToRefer = null
                            shareText(Referrals.message(
                                myDomain ?: Identity.label(this@MainActivity), s.code, s.theirReward))
                        },
                        onClose = { howToRefer = null },
                    )
                }

                walletDetails?.let { w ->
                    WalletSheet(
                        w = w,
                        linking = linking,
                        onLink = {
                            val owner = Identity.named(
                                this@MainActivity,
                                wallet.linked.all().map { it.address }.filter { it != w.address },
                            )?.first
                            if (owner == null) {
                                push("— no wallet here owns a name to lend")
                            } else lifecycleScope.launch {
                                linking = true
                                val named = wallet.linked.all().first { it.address == owner }
                                val link = wallet.link(named, w.address)
                                linking = false
                                if (link == null) {
                                    push("— ${named.name} did not sign; nothing changed")
                                } else {
                                    push("+ ${w.short} now shows ${Identity.domain(this@MainActivity, owner)}")
                                    // The armed request still carries the old payload.
                                    TapHostApduService.pending.value = null
                                    applyMode(amount)
                                }
                            }
                        },
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

                showCodeFor?.let { id ->
                    bought.firstOrNull { it.invoiceId == id }?.let { o ->
                        CodeSheet(o) { showCodeFor = null }
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
            // Kept only if the signature actually verifies — req.link() returns null otherwise —
            // and re-verified every time it is read back, so a stored link is never taken on
            // trust just because we were the ones who wrote it.
            req.link()?.let { Vouch.remember(this, it.owner, it.wallet, it.expiresDay, it.signature) }
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
            // EVERYTHING priced, not just SOL. It used to be the SOL figure alone, which meant
            // a wallet holding 456 SKR reported only the nine dollars of SOL beside it and
            // called that the total. Any amount actually paid is still counted in SOL or in
            // the token itself; this only decides a label.
            //
            // When nothing can be priced the SOL figure is shown instead, rather than a dollar
            // sign in front of a number nobody checked.
            val sol = (balance as? Chain.Balance.Ok)?.sol
            val rate by produceState<Double?>(initialValue = null) { value = Prices.solUsd() }
            // Three runs now, not one, because each rolls on its own: the sign holds still
            // while the whole number and the decimals animate their own digits.
            //
            // A single Text put them on a shared baseline for free, and that is given up here
            // — the row aligns to Bottom and the sign and cents are lifted by hand instead.
            // Worth it: DUFFLE_DESIGN.md asks for the amount to roll, and a figure that swaps
            // whole gives no sense that one balance BECAME another.
            val dollars = totalUsd ?: sol?.let { s0 -> rate?.let { s0 * it } }
            val shown = when {
                dollars != null -> Prices.money(dollars)
                sol != null -> "%.4f".format(sol)
                else -> "—"
            }
            Row(verticalAlignment = Alignment.Bottom) {
                if (dollars != null) {
                    Text(
                        "$", color = D.Quiet, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                        fontFamily = Display, style = Figures,
                        modifier = Modifier.padding(bottom = 10.dp, end = 3.dp),
                    )
                }
                FlowFigure(
                    text = shown.substringBefore('.'),
                    fontSize = 60.sp, color = D.Black,
                    fontWeight = FontWeight.ExtraBold, fontFamily = Display,
                )
                shown.substringAfter('.', "").takeIf { it.isNotEmpty() }?.let { frac ->
                    FlowFigure(
                        text = ".$frac",
                        fontSize = 26.sp, color = D.Quiet,
                        fontWeight = FontWeight.Bold, fontFamily = Display,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                if (dollars == null && sol != null) {
                    Text(
                        " SOL", color = D.Quiet, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
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
 * §2d — what is actually held, token by token.
 *
 * A dark head carrying the token's logo and a day of its price, over a white body with the
 * name, the unit price, the amount held and what that is worth.
 *
 * **The chart and the price are only drawn for a token that has a market.** A devnet mint has
 * no price history and therefore no line, and no price to put in the pill — a flat or invented
 * sparkline beside a balance is a claim about price movement nobody made. Those cards keep the
 * head and the logo and simply have nothing in the space, which is the honest shape of
 * "not traded".
 *
 * Every card is the same size. The height used to follow the content and the row came out
 * ragged; the slots are fixed now and the content fits them.
 */
@Composable
private fun TokenBadges(
    holdings: List<Tokens.Holding>,
    rate: Double?,
    market: Map<String, Market.Info>,
    onCopy: (String) -> Unit,
) {
    if (holdings.isEmpty()) return
    // One day of SOL, fetched once for the row rather than per card.
    val day by produceState<List<Double>?>(initialValue = null) { value = Prices.solDay() }

    // The row runs to both screen edges, not to this column's gutter, so a card scrolling out
    // slides off the SCREEN instead of being clipped by an invisible margin — and the inset is
    // put back inside the scroller, so the first and last card still sit on the gutter at rest.
    val screen = LocalConfiguration.current.screenWidthDp.dp
    Row(
        Modifier.offset(x = (-20).dp).width(screen)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        holdings.forEach { h ->
            // One price source for every token. The old rules priced SOL live, pinned a short
            // list of stablecoins at a dollar and gave up on everything else — so SKR, a real
            // token with real liquidity, read "No market price" purely for not being on a list.
            val unit = market[h.mint ?: Market.WSOL]?.usdPrice
                ?: if (h.isSol) rate else null
            val worth = remember(h.mint, h.units, unit) {
                unit?.let { java.math.BigDecimal.valueOf(h.units, h.decimals).toDouble() * it }
            }
            val series = if (h.isSol) day else null
            val move = Prices.change(series)
            // What one of them costs, from the market rather than from a list somebody
            // maintains. A mint with no market has no price, and no price is shown — an
            // invented one beside a real balance is worse than a gap.

            val press = remember { MutableInteractionSource() }

            // A shadow, because the card body is white on a white page: without an edge the
            // head read as a dark block with loose text under it rather than one object.
            Box(Modifier.then(PressScale(press)).width(190.dp).cardShadow(24.dp)) {
              Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp)).background(D.White)
                    .clickable(press, indication = null) { onCopy(h.mint ?: "SOL") },
              ) {
                // The logo sits INSIDE the head, flush into its top-left corner, and the head
                // is exactly as tall as the logo. It used to stand proud of the head with a
                // 16dp gap above; that read as two objects stacked rather than one head.
                Box(
                    Modifier.fillMaxWidth().height(58.dp)
                        .clip(RoundedCornerShape(24.dp)).background(Color(0xFF0E1013)),
                ) {
                    Box(Modifier.align(Alignment.TopStart)) {
                        TokenImage(h, 58.dp, RoundedCornerShape(14.dp))
                    }
                    if (series != null && series.size > 2) {
                        Sparkline(
                            series,
                            up = (move ?: 0.0) >= 0,
                            modifier = Modifier.align(Alignment.CenterEnd)
                                .padding(start = 76.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)
                                .fillMaxSize(),
                        )
                    }
                }

                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val named = h.name.isNotBlank() &&
                            !h.name.equals(h.symbol, true) && !h.name.startsWith(h.symbol)
                        Text(
                            if (named) h.name else "Unnamed",
                            color = D.Quiet, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, modifier = Modifier.weight(1f, fill = false),
                        )
                        // What one of them costs, not what it did today. The day's move is
                        // already in the line above it, in colour; repeating it as a number
                        // said the same thing twice and left the price nowhere on the card.
                        if (unit != null) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "$" + unitShown(unit),
                                color = D.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                style = Figures, maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                    .background(Color(0xFF0E1013))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${h.shown} ${h.symbol}", color = D.Black, fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold, fontFamily = Display,
                        style = Figures, maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (worth != null) "$${Prices.money(worth)}" else "No market price",
                        color = D.Quiet, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        style = Figures, maxLines = 1,
                    )
                }
              }
            }
        }
    }
}

/**
 * A unit price for the pill on a token card.
 *
 * Four places under a dollar, two above: "$118.58" and "$0.0042" are both readable, while two
 * places on a sub-cent token rounds every one of them to "$0.00".
 */
private fun unitShown(usd: Double): String =
    if (usd < 1.0) String.format("%,.4f", usd) else String.format("%,.2f", usd)

/** A day of prices as one line, with the last point marked. Drawn, not charted — no axes. */
@Composable
private fun Sparkline(series: List<Double>, up: Boolean, modifier: Modifier) {
    val stroke = if (up) D.Success else D.Danger
    Canvas(modifier) {
        val lo = series.min()
        val hi = series.max()
        val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        val dx = size.width / (series.size - 1)
        fun y(v: Double) = (size.height * (1f - ((v - lo) / span).toFloat())).coerceIn(0f, size.height)

        val path = Path().apply {
            moveTo(0f, y(series[0]))
            series.forEachIndexed { i, v -> if (i > 0) lineTo(i * dx, y(v)) }
        }
        // A wash under the line, so the head reads as a chart rather than a scribble.
        val under = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(under, Brush.verticalGradient(listOf(stroke.copy(alpha = .28f), Color.Transparent)))
        drawPath(path, stroke, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(stroke, radius = 3.dp.toPx(), center = Offset(size.width, y(series.last())))
    }
}

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
 * On the app's own [Tray] — the same chrome, scrim and `arrive` spring as the tap sheet, rather
 * than Material's, which is a different scrim, a different spring and a different corner in the
 * same app. The checks block is concentric with the tray: [Tray.INNER] is the outer radius
 * minus the padding it sits inside, so the curves nest instead of merely both being round.
 *
 * The rows arrive staggered. Frequency decides how much a thing animates and this screen is
 * rare, so it gets the entrance; everything on it is still interruptible.
 */
@Composable
private fun WalletSheet(
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
        Spacer(Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            WalletLogo(w, 46.dp, 14.dp)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(w.username ?: w.name, color = D.Black, fontSize = 19.sp,
                    fontWeight = FontWeight.Bold, fontFamily = Display, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                val copy = remember { MutableInteractionSource() }
                // Short, and tappable. The full 44 characters wrap to two lines and are read by
                // nobody; what people do with an address is paste it.
                Text(
                    if (copied) "Copied" else w.short,
                    color = if (copied) D.Success else D.Quiet,
                    fontSize = 12.5.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.then(PressScale(copy)).clip(RoundedCornerShape(6.dp))
                        .clickable(copy, indication = null) {
                            clip.setText(AnnotatedString(w.address))
                            copied = true
                            Haptics.tick(context)
                        }
                        .padding(vertical = 1.dp),
                )
            }
            Text("Linked ${whenLinked(w.addedAt)}", color = D.Quiet, fontSize = 11.sp,
                textAlign = TextAlign.End)
        }

        Spacer(Modifier.height(16.dp))

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Tray.INNER)).background(D.Fill)
                .padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            checks.forEachIndexed { i, c ->
                // Staggered, nearest the cause first. Nature is not synchronous.
                var here by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { delay(120L + i * 60L); here = true }
                val fade by animateFloatAsState(if (here) 1f else 0f, Settle, label = "check")
                Row(
                    Modifier.padding(vertical = 9.dp)
                        .graphicsLayer { alpha = fade; translationY = (1f - fade) * 8.dp.toPx() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HIcon(
                        if (c.ok) R.drawable.hi_tick else R.drawable.hi_alert,
                        if (c.ok) D.Success else D.Danger, 16.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.label, color = D.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        c.detail?.let {
                            Text(it, color = D.Quiet, fontSize = 11.sp,
                                fontFamily = if (it.contains('.')) FontFamily.Monospace else FontFamily.Default,
                                maxLines = 1)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        WalletIdentity(w, linking, onLink)

        Spacer(Modifier.height(12.dp))
        // The strongest thing on this sheet, and the one line that is not a check — because it
        // is not a property of the wallet. It is what this app does not do.
        Text(
            "AirPay never receives your key. Every payment is signed inside the wallet and handed back already signed.",
            color = D.Quiet, fontSize = 11.5.sp, lineHeight = 16.sp,
        )

        Spacer(Modifier.height(18.dp))
        // Two taps, because relinking means going back through the wallet. Nothing is spent
        // either way: this forgets a token, it does not touch the account.
        val remove = remember { MutableInteractionSource() }
        Text(
            if (confirming) "Tap again to remove" else "Remove wallet",
            color = D.Danger, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().then(PressScale(remove))
                .clip(RoundedCornerShape(Tray.INNER))
                .background(D.Danger.copy(alpha = if (confirming) .16f else .08f))
                .clickable(remove, indication = null) {
                    if (confirming) onRemove() else {
                        confirming = true
                        Haptics.tick(context)
                    }
                }
                .padding(vertical = 15.dp),
        )
        Spacer(Modifier.height(10.dp))
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
/**
 * What to call somebody, and how much of it anyone actually checked.
 *
 * Four kinds of name reach this app and exactly one of them is evidence. Collapsing them into a
 * single string is how a payments app ends up showing a stranger's chosen words with the same
 * confidence as a fact — so they are kept apart here, and drawn differently.
 *
 * In order of preference:
 *
 *  1. **[Kind.Yours]** — what you named them. Never crossed a radio, never a claim anyone made
 *     about themselves, written by you while they were standing in front of you.
 *  2. **[Kind.Verified]** — a `.skr` or `.sol` this phone resolved from the chain. Nobody can
 *     forge it, but nobody vetted who bought it either: it is unforgeable, not attested. The
 *     badge says this address owns that name, and must never be read as "this is that person".
 *  3. **[Kind.Claimed]** — the name their phone sent us. Useful for remembering who someone
 *     was; worthless for deciding whether to trust them.
 *  4. **[Kind.Anonymous]** — the address, shortened. Honest.
 *
 * [domain] is kept apart from [shown] on purpose: when you have renamed somebody who also owns
 * a domain, a row can show your name for them *and* what the chain said, rather than one
 * silently hiding the other.
 */
private data class Called(
    val shown: String,
    /** The on-chain name, when there is one. The only field that earns a tick. */
    val domain: String?,
    val kind: Kind,
    /**
     * True when the name came from a *different* wallet vouching for this one ([Vouch]).
     *
     * Still a fact — the signature was checked and the name still came off the chain — but a
     * different one: "this address belongs to whoever owns dezloper.skr" rather than "this
     * address owns dezloper.skr". Worth saying out loud where money moves.
     */
    val borrowed: Boolean = false,
) {
    enum class Kind { Yours, Verified, Claimed, Anonymous }

    /** True only when the chain answered. Drives the tick, and nothing else does. */
    val verified: Boolean get() = domain != null
}

/**
 * Resolves a contact's name, asking the chain once per address.
 *
 * The lookup is mainnet and cached in [Names], so it settles quickly on a warm cache and never
 * blocks a row from drawing: it starts from what is known locally and upgrades in place when
 * the chain replies.
 */
@Composable
private fun rememberCalled(contact: Contact): Called {
    val context = LocalContext.current
    val found by produceState(initialValue = Names.Found(null, false), contact.address) {
        // The link is re-verified on the way out of storage, so a tampered prefs file buys
        // nothing: an unverifiable link reads back as no link at all.
        value = Names.of(contact.address, Vouch.of(context, contact.address))
    }
    val domain = found.text
    return remember(domain, found.borrowed, contact.yours, contact.label, contact.address) {
        val claimed = contact.claimed()
        val yours = contact.yours
        when {
            !yours.isNullOrBlank() -> Called(yours, domain, Called.Kind.Yours, found.borrowed)
            domain != null -> Called(domain, domain, Called.Kind.Verified, found.borrowed)
            !claimed.isNullOrBlank() -> Called(claimed, null, Called.Kind.Claimed, false)
            else -> Called(contact.short, null, Called.Kind.Anonymous, false)
        }
    }
}

/**
 * A name with its provenance visible at a glance.
 *
 * The tick is the whole point: it appears for a chain-resolved name and for nothing else, so a
 * stranger can tell a fact from a claim without reading any help text. A name we were merely
 * told is drawn in the quiet colour for the same reason.
 */
@Composable
private fun NameLine(
    called: Called,
    size: TextUnit,
    modifier: Modifier = Modifier,
    centred: Boolean = false,
) {
    Row(
        modifier,
        horizontalArrangement = if (centred) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            called.shown,
            color = if (called.kind == Called.Kind.Claimed || called.kind == Called.Kind.Anonymous) D.Quiet else D.Black,
            fontSize = size,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (called.verified) {
            Spacer(Modifier.width(3.dp))
            HIcon(R.drawable.hi_verified, D.Blue, size.value.dp + 3.dp)
        }
    }
}

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
                val called = rememberCalled(c)
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
                            Text(called.shown.take(1).uppercase(), color = D.Quiet,
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
                    NameLine(called, 12.sp, Modifier.fillMaxWidth(), centred = true)
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
    onRename: (String) -> Unit,
) {
    val called = rememberCalled(who)
    var renaming by remember(who.address) { mutableStateOf(false) }

    if (renaming) {
        ContactNameDialog(
            called = called,
            current = who.yours.orEmpty(),
            onDismiss = { renaming = false },
            onSave = { renaming = false; onRename(it) },
        )
    }
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
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .clickable { renaming = true }.padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        called.shown, color = D.Black, fontSize = 19.sp,
                        fontWeight = FontWeight.Bold, fontFamily = Display, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    // The tick belongs to the name the chain confirmed, and to nothing else.
                    // The Seeker badge is a separate claim about their hardware and is spelled
                    // out below rather than given a second identical tick beside this one.
                    if (called.verified) {
                        Spacer(Modifier.width(5.dp))
                        HIcon(R.drawable.hi_verified, D.Blue, 16.dp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // When you have renamed somebody who owns a domain, their real name still
                    // shows here — your label is for recognising them, not for hiding this.
                    if (called.kind == Called.Kind.Yours && called.domain != null) {
                        Text(called.domain, color = D.Blue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(" · ", color = D.Quiet, fontSize = 11.sp)
                    }
                    Text(who.short, color = D.Quiet, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    // Said out loud, because it is a different claim: the name belongs to a
                    // wallet that vouched for this one, not to this address.
                    if (called.borrowed) {
                        Text(" · linked wallet", color = D.Quiet, fontSize = 11.sp)
                    }
                    if (who.seeker) {
                        Text(" · Seeker", color = D.Blue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
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
        Tag("1")
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

private enum class Mark { Coins, Ticket, Rings, Signal, Screen, Receipt, Basket, Plane, Cup, Game, Dots, Play, Cinema }

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
    onBuy: (Shop.Product, String, String) -> Unit,
) {
    // Only the products that actually credit a number. The Phone section also carries eSIMs
    // and Viber credit, which are not recharges and would only be in the way here.
    val operators = remember(section) { section.products.filter { it.isMobileRecharge } }
    val e164 = Sims.e164(number)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        MainnetNote()

        Text("Mobile number", color = D.Quiet, fontSize = 11.sp, letterSpacing = 1.sp,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = number,
            onValueChange = { v -> onNumber(v.filter { it.isDigit() }.take(12)) },
            singleLine = true,
            placeholder = { Text("10 digits", color = D.Quiet) },
            prefix = { Text("+91 ", color = D.Quiet) },
            isError = number.isNotBlank() && e164 == null,
            supportingText = {
                Text(
                    if (number.isNotBlank() && e164 == null) "That is not a 10-digit number."
                    else "The recharge is credited to this number.",
                    color = if (number.isNotBlank() && e164 == null) D.Danger else D.Quiet,
                )
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )

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
            Text("Checking which network…", color = D.Quiet, fontSize = 12.sp)
        }

        // Once the network is known it is a FACT, not a question, so it stops being a row of
        // choices and becomes one line saying what it is. A list of every network with one
        // highlighted asks somebody to check the app's work on something the app now knows
        // better than they do.
        //
        // The list only comes back when it has to: when the lookup could not tell, or when
        // somebody taps the answer to disagree with it. That path stays, because this decides
        // where real money goes and an app that cannot be corrected is worse than a wrong one.
        var picking by rememberSaveable { mutableStateOf(false) }
        var query by rememberSaveable(operator?.id) { mutableStateOf("") }
        val chosen = operator

        if (chosen != null && !picking) {
            // The header of the reference screen: who is being recharged, on which network,
            // with the one way to disagree. It carries the number as well as the network —
            // this is the last thing between a tap and real money leaving, and the number is
            // the half that cannot be checked afterwards.
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(D.Fill)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val mark = chosen.name.removeSuffix(" India").removeSuffix(" Bundles")
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(D.Blue),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(mark.take(1).uppercase(), color = D.White, fontSize = 19.sp,
                        fontWeight = FontWeight.ExtraBold, fontFamily = Display)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(mark.uppercase(), color = D.Black, fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold, fontFamily = Display)
                    Spacer(Modifier.height(1.dp))
                    Text("+91 $number", color = D.Quiet, fontSize = 12.5.sp, style = Figures)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Change", color = D.Blue, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .clickable { picking = true }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        } else if (picking || (found != null && !finding && chosen == null)) {
            // Either the lookup could not tell, or somebody disagreed with it.
            Spacer(Modifier.height(18.dp))
            found?.takeIf { chosen == null }?.let {
                Text(it, color = D.Quiet, fontSize = 12.sp, lineHeight = 16.sp)
                Spacer(Modifier.height(10.dp))
            }
            Text("Network", color = D.Quiet, fontSize = 11.sp, letterSpacing = 1.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                operators.forEach { p ->
                    val on = chosen?.id == p.id
                    Text(
                        p.name.removeSuffix(" India").removeSuffix(" Bundles"),
                        color = if (on) D.White else D.Black,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (on) D.Blue else D.Fill)
                            .clickable { onOperator(p); picking = false }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }

        if (chosen == null) {
            // Nothing to say yet. Before a number is typed the screen is one box, which is the
            // whole instruction — a "pick a network" prompt above an empty field is a second
            // question asked before the first one has been answered.
            if (found != null && !finding) {
                Spacer(Modifier.height(20.dp))
                Text("Pick a network to see what it sells.", color = D.Quiet, fontSize = 13.sp)
            }
        } else {
            Spacer(Modifier.height(20.dp))
            val plans = remember(chosen.id) {
                chosen.packages.ifEmpty { chosen.values.map { Shop.Package(it, it.toIntOrNull() ?: 0) } }
                    .sortedBy { it.amount }
            }
            Text(
                if (chosen.sellsPlans) "Plans" else "Top-up",
                color = D.Quiet, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold,
            )
            if (!chosen.sellsPlans) {
                Spacer(Modifier.height(6.dp))
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
                    "${chosen.name.removeSuffix(" India")} sells credit here, not plans — it " +
                        "goes to the number's main balance.",
                    color = D.Quiet, fontSize = 12.sp, lineHeight = 16.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "What lands as talktime is less than what you pay: tax and the operator's " +
                        "fee come off first, and neither is published here. Credit on its own " +
                        "does not add validity — a plan does that.",
                    color = D.Quiet, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
            Spacer(Modifier.height(10.dp))

            // What you chose for THIS number last time.
            //
            // Every recharge app shows a "recommended plan" here. They get it from a plan API
            // that ships the category with the data, or from their own record of what you have
            // bought — nobody can be told what plan a number is currently on, because no third
            // party is given that (see the note on the operator lookup). So this is the honest
            // version of the same idea: not a recommendation, a memory.
            val last = remember(e164, past) {
                past.firstOrNull { it.recipient != null && it.recipient == e164 }
            }
            if (last != null) {
                val again = plans.firstOrNull { it.value == last.value }
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(16.dp)).background(D.Blue.copy(alpha = .08f))
                        .clickable(enabled = again != null && e164 != null) {
                            again?.let { onBuy(chosen, it.value, e164!!) }
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("LAST TIME", color = D.Blue, fontSize = 10.sp, letterSpacing = 1.sp,
                            fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (again != null && again.amount > 0)
                                "${symbolFor(chosen.currency)}${again.amount} · ${last.value}"
                            else last.value,
                            color = D.Black, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (again == null) "No longer sold — pick another below."
                            else "You recharged this number ${ago(last.at)}.",
                            color = D.Quiet, fontSize = 11.sp,
                        )
                    }
                    if (again != null) {
                        Spacer(Modifier.width(10.dp))
                        Text("Again", color = D.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(D.Blue)
                                .padding(horizontal = 16.dp, vertical = 9.dp))
                    }
                }
            }

            // Searching a list of fifty is not a nicety. It is also matched against the price,
            // because the search anybody actually runs is the number they already know.
            if (chosen.sellsPlans && plans.size > 8) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search — 349, or 28 days", color = D.Quiet) },
                    leadingIcon = { HIcon(R.drawable.hi_search, D.Quiet, 17.dp) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }

            // The shelves, and only the ones with something on them. A tab that opens onto an
            // empty list is a promise the catalogue did not keep.
            val shelved = remember(chosen.id) { plans.associateWith { Plans.shelves(it.value) } }
            val tabs = remember(shelved) {
                Plans.Shelf.entries.filter { sh -> shelved.values.any { sh in it } }
                    .filter { it != Plans.Shelf.All || shelved.size > 4 }
            }
            var shelf by rememberSaveable(chosen.id) { mutableStateOf(Plans.Shelf.All) }
            if (tabs.size > 1) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tabs.forEach { t ->
                        val on = shelf == t
                        Text(
                            t.label,
                            color = if (on) D.White else D.Black,
                            fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                .background(if (on) D.Black else D.Fill)
                                .clickable { shelf = t }
                                .padding(horizontal = 13.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            val ready = e164 != null
            val shown = plans.filter {
                Plans.matches(query, it.value, it.amount) && shelf in (shelved[it] ?: emptySet())
            }
            if (shown.isEmpty()) {
                Text(
                    if (query.isBlank()) "Nothing on this shelf."
                    else "Nothing matches “$query”.",
                    color = D.Quiet, fontSize = 13.sp,
                )
            }
            val symbol = symbolFor(chosen.currency)
            shown.forEach { pack ->
                val detail = remember(pack.value) { Plans.of(pack.value) }
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(18.dp))
                        .background(D.Fill)
                        .clickable(enabled = ready) { onBuy(chosen, pack.value, e164!!) }
                        .padding(14.dp),
                ) {
                    // 5G is the one badge worth carrying: it is stated in the line rather than
                    // inferred, and it is what people filter on.
                    if (detail.fast) {
                        Text(
                            "5G", color = D.Success, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(D.Success.copy(alpha = .12f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.width(96.dp)) {
                            Text(
                                "$symbol${pack.price}",
                                color = if (ready) D.Black else D.Quiet,
                                fontSize = 21.sp, fontWeight = FontWeight.ExtraBold,
                                fontFamily = Display, style = Figures,
                            )
                            // What it works out to per day, which is the only way a 28-day plan
                            // and an 84-day one can be compared at a glance.
                            Plans.perDay(pack.amount, detail.days, symbol)?.let {
                                Text(it, color = D.Quiet, fontSize = 11.sp, style = Figures)
                            }
                        }
                        // Validity and data as their own columns. Three numbers side by side are
                        // read in a glance where the same three inside a sentence are not.
                        if (detail.validity != null || detail.data != null) {
                            detail.validity?.let { Labelled("Validity", it, Modifier.width(82.dp)) }
                            detail.data?.let { Labelled("Data", it, Modifier.weight(1f)) }
                        } else if (chosen.sellsPlans) {
                            Text(pack.value, color = D.Quiet, fontSize = 12.5.sp,
                                lineHeight = 17.sp, modifier = Modifier.weight(1f))
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        HIcon(R.drawable.hi_arrow_up_right, if (ready) D.Blue else D.Quiet, 17.dp)
                    }
                    // The full line underneath, when the columns did not carry all of it —
                    // "+ JioHotstar" and "+ Netflix Basic" are half the reason to pick a plan.
                    if (chosen.sellsPlans && (detail.validity != null || detail.data != null)) {
                        Spacer(Modifier.height(8.dp))
                        Text(pack.value, color = D.Quiet, fontSize = 11.5.sp, lineHeight = 15.sp)
                    }
                }
            }
            if (!ready) {
                Text("Enter a number to recharge.", color = D.Quiet, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A caption over a value — the shape that lets validity and data be compared down a column. */
@Composable
private fun Labelled(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(end = 8.dp)) {
        Text(label, color = D.Quiet, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(1.dp))
        Text(value, color = D.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            lineHeight = 17.sp)
    }
}

/**
 * Where codes go, asked once and only where it matters.
 *
 * Shown on shelves that deliver a code rather than crediting a number — a phone recharge has
 * nowhere to email. Optional throughout: the code is shown in the app either way, and nobody
 * should have to hand over an address to spend their own money. This is the copy that survives
 * a reinstall, a lost phone, or not looking at the screen in the ten seconds after buying.
 */
@Composable
private fun EmailRow(email: String?, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(14.dp))
            .background(D.Fill).clickable(onClick = onEdit)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (email == null) "Codes are shown here only" else "Codes also emailed to",
                color = D.Quiet, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                email ?: "Add an email for a copy",
                color = if (email == null) D.Blue else D.Black,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(if (email == null) "Add" else "Change", color = D.Blue, fontSize = 12.sp,
            fontWeight = FontWeight.Bold)
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
        title = { Text("Email for codes", color = D.Black) },
        text = {
            Column {
                Text(
                    "Gift-card codes are shown in this app when they arrive. An email is a " +
                        "second copy, in case you close the app before reading one.",
                    color = D.Quiet, fontSize = 13.sp, lineHeight = 18.sp,
                )
                Spacer(Modifier.height(12.dp))
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
                            color = if (bad) D.Danger else D.Quiet,
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = !bad) {
                Text("Save", color = D.Blue, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = D.Quiet) } },
    )
}

/**
 * The thing you bought, put in front of you.
 *
 * This did not exist, and its absence was the whole failure: a real PhonePe card was paid for,
 * delivered, and captured correctly — and the screen said "Sent" and then nothing. The code sat
 * in storage while the buyer went to their email to find it.
 *
 * Each part is its own labelled, copyable row rather than one blob. A card number and a PIN are
 * used in different boxes on someone else's website, and a sixteen-digit run followed by a
 * six-digit run says nothing about which is which.
 */
@Composable
private fun CodeSheet(order: Orders.Order, onClose: () -> Unit) {
    val r = remember(order.code) { Shop.Redemption.of(order.code) }
    Tray(onClose = onClose) {
        Spacer(Modifier.height(18.dp))
        Text(order.name.removeSuffix(" India"), color = D.Black, fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold, fontFamily = Display)
        Spacer(Modifier.height(2.dp))
        Text("${order.value} · paid ${order.paid}", color = D.Quiet, fontSize = 12.5.sp)
        Spacer(Modifier.height(18.dp))

        if (r == null) {
            Text(
                "Delivered. This one returns no code — the value went straight to the " +
                    "recipient.",
                color = D.Quiet, fontSize = 13.sp, lineHeight = 18.sp,
            )
        } else {
            r.code?.let { CodeRow("Card number", it) }
            r.pin?.let { CodeRow("PIN", it) }
            r.link?.let { CodeRow("Redeem at", it, mono = false) }
            r.instructions?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = D.Quiet, fontSize = 12.sp, lineHeight = 17.sp)
            }
            r.raw?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = D.Quiet, fontSize = 11.5.sp, lineHeight = 16.sp)
            }
            r.expires?.let {
                Spacer(Modifier.height(12.dp))
                Text("Valid until ${it.take(10)}", color = D.Quiet, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Kept in your history. It is also emailed to whoever the order named.",
            color = D.Quiet, fontSize = 11.5.sp, lineHeight = 16.sp,
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Done", onClick = onClose)
        Spacer(Modifier.height(10.dp))
    }
}

/** One labelled part of a code, big enough to read off and tappable to copy. */
@Composable
private fun CodeRow(label: String, value: String, mono: Boolean = true) {
    val clip = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(Tray.INNER))
            .background(D.Fill).clickable {
                clip.setText(AnnotatedString(value)); copied = true
            }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = D.Quiet, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp, modifier = Modifier.weight(1f))
            Text(if (copied) "Copied" else "Copy", color = D.Blue, fontSize = 12.sp,
                fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            value, color = D.Black, fontSize = if (mono) 19.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            lineHeight = 24.sp,
        )
    }
}

/** The one line that has to be on every shop screen: this is the real-money corner of the app. */
@Composable
private fun MainnetNote() {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(14.dp))
            .background(D.Blue.copy(alpha = .08f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HIcon(R.drawable.hi_info, D.Blue, 17.dp)
        Spacer(Modifier.width(8.dp))
        Text("Real money on Solana mainnet.",
            color = D.Blue, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
    }
}

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
    email: String?,
    onEmail: () -> Unit,
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
            Text("Real money on Solana mainnet.",
                color = D.Blue, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        }
        // A short shelf opens itself. Four subscription services behind four taps is a list
        // pretending to be a menu; sixty-three shopping brands genuinely need collapsing.
        val alwaysOpen = section.products.size <= 6
        // Only where a code is the thing being bought. A DTH recharge credits a set-top box
        // and has nothing to email.
        // Gated on "comes back to YOU", not on the redemption method. The first version asked
        // for `"online" in redemption`, which is false for PhonePe — it redeems by phone — so
        // the one shelf where somebody bought a gift card never offered to email them a copy.
        if (section.products.any { it.recipient == "none" }) {
            EmailRow(email, onEmail)
        }
        section.products.forEach { p ->
            val open = alwaysOpen || picked?.id == p.id
            Column(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp))
                    .background(D.Fill).clickable { onPick(if (open) null else p) }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The brand's own mark. A list of subscription services with no logos is a
                    // list of words, and these are things people recognise by sight.
                    p.logoUrl?.let { url ->
                        val logo by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, url) {
                            value = LogoCache.get(url) ?: withContext(Dispatchers.IO) { LogoCache.load(url) }
                        }
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(D.White),
                            contentAlignment = Alignment.Center,
                        ) {
                            logo?.let {
                                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(p.name.removeSuffix(" India"), color = D.Black, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(
                            // WHERE it can be spent, which is the thing that decides whether
                            // a voucher is any use. Twenty-five of the sixty-three shopping
                            // brands are in-store only — you cannot spend them on the website
                            // — and the row used to say "9 prices", which answers a question
                            // nobody asked.
                            buildString {
                                append(
                                    when {
                                        p.needsPhone -> "Credited to a phone number"
                                        "online" in p.redemption && "instore" in p.redemption ->
                                            "Online or in store"
                                        "instore" in p.redemption -> "In store only"
                                        "online" in p.redemption -> "Online only"
                                        "phone" in p.redemption -> "In their app"
                                        p.values.size == 1 -> "One price"
                                        else -> "${p.values.size} prices"
                                    }
                                )
                                // Three of the hundred and fifty-eight products are priced in
                                // dollars by the shop — Tinder, Viber and the eSIM. A lone $3
                                // among rupees reads as a bug rather than a fact, so the row
                                // says which it is instead of leaving the symbol to argue for
                                // itself.
                                if (p.currency != "INR") append(" · priced in ${p.currency}")
                            },
                            color = D.Quiet, fontSize = 12.sp,
                        )
                    }
                    HIcon(if (open) R.drawable.hi_arrow_down else R.drawable.hi_arrow_up_right, D.Quiet, 17.dp)
                }
                // What this is and what to do with the code, in the brand's own words.
                //
                // Fetched only when the row is open. The text runs to a couple of thousand
                // characters per product and matters at the moment of choosing, not while
                // scrolling past — and it is shown in full, because a voucher's terms cut off
                // mid-sentence is exactly the half somebody needed.
                if (open) {
                    val info by produceState<Shop.Details?>(null, p.id) { value = Shop.details(p.id) }
                    info?.takeIf { !it.empty }?.let { d ->
                        Spacer(Modifier.height(10.dp))
                        d.about?.let {
                            Text(it, color = D.Quiet, fontSize = 11.5.sp, lineHeight = 16.sp)
                        }
                        d.terms?.let {
                            if (d.about != null) Spacer(Modifier.height(8.dp))
                            Text(it, color = D.Quiet, fontSize = 11.5.sp, lineHeight = 16.sp)
                        }
                    }
                }
                // The denominations only appear for the row that was opened, because 156
                // products with every price showing is a list nobody reads.
                AnimatedVisibility(visible = open) {
                    Column(Modifier.padding(top = 12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            val opts = p.packages.ifEmpty { p.values.map { Shop.Package(it, 0) } }
                            opts.forEach { pack ->
                                // The PRICE on the pill, never the description. A package whose
                                // value is a name — "Amazon Prime Subscription 3M" — was being
                                // printed straight after the currency symbol, so the button to
                                // buy a ₹399 subscription read "₹Amazon Prime Subscription 3M".
                                val price = "${symbolFor(p.currency)}${pack.price}"
                                val what = packageLabel(pack, p.name)
                                Column(
                                    Modifier.clip(RoundedCornerShape(if (what == null) 999.dp else 16.dp))
                                        .background(D.Blue).clickable { onBuy(p, pack.value) }
                                        .padding(horizontal = 15.dp, vertical = 9.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(price, color = D.White, fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold, style = Figures)
                                    what?.let {
                                        Text(it, color = D.White.copy(alpha = .8f), fontSize = 10.5.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
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
        "Phone" to Mark.Signal, "Streaming" to Mark.Play,
        "Cinema" to Mark.Cinema, "Bills & wallets" to Mark.Receipt,
        "Travel" to Mark.Plane, "Food" to Mark.Cup, "Shopping" to Mark.Basket,
        "Games" to Mark.Game, "More" to Mark.Dots,
    )
    // Twelve, not eight. Splitting the old TV shelf took the count past eight, and a cap that
    // silently drops the last shelves is how a whole category disappears without a trace.
    val shown = sections.filter { it.products.isNotEmpty() }.take(12)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Four to a row, and the last row padded with empty weights rather than stretched —
        // three wide tiles beside four narrow ones is the giveaway that a grid is faked.
        shown.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { sec ->
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
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
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
                    drawCircle(D.Fill, radius = w * .08f, center = Offset(w * .31f, w * .50f))
                    drawCircle(D.Fill, radius = w * .08f, center = Offset(w * .69f, w * .50f))
                }
                // A strip of film: sprocket holes down both edges. Distinct from Ticket, which
                // is the Offers mark, and from Play, which is streaming — three neighbours that
                // would otherwise all be "a rectangle about watching something".
                Mark.Cinema -> {
                    drawRoundRect(blue, Offset(w * .14f, w * .12f), Size(w * .72f, w * .76f),
                        CornerRadius(w * .12f))
                    repeat(4) { i ->
                        val y = w * (.22f + i * .18f)
                        drawRoundRect(D.Fill, Offset(w * .20f, y), Size(w * .09f, w * .09f),
                            CornerRadius(w * .02f))
                        drawRoundRect(D.Fill, Offset(w * .71f, y), Size(w * .09f, w * .09f),
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
                        D.Fill,
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
private fun PayBar(modifier: Modifier, onTap: () -> Unit, onScan: () -> Unit) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // No floor and no backdrop: the capsule floats and the page runs under it. It used to
        // sit on a white strip with a gradient above it, which stopped content showing through
        // — at the cost of a white slab across the bottom of every screenshot.
        Row(Modifier.fillMaxWidth().padding(start = 20.dp)) { Tag("7") }

        // A white ring around the capsule, under a soft shadow. On a white page the ring is
        // invisible as a colour and does its work as a gap: it holds the dark shape off the
        // shadow so the bar reads as lifted rather than smudged.
        //
        // Width comes from the content, not the page. Stretched edge to edge the two halves
        // were mostly empty and the labels ended up far from the seam that joins them — which
        // is the one detail making this read as a single control.
        Row(
            Modifier.cardShadow(PayBarRadius)
                .clip(RoundedCornerShape(PayBarRadius)).background(D.White)
                .padding(PayBarRing),
            // The seam is the ring carried through the middle, only tighter — narrow enough
            // that the halves read as joined, wide enough to still be a join and not a crack.
            horizontalArrangement = Arrangement.spacedBy(PayBarGap),
        ) {
            PayChoice(
                first = "Pay", second = "via Tap",
                icon = R.drawable.hi_nfc, iconFirst = true,
                fill = D.Black, shape = PayBarLeft, onClick = onTap,
            )
            PayChoice(
                first = "Scan", second = "any QR",
                icon = R.drawable.hi_scan, iconFirst = false,
                fill = D.Blue, shape = PayBarRight, onClick = onScan,
            )
        }
        Spacer(Modifier.height(14.dp))
    }
}

/** Concentric, the way every other tray and card in the app is: outer = inner + the ring. */
private val PayBarHeight = 72.dp
private val PayBarRing = 5.dp
/** The seam, narrower than the ring so the two halves sit closer without touching. */
private val PayBarGap = 3.dp
private val PayBarRadius = PayBarHeight / 2 + PayBarRing

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
    second: String,
    @DrawableRes icon: Int,
    iconFirst: Boolean,
    fill: Color,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.height(PayBarHeight).clip(shape).background(fill)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pressing dims the half rather than scaling it. A scale would shrink one end of a
        // shape the eye reads as whole, which looks like it broke.
        val pressed by interaction.collectIsPressedAsState()
        val ink by animateColorAsState(
            if (pressed) D.White.copy(alpha = .55f) else D.White,
            spring(Snap.dampingRatio, Snap.stiffness), label = "ink",
        )
        if (iconFirst) HIcon(icon, ink, 26.dp)
        Column {
            Text(first, color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                fontFamily = Display, lineHeight = 19.sp, maxLines = 1)
            Text(second, color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                fontFamily = Display, lineHeight = 19.sp, maxLines = 1)
        }
        if (!iconFirst) HIcon(icon, ink, 26.dp)
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
            // A code is the one thing on this row somebody needs to USE, so it is monospaced,
            // and tapping it copies rather than opening the row. A code you cannot get out of
            // the app is barely delivered.
            if (t.code != null) {
                // A summary, not the code itself. Cramming a card number and a PIN onto one
                // ellipsized line showed neither properly and copied both as one blob; the
                // row opens the sheet where each part is labelled and separately copyable.
                Text(
                    "Tap for the code", color = D.Blue, fontSize = 12.sp, maxLines = 1,
                    fontWeight = FontWeight.SemiBold,
                )
            } else {
                Text(t.note ?: t.category, color = if (t.note != null) D.Blue else D.Quiet,
                    fontSize = 12.sp, maxLines = 1)
            }
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
 * A section's number, from SECTIONS.md, so a change can be asked for by number instead of
 * described.
 *
 * **Always on in a debug build**, and never present in a release — BuildConfig.DEBUG is a
 * compile-time constant, so R8 strips these calls entirely rather than shipping a branch that
 * is never taken. It used to ride on `checking`, the long-press that shows the exchange log,
 * which meant the numbers were invisible unless you remembered a gesture — and a label you
 * have to summon is a label you will assume was deleted.
 */
@Composable
private fun Tag(n: String) {
    if (!BuildConfig.DEBUG) return
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

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Tray.INNER))
            .background(if (mine != null || link != null) D.Blue.copy(alpha = .07f) else D.Fill)
            .padding(14.dp),
    ) {
        Text("SEEN AS", color = D.Quiet, fontSize = 10.sp, letterSpacing = 1.2.sp,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))

        when {
            mine != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(mine, color = D.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        fontFamily = Display, maxLines = 1)
                    Spacer(Modifier.width(5.dp))
                    HIcon(R.drawable.hi_verified, D.Blue, 15.dp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "This wallet owns the name, so everyone you tap sees it. It can also lend " +
                        "it to your other wallets.",
                    color = D.Quiet, fontSize = 11.5.sp, lineHeight = 16.sp,
                )
            }

            link != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Identity.domain(context, link.owner) ?: link.owner.take(6) + "…",
                        color = D.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        fontFamily = Display, maxLines = 1,
                    )
                    Spacer(Modifier.width(5.dp))
                    HIcon(R.drawable.hi_verified, D.Blue, 15.dp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Lent to this wallet by ${link.owner.take(4)}…${link.owner.takeLast(4)}, " +
                        "and signed — the other phone checks it rather than taking our word. " +
                        "Good until ${link.expiresOn}.",
                    color = D.Quiet, fontSize = 11.5.sp, lineHeight = 16.sp,
                )
            }

            owner != null -> {
                Text(w.short, color = D.Quiet, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Just an address right now. ${owner.second} can vouch for it, so people " +
                        "you tap from this wallet see your name instead. One approval in that " +
                        "wallet, nothing spent.",
                    color = D.Quiet, fontSize = 11.5.sp, lineHeight = 16.sp,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    if (linking) "Waiting for ${owner.second}…" else "Use ${owner.second} here",
                    enabled = !linking,
                    onClick = onLink,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            else -> {
                Text(w.short, color = D.Quiet, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Just an address. None of your wallets owns a .skr or .sol yet — every " +
                        "Seeker comes with a .skr, and any wallet that has one can lend it to " +
                        "the rest.",
                    color = D.Quiet, fontSize = 11.5.sp, lineHeight = 16.sp,
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
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (verified) D.Blue.copy(alpha = .07f) else D.Fill)
            .clickable(onClick = onEdit).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("They will see", color = D.Quiet, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    domain ?: username.ifBlank { "your address" },
                    color = if (verified || username.isNotBlank()) D.Black else D.Quiet,
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (verified) {
                    Spacer(Modifier.width(4.dp))
                    HIcon(R.drawable.hi_verified, D.Blue, 14.dp)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    // A borrowed name is just as checkable and is not the same claim, so it
                    // says where it came from instead of implying this wallet owns it.
                    verified && borrowed -> "Lent by your other wallet, and signed — their phone checks it."
                    verified -> "Yours on chain — their phone checks it, you can't be copied."
                    username.isNotBlank() -> "No tick: they only have your word for this name."
                    else -> "Nobody will know who asked. Tap to pick a name."
                },
                color = D.Quiet, fontSize = 11.sp,
            )
        }
        if (!verified) {
            Spacer(Modifier.width(8.dp))
            Text("Change", color = D.Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
private fun ContactNameDialog(
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
        title = { Text("Name this person", color = D.Black) },
        text = {
            Column {
                // What everyone else would see, so it is clear what is being overridden.
                if (called.domain != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(called.domain, color = D.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(4.dp))
                        HIcon(R.drawable.hi_verified, D.Blue, 14.dp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Their name on chain. Yours is just for you — this one still shows " +
                            "underneath.",
                        color = D.Quiet, fontSize = 12.sp,
                    )
                } else {
                    Text(
                        "Kept on this phone, and only you see it.",
                        color = D.Quiet, fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= Username.MAX) text = it },
                    singleLine = true,
                    isError = problem != null,
                    supportingText = {
                        Text(
                            problem ?: "Leave it empty to go back to their own name.",
                            color = if (problem != null) D.Danger else D.Quiet,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = problem == null) {
                Text("Save", color = D.Blue, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = D.Quiet) } },
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
        title = { Text(if (domain != null) "Your name" else "Pick a name", color = D.Black) },
        text = {
            Column {
                if (domain != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(domain, color = D.Black, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, fontFamily = Display)
                        Spacer(Modifier.width(5.dp))
                        HIcon(R.drawable.hi_verified, D.Blue, 17.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This is yours on chain, so everyone you tap sees it with the tick. " +
                            "Nothing to set up.",
                        color = D.Quiet, fontSize = 13.sp,
                    )
                } else {
                    Text(
                        "Shown on the other phone when you tap someone.",
                        color = D.Quiet, fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { if (it.length <= Username.MAX) text = it },
                        singleLine = true,
                        isError = problem != null,
                        supportingText = {
                            Text(
                                problem ?: "${text.trim().length}/${Username.MAX}",
                                color = if (problem != null) D.Danger else D.Quiet,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    // The nudge, stated as what it actually buys rather than as "security":
                    // a username only exists inside this app, and a domain does not.
                    Text(
                        "A name you type arrives without a tick — they only have your word " +
                            "for it. A .skr or .sol is yours on chain: it can't be copied, it " +
                            "works in every Solana app, and people can pay you by name from " +
                            "anywhere. Every Seeker comes with a .skr.",
                        color = D.Quiet, fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            if (domain != null) {
                TextButton(onClick = onDismiss) { Text("Done", color = D.Blue, fontWeight = FontWeight.Bold) }
            } else {
                TextButton(
                    onClick = { onSave(text) },
                    enabled = problem == null && Username.clean(text) != null,
                ) { Text("Save", color = D.Blue, fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            if (domain == null) TextButton(onClick = onDismiss) { Text("Cancel", color = D.Quiet) }
        },
    )
}
