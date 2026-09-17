package com.tapsol.tap

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The popup that appears when two phones touch — over whatever is on screen, with Tap closed.
 *
 * It opens three ways: Android launches it after reading the other phone's NDEF tag; our HCE
 * service opens it (or notifies, if Android refuses) after the other phone said HELLO; or the
 * user taps a notification after unlocking. In every case it shows who the other person is and
 * offers the two things you can do with someone you just touched phones with: pay them, or ask
 * them to pay you.
 *
 * States are kept deliberately distinct, because they mean different things to the person:
 * working, waiting for the other person, success (confirmed on-chain), error (nothing was sent),
 * and failed (a transaction was sent and the chain refused it).
 */
class TapPopupActivity : ComponentActivity() {

    sealed interface Phase {
        data object Ready : Phase
        data class Working(val text: String) : Phase
        data class Waiting(val amount: String, val reference: String) : Phase
        data class Success(val title: String, val detail: String, val signature: String?) : Phase
        data class Error(val title: String, val detail: String) : Phase
        data class Failed(val title: String, val detail: String, val signature: String?) : Phase
    }

    private lateinit var wallet: Wallet
    private var peer by mutableStateOf<TapPayload?>(null)
    private var phase by mutableStateOf<Phase>(Phase.Ready)
    private var address by mutableStateOf<String?>(null)
    private var shownAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wallet = Wallet(this)
        address = wallet.address
        if (!handle(intent)) {
            finish()
            return
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Ink, surface = Panel)) {
                val p = peer ?: return@MaterialTheme
                PopupScreen(
                    peer = p,
                    phase = phase,
                    connected = address != null,
                    history = Contacts(this).all().firstOrNull { it.address == p.address },
                    onDismiss = { if (phase !is Phase.Working) finish() },
                    onConnect = ::connect,
                    onPay = ::pay,
                    onRequest = ::request,
                    onRetry = { phase = Phase.Ready },
                    onExplorer = { sig -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Chain.explorerUrl(sig)))) } },
                )
                // The requester's half: watch for their payment while the popup is open.
                LaunchedEffect(phase) {
                    val w = phase as? Phase.Waiting ?: return@LaunchedEffect
                    watchForPayment(w)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Never swap the person under a payment in progress.
        if (phase is Phase.Working || phase is Phase.Waiting) return
        handle(intent)
    }

    /** Reads whatever launched us. False if there is nothing valid to show. */
    private fun handle(intent: Intent): Boolean {
        val payload = when (intent.action) {
            NfcAdapter.ACTION_NDEF_DISCOVERED -> fromNdef(intent)
            else -> intent.getByteArrayExtra(EXTRA_PAYLOAD)?.let { TapPayload.parse(it) }
        } ?: return false

        // The same person can arrive twice in a moment — our NDEF read of them and their HELLO
        // to us — which should be one popup, not two.
        if (peer?.address == payload.address && System.currentTimeMillis() - shownAt < 4_000) return true
        if (payload.address == address) return false // our own phone, somehow

        peer = payload
        phase = Phase.Ready
        shownAt = System.currentTimeMillis()
        Log.i(TAG, "popup for ${payload.label} via ${intent.getStringExtra(EXTRA_FROM) ?: intent.action}")
        Push.cancel(this, payload.address)
        Haptics.contact(this)
        Contacts(this).record(payload.address, payload.label, payload.genesis != null)

        if (payload is TapPayload.Request) {
            Push.cancel(this, payload.request.reference)
            // Saved before anything else, so "Later" — or closing the popup — never loses it.
            Inbox(this).add(payload.request)
            lifecycleScope.launch {
                ServerClient.register(payload.request.reference, payload.request.recipient, payload.request.amount, payload.request.label)
            }
        }

        // Launched by Android's NDEF read: the other phone still has no idea who we are. Say
        // HELLO while the phones may still be touching; failing that, relay through the server.
        if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED) introduce(intent, payload)
        return true
    }

    private fun fromNdef(intent: Intent): TapPayload? {
        val messages = IntentCompat.getParcelableArrayExtra(intent, NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
            ?: return null
        for (m in messages) {
            for (r in (m as NdefMessage).records) {
                if (r.toMimeType() == Ndef.MIME) return TapPayload.parse(r.payload)
            }
        }
        return null
    }

    private fun introduce(intent: Intent, payload: TapPayload) {
        val me = Identity.card(this) ?: return
        val tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java)
        lifecycleScope.launch(Dispatchers.IO) {
            val introduced = tag != null &&
                (TapExchange.run(tag, me) as? TapExchange.Outcome.Got)?.introduced == true
            Log.i(TAG, "introduced over NFC: $introduced")
            if (!introduced) ServerClient.relayTap(me, payload.address)
        }
    }

    private fun connect() {
        lifecycleScope.launch {
            phase = Phase.Working("Opening your wallet…")
            when (val r = wallet.connect()) {
                is Wallet.Outcome.Ok -> {
                    address = r.address
                    phase = Phase.Ready
                    launch(Dispatchers.IO) {
                        Identity.rememberGenesis(this@TapPopupActivity, r.address,
                            runCatching { Genesis.mintFor(r.address) }.getOrNull())
                    }
                }
                is Wallet.Outcome.Failed -> phase = Phase.Error("Couldn't connect", r.why)
                Wallet.Outcome.Declined -> phase = Phase.Ready
            }
        }
    }

    private fun pay(amount: String) {
        val p = peer ?: return
        val payer = address ?: return connect()
        val req = when (p) {
            is TapPayload.Request -> p.request
            is TapPayload.Card -> PayRequest(p.card.address, amount.trim(), "SOL", p.card.label, PayRequest.newReference(), p.card.genesis)
        }
        lifecycleScope.launch {
            phase = Phase.Working("Getting ready…")
            // Registered before paying so the server is already watching and can tell the other
            // phone the instant it lands.
            launch { ServerClient.register(req.reference, req.recipient, req.amount, Identity.label(this@TapPopupActivity)) }
            val result = try {
                Payments.pay(wallet, payer, req, stage = { phase = Phase.Working(it) }, log = { Log.i(TAG, it) })
            } catch (e: Exception) {
                PayResult.Error("Something went wrong", "Nothing was sent. ${e.message ?: ""}".trim())
            }
            phase = when (result) {
                is PayResult.Sent -> {
                    if (p is TapPayload.Request) Inbox(this@TapPopupActivity).markPaid(req.reference, result.signature)
                    Contacts(this@TapPopupActivity).record(req.recipient, req.label, req.genesis != null, paid = req.amount.toDoubleOrNull() ?: 0.0)
                    Haptics.success(this@TapPopupActivity)
                    Phase.Success("Sent", "${req.amount} SOL to ${req.label}", result.signature)
                }
                is PayResult.AlreadyPaid -> {
                    if (p is TapPayload.Request) Inbox(this@TapPopupActivity).markPaid(req.reference, result.signature)
                    Phase.Success("Already paid", "This request was paid before — nothing new was sent.", result.signature)
                }
                is PayResult.Error -> { Haptics.failure(this@TapPopupActivity); Phase.Error(result.title, result.detail) }
                is PayResult.Failed -> { Haptics.failure(this@TapPopupActivity); Phase.Failed(result.title, result.detail, result.signature) }
            }
        }
    }

    private fun request(amount: String) {
        val p = peer ?: return
        val me = address ?: return connect()
        val value = amount.trim()
        if (PayRequest(me, value, "SOL", "", "").lamports() == null) {
            phase = Phase.Error("Enter an amount", "Amounts can have up to 9 decimal places.")
            return
        }
        if (!ServerClient.enabled) {
            // The phones have parted by now, so the request has no radio to travel over.
            phase = Phase.Error("Requests need the Tap server", "Paying works without it. To request after a tap, run the server — see server/README.md.")
            return
        }
        lifecycleScope.launch {
            phase = Phase.Working("Sending request…")
            val reference = PayRequest.newReference()
            val sent = ServerClient.register(
                reference, me, value, Identity.label(this@TapPopupActivity),
                payer = p.address, genesis = Identity.card(this@TapPopupActivity)?.genesis,
            )
            if (!sent) {
                Haptics.failure(this@TapPopupActivity)
                phase = Phase.Error("Couldn't send the request", "Check your connection and try again.")
                return@launch
            }
            Watch(this@TapPopupActivity).add(Watch.Entry(reference, me, value, System.currentTimeMillis()))
            phase = Phase.Waiting(value, reference)
        }
    }

    private suspend fun watchForPayment(w: Phase.Waiting) {
        val me = address ?: return
        val lamports = PayRequest(me, w.amount, "SOL", "", w.reference).lamports() ?: return
        while (phase == w) {
            val found = withContext(Dispatchers.IO) { Chain.findPayment(w.reference, me, lamports) }
            if (found is Chain.Found.Yes) {
                if (Watch(this).markPaid(w.reference, found.signature)) {
                    Haptics.success(this)
                    phase = Phase.Success("Received", "${w.amount} SOL from ${peer?.label ?: "them"}", found.signature)
                }
                return
            }
            delay(2_000)
        }
    }

    companion object {
        private const val TAG = "TapPopup"
        private const val EXTRA_PAYLOAD = "payload"
        private const val EXTRA_FROM = "from"

        fun intent(context: Context, payload: TapPayload, cameFrom: String): Intent =
            Intent(context, TapPopupActivity::class.java)
                .putExtra(EXTRA_PAYLOAD, payload.toBytes())
                .putExtra(EXTRA_FROM, cameFrom)
    }
}

// ---------------------------------------------------------------------------------------- UI

@Composable
private fun PopupScreen(
    peer: TapPayload,
    phase: TapPopupActivity.Phase,
    connected: Boolean,
    history: Contact?,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onPay: (String) -> Unit,
    onRequest: (String) -> Unit,
    onRetry: () -> Unit,
    onExplorer: (String) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = .55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .imePadding()
                .background(Panel, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .border(1.dp, Line, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                // Swallow taps so touching the card never dismisses it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 4.dp).background(Line, CircleShape))
            Spacer(Modifier.height(16.dp))
            PeerHeader(peer, history)
            Spacer(Modifier.height(20.dp))
            AnimatedContent(targetState = phase, label = "phase") { ph ->
                Column {
                    when (ph) {
                        TapPopupActivity.Phase.Ready -> ReadyBody(peer, connected, onConnect, onPay, onRequest, onDismiss)
                        is TapPopupActivity.Phase.Working -> WorkingBody(ph.text, null)
                        is TapPopupActivity.Phase.Waiting -> {
                            WorkingBody("Waiting for ${peer.label} to pay ${ph.amount} SOL", "You can close this — it stays in Requests.")
                            Spacer(Modifier.height(16.dp))
                            SecondaryButton("Close", onDismiss)
                        }
                        is TapPopupActivity.Phase.Success -> ResultBody("✓", Accent, ph.title, ph.detail, ph.signature, onExplorer) {
                            PrimaryButton("Done", onDismiss)
                        }
                        is TapPopupActivity.Phase.Error -> ResultBody("!", Warn, ph.title, ph.detail, null, onExplorer) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                SecondaryButton("Close", onDismiss, Modifier.weight(1f))
                                PrimaryButton("Try again", onRetry, Modifier.weight(1f))
                            }
                        }
                        is TapPopupActivity.Phase.Failed -> ResultBody("✕", Warn, ph.title, ph.detail, ph.signature, onExplorer) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                SecondaryButton("Close", onDismiss, Modifier.weight(1f))
                                PrimaryButton("Try again", onRetry, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PeerHeader(peer: TapPayload, history: Contact?) {
    val seeker = peer.genesis != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(52.dp)
                .background(if (seeker) Accent.copy(alpha = .16f) else Line, CircleShape)
                .border(1.dp, if (seeker) Accent.copy(alpha = .6f) else Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(peer.label.take(1).uppercase().ifBlank { "?" }, color = if (seeker) Accent else Fg,
                fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(peer.label.ifBlank { "Unknown phone" }, color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (seeker) {
                    Spacer(Modifier.width(8.dp))
                    Text("✓ VERIFIED SEEKER", color = Accent, fontSize = 9.sp, letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Accent.copy(alpha = .12f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            Text("${peer.address.take(6)}…${peer.address.takeLast(6)}", color = Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            Text(
                when {
                    history == null || history.taps <= 1 -> "First time you've tapped"
                    history.totalPaid > 0 -> "Tapped ${history.taps} times · you've paid ${"%.3f".format(history.totalPaid)} SOL"
                    else -> "Tapped ${history.taps} times"
                },
                color = Dim, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ReadyBody(
    peer: TapPayload,
    connected: Boolean,
    onConnect: () -> Unit,
    onPay: (String) -> Unit,
    onRequest: (String) -> Unit,
    onLater: () -> Unit,
) {
    if (!connected) {
        Text("Connect a wallet to pay or request.", color = Dim, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Connect wallet", onConnect)
        return
    }
    if (peer is TapPayload.Request) {
        Text("REQUESTS", color = Dim, fontSize = 10.sp, letterSpacing = 2.sp)
        Text("${peer.request.amount} SOL", color = Fg, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Pay ${peer.request.amount} SOL", { onPay(peer.request.amount) })
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Later", onLater)
        return
    }

    var amount by rememberSaveable { mutableStateOf("") }
    val lamports = PayRequest(peer.address, amount, "SOL", "", "").lamports()
    val problem = when {
        amount.isBlank() -> null
        amount.toDoubleOrNull() == null -> "That isn't a number"
        lamports == null -> "Up to 9 decimal places"
        (amount.toDoubleOrNull() ?: 0.0) > PayRequest.MAX_AMOUNT -> "Too large for a tap"
        else -> null
    }
    val valid = lamports != null && problem == null
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    OutlinedTextField(
        value = amount,
        onValueChange = { v -> if (v.length <= 12 && v.all { it.isDigit() || it == '.' }) amount = v },
        placeholder = { Text("0.00", color = Dim, fontSize = 32.sp) },
        suffix = { Text("SOL", color = Dim, fontSize = 18.sp) },
        textStyle = LocalTextStyle.current.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Fg),
        singleLine = true,
        isError = problem != null,
        supportingText = { Text(problem ?: "devnet", color = if (problem != null) Warn else Dim) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton("Request", { onRequest(amount) }, Modifier.weight(1f), enabled = valid)
        PrimaryButton("Pay", { onPay(amount) }, Modifier.weight(1f), enabled = valid)
    }
}

@Composable
private fun WorkingBody(text: String, note: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text(text, color = Fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (note != null) {
            Spacer(Modifier.height(4.dp))
            Text(note, color = Dim, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ResultBody(
    mark: String, tone: Color, title: String, detail: String, signature: String?,
    onExplorer: (String) -> Unit, actions: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).background(tone.copy(alpha = .15f), CircleShape).border(2.dp, tone, CircleShape),
            contentAlignment = Alignment.Center) {
            Text(mark, color = tone, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = Fg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(detail, color = Dim, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (signature != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { onExplorer(signature) }) {
                Text("${signature.take(8)}…${signature.takeLast(8)} · View on explorer", color = tone, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(14.dp))
        actions()
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), enabled: Boolean = true) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink,
            disabledContainerColor = Line, disabledContentColor = Dim),
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.height(52.dp), shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Fg, disabledContentColor = Dim),
    ) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
}
