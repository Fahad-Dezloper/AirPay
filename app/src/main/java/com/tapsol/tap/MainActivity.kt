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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    /** The reference we are watching the chain for, once a reader has taken our request. */
    private var watching by mutableStateOf<String?>(null)
    private var paidSignature by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wallet = Wallet(this)
        contacts = Contacts(this)
        contactCount = contacts.count()
        reader = TapReader(
            activity = this,
            onLog = { line -> runOnUiThread { push(line) } },
            onRequest = { req ->
                runOnUiThread {
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
                var amount by remember { mutableStateOf(lastAmount) }
                var busy by remember { mutableStateOf(false) }
                val diag = remember { Diagnostics.read(this@MainActivity) }

                LaunchedEffect(Unit) {
                    scope.launch { TapHostApduService.events.collect { push(it) } }
                    scope.launch {
                        TapHostApduService.served.collect { req ->
                            watching = req.reference
                            push("watching chain for ${req.reference.take(8)}…")
                        }
                    }
                }

                LaunchedEffect(mode, amount, connected) { applyMode(amount) }

                // The payee's half — runs with the phones long since parted.
                LaunchedEffect(watching) {
                    val ref = watching ?: return@LaunchedEffect
                    while (watching == ref) {
                        val sig = withContext(Dispatchers.IO) { Chain.findPayment(ref) }
                        if (sig != null) {
                            paidSignature = sig
                            push("✓ PAID — $sig")
                            // Retire it: a served-and-paid reference must never be payable
                            // twice, and we already saw one request served on two taps.
                            TapHostApduService.pending.value = null
                            mode = Mode.IDLE
                            watching = null
                            break
                        }
                        delay(2000)
                    }
                }

                Surface(Modifier.fillMaxSize(), color = Ink) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Header(diag)
                        Spacer(Modifier.height(14.dp))

                        WalletRow(connected, busy) {
                            scope.launch {
                                busy = true
                                push("opening wallet…")
                                when (val r = wallet.connect()) {
                                    is Wallet.Outcome.Ok -> {
                                        connected = r.address
                                        push("✓ wallet ${r.address.take(8)}…${r.address.takeLast(4)}")
                                        val b = withContext(Dispatchers.IO) {
                                            runCatching { Chain.balance(r.address) }.getOrNull()
                                        }
                                        if (b != null) push("   balance ${"%.4f".format(b)} SOL")
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
                                busy = false
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        if (mode == Mode.REQUEST) {
                            OutlinedTextField(
                                value = amount,
                                onValueChange = { amount = it },
                                label = { Text("Amount (SOL, devnet)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ModeButton("Request", mode == Mode.REQUEST, connected != null, Modifier.weight(1f)) {
                                mode = if (mode == Mode.REQUEST) Mode.IDLE else Mode.REQUEST
                                paidSignature = null
                            }
                            ModeButton("Pay", mode == Mode.PAY, connected != null, Modifier.weight(1f)) {
                                mode = if (mode == Mode.PAY) Mode.IDLE else Mode.PAY
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            when {
                                connected == null -> "Connect a wallet first."
                                watching != null -> "Waiting for payment…"
                                mode == Mode.REQUEST -> "Hold the other phone against the back of this one."
                                mode == Mode.PAY -> "Hold this phone against the one requesting."
                                else -> "Pick a side. One requests, one pays."
                            },
                            color = if (watching != null) Accent else Dim, fontSize = 13.sp,
                        )

                        paidSignature?.let { sig ->
                            Spacer(Modifier.height(10.dp))
                            Column(
                                Modifier.fillMaxWidth()
                                    .background(Accent.copy(alpha = .12f), RoundedCornerShape(8.dp))
                                    .border(1.dp, Accent, RoundedCornerShape(8.dp)).padding(12.dp)
                            ) {
                                Text("✓ Paid", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Text(sig, color = Dim, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
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
                            else scope.launch {
                                busy = true
                                pay(payer, req)
                                busy = false
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
        val lamports = ((req.amount.toDoubleOrNull() ?: 0.0) * Chain.LAMPORTS_PER_SOL).toLong()
        if (lamports <= 0) { push("✗ bad amount"); return }

        push("building transfer…")
        val tx = withContext(Dispatchers.IO) {
            runCatching { Chain.buildTransfer(payer, req.recipient, lamports, req.reference) }
                .onFailure { push("✗ build failed: ${it.message}") }
                .getOrNull()
        } ?: return

        push("→ wallet: sign ${req.amount} SOL to ${req.recipient.take(8)}…")
        when (val r = wallet.signAndSend(tx)) {
            is Wallet.SendOutcome.Sent -> {
                push("✓ SENT — ${r.signature}")
                push("   ${Chain.explorerUrl(r.signature)}")
                contacts.record(
                    req.recipient, req.label, req.genesis != null,
                    paid = req.amount.toDoubleOrNull() ?: 0.0,
                )
                contactCount = contacts.count()
            }
            is Wallet.SendOutcome.Failed -> push("✗ ${r.why}")
        }
    }

    private fun applyMode(amount: String = lastAmount) {
        lastAmount = amount
        if (mode == Mode.PAY) reader.start() else reader.stop()
        val addr = connected
        TapHostApduService.pending.value =
            if (mode == Mode.REQUEST && addr != null) PayRequest(
                recipient = addr,
                amount = amount,
                token = "SOL",
                label = android.os.Build.MODEL,
                reference = PayRequest.newReference(),
                genesis = myGenesis,
            ) else null
    }

    override fun onResume() {
        super.onResume()
        if (mode != Mode.IDLE) applyMode()
    }

    override fun onPause() {
        super.onPause()
        reader.stop()
        TapHostApduService.pending.value = null
    }

    private fun push(line: String) {
        log.add(line)
        if (log.size > 200) log.removeAt(0)
    }

    private enum class Mode { IDLE, REQUEST, PAY }
}

private val Ink = Color(0xFF0B0D10)
private val Panel = Color(0xFF14181E)
private val Line = Color(0xFF262E38)
private val Fg = Color(0xFFE8EDF3)
private val Dim = Color(0xFF8A96A4)
private val Accent = Color(0xFF14F195)
private val Warn = Color(0xFFE5534B)

@Composable
private fun WalletRow(address: String?, busy: Boolean, onConnect: () -> Unit) {
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
