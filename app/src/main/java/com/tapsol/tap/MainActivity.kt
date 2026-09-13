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
import kotlinx.coroutines.launch

/**
 * Phase 1: prove two phones can exchange bytes on a tap.
 *
 * The raw APDU log IS the feature here — no amount of UI polish is worth anything until the
 * radio exchange is reliable, and when it misbehaves the only useful evidence is the traffic.
 * Real payment flow arrives in Phase 2; this screen exists to be stared at.
 */
class MainActivity : ComponentActivity() {

    private lateinit var reader: TapReader
    private val log = mutableStateListOf<String>()

    /** Stand-in until MWA lands in Phase 2. */
    private val myAddress = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"

    private var incoming by mutableStateOf<PayRequest?>(null)
    private var respond: ((PayAck) -> Boolean)? = null

    /**
     * Lives here rather than only in composition because reader mode is torn down by the
     * platform on every pause — a notification, a glance away, even a screenshot. Without
     * re-arming on resume the button stays lit while the radio is silent, which is exactly
     * how the first Phase 1 test failed: a contactless card produced no tag at all.
     */
    private var mode by mutableStateOf(Mode.IDLE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        reader = TapReader(
            activity = this,
            onLog = { line -> runOnUiThread { push(line) } },
            onRequest = { req, send ->
                runOnUiThread {
                    incoming = req
                    respond = send
                }
            },
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Ink)) {
                val scope = rememberCoroutineScope()
                var amount by remember { mutableStateOf("2.50") }

                LaunchedEffect(Unit) {
                    scope.launch { TapHostApduService.events.collect { push(it) } }
                }

                LaunchedEffect(mode, amount) { applyMode(amount) }

                val diag = remember { Diagnostics.read(this@MainActivity) }

                Surface(Modifier.fillMaxSize(), color = Ink) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Header(diag)
                        Spacer(Modifier.height(18.dp))

                        if (mode == Mode.REQUEST) {
                            OutlinedTextField(
                                value = amount,
                                onValueChange = { amount = it },
                                label = { Text("Amount") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ModeButton("Request", mode == Mode.REQUEST, Modifier.weight(1f)) {
                                mode = if (mode == Mode.REQUEST) Mode.IDLE else Mode.REQUEST
                            }
                            ModeButton("Pay", mode == Mode.PAY, Modifier.weight(1f)) {
                                mode = if (mode == Mode.PAY) Mode.IDLE else Mode.PAY
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            when (mode) {
                                Mode.REQUEST -> "Serving a request. Hold the other phone against the back of this one."
                                Mode.PAY -> "Hold this phone against the one requesting."
                                Mode.IDLE -> "Pick a side. One requests, one pays."
                            },
                            color = Dim, fontSize = 13.sp,
                        )

                        Spacer(Modifier.height(18.dp))
                        Text("EXCHANGE", color = Dim, fontSize = 10.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(6.dp))
                        LogView(log)
                    }
                }

                incoming?.let { req ->
                    ConfirmSheet(
                        req = req,
                        onPay = {
                            val ok = respond?.invoke(
                                PayAck(payer = myAddress, nonce = req.nonce)
                            ) ?: false
                            push(if (ok) "✓ ack delivered" else "✗ ack failed")
                            incoming = null; respond = null
                        },
                        onCancel = { push("— declined"); incoming = null; respond = null },
                    )
                }
            }
        }
    }

    private fun push(line: String) {
        log.add(line)
        if (log.size > 200) log.removeAt(0)
    }

    /** Single place that puts the radio into whatever the current mode requires. */
    private fun applyMode(amount: String = lastAmount) {
        lastAmount = amount
        if (mode == Mode.PAY) reader.start() else reader.stop()
        TapHostApduService.pending.value =
            if (mode == Mode.REQUEST) PayRequest(
                recipient = myAddress,
                amount = amount,
                token = "USDC",
                label = android.os.Build.MODEL,
                nonce = PayRequest.newNonce(),
            ) else null
    }

    private var lastAmount = "2.50"

    override fun onResume() {
        super.onResume()
        // Re-arm. The platform dropped reader mode while we were away.
        if (mode != Mode.IDLE) {
            push("resumed — re-arming ${mode.name.lowercase()}")
            applyMode()
        }
    }

    override fun onPause() {
        super.onPause()
        // Leaving the app must not leave a live payment request being served, but keep `mode`
        // so onResume can put it back.
        reader.stop()
        TapHostApduService.pending.value = null
    }

    private enum class Mode { IDLE, REQUEST, PAY }
}

private val Ink = Color(0xFF0B0D10)
private val Panel = Color(0xFF14181E)
private val Line = Color(0xFF262E38)
private val Fg = Color(0xFFE8EDF3)
private val Dim = Color(0xFF8A96A4)
private val Accent = Color(0xFF14F195)   // Solana green
private val Warn = Color(0xFFE5534B)

@Composable
private fun Header(d: Diagnostics) {
    var open by remember { mutableStateOf(d.blocker != null) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TAP", color = Fg, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { open = !open }) {
                Text(if (open) "hide" else "check", color = Dim, fontSize = 12.sp)
            }
        }
        Text(
            d.blocker ?: "Ready · ${d.model}",
            color = if (d.blocker != null) Warn else Accent,
            fontSize = 12.sp,
        )
        if (open) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth()
                    .background(Panel, RoundedCornerShape(8.dp))
                    .border(1.dp, Line, RoundedCornerShape(8.dp))
                    .padding(12.dp)
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
private fun ModeButton(label: String, active: Boolean, m: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = m.height(52.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Accent else Panel,
            contentColor = if (active) Ink else Fg,
        ),
    ) { Text(label, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun LogView(log: List<String>) {
    val state = rememberLazyListState()
    LaunchedEffect(log.size) { if (log.isNotEmpty()) state.animateScrollToItem(log.size - 1) }
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize()
            .background(Panel, RoundedCornerShape(8.dp))
            .border(1.dp, Line, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        items(log) { line ->
            Text(
                line,
                color = when {
                    line.startsWith("✓") -> Accent
                    line.startsWith("✗") -> Warn
                    else -> Dim
                },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun ConfirmSheet(req: PayRequest, onPay: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Panel,
        title = { Text("Pay ${req.label}?", color = Fg) },
        text = {
            Column {
                Text("${req.amount} ${req.token}", color = Accent,
                    fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("to ${req.recipient.take(8)}…${req.recipient.takeLast(6)}",
                    color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                if (req.genesis != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("✓ Verified Seeker", color = Accent, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onPay) { Text("Pay", color = Accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = Dim) } },
    )
}
