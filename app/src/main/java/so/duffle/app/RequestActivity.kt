package so.duffle.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Asking to be paid, with nobody on the other side yet.
 *
 * Until now a request could only be made AFTER two phones had touched, because the app needed
 * the other person's address to send it to. This screen needs nobody: type an amount, and the
 * phone holds the request out two ways at once —
 *
 *  - **a QR code**, a plain Solana Pay request any wallet can scan from across a table, and
 *  - **NFC**, the same request served by [TapHostApduService] to a phone held against this one.
 *
 * Both carry one reference, so whichever way it is taken there is one payment to look for, and
 * the screen turns to "Received" when the chain shows it. Nothing here moves money: this phone
 * only asks and watches.
 *
 * The NFC side is served ONCE, by the service's own rule — a reference that leaves on two taps
 * could be paid by two people. After a tap takes it, the code is put away and the screen waits
 * for that person to approve.
 */
class RequestActivity : ComponentActivity() {

    private sealed interface Step {
        data object Amount : Step
        /** Held out and waiting. [taken] once a phone has read it over NFC. */
        data class Showing(val request: PayRequest, val taken: Boolean = false) : Step
        data class Received(val request: PayRequest, val signature: String) : Step
    }

    private var step by mutableStateOf<Step>(Step.Amount)
    private var amount by mutableStateOf("")
    private var token by mutableStateOf(SOL)
    private var problem by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val me = Wallet.addressOf(this)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = D.Blue, background = D.White, surface = D.White, onSurface = D.Black)) {
                Column(
                    Modifier.fillMaxSize().background(D.White).statusBarsPadding().navigationBarsPadding()
                        .padding(horizontal = Space.gutter, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BackArrow({ finish() })
                        Text("Request", color = D.Black, style = Type.title)
                    }
                    when (val s = step) {
                        Step.Amount -> if (me == null) NoWallet() else AmountStep(me)
                        is Step.Showing -> ShowingStep(s)
                        is Step.Received -> ReceivedStep(s)
                    }
                }
                // The chain is the only thing that can say it was paid, by tap or by scan.
                (step as? Step.Showing)?.let { s -> LaunchedEffect(s.request.reference) { watch(s.request) } }
            }
        }
        // A phone took it over NFC: put the code away, so nobody else scans a request that is
        // already in somebody's hands.
        lifecycleScope.launch {
            TapHostApduService.served.collect { served ->
                val s = step as? Step.Showing ?: return@collect
                if (served.reference == s.request.reference) step = s.copy(taken = true)
            }
        }
    }

    private fun ask(me: String) {
        val units = PayRequest.baseUnits(amount.trim(), token.decimals)
        if (units == null || units <= 0) {
            problem = "Enter an amount"
            return
        }
        val request = PayRequest(
            recipient = me,
            amount = amount.trim(),
            token = token.mint ?: "SOL",
            label = Identity.label(this),
            reference = PayRequest.newReference(),
            genesis = Identity.card(this)?.genesis,
            decimals = token.decimals,
            symbol = token.symbol,
            brand = Brand.mine(),
        )
        // Written down before it is shown, so a closed screen does not lose a request somebody
        // may already have scanned: the app's own watch list picks it up from here.
        Watch(this).add(Watch.Entry(
            reference = request.reference, recipient = me, peer = null, amount = request.amount,
            createdAt = System.currentTimeMillis(),
            token = request.token, decimals = request.decimals, symbol = request.symbol,
        ))
        TapHostApduService.pending.value = request
        lifecycleScope.launch {
            // So the server watches too and can push when it lands. No origin is claimed: this
            // request may be paid by a scan, and only a real tap may call itself one.
            ServerClient.register(
                request.reference, me, request.amount, request.label, genesis = request.genesis,
                token = request.token, decimals = request.decimals, symbol = request.symbol,
            )
        }
        Log.i(TAG, "asking ${request.amount} ${request.symbol} ref ${request.reference.take(8)}…")
        step = Step.Showing(request)
    }

    private suspend fun watch(request: PayRequest) {
        val units = request.units() ?: return
        while (step is Step.Showing) {
            val found = withContext(Dispatchers.IO) {
                Chain.findPayment(request.reference, request.recipient, units, request.mint)
            }
            if (found is Chain.Found.Yes) {
                Watch(this).markPaid(request.reference, found.signature)
                TapHostApduService.pending.value = null
                Haptics.success(this)
                step = Step.Received(request, found.signature)
                return
            }
            delay(2_000)
        }
    }

    /**
     * Takes the request back: off the radio, marked withdrawn on the watch list, and the server
     * told, so a phone that already took it sees "withdrawn" instead of a payable request.
     * Leaving with Back does none of this. The request then stays watched, because somebody may
     * already have scanned it.
     */
    private fun cancel(request: PayRequest) {
        TapHostApduService.pending.value = null
        Watch(this).markWithdrawn(request.reference)
        lifecycleScope.launch { ServerClient.withdraw(request.reference) }
        Log.i(TAG, "withdrew ${request.reference.take(8)}…")
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // The radio stops offering it the moment the screen is gone. The request itself stays on
        // the watch list, so a payment already on its way is still caught.
        val live = TapHostApduService.pending.value
        val mine = (step as? Step.Showing)?.request
        if (live != null && mine != null && live.reference == mine.reference) TapHostApduService.pending.value = null
    }

    // ------------------------------------------------------------------------------- screens

    @Composable
    private fun NoWallet() {
        Spacer(Modifier.height(Space.section))
        Text("Connect a wallet first", color = D.Black, style = Type.heading)
        Spacer(Modifier.height(Space.tight))
        Text("A request has to name the wallet it pays. Open AirPay and connect one.", color = D.Quiet, style = Type.body)
    }

    @Composable
    private fun AmountStep(me: String) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(Space.section))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(SOL, USDC).forEach { t ->
                    val on = token.symbol == t.symbol
                    val shape = RoundedCornerShape(999.dp)
                    Text(
                        t.symbol, color = if (on) D.White else D.Black, style = Type.body,
                        modifier = Modifier.clip(shape).background(if (on) D.Black else D.White)
                            .border(1.5.dp, if (on) D.Black else D.Line, shape)
                            .clickable { token = t; amount = ""; problem = null }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                amount.ifEmpty { "0" }, color = if (amount.isEmpty()) D.Quiet else D.Black,
                fontSize = 64.sp, style = Type.title.figure(), maxLines = 1,
            )
            Text(token.symbol, color = D.Quiet, style = Type.body)
            problem?.let {
                Spacer(Modifier.height(Space.tight))
                Text(it, color = D.DangerInk, style = Type.caption)
            }
            Spacer(Modifier.weight(1f))
            Keys { key ->
                Haptics.tick(this@RequestActivity)
                problem = null
                amount = typedAmount(amount, key, token.decimals)
            }
            Spacer(Modifier.height(Space.row))
            PrimaryButton("Request", { ask(me) }, Modifier.fillMaxWidth(), enabled = amount.isNotBlank())
        }
    }

    @Composable
    private fun ShowingStep(s: Step.Showing) {
        val r = s.request
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(Space.section))
            Text("${r.amount} ${r.symbol}", color = D.Black, fontSize = 44.sp, style = Type.title.figure())
            Spacer(Modifier.height(Space.tight))
            // Who it pays, as this wallet is known: its on-chain name, else the name typed in the
            // app, else the start of its address. Never an empty "to".
            val who = Identity.domain(this@RequestActivity, r.recipient)
                ?: r.label.ifBlank { null }
                ?: "${r.recipient.take(4)}…${r.recipient.takeLast(4)}"
            Text("to $who", color = D.Quiet, style = Type.body)
            Spacer(Modifier.weight(1f))
            if (s.taken) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(D.Blue), contentAlignment = Alignment.Center) {
                    HiIcon(Hi.Nfc, D.White, 44.dp)
                }
                Spacer(Modifier.height(Space.lead))
                Text("They have it", color = D.Black, style = Type.title)
                Spacer(Modifier.height(Space.tight))
                Text(
                    "Waiting for them to approve in their wallet.",
                    color = D.Quiet, style = Type.body, textAlign = TextAlign.Center,
                )
            } else {
                val shape = RoundedCornerShape(24.dp)
                Box(Modifier.clip(shape).border(1.5.dp, D.Line, shape).padding(18.dp)) {
                    QrCode(solanaPayUrl(r), Modifier.size(236.dp))
                }
                Spacer(Modifier.height(Space.lead))
                Text("Hold their phone near yours,\nor let them scan", color = D.Black, style = Type.heading, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Space.tight))
                Text("Works with AirPay by tap, and with any Solana wallet by scan.", color = D.Quiet, style = Type.caption, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.weight(1f))
            TextLink("Cancel request", { cancel(r) })
        }
    }

    @Composable
    private fun ReceivedStep(s: Step.Received) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(96.dp).clip(CircleShape).background(D.Success), contentAlignment = Alignment.Center) {
                HiIcon(Hi.Tick, D.White, 48.dp)
            }
            Spacer(Modifier.height(Space.lead))
            Text("Received", color = D.Black, style = Type.title)
            Spacer(Modifier.height(Space.tight))
            Text("${s.request.amount} ${s.request.symbol}", color = D.Black, fontSize = 44.sp, style = Type.title.figure())
            Spacer(Modifier.weight(1f))
            PrimaryButton("Done", { finish() }, Modifier.fillMaxWidth())
        }
    }

    /** Four rows of keys: the digits, a decimal point and a backspace. */
    @Composable
    private fun Keys(onKey: (Char) -> Unit) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("123", "456", "789", ".0<").forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { k ->
                        Box(
                            Modifier.weight(1f).heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).clickable { onKey(k) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (k == '<') HiIcon(Hi.Backspace, D.Black, 26.dp, contentDescription = "Delete")
                            else Text(k.toString(), color = D.Black, style = Type.title)
                        }
                    }
                }
            }
        }
    }

    /** What may be asked for here. [mint] is null for SOL. */
    private class Asset(val symbol: String, val mint: String?, val decimals: Int)

    companion object {
        private const val TAG = "AirPayRequest"
        private val SOL = Asset("SOL", null, 9)
        private val USDC = Asset("USDC", Shop.USDC_MAINNET, 6)

        fun intent(context: Context) = Intent(context, RequestActivity::class.java)

        /**
         * The request as a Solana Pay transfer URL, which is what the code encodes.
         *
         * The reference rides along, so a wallet that honours it produces a payment this phone can
         * find; the label is what that wallet shows as who is asking.
         */
        internal fun solanaPayUrl(r: PayRequest): String = buildString {
            append("solana:").append(r.recipient)
            append("?amount=").append(r.amount)
            if (r.token != "SOL") append("&spl-token=").append(r.token)
            append("&reference=").append(r.reference)
            if (r.label.isNotBlank()) append("&label=").append(URLEncoder.encode(r.label, "UTF-8").replace("+", "%20"))
        }

        /** One key pressed on the amount: digits, one decimal point, no more places than the token has. */
        internal fun typedAmount(current: String, key: Char, decimals: Int): String = when {
            key == '<' -> current.dropLast(1)
            key == '.' -> if ('.' in current) current else if (current.isEmpty()) "0." else "$current."
            current == "0" -> key.toString()
            '.' in current && current.substringAfter('.').length >= decimals -> current
            current.length >= 12 -> current
            else -> current + key
        }
    }
}
