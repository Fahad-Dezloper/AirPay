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
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.TextStyle
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import so.duffle.app.TapPopupActivity.Phase


@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PopupScreen(
    /**
     * Who is on the other side — null while the sheet is **armed**: open, waiting for the
     * phones to touch, with nobody on the other end yet. The same sheet then fills in rather
     * than being replaced, which is the whole point of doing it here instead of on the page.
     */
    peer: TapPayload?,
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
    /** "Later": the sheet closes, the request stays payable, and the asker is told. */
    onLater: () -> Unit,
    onRetry: () -> Unit,
    onExplorer: (String) -> Unit,
    previewAmount: String? = null,
    /** Shown on the waiting screen when the payer has answered without paying yet. */
    waitingNote: String? = null,
    holdings: List<Tokens.Holding>? = null,
    /** What the payment just made earned, once the server knows. Shown on the ✓ screen. */
    earned: Rewards.Earned? = null,
    /** How the sheet was opened: "nfc", "hce", "app" (in person) or "server", "notification". */
    cameFrom: String? = null,
    /** The chain's answer on this peer's Genesis token, for whoever wants to remember it. */
    onVerified: (Boolean) -> Unit = {},
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
    val genesis by produceState<Genesis.Check?>(initialValue = null, peer?.address) {
        val who = peer?.address ?: return@produceState
        value = withContext(Dispatchers.IO) { Genesis.check(who) }
        // Held and None are both answers; Unreachable is not one, and is not reported — a
        // chain we could not ask must never look like a chain that said no.
        when (value) {
            is Genesis.Check.Held -> onVerified(true)
            Genesis.Check.None -> onVerified(false)
            else -> Unit
        }
    }
    // Fetched now, not when the device page opens: arriving mid-animation it changed "Checking…"
    // to a date while the sheet was still resizing, forcing a relayout during the move.
    val wallet by produceState<WalletAge?>(initialValue = null, peer?.address) {
        value = peer?.address?.let { walletAge(it) }
    }

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

    // The tap's light only means one thing — two phones met — so a sheet opened by a push, a
    // notification or the camera gets none. It used to draw a ring for every one of them.
    val inPerson = cameFrom in InPerson

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
                .background(D.White, RoundedCornerShape(topStart = Tray.OUTER, topEnd = Tray.OUTER))
                // Swallow taps so touching the sheet never dismisses it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                // No animateContentSize here. Every change of screen already animates its own height
                // (SizeTransform); a second size animation on the sheet chased the first one frame by
                // frame, re-laying out the whole sheet each time — 40-50ms frames going to the device
                // page on a OnePlus, against a 16ms budget.
                .padding(start = Tray.PAD, end = Tray.PAD, top = 10.dp, bottom = 12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(D.Line, CircleShape))
            // The sheet's own top padding, below the grab handle: `pt-5` on the body in
            // ui/components/screens/Tap.tsx. Not a spacing role — it is this sheet's inset.
            Spacer(Modifier.height(20.dp))
            if (peer != null) {
                val paying = peer is TapPayload.Card && connected && (phase == Phase.Ready || phase is Phase.Working)
                PeerHeader(peer, me, history, title = if (paying && !aboutThem) "Pay ${peer.label.ifBlank { "them" }}" else peer.label.ifBlank { "Unknown phone" },
                    verified = genesis is Genesis.Check.Held,
                    onOpen = if (phase is Phase.Working) null else ({ aboutThem = !aboutThem }), open = aboutThem)
                Spacer(Modifier.height(Space.lead))
            }

            // Typing and paying are one screen: the button morphs instead of the sheet swapping.
            BackHandler(enabled = aboutThem) { aboutThem = false }
            val key = if (peer == null) "armed" else when (phase) {
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
                        // Waiting for the other phone. Shares the AnimatedContent with every
                        // other state, so when the tap lands the sheet grows into the keypad
                        // on the same spring rather than closing and reopening as a new one.
                        "armed" -> ArmedBody(close)
                        // The keypad and the device page both stay built; switching only fades one
                        // out and the other in. Rebuilding the keypad form on the way back — amount,
                        // chip, twelve keys, shared-element setup — cost 85-150ms frames.
                        "form" -> peer?.let { p -> KeepBoth(
                            showSecond = aboutThem && phase !is Phase.Working,
                            first = {
                                Form(p, phase, connected, amount, { amount = it }, token, { token = it }, holdings, onConnect, onPay, onRequest,
                                    { leave(onDecline) }, { leave(onLater) }, confirming, { confirming = it }, covered = aboutThem,
                                    cameFrom = cameFrom)
                            },
                            second = { AboutThem(p, history, genesis, wallet, cameFrom, onBack = { aboutThem = false }) },
                        ) }
                        "waiting" -> (phase as? Phase.Waiting)?.let { WaitingBody(peer?.label.orEmpty(), it.amount, it.symbol, waitingNote, close) }
                        "success" -> (phase as? Phase.Success)?.let {
                            ResultBody(Tone.Paid, it.title, it.detail, earned) {
                                ExplorerUnder(it.signature, onExplorer) { PrimaryButton("Done", close) }
                            }
                        }
                        // Error is NOT Failed. Error means nothing was sent: no transaction exists,
                        // which is why no signature is passed, and why the disc is the neutral one.
                        // An ordinary outcome must not be dressed as an alarm — that is how somebody
                        // pays twice.
                        "error" -> (phase as? Phase.Error)?.let {
                            ResultBody(Tone.Nothing, it.title, it.detail) { RetryRow(close, onRetry) }
                        }
                        // Failed means a transaction went out and the chain refused it. The only red
                        // on this sheet, and the only outcome with a signature worth looking up.
                        "failed" -> (phase as? Phase.Failed)?.let {
                            ResultBody(Tone.Refused, it.title, it.detail) {
                                ExplorerUnder(it.signature, onExplorer) { RetryRow(close, onRetry) }
                            }
                        }
                    }
                }
            }
        }
        // Over the sheet, not under it: the light ends along the bottom edge, which is the
        // sheet. Armed, it waits along the top; when the other phone arrives it parts, runs
        // down the sides and stays. It leaves with the sheet — the fade is read while drawing,
        // for the same reason everything else that moves per frame here is.
        TapGlow(
            waiting = peer == null && inPerson,
            touched = peer != null && inPerson,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = scrim.value * (1f - dismissFraction()) },
        )
    }
    }
    }
    }
}

/** How a sheet can have been opened by two phones actually touching. See `cameFrom`. */
private val InPerson = setOf("nfc", "hce", "app", "tap-button")

/**
 * The sheet before anybody is on the other end: open, radio on, waiting for the phones to meet.
 *
 * Pressing "Pay via Tap" used to arm the radio silently and leave the home screen looking
 * exactly as it had a moment earlier — nothing said the phone was now listening, or that the
 * next thing to do was physical. This is that sentence, and it holds the place the other
 * person's name will appear.
 *
 * The pulse is the only moving thing on it. It is doing a job: NFC has no progress to report
 * and about four centimetres of range, so the one honest signal is "still listening".
 */
@Composable
private fun ArmedBody(onCancel: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "armed")
    val wave by pulse.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart),
        label = "wave",
    )
    // Every centred state on this sheet is the same stack: disc, 16, headline, 4, the line under
    // it, 24, the actions (`Centered` in ui/components/screens/Tap.tsx). No leading spacer — the
    // sheet's own top padding is the only space above the disc.
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Waiting is the pending tone, and the NFC mark says what kind of waiting it is.
        StatusDisc(Tone.Pending, size = 72.dp, mark = { HiIcon(Hi.Nfc, D.White, 40.dp) })
        Spacer(Modifier.height(Space.row))
        Text("Hold your phones together", color = D.Black, style = Type.title, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.tight))
        Text(
            "Back to back, until they buzz.",
            color = D.Quiet, style = Type.caption, textAlign = TextAlign.Center,
            // `mx-auto max-w-xs`: a maximum, not an inset, so a longer line in another language
            // uses the width it needs up to 320 and still sits centred.
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(24.dp))
        // Three dots, each swelling in turn. The wave is in the SIZE, not the opacity: the two
        // pale rings that used to pulse here were a wash of a fill colour, which is the one thing
        // the palette forbids. Solid blue at full strength says the same thing and is allowed.
        Row(Modifier.padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { i ->
                val swell = 1f + .4f * sin(Math.PI * ((wave + i * .18f) % 1f)).toFloat()
                Box(
                    Modifier.size(8.dp)
                        .graphicsLayer { scaleX = swell; scaleY = swell }
                        .background(D.Blue, CircleShape),
                )
            }
        }
        SecondaryButton("Cancel", onCancel, Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.tight))
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
        Spacer(Modifier.width(Space.inline))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // One line, shortened with "…" if it must be. The "VERIFIED SEEKER" pill that used to
                // sit here didn't fit beside a name on a narrower phone and cut "Pay Seeker" to
                // "Pay"; a verified mark after the name says the same, and the device page says why.
                Text(title, color = D.Black, style = Type.heading,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (seeker) {
                    Spacer(Modifier.width(Space.tight))
                    HiIcon(Hi.Verified, D.BlueInk, 20.dp)
                }
            }
            // An address is read a character at a time, so it is set in the mono step.
            Text("${peer.address.take(6)}…${peer.address.takeLast(6)}", color = D.Quiet, style = Type.mono, maxLines = 1)
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
            // 17 on a 46dp disc is the sandbox's `size * 0.38` (`Avatar` in ui/components/ui/Bits.tsx):
            // the initial is fitted to the circle and moves with it, so it is not a scale step.
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
    /** How the sheet was opened, for the line under an incoming ask. */
    cameFrom: String? = null,
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
                    reward = if (peer is TapPayload.Request) reward else null,
                    cameFrom = cameFrom)
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
    /** How the sheet was opened, for the line under an incoming ask. */
    cameFrom: String? = null,
) {
    if (peer is TapPayload.Request) {
        val asked = peer.request.amount
        // How the ask reached you. Only said when we know — the device page has the same rule,
        // and a guess about how two phones met is not worth printing above a Pay button.
        val met = when (cameFrom) {
            "nfc", "hce", "app" -> "In person, by tap · "
            "server", "notification" -> "Through AirPay, after a tap · "
            else -> ""
        }
        // The ask, centred, with the amount as the hero: `Asking` in
        // ui/components/screens/Tap.tsx. The amount is the `amount` step, not `display` — one
        // amount size on every state of this sheet, so the ask and the screen that waits for it
        // to be paid show the same number at the same size.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Asks you for", color = D.Quiet, style = Type.caption)
            Spacer(Modifier.height(Space.tight))
            Text(
                moneyOr(asked, peer.request.symbol), color = D.Black, style = Type.amount,
                maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                modifier = Modifier.shared("amount"),
            )
            Spacer(Modifier.height(Space.tight))
            Text("${met}Solana mainnet", color = D.Quiet, style = Type.caption, textAlign = TextAlign.Center)
        }
        RewardLine(reward)
        Spacer(Modifier.height(24.dp))
        PrimaryButton(working ?: "Pay", onReview,
            Modifier.fillMaxWidth().shared("primary", bounds = true), loading = working != null)
        // Two different answers: "Later" keeps it in Requests and tells them nothing; "Decline"
        // is a no, and their phone is told so they stop waiting.
        AnimatedVisibility(visible = working == null) {
            Column {
                Row(Modifier.padding(top = Space.lead), horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
                    SecondaryButton("Decline", onDecline, Modifier.weight(1f))
                    SecondaryButton("Later", onLater, Modifier.weight(1f))
                }
                // Said under the answer, not above it: somebody looking at a stranger's ask is
                // deciding whether to open their wallet, so what this app cannot reach is part
                // of the decision.
                Text(
                    "AirPay never holds your keys.", color = D.Quiet, style = Type.caption,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = Space.lead),
                )
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
    Spacer(Modifier.height(Space.row))
    PrimaryButton(
        working ?: "Pay",
        onReview, Modifier.fillMaxWidth().shared("primary", bounds = true), enabled = valid && enough, loading = working != null,
    )
    AnimatedVisibility(visible = working == null) {
        TextLink("Ask instead", { onRequest(amount, chosen) }, enabled = valid, modifier = Modifier.shared("secondary", bounds = true))
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
        Spacer(Modifier.height(Space.inline))
        Row(verticalAlignment = Alignment.Bottom) {
            // The `display` step, exactly as on the keypad, so the number moves between the two
            // screens without changing shape. It is the biggest thing on the sheet, and the step
            // is a maximum: nothing here is larger.
            Text(
                amount, color = D.Black, style = Type.display, softWrap = false, maxLines = 1,
                modifier = Modifier.weight(1f, fill = false).shared("amount"),
            )
            Spacer(Modifier.width(Space.inline))
            // `title` here against `heading` on the keypad: the symbol still grows as the review
            // opens, and both ends of that growth are scale steps.
            Text(token.symbol, color = D.Black, style = Type.title,
                modifier = Modifier.padding(bottom = 6.dp).shared("symbol-${token.token}", bounds = true))
        }
        Text("to ${peer.label.ifBlank { "them" }}", color = D.Quiet, style = Type.caption)

        Spacer(Modifier.height(Space.row))
        DetailRow("Pay with") {
            TokenImage(token, 20.dp)
            Spacer(Modifier.width(Space.inline))
            Value(token.symbol)
        }
        DetailRow("To") {
            MiniAvatar(brand, peer.label)
            Spacer(Modifier.width(Space.inline))
            Value(peer.label.ifBlank { "Unknown phone" })
        }
        DetailRow("Address") { Value("${peer.address.take(6)}…${peer.address.takeLast(6)}") }
        DetailRow("Network") { Value("Solana mainnet") }
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
        // No well behind either line: both used to sit in one, and one of them was a 7% wash of
        // D.Blue, which is the shape of tint the palette forbids. What is earned goes on the
        // highlight instead, like a marker pen. The refusal is a reason, not a prize, so it takes
        // no mark at all.
        Row(
            Modifier.fillMaxWidth().padding(top = Space.lead),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HIcon(if (earns) R.drawable.hi_gift else R.drawable.hi_info, if (earns) D.Black else D.Quiet, 18.dp)
            Spacer(Modifier.width(Space.inline))
            if (earns) Marked("Earns ${reward?.skr} SKR")
            else Text(why.orEmpty(), color = D.Quiet, style = Type.body)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = D.Quiet, style = Type.body, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, content = value)
    }
}

/** A row's value — money as often as not, so tabular: digits must not shift as they change. */
@Composable
private fun Value(text: String, strong: Boolean = false) =
    Text(text, color = D.Black, style = if (strong) ValueStrong else ValueText, maxLines = 1)

private val ValueText = Type.body.copy(fontFeatureSettings = "tnum")
private val ValueStrong = ValueText.copy(fontWeight = FontWeight.Bold)

/**
 * A value worth noticing, on the highlight — the marker pen that replaced the tints.
 *
 * Nothing that uses this is a failure: no money moved and nothing was refused, so none of it may
 * take the red. `D.Highlight` only ever sits behind ink text; alone on white it is invisible at
 * 1.15:1, so it is never a dot, a line, a ring or a border.
 */
@Composable
private fun Marked(text: String) = Text(
    text, color = D.Black, style = Type.body, maxLines = 1,
    modifier = Modifier.background(D.Highlight, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
)

/** A small version of the header avatar: their phone's mark, or their initial. */
@Composable
private fun MiniAvatar(brand: String?, label: String) {
    val look = Brand.look(brand)
    Box(Modifier.size(22.dp).clip(CircleShape).background(look?.colour ?: D.Black), contentAlignment = Alignment.Center) {
        when {
            // Fitted to the 22dp disc, like the header avatar, so not a scale step either.
            // `Type.micro` would also be 11, but its +1.5sp tracking shoves a single centred
            // letter off centre.
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
                    Spacer(Modifier.width(Space.tight))
                    HiIcon(Hi.Verified, D.BlueInk, 18.dp)
                }
                Genesis.Check.Unreachable -> Value("Couldn't check")
                // The one warning here: the phone claimed a Genesis token its wallet doesn't hold.
                // Marked, not reddened — red on this sheet means a transaction the chain refused,
                // and nothing here has even been attempted.
                Genesis.Check.None -> if (peer.genesis != null) Marked("Claimed, not held")
                else Value("Not a Seeker")
            }
        }
        DetailRow("Phone") {
            MiniAvatar(brand, peer.label)
            Spacer(Modifier.width(Space.inline))
            Value(Brand.name(brand) ?: "Unknown")
        }
        when (cameFrom) {
            "nfc", "hce", "app" -> DetailRow("Met") { Value("In person, by tap") }
            "server", "notification" -> DetailRow("Met") { Value("Through AirPay, after a tap") }
        }
        DetailRow("With you") {
            Value(when {
                history == null || history.taps <= 1 -> "First time"
                // Through Money, because `"%.4f".format(…)` is HALF_UP: it rounded a lifetime
                // total UP, and "paid 0.0001 SOL" for 0.00009 is a figure nobody paid. The
                // trailing zeros still come off — "paid 0.25 SOL", not "0.2500" — and dust
                // survives the trim because "<0.0001" ends in a 1.
                history.totalPaid > 0 -> "${history.taps} taps · paid " +
                    Money.tokenAmount(java.math.BigDecimal(history.totalPaid.toString()), "SOL")
                        .trimEnd('0').trimEnd('.') + " SOL"
                else -> "${history.taps} taps since ${fmt.format(java.util.Date(history.firstTapMillis))}"
            })
        }
        DetailRow("Wallet") {
            val w = wallet
            when {
                w == null -> Value("Checking…")
                w.count < 0 -> Value("Couldn't check")
                // Marked rather than reddened, for the same reason as "Claimed, not held" above.
                w.count == 0 -> Marked("Brand new")
                // Only the first thousand transactions are read, so a busier wallet is "before" that date.
                else -> Value(w.firstMillis?.let { (if (w.count >= 1000) "Before " else "Since ") + fmt.format(java.util.Date(it)) } ?: "In use")
            }
        }

        Spacer(Modifier.height(Space.row))
        Text("WALLET ADDRESS", color = D.Quiet, style = Type.micro)
        Spacer(Modifier.height(Space.tight))
        AddressRow(peer.address)
        Spacer(Modifier.height(Space.tight))
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
        // No grey well behind it (ui/PORT.md §9): the containers are retired, so this is a row on
        // the white sheet, bounded by the label above it and the space below. Losing the box also
        // loses its 14dp inset, so the address now starts on the sheet's own gutter and the two
        // buttons end on it — the 48dp hit areas carry their own optical padding.
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `Type.mono` is Geist Mono — the app's own monospace, rather than whatever the phone
        // happens to call Monospace.
        Text(
            "${address.take(8)}…${address.takeLast(8)}", color = D.Black, style = Type.mono,
            maxLines = 1, softWrap = false, modifier = Modifier.weight(1f),
        )
        RoundIcon(if (copied) R.drawable.hi_tick else R.drawable.hi_copy, if (copied) "Copied" else "Copy address") {
            Haptics.select(context)
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(address))
            copied = true
        }
        Spacer(Modifier.width(8.dp))
        RoundIcon(R.drawable.hi_link_out, "View this address on the Solana explorer") {
            Haptics.select(context)
            runCatching { uri.openUri("https://explorer.solana.com/address/$address") }
        }
    }
}

/** A large round icon button that presses in and clicks. */
@Composable
private fun RoundIcon(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        // An icon-only button: `onClickLabel` names the action, `contentDescription` names the
        // button. Without the second, a screen reader announces it as an unnamed button.
        Modifier.then(PressScale(interaction)).size(48.dp).clip(CircleShape).background(D.White)
            .semantics { contentDescription = label }
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

/**
 * An amount and its token code, as one string: "2.00 USDC".
 *
 * The amounts on this sheet arrive as decimal strings from the wire or the keypad, and every one
 * of them goes through [Money] — locale grouping, cut never rounded up, dust said as "<", and a
 * no-break space before the code. A string that is not a decimal at all is shown as it came
 * rather than swallowed: the sheet is about money, so it never has nothing to say about it.
 */
private fun moneyOr(amount: String, symbol: String): String =
    runCatching { Money.token(java.math.BigDecimal(amount), symbol) }
        .getOrDefault("$amount $symbol")

@Composable
private fun AmountDisplay(
    amount: String, note: String?, token: String,
    chip: (@Composable (Modifier) -> Unit)? = null, warn: Boolean = true,
) {
    // The keypad's own amount, and only that: `display` is the keypad step (ui/PROJECT.md §3).
    // The centred variant this used to have belonged to the incoming ask, which now draws its
    // own amount at the `amount` step, because the sheet shows one amount size per state.
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
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
            // The `display` step: Geist, tabular, 60sp. It is a maximum — the biggest thing on
            // the sheet and the biggest thing in the app — and it is money, so it is tabular:
            // digits must not shift sideways as they are typed.
            Text(
                amount.ifBlank { "0" },
                softWrap = false, maxLines = 1,
                color = if (amount.isBlank()) D.Quiet else D.Black,
                style = Type.display,
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
            Spacer(Modifier.width(Space.inline))
            if (chip == null) {
                Text(token, color = D.Quiet, style = Type.heading,
                    modifier = Modifier.padding(bottom = 6.dp).shared("symbol-$token", bounds = true))
            } else {
                chip(Modifier.padding(bottom = 2.dp))
            }
        }
        // Only speaks up when there's something to say, in a slot that holds its place whether or
        // not it does — a minimum rather than a fixed height, so at 2x text size it grows instead
        // of clipping.
        //
        // Not red. A balance that is short and an amount with too many decimals are not failures:
        // nothing was sent and nothing was refused. Plain ink says it without raising an alarm.
        Text(
            note ?: "", color = if (warn) D.Black else D.Quiet, style = Type.caption,
            modifier = Modifier.heightIn(min = 16.dp),
        )
    }
}

@Composable
private fun Keypad(enabled: Boolean, onKey: (Char) -> Unit) {
    val keys = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', '<')
    Column {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
                row.forEach { k -> Key(k, enabled, Modifier.weight(1f)) { onKey(k) } }
            }
        }
    }
}

/** The keypad's digits are money: Geist, tabular, at the `title` step's size. */
private val KeyDigit = Type.title.figure()

/** A key lights up grey while held — no ripple, just the pressed state, like the mock. */
@Composable
private fun Key(k: Char, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        // A minimum, not a fixed height: at 2x text size the digit grows and the row grows with it.
        modifier.heightIn(min = 58.dp).clip(RoundedCornerShape(16.dp))
            // Backspace is the one key with no word in it, so it is the one that needs a name.
            .then(if (k == '<') Modifier.semantics { contentDescription = "Delete the last digit" } else Modifier)
            .clickable(remember { MutableInteractionSource() }, indication = KeyPress, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (enabled) D.Black else D.Quiet
        if (k == '<') HiIcon(Hi.Backspace, tint, 26.dp)
        else Text(k.toString(), color = tint, style = KeyDigit)
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
        // Not one of the money states, so no status disc — and no pale well behind the mark
        // either. The bare icon at the hero size, in the ink shade so it holds on white.
        HiIcon(Hi.Wallet, D.BlueInk, 40.dp)
        Spacer(Modifier.height(Space.row))
        Text("Connect a wallet to pay or ask", color = D.Black, style = Type.title, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.tight))
        Text(
            "AirPay never holds your keys.", color = D.Quiet, style = Type.caption,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(working ?: "Connect wallet", onConnect, loading = working != null)
    }
}

// ---------------------------------------------------------------------------------- outcomes

@Composable
private fun WaitingBody(label: String, amount: String, symbol: String, note: String?, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // The amount stays the loudest thing here: what is owed outranks the fact that it has not
        // arrived. The state sits under it as a disc and its words, side by side.
        //
        // Through Money, so this reads the same as every other amount in the app: the phone's
        // grouping, cut rather than rounded up, and the no-break space before the code so the
        // number and "USDC" can never wrap apart. The fallback is for a string that isn't a
        // decimal at all \u2014 showing what we were handed beats showing nothing.
        Text(moneyOr(amount, symbol), color = D.Black, style = Type.amount)
        Spacer(Modifier.height(Space.row))
        // The disc and its words are one group, centred as a group with the words start-aligned —
        // not stretched across the sheet, which would read as a left-aligned row under a centred
        // amount.
        Row(verticalAlignment = Alignment.CenterVertically) {
            // "Later" lands here too, and it is still the pending disc — a deferral is not an
            // ending, the money can still arrive, so only the words change. The clock keeps
            // running because it is still true that we are waiting.
            StatusDisc(Tone.Pending, mark = { TickingClock(20.dp, D.White) })
            Spacer(Modifier.width(Space.inline))
            Column {
                Text(note ?: "Waiting for ${label.ifBlank { "them" }}", color = Tone.Pending.ink, style = Type.body)
                Spacer(Modifier.height(Space.tight))
                Text(
                    if (note != null) "Still watching for it · this stays open"
                    else "Still watching for it · you can close this",
                    color = D.Quiet, style = Type.caption,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        SecondaryButton("Close", onClose, Modifier.fillMaxWidth())
    }
}

/**
 * A clock that actually keeps time, instead of an icon breathing in and out.
 *
 * The second hand jumps once a second and settles, the way an escapement does — the overshoot
 * is a low damping ratio on the spring, not a decoration. Waiting is the one screen where a
 * clock means something, so it should read as one: someone glancing at it can tell it is
 * running rather than merely animated.
 *
 * It is now the mark inside the pending [StatusDisc] rather than a disc of its own, so it is
 * drawn on the same 24-unit grid as the other marks, at the stroke a disc asks for, in white on
 * the tone's fill. The tinted well it used to sit in — a 10% wash of the fill — is gone; the
 * quarter marks went with it, because at 20dp they were below a pixel.
 */
@Composable
private fun TickingClock(size: Dp = 20.dp, tint: Color = D.White) {
    var ticks by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        // Aligned to the wall clock so the first tick is not a fraction of a second late.
        delay(1_000 - System.currentTimeMillis() % 1_000)
        while (true) {
            ticks++
            delay(1_000)
        }
    }
    val second by animateFloatAsState(
        targetValue = ticks * 6f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 2200f),
        label = "second",
    )
    Canvas(Modifier.size(size)) {
        val unit = this.size.minDimension / 24f
        scale(unit, unit, pivot = Offset.Zero) {
            // The face is the sandbox's clock mark: r = 8 on the 24 grid, centred.
            val c = Offset(12f, 12f)
            val w = markStroke(size, disc = true)
            drawCircle(tint, radius = 8f - w / 2f, center = c, style = Stroke(w))
            // The hour hand creeps; at this size it reads as "a clock" rather than "a dial".
            val hour = Math.toRadians((second / 60f) * 0.5)
            drawLine(tint, c, Offset(c.x + (3.5f * sin(hour)).toFloat(), c.y - (3.5f * cos(hour)).toFloat()),
                strokeWidth = w, cap = StrokeCap.Round)
            val a = Math.toRadians(second.toDouble())
            drawLine(tint, c, Offset(c.x + (5.5f * sin(a)).toFloat(), c.y - (5.5f * cos(a)).toFloat()),
                strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

/** The result line carries an amount, so it is tabular even at the caption step. */
private val DetailText = Type.caption.copy(fontFeatureSettings = "tnum")

/**
 * How a payment ended: the outcome's disc, its headline in that outcome's ink, and the line that
 * says what happened to the money.
 *
 * [tone] is the whole difference between the endings, and it carries more than colour — see the
 * doc on [Tone]. Nothing here decides which tone applies; the caller does, from the phase.
 */
@Composable
private fun ResultBody(
    tone: Tone, title: String, detail: String, earned: Rewards.Earned? = null,
    actions: @Composable () -> Unit,
) {
    // The mark pops in on a spring; the success haptic fires as the phase changes, so the two
    // land together.
    val pop = remember { Animatable(.5f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, Arrive) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        StatusDisc(tone, Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }, size = 72.dp)
        Spacer(Modifier.height(Space.row))
        // The headline takes the tone's ink, so the colour and the words say the same thing. That
        // is what keeps "Nothing was sent" from reading as a failure, and keeps the chain's
        // refusal from reading as an ordinary end.
        Text(title, color = tone.ink, style = Type.title, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.tight))
        Text(
            detail, color = D.Quiet, style = DetailText, textAlign = TextAlign.Center,
            // `mx-auto max-w-xs`, like every other centred state: the line wraps before it runs
            // the full width of the sheet, so a two-line detail reads as one block.
            modifier = Modifier.widthIn(max = 320.dp),
        )
        // `mt-6` — the gap between the line under the headline and whatever comes next, which is
        // the reward when there is one and the buttons when there isn't. The explorer link is no
        // longer a blue phrase floating here: it is a full-width button in the actions below,
        // stacked under the way out, because two 19sp labels in half-width buttons wrap
        // ("View on / explorer").
        Spacer(Modifier.height(24.dp))
        // Arrives seconds after the ✓, because the server only decides once the payment is on
        // the chain. It slides in rather than appearing, so it reads as something that happened
        // rather than something that was always there. On the highlight, not in a blue wash, and
        // with no icon beside it — the marker pen is the whole mark.
        AnimatedVisibility(visible = earned != null, enter = fadeIn(tween(260)) + expandVertically(spring(dampingRatio = 0.75f, stiffness = 158f))) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Marked("You earned ${earned?.skr ?: 0} SKR")
                Spacer(Modifier.height(24.dp))
            }
        }
        actions()
    }
}

@Composable
private fun RetryRow(onClose: () -> Unit, onRetry: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
        SecondaryButton("Close", onClose, Modifier.weight(1f))
        PrimaryButton("Try again", onRetry, Modifier.weight(1f))
    }
}

/**
 * [primary] with "View on explorer" stacked under it, when there is a transaction to look up.
 *
 * Stacked rather than side by side: both labels are 19sp — every button label is — and
 * "View on explorer" in a half-width button wraps to "View on / explorer". The way out leads;
 * the explorer is for whoever wants proof, so it sits second and takes the outline.
 */
@Composable
private fun ExplorerUnder(signature: String?, onExplorer: (String) -> Unit, primary: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        primary()
        if (signature != null) {
            Spacer(Modifier.height(Space.inline))
            SecondaryButton("View on explorer", { onExplorer(signature) }, Modifier.fillMaxWidth())
        }
    }
}

// ----------------------------------------------------------------------------------- pieces

/**
 * A payment state, as a colour and a shape. Ported from `StatusDisc` / `statusInk` in
 * ui/components/ui/Bits.tsx, and these five are the whole vocabulary of outcomes on this sheet.
 *
 * [fill] is the disc; [ink] is the words next to or under it, and the two are never swapped. A
 * fill is about 3.3:1 with white, which passes for a disc carrying a thick mark and fails for a
 * sentence — so a label under a disc takes the tone's ink, never its fill.
 *
 * All five fills share one brightness on purpose, which means the **shape inside the disc** is
 * what a colour-blind eye reads. That is why a disc is never drawn without its mark, and why the
 * mark is stroked at `markStroke(size, disc = true)` rather than at the weight an icon of that
 * size would otherwise take: it is carrying the meaning, so it has to hold at a glance.
 *
 * [Nothing] and [Refused] are the pair that must never converge. Nothing means no transaction
 * exists; Refused means one went out and the chain turned it down. Grey and red, and only
 * Refused is ever red.
 */
private enum class Tone(
    val fill: Color,
    val ink: Color,
    /** The mark, as `pathData` on the 24-unit icon grid. */
    val mark: List<String>,
    /** A filled dot, where the mark needs one that holds at any stroke. */
    val dot: Offset? = null,
) {
    /** A payment that landed. */
    Paid(D.Success, D.SuccessInk, listOf("M5 14L8.5 17.5L19 6.5")),

    /** Working, waiting, deferred — anything still in flight. A clock, because time is passing. */
    Pending(D.Blue, D.BlueInk, listOf("M4.0,12.0a8.0,8.0 0 1,0 16.0,0a8.0,8.0 0 1,0 -16.0,0", "M12 8V12L14.5 14")),

    /**
     * A deadline the person has to meet, and nothing else: the approval window and its countdown.
     *
     * No site on this sheet yet, and deliberately not invented one — `Phase.Working` carries a
     * line of text but no deadline, so there is nothing honest to count down from. Defined here
     * so that when the window gets a clock, orange is waiting for it and goes nowhere else.
     */
    Act(D.Orange, D.OrangeInk, listOf("M12 6.5V13"), dot = Offset(12f, 17.5f)),

    /**
     * Nothing happened: no money moved and none was going to.
     *
     * Grey, because this must not look like a failure. It is an ordinary outcome, and dressing it
     * as an alarm is how somebody pays a second time for a payment that never went out.
     */
    Nothing(D.Neutral, D.Quiet, listOf("M7 12H17")),

    /**
     * A transaction went out and the chain refused it. The only red on this sheet — and the only
     * outcome with a signature, because something really did happen.
     */
    Refused(D.Danger, D.DangerInk, listOf("M18 6L6.00081 17.9992M17.9992 18L6 6.00085")),
    ;

    /**
     * The marks, parsed once each.
     *
     * Building a `Path` from its string inside a draw scope is work on a frame, and this sheet
     * has paid for that mistake before — the same reason the drag offset is read while drawing
     * and never while composing.
     */
    val paths: List<Path> by lazy { mark.map { PathParser().parsePathString(it).toPath() } }
}

/**
 * The disc: a bright circle carrying a thick white mark.
 *
 * [mark] replaces the tone's own shape where a live one says more — the waiting clock, whose
 * hands actually move. It does not change the rule: there is always a mark.
 */
@Composable
private fun StatusDisc(
    tone: Tone,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    mark: (@Composable () -> Unit)? = null,
) {
    // 20 in a row disc, 40 in the 72dp hero — the icon scale's two disc sizes.
    val icon = if (size >= 72.dp) 40.dp else 20.dp
    Box(modifier.size(size).background(tone.fill, CircleShape), contentAlignment = Alignment.Center) {
        if (mark != null) {
            mark()
        } else {
            Canvas(Modifier.size(icon)) {
                // The paths are written on the 24-unit grid, so the whole drawing is scaled into
                // place — which scales the stroke with it, and `markStroke` is a grid width.
                val unit = this.size.minDimension / 24f
                scale(unit, unit, pivot = Offset.Zero) {
                    val stroke = Stroke(
                        width = markStroke(icon, disc = true),
                        cap = StrokeCap.Round, join = StrokeJoin.Round,
                    )
                    tone.paths.forEach { drawPath(it, D.White, style = stroke) }
                    tone.dot?.let { drawCircle(D.White, radius = 1.6f, center = it) }
                }
            }
        }
    }
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
            // An ink outline on white, not a grey body — the same move `SecondaryButton` made
            // when the containers were retired (ui/PORT.md §9). The chip is a control, so it
            // needs a findable edge; `D.Line` could not be that edge, because a divider colour
            // is decorative and never the only boundary of anything.
            Modifier.clip(RoundedCornerShape(50)).background(D.White)
                .border(1.5.dp, D.Black, RoundedCornerShape(50)).clickable { open = true }
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
                    Spacer(Modifier.width(Space.inline))
                    Text(t.symbol, color = D.Black, style = Type.heading,
                        modifier = Modifier.shared("symbol-${t.token}", bounds = true))
                }
            }
            Spacer(Modifier.width(2.dp))
            HiIcon(Hi.ArrowDown, D.Black, 18.dp)
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
                Spacer(Modifier.width(Space.inline))
                Column(Modifier.weight(1f)) {
                    Text(t.symbol, color = D.Black, style = Type.body, maxLines = 1)
                    Text(t.name, color = D.Quiet, style = Type.caption, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Text(t.shown, color = D.Black, style = ValueText, maxLines = 1)
                Spacer(Modifier.width(8.dp))
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
internal fun TokenImage(t: Tokens.Holding, size: Dp, shape: Shape = CircleShape) {
    val round = Modifier.size(size).clip(shape)
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
            // Fitted to the logo circle, like the avatars' initials — a fixed composition, not a
            // scale step: the letter has to fill whatever size the caller asked for.
            Text(t.symbol.take(1).uppercase(), color = D.White, fontSize = (size.value * .42f).sp, fontWeight = FontWeight.Bold, fontFamily = Display)
        }
    }
}

/** Remote logos — token mints and shop brands alike — fetched once and kept small. */
internal object LogoCache {
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
