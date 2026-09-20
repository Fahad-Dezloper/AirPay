package so.duffle.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    /** What the other phone shows when you tap them. A model number is not a name. */
    private var myName by mutableStateOf("")
    private var renaming by mutableStateOf(false)

    /** UI mirrors of the two on-disk lists. Disk is the truth; these are re-read after writes. */
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashGuard()
        wallet = Wallet(this)
        connected = wallet.address   // a session persisted from last launch
        contacts = Contacts(this)
        contactCount = contacts.count()
        inbox = Inbox(this)
        watch = Watch(this)
        myName = Identity.label(this)
        refreshLists()
        Push.init(this)

        reader = TapReader(
            activity = this,
            onLog = { line -> runOnUiThread { push(line) } },
            onRequest = { req -> runOnUiThread { onRead(req) } },
            // Their phone had no request, only its card: an introduction. The popup is where
            // you decide to pay them or ask them for money — the same one a closed app shows.
            onCard = { card -> runOnUiThread { startActivity(TapPopupActivity.intent(this, TapPayload.Card(card), cameFrom = "app")) } },
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Ink)) {
                val scope = rememberCoroutineScope()
                var amount by rememberSaveable { mutableStateOf(lastAmount) }
                var busy by remember { mutableStateOf(false) }
                // Re-read on every resume: NFC can be switched off while the app sits open,
                // and a stale "Ready" is worse than no indicator at all.
                var diag by remember { mutableStateOf(Diagnostics.read(this@MainActivity)) }
                LaunchedEffect(resumeTick) { diag = Diagnostics.read(this@MainActivity) }

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
                        for (e in owed.filter { !it.paid }) {
                            if (now < (next[e.reference] ?: 0L)) continue
                            checkOwed(e)
                            val age = now - e.createdAt
                            next[e.reference] = now + when {
                                age < 60_000 -> 2_000L
                                age < 10 * 60_000 -> 8_000L
                                else -> 60_000L
                            }
                        }
                        delay(500)
                    }
                }

                // The server's doorbell: while the app is on screen, a payment to us is
                // announced the moment the server sees it. The announcement is only a reason
                // to look — checkOwed verifies it on-chain before anything turns green.
                LaunchedEffect(connected) {
                    val addr = connected ?: return@LaunchedEffect
                    if (!ServerClient.enabled) return@LaunchedEffect
                    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        ServerClient.events(addr).collect { ev ->
                            when (ev) {
                                is ServerClient.Paid -> {
                                    val e = owed.firstOrNull { it.reference == ev.reference && !it.paid }
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
                                is ServerClient.Tapped -> {
                                    push("server: ${ev.card.label} tapped you")
                                    startActivity(TapPopupActivity.intent(this@MainActivity, TapPayload.Card(ev.card), cameFrom = "server"))
                                }
                            }
                        }
                    }
                }

                Surface(Modifier.fillMaxSize(), color = Ink) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Header(diag)
                        Spacer(Modifier.height(12.dp))
                        val openCount = toPay.count { !it.paid } + owed.count { !it.paid }
                        TabBar(tab, openCount) { tab = it }
                        Spacer(Modifier.height(12.dp))

                        when (val st = status) {
                            is Status.Working -> { Banner("…", st.what, Dim); Spacer(Modifier.height(10.dp)) }
                            is Status.Problem -> {
                                Banner(st.title, st.detail, Warn, onDismiss = { status = Status.Idle })
                                Spacer(Modifier.height(10.dp))
                            }
                            is Status.Sent -> {
                                Banner("✓ Sent", st.signature, Accent, mono = true,
                                    onDismiss = { status = Status.Idle })
                                Spacer(Modifier.height(10.dp))
                            }
                            Status.Idle -> Unit
                        }
                        paidSignature?.let { sig ->
                            Banner("✓ Paid", sig, Accent, mono = true, onDismiss = { paidSignature = null })
                            Spacer(Modifier.height(10.dp))
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

                        WalletRow(
                            connected, busy,
                            onDisconnect = {
                                scope.launch {
                                    wallet.disconnect()
                                    connected = null
                                    myGenesis = null
                                    mode = Mode.IDLE
                                    push("— wallet disconnected")
                                }
                            },
                        ) {
                            // Connecting opens the wallet app and suspends there, so it must
                            // outlive the composition and clear `busy` even when cancelled.
                            lifecycleScope.launch {
                                busy = true
                                try {
                                    push("opening wallet…")
                                    when (val r = wallet.connect()) {
                                        is Wallet.Outcome.Ok -> {
                                            myGenesis = null
                                            connected = r.address
                                            push("✓ wallet ${r.address.take(8)}…${r.address.takeLast(4)}")
                                            // Chain.balance returns a typed result, not a Double.
                                            // Formatting it with %f compiled fine and crashed at
                                            // runtime, because String.format accepts Any.
                                            when (val b = withContext(Dispatchers.IO) { Chain.balance(r.address) }) {
                                                is Chain.Balance.Ok -> push("   balance ${"%.4f".format(b.sol)} SOL")
                                                is Chain.Balance.Offline -> push("   balance unavailable — no connection")
                                            }
                                        }
                                        is Wallet.Outcome.Failed -> push("✗ ${r.why}")
                                        Wallet.Outcome.Declined -> push("— wallet declined")
                                    }
                                } finally {
                                    busy = false
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("They see you as ", color = Dim, fontSize = 12.sp)
                            TextButton(onClick = { renaming = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                Text(myName, color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Spacer(Modifier.height(6.dp))

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
                                supportingText = amountProblem?.let { { Text(it, color = Warn) } },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ModeButton("Request", mode == Mode.REQUEST, connected != null, Modifier.weight(1f)) {
                                if (mode == Mode.REQUEST) {
                                    mode = Mode.IDLE
                                } else {
                                    mode = Mode.REQUEST
                                    paidSignature = null
                                }
                            }
                            ModeButton("Pay", mode != Mode.REQUEST, connected != null, Modifier.weight(1f)) {
                                mode = Mode.PAY
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        val waiting = owed.count { !it.paid }
                        Text(
                            when {
                                connected == null -> "Connect a wallet first."
                                mode == Mode.REQUEST && waiting > 0 ->
                                    "Touch the backs of the phones together · $waiting waiting in Requests"
                                mode == Mode.REQUEST -> "Touch the backs of the phones together."
                                else -> "Ready. Touch backs with someone's phone to pay them."
                            },
                            color = Dim, fontSize = 13.sp,
                        )

                        if (contactCount > 0) {
                            Spacer(Modifier.height(14.dp))
                            ContactStrip(contacts.all())
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("EXCHANGE", color = Dim, fontSize = 10.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(6.dp))
                        LogView(log)
                    }
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
            val isNew = contacts.record(req.recipient, req.label, req.genesis != null)
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

    /** Our request was just handed to a payer. */
    private fun onServed(req: PayRequest) {
        watch.add(
            Watch.Entry(
                reference = req.reference,
                recipient = req.recipient,
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
                contacts.record(req.recipient, req.label, req.genesis != null, paid = req.amount.toDoubleOrNull() ?: 0.0)
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
            android.util.Log.e("Duffle", "uncaught on ${thread.name}", error)
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

enum class Tab { TAP, REQUESTS, REWARDS }

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
        Modifier.fillMaxWidth()
            .background(tone.copy(alpha = .10f), RoundedCornerShape(8.dp))
            .border(1.dp, tone.copy(alpha = .55f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = tone, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(detail, color = Dim, fontSize = if (mono) 9.sp else 12.sp,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                    lineHeight = 16.sp)
            }
        }
        if (onDismiss != null) {
            TextButton(onClick = onDismiss) { Text("OK", color = tone, fontSize = 12.sp) }
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
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp))
            .border(1.dp, if (address == null) Warn.copy(alpha = .5f) else Line, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (address == null) "No wallet" else "Wallet",
                color = Dim, fontSize = 10.sp, letterSpacing = 1.sp)
            Text(
                address?.let { "${it.take(10)}…${it.takeLast(6)}" } ?: "connect to send or receive",
                color = if (address == null) Warn else Fg,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
        }
        if (address == null) {
            Button(
                onClick = onConnect, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
            ) { Text(if (busy) "…" else "Connect", fontWeight = FontWeight.SemiBold) }
        } else {
            // The escape hatch for a session the wallet no longer honours. Without it the only
            // cure for a stale token is reinstalling the app.
            TextButton(onClick = onDisconnect, enabled = !busy) {
                Text("Disconnect", color = Dim, fontSize = 12.sp)
            }
        }
    }
}

/**
 * People met by tapping. The reason to open the app on a day you are not paying anyone —
 * and the seed of the social graph the reward loop needs, since a new counterparty is worth
 * more than a repeat one.
 */
@Composable
private fun ContactStrip(list: List<Contact>) {
    Column {
        Text("TAPPED ${list.size}", color = Dim, fontSize = 10.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        list.take(3).forEach { c ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(26.dp)
                        .background(if (c.seeker) Accent.copy(alpha = .18f) else Panel, RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(c.label.take(1).uppercase(),
                        color = if (c.seeker) Accent else Dim,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.label, color = Fg, fontSize = 13.sp)
                        if (c.seeker) {
                            Spacer(Modifier.width(6.dp))
                            Text("SEEKER", color = Accent, fontSize = 8.sp,
                                letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(c.short, color = Dim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                Text(
                    if (c.totalPaid > 0) "${"%.3f".format(c.totalPaid)} SOL" else "${c.taps} tap${if (c.taps > 1) "s" else ""}",
                    color = Dim, fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun Header(d: Diagnostics) {
    var open by remember { mutableStateOf(d.blocker != null) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DUFFLE", color = Fg, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { open = !open }) {
                Text(if (open) "hide" else "check", color = Dim, fontSize = 12.sp)
            }
        }
        Text(d.blocker ?: "Ready · ${d.model} · devnet",
            color = if (d.blocker != null) Warn else Accent, fontSize = 12.sp)
        if (open) {
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp))
                    .border(1.dp, Line, RoundedCornerShape(8.dp)).padding(12.dp)
            ) {
                d.lines().forEach { (label, ok) ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(if (ok) "✓" else "✗", color = if (ok) Accent else Warn,
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(10.dp))
                        Text(label, color = if (ok) Dim else Warn, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, active: Boolean, enabled: Boolean, m: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = m.height(52.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Accent else Panel,
            contentColor = if (active) Ink else Fg,
            disabledContainerColor = Panel.copy(alpha = .5f),
            disabledContentColor = Dim.copy(alpha = .5f),
        ),
    ) { Text(label, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun LogView(log: List<String>) {
    val state = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) state.animateScrollToItem(log.size - 1) }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize().background(Panel, RoundedCornerShape(8.dp))
            .border(1.dp, Line, RoundedCornerShape(8.dp)).padding(12.dp),
    ) {
        items(log) { line ->
            Text(line,
                color = when {
                    line.startsWith("✓") -> Accent
                    line.startsWith("✗") -> Warn
                    else -> Dim
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
        containerColor = Panel,
        title = { Text("Pay ${req.label}?", color = Fg) },
        text = {
            Column {
                Text("${req.amount} ${req.token}", color = Accent,
                    fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("to ${req.recipient.take(8)}…${req.recipient.takeLast(6)}",
                    color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                if (req.genesis != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("✓ Verified Seeker", color = Accent, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text("No rush — this stays in Requests until you pay it.", color = Dim, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onPay, enabled = !busy) {
                Text(if (busy) "…" else "Pay", color = Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDecline, enabled = !busy) { Text("Decline", color = Warn) }
                TextButton(onClick = onLater) { Text("Later", color = Dim) }
            }
        },
    )
}

@Composable
private fun TabBar(current: Tab, openCount: Int, onSelect: (Tab) -> Unit) {
    // Rewards only appears in a build that has them; without it the tab would open a screen
    // that can never say anything.
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp))
            .border(1.dp, Line, RoundedCornerShape(10.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        (listOf(Tab.TAP to "Tap", Tab.REQUESTS to if (openCount > 0) "Requests · $openCount" else "Requests") +
            if (Rewards.enabled) listOf(Tab.REWARDS to "Rewards") else emptyList())
            .forEach { (t, label) ->
                val on = t == current
                TextButton(
                    onClick = { onSelect(t) },
                    modifier = Modifier.weight(1f)
                        .background(if (on) Line else Color.Transparent, RoundedCornerShape(8.dp)),
                ) {
                    Text(label, color = if (on) Fg else Dim, fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
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
            Text("Nothing here yet", color = Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Requests you tap land here, and stay until they're paid.", color = Dim, fontSize = 13.sp)
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
                            Text("${item.request.amount} ${item.request.symbol}", color = if (gone) Dim else Fg,
                                fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            if (gone) {
                                Spacer(Modifier.width(8.dp))
                                Text("WITHDRAWN BY ${item.request.label.uppercase()}", color = Warn,
                                    fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.background(Warn.copy(alpha = .12f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp))
                            } else if (item.request.genesis != null) {
                                Spacer(Modifier.width(6.dp))
                                Text("SEEKER", color = Accent, fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Text(
                            if (gone) "${item.request.label} took this back"
                            else "${item.request.label} · tapped ${ago(item.readAt)}",
                            color = Dim, fontSize = 11.sp,
                        )
                    }
                    TextButton(onClick = { onDismissToPay(item) }, enabled = paying == null) {
                        Text("Remove", color = Dim, fontSize = 12.sp)
                    }
                    if (!gone) {
                        Button(
                            onClick = { onPay(item) }, enabled = paying == null,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
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
                            Text("${e.amount} ${e.symbol}", color = if (no) Dim else Fg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            if (no || later) {
                                val tag = if (no) "DECLINED BY ${e.rejectedBy!!.uppercase()}" else "PENDING"
                                val tone = if (no) Warn else Accent
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
                            color = Dim, fontSize = 11.sp,
                            fontFamily = if (no || later) FontFamily.Default else FontFamily.Monospace,
                        )
                    }
                    TextButton(onClick = { onDismissOwed(e) }) { Text("Remove", color = Dim, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (history.isNotEmpty()) {
            SectionLabel("DONE")
            history.forEach { (at, what, sig) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("✓", color = Accent, fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(what, color = Fg, fontSize = 13.sp)
                        Text("${ago(at)} · ${sig.take(10)}…", color = Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
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
            Text("Connect a wallet", color = Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Rewards are earned by the wallet that pays, so there is nothing to show until one is connected.",
                color = Dim, fontSize = 13.sp)
            return@Column
        }
        val s = summary
        if (s == null) {
            Spacer(Modifier.height(40.dp))
            Text(if (loading) "Checking…" else "Can't reach the server", color = Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (!loading) {
                Spacer(Modifier.height(6.dp))
                Text("Your rewards are safe on the server — this screen just can't read them right now.",
                    color = Dim, fontSize = 13.sp)
            }
            return@Column
        }

        // Offers first: they are the thing to do, the balance is the thing that happened.
        if (loading && offers.isEmpty()) {
            SectionLabel("OFFERS")
            Text("Checking…", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 14.dp))
        }
        if (offers.isNotEmpty()) {
            SectionLabel("OFFERS")
            offers.forEach { offer ->
                Column(RowStyle) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(offer.what, color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (offer.funder == "duffle") "${offer.app} · from Duffle"
                                else "${offer.app} · from ${offer.funder}",
                                color = Dim, fontSize = 12.sp,
                            )
                        }
                        if (offer.reward > 0) {
                            Text("${offer.reward} SKR", color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    when {
                        offer.state == "paid" ->
                            Text("✓ Earned ${offer.earned} SKR", color = Accent, fontSize = 13.sp)
                        offer.waiting -> {
                            // Claimed and waiting. The reason it hasn't paid is worth showing:
                            // "pay again" is actionable, "nothing happened" is not.
                            Text(Rewards.offerWhy(offer.refused)
                                ?: "Claimed · pay ${offer.app} within ${offer.windowHours}h",
                                color = Dim, fontSize = 13.sp)
                        }
                        offer.state == "expired" ->
                            Text("The window closed — claim it again", color = Dim, fontSize = 13.sp)
                        // Say it before, not after. A green Claim button on a phone that can
                        // never claim is the same mistake as a reward rule nobody hears about
                        // until they are not paid — and this one is answerable on the spot,
                        // because the app already knows whether this wallet holds Genesis.
                        !isSeeker -> Text("Seeker only — this wallet holds no Genesis Token",
                            color = Dim, fontSize = 13.sp)
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
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink),
                            shape = RoundedCornerShape(8.dp),
                        ) { Text(if (claiming == offer.id) "…" else "Claim", fontWeight = FontWeight.Bold) }
                    }
                }
            }
            claimProblem?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Warn, fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
        }

        // The total, with the split underneath. "Locked" is not a footnote: a reward can still
        // be reversed for a week, and saying so now is cheaper than explaining it after.
        Column(
            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp))
                .border(1.dp, Line, RoundedCornerShape(10.dp)).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${s.total}", color = Accent, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text("SKR", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
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
                color = Dim, fontSize = 13.sp,
            )
            if (s.settled > 0) {
                Spacer(Modifier.height(2.dp))
                Text("${s.settled} paid out", color = Dim, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        // This week, as a row of pips rather than a number: four is small enough to see, and
        // the streak is the thing worth finishing.
        Column(RowStyle) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("This week", color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${s.weekPayments} of ${s.weekAllows}", color = Dim, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                repeat(s.weekAllows) { i ->
                    Box(
                        Modifier.weight(1f).height(6.dp)
                            .background(if (i < s.weekPayments) Accent else Line, RoundedCornerShape(3.dp)),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (s.weekPayments >= s.streakNeeded) "Streak complete — ${s.streak} SKR earned"
                else "${s.streakNeeded - s.weekPayments} more this week for the ${s.streak} SKR streak",
                color = Dim, fontSize = 12.sp,
            )
        }

        Column(RowStyle) {
            Row {
                Text("This month", color = Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${s.monthEarned} of ${s.monthCap} SKR", color = Fg, fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Text("Left in this week's pool", color = Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
                // An empty pool pays nobody, and the screen says so rather than letting the
                // next payment quietly earn nothing.
                Text(if (s.poolLeft > 0) "${s.poolLeft} SKR" else "empty — nothing earns until it refills",
                    color = if (s.poolLeft > 0) Fg else Warn, fontSize = 13.sp)
            }
        }

        if (s.rows.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionLabel("EARNED")
            s.rows.forEach { row ->
                Row(RowStyle, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(Rewards.nameOf(row.kind), color = Fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        // A reward taken back says why, in the same place the reward was. It
                        // is not deleted: someone who saw 300 SKR and later sees 0 deserves
                        // the reason on the same screen rather than a number that changed.
                        Text(
                            when {
                                row.state == "reversed" -> row.reason?.substringAfter(": ") ?: "taken back"
                                row.state == "locked" -> "with ${row.counterparty.take(4)}…${row.counterparty.takeLast(4)} · locked"
                                else -> "with ${row.counterparty.take(4)}…${row.counterparty.takeLast(4)}"
                            },
                            color = if (row.state == "reversed") Warn else Dim, fontSize = 12.sp,
                        )
                    }
                    Text(if (row.state == "reversed") "−${row.skr}" else "+${row.skr}",
                        color = if (row.state == "reversed") Warn else Accent,
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
                        Text(refusalText(ref.why), color = Fg, fontSize = 13.sp)
                        Text("${ref.counterparty.take(4)}…${ref.counterparty.takeLast(4)}", color = Dim, fontSize = 12.sp)
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

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Dim, fontSize = 10.sp, letterSpacing = 2.sp)
    Spacer(Modifier.height(6.dp))
}

private val RowStyle = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    .background(Panel, RoundedCornerShape(8.dp))
    .border(1.dp, Line, RoundedCornerShape(8.dp))
    .padding(horizontal = 12.dp, vertical = 8.dp)

/**
 * Naming yourself. Until someone does, they are a phone model to everybody they tap — and
 * "CPH2401 wants 0.5 SOL" is a worse thing to approve than "Fahad wants 0.5 SOL".
 */
@Composable
private fun NameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Your name", color = Fg) },
        text = {
            Column {
                Text("Shown on the other phone when you tap someone.", color = Dim, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= 40) text = it },
                    singleLine = true,
                    placeholder = { Text(android.os.Build.MODEL, color = Dim) },
                    supportingText = { Text("${text.trim().length}/40", color = Dim) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                Text("Save", color = Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Dim) } },
    )
}
