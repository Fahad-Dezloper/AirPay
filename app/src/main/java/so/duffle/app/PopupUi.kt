package so.duffle.app

import androidx.compose.foundation.layout.wrapContentHeight
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.keyframes
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
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.util.lerp
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
    /** Gives up on a payment that was signed with no connection and never handed over. */
    onAbandon: () -> Unit = {},
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
    /** From the armed sheet: this phone wants to be paid, so open the request screen. */
    onRequestInstead: () -> Unit = {},
    /** Every linked wallet, for the armed sheet's card and switcher. */
    wallets: List<Linked> = emptyList(),
    /** Which of [wallets] pays, by [Linked.id]: two apps can hold the same address. */
    activeWallet: String? = null,
    onWallet: (Linked) -> Unit = {},
    /** A phone came near and nothing usable was read. */
    armedNote: String? = null,
    /** A copy of the screen underneath, drawn blurred. Null means only the dim. */
    backdrop: androidx.compose.ui.graphics.ImageBitmap? = null,
    /** Set when AirPay can be made the phone's wallet app, so double-press power opens this. */
    onWalletApp: (() -> Unit)? = null,
) {
    // Hoisted so the amount survives the switch from typing to paying: the same number stays on
    // screen while the button below it reports progress.
    //
    // All four belong to the person on the sheet, so they start again when that person changes.
    // The sheet outlives one payment (a second tap, a scan, the power button all land on the
    // same one), and the next person used to open straight on the review step with the last
    // person's amount already filled in.
    val sheetFor = peer?.address
    var amount by rememberSaveable(sheetFor) { mutableStateOf(previewAmount ?: "") }
    var token by rememberSaveable(sheetFor) { mutableStateOf("SOL") }
    // Pay opens a review of the payment; only "Confirm and pay" starts it. Hoisted so "Try again"
    // after a failure comes back to the review, not the keypad.
    var confirming by rememberSaveable(sheetFor) { mutableStateOf(false) }
    // The page about the other phone, opened by tapping the header.
    var aboutThem by rememberSaveable(sheetFor) { mutableStateOf(false) }
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
    // True once the sheet has finished rising. The card above it waits for this.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // Wait for the first measurement, then rise. Written so that a restart simply carries on
        // from wherever the sheet is — an earlier version gave up on restart and left the sheet
        // parked below the screen, invisible, with the app still in front.
        val h = snapshotFlow { sheetHeight }.first { it > 0f }
        if (!shown) {
            offset.snapTo(h)
            shown = true
        }
        // One thing at a time, in the order the eye should take them: the page behind goes
        // soft where it stands, then the sheet comes up from the bottom, then the card appears
        // above it. All three at once read as the whole screen sliding up.
        scrim.animateTo(1f, tween(170))
        offset.animateTo(0f, SheetRise)
        settled = true
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
            // Never above its resting edge: pulled up, it simply stays put. Downward it follows
            // the finger.
            offset.snapTo((offset.value + delta).coerceAtLeast(0f))
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
            .drawBehind { drawRect(Color.Black.copy(alpha = (if (backdrop != null) .28f else .5f) * scrim.value * (1f - dismissFraction()))) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // The screen underneath, out of focus. It fades with the dim, so it arrives and leaves
        // with the sheet. With no copy to blur (opened over another app, or from the power
        // button) the dim is simply deeper.
        if (backdrop != null) {
            Image(
                backdrop, contentDescription = null, contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize().blur(14.dp)
                    .graphicsLayer { alpha = scrim.value * (1f - dismissFraction()) }
                    // Dimmed as well as blurred: on a white page the white sheet had no edge.
                    .drawWithContent { drawContent(); drawRect(Color.Black.copy(alpha = .3f)) },
            )
        }
        val payWith = wallets.firstOrNull { it.id == activeWallet } ?: wallets.firstOrNull { it.address == myAddress }
        Column(Modifier.fillMaxWidth()) {
        // The wallet that will pay, held out above the sheet like a card over a terminal. It
        // rides with the sheet (same travel, same fade) and steps aside when somebody arrives.
        AnimatedVisibility(
            visible = peer == null && payWith != null && settled,
            // It appears in place, growing a little, rather than travelling up with the sheet.
            enter = fadeIn(tween(200)) + scaleIn(spring(0.7f, 320f), initialScale = 0.9f),
            exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.94f),
        ) {
            Box(
                Modifier.fillMaxWidth().padding(horizontal = Tray.PAD).padding(bottom = 18.dp)
                    .graphicsLayer {
                        // Follows the sheet when it is dragged or sent away, which is the only
                        // time the sheet is anywhere but at rest once the card is showing.
                        translationY = offset.value.coerceAtLeast(0f)
                        alpha = 1f - dismissFraction()
                    }
                    // A touch on the card is not a touch on the dim behind it.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                AnimatedContent(
                    targetState = payWith,
                    transitionSpec = {
                        (slideInVertically(spring(0.82f, 320f)) { it / 3 } + fadeIn(tween(180))) togetherWith
                            (slideOutVertically(tween(140)) { -it / 4 } + fadeOut(tween(110)))
                    },
                    contentKey = { it?.id },
                    label = "pay-card",
                ) { w -> if (w != null) PayCard(w) }
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .onSizeChanged { sheetHeight = it.height.toFloat() }
                // Below the screen until it has been measured, so it never flashes in place.
                .graphicsLayer { translationY = if (shown) offset.value.coerceAtLeast(0f) else size.height.toFloat() }
                .draggable(
                    drag, Orientation.Vertical, enabled = shown && !leaving,
                    onDragStopped = { velocity ->
                        // Far enough, or flicked: go. Otherwise settle back where it was.
                        if (offset.value > sheetHeight * .25f || velocity > 1_200f) leave()
                        else offset.animateTo(0f, SheetSettle, initialVelocity = velocity)
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
                    onOpen = if (phase is Phase.Working) null else ({ aboutThem = !aboutThem }), open = aboutThem,
                    // §1.4: only a chain refusal puts the explorer up here, and only when there
                    // really is a signature to look up. Nothing is invented when there isn't —
                    // an icon that leads nowhere is worse than no icon.
                    onExplorer = (phase as? Phase.Failed)?.signature?.let { sig -> { onExplorer(sig) } })
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
                is Phase.HandOver -> "handover"
                is Phase.HandedOver -> "handed"
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
                        "armed" -> ArmedBody(wallets, (wallets.firstOrNull { it.id == activeWallet } ?: wallets.firstOrNull { it.address == myAddress })?.id, armedNote, onWallet, onConnect, onRequestInstead, onWalletApp)
                        // The keypad and the device page both stay built; switching only fades one
                        // out and the other in. Rebuilding the keypad form on the way back — amount,
                        // chip, twelve keys, shared-element setup — cost 85-150ms frames.
                        "form" -> peer?.let { p -> KeepBoth(
                            showSecond = aboutThem && phase !is Phase.Working,
                            first = {
                                Form(p, phase, connected, amount, { amount = it }, token, { token = it }, holdings, onConnect, onPay, onRequest,
                                    { leave(onDecline) }, { leave(onLater) }, confirming, { confirming = it }, covered = aboutThem)
                            },
                            second = { AboutThem(p, history, genesis, wallet, cameFrom, onBack = { aboutThem = false }) },
                        ) }
                        // `waitingNote` is written only when the payer has answered "Later", so
                        // its presence IS the deferral — §1.3 wants two headlines off one state,
                        // not a note pasted over one.
                        "waiting" -> (phase as? Phase.Waiting)?.let {
                            WaitingBody(peer?.label.orEmpty(), it.amount, it.symbol, deferred = waitingNote != null, onClose = close)
                        }
                        // Signed, and going nowhere until the phones touch again.
                        "handover" -> (phase as? Phase.HandOver)?.let {
                            HandOverBody(it.request.label, it.request.amount, it.request.symbol, it.note, onAbandon)
                        }
                        // Pending, not Paid: their phone has it, and nobody here knows yet
                        // whether it landed. A green tick would be a claim this phone cannot make.
                        // Also where a payment that was sent and not yet confirmed lands: that one
                        // has a signature, so it gets the way to look it up.
                        "handed" -> (phase as? Phase.HandedOver)?.let {
                            ResultBody(Tone.Pending, it.title, it.detail) {
                                ExplorerUnder(it.signature, onExplorer) { PrimaryButton("Done", close, Modifier.fillMaxWidth()) }
                            }
                        }
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
                        // on this sheet, and the only outcome with a signature worth looking up —
                        // which §1.4 moves to an icon in the header, so the two buttons here are
                        // Close and Try again and nothing else.
                        "failed" -> (phase as? Phase.Failed)?.let {
                            ResultBody(Tone.Refused, it.title, it.detail) { RetryRow(close, onRetry) }
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
 * ui/PORT-2026-10-02.md §1.1. Pressing "Pay via Tap" used to arm the radio silently and leave
 * the home screen looking exactly as it had a moment earlier — nothing said the phone was now
 * listening, or that the next thing to do was physical. This is that sentence, and it holds the
 * place the other person's name will appear.
 *
 * Everything is centred and **nothing here is a button**: the next move is physical, so the
 * screen shows the gesture rather than offering an alternative to it. The "Cancel" button that
 * used to close the sheet is gone with the redesign — back, the scrim and a downward drag all
 * still dismiss it, and none of them competes with the instruction.
 *
 * Gone too: the status disc, the line under the title and the three pulsing dots. §1.1 is
 * explicit that there is no line and no dots — both survive in the sandbox file as commented-out
 * code and are to be left out. [TapHero] took over the job the pulse was doing (saying the radio
 * is live) and does it by showing what to do with it.
 */
@Composable
private fun ArmedBody(
    wallets: List<Linked>,
    active: String?,
    note: String?,
    onWallet: (Linked) -> Unit,
    onConnect: () -> Unit,
    onRequestInstead: () -> Unit,
    onWalletApp: (() -> Unit)?,
) {
    val mine = wallets.firstOrNull { it.id == active }
    val others = wallets.filter { it.id != active }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (mine != null) {
            // The instruction leads. The card that pays is above the sheet, already held out.
            Text("Hold near to pay", color = D.Black, style = Type.title, textAlign = TextAlign.Center)
        } else {
            // No wallet, so nothing can pay. The one thing to do is said as the one button.
            Text("Connect a wallet to pay", color = D.Black, style = Type.title, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Space.row))
            PrimaryButton("Connect wallet", onConnect, Modifier.fillMaxWidth())
        }
        // A phone came near and could not be read. Said here, where the eyes already are.
        AnimatedVisibility(visible = note != null) {
            Text(
                note.orEmpty(), color = NOTICE_INK, style = Type.caption.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = Space.inline).background(NOTICE_MARK, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        if (others.isNotEmpty()) {
            // Every other wallet, stacked the way cards sit in a wallet: each one full width,
            // showing only its top edge, the next laid over it. One touch brings it to the top.
            // Adding a wallet adds one more edge, not one more full card.
            Spacer(Modifier.height(Space.row))
            Box(
                Modifier.fillMaxWidth().height(STACK_PEEK * others.size + STACK_FOOT)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
            ) {
                others.forEachIndexed { i, w ->
                    Box(Modifier.offset(y = STACK_PEEK * i)) { OtherCard(w) { onWallet(w) } }
                }
                // The last card fades into the sheet instead of being cut off by it, so the
                // stack reads as going on underneath and the button below has clear ground.
                // A long, even fade over the whole of the last card's strip. A short one that
                // reached full white early read as a hard shadow lying across the card.
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(STACK_FOOT)
                        .background(Brush.verticalGradient(
                            0f to D.White.copy(alpha = 0f), 0.35f to D.White.copy(alpha = .18f),
                            0.7f to D.White.copy(alpha = .62f), 1f to D.White,
                        )),
                )
            }
        } else {
            Spacer(Modifier.height(Space.row))
        }

        // The other half of the same moment: this phone is the one being paid. A real button
        // now, on clear ground, where it was a line of text pressed against the cards.
        // A blue pill as wide as its words, like "Scan any QR" on Home. It sits just inside the
        // end of the fade, so the card seems to run on under it rather than stop above it.
        val pill = remember { MutableInteractionSource() }
        Box(
            Modifier.offset(y = if (others.isNotEmpty()) (-10).dp else 0.dp).then(PressScale(pill))
                .clip(CircleShape).background(D.Blue)
                .clickable(pill, indication = null, onClick = onRequestInstead)
                .heightIn(min = 52.dp).padding(horizontal = 28.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Request instead", color = D.White, style = Type.heading) }
        if (onWalletApp != null) {
            // A setting, not a way to pay, so it is the quietest thing on the sheet.
            Text(
                "Open this with a double-press of power", color = D.BlueInk, style = Type.caption,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                // 16 above and below an 18 line: the 48dp a touch needs.
                modifier = Modifier.clickable(onClick = onWalletApp).padding(vertical = 16.dp, horizontal = 8.dp),
            )
        }
        Spacer(Modifier.height(Space.tight))
    }
}

/** What a wallet holds, in dollars: the figure when it lands, and whether it is still on its way. */
@Composable
private fun worthOf(address: String): Pair<Double?, Boolean> {
    val context = LocalContext.current
    val state by produceState<Pair<Double?, Boolean>>(initialValue = null to true, address) {
        value = runCatching { loadWorth(context, address)?.usd }.getOrNull() to false
    }
    return state
}

/**
 * The wallet in use, as the card being held out: the same card it is on the Wallets screen, so
 * Seeker looks like Seeker and Phantom like Phantom. The corner the Wallets screen gives to the
 * "Active" pill carries the contactless sign here, pulsing, which is what says the radio is live.
 */
@Composable
private fun PayCard(w: Linked) {
    val (usd, loading) = worthOf(w.address)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val lum = 0.2126f * w.colour.red + 0.7152f * w.colour.green + 0.0722f * w.colour.blue
    // The foot of Seeker's card is mint, so its ink is black whatever the top of the card is.
    val ink = if (w.isSeeker || lum > 0.5f) D.Black else D.White
    // Touch the card and it turns over: the back is this wallet's own code, for somebody to
    // scan. Paying and being paid are then the two sides of the one card.
    var turned by remember(w.id) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (turned) 180f else 0f, spring(dampingRatio = 0.72f, stiffness = 180f), label = "turn")
    Box(
        Modifier.fillMaxWidth().height(196.dp)
            .graphicsLayer { rotationY = turn; cameraDistance = 14f * density }
            .clip(RoundedCornerShape(24.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null) { turned = !turned; Haptics.tick(context) },
    ) {
        if (turn <= 90f) {
            WalletFace(
                w, active = false, usd = { usd }, loading = { loading }, lift = { 1f },
                onCopy = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(w.address)) },
                modifier = Modifier.fillMaxSize(),
                corner = { ContactlessPulse(ink) },
            )
        } else {
            // Turned back the right way round, or the code would be a mirror image no camera reads.
            Column(
                Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }
                    .background(D.White).border(1.5.dp, D.Line, RoundedCornerShape(24.dp)).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // The code alone in the middle, as large as the card allows: it is the only
                // thing a camera needs, and the line under it only says whose it is.
                QrCode("solana:${w.address}", Modifier.size(146.dp))
                Spacer(Modifier.height(6.dp))
                Text(w.short, color = D.Quiet, style = Type.mono, maxLines = 1, textAlign = TextAlign.Center)
            }
        }
    }
}

/** How much of each waiting card shows above the one laid over it, and under the last one. */
private val STACK_PEEK = 64.dp
private val STACK_FOOT = 84.dp

/**
 * Another linked wallet, in its own colours and at full size, though only its top edge is seen.
 * Touching it makes it the one that pays.
 */
@Composable
private fun OtherCard(w: Linked, onUse: () -> Unit) {
    val (usd, loading) = worthOf(w.address)
    // Unbounded and top-aligned, so the card keeps its real height and the stack cuts it off, instead of
    // the card squeezing itself into the strip it is given.
    WalletFace(
        w, active = false, usd = { usd }, loading = { loading }, lift = { 0f }, onCopy = onUse,
        modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(196.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onUse),
    )
}

/**
 * The contactless sign in the card's corner, with one soft ring leaving it on a slow beat.
 *
 * The sign is the app's own NFC mark at full strength, so it always reads as the familiar
 * symbol. Three hand-drawn arcs blinking in turn did not: on the card they looked like a
 * loading spinner. Only the ring moves, which says "live" without redrawing the symbol.
 */
@Composable
internal fun ContactlessPulse(ink: Color) {
    val beat by rememberInfiniteTransition(label = "nfc").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "ring",
    )
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            // Grows from the sign's own size to the edge of its box, fading as it goes.
            val r = size.minDimension / 2f
            drawCircle(ink, radius = r * (0.55f + 0.45f * beat), alpha = 0.22f * (1f - beat))
        }
        HiIcon(Hi.Nfc, ink, 26.dp)
    }
}

/**
 * Where to aim, as a picture rather than a sentence: ui/PORT-2026-10-02.md §1.1.
 *
 * Corner 24, padding 12, on the artwork's own pale blue. [TIP_GROUND] and [TIP_DISC] are
 * illustration pigments, not palette colours — the sanctioned exception for a tint *under* an
 * illustration (ui/PROJECT.md §3), the same licence the Seeker's own colours take inside
 * [TapHero]. The text on it is ink, not quiet: quiet on that blue is 4.4:1.
 */
@Composable
private fun NfcTip() {
    Row(
        Modifier.fillMaxWidth().background(TIP_GROUND, RoundedCornerShape(24.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The top half of the phone, zoomed in on the NFC highlight, standing in a disc a shade
        // deeper than the card. The disc cuts it off at the bottom and the sides; its top rises
        // 8 past the disc, which is what makes it read as a thing and not a photo.
        Box(Modifier.size(80.dp)) {
            Box(Modifier.fillMaxSize().background(TIP_DISC, CircleShape))
            Image(
                painterResource(R.drawable.nfc_tip), contentDescription = null,
                // 80 across by 88 tall, starting 8 above the disc, with the bottom 40 rounded
                // away. `rounded-b-full` on an 80-wide box clamps to a 40 radius, which is
                // exactly the disc's own bottom semicircle — so the photo ends on the disc's edge
                // rather than near it.
                modifier = Modifier.offset(y = (-8).dp).size(width = 80.dp, height = 88.dp)
                    .clip(RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp)),
                alignment = Alignment.TopCenter,
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.width(Space.inline))
        Column(Modifier.weight(1f)) {
            Text("Tip", color = D.Black, style = TipHead)
            Spacer(Modifier.height(Space.tight))
            Text(
                "The NFC chip is near the centre of the back of Seeker phones. " +
                    "Hold it close and steady for a moment.",
                color = D.Black, style = Type.caption,
            )
        }
    }
}

/** `text-body font-bold`: the body step at bold, which is not a scale step of its own. */
private val TipHead = Type.body.copy(fontWeight = FontWeight.Bold)

/**
 * The tip card's ground and the disc standing on it.
 *
 * Raw hexes on purpose: they are the artwork's own pale blue, lifted from the render the tip
 * photo was cut out of, and they exist to sit *under* that photo. They are not palette colours
 * and nothing else may use them, which is why they live here beside the one card that does and
 * not in `D` — the same reason [TapHero]'s pigments are written out where they are drawn.
 */
private val TIP_GROUND = Color(0xFFECF3FC)
private val TIP_DISC = Color(0xFFD3E3FB)

// -------------------------------------------------------------------------------- the gesture

// The three CSS easings the hero's keyframes ask for, spelled out because Compose 1.7 has no
// equivalents: its named easings are a different set, and `FastOutSlowInEasing` is (.4, 0, .2, 1),
// which is NOT CSS `ease-in-out`. Prefixed so that if a later Compose puts `Ease`/`EaseInOut`/
// `EaseOut` within reach of a wildcard import, these stay the ones the hero means.
// Ported from ui/app/globals.css (`tap-hero-*`).
/** CSS `ease-in-out` — the ring's breath. Symmetric, which is why one half-wave covers both. */
private val CssEaseInOut = CubicBezierEasing(.42f, 0f, .58f, 1f)
/** CSS `ease-out` — a ripple leaves fast and dies slowly. */
private val CssEaseOut = CubicBezierEasing(0f, 0f, .58f, 1f)
/** CSS `ease`: what an `animation` shorthand with no timing function gets, i.e. the field's fade. */
private val CssEase = CubicBezierEasing(.25f, .1f, .25f, 1f)

/** One trip apart → together → apart. */
private const val HERO_LOOP = 4_800
/** The ring's breath — exactly a third of the loop, so the two can never drift out of step. */
private const val HERO_PULSE = 1_600

/** Where the two backs meet, on the 360 × 270 canvas. Everything in the field is centred here. */
private val FIELD = Offset(198f, 128f)
private val RING_R = floatArrayOf(82f, 62f, 43f, 25f)
private val RING_A = floatArrayOf(.16f, .26f, .42f, .75f)
/** `animationDelay: -.15s` per ring, as a fraction of the 1.6s breath. */
private const val RING_STAGGER = .15f / 1.6f

/**
 * The gesture, drawn and looping: ui/PORT-2026-10-02.md §1.1, from `TapHero.tsx` (the drawing,
 * on a 360 × 270 canvas) and the `tap-hero-*` rules in `globals.css` (the motion).
 *
 * Two Seekers come together back to back, a blue field answers where they meet, and they part.
 * It is the instruction — "Hold your phones together" — shown instead of said, which is why it
 * loops for as long as the sheet waits rather than playing once.
 *
 * One 4.8s loop, read off a single progress number: hold apart to 8%, closed by 36%, held until
 * 80%, back apart by 100%. CSS interpolates a transform list component by component, so the
 * translate and the angle both come off that one number instead of being animated apart.
 *
 * **The rings are the app's blue at four fixed opacities.** That is a glow inside an
 * illustration — the sanctioned exception to "no tints" (ui/PROJECT.md §3), like the tip card
 * under it — and it is drawn with `drawCircle`'s own `alpha`, never by making a paler blue. The
 * phone's colours are the Seeker's own and are raw on purpose: they are the picture, not the
 * palette.
 *
 * Everything that moves is read inside the draw lambda, never in composition. This sheet has
 * paid for that mistake before (the drag offset, the sheet's own height): a per-frame read in
 * composition recomposes the whole sheet every frame, and this canvas changes on all of them.
 *
 * [moving] false draws the CSS resting pose — closed, field on, rings at rest, no ripples —
 * which is the same instruction standing still. It is what reduced motion should pass; nothing
 * in the app reads that setting yet, so nothing passes it.
 */
@Composable
private fun TapHero(modifier: Modifier = Modifier, moving: Boolean = true) {
    val loop = rememberInfiniteTransition(label = "hero")
    val pose = loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = HERO_LOOP
                0f at 0
                0f at 384 using CubicBezierEasing(.5f, 0f, .2f, 1f) // 8% → 36%: closing
                1f at 1_728
                1f at 3_840 using CubicBezierEasing(.6f, 0f, .4f, 1f) // 80% → 100%: parting
                0f at HERO_LOOP
            },
            RepeatMode.Restart,
        ),
        label = "pose",
    )
    // The field is only on while the backs are actually together: it fades in over 33–40% and
    // out over 76–84%, so the blue never appears across a gap.
    val field = loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = HERO_LOOP
                0f at 0 using CssEase
                0f at 1_584 using CssEase
                1f at 1_920 using CssEase
                1f at 3_648 using CssEase
                0f at 4_032 using CssEase
                0f at HERO_LOOP
            },
            RepeatMode.Restart,
        ),
        label = "field",
    )
    // Linear, because the curve belongs to each thing the phase drives: the rings breathe on
    // ease-in-out, the ripples expand on ease-out, and both are a third of the loop long.
    val pulse = loop.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(HERO_PULSE, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )

    Canvas(
        modifier.aspectRatio(4f / 3f)
            // One picture, and it says what the title says. A screen reader gets the title.
            .clearAndSetSemantics { },
    ) {
        scale(size.width / 360f, size.width / 360f, pivot = Offset.Zero) {
            val t = if (moving) pose.value else 1f
            val on = if (moving) field.value else 1f
            val p = pulse.value

            // Paint order is the depth: the far phone, the field's filled discs, the near phone,
            // then the ripples and the bright core over both, at the edge where the backs meet.
            phone(lerp(260f, 195f, t), lerp(137f, 134f, t), lerp(10f, 30f, t), flip = true)
            if (on > 0f) {
                for (i in RING_R.indices) {
                    val k = if (moving) ringScale((p + i * RING_STAGGER) % 1f) else 1f
                    drawCircle(D.Blue, RING_R[i] * k, FIELD, alpha = on * RING_A[i])
                }
            }
            phone(lerp(100f, 163f, t), lerp(140f, 137f, t), lerp(-10f, -27f, t), flip = false)
            if (on > 0f) {
                // Two ripples half a cycle apart. At rest they are invisible (opacity 0 is the
                // CSS resting value), so a still hero is the pose and the glow and nothing else.
                if (moving) {
                    for (i in 0 until 2) {
                        val q = CssEaseOut.transform((p + i * .5f) % 1f)
                        drawCircle(
                            D.Blue, 96f * lerp(.25f, 1f, q), FIELD,
                            alpha = on * lerp(.7f, 0f, q), style = Stroke(2f),
                        )
                    }
                }
                drawCircle(CORE, 26f, FIELD, alpha = on)
            }
        }
    }
}

/** 0%,100% scale(.94) · 50% scale(1.05), on CSS ease-in-out — which is symmetric, so one wave. */
private fun ringScale(phase: Float): Float =
    lerp(.94f, 1.05f, CssEaseInOut.transform(if (phase < .5f) phase * 2f else (1f - phase) * 2f))

/** The point of contact: white at the centre, out to a transparent edge. */
private val CORE = Brush.radialGradient(
    0f to Color(0xFFFFFFFF), .35f to Color(0xFF9AD2FF), 1f to Color(0x009AD2FF),
    center = FIELD, radius = 26f,
)

// The Seeker's own colours, measured off the product shot. A vertical gradient here is written
// with the start and end y it had as an SVG objectBoundingBox gradient, because the phone is
// drawn centred on its own origin and so every one of those boxes straddles zero.
/** The back panel: near-black at the cameras, lightening to teal-grey at the foot. */
private val BACK = Brush.verticalGradient(
    0f to Color(0xFF1A2123), .45f to Color(0xFF33444A), 1f to Color(0xFF5E7378),
    startY = -97.4f, endY = 97.4f,
)
private val FRAME = Brush.horizontalGradient(
    0f to Color(0xFF0D1214), .5f to Color(0xFF263236), 1f to Color(0xFF6F8388),
    startX = -46f, endX = 46f,
)
private val EDGE = Brush.verticalGradient(
    0f to Color(0xFF4A5A5F), .5f to Color(0xFF7D9196), 1f to Color(0xFF3A484C),
    startY = -99f, endY = 99f,
)
/** The Seed Vault plate: black where it meets the cameras, pale at its foot. */
private val PLATE = Brush.verticalGradient(
    0f to Color(0xFF090B0C), .5f to Color(0xFF2C373B), 1f to Color(0xFF93A4AA),
    startY = -23.6f, endY = 23.1f,
)

/**
 * The plate and the three logo bars, parsed once.
 *
 * Building a `Path` from its string inside a draw scope is work on a frame, and on this canvas
 * that is every frame — the same reason [Tone.paths] is lazy.
 */
private val PLATE_PATH: Path by lazy {
    PathParser().parsePathString(
        "M-46 -23.6H-16.3A2.8 2.8 0 0 1 -13.5 -20.8V20.3A2.8 2.8 0 0 1 -16.3 23.1H-46Z",
    ).toPath()
}
private val LOGO_BARS: List<Path> by lazy {
    listOf(
        "M-3.35 67H5.15L3.35 69.4H-5.15Z",
        "M-5.15 70.5H3.35L5.15 72.9H-3.35Z",
        "M-3.35 74H5.15L3.35 76.4H-5.15Z",
    ).map { PathParser().parsePathString(it).toPath() }
}

/**
 * A Seeker's back: 92 × 198, corner 15, centred on its own origin — measured off the product
 * shot at .2335 units to its pixel (394 × 838 → 92 × 195.7).
 *
 * Placed by the CSS `translate(x, y) rotate(deg)`, in that order, so the phone turns about its
 * own middle and not about the canvas.
 *
 * | part | where |
 * |---|---|
 * | thickness | the frame showing 6 past the +x side, with the two keys on it |
 * | frame, back | the body, then the panel inset 1.6 with the vertical gradient |
 * | cameras | one round lens on its own island (r 8.9) and, under it, a pill of two — the Seeker's layout, not a single bar |
 * | flash | a pale dot right of the gap between them |
 * | Seed Vault plate | flush with the −x edge, square there and rounded on the inner side, with its ring mark |
 * | logo | three bars near the foot, the middle one skewed the other way |
 *
 * [flip] mirrors the whole phone for the far one, so its cameras sit top-right. The plate's
 * "SEED VAULT" lettering is **not** drawn: it is 3 units tall, which is about 3dp on the device,
 * so it is texture rather than text — and measuring a text layout to put it inside this
 * transform stack would cost a frame to draw something nobody can read.
 */
private fun DrawScope.phone(x: Float, y: Float, deg: Float, flip: Boolean) {
    translate(x, y) {
        rotate(deg, Offset.Zero) {
            scale(if (flip) -1f else 1f, 1f, Offset.Zero) {
                val body = Size(92f, 198f)
                val corner = CornerRadius(15f)
                drawRoundRect(EDGE, Offset(-40f, -99f), body, corner)
                drawRoundRect(KEY, Offset(47f, -32f), Size(3f, 15f), CornerRadius(1.5f))
                drawRoundRect(KEY, Offset(47f, -13f), Size(3f, 15f), CornerRadius(1.5f))
                drawRoundRect(FRAME, Offset(-46f, -99f), body, corner)
                drawRoundRect(BACK, Offset(-44.4f, -97.4f), Size(88.8f, 194.8f), CornerRadius(13.4f))

                // The islands in one dark fill, then each lens as rim, glass, pupil, glint.
                val island = Offset(-34.6f, -67.5f)
                val islandSize = Size(16.8f, 35f)
                drawRoundRect(ISLAND, island, islandSize, CornerRadius(8.4f))
                drawRoundRect(ISLAND_RIM, island, islandSize, CornerRadius(8.4f), style = Stroke(.8f))
                drawCircle(ISLAND, 8.9f, LENS[0])
                drawCircle(ISLAND_RIM, 8.9f, LENS[0], style = Stroke(.8f))
                for (i in LENS.indices) {
                    val r = if (i == 0) 5.4f else 5.1f
                    drawCircle(GLASS, r, LENS[i])
                    drawCircle(GLASS_RIM, r, LENS[i], style = Stroke(.7f))
                    drawCircle(PUPIL, if (i == 0) 2.8f else 2.6f, LENS[i])
                    drawCircle(GLINT, .7f, Offset(LENS[i].x + 1f, LENS[i].y - 1f))
                }
                drawCircle(FLASH, 2.6f, Offset(-10f, -69.1f))
                drawCircle(FLASH_RIM, 2.6f, Offset(-10f, -69.1f), style = Stroke(.6f))

                drawPath(PLATE_PATH, PLATE)
                drawCircle(MARK, 3f, Offset(-37.4f, -17f), style = Stroke(.7f))
                drawCircle(MARK, 1.2f, Offset(-37.4f, -17f), style = Stroke(.7f))

                LOGO_BARS.forEach { drawPath(it, LOGO) }
            }
        }
    }
}

private val KEY = Color(0xFF1C2527)
private val ISLAND = Color(0xFF0B0E0F)
private val ISLAND_RIM = Color(0xFF2A3539)
private val GLASS = Color(0xFF141A1C)
private val GLASS_RIM = Color(0xFF36444A)
private val PUPIL = Color(0xFF050607)
private val GLINT = Color(0xFF8FA3AB)
private val FLASH = Color(0xFF8793A0)
private val FLASH_RIM = Color(0xFFAEB9C2)
private val MARK = Color(0xFFD5DDE0)
private val LOGO = Color(0xFFCDD5D8)

/** The three lens centres, top to bottom. The glint sits 1 up and 1 right of each. */
private val LENS = arrayOf(Offset(-25.7f, -77.8f), Offset(-26.2f, -59.1f), Offset(-26.2f, -40.4f))

// ------------------------------------------------------------------------------------ header

/**
 * Who is on the other end, above every state but armed.
 *
 * [onExplorer] is ui/PORT-2026-10-02.md §1.4: on a chain refusal the explorer stops being a
 * button and becomes an icon at this header's end. Under Close and Try again it was a second
 * outlined button the same size as the first, and at a glance the two could not be told apart —
 * which on the one screen where somebody is deciding whether to pay again is the worst place for
 * it. Success keeps its `Done` + `View on explorer` pair, where there is no second chance to
 * confuse it with.
 */
@Composable
private fun PeerHeader(
    peer: TapPayload, me: String, history: Contact?, title: String,
    verified: Boolean, onOpen: (() -> Unit)?, open: Boolean,
    onExplorer: (() -> Unit)? = null,
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
        if (onExplorer != null) {
            val press = remember { MutableInteractionSource() }
            Box(
                // 48dp of hit area, pulled out by 12 so the mark — not the empty half of the
                // target — is what meets the gutter.
                Modifier.then(PressScale(press)).offset(x = 12.dp).size(48.dp)
                    .clickable(press, indication = null, onClick = onExplorer)
                    .semantics { contentDescription = "View on explorer" },
                contentAlignment = Alignment.Center,
            ) {
                // Hugeicons LinkSquare02 — a box with an arrow leaving it, "opens elsewhere".
                HIcon(R.drawable.hi_open, D.Black, 24.dp)
            }
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
                    loading = holdings == null,
                    reward = reward,
                    onConfirm = { sending -> onPay(sending, if (req == null) shown else null) }, onBack = { onConfirming(false) })
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
        // ui/PORT-2026-10-02.md §1.2. The ask, centred, with the amount as the hero: `Asking` in
        // ui/components/screens/Tap.tsx. The amount is the `amount` step, not `display` — one
        // amount size on every state of this sheet, so the ask and the screen that waits for it
        // to be paid show the same number at the same size.
        //
        // "In person, by tap · Solana mainnet" is gone from under the amount (§1.2). Neither
        // half of it changed the decision: the network is the same on every ask, and the header
        // above already says who this is and the device page says how they reached you — so it
        // was a line of furniture between the number and what it earns.
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Asks you for", color = D.Quiet, style = Type.caption)
            Spacer(Modifier.height(Space.tight))
            Text(
                moneyOr(asked, peer.request.symbol), color = D.Black, style = Type.amount,
                maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                modifier = Modifier.shared("amount"),
            )
        }
        EarnsLine(reward)
        Spacer(Modifier.height(24.dp))
        PrimaryButton(working ?: "Pay", onReview,
            Modifier.fillMaxWidth().shared("primary", bounds = true), loading = working != null)
        // Two different answers: "Later" keeps it payable and tells them nothing; "Decline"
        // is a no, and their phone is told so they stop waiting.
        AnimatedVisibility(visible = working == null) {
            Column {
                Row(Modifier.fillMaxWidth().padding(top = Space.lead), horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
                    DeclineButton(onDecline, Modifier.weight(1f))
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
    /** True while the wallet's balances have not been read yet. Not the same as holding none. */
    loading: Boolean = false,
    /** What this payment would earn. Asked by the caller, long before this screen opened. */
    reward: Rewards.Preview?,
    /** Called with the amount that is really sent, which is less than typed when the fee comes out of it. */
    onConfirm: (String) -> Unit,
    onBack: () -> Unit,
) {
    val asked = PayRequest.baseUnits(amount, token.decimals) ?: 0L
    val fee = Chain.NETWORK_FEE_LAMPORTS // one signature
    // Sending all the SOL there is leaves nothing to pay the fee with. That is not refused: the
    // fee comes out of the amount and the rest is sent. Only for an amount somebody typed; a
    // request names its own figure, and paying less than it asks would not settle it.
    val typed = peer is TapPayload.Card && token.isSol && known
    val sending = if (typed) Chain.sendable(asked, token.units, fee) else null
    val fromAmount = sending != null && sending != asked
    // Would leave an amount the chain does not allow a wallet to hold: too little to keep.
    val stuck = typed && sending == null && asked + fee <= token.units
    val units = sending ?: asked
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
    val enough = known && left >= 0 && !stuck
    val brand = Brand.of(peer, LocalVerified.current)

    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.shared("logo-${token.token}")) { TokenImage(token, 52.dp) }
        Spacer(Modifier.height(Space.inline))
        // Number and symbol sit on one baseline, the way a price is written. Bottom-aligning the
        // two boxes and nudging the small one up by eye left them a few pixels apart.
        Row {
            // The `display` step, exactly as on the keypad, so the number moves between the two
            // screens without changing shape. It is the biggest thing on the sheet, and the step
            // is a maximum: nothing here is larger.
            Text(
                amount, color = D.Black, style = Type.display, softWrap = false, maxLines = 1,
                modifier = Modifier.weight(1f, fill = false).alignByBaseline().shared("amount"),
            )
            Spacer(Modifier.width(Space.inline))
            // `title` here against `heading` on the keypad: the symbol still grows as the review
            // opens, and both ends of that growth are scale steps.
            Text(token.symbol, color = D.Black, style = Type.title,
                modifier = Modifier.alignByBaseline().shared("symbol-${token.token}", bounds = true))
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
        if (fromAmount) {
            DetailRow("They receive") { Value("${Chain.fmtUnits(units, 9)} SOL", strong = true) }
        }
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

        if (fromAmount) {
            // The one line on this sheet that changes what arrives, so it is marked, and centred
            // so it reads as a note about the whole payment and not as one more row.
            //
            // Amber, not the reward's yellow and not red: yellow here already means "you gain",
            // and red means something failed. Amber is the colour people read as "notice this
            // before you go on", which is exactly what this is.
            Spacer(Modifier.height(Space.inline))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Network fee is taken from this amount",
                    color = NOTICE_INK, style = Type.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1,
                    modifier = Modifier.background(NOTICE_MARK, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        if (stuck) {
            Spacer(Modifier.height(Space.tight))
            Text(
                "This would leave less than ${Chain.fmtUnits(Chain.RENT_MIN_LAMPORTS, 9)} SOL, which Solana does not allow a wallet to keep. Send all of it, or a little less.",
                color = D.DangerInk, style = Type.caption,
            )
        }

        RewardLine(reward)

        Spacer(Modifier.height(20.dp))
        PrimaryButton(working ?: if (enough || stuck) "Confirm and pay" else if (loading) "Checking your balance…" else if (known) "Not enough ${token.symbol}" else "No ${token.symbol} in your wallet",
            { onConfirm(if (fromAmount) Chain.fmtUnits(units, 9) else amount) }, Modifier.fillMaxWidth().shared("primary", bounds = true),
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
    // Only a payment that earns says anything. One that earns nothing says nothing: a line about
    // a reward that is not coming is noise on the screen where money is being sent.
    AnimatedVisibility(visible = reward?.earns == true) {
        Row(
            Modifier.fillMaxWidth().padding(top = Space.lead),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HIcon(R.drawable.hi_gift, D.Black, 18.dp)
            Spacer(Modifier.width(Space.inline))
            Marked("Earns ${reward?.skr} SKR")
        }
    }
}

/**
 * What an incoming ask earns, centred under the amount it belongs to: §1.2.
 *
 * Separate from [RewardLine], which is the review screen's start-aligned row inside a column of
 * labelled details. Here the amount is the hero and this hangs off it, so the pair has to be
 * centred as a group — and only the *earning* half lives here. The refusal moved to the foot
 * ([WhyNothing]) because a reason sitting where a reward goes reads as the reward.
 */
@Composable
private fun EarnsLine(reward: Rewards.Preview?) {
    AnimatedVisibility(visible = reward?.earns == true) {
        Row(
            // `mt-lead`: 12 below the amount.
            Modifier.fillMaxWidth().padding(top = Space.lead),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HiIcon(Hi.Gift, D.Black, 18.dp)
            // `gap-2`, not `Space.inline`: an 18 icon beside a 15sp line, not an avatar beside a row.
            Spacer(Modifier.width(8.dp))
            Marked("Earns ${reward?.skr} SKR")
        }
    }
}

/**
 * "No", and their phone is told so they stop waiting. ui/PORT-2026-10-02.md §1.2 and §10.
 *
 * **The second sanctioned red in the app**, after a chain refusal — §10 lists exactly three, and
 * this is one of them. It is not a warning: declining is a perfectly ordinary answer. It is red
 * because it is the only *irreversible* answer on the sheet. "Later" leaves the ask payable, Pay
 * pays it, and both can be undone by doing the other; a decline tells the other phone to stop,
 * and nothing on this screen takes that back. Two outline buttons said those two things in the
 * same voice.
 *
 * `D.Danger` fill with white [Type.heading] is 3.31:1, and large text needs 3:1. Same geometry
 * as the [SecondaryButton] beside it — min 52, corner 16 — so the pair is one row and not a
 * button next to a bigger button.
 */
@Composable
private fun DeclineButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.then(PressScale(interaction)).heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp))
            .background(D.Danger)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("Decline", color = D.White, style = Type.heading)
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
/** The marker for a heads-up: amber behind dark amber ink (8.6:1). */
private val NOTICE_MARK = Color(0xFFFFE2A8)
private val NOTICE_INK = Color(0xFF5C3A00)

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

/**
 * Watching for money that has not landed yet: ui/PORT-2026-10-02.md §1.3.
 *
 * **The same shape as [ResultBody]**, so every outcome on this sheet looks alike — the 72 disc,
 * the headline in the tone's ink, the amount, one line, one way out. Waiting used to have an
 * arrangement of its own (the amount on top, then a 40 disc and its words beside it), which made
 * it read as a different kind of screen from the endings it turns into.
 *
 * [deferred] is still the pending tone and still the ticking clock: a deferral is not an ending,
 * the money can still arrive, so only the words change. The clock keeps running because it is
 * still true that we are waiting.
 */
@Composable
private fun WaitingBody(label: String, amount: String, symbol: String, deferred: Boolean, onClose: () -> Unit) {
    // The same unknown, mid-sentence and at the start of one. A blank label is a phone that gave
    // us no name, and "They will pay later" is honest where "will pay later" is broken.
    val them = label.ifBlank { "them" }
    val they = label.ifBlank { "They" }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // §2: inside a `StatusDisc` the clock is drawn at 80% of the disc — 58 in a 72 — because
        // the face now carries twelve hour dots, and at the other marks' 40 they close up.
        StatusDisc(Tone.Pending, size = 72.dp, mark = { TickingClock(58.dp, D.White) })
        Spacer(Modifier.height(Space.row))
        Text(
            if (deferred) "They will pay later" else "Waiting for payment",
            color = Tone.Pending.ink, style = Type.title, textAlign = TextAlign.Center,
        )
        // `mt-2`, and not a spacing role: it is the gap between a headline and the number it is
        // about, which is tighter than heading-to-content on purpose.
        Spacer(Modifier.height(8.dp))
        // Through Money, so this reads the same as every other amount in the app: the phone's
        // grouping, cut rather than rounded up, and the no-break space before the code so the
        // number and "USDC" can never wrap apart. The fallback is for a string that isn't a
        // decimal at all \u2014 showing what we were handed beats showing nothing.
        Text(moneyOr(amount, symbol), color = D.Black, style = Type.amount)
        Spacer(Modifier.height(Space.tight))
        Text(
            if (deferred) "$they will pay later. You will be notified when they do."
            else "From $them. We are watching for it, you can close this.",
            color = D.Quiet, style = Type.caption, textAlign = TextAlign.Center,
            // `mx-auto max-w-xs`, like every other centred state: the line wraps before it runs
            // the full width of the sheet, so a two-line note reads as one block.
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(24.dp))
        SecondaryButton("Close", onClose, Modifier.fillMaxWidth())
    }
}

/**
 * A payment signed with no connection, waiting to be carried to the phone that asked.
 *
 * The orange disc, because this is the one state on the sheet where the person has something
 * left to do and a limited time to do it. The amount stays on screen so it is plain what is
 * being held. [note] is why the last try did not work; it replaces the line under the amount so
 * the instruction and the reason are never both competing to be read.
 */
@Composable
private fun HandOverBody(label: String, amount: String, symbol: String, note: String?, onCancel: () -> Unit) {
    val them = label.ifBlank { "their phone" }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        StatusDisc(Tone.Act, size = 72.dp)
        Spacer(Modifier.height(Space.row))
        Text("Hold near again to send it", color = Tone.Act.ink, style = Type.title, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(moneyOr(amount, symbol), color = D.Black, style = Type.amount)
        Spacer(Modifier.height(Space.tight))
        Text(
            note ?: "You are offline, so $them will send it for you. It is signed and ready. Nothing is sent until the phones touch.",
            color = D.Quiet, style = Type.caption, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(24.dp))
        SecondaryButton("Cancel", onCancel, Modifier.fillMaxWidth())
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
