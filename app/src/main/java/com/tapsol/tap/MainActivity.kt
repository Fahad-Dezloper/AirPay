package com.tapsol.tap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 2: the tap moves real money.
 *
 * Note how little happens while the phones are touching:
 *
 *   payee   connect wallet → Request → HCE serves {recipient, amount, reference}
 *   TAP     ~100ms, one direction                        ← phones part here
 *   payer   confirm → build transfer → MWA signs → submitted to devnet
 *   payee   polls getSignaturesForAddress(reference) → "Paid ✓"
 */
class MainActivity : ComponentActivity() {

    private lateinit var reader: TapReader
    private lateinit var wallet: Wallet
    private lateinit var contacts: Contacts

    /** Our own Genesis mint, if this phone's wallet holds one. Attached to every request. */
    private var myGenesis by mutableStateOf<String?>(null)
    private var contactCount by mutableIntStateOf(0)
    private val log = mutableStateListOf<String>()

    private var incoming by mutableStateOf<PayRequest?>(null)

    /**
     * Mode lives here rather than only in composition because reader mode is torn down by the
     * platform on every pause — a notification, a glance away, even a screenshot. Without
     * re-arming on resume the button stays lit while the radio is silent.
     */
    private var mode by mutableStateOf(Mode.IDLE)
    private var lastAmount = "0.01"
    private var connected by mutableStateOf<String?>(null)

    /**
     * Every payment handed out and not yet settled.
     *
     * A list because the payee should not be blocked: after serving one phone it re-arms
     * immediately and can take the next tap while the first payer is still in their wallet.
     * Each entry carries its own reference and its own blockhash, so they settle and expire
     * independently of one another.
     */
    private val outstanding = mutableStateListOf<Watch.Armed>()
    private lateinit var watch: Watch
    private var paidSignature by mutableStateOf<String?>(null)

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

    /** References this phone has already paid, so a repeat tap cannot pay twice. */
    private val paidReferences = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashGuard()
        wallet = Wallet(this)
        connected = wallet.address   // a session persisted from last launch
        contacts = Contacts(this)
        contactCount = contacts.count()
        watch = Watch(this)
        // A request that outlived the app. The payer may have settled it while we were gone,
        // so resume the wait rather than lose a payment that already happened.
        // Payments that outlived the app. The payers may have settled while we were gone,
        // so the whole queue is resumed rather than lost.
        watch.all().forEach { a ->
            outstanding.add(a)
            push("resuming wait for ${a.reference.take(8)}…")
        }
        reader = TapReader(
            activity = this,
            onLog = { line -> runOnUiThread { push(line) } },
            onRequest = { req ->
                runOnUiThread {
                    // Reader mode fires repeatedly while phones stay in contact. Swapping the
                    // request under an open dialog could have someone approve a different one
                    // than they read.
                    if (incoming != null) return@runOnUiThread
                    incoming = req
                    // The tap itself is the introduction — record them before any payment,
                    // because a declined payment is still a person you met.
                    val isNew = contacts.record(req.recipient, req.label, req.genesis != null)
                    contactCount = contacts.count()
                    push(if (isNew) "+ new contact ${req.label}" else "— known contact ${req.label}")
                }
            },
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
                        TapHostApduService.served.collect { req ->
                            // The hash we just handed over IS the payer's hash, so polling it
                            // tells us exactly when their transaction stops being submittable.
                            val entry = Watch.Armed(
                                reference = req.reference,
                                amount = req.amount,
                                startedAt = System.currentTimeMillis(),
                                blockhash = req.blockhash,
                                // The tap is one-way: the payee is read, and never learns
                                // who read it. The amount is the only honest label we have.
                                label = req.amount,
                            )
                            outstanding.add(entry)
                            watch.add(entry)
                            push("watching chain for ${req.reference.take(8)}… (${outstanding.size} open)")
                            // Re-arm straight away so the next person can tap while this payer
                            // is still deciding. This is the whole point of a queue.
                            applyMode(amount)
                        }
                    }
                }

                LaunchedEffect(mode, amount, connected) { applyMode(amount) }

                // The blockhash inside an armed request is the deadline BOTH phones will bind
                // to, so it has to be fresh at the moment of the tap — not at the moment
                // Request was pressed.
                //
                // The interval is the payer's whole budget. A hash lives ~60s, so whatever age
                // it has when handed over is time the payer does not get: refreshing every 20s
                // meant serving a hash up to 20s old, leaving as little as 40s to read the
                // sheet, press Pay, wait for the wallet to launch, and approve. That is not
                // enough, and it read as "the sender keeps getting stuck". Five seconds costs
                // a request per five seconds while the Request screen is open — only while it
                // is open — and hands the payer very nearly the full window.
                LaunchedEffect(mode, connected) {
                    if (mode != Mode.REQUEST || connected == null) return@LaunchedEffect
                    while (true) {
                        val bh = withContext(Dispatchers.IO) { Chain.latestBlockhash() }
                        val live = TapHostApduService.pending.value
                        if (bh != null && live != null && live.blockhash != bh) {
                            TapHostApduService.pending.value = live.copy(blockhash = bh)
                            android.util.Log.i("TapUI", "armed hash ${bh.take(8)}…")
                        }
                        delay(5_000)
                    }
                }

                // The payee's half — runs with the phones long since parted, over EVERY
                // outstanding payment at once. A single blocking wait meant the second person
                // in a queue could not tap until the first had paid; there is no reason for
                // that, since each payment is tracked by its own reference on the chain.
                LaunchedEffect(Unit) {
                    val offline = mutableMapOf<String, Int>()
                    val lastHashCheck = mutableMapOf<String, Long>()
                    while (true) {
                        val now = System.currentTimeMillis()
                        val queue = outstanding.toList()
                        if (queue.isEmpty()) {
                            delay(400)
                            continue
                        }
                        for (p in queue) {
                            if (outstanding.none { it.reference == p.reference }) continue
                            val waited = now - p.startedAt
                            android.util.Log.i(
                                "TapUI", "poll ref=${p.reference.take(8)} waited=${waited}ms",
                            )
                            val found = withContext(Dispatchers.IO) { Chain.findPayment(p.reference) }
                            android.util.Log.i("TapUI", "poll -> $found")

                            if (found is Chain.Found.Yes) {
                                settle(p, found.signature)
                                continue
                            }

                            // "Not yet", "can't tell" and "our question was wrong" must not
                            // look the same. The last is the dangerous one: it wears the same
                            // spinner as patience while the payment has already arrived.
                            when (found) {
                                is Chain.Found.Broken -> status = Status.Problem(
                                    "Can't check for payment",
                                    "Solana rejected our query: ${found.why}",
                                )
                                Chain.Found.Offline -> {
                                    val n = (offline[p.reference] ?: 0) + 1
                                    offline[p.reference] = n
                                    if (n >= 3) status = Status.Problem(
                                        "Can't reach Solana",
                                        "Still waiting, but we cannot check right now.",
                                    )
                                }
                                else -> offline[p.reference] = 0
                            }

                            // Is this payer's attempt still able to land at all? Their
                            // transaction is bound to the blockhash we handed them, and once
                            // that dies their signature is worthless. Checked on the payer's
                            // own cadence so both phones conclude it within a poll of each
                            // other; null — "could not ask" — is never read as dead.
                            val bh = p.blockhash
                            val due = now - (lastHashCheck[p.reference] ?: 0L) > 4_000
                            var dead = false
                            if (bh != null && waited > EXPIRY_FLOOR_MS && due) {
                                lastHashCheck[p.reference] = now
                                if (withContext(Dispatchers.IO) { Chain.blockhashAlive(bh) } == false) {
                                    // One last look: it could have landed as the hash died.
                                    val late = withContext(Dispatchers.IO) { Chain.findPayment(p.reference) }
                                    if (late is Chain.Found.Yes) {
                                        settle(p, late.signature)
                                        continue
                                    }
                                    dead = true
                                    push("— ${p.reference.take(8)}… can no longer land")
                                }
                            }

                            if (dead || waited > WATCH_TIMEOUT_MS) {
                                // Stop watching, but do NOT forget unless it is truly old: a
                                // payer who took six minutes still moved real SOL, and the
                                // payee has to find out eventually. Each launch buys the
                                // reference one more look at the chain.
                                val abandoned = waited > Watch.ABANDON_MS
                                if (abandoned) watch.remove(p.reference)
                                outstanding.removeAll { it.reference == p.reference }
                                push("— expired, nobody paid ${p.reference.take(8)}…")
                                status = Status.Problem(
                                    if (abandoned) "Never paid" else "Payment not sent",
                                    if (abandoned) "That ${p.amount} SOL request was never paid."
                                    else "That ${p.amount} SOL request was not approved in time.",
                                )
                            }
                        }
                        // Tight while payers are plausibly still in their wallets, relaxed
                        // after that. Scaled by queue size so a long queue is not a flood.
                        val youngest = outstanding.minOfOrNull { now - it.startedAt } ?: 0
                        delay(if (youngest < 30_000) 600L else 2_000L)
                    }
                }

                Surface(Modifier.fillMaxSize(), color = Ink) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Header(diag)
                        Spacer(Modifier.height(14.dp))

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
                            // Same shape as paying: connecting opens the wallet app and
                            // suspends there, so it outlives the composition and must clear
                            // `busy` even when cancelled.
                            lifecycleScope.launch {
                                busy = true
                                try {
                                push("opening wallet…")
                                when (val r = wallet.connect()) {
                                    is Wallet.Outcome.Ok -> {
                                        connected = r.address
                                        push("✓ wallet ${r.address.take(8)}…${r.address.takeLast(4)}")
                                        // Chain.balance returns a typed result, not a Double.
                                        // Formatting it with %f compiled fine and crashed at
                                        // runtime, because String.format accepts Any.
                                        when (val b = withContext(Dispatchers.IO) { Chain.balance(r.address) }) {
                                            is Chain.Balance.Ok ->
                                                push("   balance ${"%.4f".format(b.sol)} SOL")
                                            is Chain.Balance.Offline ->
                                                push("   balance unavailable — no connection")
                                        }
                                        // Genesis lives on mainnet even though we pay on devnet.
                                        val g = withContext(Dispatchers.IO) {
                                            runCatching { Genesis.mintFor(r.address) }.getOrNull()
                                        }
                                        myGenesis = g
                                        push(
                                            if (g != null) "✓ Verified Seeker — genesis ${g.take(8)}…"
                                            else "   no Genesis Token on this wallet"
                                        )
                                    }
                                    is Wallet.Outcome.Failed -> push("✗ ${r.why}")
                                    Wallet.Outcome.Declined -> push("— wallet declined")
                                }
                                } finally {
                                    busy = false
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        if (mode == Mode.REQUEST) {
                            val amountProblem = when {
                                amount.isBlank() -> "Enter an amount"
                                amount.toDoubleOrNull() == null -> "That is not a number"
                                amount.toDouble() <= 0 -> "Must be more than zero"
                                amount.toDouble() > PayRequest.MAX_AMOUNT -> "Too large for a tap"
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
                            ModeButton("Pay", mode == Mode.PAY, connected != null, Modifier.weight(1f)) {
                                mode = if (mode == Mode.PAY) Mode.IDLE else Mode.PAY
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            when {
                                connected == null -> "Connect a wallet first."
                                mode == Mode.REQUEST && outstanding.isNotEmpty() ->
                                    "Ready for the next tap — ${outstanding.size} still to pay."
                                mode == Mode.REQUEST -> "Hold the other phone against the back of this one."
                                mode == Mode.PAY -> "Hold this phone against the one requesting."
                                outstanding.isNotEmpty() -> "Waiting on ${outstanding.size} payment(s)…"
                                else -> "Pick a side. One requests, one pays."
                            },
                            color = if (outstanding.isNotEmpty()) Accent else Dim, fontSize = 13.sp,
                        )

                        if (outstanding.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            OutstandingList(outstanding) { ref ->
                                push("— stopped waiting on ${ref.take(8)}…")
                                watch.remove(ref)
                                outstanding.removeAll { it.reference == ref }
                            }
                        }

                        // The payer can abandon a payment — decline it, close the wallet, walk
                        // away — and nothing about that reaches us over the chain. Without this
                        // the payee is pinned to a countdown for a payment that is never coming.
                        if (outstanding.size > 1) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { cancelWait("stopped waiting") },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Stop waiting on all", color = Warn, fontSize = 13.sp) }
                        }

                        paidSignature?.let { sig ->
                            Spacer(Modifier.height(10.dp))
                            Banner("✓ Paid", sig, Accent, mono = true)
                        }
                        when (val st = status) {
                            is Status.Working -> { Spacer(Modifier.height(10.dp)); Banner("…", st.what, Dim) }
                            is Status.Problem -> {
                                Spacer(Modifier.height(10.dp))
                                Banner(st.title, st.detail, Warn, onDismiss = { status = Status.Idle })
                            }
                            is Status.Sent -> {
                                Spacer(Modifier.height(10.dp))
                                Banner("✓ Sent", st.signature, Accent, mono = true,
                                    onDismiss = { status = Status.Idle })
                            }
                            Status.Idle -> Unit
                        }

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

                incoming?.let { req ->
                    ConfirmSheet(
                        req = req,
                        busy = busy,
                        onPay = {
                            val payer = connected
                            incoming = null
                            if (payer == null) push("✗ connect a wallet first")
                            // lifecycleScope, not the composition's scope: paying suspends
                            // inside the wallet for as long as the user takes — a minute and a
                            // half of Phantom is normal — and a composition disposed behind
                            // that dialog would cancel the payment mid-flight.
                            //
                            // finally, not a trailing assignment: cancellation skips the line
                            // after a suspend point, which left `busy` stuck true and the
                            // confirm button reading "…" instead of "Pay" for the rest of the
                            // session, with no way back short of restarting the app.
                            else lifecycleScope.launch {
                                busy = true
                                try {
                                    pay(payer, req)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        onCancel = { push("— declined"); incoming = null },
                    )
                }
            }
        }
    }

    /** The payer's half. Runs entirely with the phones already apart. */
    private suspend fun pay(payer: String, req: PayRequest) {
        // The confirm sheet renders req.token, but buildTransfer only ever makes a SystemProgram
        // transfer. A request denominated in USDC would have displayed "5 USDC" and sent 5 SOL.
        // Refusing is the only honest option until SPL transfers exist.
        if (!req.token.equals("SOL", ignoreCase = true)) {
            status = Status.Problem(
                "Can't pay in ${req.token}",
                "This version only sends SOL. Ask them to request SOL instead.",
            )
            return
        }

        // Paying yourself burns a fee and achieves nothing.
        if (req.recipient == payer) {
            status = Status.Problem("That's your own wallet", "Pick a different phone to pay.")
            return
        }

        // The payee retires a reference once they see payment, but the payer can tap the same
        // request again first. On-chain that is a second real transfer.
        if (paidReferences.contains(req.reference)) {
            status = Status.Problem("Already paid", "You have paid this request. Ask for a new one.")
            return
        }

        val lamports = ((req.amount.toDoubleOrNull() ?: 0.0) * Chain.LAMPORTS_PER_SOL).toLong()

        // Bind to the hash that crossed the tap. Check it is still alive first: signing
        // against a dead blockhash wastes the user's fingerprint on a transaction the network
        // will refuse, and the payee has already stopped waiting for it.
        val shared = req.blockhash
        if (shared != null) {
            if (withContext(Dispatchers.IO) { Chain.blockhashAlive(shared) } == false) {
                status = Status.Problem(
                    "This request expired",
                    "They took it back. Ask them to tap again.",
                )
                return
            }
        }

        push("pay: building on ${shared?.take(8) ?: "own"} hash")
        status = Status.Working("Checking your balance…")
        val built = withContext(Dispatchers.IO) {
            Chain.buildTransfer(payer, req.recipient, lamports, req.reference, sharedBlockhash = shared)
        }

        val tx = when (built) {
            is Chain.Built.Ok -> built.tx
            is Chain.Built.NotEnough -> {
                status = Status.Problem(
                    "Not enough SOL",
                    "You have ${"%.4f".format(built.have)} and this needs ${"%.4f".format(built.need)} plus fees.",
                )
                return
            }
            is Chain.Built.Offline -> {
                status = Status.Problem("Can't reach Solana", "Check your connection and try again.")
                return
            }
            is Chain.Built.Bad -> {
                status = Status.Problem("Can't pay this request", built.why)
                return
            }
        }

        // The wallet's approval sheet has no deadline of its own — Phantom will sit on that
        // screen indefinitely — but the transaction underneath it does: it is bound to the
        // blockhash just fetched, and dies with it. Without saying so, the payer stares at an
        // approve button that has already stopped meaning anything, while the payee has
        // already been told the payment is dead. A ticker says it on this side too.
        //
        // It does NOT cancel the wallet call. Aborting an MWA transact mid-flight risks
        // leaving the session wedged, and there is nothing to gain: we submit the signature
        // ourselves, so a late approval simply fails preflight and is reported honestly.
        push("pay: opening wallet")
        status = Status.Working("Waiting for your wallet…")
        val openedAt = System.currentTimeMillis()
        val deadline = lifecycleScope.launch {
            if (shared == null) {
                // No shared hash to watch — fall back to the blockhash lifetime as a bound.
                delay(APPROVE_WINDOW_MS)
            } else {
                // Watch the very hash the payee is watching, so both phones give up together.
                while (withContext(Dispatchers.IO) { Chain.blockhashAlive(shared) } != false) {
                    delay(4_000)
                }
            }
            push("pay: approval window CLOSED after ${System.currentTimeMillis() - openedAt}ms")
            status = Status.Problem(
                "Payment not sent",
                "The approval window closed before you approved. Nothing was sent — tap again.",
            )
            // The wallet's own sheet has no deadline and will sit there for another minute,
            // showing an approve button that has already stopped meaning anything. Ask to come
            // forward so the payer sees the truth instead. Android restricts activity starts
            // from the background, so this may be refused — the state is set either way, and
            // is waiting whenever they come back on their own.
            bringForward()
        }
        val r = wallet.signOnly(tx)
        deadline.cancel()
        push("pay: wallet returned after ${System.currentTimeMillis() - openedAt}ms")
        when (r) {
            is Wallet.SendOutcome.Signed -> {
                // We submit, not the wallet — so the cluster is ours to choose.
                status = Status.Working("Sending…")
                when (val sent = withContext(Dispatchers.IO) { Chain.submit(r.tx) }) {
                    is Chain.Submitted.Ok -> {
                        paidReferences.add(req.reference)
                        status = Status.Sent(sent.signature)
                        push("✓ SENT — ${sent.signature}")
                        push("   ${Chain.explorerUrl(sent.signature)}")
                        contacts.record(
                            req.recipient, req.label, req.genesis != null,
                            paid = req.amount.toDoubleOrNull() ?: 0.0,
                        )
                        contactCount = contacts.count()
                    }
                    is Chain.Submitted.Rejected -> {
                        val stale = sent.why.contains("blockhash", true)
                        status = Status.Problem(
                            if (stale) "That took too long" else "The network refused it",
                            if (stale) "Tap again and approve a little quicker." else sent.why,
                        )
                    }
                    Chain.Submitted.Offline -> status = Status.Problem(
                        "Can't reach Solana", "Signed, but not sent. Check your connection.",
                    )
                }
            }
            is Wallet.SendOutcome.Failed -> {
                // A blockhash that expired while the user was deciding is the common case,
                // and "expired" is meaningless to a person.
                val stale = r.why.contains("blockhash", true) || r.why.contains("expired", true)
                status = Status.Problem(
                    if (stale) "That took too long" else "Payment not sent",
                    if (stale) "Tap again and approve a little quicker." else r.why,
                )
            }
            // Wallet.signAndSend retries internally and never surfaces this, but the compiler
            // is right to insist — an unhandled branch here would be a silent dead end.
            Wallet.SendOutcome.NeedsReconnect -> {
                connected = null
                status = Status.Problem(
                    "Wallet needs reconnecting",
                    "Tap Connect and approve again, then retry the payment.",
                )
            }
        }
    }

    /**
     * Abandons whatever payment we were waiting for.
     *
     * Nothing about a payer giving up — declining, closing the wallet, letting the blockhash
     * expire — travels back over the chain, so the payee cannot detect it and must be able to
     * say so. Clearing the disk entry too: this is a deliberate "that one is not coming".
     */
    /**
     * Ask to be the foreground app again.
     *
     * Best effort by design: since Android 10 a background app usually cannot start an
     * activity, and we may well be refused. Never rely on it — it is a courtesy on top of
     * state that is already correct.
     */
    private fun bringForward() {
        val ok = runCatching {
            startActivity(
                android.content.Intent(this, MainActivity::class.java).addFlags(
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                )
            )
        }.isSuccess
        push("pay: bring-to-front ${if (ok) "requested" else "refused by Android"}")
    }

    /** One payment arrived. Retire it and leave the rest of the queue running. */
    private fun settle(p: Watch.Armed, signature: String) {
        paidSignature = signature
        status = Status.Idle
        push("✓ PAID ${p.amount} SOL — $signature")
        watch.remove(p.reference)
        outstanding.removeAll { it.reference == p.reference }
    }

    /**
     * Give up on everything outstanding.
     *
     * Nothing about a payer abandoning a payment reaches the chain, so the payee can never
     * observe it directly and must be able to say so by hand. Clearing the disk entries too:
     * this is a deliberate "those are not coming".
     */
    private fun cancelWait(why: String) {
        if (outstanding.isEmpty()) return
        push("— $why (dropped ${outstanding.size})")
        watch.clear()
        outstanding.clear()
        TapHostApduService.pending.value = null
        status = Status.Idle
    }

    private fun applyMode(amount: String = lastAmount) {
        lastAmount = amount
        if (mode == Mode.PAY) reader.start() else reader.stop()
        val addr = connected
        if (mode != Mode.REQUEST || addr == null) {
            TapHostApduService.pending.value = null
            return
        }
        // Deliberately NOT blocked by outstanding payments any more. Each one is tracked by
        // its own reference, so arming the next request cannot be confused with the last —
        // and refusing to arm was what made the second person in a queue wait on the first.
        val live = TapHostApduService.pending.value
        // Keep the same reference across a pause/resume. This runs again on every onResume,
        // and minting a fresh reference each time meant a notification — or the screen simply
        // sleeping — silently swapped the key under a request already on screen. The payer
        // would then pay a reference we had stopped watching, and ✓ Paid would never arrive.
        if (live != null &&
            live.recipient == addr && live.amount == amount && live.genesis == myGenesis
        ) return

        TapHostApduService.pending.value = PayRequest(
            recipient = addr,
            amount = amount,
            token = "SOL",
            label = android.os.Build.MODEL,
            reference = PayRequest.newReference(),
            genesis = myGenesis,
        )
    }

    /** Bumped on resume so composables that cache device state can re-read it. */
    private var resumeTick by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick++
        if (mode != Mode.IDLE) applyMode()
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
     * This keeps the UI alive and says something honest instead of vanishing.
     *
     * Not a substitute for handling errors where they happen — it is the net under the net.
     */
    private fun installCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            android.util.Log.e("Tap", "uncaught on ${thread.name}", error)
            if (thread == mainLooper.thread) {
                // The main thread is unrecoverable; let the platform handle it.
                previous?.uncaughtException(thread, error)
            } else {
                runOnUiThread {
                    status = Status.Problem(
                        "Something went wrong",
                        "That step failed unexpectedly. Nothing was sent — try the tap again.",
                    )
                }
            }
        }
    }

    private enum class Mode { IDLE, REQUEST, PAY }

    private companion object {
        /**
         * How long the payee actively waits before calling a payment expired.
         *
         * Nothing about a payer giving up reaches the chain, so absence of a transaction is
         * the only evidence the payee ever gets — and absence looks identical to slowness.
         * The one real bound is Solana's: a transaction is built against a blockhash that
         * dies after ~150 slots, roughly 60–90 seconds. Once that window plus a margin has
         * passed, the payer's transaction cannot land, and waiting longer is just a spinner
         * pretending to be patience.
         *
         * Two minutes, not five. The payer could of course tap again and build afresh, which
         * is why the reference stays on disk for a late look rather than being deleted here.
         */
        const val WATCH_TIMEOUT_MS = 2 * 60 * 1000L

        /**
         * No expiry is declared before this, however the chain answers — insurance against a
         * hash that was already stale when armed, or a bad answer from the node.
         *
         * It was 40s, from when each side timed its own hash and the payee's was the older
         * one. Both now watch the SAME hash, so the guard no longer protects against anything
         * the payer is not equally exposed to — it only made the payee announce expiry seven
         * seconds after the payer had already given up on the very same fact. Measured: payer
         * saw the hash die at 33s, payee was gagged until 40s.
         */
        const val EXPIRY_FLOOR_MS = 12 * 1000L

        /**
         * How long the payer's signature is worth having. A transaction is only submittable
         * while its blockhash lives (~150 slots), and the blockhash is fetched immediately
         * before the wallet is opened, so this is that window less a little slack.
         */
        const val APPROVE_WINDOW_MS = 55 * 1000L
    }
}

private val Ink = Color(0xFF0B0D10)
private val Panel = Color(0xFF14181E)
private val Line = Color(0xFF262E38)
private val Fg = Color(0xFFE8EDF3)
private val Dim = Color(0xFF8A96A4)
private val Accent = Color(0xFF14F195)
private val Warn = Color(0xFFE5534B)

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
            Text("TAP", color = Fg, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
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
private fun ConfirmSheet(req: PayRequest, busy: Boolean, onPay: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Panel,
        title = { Text("Pay ${req.label}?", color = Fg) },
        text = {
            Column {
                Text("${req.amount} ${req.token}", color = Accent,
                    fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("to ${req.recipient.take(8)}…${req.recipient.takeLast(6)}",
                    color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(4.dp))
                Text("ref ${req.reference.take(12)}…", color = Dim.copy(alpha = .6f),
                    fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                if (req.genesis != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("✓ Verified Seeker", color = Accent, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onPay, enabled = !busy) {
                Text(if (busy) "…" else "Pay", color = Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = Dim) } },
    )
}

/**
 * The payments handed out and not yet settled.
 *
 * Visible as a list rather than a single line because the payee can now be owed by several
 * people at once, and "waiting" without saying who for is not information. Each row can be
 * dropped on its own — one payer walking away should not cost the others their place.
 */
@Composable
private fun OutstandingList(items: List<Watch.Armed>, onDrop: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("AWAITING PAYMENT", color = Dim, fontSize = 10.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        items.forEach { p ->
            val age = ((System.currentTimeMillis() - p.startedAt) / 1000).toInt()
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${p.amount} SOL", color = Accent, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("${p.reference.take(8)}… · ${age}s", color = Dim, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                TextButton(onClick = { onDrop(p.reference) }) {
                    Text("Drop", color = Warn, fontSize = 11.sp)
                }
            }
        }
    }
}
