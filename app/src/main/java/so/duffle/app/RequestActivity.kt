package so.duffle.app

import androidx.compose.ui.graphics.Color
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
 * The request stays on offer, on the code and on the radio alike, until it is paid, cancelled or
 * the screen is left. A tap only proves the request left this phone, not that the other phone
 * kept it, so a tap is shown as "phone detected" and nothing is put away: if their read failed
 * they tap again and get the same request, not a bare card. One reference still means one
 * payment to look for, and the code was already showing it to anyone who could scan it.
 */
class RequestActivity : ComponentActivity() {

    private sealed interface Step {
        data object Amount : Step
        /** Held out and waiting. [taken] once a phone has read it over NFC; it stays held out. */
        data class Showing(val request: PayRequest, val taken: Boolean = false) : Step
        data class Received(val request: PayRequest, val signature: String) : Step
    }

    private var step by mutableStateOf<Step>(Step.Amount)
    private var amount by mutableStateOf("")
    private var token by mutableStateOf(SOL)
    private var problem by mutableStateOf<String?>(null)

    /** What became of a payment handed to this phone to send. [bad] when it did not go out. */
    private data class Hand(val text: String, val bad: Boolean)
    private var hand by mutableStateOf<Hand?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val me = Wallet.addressOf(this)
        savedInstanceState?.let { restore(it) }
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = true
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = D.Blue, background = D.White, surface = D.White, onSurface = D.Black)) {
                // A sheet, like the tap sheet it follows: the same blurred page behind, the same
                // rounded top. It was a full white screen, which read as leaving for the app.
                val shot = remember { Backdrop.showing?.asImageBitmap() }
                androidx.activity.compose.BackHandler { leave() }
                val rise = remember { Animatable(1f) }
                LaunchedEffect(Unit) { rise.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = 300f)) }
                Box(
                    Modifier.fillMaxSize()
                        // Outside the sheet closes it only while nothing is being asked for. A
                        // live request is ended by its own Cancel, never by a stray touch.
                        .clickable(remember { MutableInteractionSource() }, indication = null) { if (step == Step.Amount) finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (shot != null) {
                        Image(
                            shot, contentDescription = null, contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize().blur(14.dp)
                                .drawWithContent { drawContent(); drawRect(Color.Black.copy(alpha = .3f)) },
                        )
                    } else {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .5f)))
                    }
                    Column(
                        Modifier.fillMaxWidth()
                            .graphicsLayer { translationY = size.height * rise.value }
                            .background(D.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .clickable(remember { MutableInteractionSource() }, indication = null) {}
                            .navigationBarsPadding()
                            .animateContentSize(spring(dampingRatio = 0.9f, stiffness = 380f))
                            .padding(horizontal = Space.gutter).padding(top = 10.dp, bottom = 12.dp),
                    ) {
                        Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(D.Line, CircleShape))
                        Spacer(Modifier.height(Space.inline))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BackArrow({ leave() })
                            Text("Request", color = D.Black, style = Type.title)
                        }
                        when (val s = step) {
                            Step.Amount -> if (me == null) NoWallet() else AmountStep(me)
                            is Step.Showing -> ShowingStep(s)
                            is Step.Received -> ReceivedStep(s)
                        }
                    }
                    // The app's own tap light: along the top while the radio is offering the
                    // request, running down the sides when a phone reads it.
                    (step as? Step.Showing)?.let { s ->
                        TapGlow(waiting = !s.taken, touched = s.taken, modifier = Modifier.fillMaxSize())
                    }
                }
                // The chain is the only thing that can say it was paid, by tap or by scan.
                (step as? Step.Showing)?.let { s -> LaunchedEffect(s.request.reference) { watch(s.request) } }
                (step as? Step.Showing)?.let { s -> LaunchedEffect(s.request.reference) { keepFresh(s.request) } }
            }
        }
        // A phone read it over NFC. That is said on screen and nothing else changes: the request
        // is still on offer, because the read may not have survived on their side.
        lifecycleScope.launch {
            TapHostApduService.picked.collect { picked ->
                val s = step as? Step.Showing ?: return@collect
                if (picked.reference == s.request.reference) step = s.copy(taken = true)
                // A new tap is a new try: what went wrong with the last one is no longer the news.
                if (hand?.bad == true) hand = null
            }
        }
        // A phone with no connection signed the payment and handed it back for us to send.
        lifecycleScope.launch {
            TapHostApduService.handed.collect { tx ->
                (step as? Step.Showing)?.let { send(it.request, tx) }
            }
        }
    }

    /**
     * The request on screen is written into the saved state, so a rebuilt screen (a config
     * change, or the process killed behind another app) comes back to the same code and the
     * same reference instead of an empty amount with a request still out there.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_AMOUNT, amount)
        outState.putString(KEY_TOKEN, token.symbol)
        when (val s = step) {
            Step.Amount -> Unit
            is Step.Showing -> {
                outState.putByteArray(KEY_REQUEST, s.request.toBytes())
                outState.putBoolean(KEY_TAKEN, s.taken)
            }
            is Step.Received -> {
                outState.putByteArray(KEY_REQUEST, s.request.toBytes())
                outState.putString(KEY_SIGNATURE, s.signature)
            }
        }
    }

    private fun restore(saved: Bundle) {
        amount = saved.getString(KEY_AMOUNT).orEmpty()
        token = listOf(SOL, USDC).firstOrNull { it.symbol == saved.getString(KEY_TOKEN) } ?: SOL
        val request = saved.getByteArray(KEY_REQUEST)?.let { PayRequest.fromBytes(it) } ?: return
        val signature = saved.getString(KEY_SIGNATURE)
        if (signature != null) {
            step = Step.Received(request, signature)
        } else {
            // The old screen took it off the radio as it went, and a killed process lost it.
            offer(request)
            step = Step.Showing(request, saved.getBoolean(KEY_TAKEN))
        }
    }

    /** Puts the request on the radio and keeps it there through taps. See [TapHostApduService.held]. */
    private fun offer(request: PayRequest) {
        TapHostApduService.held = request.reference
        TapHostApduService.pending.value = request
    }

    /**
     * Takes the request off the radio. Only if it is still this one: a later request, from here
     * or from the home screen, must not be withdrawn by a screen that is on its way out.
     */
    private fun release(request: PayRequest) {
        if (TapHostApduService.held == request.reference) TapHostApduService.held = null
        if (TapHostApduService.pending.value?.reference == request.reference) TapHostApduService.pending.value = null
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
        hand = null
        offer(request)
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

    /**
     * Keeps a recent blockhash on the request the radio serves, for a payer with no connection.
     *
     * That phone cannot fetch one, and a transaction cannot be built without one, so ours rides
     * along. A blockhash is good for roughly a minute, which is why it is replaced every few
     * seconds: the payer's minute should start at the tap, not when this screen opened. The
     * reference never changes, the code on screen does not carry the hash, and a payer who is
     * online ignores it. When this phone cannot reach Solana either, none is served: a payment
     * handed back could not be sent from here anyway.
     */
    private suspend fun keepFresh(request: PayRequest) {
        while ((step as? Step.Showing)?.request?.reference == request.reference) {
            val hash = withContext(Dispatchers.IO) { Chain.latestBlockhash() }
            // Only while it is still ours to serve: release() may have run during the fetch.
            if (TapHostApduService.held == request.reference &&
                TapHostApduService.pending.value?.reference == request.reference) {
                TapHostApduService.pending.value = request.copy(blockhash = hash)
            }
            delay(5_000)
        }
    }

    /**
     * Sends a payment the payer signed with no connection and handed over on a second tap.
     *
     * The bytes came over the air, so they are checked first ([OfflineTx.check]), and nothing
     * here ever says Received: only [watch] does, when the chain shows the money. Whatever goes
     * wrong, the request stays up so they can tap again.
     */
    private suspend fun send(request: PayRequest, tx: ByteArray) {
        val verdict = OfflineTx.check(tx, request)
        if (verdict is OfflineTx.Verdict.Refused) {
            Log.i(TAG, "handed ${tx.size}B refused: ${verdict.why}")
            Haptics.failure(this)
            hand = Hand("That payment was not for this request. Ask them to tap again.", bad = true)
            return
        }
        hand = Hand("Sending their payment.", bad = false)
        val sent = withContext(Dispatchers.IO) { Chain.submit(tx) }
        Log.i(TAG, "handed ${tx.size}B submit: $sent")
        hand = when (sent) {
            is Chain.Submitted.Ok -> Hand("Sent. Waiting for it to arrive.", bad = false)
            Chain.Submitted.Offline -> Hand("This phone is offline, so it was not sent. Connect and ask them to tap again.", bad = true)
            is Chain.Submitted.Rejected -> when {
                // The same payment handed over twice. The first one is already on its way.
                sent.why.contains("already been processed", true) -> Hand("Sent. Waiting for it to arrive.", bad = false)
                sent.why.contains("blockhash", true) -> Hand("That took too long and expired. Ask them to tap again.", bad = true)
                else -> Hand("That payment was refused and nothing was sent. Ask them to tap again.", bad = true)
            }
        }
        if (hand?.bad == true) Haptics.failure(this)
    }

    private suspend fun watch(request: PayRequest) {
        val units = request.units() ?: return
        while (step is Step.Showing) {
            val found = withContext(Dispatchers.IO) {
                Chain.findPayment(request.reference, request.recipient, units, request.mint)
            }
            if (found is Chain.Found.Yes) {
                Watch(this).markPaid(request.reference, found.signature)
                release(request)
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
    /**
     * Back, from the sheet. Before anything has been asked for, that means back to the pay sheet
     * this came from, not out of AirPay altogether. With a request showing it ends the screen as
     * before, which takes the request off the radio.
     */
    private fun leave() {
        if (step == Step.Amount) {
            startActivity(TapPopupActivity.back(this))
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        } else finish()
    }

    private fun cancel(request: PayRequest) {
        release(request)
        Watch(this).markWithdrawn(request.reference)
        lifecycleScope.launch { ServerClient.withdraw(request.reference) }
        Log.i(TAG, "withdrew ${request.reference.take(8)}…")
        finish()
    }

    override fun onPause() {
        super.onPause()
        // Leaving for good. Done here as well as in onDestroy because Android may hold that back
        // for seconds after Back, and the radio should stop offering it as the screen goes.
        if (isFinishing) (step as? Step.Showing)?.let { release(it.request) }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The radio stops offering it once the screen is gone. The request itself stays on the
        // watch list, so a payment already on its way is still caught. A screen being rebuilt
        // puts it back from its saved state.
        (step as? Step.Showing)?.let { release(it.request) }
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
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(Space.row))
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
            Spacer(Modifier.height(Space.row))
            Text(
                amount.ifEmpty { "0" }, color = if (amount.isEmpty()) D.Quiet else D.Black,
                fontSize = 64.sp, style = Type.title.figure(), maxLines = 1,
            )
            Text(token.symbol, color = D.Quiet, style = Type.body)
            problem?.let {
                Spacer(Modifier.height(Space.tight))
                Text(it, color = D.DangerInk, style = Type.caption)
            }
            Spacer(Modifier.height(Space.row))
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
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(Space.inline))
            // Who it pays, as this wallet is known: its on-chain name, else the name typed in the
            // app, else the start of its address. Never an empty "to".
            val who = Identity.domain(this@RequestActivity, r.recipient)
                ?: r.label.ifBlank { null }
                ?: "${r.recipient.take(4)}…${r.recipient.takeLast(4)}"
            // Who is being paid leads, the code is the thing to act on, and the amount sits
            // under the code where the eye lands after it: the order a payer reads them in.
            Text("to $who", color = D.Quiet, style = Type.body)
            Spacer(Modifier.height(Space.inline))
            // The code stays up after a tap. A tap only shows the request left this phone; if
            // their read failed, this is still what they tap or scan.
            val shape = RoundedCornerShape(24.dp)
            Box(
                Modifier.then(if (s.taken) Modifier else Modifier.waitingPulse())
                    .clip(shape).border(1.5.dp, if (s.taken) D.Blue else D.Line, shape).padding(18.dp),
            ) {
                QrCode(solanaPayUrl(r), Modifier.size(220.dp))
            }
            Spacer(Modifier.height(Space.row))
            Text("${r.amount} ${r.symbol}", color = D.Black, fontSize = 44.sp, style = Type.title.figure())
            Spacer(Modifier.height(Space.tight))
            val h = hand
            if (h != null) {
                // A handed-over payment has more to say than "phone detected", and when it did
                // not go out this is the only place the person asking finds that out.
                Text(if (h.bad) "Not sent" else "Payment handed over", color = if (h.bad) D.DangerInk else D.Black, style = Type.heading)
                Spacer(Modifier.height(Space.tight))
                Text(h.text, color = D.Quiet, style = Type.body, textAlign = TextAlign.Center)
            } else if (s.taken) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HiIcon(Hi.Nfc, D.Blue, 24.dp)
                    Text("Phone detected", color = D.Black, style = Type.heading)
                }
                Spacer(Modifier.height(Space.tight))
                Text("Waiting for them to approve.", color = D.Quiet, style = Type.body, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Space.tight))
                Text("If it did not reach them, they can tap or scan again.", color = D.Quiet, style = Type.caption, textAlign = TextAlign.Center)
            } else {
                // Two words, because those are the two things the other person can do.
                Text("Scan or tap", color = D.Quiet, style = Type.body, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(Space.row))
            // Red, because it ends the request, but a soft red pill only as wide as its words:
            // it is the way out, not the thing to do next.
            Box(
                Modifier.clip(CircleShape).background(D.DangerInk.copy(alpha = 0.10f))
                    .clickable { cancel(r) }
                    .heightIn(min = 48.dp).padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Cancel request", color = D.DangerInk, style = Type.heading) }
            Spacer(Modifier.height(Space.tight))
        }
    }

    /**
     * A slow outline that leaves the code and fades: the request is live and waiting for someone.
     *
     * One blue at a falling opacity, drawn with the stroke's own alpha like the app's other
     * glows. The phase is read inside the draw block, so it redraws without recomposing the
     * screen. Nothing in the app reads the reduced motion setting yet, so this does not either.
     */
    @Composable
    private fun Modifier.waitingPulse(): Modifier {
        val loop = rememberInfiniteTransition(label = "waiting")
        val phase = loop.animateFloat(
            0f, 1f, infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart), label = "pulse",
        )
        return drawBehind {
            // Out quickly, then a long fade, and a rest before the next one: breathing, not radar.
            val t = FastOutSlowInEasing.transform((phase.value / 0.75f).coerceIn(0f, 1f))
            if (t >= 1f) return@drawBehind
            val grow = 22.dp.toPx() * t
            drawRoundRect(
                color = D.Blue,
                topLeft = Offset(-grow, -grow),
                size = Size(size.width + 2 * grow, size.height + 2 * grow),
                cornerRadius = CornerRadius(24.dp.toPx() + grow),
                style = Stroke(2.dp.toPx()),
                alpha = 0.45f * (1f - t),
            )
        }
    }

    /**
     * The payment landed. The moment, not just the fact: the disc springs in, a soft ring leaves
     * it, the tick is drawn across it, and the amount arrives with the tick. The buzz was played
     * where the chain answered, in [watch], so it is felt once and never again on a rebuild.
     */
    @Composable
    private fun ReceivedStep(s: Step.Received) {
        val pop = remember { Animatable(0f) }
        val ring = remember { Animatable(0f) }
        val draw = remember { Animatable(0f) }
        // A screen rebuilt after the tick has landed shows it landed, and does not replay it.
        var played by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (played) {
                pop.snapTo(1f); ring.snapTo(1f); draw.snapTo(1f)
                return@LaunchedEffect
            }
            launch { pop.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)) }
            launch { ring.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
            // The tick starts once there is a disc to draw it on.
            delay(160)
            draw.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
            played = true
        }
        val tick = remember { Path() }
        val drawn = remember { Path() }
        val measure = remember { PathMeasure() }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(Space.row))
            // Wider than the disc, so the ring has room to leave it.
            Canvas(Modifier.size(176.dp)) {
                val disc = 48.dp.toPx()
                val out = ring.value
                if (out > 0f && out < 1f) {
                    drawCircle(D.Success, radius = disc + (size.minDimension / 2f - disc) * out, alpha = 0.30f * (1f - out))
                }
                val scale = pop.value
                if (scale <= 0f) return@Canvas
                drawCircle(D.Success, radius = disc * scale)
                if (draw.value <= 0f) return@Canvas
                // The same tick as Hi.Tick, on its 24 unit grid, scaled to 48dp and to the spring.
                val unit = 48.dp.toPx() / 24f * scale
                val left = center.x - 12f * unit
                val top = center.y - 12f * unit
                tick.rewind()
                tick.moveTo(left + 5f * unit, top + 14f * unit)
                tick.lineTo(left + 8.5f * unit, top + 17.5f * unit)
                tick.lineTo(left + 19f * unit, top + 6.5f * unit)
                measure.setPath(tick, false)
                drawn.rewind()
                measure.getSegment(0f, measure.length * draw.value, drawn, true)
                drawPath(
                    drawn, D.White,
                    style = Stroke(markStroke(48.dp, disc = true) * unit, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            Column(
                Modifier.graphicsLayer {
                    alpha = draw.value
                    translationY = 12.dp.toPx() * (1f - draw.value)
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Received", color = D.Black, style = Type.title)
                Spacer(Modifier.height(Space.tight))
                Text("${s.request.amount} ${s.request.symbol}", color = D.Black, fontSize = 44.sp, style = Type.title.figure())
            }
            Spacer(Modifier.height(Space.section))
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
        private const val KEY_AMOUNT = "amount"
        private const val KEY_TOKEN = "token"
        private const val KEY_REQUEST = "request"
        private const val KEY_TAKEN = "taken"
        private const val KEY_SIGNATURE = "signature"
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
