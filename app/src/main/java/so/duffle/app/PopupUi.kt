package so.duffle.app

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.produceState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
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

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PopupScreen(
    peer: TapPayload,
    phase: Phase,
    connected: Boolean,
    /** One letter for our own avatar. */
    me: String,
    /** Our wallet address, when connected — what the reward preview is asked about. */
    myAddress: String,
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
    /** What the payment just made earned, once the server knows. Shown on the ✓ screen. */
    earned: Rewards.Earned? = null,
    /** How the sheet was opened: "nfc", "hce", "app" (in person) or "server", "notification". */
    cameFrom: String? = null,
) {
    // Hoisted so the amount survives the switch from typing to paying: the same number stays on
    // screen while the button below it reports progress.
    var amount by rememberSaveable { mutableStateOf(previewAmount ?: "") }
    var token by rememberSaveable { mutableStateOf("SOL") }
    // Pay opens a review of the payment; only "Confirm and pay" starts it. Hoisted so "Try again"
    // after a failure comes back to the review, not the keypad.
    var confirming by rememberSaveable { mutableStateOf(false) }
    // The page about the other phone, opened by tapping the header.
    var aboutThem by rememberSaveable { mutableStateOf(false) }
    // A Genesis token is a claim until the chain says otherwise. Checked for every phone, on
    // mainnet (where Genesis lives), and the "Verified Seeker" badge waits for the answer.
    val genesis by produceState<Genesis.Check?>(initialValue = null, peer.address) {
        value = withContext(Dispatchers.IO) { Genesis.check(peer.address) }
    }
    // Fetched now, not when the device page opens: arriving mid-animation it changed "Checking…"
    // to a date while the sheet was still resizing, forcing a relayout during the move.
    val wallet by produceState<WalletAge?>(initialValue = null, peer.address) { value = walletAge(peer.address) }

    // The sheet is positioned by one number: how far below its resting place it is, in pixels.
    // Rising, dragging, springing back and leaving all move that same number, so any of them can
    // take over from another mid-motion — a drag can catch the sheet while it is still rising.
    val scope = rememberCoroutineScope()
    val scrim = remember { Animatable(0f) }
    val offset = remember { Animatable(0f) }
    var sheetHeight by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Wait for the first measurement, then rise. Written so that a restart simply carries on
        // from wherever the sheet is — an earlier version gave up on restart and left the sheet
        // parked below the screen, invisible, with the app still in front.
        val h = snapshotFlow { sheetHeight }.first { it > 0f }
        if (!shown) {
            offset.snapTo(h)
            shown = true
        }
        launch { scrim.animateTo(1f, tween(220)) }
        offset.animateTo(0f, Arrive)
    }
    // Nothing leaves while money is in flight: the payment would carry on with no screen to report it.
    val canLeave = phase !is Phase.Working

    /** Slides the sheet away, then runs [after]. Exits are quicker and quieter than entrances. */
    fun leave(after: () -> Unit = onDismiss) {
        if (leaving) return
        if (!canLeave) {
            scope.launch { offset.animateTo(0f, spring(0.9f, 247f)) }
            return
        }
        leaving = true
        scope.launch {
            launch { scrim.animateTo(0f, tween(200)) }
            offset.animateTo(sheetHeight.coerceAtLeast(1f), tween(220, easing = FastOutLinearInEasing))
            after()
        }
    }
    BackHandler { leave() }
    val close: () -> Unit = { leave() }

    val drag = rememberDraggableState { delta ->
        scope.launch {
            val next = offset.value + delta
            // Pulled upward it resists and stops a little way up; downward it follows the finger.
            offset.snapTo(if (next < 0f) (offset.value + delta * .25f).coerceAtLeast(-40f) else next)
        }
    }
    // Everything that moves every frame — the sheet's height while it resizes, how far it has been
    // dragged, the dim fading — is read while drawing, never while composing. Read here, in the
    // body, the height changing on every frame of a resize recomposed the entire sheet every frame
    // (113 recompositions in three trips to the device page), and that was the stutter.
    fun dismissFraction() = if (sheetHeight > 0f) (offset.value / sheetHeight).coerceIn(0f, 1f) else 0f

    // The tap's ripple: a ring spreading from where the phones touched, then fading.
    val ripple = remember { Animatable(0f) }
    LaunchedEffect(Unit) { ripple.animateTo(1f, tween(900)) }

    CompositionLocalProvider(LocalTextStyle provides TextStyle(fontFamily = Ui)) {
    // Hosted here, at the top of the sheet, rather than around the form: nested inside the sheet's
    // size animations it could be asked to place a shared element before it had been placed
    // itself, and crashed ("Uninitialized LayoutCoordinates").
    SharedTransitionLayout(Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalTransition provides this, LocalVerified provides (genesis is Genesis.Check.Held),
        LocalMe provides myAddress) {
    Box(
        Modifier.fillMaxSize()
            .drawBehind { drawRect(Color.Black.copy(alpha = .28f * scrim.value * (1f - dismissFraction()))) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
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
                .onSizeChanged { sheetHeight = it.height.toFloat() }
                // Below the screen until it has been measured, so it never flashes in place.
                .graphicsLayer { translationY = if (shown) offset.value else size.height.toFloat() }
                .draggable(
                    drag, Orientation.Vertical, enabled = shown && !leaving,
                    onDragStopped = { velocity ->
                        // Far enough, or flicked: go. Otherwise settle back where it was.
                        if (offset.value > sheetHeight * .25f || velocity > 1_200f) leave()
                        else offset.animateTo(0f, spring(0.9f, 247f), initialVelocity = velocity)
                    },
                )
                .background(D.White, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                // Swallow taps so touching the sheet never dismisses it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                // No animateContentSize here. Every change of screen already animates its own height
                // (SizeTransform); a second size animation on the sheet chased the first one frame by
                // frame, re-laying out the whole sheet each time — 40-50ms frames going to the device
                // page on a OnePlus, against a 16ms budget.
                .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(D.Line, CircleShape))
            Spacer(Modifier.height(16.dp))
            val paying = peer is TapPayload.Card && connected && (phase == Phase.Ready || phase is Phase.Working)
            PeerHeader(peer, me, history, title = if (paying && !aboutThem) "Pay ${peer.label.ifBlank { "them" }}" else peer.label.ifBlank { "Unknown phone" },
                verified = genesis is Genesis.Check.Held,
                onOpen = if (phase is Phase.Working) null else ({ aboutThem = !aboutThem }), open = aboutThem)
            Spacer(Modifier.height(14.dp))

            // Typing and paying are one screen: the button morphs instead of the sheet swapping.
            BackHandler(enabled = aboutThem) { aboutThem = false }
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
                    fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(110)) using
                        SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.9f, stiffness = 380f) }
                },
                label = "phase",
            ) { k ->
                Column(Modifier.fillMaxWidth()) {
                    when (k) {
                        // The keypad and the device page both stay built; switching only fades one
                        // out and the other in. Rebuilding the keypad form on the way back — amount,
                        // chip, twelve keys, shared-element setup — cost 85-150ms frames.
                        "form" -> KeepBoth(
                            showSecond = aboutThem && phase !is Phase.Working,
                            first = {
                                Form(peer, phase, connected, amount, { amount = it }, token, { token = it }, holdings, onConnect, onPay, onRequest,
                                    { leave(onDecline) }, close, confirming, { confirming = it }, covered = aboutThem)
                            },
                            second = { AboutThem(peer, history, genesis, wallet, cameFrom, onBack = { aboutThem = false }) },
                        )
                        "waiting" -> (phase as? Phase.Waiting)?.let { WaitingBody(peer.label, it.amount, it.symbol, close) }
                        "success" -> (phase as? Phase.Success)?.let {
                            ResultBody(Result.Success, it.title, it.detail, it.signature, onExplorer, earned) {
                                PrimaryButton("Done", close)
                            }
                        }
                        "error" -> (phase as? Phase.Error)?.let {
                            ResultBody(Result.Calm, it.title, it.detail, null, onExplorer) { RetryRow(close, onRetry) }
                        }
                        "failed" -> (phase as? Phase.Failed)?.let {
                            ResultBody(Result.Failed, it.title, it.detail, it.signature, onExplorer) { RetryRow(close, onRetry) }
                        }
                    }
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
private fun PeerHeader(
    peer: TapPayload, me: String, history: Contact?, title: String,
    verified: Boolean, onOpen: (() -> Unit)?, open: Boolean,
) {
    // Only what the chain confirmed. A claimed Genesis token used to earn the badge by itself.
    val seeker = verified
    // The two avatars slide in from opposite sides and meet — the phones touching, again.
    val meet = remember { Animatable(1f) }
    LaunchedEffect(Unit) { meet.animateTo(0f, Arrive) }
    val gap = with(LocalDensity.current) { 28.dp.toPx() }

    Row(
        Modifier.clip(RoundedCornerShape(16.dp))
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 70.dp, height = 46.dp)) {
            Avatar(me, D.Blue, Brand.mine(), Modifier.align(Alignment.CenterStart).graphicsLayer { translationX = -gap * meet.value; alpha = 1f - meet.value * .6f })
            // The Seeker mark, like the badge, only once the chain has confirmed it.
            Avatar(peer.label.take(1).uppercase().ifBlank { "?" }, D.Black, Brand.of(peer, verified),
                Modifier.align(Alignment.CenterEnd).graphicsLayer { translationX = gap * meet.value; alpha = 1f - meet.value * .6f })
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // One line, shortened with "…" if it must be. The "VERIFIED SEEKER" pill that used to
                // sit here didn't fit beside a name on a narrower phone and cut "Pay Seeker" to
                // "Pay"; a verified mark after the name says the same, and the device page says why.
                Text(title, color = D.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Ui,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (seeker) {
                    Spacer(Modifier.width(5.dp))
                    HIcon(R.drawable.hi_verified, D.Blue, 20.dp)
                }
            }
            Text("${peer.address.take(6)}…${peer.address.takeLast(6)}", color = D.Quiet, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
private fun Avatar(letter: String, colour: Color, brand: String?, modifier: Modifier) {
    // The maker's mark when we know the phone; the initial when we don't.
    val look = Brand.look(brand)
    Box(
        modifier.size(46.dp).background(D.White, CircleShape).padding(3.dp).clip(CircleShape)
            .background(look?.colour ?: colour),
        contentAlignment = Alignment.Center,
    ) {
        when {
            look == null -> Text(letter, color = D.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = Display)
            look.picture -> Image(painterResource(look.drawable), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> HIcon(look.drawable, D.White, 20.dp)
        }
    }
}

// -------------------------------------------------------------------------------------- form

@OptIn(ExperimentalSharedTransitionApi::class)
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
    confirming: Boolean,
    onConfirming: (Boolean) -> Unit,
    /** The device page is showing over this form; its back button belongs to that page. */
    covered: Boolean = false,
) {
    val working = (phase as? Phase.Working)?.text
    if (!connected) return ConnectBody(working, onConnect)

    // Back from the review returns to the amount, not out of the sheet — unless money is moving.
    BackHandler(enabled = confirming && working == null && !covered) { onConfirming(false) }

    // The keypad and the review share their pieces, like Framer Motion's layoutId: the token's logo
    // flies out of the chip to sit above the number, the amount glides into place, "SOL" grows, and
    // the two buttons stay exactly where they are while their words change. Only the middle —
    // keypad or details — fades from one to the other.
    // What this payment would earn, asked here rather than on the review screen.
    //
    // It used to be asked when the review opened, and someone who went from "Pay" to "Confirm"
    // in a second never saw the answer: the request was still in flight when the screen moved
    // on, and was cancelled with it. A warning nobody has time to read is not a warning. For a
    // request the amount is known the moment the sheet opens, so the call has the whole time
    // the person spends reading it.
    val req0 = (peer as? TapPayload.Request)?.request
    val payAmount0 = req0?.amount ?: amount
    val decimals0 = req0?.decimals ?: holdings?.firstOrNull { it.token == token }?.decimals ?: 9
    val units0 = PayRequest.baseUnits(payAmount0, decimals0) ?: 0L
    val me0 = LocalMe.current
    val reward by produceState<Rewards.Preview?>(initialValue = null, me0, peer.address, req0?.token ?: token, units0) {
        value = null
        if (units0 > 0 && me0.isNotBlank()) value = Rewards.preview(me0, peer.address, req0?.token ?: token, units0)
    }

    val transition = LocalTransition.current
    AnimatedContent(
        targetState = confirming,
        transitionSpec = {
            fadeIn(tween(220, delayMillis = 70)) togetherWith fadeOut(tween(120)) using
                SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.9f, stiffness = 380f) }
        },
        label = "review",
    ) { reviewing ->
        CompositionLocalProvider(LocalShared provides transition?.let { SharedScopes(it, this@AnimatedContent) }) {
        Column(Modifier.fillMaxWidth()) {
            if (reviewing) {
                val req = (peer as? TapPayload.Request)?.request
                val payAmount = req?.amount ?: amount
                val held = holdings?.firstOrNull { it.token == (req?.token ?: token) }
                val shown = held ?: req?.let { Tokens.Holding(it.mint, it.symbol, it.symbol, it.decimals, 0, null) }
                    ?: holdings?.firstOrNull() ?: SolPlaceholder
                ConfirmBody(peer, payAmount, shown, known = holdings != null && held != null, working = working,
                    reward = reward,
                    onConfirm = { onPay(payAmount, if (req == null) shown else null) }, onBack = { onConfirming(false) })
            } else {
                Entry(peer, working, amount, onAmount, token, onToken, holdings, { onConfirming(true) }, onRequest, onDecline, onLater,
                    // Shown here too when the amount is already fixed: on a request there is
                    // nothing left to change, so the earlier they read it the better.
                    reward = if (peer is TapPayload.Request) reward else null)
            }
        }
        }
    }
}

/**
 * Two screens, both kept composed, one shown. The container takes the shown one's height (animated
 * once, here) and the other is faded out and made untouchable — so switching builds nothing.
 */
@Composable
private fun KeepBoth(showSecond: Boolean, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    val firstAlpha by animateFloatAsState(if (showSecond) 0f else 1f, tween(if (showSecond) 110 else 200, delayMillis = if (showSecond) 0 else 60), label = "first")
    val secondAlpha by animateFloatAsState(if (showSecond) 1f else 0f, tween(if (showSecond) 200 else 110, delayMillis = if (showSecond) 60 else 0), label = "second")
    Layout(
        contents = listOf(
            { Box(Modifier.graphicsLayer { alpha = firstAlpha }.inert(showSecond)) { first() } },
            { Box(Modifier.graphicsLayer { alpha = secondAlpha }.inert(!showSecond)) { second() } },
        ),
        modifier = Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.9f, stiffness = 380f)),
    ) { (a, b), constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val pa = a.first().measure(loose)
        val pb = b.first().measure(loose)
        val shown = if (showSecond) pb else pa
        // The shown screen is placed on top, so it is the one touches reach. With the page always
        // on top, its hidden (touch-swallowing) layer sat over the keypad and no key could be tapped.
        layout(constraints.maxWidth, shown.height) {
            pa.placeRelative(0, 0, zIndex = if (showSecond) 0f else 1f)
            pb.placeRelative(0, 0, zIndex = if (showSecond) 1f else 0f)
        }
    }
}

/** Hidden content: no touches reach it, and screen readers skip it. */
private fun Modifier.inert(hidden: Boolean): Modifier = if (!hidden) this else this
    .clearAndSetSemantics { }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

/** The two scopes a shared element needs, handed down so any piece of the form can take part. */
@OptIn(ExperimentalSharedTransitionApi::class)
private class SharedScopes(val transition: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

private val LocalShared = compositionLocalOf<SharedScopes?> { null }

/** Whether the chain has confirmed the other phone is a Seeker, for anything deep in the sheet. */
private val LocalVerified = compositionLocalOf { false }

/** The connected wallet's address. The review screen asks the server what it would earn. */
private val LocalMe = compositionLocalOf { "" }

@OptIn(ExperimentalSharedTransitionApi::class)
private val LocalTransition = compositionLocalOf<SharedTransitionScope?> { null }

/** Joins this element to its twin on the other screen under [key]. Outside the review flow, nothing. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.shared(key: String, bounds: Boolean = false): Modifier {
    val s = LocalShared.current ?: return this
    val motion = BoundsTransform { _, _ -> spring(dampingRatio = 0.86f, stiffness = 380f) }
    return with(s.transition) {
        val state = rememberSharedContentState(key)
        // Bounds for things whose content changes (a button's words, SOL growing): the old and new
        // crossfade inside a shape that moves. Element for things that are the same on both sides.
        if (bounds) this@shared.sharedBounds(
            state, s.visibility, boundsTransform = motion,
            resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(ContentScale.Fit, Alignment.CenterStart),
        )
        else this@shared.sharedElement(state, s.visibility, boundsTransform = motion)
    }
}

@Composable
private fun Entry(
    peer: TapPayload,
    working: String?,
    amount: String,
    onAmount: (String) -> Unit,
    token: String,
    onToken: (String) -> Unit,
    holdings: List<Tokens.Holding>?,
    onReview: () -> Unit,
    onRequest: (String, Tokens.Holding?) -> Unit,
    onDecline: () -> Unit,
    onLater: () -> Unit,
    reward: Rewards.Preview? = null,
) {
    if (peer is TapPayload.Request) {
        val asked = peer.request.amount
        Text("Asks you for", color = D.Quiet, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        AmountDisplay(asked, null, peer.request.symbol, centred = true)
        RewardLine(reward)
        Spacer(Modifier.height(24.dp))
        PrimaryButton(working ?: "Pay $asked ${peer.request.symbol}", onReview,
            Modifier.fillMaxWidth().shared("primary", bounds = true), loading = working != null)
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
        onReview, Modifier.fillMaxWidth().shared("primary", bounds = true), enabled = valid && enough, loading = working != null,
    )
    AnimatedVisibility(visible = working == null) {
        TextLink("Request instead", { onRequest(amount, chosen) }, enabled = valid, modifier = Modifier.shared("secondary", bounds = true))
    }
}

/**
 * The last look before money moves: what, to whom, on which network, what it costs, and what is
 * left. Nothing has been sent when this shows; "Confirm and pay" is the only way forward.
 */
@Composable
private fun ConfirmBody(
    peer: TapPayload,
    amount: String,
    token: Tokens.Holding,
    /** False when the wallet's balance of this token isn't known (or it holds none). */
    known: Boolean,
    working: String?,
    /** What this payment would earn. Asked by the caller, long before this screen opened. */
    reward: Rewards.Preview?,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    val units = PayRequest.baseUnits(amount, token.decimals) ?: 0L
    val fee = 5_000L // one signature; the wallet may add a small priority fee on top
    // A first payment in a token also opens the recipient's account for it — checked, not assumed.
    val needsAccount by produceState<Boolean?>(initialValue = null, token.token, peer.address) {
        value = if (token.isSol) false else withContext(Dispatchers.IO) {
            runCatching {
                val program = Chain.mintInfo(token.mint!!)?.program ?: return@runCatching null
                Chain.exists(Chain.tokenAccount(peer.address, token.mint, program))?.not()
            }.getOrNull()
        }
    }
    val setupLamports = if (needsAccount == true) 2_039_280L else 0L
    val solCost = fee + setupLamports + if (token.isSol) units else 0L
    val left = if (token.isSol) token.units - solCost else token.units - units
    val enough = known && left >= 0
    val brand = Brand.of(peer, LocalVerified.current)

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.shared("logo-${token.token}")) { TokenImage(token, 52.dp) }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            // Styled exactly like the keypad's amount, so the number moves without changing shape.
            Text(
                amount, color = D.Black, fontSize = 60.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Display,
                letterSpacing = (-2).sp, softWrap = false, maxLines = 1,
                style = TextStyle(
                    fontFeatureSettings = "tnum", lineHeight = 60.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                ),
                modifier = Modifier.weight(1f, fill = false).shared("amount"),
            )
            Spacer(Modifier.width(10.dp))
            Text(token.symbol, color = D.Black, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp).shared("symbol-${token.token}", bounds = true))
        }
        Text("to ${peer.label.ifBlank { "them" }}", color = D.Quiet, fontSize = 15.sp)

        Spacer(Modifier.height(18.dp))
        DetailRow("Pay with") {
            TokenImage(token, 20.dp)
            Spacer(Modifier.width(6.dp))
            Value(token.symbol)
        }
        DetailRow("To") {
            MiniAvatar(brand, peer.label)
            Spacer(Modifier.width(6.dp))
            Value(peer.label.ifBlank { "Unknown phone" })
        }
        DetailRow("Address") { Value("${peer.address.take(6)}…${peer.address.takeLast(6)}") }
        DetailRow("Network") { Value("Solana devnet") }
        DetailRow("Network fee") { Value("~${Chain.fmtUnits(fee, 9)} SOL") }
        if (needsAccount == true) {
            DetailRow("New account") { Value("~${Chain.fmtUnits(setupLamports, 9)} SOL") }
        }
        DetailRow("Total") {
            Value(
                if (token.isSol) "${Chain.fmtUnits(solCost, 9)} SOL"
                else "$amount ${token.symbol} + ${Chain.fmtUnits(fee + setupLamports, 9)} SOL",
                strong = true,
            )
        }

        RewardLine(reward)

        Spacer(Modifier.height(20.dp))
        PrimaryButton(working ?: if (enough) "Confirm and pay" else if (known) "Not enough ${token.symbol}" else "No ${token.symbol} in your wallet", onConfirm, Modifier.fillMaxWidth().shared("primary", bounds = true),
            enabled = enough, loading = working != null)
        AnimatedVisibility(visible = working == null) { TextLink("Back", onBack, modifier = Modifier.shared("secondary", bounds = true)) }
    }
}

/**
 * What this payment earns, said before it is made — or why it earns nothing.
 *
 * Nobody should learn a reward rule by not being paid. The refusal is the important half: a
 * wallet made this morning earns nothing, and the person tapping it deserves to know that
 * while they can still decide, rather than wondering afterwards.
 */
@Composable
private fun RewardLine(reward: Rewards.Preview?) {
    val why = Rewards.sayWhy(reward)
    AnimatedVisibility(visible = reward != null && (reward.earns || why != null)) {
        val earns = reward?.earns == true
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp)
                .background(if (earns) D.Blue.copy(alpha = .07f) else D.Fill, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HIcon(if (earns) R.drawable.hi_gift else R.drawable.hi_info, if (earns) D.Blue else D.Quiet, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                if (earns) "Earns ${reward?.skr} SKR" else why.orEmpty(),
                color = if (earns) D.Blue else D.Quiet,
                fontSize = 14.sp, fontWeight = if (earns) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = D.Quiet, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, content = value)
    }
}

@Composable
private fun Value(text: String, strong: Boolean = false) =
    Text(text, color = D.Black, fontSize = 15.sp, fontWeight = if (strong) FontWeight.Bold else FontWeight.Medium, style = Figures, maxLines = 1)

/** A small version of the header avatar: their phone's mark, or their initial. */
@Composable
private fun MiniAvatar(brand: String?, label: String) {
    val look = Brand.look(brand)
    Box(Modifier.size(22.dp).clip(CircleShape).background(look?.colour ?: D.Black), contentAlignment = Alignment.Center) {
        when {
            look == null -> Text(label.take(1).uppercase().ifBlank { "?" }, color = D.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            look.picture -> Image(painterResource(look.drawable), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> HIcon(look.drawable, D.White, 12.dp)
        }
    }
}

/**
 * What can honestly be said about the phone you just touched. Everything here is either checked
 * on-chain, or your own history with them, or labelled as what their phone says about itself —
 * never a guess dressed up as a verdict.
 */
@Composable
private fun AboutThem(
    peer: TapPayload, history: Contact?, genesis: Genesis.Check?, wallet: WalletAge?, cameFrom: String?, onBack: () -> Unit,
) {
    val verified = genesis is Genesis.Check.Held
    val brand = Brand.of(peer, verified)
    val fmt = DayMonth

    Column(Modifier.fillMaxWidth()) {
        DetailRow("Genesis token") {
            when (genesis) {
                null -> Value("Checking…")
                is Genesis.Check.Held -> {
                    Value("Verified Seeker")
                    Spacer(Modifier.width(5.dp))
                    HIcon(R.drawable.hi_verified, D.Blue, 18.dp)
                }
                Genesis.Check.Unreachable -> Value("Couldn't check")
                // The one warning here: the phone claimed a Genesis token its wallet doesn't hold.
                Genesis.Check.None -> if (peer.genesis != null) {
                    Text("Claimed, not held", color = D.Danger, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                } else Value("Not a Seeker")
            }
        }
        DetailRow("Phone") {
            MiniAvatar(brand, peer.label)
            Spacer(Modifier.width(6.dp))
            Value(Brand.name(brand) ?: "Unknown")
        }
        when (cameFrom) {
            "nfc", "hce", "app" -> DetailRow("Met") { Value("In person, by tap") }
            "server", "notification" -> DetailRow("Met") { Value("Through Duffle, after a tap") }
        }
        DetailRow("With you") {
            Value(when {
                history == null || history.taps <= 1 -> "First time"
                history.totalPaid > 0 -> "${history.taps} taps · paid ${"%.4f".format(history.totalPaid).trimEnd('0').trimEnd('.')} SOL"
                else -> "${history.taps} taps since ${fmt.format(java.util.Date(history.firstTapMillis))}"
            })
        }
        DetailRow("Wallet") {
            val w = wallet
            when {
                w == null -> Value("Checking…")
                w.count < 0 -> Value("Couldn't check")
                w.count == 0 -> Text("Brand new", color = D.Danger, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                // Only the first thousand transactions are read, so a busier wallet is "before" that date.
                else -> Value(w.firstMillis?.let { (if (w.count >= 1000) "Before " else "Since ") + fmt.format(java.util.Date(it)) } ?: "In use")
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("WALLET ADDRESS", color = D.Quiet, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        AddressRow(peer.address)
        Spacer(Modifier.height(4.dp))
        TextLink("Back", onBack)
    }
}

/** One line, however narrow the phone: the address shortened in the middle, with its two actions. */
@Composable
private fun AddressRow(address: String) {
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1_400); copied = false } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(D.Fill)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${address.take(8)}…${address.takeLast(8)}", color = D.Black, fontSize = 16.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1, softWrap = false,
            modifier = Modifier.weight(1f),
        )
        RoundIcon(if (copied) R.drawable.hi_tick else R.drawable.hi_copy, if (copied) "Copied" else "Copy address") {
            Haptics.select(context)
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(address))
            copied = true
        }
        Spacer(Modifier.width(6.dp))
        RoundIcon(R.drawable.hi_link_out, "View on explorer") {
            Haptics.select(context)
            runCatching { uri.openUri("https://explorer.solana.com/address/$address?cluster=devnet") }
        }
    }
}

/** A large round icon button that presses in and clicks. */
@Composable
private fun RoundIcon(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.then(PressScale(interaction)).size(48.dp).clip(CircleShape).background(D.White)
            .clickable(interaction, indication = null, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = icon,
            transitionSpec = { (fadeIn(tween(120)) + scaleIn(initialScale = .6f)) togetherWith fadeOut(tween(80)) },
            label = "icon",
        ) { HIcon(it, D.Black, 24.dp) }
    }
}

/**
 * "7 Sept". One instance for the app: building a SimpleDateFormat loads locale data, and doing it
 * each time the device page opened was work on the first frame of the animation.
 */
private val DayMonth by lazy { java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()) }

/**
 * How long their wallet has been in use on Solana (with anyone, not just you) — a wallet made
 * minutes ago with no history is what a throwaway looks like. `count` is -1 when unreachable.
 */
private data class WalletAge(val count: Int, val firstMillis: Long?)

private suspend fun walletAge(address: String): WalletAge = withContext(Dispatchers.IO) {
    val res = Chain.rpc("""{"jsonrpc":"2.0","id":1,"method":"getSignaturesForAddress","params":["$address",{"limit":1000}]}""")
    if (res == null || Chain.rpcError(res) != null) return@withContext WalletAge(-1, null)
    runCatching {
        val arr = org.json.JSONObject(res).getJSONArray("result")
        // Newest first, so the last entry is the oldest this page reaches.
        val oldest = if (arr.length() > 0) arr.getJSONObject(arr.length() - 1).optLong("blockTime", 0L).takeIf { it > 0 } else null
        WalletAge(arr.length(), oldest?.times(1000))
    }.getOrDefault(WalletAge(-1, null))
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
        // Bottom-aligned with small offsets rather than alignByBaseline: reading a baseline lays a
        // child out during measurement, before the shared-transition host is placed, and the
        // shared amount and chip crashed the sheet on open ("Uninitialized LayoutCoordinates").
        Row(verticalAlignment = Alignment.Bottom) {
            // The token chip is measured first and always keeps its full width; the number gets
            // what is left. Past that it scrolls sideways, pinned to the digit just typed, with a
            // fade on the left edge to say there is more. A long number used to crush the chip
            // until "SOL" wrapped one letter per line.
            val scroll = rememberScrollState()
            LaunchedEffect(amount, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
            Text(
                amount.ifBlank { "0" },
                softWrap = false, maxLines = 1,
                color = if (amount.isBlank()) D.Quiet else D.Black,
                fontSize = 60.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Display, letterSpacing = (-2).sp,
                style = TextStyle(
                    fontFeatureSettings = "tnum", lineHeight = 60.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                ),
                modifier = Modifier.weight(1f, fill = false)
                    .shared("amount")
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        if (scroll.value > 0) {
                            drawRect(
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = 28.dp.toPx()),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    }
                    .horizontalScroll(scroll),
            )
            Spacer(Modifier.width(10.dp))
            if (chip == null) {
                Text(token, color = D.Quiet, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp).shared("symbol-$token", bounds = true))
            } else {
                chip(Modifier.padding(bottom = 2.dp))
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
    Box(
        modifier.height(58.dp).clip(RoundedCornerShape(16.dp))
            .clickable(remember { MutableInteractionSource() }, indication = KeyPress, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (enabled) D.Black else D.Quiet
        if (k == '<') HIcon(R.drawable.hi_backspace, tint, 26.dp)
        else Text(k.toString(), color = tint, fontSize = 24.sp, fontWeight = FontWeight.Medium, style = Figures)
    }
}

/**
 * A key's grey press, drawn by one shared modifier node: on at the touch, fading out over 220ms.
 * Each key used to hold its own pressed state and colour animation — twelve of each set up in the
 * frame the keypad reappeared, which is where the long frame coming back from a page went.
 */
private object KeyPress : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = KeyPressNode(interactionSource)
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7
}

private class KeyPressNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private val shade = Animatable(0f)
    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { i ->
                when (i) {
                    is PressInteraction.Press -> launch { shade.snapTo(1f) }
                    is PressInteraction.Release, is PressInteraction.Cancel -> launch { shade.animateTo(0f, tween(220)) }
                }
            }
        }
    }
    override fun ContentDrawScope.draw() {
        if (shade.value > 0f) drawRect(D.Line.copy(alpha = shade.value))
        drawContent()
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
    onExplorer: (String) -> Unit, earned: Rewards.Earned? = null,
    actions: @Composable () -> Unit,
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
        // Arrives seconds after the ✓, because the server only decides once the payment is on
        // the chain. It slides in rather than appearing, so it reads as something that happened
        // rather than something that was always there.
        AnimatedVisibility(visible = earned != null, enter = fadeIn(tween(260)) + expandVertically(spring(dampingRatio = 0.75f, stiffness = 158f))) {
            Row(
                Modifier.padding(top = 14.dp).background(D.Blue.copy(alpha = .08f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HIcon(R.drawable.hi_gift, D.Blue, 18.dp)
                Spacer(Modifier.width(7.dp))
                Text("You earned ${earned?.skr ?: 0} SKR", color = D.Blue, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
private fun TextLink(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Box(
        Modifier.padding(top = 6.dp).then(modifier).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
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
    // The list stays on screen until its exit has played, not just until `open` goes false.
    val visible = remember { MutableTransitionState(false) }
    LaunchedEffect(open) { visible.targetState = open; onOpen(open) }
    val inPopup = visible.currentState || visible.targetState
    // Where the list grows from — the chip, wherever the list ended up relative to it.
    var origin by remember { mutableStateOf(TransformOrigin(.15f, 0f)) }
    val density = LocalDensity.current
    val shadowRoom = 8.dp

    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(D.Fill).clickable { open = true }
                .padding(start = 6.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The chip changes the instant a token is picked — it sits above the list, so this is
            // the first thing the eye catches.
            AnimatedContent(
                targetState = chosen,
                transitionSpec = { (fadeIn(tween(140)) + scaleIn(initialScale = .8f, animationSpec = spring(0.7f, 600f))) togetherWith fadeOut(tween(90)) },
                contentKey = { it.token }, label = "chip",
            ) { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Keyed by token so switching tokens here never has two "logos" at once.
                    Box(Modifier.shared("logo-${t.token}")) { TokenImage(t, 24.dp) }
                    Spacer(Modifier.width(6.dp))
                    Text(t.symbol, color = D.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.shared("symbol-${t.token}", bounds = true))
                }
            }
            Spacer(Modifier.width(2.dp))
            HIcon(R.drawable.hi_arrow_down, D.Black, 18.dp)
        }

        if (inPopup) {
            // The popup is larger than the card by `shadowRoom` on every side, so the shadow has
            // somewhere to fall. Material's menu draws its shadow in a window the size of the
            // card, and the edges came out cut square.
            val position = remember(density) {
                MenuPosition(
                    gap = with(density) { 6.dp.roundToPx() }, margin = with(density) { 16.dp.roundToPx() },
                    room = with(density) { shadowRoom.roundToPx() },
                ) { origin = it }
            }
            Popup(
                popupPositionProvider = position,
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true, clippingEnabled = false),
            ) {
                AnimatedVisibility(
                    visibleState = visible,
                    enter = fadeIn(tween(120)) + scaleIn(initialScale = .94f, transformOrigin = origin, animationSpec = spring(0.8f, 700f)),
                    exit = fadeOut(tween(130)) + scaleOut(targetScale = .96f, transformOrigin = origin, animationSpec = tween(130)),
                ) {
                    Box(Modifier.padding(shadowRoom)) {
                        TokenList(chosen, list, onPick = onToken, onDone = { open = false })
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenList(chosen: Tokens.Holding, list: List<Tokens.Holding>, onPick: (String) -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Where the ring is. Moves the moment a row is tapped, before anything closes.
    var ringOn by remember { mutableStateOf(chosen.token) }
    var picking by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.width(290.dp)
            .cardShadow(20.dp)
            .background(D.White, shape)
            .padding(6.dp),
    ) {
        list.forEach { t ->
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val rowBg by animateColorAsState(if (pressed) D.Fill else D.White, tween(if (pressed) 0 else 200), label = "row")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(rowBg)
                    .clickable(interaction, indication = null, enabled = !picking) {
                        // Tap → click felt, ring lands, chip and balance switch → then the list
                        // goes. Everything the person needs to see has happened before it closes.
                        picking = true
                        Haptics.select(context)
                        ringOn = t.token
                        onPick(t.token)
                        scope.launch {
                            delay(if (t.token == chosen.token) 120 else 260)
                            onDone()
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectRing(selected = t.token == ringOn) { TokenImage(t, 36.dp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.symbol, color = D.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(t.name, color = D.Quiet, fontSize = 13.sp, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Text(t.shown, color = D.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = Figures, maxLines = 1)
                Spacer(Modifier.width(6.dp))
            }
        }
    }
}

/**
 * The selected token: a ring of eight short rounded strokes around its logo, like a sun. It springs in
 * and turns a few degrees as it lands; leaving, it simply fades.
 */
@Composable
private fun SelectRing(selected: Boolean, content: @Composable () -> Unit) {
    val p by animateFloatAsState(
        if (selected) 1f else 0f,
        if (selected) spring(dampingRatio = 0.55f, stiffness = 500f) else tween(110),
        label = "ring",
    )
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            if (p <= 0.01f) return@Canvas
            val strokes = 8
            val inner = 20.5.dp.toPx()          // just outside the 36dp logo
            val length = 2.5.dp.toPx() * p.coerceAtMost(1.15f)
            rotate(-30f * (1f - p)) {
                for (i in 0 until strokes) {
                    val a = (2 * Math.PI * i / strokes).toFloat()
                    val dir = Offset(kotlin.math.cos(a), kotlin.math.sin(a))
                    drawLine(
                        color = D.Blue.copy(alpha = p.coerceIn(0f, 1f)),
                        start = center + dir * inner,
                        end = center + dir * (inner + length),
                        strokeWidth = 2.2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        content()
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

/**
 * The standard card edge, as CSS writes it:
 *     0 2px 3px -1px rgba(0,0,0,.1), 0 1px 0 0 rgba(25,28,33,.02), 0 0 0 1px rgba(25,28,33,.08)
 * A soft drop, a crisp lip, and a hairline ring — the ring is what separates a white card from the
 * white sheet under it. Drawn by hand because Compose's shadow() has no spread and no outline.
 */
private fun Modifier.cardShadow(radius: Dp) = drawBehind {
    val r = radius.toPx()
    val px = 1.dp.toPx()
    drawIntoCanvas { canvas ->
        // 0 2px 3px -1px rgba(0,0,0,.1): the shape shrunk by the spread, casting a blurred shadow.
        val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            setShadowLayer(3 * px, 0f, 2 * px, android.graphics.Color.argb(26, 0, 0, 0))
        }
        canvas.nativeCanvas.drawRoundRect(px, px, size.width - px, size.height - px, r - px, r - px, paint)
    }
    // 0 1px 0 0 rgba(25,28,33,.02): the same shape, one pixel lower.
    drawRoundRect(Color(25, 28, 33).copy(alpha = .02f), topLeft = Offset(0f, px), size = size, cornerRadius = CornerRadius(r))
    // 0 0 0 1px rgba(25,28,33,.08): a one-pixel ring outside the edge.
    drawRoundRect(
        Color(25, 28, 33).copy(alpha = .08f), topLeft = Offset(-px, -px),
        size = Size(size.width + 2 * px, size.height + 2 * px), cornerRadius = CornerRadius(r + px),
    )
}

/**
 * Places the token list like any good menu: under the chip and lined up with it, slid sideways to
 * stay [margin] inside the screen, and flipped above the chip when there's no room below. A long
 * amount pushes the chip right, and the list used to run off the edge.
 *
 * The popup is the card plus [room] on every side for its shadow; positions are for the card.
 */
private class MenuPosition(
    private val gap: Int,
    private val margin: Int,
    private val room: Int,
    private val onOrigin: (TransformOrigin) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize,
    ): IntOffset {
        val w = popupContentSize.width - 2 * room
        val h = popupContentSize.height - 2 * room
        val maxX = (windowSize.width - margin - w).coerceAtLeast(margin)
        val x = anchorBounds.left.coerceIn(margin, maxX)
        val below = anchorBounds.bottom + gap
        val fitsBelow = below + h <= windowSize.height - margin
        val y = if (fitsBelow) below else (anchorBounds.top - gap - h).coerceAtLeast(margin)
        // Grow out of the chip: its centre, measured on the card, at the edge nearest to it.
        val ox = if (w > 0) ((anchorBounds.center.x - x).toFloat() / w).coerceIn(0f, 1f) else .5f
        onOrigin(TransformOrigin(ox, if (fitsBelow) 0f else 1f))
        return IntOffset(x - room, y - room)
    }
}
