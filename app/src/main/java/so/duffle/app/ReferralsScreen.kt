package so.duffle.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.imePadding
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A scale step in another face.
 *
 * Not new steps: each is one of the seven sizes with the face its content asks for, which is what
 * the sandbox's `<Amount className="text-title">` and `font-mono text-title` are. Money gets
 * Geist and tabular figures; an identifier gets Geist Mono. Nothing here invents a size.
 */
private val FigureTitle = Type.title.figure()
private val FigureBody = Type.body.figure()
private val MonoTitle = Type.title.monospaced()
private val MonoBody = Type.body.monospaced()

/**
 * The body step in its bold weight, and the caption step with tabular digits.
 *
 * Same two steps, not two more: a weight and a font feature are not sizes. `BodyBold` is the
 * sandbox's `text-body font-bold` (the step titles in "How it works"); `CaptionTabular` is its
 * `text-caption tnum` — "1 of 2 paid" is a pair of counts rather than money, so it keeps the
 * sentence face and takes only the tabular figures that stop the digits shifting.
 */
private val BodyBold = Type.body.copy(fontWeight = FontWeight.Bold)
private val CaptionTabular = Type.caption.copy(fontFeatureSettings = "tnum")

/**
 * The hero card's ground.
 *
 * These are the ARTWORK's own colours, under the tinted-card exception in ui/PROJECT.md §3 —
 * the gift's green, the same pair `This week` sits on over in RewardsScreen. They are
 * deliberately NOT `Palette.kt` tokens: they describe one picture's background, not a state, a
 * tier or a severity, which is what the no-tints rule exists to stop. Do not reuse them.
 */
private val HERO_TOP = Color(0xFFE8FCEF)
private val HERO_BOTTOM = Color(0xFFD0FADD)

/** The gift fills the card's end 46%, and its start edge is wiped out over that strip's first 35%. */
private const val HERO_ART = .46f
private const val HERO_FADE = .35f

// ============================================================ the Refer sheet (PORT-4 §2)

/**
 * Refer & earn — **a sheet, not a screen** (ui/PORT-2026-10-01-4.md §2).
 *
 * `ReferralsTab` was a destination on the tab bar. It is now a tray that opens over whatever the
 * user is already looking at, from four places: the Referrals card on Home, "Invite a friend" on
 * Rewards, the AirPay row on Offers, and "Refer and earn" in the profile panel. So this takes
 * nothing but a way to close it — the wallet, the name and the share chooser are all things it
 * can reach for itself, and four call sites that each had to assemble them would be four places
 * for them to drift.
 *
 * [myName] is the one exception, because the Activity knows something this cannot: the `.sol` or
 * `.skr` name a wallet has borrowed or resolved (`myDomain`). Left null it falls back to
 * [Identity.label], which is what the rest of the app shows for somebody who has set a name and
 * "" for everybody else — and [Referrals.message] is written to work without one.
 *
 * ### Why a Dialog
 *
 * Opened from inside a tab, this cannot reach the activity root where `OfferSheet` lives. A
 * [androidx.compose.ui.window.Popup] is laid out INSIDE the system-bar insets, so the tray stops
 * a navigation bar's height short of the display and a strip of the page behind shows through
 * under the button — found and fixed on the phone for `StreakSheet`, 2026-10-01. A [Dialog] told
 * not to use the platform's default width and not to fit the system windows is the one overlay a
 * screen composable can open that genuinely reaches the display's edges.
 *
 * ### Nothing here says "assured"
 *
 * The inviter is paid on the invitee's **first rewarded payment**, not when they join: a wallet
 * is free, so "somebody signed up with my code" costs an attacker nothing. The hero says what
 * each side gets and the second sheet ends on the condition; the invited list shows which
 * invitations have actually turned into a payment.
 */
@Composable
internal fun ReferSheet(onClose: () -> Unit, myName: String? = null) {
    val context = LocalContext.current
    // Who "you" are, without an activity. Constructing a [Wallet] registers an MWA result
    // sender, which is only legal before an activity is STARTED — `addressOf` is the expression
    // the app keeps in one place for exactly this question.
    val address = remember { Wallet.addressOf(context) }
    val fallbackName = remember { Identity.label(context) }
    val name = myName ?: fallbackName

    var summary by remember { mutableStateOf<Referrals.Summary?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(address, Session.version, Live.tick) {
        loading = true
        // A refresh that fails keeps the code already on screen. Replacing it with null swapped
        // an open sheet, and whatever was being typed on the second one, for "Can't reach the
        // server" on a single dropped request.
        address?.let { Referrals.summary(it) }?.let { summary = it }
        loading = false
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            // Both matter, for different halves of the same problem: without the first the
            // window is a floating dialog inset from every edge, and without the second it is
            // laid out inside the system-bar insets.
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            // The stack draws its own scrim and dismisses itself when it is tapped, with the
            // exit animation. Letting the window dismiss as well would skip that animation.
            dismissOnClickOutside = false,
        ),
    ) {
        // A dialog window can dim behind itself. The scrim below is the only scrim this app has;
        // a system dim under it would be a second one, at an opacity nobody chose.
        val host = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { host?.setDimAmount(0f) }
        ReferStack(
            s = summary,
            loading = loading,
            connected = address,
            myName = name,
            onClose = onClose,
        )
    }
}

/**
 * Both sheets, stacked, over one scrim (§2.3).
 *
 * ### Why this is not two [Tray]s
 *
 * [Tray] is the app's sheet and this would rather have been two of them. It cannot be: a `Tray`
 * fills the screen and draws the scrim INSIDE itself, so a second one would put a second scrim
 * over the first sheet — at 28% over the 20% dim the brief asks for — and scaling the first
 * `Tray` to 94% would scale its scrim with it. So the chrome is rebuilt here: same scrim alpha,
 * same `Arrive` rise, same drag-to-dismiss thresholds, same handle, same [Tray.OUTER] corner and
 * [Tray.PAD] gutter, all read from `Tray` itself so the two cannot drift apart.
 *
 * ### The motion
 *
 * The sandbox animates with 300 ms ease-out. §2.3 says to prefer the app's own springs and that
 * the scale, offset and dim values are what matter, so the three of them ride [Settle] — the
 * spring every other content swap in this app uses — rather than a tween nobody chose. No new
 * spring is declared and `Arrive`/`Snap`/`Settle` are untouched.
 *
 * The first sheet scales from its **top** edge ([TransformOrigin] at y = 0), which is what keeps
 * its top where it is and pulls its bottom up behind the second sheet. Scaling from the centre
 * would sink the top out of sight, and the top staying visible is the whole point of the stack.
 *
 * The second sheet moves by a layout [offset] rather than a `graphicsLayer` translation, because
 * a layout offset is where Compose places the node and therefore where it hit-tests it: parked
 * below the display it can never swallow a tap meant for the first sheet's buttons underneath.
 */
@Composable
private fun ReferStack(
    s: Referrals.Summary?,
    loading: Boolean,
    connected: String?,
    myName: String,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val scrim = remember { Animatable(0f) }
    val offset = remember { Animatable(0f) }
    var height by remember { mutableFloatStateOf(0f) }
    var howHeight by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var how by remember { mutableStateOf(false) }
    // What the second sheet holds: how it works, or the place to enter a friend's code.
    var second by remember { mutableStateOf("how") }
    // A code can be entered until one has been, or until the first rewarded payment. The server
    // says which (`can_claim`); a code accepted in this sitting closes the door at once.
    var codeUsed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val h = snapshotFlow { height }.first { it > 0f }
        // Below the screen until it has been measured, so it never flashes in place.
        offset.snapTo(h)
        shown = true
        launch { scrim.animateTo(1f, tween(220)) }
        offset.animateTo(0f, SheetRise)
    }

    fun leave() {
        if (leaving) return
        leaving = true
        // Both go at once: the scrim closes everything, and a second sheet left standing while
        // the first slid out from under it would be the one frame that shows they are two
        // windows rather than one stack.
        how = false
        scope.launch { scrim.animateTo(0f, tween(150)) }
        scope.launch {
            // Quicker and quieter than the entrance, like every other sheet here.
            offset.animateTo(height.coerceAtLeast(1f), tween(200, easing = FastOutLinearInEasing))
            onClose()
        }
    }

    // Back takes one layer off at a time, which is what the stack looks like it should do.
    BackHandler { if (how) how = false else leave() }

    val shrink by animateFloatAsState(if (how) .94f else 1f, Settle, label = "shrink")
    val lift by animateFloatAsState(if (how) -10f else 0f, Settle, label = "lift")
    val dim by animateFloatAsState(if (how) .20f else 0f, Settle, label = "dim")
    val parked by animateFloatAsState(if (how) 0f else 1f, Settle, label = "second")

    // The second sheet follows the finger like the first. Its position is `parked` (how far it is
    // tucked away, animated) and this (how far it has been pulled down); the drawn offset is the
    // larger of the two, so a drag-dismiss slides it the rest of the way down and the tuck-away
    // that follows has nothing left to do. It had no drag at all — it moved only by the toggle.
    val howOffset = remember { Animatable(0f) }
    LaunchedEffect(parked) { if (parked >= 1f) howOffset.snapTo(0f) }
    // Reopened before the tuck-away above had finished, the pull-down was still in force: the
    // first sheet dimmed and the second stayed below the screen. Opening always brings it back.
    LaunchedEffect(how) { if (how && howOffset.value > 0f) howOffset.animateTo(0f, SheetSettle) }
    val howDrag = rememberDraggableState { delta ->
        scope.launch { howOffset.snapTo((howOffset.value + delta).coerceAtLeast(0f)) }
    }

    val drag = rememberDraggableState { delta ->
        scope.launch {
            // Never above its resting edge: pulled up, it simply stays put.
            offset.snapTo((offset.value + delta).coerceAtLeast(0f))
        }
    }
    // The first sheet scrolls inside; dragging that content has to pull the sheet too. Without
    // this the scroll took every drag and only the few pixels of handle moved the sheet — which
    // is why this one felt undraggable next to the others. See [sheetNestedScroll].
    val nested = remember {
        sheetNestedScroll(offset, scope, enabled = { !leaving && !how }) { v ->
            if (offset.value > height * .25f || v > 1_200f) leave()
            else scope.launch { offset.animateTo(0f, SheetSettle, initialVelocity = v) }
        }
    }

    // The first sheet is capped and scrolls inside; the second is as tall as its content.
    val cap = LocalConfiguration.current.screenHeightDp.dp * .88f
    val panelShape = RoundedCornerShape(topStart = Tray.OUTER, topEnd = Tray.OUTER)
    val dismiss = remember { MutableInteractionSource() }
    val behind = remember { MutableInteractionSource() }

    Box(
        Modifier.fillMaxSize()
            .drawBehind {
                val gone = if (height > 0f) (offset.value / height).coerceIn(0f, 1f) else 0f
                drawRect(Color.Black.copy(alpha = .28f * scrim.value * (1f - gone)))
            }
            // A tap on the scrim closes everything, second sheet included.
            .clickable(dismiss, indication = null) { leave() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            Modifier.fillMaxWidth()
                .onSizeChanged { height = it.height.toFloat() }
                .graphicsLayer {
                    translationY = (if (shown) offset.value.coerceAtLeast(0f) else size.height) + lift.dp.toPx()
                    scaleX = shrink
                    scaleY = shrink
                    transformOrigin = TransformOrigin(.5f, 0f)
                }
                .nestedScroll(nested)
                .draggable(
                    drag, Orientation.Vertical, enabled = shown && !leaving && !how,
                    onDragStopped = { velocity ->
                        // Far enough, or flicked: go. Otherwise settle back.
                        if (offset.value > height * .25f || velocity > 1_200f) leave()
                        else scope.launch { offset.animateTo(0f, SheetSettle, initialVelocity = velocity) }
                    },
                ),
        ) {
            SheetPanel(Modifier.heightIn(max = cap), scroll = true) {
                when {
                    connected == null -> Blank(
                        "Connect a wallet",
                        "Your code belongs to a wallet, so there is nothing to show until one is connected.",
                    )
                    s == null && !loading && !Session.valid(connected) ->
                        SignInNeeded("see your referral code")
                    s == null && loading -> ReferralSkeleton()
                    s == null -> Blank(
                        "Can't reach the server",
                        "Your code is made by the server, so this screen can't show one right now.",
                    )
                    else -> ReferBody(
                        s = s,
                        onHow = { second = "how"; how = true },
                        onCode = if (s.canClaim && !codeUsed) ({ second = "code"; how = true }) else null,
                        onCopyCode = { clipboard.setText(AnnotatedString(s.code)) },
                        onCopyInvite = {
                            clipboard.setText(AnnotatedString(
                                Referrals.message(myName, s.code, s.theirReward)))
                        },
                        onShare = { pkg ->
                            shareInvite(context, Referrals.message(myName, s.code, s.theirReward), pkg)
                        },
                    )
                }
            }
            // The dim over the first sheet, and the tap that sends the second one back down.
            // Clipped to the panel's own shape so the rounded top is not squared off by it, and
            // it only takes a tap while the second sheet is up — otherwise it would sit over
            // `Share on Telegram` and swallow it.
            Box(
                Modifier.matchParentSize().clip(panelShape)
                    .background(Color.Black.copy(alpha = dim))
                    .then(
                        if (how) Modifier.clickable(behind, indication = null) { how = false }
                        else Modifier,
                    ),
            )
        }

        // The second sheet. Shorter than the first, so the first's top shows above it.
        Box(
            Modifier.fillMaxWidth()
                .onSizeChanged { howHeight = it.height.toFloat() }
                .offset {
                    // Parked far below the display until it has been measured, so it never
                    // flashes over the first sheet on the frame before its height is known.
                    val tucked = if (howHeight > 0f) parked * howHeight else 20_000f
                    IntOffset(0, maxOf(tucked, howOffset.value).roundToInt())
                }
                .draggable(
                    howDrag, Orientation.Vertical, enabled = how,
                    onDragStopped = { velocity ->
                        // Far enough, or flicked: send it down. Otherwise settle back up.
                        if (howOffset.value > howHeight * .25f || velocity > 1_200f) {
                            howOffset.animateTo(howHeight, tween(180, easing = FastOutLinearInEasing))
                            how = false
                        } else {
                            howOffset.animateTo(0f, SheetSettle, initialVelocity = velocity)
                        }
                    },
                )
                .topEdgeShadow(Tray.OUTER),
        ) {
            // Lifted clear of the keyboard, so the field being typed in is never under it.
            SheetPanel(modifier = Modifier.imePadding(), swallowTaps = how || parked < 1f) {
                if (second == "code") {
                    FriendCodeBody(
                        address = connected,
                        theirReward = s?.theirReward ?: 0,
                        open = how,
                        onDone = { codeUsed = true },
                        onClose = { how = false },
                    )
                } else {
                    HowItWorksBody(
                        theirReward = s?.theirReward ?: 0,
                        reward = s?.reward ?: 0,
                        onClose = { how = false },
                    )
                }
            }
        }
    }
}

/**
 * The white panel of a sheet: the [Tray]'s chrome, reusable twice over.
 *
 * Every number is read off [Tray] rather than written again — the corner, the side gutter, the
 * 10/12 top and bottom — so the two sheets here and every other sheet in the app stay one shape.
 * [scroll] is the first sheet's 88% cap: the panel scrolls inside the height its caller allows.
 * [swallowTaps] is what stops a tap on the panel reaching the scrim behind it; the second sheet
 * drops it while parked off-screen so nothing is listening down there.
 */
@Composable
private fun SheetPanel(
    modifier: Modifier = Modifier,
    scroll: Boolean = false,
    swallowTaps: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val swallow = remember { MutableInteractionSource() }
    val state = rememberScrollState()
    Column(
        modifier.fillMaxWidth()
            .background(D.White, RoundedCornerShape(topStart = Tray.OUTER, topEnd = Tray.OUTER))
            .clickable(swallow, indication = null, enabled = swallowTaps) {}
            .navigationBarsPadding()
            .then(if (scroll) Modifier.verticalScroll(state) else Modifier)
            .padding(start = Tray.PAD, end = Tray.PAD, top = 10.dp, bottom = 12.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(D.Line, CircleShape))
        content()
    }
}

/**
 * The soft shadow on the second sheet's top edge: `0 -8px 24px -8px rgba(0,0,0,.25)`.
 *
 * Drawn the way `cardShadow` is, because Compose's `shadow()` has no spread and casts on every
 * edge. It is what separates two white panels of the same colour that overlap — without it the
 * second sheet's top edge is invisible against the first.
 */
private fun Modifier.topEdgeShadow(corner: Dp) = drawBehind {
    val px = 1.dp.toPx()
    // The -8px spread: the shape is shrunk by it before the blur is cast.
    val spread = 8 * px
    val r = corner.toPx() - spread
    drawIntoCanvas { canvas ->
        val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            setShadowLayer(24 * px, 0f, -8 * px, android.graphics.Color.argb(64, 0, 0, 0))
        }
        canvas.nativeCanvas.drawRoundRect(
            spread, spread, size.width - spread, size.height, r, r, paint,
        )
    }
}

/** What the sheet says when there is nothing to show yet. Two lines, no box (§2.1). */
@Composable
private fun Blank(title: String, detail: String?) {
    Spacer(Modifier.height(24.dp))
    Text(title, color = D.Black, style = Type.heading)
    if (detail != null) {
        Spacer(Modifier.height(Space.tight))
        Text(detail, color = D.Quiet, style = Type.caption)
    }
    Spacer(Modifier.height(24.dp))
}

// ------------------------------------------------------------------ §2.1 the first sheet

/**
 * The first sheet: what the offer is, your code, four ways to send it, and who took it.
 *
 * ### What came off this surface (§2.1)
 *
 * - **The "Have a code?" door.** Entering somebody else's code belongs to the first-run screens,
 *   before a first payment — not to the sheet for sharing yours. §6 records that this leaves an
 *   invited user with nowhere to type a code until those screens exist; that gap is known and is
 *   not this sheet's to close.
 * - **The blue poster.** The hero is the artwork's green card now, and the figures are ink on it
 *   rather than white on a fill, so they are no longer bound by the 19sp-bold rule.
 * - **The invited / have paid / SKR earned stats row**, which said the same thing as the list
 *   below it, and **the "How to refer" link**, which is the pill in the header.
 */
@Composable
private fun ReferBody(
    s: Referrals.Summary,
    onHow: () -> Unit,
    /** Opens the sheet for a friend's code. Null once one cannot be entered any more. */
    onCode: (() -> Unit)?,
    onCopyCode: () -> Unit,
    onCopyInvite: () -> Unit,
    /** A package name to send to, or null for the system chooser. */
    onShare: (String?) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Refer & earn", color = D.Black, style = Type.heading, modifier = Modifier.weight(1f))
        OutlinePill("How it works", Hi.Info, onHow)
    }

    Spacer(Modifier.height(8.dp))
    Hero(s.theirReward, s.reward)

    // The code is read aloud and typed by hand, so it is mono and large: a monospace face gives
    // every character its own width, which is what tells an O from a 0.
    Spacer(Modifier.height(24.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Your referral code", color = D.Quiet, style = Type.caption)
            Spacer(Modifier.height(Space.tight))
            Text(s.code, color = D.Black, style = MonoTitle)
        }
        Spacer(Modifier.width(Space.inline))
        OutlinePill("Copy", Hi.Copy, onCopyCode, description = "Copy your code")
    }

    Spacer(Modifier.height(Space.lead))
    TelegramButton { onShare(TELEGRAM) }

    // Three, not four: "Share" opens the system sheet, which is where every other app already
    // is, so a "More" beside it would be a second door to the same room.
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth()) {
        ShareAction("WhatsApp", Modifier.weight(1f), onClick = { onShare(WHATSAPP) }) {
            // A brand mark is a picture in its own colour: `Image`, never `Icon`, which tints.
            Image(painterResource(R.drawable.brand_whatsapp), null, Modifier.size(24.dp))
        }
        // §6: there is no invite link today, only a code. So this copies the invite MESSAGE —
        // the same words the share actions send, code included — and invents no URL. The pill
        // above copies the bare code; this copies something you can paste into a chat.
        ShareAction("Copy link", Modifier.weight(1f), onClick = onCopyInvite) {
            HIcon(R.drawable.hi_link, D.Black, 20.dp)
        }
        ShareAction("Share", Modifier.weight(1f), onClick = { onShare(null) }) {
            HIcon(R.drawable.hi_share, D.Black, 20.dp)
        }
        // The other direction: somebody invited YOU. It sits with the three ways of sending a
        // code because it is the fourth thing to do with one, and it goes away once it cannot
        // be used (a code already entered, or a first rewarded tap already made).
        if (onCode != null) {
            ShareAction("Friend's code", Modifier.weight(1f), onClick = onCode) {
                HiIcon(Hi.Gift, D.Black, 20.dp)
            }
        }
    }

    // Only once somebody has joined. Before that it was a heading over "Nobody yet", which is a
    // section about nothing.
    if (s.people.isNotEmpty()) {
        Spacer(Modifier.height(Space.section))
        InvitedList(s)
    }
}

/**
 * The hero (§2.1).
 *
 * The gift's own green, and the gift itself at the end filling the card's height. The picture
 * carries that same pale ground, so where it meets the card's gradient there is a seam — wiping
 * its start edge out with [BlendMode.DstIn] is what hides the join, and it is mirrored in a
 * right-to-left layout, which carries the wipe to the other side for free.
 *
 * Nothing else is in the card: no button, no "limited time" badge. The one action is the share
 * button below, and a second way in would only split the tap.
 */
@Composable
private fun Hero(theirReward: Int, reward: Int) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.verticalGradient(0f to HERO_TOP, .45f to HERO_TOP, 1f to HERO_BOTTOM)),
    ) {
        Box(Modifier.matchParentSize()) {
            Image(
                painterResource(R.drawable.week_gift),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.CenterEnd)
                    .fillMaxWidth(HERO_ART).fillMaxHeight()
                    // The layer is OUTSIDE the wipe, so the alpha comes out of the picture alone.
                    .graphicsLayer {
                        if (rtl) scaleX = -1f
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, Color.Black),
                                startX = 0f, endX = size.width * HERO_FADE,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }
        // The words take the start 54% of the padded card, which is the sandbox's `max-w-[54%]`
        // inside its `p-4` — a percentage of the content box, so the padding comes off first.
        Column(Modifier.padding(16.dp).fillMaxWidth(.54f)) {
            Text(
                buildAnnotatedString {
                    append("Invite friends\n& earn ")
                    withStyle(SpanStyle(color = D.SuccessInk)) { append("SKR") }
                },
                color = D.Black, style = Type.title,
            )
            Spacer(Modifier.height(8.dp))
            // Both amounts through [Money], so the phone's locale groups the digits — and bold
            // in success ink, because the two figures are the whole offer. They stay in the
            // sentence's face and take only its tabular figures; the sandbox does the same.
            val figure = SpanStyle(
                color = D.SuccessInk, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum",
            )
            Text(
                buildAnnotatedString {
                    append("They get ")
                    withStyle(figure) { append("${skr(theirReward)} SKR") }
                    append(",\nyou get ")
                    withStyle(figure) { append("${skr(reward)} SKR") }
                },
                color = D.Black, style = Type.body,
            )
        }
    }
}

/** The main share button: ink, 60 tall, corner 18, the Telegram mark before the words. */
@Composable
private fun TelegramButton(onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.then(PressScale(press)).fillMaxWidth().heightIn(min = 60.dp)
            .clip(RoundedCornerShape(18.dp)).background(D.Black)
            .clickable(press, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.brand_telegram), null, Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            // Every button label is Type.heading (ui/PORT.md §3), white on ink at 12.46:1.
            Text("Share on Telegram", color = D.White, style = Type.heading)
        }
    }
}

/**
 * A small outlined pill: a mark and a word. 48 tall to the finger, 36 to the eye.
 *
 * The touch height is on the outside and the ring on the inside, so the pill stays the size it
 * is drawn while the target clears the 48dp minimum — the same trick the rest of the app's
 * inline controls use rather than growing the visible shape to meet a finger.
 */
@Composable
private fun OutlinePill(label: String, icon: Hi, onClick: () -> Unit, description: String? = null) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.then(PressScale(press)).heightIn(min = 48.dp)
            .clickable(press, indication = null, onClick = onClick)
            .then(
                if (description != null) Modifier.semantics { contentDescription = description }
                else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.border(1.5.dp, D.Line, CircleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 16 beside a caption, so the mark is the same weight as the words next to it. Its
            // label is right there, so it stays decorative to a screen reader.
            HiIcon(icon, D.Black, 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(label, color = D.Black, style = Type.caption)
        }
    }
}

/** One of the three actions under the main button: a 48 outlined disc and its word. */
@Composable
private fun ShareAction(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    Column(
        modifier.then(PressScale(press)).clickable(press, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).border(1.5.dp, D.Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.height(8.dp))
        // Centred, because with four across "Friend's code" wraps on a narrow phone and a
        // start-aligned second line sat off-centre under its disc.
        Text(label, color = D.Quiet, style = Type.caption, textAlign = TextAlign.Center)
    }
}

// ------------------------------------------------------------------ §2.2 people you invited

/**
 * The people who took your code (§2.2).
 *
 * Each invitation is in one of two places: they have joined — their wallet is connected, their
 * first tap has not happened — or their first tap has landed and both sides were paid. The card
 * says which three ways over: the mark on the face, the words, and the amount at the end.
 *
 * ### Why this is people you invited, not people you could invite
 *
 * The obvious version reads the phone's address book, which needs READ_CONTACTS — a directory of
 * everybody you know, requested so a promo sheet can look busier. [Contacts] is no help either:
 * everyone in it has already tapped with you, so inviting them is meaningless. What is worth
 * showing is the half that is true and actually changes: who took the code, and who has paid.
 */
@Composable
private fun InvitedList(s: Referrals.Summary) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            "People you invited", color = D.Black, style = Type.heading,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
        // Counts, not money — so tabular figures and nothing else. Only once there is somebody
        // to count: "0 of 0 paid" above "Nobody yet" says the same thing twice.
        if (s.people.isNotEmpty()) {
            Spacer(Modifier.width(Space.inline))
            // The server's own counts, not the list's: it sends at most twenty people, so
            // counting the rows said "3 of 20 paid" to somebody who had invited thirty-five.
            Text(
                "${maxOf(s.paid, s.people.count { it.paid })} of ${maxOf(s.invited, s.people.size)} paid",
                color = D.Quiet, style = CaptionTabular,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
    if (s.people.isEmpty()) {
        Spacer(Modifier.height(Space.tight))
        Text("Nobody yet. Anyone who uses your code shows up here.",
            color = D.Quiet, style = Type.caption)
        return
    }
    Spacer(Modifier.height(Space.lead))
    Column(verticalArrangement = Arrangement.spacedBy(Space.inline)) {
        s.people.forEach { InvitedCard(it, s.reward) }
    }
}

@Composable
private fun InvitedCard(p: Referrals.Person, reward: Int) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).border(1.5.dp, D.Line, shape).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            // Seeded on the ADDRESS, never a name: one person is one colour everywhere in the
            // app, and nobody has named these people anyway.
            FaceDisc(p.address, p.address, 40.dp)
            if (p.paid) {
                // A tick once their first tap has landed. Somebody who has only joined carries
                // no mark, because there is nothing yet to mark. The white ring sits OUTSIDE the
                // 16 disc — a 20 white circle with the success disc inset 2 into it — which is
                // what lifts it off the face's own colour.
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp)
                        .size(20.dp).clip(CircleShape).background(D.White)
                        .padding(2.dp).clip(CircleShape).background(D.Success),
                    contentAlignment = Alignment.Center,
                ) {
                    // `disc = true`: inside a filled disc the icon is what a colour-blind person
                    // reads, so it is drawn at the heavier grid stroke.
                    HiIcon(Hi.Tick, D.White, 12.dp, disc = true)
                }
            }
        }
        Spacer(Modifier.width(Space.inline))
        Column(Modifier.weight(1f)) {
            // The address, not a name: inventing one here would be the only unverified thing on
            // the sheet. Mono, because it is an identifier — at the body step, because it is
            // this card's title and sits above its own caption.
            Text(p.short, color = D.Black, style = MonoBody)
            Spacer(Modifier.height(Space.tight))
            Text(
                if (p.paid) "First tap landed · joined ${ago(p.at)}" else "Joined ${ago(p.at)}",
                color = D.Quiet, style = Type.caption,
            )
        }
        if (p.paid) {
            Spacer(Modifier.width(Space.inline))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Through [Money], so the + comes from the formatter rather than from a string
                // that happens to start with one.
                Text(skr(reward, sign = true), color = D.SuccessInk, style = FigureBody)
                Spacer(Modifier.width(Space.tight))
                // SKR's own token icon in place of the letters, and no "SKR" text beside it. The
                // icon is a picture of a word, so it carries the word for a screen reader.
                Image(
                    painterResource(R.drawable.token_skr),
                    contentDescription = "SKR",
                    modifier = Modifier.size(20.dp).clip(CircleShape),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ §2.3 the second sheet

/** One step of the explainer: a fill, a mark, and the two lines beside them. */
private class HowStep(
    val fill: Color,
    val title: String,
    val detail: String,
    val mark: @Composable () -> Unit,
)

/**
 * "How it works" (§2.3).
 *
 * Three steps down a line, then the one rule. Every disc is a **solid fill with a white icon** —
 * a status disc's kind of mark — and the note is an **outline**: no pale tinted backgrounds
 * anywhere on this sheet, which is the no-tints rule the rest of the app already follows.
 *
 * Step 3 carries the weight. It is where the reward actually happens, so it is the step that has
 * to say *a real payment* and not *a sign-up*, and the note under the three repeats it — this
 * sheet is exactly where somebody comes to check whether the offer is real.
 */
@Composable
private fun HowItWorksBody(theirReward: Int, reward: Int, onClose: () -> Unit) {
    val steps = listOf(
        HowStep(
            D.Success, "Send them your code",
            "It is on the screen behind this, ready to share.",
        ) {
            // `hi_share` is a drawable rather than a [Hi], so its stroke is the 1.5 the XML
            // hard-codes instead of following the size. Adding it to the [Hi] set means editing
            // Icons.kt, which this change is not allowed to touch; at 20 the two are a quarter
            // of a pixel apart.
            HIcon(R.drawable.hi_share, D.White, 20.dp)
        },
        HowStep(
            D.Blue, "They enter it in AirPay",
            "Before their first payment, a code is an invitation, not a receipt.",
        ) {
            // AirPay's own mark. §6: there is no logo symbol yet, so this is the wordmark's "A"
            // in the logo face — the same thing the sandbox shows, rather than a logo invented
            // here. It is replaced when the real mark arrives.
            Text(
                "A", color = D.White,
                style = Type.heading.copy(fontFamily = Display, fontWeight = FontWeight.ExtraBold),
            )
        },
        HowStep(
            D.Orange, "They pay someone by tapping",
            "They earn ${skr(theirReward)} SKR. You earn ${skr(reward)} SKR.",
        ) {
            HiIcon(Hi.Nfc, D.White, 20.dp, disc = true)
        },
    )

    Spacer(Modifier.height(16.dp))
    Text("How it works", color = D.Black, style = Type.heading)
    Spacer(Modifier.height(24.dp))
    steps.forEachIndexed { i, step ->
        val last = i == steps.lastIndex
        Row(
            Modifier.fillMaxWidth()
                .padding(bottom = if (last) 0.dp else 24.dp)
                // The line down to the next disc, drawn rather than laid out: a `weight` inside
                // the lead column would need the row's height before the text has wrapped, and
                // at draw time the height is simply known. It starts 4 under this disc and stops
                // 4 above the next, which is the sandbox's `my-1`.
                .drawBehind {
                    if (last) return@drawBehind
                    val x = 20.dp.toPx()
                    drawLine(
                        D.Line, Offset(x, 44.dp.toPx()), Offset(x, size.height - 4.dp.toPx()),
                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                    )
                },
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(step.fill),
                contentAlignment = Alignment.Center,
            ) { step.mark() }
            Spacer(Modifier.width(Space.inline))
            Column(Modifier.weight(1f)) {
                Text("STEP ${i + 1}", color = D.Quiet, style = Type.micro)
                Spacer(Modifier.height(Space.tight))
                Text(step.title, color = D.Black, style = BodyBold)
                Spacer(Modifier.height(Space.tight))
                Text(step.detail, color = D.Quiet, style = Type.caption)
            }
        }
    }

    // The rule, boxed by an outline rather than a wash.
    Spacer(Modifier.height(24.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .border(1.5.dp, D.Line, RoundedCornerShape(16.dp)).padding(16.dp),
    ) {
        HiIcon(Hi.Info, D.Black, 20.dp)
        Spacer(Modifier.width(Space.inline))
        Text(
            "Nothing is paid for joining. A wallet costs nothing to make, so the reward waits " +
                "until a real payment has happened.",
            color = D.Black, style = Type.caption,
        )
    }
    Spacer(Modifier.height(24.dp))
    PrimaryButton("Got it", onClose)
}

// ------------------------------------------------------------------ sharing the invite

/** Telegram's own app, tried before the chooser. */
private const val TELEGRAM = "org.telegram.messenger"

/** WhatsApp's own app, tried before the chooser. */
private const val WHATSAPP = "com.whatsapp"

/**
 * Sends the invite, to one named app or to whatever the phone offers.
 *
 * [pkg] is attempted first and the system chooser is the fallback, because on Android 11+ a
 * package the manifest has not declared a `<queries>` entry for is invisible: `resolveActivity`
 * answers null even when the app is installed, and `startActivity` to it throws rather than
 * lying. So the attempt IS the check — and a phone without Telegram still gets a share sheet
 * instead of a dead button.
 *
 * The message carries no link. `duffle.so` does not resolve, and a share whose one link is dead
 * is worse than one with none, so the code is the thing it carries (see [Referrals.message]).
 */
private fun shareInvite(context: Context, text: String, pkg: String?) {
    fun send() = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    fun go(intent: Intent) = runCatching {
        // A Dialog's context is not always the Activity, and starting an activity from a
        // non-activity context without this flag throws.
        context.startActivity(
            if (context is android.app.Activity) intent
            else intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    if (pkg != null && go(send().setPackage(pkg))) return
    go(Intent.createChooser(send(), "Invite a friend"))
}

// ============================================================ the old screen, on its way out

/**
 * Referrals as a **screen** — superseded by [ReferSheet] (ui/PORT-2026-10-01-4.md §2).
 *
 * `ReferralsTab` is no longer a destination: the four places that led here now open [ReferSheet]
 * over whatever the user is already on. This is kept only because `MainActivity.kt` still has the
 * `Tab.REFERRALS` branch that calls it, and that file is not this change's to edit. It goes with
 * that call site; nothing new should be wired to it.
 *
 * The one thing that has already gone from it is the **"Have a code?" door** (§2.1, and §5 item 6
 * checks that none survives): entering somebody else's code belongs to the first-run screens.
 * §6 records the gap that leaves — an invited user has nowhere to type a code until those screens
 * are designed — and it is deliberately not patched here.
 *
 * ### Ported from ui/components/screens/Referrals.tsx (2026-09-30)
 *
 * The card is a poster (PORT.md §9): solid blue, carrying only the figure and one 19sp bold
 * line, because white on `D.Blue` is 3.30:1 and passes only as large text. Everything that was
 * small and white — the eyebrow, their reward, the condition, the share button — moved off the
 * blue onto white. The grey and ink containers are gone with them: the code sits on the page,
 * the invited rows are split by space and a `D.Line` divider.
 */
@Composable
internal fun ReferralsTab(
    connected: String?,
    onShare: (String) -> Unit,
    /** Opens the explainer. Hosted by the Activity so its scrim covers the whole screen. */
    onHowToRefer: (Referrals.Summary) -> Unit,
    /** The name to put in the message — the same one the rest of the app shows for you. */
    myName: String,
) {
    var summary by remember { mutableStateOf<Referrals.Summary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(connected, refresh, Session.version, Live.tick) {
        loading = true
        summary = connected?.let { Referrals.summary(it) }
        loading = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberHeldScroll("referrals"))) {
        if (connected == null) {
            LegacyBlank(
                "Connect a wallet",
                "Your code belongs to a wallet, so there is nothing to show until one is connected.",
            )
            return@Column
        }
        val s = summary
        if (s == null && !loading && !Session.valid(connected)) {
            SignInNeeded("see your referral code")
            return@Column
        }
        if (s == null && loading) {
            ReferralSkeleton()
            return@Column
        }
        if (s == null) {
            LegacyBlank(
                "Can't reach the server",
                "Your code is made by the server, so this screen can't show one right now.",
            )
            return@Column
        }

        HowToRefer { onHowToRefer(s) }
        Spacer(Modifier.height(Space.lead))
        ReferPoster(s)

        // The condition, not small print. It is the whole difference between this offer and one
        // that can be farmed, and on the blue it could only ever have been the smallest line on
        // the screen — so it is the first thing under the card instead.
        Spacer(Modifier.height(Space.row))
        Text("Paid when their first tap lands, not when they join.",
            color = D.Black, style = Type.body)
        Spacer(Modifier.height(Space.tight))
        // An amount inside a sentence, so it stays in the sentence's face — only the figure
        // itself goes through Money.
        Text("They get ${skr(s.theirReward)} SKR on their first tap.",
            color = D.Quiet, style = Type.caption)

        Spacer(Modifier.height(Space.section))
        CodeBlock(
            code = s.code,
            onCopy = { clipboard.setText(AnnotatedString(s.code)) },
            onShare = { onShare(Referrals.message(myName, s.code, s.theirReward)) },
        )

        // What the code has actually done. Only once it has done something: a row of zeroes
        // above an empty list says the same thing twice.
        if (s.invited > 0) {
            Spacer(Modifier.height(Space.section))
            Progress(s)
        }

        Spacer(Modifier.height(Space.section))
        Text("People you invited", color = D.Black, style = Type.heading)
        Spacer(Modifier.height(Space.tight))
        Text(
            if (s.invited == 0) "Nobody yet."
            else "They earn on their first tap. You earn when it lands.",
            color = D.Quiet, style = Type.caption,
        )

        if (s.people.isEmpty()) {
            // No box: two lines on the page, taking a list row's own space above AND below, so
            // the absence occupies the slot the first row would have. A grey panel around
            // "nothing here yet" is a container drawn around an absence.
            Column(Modifier.padding(vertical = Space.row)) {
                Text("Share your code to start", color = D.Black, style = Type.body)
                Spacer(Modifier.height(Space.tight))
                Text("Anyone who uses it shows up here, with whether their first tap has landed.",
                    color = D.Quiet, style = Type.caption)
            }
        } else {
            Spacer(Modifier.height(Space.lead))
            // Only worth a search box once the list is long enough to need one.
            if (s.people.size > 5) {
                SearchBox(query) { query = it }
                Spacer(Modifier.height(Space.lead))
            }
            val shown = s.people.filter {
                query.isBlank() || it.address.contains(query.trim(), ignoreCase = true)
            }
            if (shown.isEmpty()) {
                Text("Nobody matching that.", color = D.Quiet, style = Type.caption)
            }
            shown.forEachIndexed { i, p ->
                if (i > 0) Divider()
                InvitedRow(p, s.reward, first = i == 0)
            }
        }
        Spacer(Modifier.height(Space.section))
    }
}

/** What the old screen said when there was nothing to show. Two lines, no box. */
@Composable
private fun LegacyBlank(title: String, detail: String?) {
    Spacer(Modifier.height(Space.section))
    Text(title, color = D.Black, style = Type.heading)
    if (detail != null) {
        Spacer(Modifier.height(Space.tight))
        Text(detail, color = D.Quiet, style = Type.caption)
    }
}

/** A hairline between two rows. Decorative — the space is what actually separates them. */
@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(D.Line))

/**
 * The way in to the explainer.
 *
 * No pill: the grey body went with the rest of the containers, so this is the mark and the words
 * in blue ink. It is still the only thing on this screen that answers "what am I actually
 * agreeing to" — and the answer includes the condition that the reward waits for a real payment.
 * Somebody who never opens it still reads that line under the card; this is for the person who
 * wants it spelled out.
 */
@Composable
private fun HowToRefer(onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.then(PressScale(interaction))
            // The sandbox's `-mx-2 … px-2`: the hit area reaches 8dp past the gutter on the
            // start side and 8dp past the words on the end side, while the mark itself stays
            // lined up with everything below it. Negative padding does not exist in Compose,
            // so it is an offset out and the same 8dp back in — and the padding sits AFTER
            // clickable, which is what puts it inside the tappable node rather than around it.
            .offset(x = (-8).dp)
            .heightIn(min = 48.dp)
            .clickable(interaction, indication = null, onClick = onOpen)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A 20dp mark beside body text, so it is a small icon and takes the ink, not the fill.
        // Its label is right next to it, so it stays decorative to a screen reader.
        HiIcon(Hi.Info, D.BlueInk, 20.dp)
        // 8, not Space.inline: this is a control's own internals, where the scale applies
        // rather than the layout roles, and the sandbox pairs a 20px mark with an 8px gap.
        Spacer(Modifier.width(8.dp))
        Text("How to refer", color = D.BlueInk, style = Type.body)
    }
}

/**
 * The poster.
 *
 * Loud enough to be the thing you see, and it still refuses to say "assured". Solid blue, no
 * gradient (PORT.md §9), and only large text on it: the figure and one 19sp bold line, because
 * white on `D.Blue` is 3.30:1 and clears AA only as large text. The eyebrow, their reward, the
 * condition and the share button were all small and white, so they are all on white now.
 */
@Composable
private fun ReferPoster(s: Referrals.Summary) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(D.Blue),
    ) {
        // The rings mark — two circles meeting, the way the avatars meet when phones touch —
        // hung off the top-end corner and cut off by the card's own clip. It is the slot for
        // the future graphic, which the poster rule allows.
        //
        // The 15% is that graphic, not a tint: the no-tints rule bans a pale wash used as a
        // BACKGROUND behind words, and at full white a 96dp mark would out-shout the 60sp
        // figure it sits behind. It is one alpha over the whole drawing rather than one per
        // circle, because the two strokes overlap and per-circle alpha would composite that
        // overlap twice — a brighter seam where the rings meet.
        Box(
            Modifier.align(Alignment.TopEnd).offset(x = 16.dp, y = (-12).dp)
                .size(96.dp).alpha(.15f),
        ) {
            // `MarkCanvas` itself, in white. This was a copy of its `Rings` branch, kept only
            // because the mark was private AND hard-coded its own blue — which is what made a
            // copy look necessary on a blue poster. With `tint` a parameter it is the same
            // drawing, and the back ring's .35 alpha comes from the mark rather than from a
            // second place that has to be kept in step.
            MarkCanvas(Mark.Rings, 96.dp, tint = Color.White)
        }
        Column(Modifier.padding(24.dp)) {
            Row {
                // `display` already is the money face with tabular figures.
                Text(skr(s.reward), color = D.White, style = Type.display,
                    modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(8.dp))
                // The unit sits on the figure's own baseline, so the two are one composition.
                // `alignByBaseline`, not `Alignment.Bottom` plus a bottom nudge: a nudge is a
                // number that happens to look right at 60sp and stops being right the moment
                // the font scale moves it.
                Text("SKR", color = D.White, style = Type.heading,
                    modifier = Modifier.alignByBaseline())
            }
            Spacer(Modifier.height(8.dp))
            Text("For you, every friend who taps", color = D.White, style = Type.heading)
        }
    }
}

/**
 * The code itself.
 *
 * The dark rail is gone: the code sits on the page, in the identifier face at the title step.
 * Mono gives every character its own width, which is what tells an O from a 0 when this is read
 * aloud and typed by hand — and it is not the money face, because a code is not an amount. The
 * share button left the blue card and became the page's primary action, whose label is 19sp bold
 * and therefore the one white-on-blue that passes.
 */
@Composable
private fun CodeBlock(code: String, onCopy: () -> Unit, onShare: () -> Unit) {
    val copy = remember { MutableInteractionSource() }
    Text("Your code · tap to copy", color = D.Quiet, style = Type.caption)
    Spacer(Modifier.height(Space.tight))
    Row(
        Modifier.then(PressScale(copy)).fillMaxWidth().heightIn(min = 48.dp)
            .clickable(copy, indication = null, onClick = onCopy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(code, color = D.Black, style = MonoTitle)
        Spacer(Modifier.width(Space.inline))
        // Beside the code it labels, so it stays decorative.
        HiIcon(Hi.Copy, D.Quiet, 20.dp)
    }
    Spacer(Modifier.height(Space.lead))
    PrimaryButton("Share your code", onShare)
}

/** Invited and paid, kept apart — one is a hope and the other is money. */
@Composable
private fun Progress(s: Referrals.Summary) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
        listOf(
            // The first two are counts, not money, so they are not formatted as an amount.
            // Only "SKR earned" goes through Money.
            "${s.invited}" to "invited",
            "${s.paid}" to "have paid",
            skr(s.earned) to "SKR earned",
        ).forEach { (n, label) ->
            Column(Modifier.weight(1f)) {
                // All three in ink. "SKR earned" used to be blue, which made the one figure
                // that is money look like a link.
                Text(n, color = D.Black, style = FigureTitle)
                Spacer(Modifier.height(Space.tight))
                Text(label, color = D.Quiet, style = Type.caption)
            }
        }
    }
}

@Composable
private fun InvitedRow(p: Referrals.Person, reward: Int, first: Boolean) {
    Row(
        Modifier.fillMaxWidth()
            // The container is gone: space above and below, and a hairline between rows.
            .padding(top = if (first) 0.dp else Space.row, bottom = Space.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A white disc with a hairline ring. The paid one used to be a pale blue wash, which is
        // a tint — and the reward in success ink at the end of the row already says which of
        // these has paid.
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(D.White)
                .border(1.5.dp, D.Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(p.address.take(1).uppercase(), color = D.Quiet, style = Type.body)
        }
        Spacer(Modifier.width(Space.inline))
        Column(Modifier.weight(1f)) {
            // The address, not a name: nobody named them, and inventing one here would be the
            // only unverified thing on the screen. Mono, because it is an identifier — at the
            // body step, because it is this row's title and sits above its own caption.
            Text(p.short, color = D.Black, style = MonoBody)
            Spacer(Modifier.height(Space.tight))
            Text(
                if (p.paid) "Joined ${ago(p.at)} · their first tap landed"
                else "Joined ${ago(p.at)} · waiting for their first tap",
                color = D.Quiet, style = Type.caption,
            )
        }
        if (p.paid) {
            // Money in, so it carries the + and the success ink — and the + comes from Money,
            // not from a string that happens to start with one.
            Text(skr(reward, sign = true), color = D.SuccessInk, style = FigureBody)
        } else {
            // An em dash reads as silence, so it says what it means out loud.
            Text("-", color = D.Quiet, style = Type.body,
                modifier = Modifier.semantics { contentDescription = "nothing yet" })
        }
    }
}

@Composable
private fun SearchBox(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        textStyle = Type.body.copy(color = D.Black),
        placeholder = { Text("Search by wallet", color = D.Quiet, style = Type.body) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = D.Blue, unfocusedBorderColor = D.Line,
            focusedTextColor = D.Black, unfocusedTextColor = D.Black,
            cursorColor = D.Blue,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The old explainer: three steps, and the one condition that makes them honest.
 *
 * Superseded by the stacked second sheet in [ReferSheet], and kept for the same reason
 * [ReferralsTab] is: `MainActivity.kt` still hosts it, and that file is not this change's to
 * edit. It goes with that call site.
 *
 * On the app's own [Tray] rather than a Material dialog — same scrim, same spring, same corner
 * as every other sheet here. The steps alternate side to side so the eye zig-zags down them
 * instead of skimming a list, which is the only reason the reference's layout is shaped that
 * way and the only reason to copy it.
 *
 * Step 3 carries the weight. It is where the reward actually happens, so it is the step that
 * has to say *a real payment*, not *a sign-up* — and the line under the three repeats it,
 * because this sheet is exactly where somebody comes to check whether the offer is real.
 *
 * The button says "Share your code", not "Refer via WhatsApp": the share sheet is the phone's,
 * and naming one app we do not integrate with would be a promise about where it goes.
 */
@Composable
internal fun HowToReferSheet(s: Referrals.Summary, onShare: () -> Unit, onClose: () -> Unit) {
    Tray(onClose = onClose) {
        Spacer(Modifier.height(Space.lead))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Inter, not Bricolage: the display face is the wordmark's alone now (PORT.md §2).
            Text("How to refer", color = D.Black, style = Type.title,
                modifier = Modifier.weight(1f))
            val close = remember { MutableInteractionSource() }
            Box(
                // The grey disc went with the other containers; the hit area stays 48dp, which
                // is the part a finger needs.
                Modifier.then(PressScale(close)).size(48.dp)
                    .clickable(close, indication = null, onClick = onClose)
                    .semantics { contentDescription = "Close" },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(16.dp)) {
                    val w = size.minDimension * .125f
                    drawLine(D.Black, Offset.Zero, Offset(size.width, size.height),
                        strokeWidth = w, cap = StrokeCap.Round)
                    drawLine(D.Black, Offset(size.width, 0f), Offset(0f, size.height),
                        strokeWidth = w, cap = StrokeCap.Round)
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        Step(1, "Send them your code", "It is on the screen behind this, ready to share.",
             artOnLeft = false)
        Step(2, "They enter it in AirPay",
             "Before their first payment, a code is an invitation, not a receipt.",
             artOnLeft = true)
        Step(3, "They pay someone by tapping",
             "They earn ${skr(s.theirReward)} SKR. You earn ${skr(s.reward)}.", artOnLeft = false)

        Spacer(Modifier.height(Space.tight))
        Text(
            "Nothing is paid for joining. A wallet costs nothing to make, so the reward waits " +
                "until a real payment has happened.",
            color = D.Quiet, style = Type.caption,
        )
        Spacer(Modifier.height(Space.row))
        PrimaryButton("Share your code", onShare)
        Spacer(Modifier.height(Space.tight))
    }
}

/** One step: a number, a line, and a drawing, with the drawing on alternating sides. */
@Composable
private fun Step(n: Int, title: String, detail: String, artOnLeft: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = Space.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (artOnLeft) {
            StepArt(n)
            Spacer(Modifier.width(Space.inline))
        }
        Column(Modifier.weight(1f)) {
            Text("STEP $n", color = D.Quiet, style = Type.micro)
            Spacer(Modifier.height(Space.tight))
            Text(title, color = D.Black, style = Type.body)
            Spacer(Modifier.height(Space.tight))
            Text(detail, color = D.Quiet, style = Type.caption)
        }
        if (!artOnLeft) {
            Spacer(Modifier.width(Space.inline))
            StepArt(n)
        }
    }
}

/**
 * The three vignettes, drawn rather than drawn *for* us.
 *
 * Built from the shapes the rest of the app already uses — the code rail, a phone, the two
 * rings of a tap, the coin discs of a reward — so the sheet reads as this app explaining
 * itself rather than as stock illustration dropped into it.
 *
 * The pale blue disc these used to sit on was a tint used as a background, which the palette
 * no longer allows; the drawing now sits on the sheet. The opacities *inside* the drawing stay:
 * they are how a mark reads as behind or in front of another one, not a wash behind words.
 */
@Composable
private fun StepArt(n: Int) {
    Box(Modifier.size(86.dp)) {
        Canvas(Modifier.fillMaxSize().padding(18.dp)) {
            val w = size.width
            val h = size.height
            val line = w * .085f
            when (n) {
                // 1 — the code rail, going out. The arrow leaves the card, which is the whole
                // action of the step.
                1 -> {
                    drawRoundRect(D.Blue, Offset(0f, h * .16f), Size(w * .74f, h * .40f),
                        CornerRadius(w * .10f))
                    drawLine(Color.White, Offset(w * .10f, h * .30f), Offset(w * .50f, h * .30f),
                        strokeWidth = line * .8f, cap = StrokeCap.Round)
                    drawLine(Color.White, Offset(w * .10f, h * .43f), Offset(w * .36f, h * .43f),
                        strokeWidth = line * .8f, cap = StrokeCap.Round)
                    drawLine(D.Blue, Offset(w * .22f, h * .80f), Offset(w, h * .80f),
                        strokeWidth = line, cap = StrokeCap.Round)
                    drawLine(D.Blue, Offset(w * .76f, h * .62f), Offset(w, h * .80f),
                        strokeWidth = line, cap = StrokeCap.Round)
                    drawLine(D.Blue, Offset(w * .76f, h * .98f), Offset(w, h * .80f),
                        strokeWidth = line, cap = StrokeCap.Round)
                }
                // 2 — a phone with the code typed into it, and a tick.
                2 -> {
                    drawRoundRect(D.Blue, Offset(w * .16f, 0f), Size(w * .52f, h),
                        CornerRadius(w * .14f), style = Stroke(line))
                    drawRoundRect(D.Blue.copy(alpha = .30f), Offset(w * .26f, h * .30f),
                        Size(w * .32f, h * .16f), CornerRadius(w * .05f))
                    drawLine(D.Blue, Offset(w * .32f, h * .68f), Offset(w * .40f, h * .78f),
                        strokeWidth = line, cap = StrokeCap.Round)
                    drawLine(D.Blue, Offset(w * .40f, h * .78f), Offset(w * .60f, h * .56f),
                        strokeWidth = line, cap = StrokeCap.Round)
                }
                // 3 — two rings meeting, the way the avatars meet on a tap, over a coin.
                else -> {
                    val r = w * .26f
                    drawCircle(D.Blue.copy(alpha = .40f), r, Offset(w * .34f, h * .38f),
                        style = Stroke(line))
                    drawCircle(D.Blue, r, Offset(w * .66f, h * .38f), style = Stroke(line))
                    listOf(.86f to .34f, .74f to .20f).forEachIndexed { i, (cw, dy) ->
                        drawOval(D.Blue.copy(alpha = .35f + i * .35f),
                            Offset(w * (1 - cw) / 2, h * (.70f + dy)), Size(w * cw, h * .17f))
                    }
                }
            }
        }
    }
}


/**
 * The sheet for a friend's code: one field, one button, and what happened.
 *
 * It was a field at the foot of the first sheet, where the keyboard covered it. A sheet of its
 * own rides above the keyboard, and takes the focus as it opens so there is nothing to tap first.
 */
@Composable
private fun FriendCodeBody(
    address: String?,
    theirReward: Int,
    open: Boolean,
    onDone: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val field = remember { androidx.compose.ui.focus.FocusRequester() }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // Null until tried. An empty string is success.
    var outcome by remember { mutableStateOf<String?>(null) }
    // Focus when it opens, let go when it closes, so the keyboard comes and goes with the sheet.
    LaunchedEffect(open) {
        if (open && outcome != "") {
            // A refused code does not come back with the sheet: reopened, it showed the old code
            // in red with nothing to press. A half-typed one is kept, and so is a check in flight.
            if (!busy && outcome != null) { code = ""; outcome = null }
            kotlinx.coroutines.delay(220); runCatching { field.requestFocus() }
        }
        else focus.clearFocus()
    }

    val done = outcome == ""
    val refused = outcome?.takeIf { it.isNotEmpty() }

    fun apply(entered: String) {
        val me = address ?: return
        if (busy) return
        busy = true
        scope.launch {
            val no = Referrals.claim(entered, me)
            busy = false
            outcome = no ?: ""
            if (no == null) { focus.clearFocus(); Haptics.success(context); onDone() }
            // These two mean the door is shut for good, whatever was typed.
            if (no == "already_referred" || no == "referral_too_late") onDone()
        }
    }

    Spacer(Modifier.height(Space.row))
    Text("Enter a friend's code", color = D.Black, style = Type.heading)
    Spacer(Modifier.height(Space.tight))
    Text(
        if (theirReward > 0) "Your first rewarded tap then earns you $theirReward SKR, and your friend earns too."
        else "Your first rewarded tap then earns for both of you.",
        color = D.Quiet, style = Type.caption,
    )
    Spacer(Modifier.height(Space.row))

    // One box per character, the way a one-time code is entered: a code has a known length,
    // and eight boxes say so before a single key is pressed. The field itself is invisible;
    // the boxes are its decoration, so the keyboard, paste and the cursor all still work.
    // The eighth character sends it. All eight turn green when it is accepted, red when not.
    val edge by animateColorAsState(
        when { done -> D.Success; refused != null -> D.DangerInk; else -> D.Line }, label = "code-edge",
    )
    androidx.compose.foundation.text.BasicTextField(
        value = code,
        onValueChange = { v ->
            if (done || busy) return@BasicTextField
            val next = v.filter { it.isLetterOrDigit() }.take(CODE_LENGTH).uppercase()
            if (next != code) { outcome = null; code = next; if (next.length == CODE_LENGTH) apply(next) }
        },
        singleLine = true,
        enabled = !done,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent),
        textStyle = Type.mono.copy(color = Color.Transparent),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().focusRequester(field),
        decorationBox = { _ ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(CODE_LENGTH) { i ->
                    val ch = code.getOrNull(i)
                    // The box being typed into is the one outlined in blue.
                    val here = !done && refused == null && i == code.length
                    val shape = RoundedCornerShape(12.dp)
                    Box(
                        // A minimum, so a large font grows the box instead of cutting the letter.
                        Modifier.weight(1f).heightIn(min = 52.dp).clip(shape)
                            .background(if (done) D.Success.copy(alpha = .10f) else Color.Transparent)
                            .border(if (here) 2.dp else 1.5.dp, if (here) D.Blue else edge, shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ch?.toString().orEmpty(),
                            color = if (done) D.SuccessInk else D.Black,
                            style = Type.heading.copy(fontFamily = Type.mono.fontFamily),
                        )
                    }
                }
            }
        },
    )
    Spacer(Modifier.height(Space.inline))
    // One line under the boxes says where things stand, so nothing has to be pressed to find out.
    Text(
        when {
            done -> "Code added. Your first rewarded tap now earns for both of you."
            refused != null -> Referrals.why(refused) ?: "That code could not be added"
            busy -> "Checking the code…"
            else -> "It is checked as soon as the last character is in."
        },
        color = when { done -> D.SuccessInk; refused != null -> D.DangerInk; else -> D.Quiet },
        style = Type.caption,
    )
    if (done) {
        Spacer(Modifier.height(Space.row))
        PrimaryButton("Done", onClose, Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(Space.row))
}

/** A referral code is eight letters and digits. */
private const val CODE_LENGTH = 8
