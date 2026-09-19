package so.duffle.app

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import so.duffle.app.TapPopupActivity.Phase

// The tap sheet. Motion follows DUFFLE_DESIGN.md: springs described by damping and response,
// with stiffness = (2π / response)². `arrive` is spent on the tap only; everything else settles.
private val Arrive = spring<Float>(dampingRatio = 0.75f, stiffness = 158f)
private val Snap = spring<Float>(dampingRatio = 1f, stiffness = 439f)

/** Bricolage Grotesque for names and amounts; Figtree for everything else. Both variable fonts. */
@OptIn(ExperimentalTextApi::class)
private fun bricolage(w: Int) = Font(R.font.bricolage, FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.Setting("opsz", 96f)))
@OptIn(ExperimentalTextApi::class)
private fun figtree(w: Int) = Font(R.font.figtree, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
internal val Display = FontFamily(bricolage(700), bricolage(800))
internal val Ui = FontFamily(figtree(400), figtree(500), figtree(600), figtree(700))

/** Every amount uses tabular figures, so digits don't shift sideways as they change. */
private val Figures = TextStyle(fontFamily = Ui, fontFeatureSettings = "tnum")

@Composable
internal fun PopupScreen(
    peer: TapPayload,
    phase: Phase,
    connected: Boolean,
    me: String,
    history: Contact?,
    onDismiss: () -> Unit,
    onConnect: () -> Unit,
    onPay: (String, Tokens.Holding?) -> Unit,
    onRequest: (String, Tokens.Holding?) -> Unit,
    onDecline: () -> Unit,
    onRetry: () -> Unit,
    onExplorer: (String) -> Unit,
    previewAmount: String? = null,
    holdings: List<Tokens.Holding>? = null,
) {
    BackHandler(onBack = onDismiss)
    // Hoisted so the amount survives the switch from typing to paying: the same number stays on
    // screen while the button below it reports progress.
    var amount by rememberSaveable { mutableStateOf(previewAmount ?: "") }
    var token by rememberSaveable { mutableStateOf("SOL") }

    val scrim = remember { Animatable(0f) }
    val rise = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        launch { scrim.animateTo(1f, tween(220)) }
        rise.animateTo(0f, Arrive)
    }

    // The tap's ripple: a ring spreading from where the phones touched, then fading.
    val ripple = remember { Animatable(0f) }
    LaunchedEffect(Unit) { ripple.animateTo(1f, tween(900)) }

    CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = Ui)) {
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = .28f * scrim.value))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val t = ripple.value
            drawCircle(
                color = D.Blue.copy(alpha = .6f * (1f - t)),
                radius = size.minDimension * (.08f + .55f * t),
                center = center.copy(y = size.height * .3f),
                style = Stroke(width = 4.dp.toPx()),
            )
        }
        Column(
            Modifier.fillMaxWidth()
                .graphicsLayer { translationY = size.height * rise.value }
                .background(D.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                // Swallow taps so touching the sheet never dismisses it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 12.dp)
                .animateContentSize(spring(dampingRatio = 0.9f, stiffness = 247f)),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(D.Line, CircleShape))
            Spacer(Modifier.height(16.dp))
            val paying = peer is TapPayload.Card && connected && (phase == Phase.Ready || phase is Phase.Working)
            PeerHeader(peer, me, history, title = if (paying) "Pay ${peer.label.ifBlank { "them" }}" else peer.label.ifBlank { "Unknown phone" })
            Spacer(Modifier.height(14.dp))

            // Typing and paying are one screen: the button morphs instead of the sheet swapping.
            val key = when (phase) {
                Phase.Ready, is Phase.Working -> "form"
                is Phase.Waiting -> "waiting"
                is Phase.Success -> "success"
                is Phase.Error -> "error"
                is Phase.Failed -> "failed"
            }
            AnimatedContent(
                targetState = key,
                transitionSpec = {
                    fadeIn(tween(180, delayMillis = 60)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false)
                },
                label = "phase",
            ) { k ->
                Column(Modifier.fillMaxWidth()) {
                    when (k) {
                        "form" -> Form(peer, phase, connected, amount, { amount = it }, token, { token = it }, holdings, onConnect, onPay, onRequest, onDecline, onDismiss)
                        "waiting" -> (phase as? Phase.Waiting)?.let { WaitingBody(peer.label, it.amount, it.symbol, onDismiss) }
                        "success" -> (phase as? Phase.Success)?.let {
                            ResultBody(Result.Success, it.title, it.detail, it.signature, onExplorer) {
                                PrimaryButton("Done", onDismiss)
                            }
                        }
                        "error" -> (phase as? Phase.Error)?.let {
                            ResultBody(Result.Calm, it.title, it.detail, null, onExplorer) { RetryRow(onDismiss, onRetry) }
                        }
                        "failed" -> (phase as? Phase.Failed)?.let {
                            ResultBody(Result.Failed, it.title, it.detail, it.signature, onExplorer) { RetryRow(onDismiss, onRetry) }
                        }
                    }
                }
            }
        }
    }
    }
}

// ------------------------------------------------------------------------------------ header

@Composable
private fun PeerHeader(peer: TapPayload, me: String, history: Contact?, title: String) {
    val seeker = peer.genesis != null
    // The two avatars slide in from opposite sides and meet — the phones touching, again.
    val meet = remember { Animatable(1f) }
    LaunchedEffect(Unit) { meet.animateTo(0f, Arrive) }
    val gap = with(LocalDensity.current) { 28.dp.toPx() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 70.dp, height = 46.dp)) {
            Avatar(me, D.Blue, Modifier.align(Alignment.CenterStart).graphicsLayer { translationX = -gap * meet.value; alpha = 1f - meet.value * .6f })
            Avatar(peer.label.take(1).uppercase().ifBlank { "?" }, D.Black,
                Modifier.align(Alignment.CenterEnd).graphicsLayer { translationX = gap * meet.value; alpha = 1f - meet.value * .6f })
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = D.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Ui, maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false))
                if (seeker) {
                    Spacer(Modifier.width(8.dp))
                    Row(
                        Modifier.background(D.Blue.copy(alpha = .12f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HIcon(R.drawable.hi_tick, D.Black, 12.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("VERIFIED SEEKER", color = D.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
                    }
                }
            }
            Text("${peer.address.take(6)}…${peer.address.takeLast(6)}", color = D.Quiet, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Avatar(letter: String, colour: Color, modifier: Modifier) {
    Box(
        modifier.size(46.dp).background(D.White, CircleShape).padding(3.dp).background(colour, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(letter, color = D.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = Display) }
}

// -------------------------------------------------------------------------------------- form

@Composable
private fun Form(
    peer: TapPayload,
    phase: Phase,
    connected: Boolean,
    amount: String,
    onAmount: (String) -> Unit,
    token: String,
    onToken: (String) -> Unit,
    holdings: List<Tokens.Holding>?,
    onConnect: () -> Unit,
    onPay: (String, Tokens.Holding?) -> Unit,
    onRequest: (String, Tokens.Holding?) -> Unit,
    onDecline: () -> Unit,
    onLater: () -> Unit,
) {
    val working = (phase as? Phase.Working)?.text
    if (!connected) return ConnectBody(working, onConnect)

    if (peer is TapPayload.Request) {
        val asked = peer.request.amount
        Text("Asks you for", color = D.Quiet, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        AmountDisplay(asked, null, peer.request.symbol, centred = true)
        Spacer(Modifier.height(24.dp))
        PrimaryButton(working ?: "Pay $asked ${peer.request.symbol}", { onPay(asked, null) }, loading = working != null)
        // Two different answers: "Later" keeps it in Requests and tells them nothing; "Decline"
        // is a no, and their phone is told so they stop waiting.
        AnimatedVisibility(visible = working == null) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Decline", onDecline, Modifier.weight(1f))
                SecondaryButton("Later", onLater, Modifier.weight(1f))
            }
        }
        return
    }

    // The token list is what this wallet holds. Until it loads, SOL — which every wallet has.
    val list = holdings ?: listOf(SolPlaceholder)
    val chosen = list.firstOrNull { it.token == token } ?: list.first()
    val units = PayRequest.baseUnits(amount, chosen.decimals)
    val problem = when {
        amount.isBlank() || amount.endsWith(".") -> null
        units == null -> "${chosen.symbol} has at most ${chosen.decimals} decimal places"
        (amount.toDoubleOrNull() ?: 0.0) > PayRequest.MAX_AMOUNT -> "Too large for a tap"
        else -> null
    }
    val valid = units != null && problem == null
    // Paying needs the balance; asking for money doesn't.
    val enough = holdings == null || (units != null && units <= chosen.units)
    val context = LocalContext.current
    // While the token list is open the keypad sits underneath it; a tap meant for a token must
    // never type a digit. Twice an amount changed while the list was open, so the keys are off.
    var listOpen by remember { mutableStateOf(false) }

    AmountDisplay(
        amount,
        problem ?: when {
            valid && !enough -> "More than your ${chosen.shown} ${chosen.symbol}"
            holdings != null -> "Balance ${chosen.shown} ${chosen.symbol}"
            else -> null
        },
        chosen.symbol, chip = { mod -> TokenChip(chosen, list, onToken, { listOpen = it }, mod) },
        warn = problem != null || (valid && !enough),
    )
    Keypad(enabled = working == null && !listOpen) { key ->
        Haptics.tick(context)
        onAmount(typed(amount, key, chosen.decimals))
    }
    Spacer(Modifier.height(16.dp))
    PrimaryButton(
        working ?: if (valid) "Pay $amount ${chosen.symbol}" else "Pay",
        { onPay(amount, chosen) }, enabled = valid && enough, loading = working != null,
    )
    AnimatedVisibility(visible = working == null) {
        TextLink("Request instead", { onRequest(amount, chosen) }, enabled = valid)
    }
}

/** One keypress applied to the amount so far. Never produces something that can't be sent. */
private fun typed(current: String, key: Char, decimals: Int = 9): String = when (key) {
    '<' -> current.dropLast(1)
    '.' -> when {
        '.' in current || decimals == 0 -> current
        current.isEmpty() -> "0."
        else -> "$current."
    }
    else -> when {
        current == "0" -> key.toString()
        '.' in current && current.substringAfter('.').length >= decimals -> current
        current.length >= 12 -> current
        else -> current + key
    }
}

@Composable
private fun AmountDisplay(
    amount: String, note: String?, token: String, centred: Boolean = false,
    chip: (@Composable (Modifier) -> Unit)? = null, warn: Boolean = true,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                amount.ifBlank { "0" },
                color = if (amount.isBlank()) D.Quiet else D.Black,
                fontSize = 60.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Display, letterSpacing = (-2).sp,
                style = TextStyle(
                    fontFeatureSettings = "tnum", lineHeight = 60.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                ),
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(10.dp))
            if (chip == null) {
                Text(token, color = D.Quiet, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.alignByBaseline())
            } else {
                chip(Modifier.alignByBaseline())
            }
        }
        // Only speaks up when there's something to say, in a slot that never changes the sheet's height.
        Text(note ?: "", color = if (warn) D.Danger else D.Quiet, fontSize = 13.sp, modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun Keypad(enabled: Boolean, onKey: (Char) -> Unit) {
    val keys = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', '<')
    Column {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { k -> Key(k, enabled, Modifier.weight(1f)) { onKey(k) } }
            }
        }
    }
}

/** A key lights up grey while held — no ripple, just the pressed state, like the mock. */
@Composable
private fun Key(k: Char, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(if (pressed) D.Line else D.White, tween(if (pressed) 0 else 220), label = "key")
    Box(
        modifier.height(58.dp).clip(RoundedCornerShape(16.dp)).background(bg)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (enabled) D.Black else D.Quiet
        if (k == '<') HIcon(R.drawable.hi_backspace, tint, 26.dp)
        else Text(k.toString(), color = tint, fontSize = 24.sp, fontWeight = FontWeight.Medium, style = Figures)
    }
}

@Composable
private fun ConnectBody(working: String?, onConnect: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        IconDisc(R.drawable.hi_wallet, D.Blue, D.Blue.copy(alpha = .1f))
        Spacer(Modifier.height(14.dp))
        Text("Connect a wallet to pay or request", color = D.Black, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text("Duffle never holds your keys.", color = D.Quiet, fontSize = 13.5.sp)
        Spacer(Modifier.height(20.dp))
        PrimaryButton(working ?: "Connect wallet", onConnect, loading = working != null)
    }
}

// ---------------------------------------------------------------------------------- outcomes

@Composable
private fun WaitingBody(label: String, amount: String, symbol: String, onClose: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse",
    )
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        IconDisc(R.drawable.hi_clock, D.Blue, D.Blue.copy(alpha = .1f), Modifier.graphicsLayer { scaleX = pulse; scaleY = pulse })
        Spacer(Modifier.height(14.dp))
        Text("Waiting for ${label.ifBlank { "them" }}", color = D.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("$amount $symbol", color = D.Black, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, style = Figures)
        Spacer(Modifier.height(4.dp))
        Text("You can close this — it stays in Requests.", color = D.Quiet, fontSize = 13.5.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        SecondaryButton("Close", onClose, Modifier.fillMaxWidth())
    }
}

private enum class Result { Success, Calm, Failed }

@Composable
private fun ResultBody(
    kind: Result, title: String, detail: String, signature: String?,
    onExplorer: (String) -> Unit, actions: @Composable () -> Unit,
) {
    // The mark pops in on a spring; the success haptic fires as the phase changes, so the two
    // land together.
    val pop = remember { Animatable(.5f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, Arrive) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val mod = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }
        when (kind) {
            Result.Success -> IconDisc(R.drawable.hi_tick, D.White, D.Success, mod, size = 72.dp, icon = 38.dp)
            Result.Calm -> IconDisc(R.drawable.hi_info, D.Black, D.Fill, mod, size = 72.dp, icon = 34.dp)
            Result.Failed -> IconDisc(R.drawable.hi_alert, D.Danger, D.Danger.copy(alpha = .1f), mod, size = 72.dp, icon = 34.dp)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, color = D.Black, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(detail, color = D.Quiet, fontSize = 15.sp, textAlign = TextAlign.Center, style = Figures)
        if (signature != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { onExplorer(signature) }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("View on explorer", color = D.Blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                HIcon(R.drawable.hi_arrow_up_right, D.Blue, 16.dp)
            }
        }
        Spacer(Modifier.height(20.dp))
        actions()
    }
}

@Composable
private fun RetryRow(onClose: () -> Unit, onRetry: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton("Close", onClose, Modifier.weight(1f))
        PrimaryButton("Try again", onRetry, Modifier.weight(1f))
    }
}

// ----------------------------------------------------------------------------------- pieces

@Composable
private fun HIcon(@DrawableRes id: Int, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) =
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = modifier.size(size))

@Composable
private fun IconDisc(
    @DrawableRes id: Int, tint: Color, background: Color, modifier: Modifier = Modifier,
    size: Dp = 60.dp, icon: Dp = 28.dp,
) {
    Box(modifier.size(size).background(background, CircleShape), contentAlignment = Alignment.Center) {
        HIcon(id, tint, icon)
    }
}

/** Presses scale to 0.97 on the `snap` spring. The label morphs rather than swapping. */
@Composable
private fun PressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, Snap, label = "press")
    return Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
private fun PrimaryButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true, loading: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val live = enabled || loading
    val bg by animateColorAsState(if (live) D.Blue else D.Blue.copy(alpha = .35f), label = "bg")
    val fg = D.White
    Box(
        modifier.then(PressScale(interaction)).height(60.dp).clip(RoundedCornerShape(18.dp)).background(bg)
            .clickable(interaction, indication = null, enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = label to loading,
            transitionSpec = {
                (slideInVertically(spring(0.9f, 247f)) { it / 2 } + fadeIn(tween(150))) togetherWith
                    (slideOutVertically(spring(0.9f, 247f)) { -it / 2 } + fadeOut(tween(100)))
            },
            label = "label",
        ) { (text, spinning) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (spinning) {
                    CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(text, color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold, style = Figures)
            }
        }
    }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.then(PressScale(interaction)).height(52.dp).clip(RoundedCornerShape(16.dp)).background(D.Fill)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) D.Black else D.Quiet, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = Figures)
    }
}

@Composable
private fun TextLink(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        Modifier.fillMaxWidth().padding(top = 6.dp).height(48.dp).clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) D.Black else D.Quiet, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

// ------------------------------------------------------------------------------------ tokens

/** Shown until the wallet's real holdings load. */
private val SolPlaceholder = Tokens.Holding(null, "SOL", "Solana", 9, 0, null)

@Composable
private fun TokenChip(
    chosen: Tokens.Holding, list: List<Tokens.Holding>, onToken: (String) -> Unit,
    onOpen: (Boolean) -> Unit, modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(open) { onOpen(open) }
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(D.Fill).clickable { open = true }
                .padding(start = 6.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TokenImage(chosen, 24.dp)
            Spacer(Modifier.width(6.dp))
            Text(chosen.symbol, color = D.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(2.dp))
            HIcon(R.drawable.hi_arrow_down, D.Black, 18.dp)
        }
        DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            shape = RoundedCornerShape(18.dp), containerColor = D.White, shadowElevation = 12.dp,
        ) {
            list.forEach { t ->
                Row(
                    Modifier.width(280.dp).clickable { onToken(t.token); open = false }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TokenImage(t, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.symbol, color = D.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(t.name, color = D.Quiet, fontSize = 13.sp, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(t.shown, color = D.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = Figures, maxLines = 1)
                    if (t.token == chosen.token) {
                        Spacer(Modifier.width(8.dp))
                        HIcon(R.drawable.hi_tick, D.Blue, 18.dp)
                    }
                }
            }
        }
    }
}

/** A token's logo: SOL's ships with the app; others load from their metadata; a letter otherwise. */
@Composable
private fun TokenImage(t: Tokens.Holding, size: Dp) {
    val round = Modifier.size(size).clip(CircleShape)
    if (t.isSol) {
        Image(painterResource(R.drawable.token_sol), contentDescription = null, modifier = round, contentScale = ContentScale.Crop)
        return
    }
    if (t.image == Tokens.BUNDLED_USDC) {
        Image(painterResource(R.drawable.token_usdc), contentDescription = null, modifier = round, contentScale = ContentScale.Crop)
        return
    }
    val url = t.image
    val bitmap by produceState(initialValue = url?.let { LogoCache.get(it) }, url) {
        if (value == null && url != null) value = withContext(Dispatchers.IO) { LogoCache.load(url) }
    }
    val b = bitmap
    if (b != null) {
        Image(b, contentDescription = null, modifier = round, contentScale = ContentScale.Crop)
    } else {
        // A stable colour per mint, so the same token always looks the same.
        val hue = ((t.token.hashCode() and 0x7fffffff) % 360).toFloat()
        Box(round.background(Color.hsv(hue, .55f, .78f)), contentAlignment = Alignment.Center) {
            Text(t.symbol.take(1).uppercase(), color = D.White, fontSize = (size.value * .42f).sp, fontWeight = FontWeight.Bold, fontFamily = Display)
        }
    }
}

/** Token logos, fetched once and kept small. Never more than 2 MB read, never bigger than 128px. */
private object LogoCache {
    private val cache = android.util.LruCache<String, ImageBitmap>(48)
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun get(url: String): ImageBitmap? = cache.get(url)

    fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        if (url in failed) return null
        return try {
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 6_000; readTimeout = 6_000
            }
            val bytes = c.inputStream.use { it.readNBytes(2_000_000) }
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 128) sample *= 2
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null.also { failed += url }
            bmp.asImageBitmap().also { cache.put(url, it) }
        } catch (e: Exception) {
            failed += url
            null
        }
    }
}
