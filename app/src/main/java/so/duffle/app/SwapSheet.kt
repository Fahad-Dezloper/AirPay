package so.duffle.app

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/** Whole SKR a wallet holds to pay no network fee. The server's own figure: `Sponsor.free_at`. */
internal const val FREE_GAS_SKR = 1_000L

/** SOL left in the wallet when "Max" is pressed, so the swap and the next payment have their fee. */
private const val SOL_KEPT_LAMPORTS = 10_000_000L

private sealed interface Stage {
    data object Edit : Stage
    data object Approving : Stage
    data object Sending : Stage
    /** [heldBefore] is the SKR held when the swap was sent, before the wallet is read again. */
    data class Done(val outUnits: Long, val paid: String, val heldBefore: Long) : Stage
}

/**
 * Buying SKR without leaving AirPay: pick what to pay with, type how much, see what Jupiter will
 * give for it, and swap.
 *
 * The rate is Jupiter's and is asked for again whenever the figure or the token changes, and
 * every fifteen seconds while the sheet sits open, so the number on the button's side of the
 * sheet is never an old one. The wallet signs the transaction Jupiter built; this sheet never
 * holds the money.
 *
 * Its own number pad, not the keyboard: the tray does not move for the keyboard, and a pad keeps
 * the rate and the button on screen while the amount is typed.
 *
 * @param scope the activity's, not the sheet's. The wallet opens over this app while it signs, and
 *   a scope that dies with the composition would drop the swap the moment it was approved.
 */
@Composable
internal fun BuySkrSheet(
    holdings: List<Tokens.Holding>,
    taker: String,
    /** Dollars for one whole token, by mint ([Market.WSOL] for SOL), or null when not known. */
    usd: (String) -> Double?,
    scope: CoroutineScope,
    sign: suspend (ByteArray) -> Wallet.SendOutcome,
    onSwapped: () -> Unit,
    onClose: () -> Unit,
    /**
     * Preview builds only: SKR base units to show as already swapped, so the last screen can be
     * looked at without swapping anything. Null everywhere else.
     */
    previewDone: Long? = null,
) {
    val ctx = LocalContext.current
    // What can be paid with: anything held that is not SKR itself.
    val sources = remember(holdings) { holdings.filter { it.mint != Swap.SKR_MINT && it.units > 0 } }
    val skrHeld = remember(holdings) { holdings.firstOrNull { it.mint == Swap.SKR_MINT }?.units ?: 0L }

    var fromToken by remember { mutableStateOf<String?>(null) }
    val from = sources.firstOrNull { it.token == fromToken }
        // Dollars first: somebody buying SKR usually means to spend dollars on it.
        ?: sources.firstOrNull { it.symbol == "USDC" } ?: sources.firstOrNull()
    var typed by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var stage by remember {
        mutableStateOf<Stage>(previewDone?.let { Stage.Done(it, "1.00 USDC", skrHeld) } ?: Stage.Edit)
    }
    var order by remember { mutableStateOf<Swap.Order?>(null) }
    var asking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var lastAsk by remember { mutableLongStateOf(0L) }
    var details by remember { mutableStateOf(false) }

    val units = from?.let { toUnits(typed, it.decimals) } ?: 0L
    val spendable = from?.let { if (it.isSol) (it.units - SOL_KEPT_LAMPORTS).coerceAtLeast(0L) else it.units } ?: 0L
    val over = units > (from?.units ?: 0L)
    val inMint = from?.let { it.mint ?: Market.WSOL }

    /** One ask, kept inside Jupiter's allowance. Null when it could not be answered. */
    suspend fun ask(mint: String, amount: Long): Swap.Asked {
        val wait = lastAsk + Swap.MIN_GAP_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastAsk = System.currentTimeMillis()
        return withContext(Dispatchers.IO) { Swap.order(mint, amount, taker) }
    }

    // The rate, asked for once the figure has stopped changing and again while the sheet waits.
    LaunchedEffect(inMint, units, stage is Stage.Edit) {
        if (stage !is Stage.Edit) return@LaunchedEffect
        order = null
        note = null
        if (inMint == null || units <= 0L || over) { asking = false; return@LaunchedEffect }
        asking = true
        delay(450)
        while (true) {
            when (val a = ask(inMint, units)) {
                is Swap.Asked.Ok -> { order = a.order; note = a.order.problem }
                is Swap.Asked.None -> { order = null; note = a.why }
                Swap.Asked.Busy -> {}
                Swap.Asked.Offline -> if (order == null) note = "Could not reach Jupiter. Check your connection."
            }
            asking = false
            delay(15_000)
        }
    }

    fun swap() {
        val mint = inMint ?: return
        val paying = from ?: return
        if (stage !is Stage.Edit || units <= 0L || over) return
        val want = units
        scope.launch {
            var landed = false
            try {
                stage = Stage.Approving
                note = null
                // Signed against a rate that is still good, and for exactly the figure on screen.
                val o = order?.takeIf { it.signable && it.inUnits == want && it.fresh(System.currentTimeMillis()) }
                    ?: (ask(mint, want) as? Swap.Asked.Ok)?.order?.also { order = it }
                if (o == null || !o.signable) {
                    note = o?.problem ?: "Could not get a rate. Nothing was swapped. Try again."
                    return@launch
                }
                val tx = runCatching { android.util.Base64.decode(o.transaction, android.util.Base64.DEFAULT) }.getOrNull()
                if (tx == null) { note = "This swap could not be prepared. Nothing was swapped."; return@launch }
                when (val signed = sign(tx)) {
                    is Wallet.SendOutcome.Signed -> {
                        stage = Stage.Sending
                        when (val done = withContext(Dispatchers.IO) { Swap.execute(signed.tx, o.requestId!!) }) {
                            is Swap.Done.Ok -> {
                                Analytics.event("skr_bought", "token" to paying.symbol)
                                landed = true
                                Haptics.success(ctx)
                                stage = Stage.Done(
                                    done.outUnits ?: o.outUnits,
                                    Money.token(BigDecimal.valueOf(want, paying.decimals), paying.symbol), skrHeld,
                                )
                                onSwapped()
                            }
                            is Swap.Done.Failed -> note = done.why
                            Swap.Done.Offline -> note = "Lost the connection while swapping. Check your balance before trying again."
                        }
                    }
                    is Wallet.SendOutcome.Failed -> note = signed.why.replace("payment", "swap")
                    Wallet.SendOutcome.NeedsReconnect -> note = "Your wallet did not approve that. Nothing was swapped."
                }
            } finally {
                // Whatever happened, including this being cancelled, the sheet is usable again.
                if (!landed) stage = Stage.Edit
            }
        }
    }

    val busy = stage is Stage.Approving || stage is Stage.Sending
    Tray(onClose = onClose, dismissible = !busy) {
        AnimatedContent(
            targetState = stage as? Stage.Done,
            transitionSpec = { fadeIn(tween(260, delayMillis = 120)) togetherWith fadeOut(tween(140)) },
            label = "swap",
        ) { done ->
            if (done != null) {
                Swapped(done, onClose)
                return@AnimatedContent
            }
            Column {
                Spacer(Modifier.height(Space.lead))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Buy SKR", color = D.Black, style = Type.title, modifier = Modifier.weight(1f))
                    Text(
                        "Best rate from Jupiter", color = D.Black, style = Type.caption,
                        modifier = Modifier.clip(CircleShape).background(D.Highlight).padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                Spacer(Modifier.height(Space.lead))

                if (from == null) {
                    Text(
                        "There is nothing in this wallet to swap yet. Add SOL or USDC and come back.",
                        color = D.Quiet, style = Type.body,
                    )
                    Spacer(Modifier.height(Space.section))
                    PrimaryButton("Close", onClose, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(Space.lead))
                    return@Column
                }

                // ---- what is paid
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(D.Fill).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("You pay", color = D.Quiet, style = Type.caption, modifier = Modifier.weight(1f))
                        Text("You have ${from.shown} ${from.symbol}", color = D.Quiet, style = Type.caption)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val ink by animateColorAsState(if (over) D.Danger else if (typed.isEmpty()) D.Quiet else D.Black, label = "ink")
                        FlowFigure(
                            typed.ifEmpty { "0" }, 34.sp, ink, FontWeight.SemiBold,
                            Modifier.weight(1f), fontFamily = Figure,
                        )
                        TokenChip(from, enabled = !busy && sources.size > 1, open = picking) { picking = !picking }
                    }
                    // Every other thing that can be paid with, one tap away.
                    AnimatedVisibility(picking, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Row(
                            Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            sources.forEach { t ->
                                val on = t.token == from.token
                                Row(
                                    Modifier.clip(CircleShape).background(if (on) D.Black else D.White)
                                        .clickable { fromToken = t.token; typed = ""; picking = false }
                                        .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TokenImage(t, 24.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(t.symbol, color = if (on) D.White else D.Black, style = Type.body)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("25%" to 4L, "50%" to 2L, "Max" to 1L).forEach { (label, part) ->
                            Text(
                                label, color = D.Black, style = Type.caption,
                                modifier = Modifier.clip(CircleShape).background(D.White)
                                    .clickable(enabled = !busy) {
                                        typed = plain(spendable / part, from.decimals)
                                        Haptics.tick(ctx)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        // What the figure above is worth, in the money everything else here is in.
                        Text(dollars(units, from.decimals, usd(inMint!!)) ?: "", color = D.Quiet, style = Type.body)
                    }
                }

                // ---- the arrow between them, breathing while the rate is asked for
                val breathe by rememberInfiniteTransition(label = "ask").animateFloat(
                    1f, 0.35f, infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a",
                )
                Box(
                    Modifier.align(Alignment.CenterHorizontally).offset(y = (-10).dp).zIndex(1f).size(36.dp)
                        .clip(CircleShape).background(D.White).border(1.5.dp, D.Line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.graphicsLayer { alpha = if (asking) breathe else 1f }) {
                        HiIcon(Hi.ArrowDown, D.Black, 18.dp, contentDescription = null)
                    }
                }

                // ---- what arrives
                Column(
                    Modifier.fillMaxWidth().offset(y = (-20).dp).clip(RoundedCornerShape(22.dp))
                        .background(D.Fill).padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("You get", color = D.Quiet, style = Type.caption)
                        Spacer(Modifier.width(6.dp))
                        // The rate and the small print, for whoever wants them. Opens beside the
                        // "i", on the side that has the room.
                        Box {
                            val o = order
                            val press = remember { MutableInteractionSource() }
                            Box(
                                Modifier.size(28.dp).then(PressScale(press)).clip(CircleShape)
                                    .clickable(press, indication = null, enabled = o != null) { details = true },
                                contentAlignment = Alignment.Center,
                            ) { HiIcon(Hi.Info, if (o != null) D.Black else D.Line, 18.dp, contentDescription = "Rate and details") }
                            if (details && o != null) Details(o, from, usd(Swap.SKR_MINT)) { details = false }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val got = order?.outUnits
                        FlowFigure(
                            if (got != null) skr(BigDecimal.valueOf(got, Swap.SKR_DECIMALS)) else "0",
                            34.sp, if (got != null) D.Black else D.Quiet, FontWeight.SemiBold,
                            Modifier.weight(1f).graphicsLayer { alpha = if (asking && got == null) breathe else 1f },
                            fontFamily = Figure,
                        )
                        Row(
                            Modifier.clip(CircleShape).background(D.White)
                                .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(painterResource(R.drawable.skr_token), contentDescription = null, modifier = Modifier.size(24.dp).clip(CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text("SKR", color = D.Black, style = Type.body)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // Dollars for what arrives. When SKR's own price is not known, what was paid
                    // stands in: a swap gives very nearly what it takes.
                    Text(
                        order?.let { dollars(it.outUnits, Swap.SKR_DECIMALS, usd(Swap.SKR_MINT)) ?: dollars(it.inUnits, from.decimals, usd(inMint!!)) } ?: "",
                        color = D.Quiet, style = Type.body,
                    )
                }

                // ---- the rate, and what holding SKR is for
                Column(Modifier.offset(y = (-8).dp)) {
                    FreeGas(skrHeld + (order?.outUnits ?: 0L))
                }

                Keys(enabled = !busy) { k ->
                    typed = press(typed, k, from.decimals)
                    Haptics.tick(ctx)
                }
                Spacer(Modifier.height(10.dp))
                note?.let {
                    Text(it, color = D.Quiet, style = Type.caption, modifier = Modifier.padding(bottom = 8.dp))
                }
                val ready = order?.signable == true && !over && units > 0L
                PrimaryButton(
                    when {
                        stage is Stage.Approving -> "Approve in your wallet"
                        stage is Stage.Sending -> "Swapping"
                        units <= 0L -> "Enter an amount"
                        over -> "Not enough ${from.symbol}"
                        asking && order == null -> "Getting the best rate"
                        else -> "Swap"
                    },
                    { swap() }, Modifier.fillMaxWidth(),
                    enabled = ready && !busy, loading = busy || (asking && order == null && units > 0L && !over),
                )
                Spacer(Modifier.height(Space.lead))
            }
        }
    }
}

/** The token being paid with, as a pill that opens the list of the others. */
@Composable
private fun TokenChip(t: Tokens.Holding, enabled: Boolean, open: Boolean, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, spring(dampingRatio = 0.8f), label = "turn")
    Row(
        Modifier.clip(CircleShape).background(D.White).clickable(enabled = enabled, onClick = onClick)
            .padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TokenImage(t, 24.dp)
        Spacer(Modifier.width(6.dp))
        Text(t.symbol, color = D.Black, style = Type.body)
        if (enabled) {
            Spacer(Modifier.width(4.dp))
            Box(Modifier.graphicsLayer { rotationZ = turn }) { HiIcon(Hi.ArrowDown, D.Quiet, 14.dp, contentDescription = "Change") }
        }
    }
}

/**
 * The rate and the small print of an order, in a card that opens from the "i".
 *
 * A popup, so it floats over the sheet and a touch anywhere else puts it away. It hangs from the
 * icon's left edge and the window keeps it on screen, so it opens towards whichever side has room.
 */
@Composable
private fun Details(o: Swap.Order, from: Tokens.Holding, skrUsd: Double?, onClose: () -> Unit) {
    // The popup's own window is this much bigger than the card on every side, so the card's
    // shadow has somewhere to fall instead of being cut off square at the window's edge.
    val room = 20.dp
    val (dx, dy) = with(LocalDensity.current) { -room.roundToPx() to (34.dp - room).roundToPx() }
    Popup(
        alignment = Alignment.TopStart, offset = IntOffset(dx, dy),
        onDismissRequest = onClose, properties = PopupProperties(focusable = true),
    ) {
        val rise = remember { Animatable(0f) }
        LaunchedEffect(Unit) { rise.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 420f)) }
        Column(
            Modifier.padding(room).width(276.dp)
                .graphicsLayer {
                    alpha = rise.value.coerceIn(0f, 1f)
                    val k = 0.92f + 0.08f * rise.value
                    scaleX = k; scaleY = k
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                // A soft, tinted shadow. The default one at this height drew as a grey block over
                // the number pad beneath the card.
                .shadow(10.dp, RoundedCornerShape(20.dp), clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.14f))
                .clip(RoundedCornerShape(20.dp)).background(D.White).border(1.dp, D.Line, RoundedCornerShape(20.dp))
                .padding(16.dp),
        ) {
            val per = BigDecimal.valueOf(o.outUnits, Swap.SKR_DECIMALS)
                .divide(BigDecimal.valueOf(o.inUnits, from.decimals), 6, java.math.RoundingMode.DOWN)
            Text("RATE", color = D.Quiet, style = Type.micro)
            Spacer(Modifier.height(6.dp))
            Text(
                "1 ${from.symbol} = ${skr(per)} SKR", color = D.Black, style = Type.heading.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(D.Highlight).padding(horizontal = 8.dp, vertical = 4.dp),
            )
            if (skrUsd != null && skrUsd > 0) {
                Spacer(Modifier.height(6.dp))
                Text("1 SKR is about $${"%.4f".format(skrUsd)}", color = D.Quiet, style = Type.caption)
            }
            Spacer(Modifier.height(14.dp))
            Fact("Least you get", "${skr(BigDecimal.valueOf(o.minOut, Swap.SKR_DECIMALS))} SKR")
            o.impact?.let { p ->
                val pct = kotlin.math.abs(p)
                Fact("Price impact", if (pct < 0.01) "Under 0.01%" else "${"%.2f".format(pct)}%", if (pct >= 2.0) D.OrangeInk else D.Black)
            }
            Fact("Network fee", if (o.gasless) "Paid by Jupiter" else "From your wallet")
            Fact("Found by", "Jupiter" + o.router.takeIf { it.isNotBlank() }?.let { " · ${it.replaceFirstChar(Char::uppercase)}" }.orEmpty(), last = true)
        }
    }
}

@Composable
private fun Fact(label: String, value: String, ink: androidx.compose.ui.graphics.Color = D.Black, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = D.Quiet, style = Type.caption, modifier = Modifier.weight(1f))
        Text(value, color = ink, style = Type.body.copy(fontFeatureSettings = "tnum"))
    }
    if (!last) RowLine()
}

/**
 * How close this wallet is to paying no network fee: one wide stripe that fills, with what is
 * left to go written in the part still empty. [units] is the SKR it will hold once the swap on
 * screen has gone through.
 *
 * The words are drawn twice, dark and then white, and the white copy is cut to the filled part:
 * so a word the fill passes under turns white exactly where the fill is, whatever its length.
 */
@Composable
private fun FreeGas(
    target: Long,
    /** A slower, deliberate move, for when the change is the thing being shown. */
    slow: Boolean = false,
    /** Ticks in the hand while it moves, and the success pattern if it arrives at free gas. */
    felt: Boolean = false,
) {
    val ctx = LocalContext.current
    val need = FREE_GAS_SKR * 1_000_000L
    // The SKR itself is what animates, so the stripe and the figure in it move as one thing:
    // the number counts down exactly as the fill grows.
    val moving by animateFloatAsState(
        target.toFloat(),
        if (slow) tween(1_400, easing = FastOutSlowInEasing) else spring(dampingRatio = 0.9f, stiffness = 120f),
        label = "gas",
    )
    val units = moving.toLong()
    val part = (moving / need).coerceIn(0f, 1f)
    val there = units >= need
    val fill by animateColorAsState(if (there) D.Success else D.Blue, label = "fill")
    val left = BigDecimal.valueOf((need - units).coerceAtLeast(0L), Swap.SKR_DECIMALS).setScale(0, java.math.RoundingMode.UP)
    if (felt) {
        var from by remember { mutableLongStateOf(target) }
        LaunchedEffect(target) {
            if (target == from) return@LaunchedEffect
            val crossed = from < need && target >= need
            from = target
            // Close together at first and further apart as it settles, like something rolling
            // to a stop. Spread over the time the stripe takes to move.
            for (gap in listOf(60L, 60L, 70L, 80L, 90L, 110L, 130L, 160L, 200L, 240L)) {
                Haptics.tick(ctx)
                delay(gap)
            }
            if (crossed) Haptics.success(ctx)
        }
    }
    val words: @Composable (androidx.compose.ui.graphics.Color) -> Unit = { ink ->
        Row(Modifier.fillMaxWidth().height(STRIPE).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Free gas", color = ink, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Text("${java.text.DecimalFormat("#,##0").format(left)} SKR to go", color = ink, style = Type.caption.copy(fontFeatureSettings = "tnum"))
        }
    }
    // Full: the stripe has nothing left to measure, so it closes up into a mark of its own, as
    // wide as its words and in the middle. A stripe that is all one colour says less than this.
    AnimatedContent(
        targetState = there,
        transitionSpec = {
            (fadeIn(tween(220, delayMillis = 90)) + scaleIn(spring(dampingRatio = 0.55f, stiffness = 380f), initialScale = 0.7f)) togetherWith
                fadeOut(tween(120))
        },
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        label = "unlocked",
    ) { full ->
        if (full) {
            Box(Modifier.fillMaxWidth().height(STRIPE), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.height(STRIPE).clip(CircleShape).background(D.Success).padding(start = 12.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HiIcon(Hi.Gift, D.White, 18.dp, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Free network fees unlocked", color = D.White, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().height(STRIPE).clip(CircleShape).background(D.Fill)) {
                words(D.Black)
                Box(
                    Modifier.fillMaxWidth().height(STRIPE).drawWithContent {
                        // Only the filled part of this layer is drawn: the fill, and the white words on it.
                        clipRect(right = size.width * part) { this@drawWithContent.drawContent() }
                    }.background(fill),
                ) { words(D.White) }
            }
        }
    }
}

/**
 * What holding SKR has earned this wallet, opened from the mark on Home.
 *
 * Said in three things and no more: that the fees are free, a coin, and why. The coin is the
 * SKR token, large and with a rim, turning steadily about its upright axis, in place.
 *
 * @param onBuy closes this sheet and opens the one that buys SKR.
 */
@Composable
internal fun FreeGasSheet(onBuy: () -> Unit, onClose: () -> Unit) {
    val arrive = remember { Animatable(0.7f) }
    LaunchedEffect(Unit) { arrive.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 220f)) }
    // One steady turn after another, in place and without end. No toss, no landing, no shadow:
    // it stays exactly where it is and only turns.
    //
    // Read off the frame clock, not from a repeating animation. A repeat ends on 360 and starts
    // again on 0, which are the same picture drawn twice: one frame where the coin stands still,
    // face on, every turn. That was the flick. The clock never repeats a value.
    var angle by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val began = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            angle = ((now - began) % COIN_TURN_NANOS).toFloat() / COIN_TURN_NANOS * 360f
        }
    }
    val density = LocalDensity.current
    val thick = with(density) { COIN_THICK.toPx() }

    Tray(onClose = onClose) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(Space.section))
            // Said once. The line under the coin says who pays and why, not this again.
            FreeFeeMark("Free network fees")
            Spacer(Modifier.height(Space.row))

            Box(
                Modifier.size(COIN).graphicsLayer { scaleX = arrive.value; scaleY = arrive.value },
                contentAlignment = Alignment.Center,
            ) {
                val rad = Math.toRadians(angle.toDouble())
                val sin = kotlin.math.sin(rad).toFloat()
                val facing = kotlin.math.cos(rad) >= 0
                // The coin's body, drawn as the one solid shape a thick disc shows from the side:
                // its two faces as ellipses, and everything between them filled in.
                //
                // It was a stack of thin discs, each turned like the face. Edge on, every one of
                // them is a line with nothing between it and the next, so for a frame the coin
                // was mostly not there: the flick, at the moment it stands straight.
                Canvas(Modifier.size(COIN)) {
                    val half = thick * 0.5f * kotlin.math.abs(sin)
                    val w = size.width * kotlin.math.abs(kotlin.math.cos(rad)).toFloat()
                    val cx = size.width / 2f
                    drawRect(COIN_EDGE, Offset(cx - half, 0f), Size(half * 2f, size.height))
                    drawOval(COIN_EDGE, Offset(cx - half - w / 2f, 0f), Size(w, size.height))
                    drawOval(COIN_EDGE, Offset(cx + half - w / 2f, 0f), Size(w, size.height))
                }
                Image(
                    painterResource(R.drawable.skr_token), contentDescription = null,
                    modifier = Modifier.size(COIN).graphicsLayer {
                        rotationY = angle
                        // Seen from far off, so the face is a plain ellipse that sits exactly
                        // on the body drawn under it at every angle.
                        cameraDistance = 400f * density.density
                        // On whichever face is towards the eye, and the right way round on both.
                        translationX = thick * 0.5f * sin * (if (facing) 1f else -1f)
                        if (!facing) scaleX = -1f
                    }.clip(CircleShape),
                )
            }

            Spacer(Modifier.height(Space.row))
            Text(
                "AirPay pays them for you, because this wallet holds more than ${"%,d".format(FREE_GAS_SKR)} SKR.",
                color = D.Black, style = Type.body, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(Space.section))
            PrimaryButton("Buy more SKR", onBuy, Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.lead))
        }
    }
}

private val COIN = 176.dp
/** How thick the coin is, edge on. */
private val COIN_THICK = 30.dp
/** One whole turn of the coin. */
private const val COIN_TURN_NANOS = 2_800_000_000L
/** The coin's edge, a shade off the token's own black so the rim can be told from the face. */
private val COIN_EDGE = Color(0xFF3A3A3A)

private val STRIPE = 34.dp

@Composable
private fun Keys(enabled: Boolean, onKey: (Char) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("123", "456", "789", ".0<").forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { k ->
                    val press = remember { MutableInteractionSource() }
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp).then(PressScale(press)).clip(RoundedCornerShape(14.dp))
                            .clickable(press, indication = null, enabled = enabled) { onKey(k) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == '<') HiIcon(Hi.Backspace, D.Black, 24.dp, contentDescription = "Delete")
                        else Text(k.toString(), color = D.Black, style = Type.title)
                    }
                }
            }
        }
    }
}

/**
 * The swap landed: the tick arrives, the SKR that came rolls up, and the free gas stripe moves
 * from where it was to where it is now, felt in the hand as it goes.
 */
@Composable
private fun Swapped(done: Stage.Done, onClose: () -> Unit) {
    val pop = remember { Animatable(0.4f) }
    var shown by remember { mutableStateOf("0") }
    // Starts where the wallet was, so the stripe is seen to grow by what was just bought.
    var held by remember { mutableStateOf(done.heldBefore) }
    LaunchedEffect(Unit) {
        launch { pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 300f)) }
        delay(220)
        shown = skr(BigDecimal.valueOf(done.outUnits, Swap.SKR_DECIMALS))
        delay(520)
        held = done.heldBefore + done.outUnits
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(Space.section))
        // The app's own paid disc: the hero size, with the mark at the weight a disc takes.
        Box(
            Modifier.size(72.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.clip(CircleShape).background(D.Success),
            contentAlignment = Alignment.Center,
        ) { HiIcon(Hi.Tick, D.White, 40.dp, contentDescription = null, disc = true) }
        Spacer(Modifier.height(Space.row))
        Text("You got", color = D.Quiet, style = Type.body)
        Spacer(Modifier.height(Space.tight))
        // The token's own mark says what the figure is a figure of. No "SKR" beside it as well.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.skr_token), contentDescription = "SKR", modifier = Modifier.size(34.dp).clip(CircleShape))
            Spacer(Modifier.width(10.dp))
            FlowFigure(shown, 40.sp, D.Black, FontWeight.SemiBold, fontFamily = Figure)
        }
        Spacer(Modifier.height(Space.tight))
        Text("Swapped from ${done.paid}", color = D.Quiet, style = Type.caption)
        Spacer(Modifier.height(Space.row))
        FreeGas(held, slow = true, felt = true)
        Spacer(Modifier.height(Space.row))
        PrimaryButton("Done", onClose, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.lead))
    }
}

/** [units] of a token in dollars, as "$1.00", or null when the price is not known. */
internal fun dollars(units: Long, decimals: Int, price: Double?): String? {
    if (price == null || price <= 0.0 || units <= 0L) return null
    val v = BigDecimal.valueOf(units, decimals).multiply(BigDecimal.valueOf(price))
    // Under a cent is said as that, not as "$0.00".
    if (v < BigDecimal("0.01")) return "<" + Money.fiat(BigDecimal("0.01"), "USD")
    return Money.fiat(v.setScale(2, java.math.RoundingMode.HALF_UP), "USD")
}

/**
 * An amount of SKR for this sheet: two places, cut and never rounded up, so the figure shown is
 * never more than what arrives. The app's usual SKR figure is whole, which is right for a reward
 * and too coarse for a rate.
 */
internal fun skr(value: BigDecimal): String =
    java.text.DecimalFormat("#,##0.00").apply { roundingMode = java.math.RoundingMode.DOWN }.format(value)

// ---- the figure being typed. Pure, so the rules can be tested.

/** What [typed] becomes when [key] is pressed: a digit, the point, or '<' to take one back. */
internal fun press(typed: String, key: Char, decimals: Int): String = when {
    key == '<' -> typed.dropLast(1)
    key == '.' -> if ('.' in typed || decimals == 0) typed else if (typed.isEmpty()) "0." else "$typed."
    !key.isDigit() -> typed
    // No more places than the token has, and no more than anybody types.
    '.' in typed && typed.substringAfter('.').length >= minOf(decimals, 6) -> typed
    typed == "0" -> key.toString()
    typed.replace(".", "").length >= 12 -> typed
    else -> typed + key
}

/** [typed] in the token's base units. Zero for anything that is not yet a number. */
internal fun toUnits(typed: String, decimals: Int): Long =
    typed.trimEnd('.').toBigDecimalOrNull()?.movePointRight(decimals)
        ?.setScale(0, java.math.RoundingMode.DOWN)?.takeIf { it.signum() > 0 && it.toBigInteger().bitLength() < 63 }?.toLong() ?: 0L

/** [units] as the figure somebody would have typed for it, cut to the places [press] allows. */
internal fun plain(units: Long, decimals: Int): String {
    if (units <= 0L) return ""
    val v = BigDecimal.valueOf(units, decimals).setScale(minOf(decimals, 6), java.math.RoundingMode.DOWN).stripTrailingZeros()
    return if (v.signum() == 0) "" else v.toPlainString()
}
