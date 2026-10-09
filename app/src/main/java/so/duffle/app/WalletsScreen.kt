package so.duffle.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

/**
 * The Wallets screen (ui/PORT-2026-10-02-2.md): a card per linked wallet, a lift into a sheet.
 *
 * Moved out of `MainActivity.kt` and rewritten. The old list painted a flat card with a dot-dot
 * address and "Use this one"; the card is now a 37:24 face with the wallet's own icon, its
 * address and a copy button, its name and what it holds, and the Active badge on the one a tap
 * pays from.
 *
 * ### Opening a wallet
 *
 * A press lifts the card to 64dp from the top as a **shared element** (`sharedBounds`, keyed on
 * the address). The list is blurred and dimmed behind it, the card's place in the list is kept
 * empty (the slot has a fixed height, so nothing reflows), and the sheet rises from 12dp under
 * the card. There is no close button: tap the dim, drag the sheet's handle down, or go back.
 *
 * ### What each wallet holds
 *
 * The app loads one wallet's holdings at Home. The cards need every wallet's, so this screen
 * loads each on open ([loadWorth]) and shows "—" for a balance still loading or unreachable —
 * never a zero, which would claim the wallet is empty.
 */

private val REST = 64.dp

/**
 * The app's own bounds stiffness (the review sheet's), critically damped. At 0.86 the bounds
 * overshoot, and the card read as growing taller on the way up and shorter on the way down.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private val LIFT = BoundsTransform { _, _ -> spring<Rect>(dampingRatio = 1f, stiffness = 380f) }

/** What one wallet holds, priced. */
internal class Worth(val holdings: List<Tokens.Holding>, val quotes: Map<String, Market.Quote>, val usd: Double?)

/** A finished load: [worth] is null when Solana could not be reached. Absent from the map = loading. */
private class Loaded(val worth: Worth?)

internal suspend fun loadWorth(ctx: Context, address: String): Worth? {
    val h = withContext(Dispatchers.IO) { Tokens.holdings(ctx, address) } ?: return null
    val q = Market.quotes(h.map { it.mint ?: Market.WSOL })
    val priced = h.mapNotNull { t ->
        q[t.mint ?: Market.WSOL]?.usd?.let { u -> BigDecimal.valueOf(t.units, t.decimals).toDouble() * u }
    }
    return Worth(h, q, if (priced.isEmpty()) null else priced.sum())
}

/** Home's rule for which tokens are worth a card: SOL, and verified tokens that have a logo. */
private fun shown(w: Worth): List<Tokens.Holding> =
    w.holdings.filter { t ->
        val k = w.quotes[t.mint]
        t.isSol || w.quotes.isEmpty() || (k?.verified == true && !k.icon.isNullOrBlank())
    }.sortedBy { t ->
        val worth = w.quotes[t.mint ?: Market.WSOL]?.usd?.let { u ->
            BigDecimal.valueOf(t.units, t.decimals).multiply(BigDecimal(u.toString()))
        }
        if (worth != null && !Money.cutsToZero(worth, 2)) 0 else 1
    }

/** Lets a child draw past its parent's edges: the dim has to reach the screen's, not the gutter's. */
private fun Modifier.bleed(l: Int, t: Int, r: Int, b: Int) = layout { m, c ->
    val p = m.measure(Constraints.fixed(c.maxWidth + l + r, c.maxHeight + t + b))
    layout(c.maxWidth, c.maxHeight) { p.place(-l, -t) }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun WalletsScreen(
    list: List<Linked>,
    selected: String?,
    busy: Boolean,
    onUse: (Linked) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
    onReceive: (Linked) -> Unit,
    onRename: (Linked) -> Unit,
    onReconnect: (Linked) -> Unit,
    onVouch: (Linked) -> Unit,
    vouchTick: Int,
    onRemove: (Linked) -> Unit,
    onNote: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val loads = remember { mutableStateMapOf<String, Loaded>() }
    LaunchedEffect(list.map { it.address }) {
        list.forEach { w ->
            // A failed read is retried, not shown: one dropped connection used to leave "—" on
            // the card until the screen was reopened. A good read is kept when a later one fails
            // and refreshed every half minute while the screen is up.
            launch {
                var wait = 1_000L
                while (true) {
                    val got = runCatching { loadWorth(ctx, w.address) }.getOrNull()
                    if (got != null && (got.usd != null || loads[w.address]?.worth?.usd == null)) {
                        loads[w.address] = Loaded(got)
                        wait = if (got.usd != null) 30_000L else 3_000L
                    } else if (got != null) {
                        wait = 3_000L
                    } else if (loads[w.address] == null) {
                        wait = (wait * 2).coerceAtMost(8_000L)
                        // Three misses in, it stops being "loading". The card showed its bar for
                        // as long as Solana stayed out of reach and never said so. The retries
                        // go on underneath, and the first good read replaces this.
                        if (wait >= 8_000L) loads[w.address] = Loaded(null)
                    }
                    kotlinx.coroutines.delay(wait)
                }
            }
        }
    }

    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(list) { if (open != null && list.none { it.id == open }) open = null }
    val openW = list.firstOrNull { it.id == open }
    // Kept after closing so the sheet has something to draw while it slides out.
    var last by remember { mutableStateOf<Linked?>(null) }
    LaunchedEffect(openW) { if (openW != null) last = openW }
    val sheetW = openW ?: last

    val dim = animateFloatAsState(if (open != null) 1f else 0f, tween(300), label = "dim")
    BackHandler(enabled = open != null) { open = null }

    val dragY = remember { Animatable(0f) }
    // A card cannot be lifted again until the last one has landed. Opening another wallet while
    // the first was still travelling back left two cards in flight at once.
    var settling by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open == null) {
            // Only when a card is on its way back. On the screen's first frame nothing is, and
            // waiting anyway made every card ignore a press for its first half second.
            if (last != null) { settling = true; delay(480) }
            dragY.snapTo(0f); settling = false
        } else dragY.snapTo(0f)
    }
    val dragState = rememberDraggableState { d -> scope.launch { dragY.snapTo((dragY.value + d).coerceAtLeast(0f)) } }
    val handle = Modifier.draggable(dragState, Orientation.Vertical, onDragStopped = { v ->
        if (dragY.value > with(density) { 120.dp.toPx() } || v > 1500f) open = null
        else dragY.animateTo(0f, spring())
    })

    val gutterPx = with(density) { Space.gutter.roundToPx() }
    val navPx = WindowInsets.navigationBars.getBottom(density)
    // Where this screen's box sits in the window, measured. The dim has to reach the window's
    // edges, and the insets cannot say how far that is: the parent has already consumed them.
    var frame by remember { mutableStateOf(androidx.compose.ui.unit.IntRect.Zero) }
    val win = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize

    BoxWithConstraints(
        Modifier.fillMaxSize().onGloballyPositioned { c ->
            val o = c.positionInWindow()
            frame = androidx.compose.ui.unit.IntRect(
                o.x.toInt(), o.y.toInt(), o.x.toInt() + c.size.width, o.y.toInt() + c.size.height,
            )
        },
    ) {
        val cardW = maxWidth
        val cardH = cardW * 24f / 37f

        SharedTransitionLayout {
            Box(Modifier.fillMaxSize()) {
                // ---------------------------------------------------------------- the list
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        // Read here, in the layer, so the animation redraws one layer a frame
                        // instead of recomposing the whole screen sixty times a second.
                        val r = 12.dp.toPx() * dim.value
                        renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Decal) else null
                    },
                ) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberHeldScroll("wallets"))) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BackArrow(onClose)
                            Text("Wallets", color = D.Black, style = Type.title, maxLines = 1)
                        }
                        Spacer(Modifier.height(Space.lead))
                        list.forEach { w ->
                            val active = w.id == selected
                                                        // Padding BEFORE height: `height(cardH).padding(...)` put the 12dp gap
                            // inside the card's own height, so the list card was 12dp shorter than
                            // the lifted one and visibly stretched on the way up and shrank on the
                            // way back.
                            Box(
                                Modifier.fillMaxWidth().padding(bottom = Space.inline).height(cardH)
                            ) {
                                // The slot holds its size while the card is lifted out of it.
                                Box(
                                    Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))
                                        .clickable(remember { MutableInteractionSource() }, indication = null) {
                                            if (!settling) open = w.id
                                        },
                                ) {
                                    Slot(visible = open != w.address) {
                                        WalletFace(
                                            w, active, { loads[w.address]?.worth?.usd }, { loads[w.address] == null }, { if (w.id == sheetW?.id) dim.value else 0f },
                                            onCopy = { clip.setText(AnnotatedString(w.address)); Haptics.tick(ctx) },
                                            modifier = Modifier.fillMaxSize().sharedBounds(
                                                rememberSharedContentState("wallet-${w.id}"), this,
                                                enter = EnterTransition.None, exit = ExitTransition.None,
                                                boundsTransform = LIFT,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                        if (list.isEmpty()) {
                            Text("No wallets linked yet.", color = D.Quiet, style = Type.body)
                        }
                        // Clear of the floating button.
                        Spacer(Modifier.height(100.dp))
                    }

                    // Add wallet, floating: Home's pay bar as one button — an ink capsule on a
                    // white ring under the card shadow, as wide as its words.
                    val press = remember { MutableInteractionSource() }
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) {
                        Box(
                            Modifier.cardShadow(31.dp).clip(RoundedCornerShape(31.dp))
                                .background(D.White).padding(5.dp),
                        ) {
                            Row(
                                Modifier.then(PressScale(press)).clip(CircleShape).background(D.Black)
                                    .clickable(press, indication = null, enabled = !busy, onClick = onAdd)
                                    .height(52.dp).padding(start = 16.dp, end = 24.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                HiIcon(Hi.Add, D.White, 24.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(if (busy) "Linking…" else "Add wallet", color = D.White, style = Type.heading)
                            }
                        }
                    }
                }

                // -------------------------------------------------------- dim, card, sheet
                Box(
                    Modifier.fillMaxSize().bleed(
                        frame.left, frame.top, (win.width - frame.right).coerceAtLeast(0),
                        (win.height - frame.bottom).coerceAtLeast(0),
                    ),
                ) {
                    Box(
                        Modifier.fillMaxSize()
                            .drawBehind { drawRect(Color.Black.copy(alpha = .28f * dim.value)) }
                            // Only while a wallet is open: even disabled, a clickable here
                            // sits over the list and the cards under it stop hearing taps.
                            .then(
                                if (open != null) Modifier.clickable(
                                    remember { MutableInteractionSource() }, indication = null,
                                ) { open = null } else Modifier,
                            ),
                    )
                    list.forEach { w ->
                        Slot(
                            visible = open == w.address,
                            modifier = Modifier.padding(start = with(density) { frame.left.toDp() }, top = REST)
                                .requiredSize(cardW, cardH),
                        ) {
                            WalletFace(
                                w, w.id == selected, { loads[w.address]?.worth?.usd }, { loads[w.address] == null }, { dim.value },
                                onCopy = { clip.setText(AnnotatedString(w.address)); Haptics.tick(ctx) },
                                modifier = Modifier.fillMaxSize().sharedBounds(
                                    rememberSharedContentState("wallet-${w.id}"), this,
                                    enter = EnterTransition.None, exit = ExitTransition.None,
                                    boundsTransform = LIFT,
                                ),
                            )
                        }
                    }
                    // In the overlay, above every shared element. A card that is NOT the one being lifted
                    // can still be drawn in the overlay for a few frames of the transition; with the sheet
                    // in the normal layer that card landed on top of it. Ranked above them (the lifted
                    // card is clear of the sheet, so nothing that matters is hidden), it cannot.
                    Box(
                        Modifier.fillMaxSize().padding(top = REST + cardH + 12.dp)
                            .renderInSharedTransitionScopeOverlay(zIndexInOverlay = 10f),
                    ) {
                        AnimatedVisibility(
                            visible = open != null,
                            enter = slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it },
                            exit = slideOutVertically(tween(260, easing = FastOutSlowInEasing)) { it },
                            modifier = Modifier.graphicsLayer { translationY = dragY.value },
                        ) {
                            sheetW?.let { w ->
                                val standing = remember(w.address, vouchTick, list) { standingOf(ctx, w, list) }
                                WalletSheet(
                                    w = w,
                                    active = w.id == selected,
                                    load = { loads[w.address] },
                                    standing = standing,
                                    handle = handle,
                                    bottomInset = with(density) { navPx.toDp() },
                                    onUse = { onUse(w) },
                                    onReceive = { onReceive(w) },
                                    onRename = { onRename(w) },
                                    onReconnect = { onReconnect(w) },
                                    onVouch = { onVouch(w) },
                                    onRemove = { open = null; onRemove(w) },
                                    onCopyMint = { mint ->
                                        clip.setText(AnnotatedString(mint)); Haptics.tick(ctx); onNote("- copied $mint")
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A card's balance, in a composable of its own. Reading the state here, not in the screen,
 * means a balance arriving recomposes this one Text instead of every card, the shared-element
 * hosts and the sheet — which cost 45 ms in a debug build, in the middle of an animation.
 */
@Composable
private fun Balance(usd: () -> Double?, loading: () -> Boolean, color: Color) {
    val v = usd()
    // Loading is its own thing from "could not read": a bar while it is on its way, "—" only once
    // the answer came back empty.
    if (v == null && loading()) CardBalanceSkeleton()
    else Text(v?.let { Money.fiat(BigDecimal.valueOf(it), "USD") } ?: "-", color = color,
        style = Type.title.figure(), maxLines = 1)
}

/** A shared element's home. Top level, so it resolves the plain AnimatedVisibility, not a scope's. */
@Composable
private fun Slot(visible: Boolean, modifier: Modifier = Modifier, content: @Composable AnimatedVisibilityScope.() -> Unit) =
    AnimatedVisibility(visible, modifier, EnterTransition.None, ExitTransition.None, content = content)

/** The card's face: the same drawing in the list and lifted out of it. */
@Composable
internal fun WalletFace(
    w: Linked, active: Boolean, usd: () -> Double?, loading: () -> Boolean, lift: () -> Float,
    onCopy: () -> Unit, modifier: Modifier,
    /** Drawn in the bottom corner in place of the "Active" pill: the tap sheet's contactless sign. */
    corner: (@Composable () -> Unit)? = null,
) {
    val lum = 0.2126f * w.colour.red + 0.7152f * w.colour.green + 0.0722f * w.colour.blue
    val ink = if (lum > 0.5f) D.Black else D.White
    // Seeker's card runs black at the top to mint at the foot, so one ink cannot serve both rows.
    val top = if (w.isSeeker) D.White else ink
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }

    Column(
        // The shadow rides on the card itself (before the clip), so it travels with the shared
        // element instead of being painted at the card's resting place. It fades in with the lift.
        modifier.graphicsLayer {
            shadowElevation = 8.dp.toPx() * lift()
            shape = RoundedCornerShape(24.dp)
            clip = false
            ambientShadowColor = Color.Black.copy(alpha = .10f)
            spotShadowColor = Color.Black.copy(alpha = .30f)
        }.clip(RoundedCornerShape(24.dp)).background(w.fill).padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                // The card keeps the wallet app's own icon: see [Linked.cardIconRes].
                painterResource(w.cardIconRes), contentDescription = null,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.weight(1f))
            Text(w.short, color = top, style = Type.mono.copy(fontSize = 15.sp, lineHeight = 20.sp))
            // 20 in a 36 x 48 target, pulled 8 past the end: the icon sits at the content's edge
            // with 8 between it and the address, not the 14 a square 48 target left.
            // Only this copies; a press anywhere else opens the wallet.
            Box(
                Modifier.requiredSize(36.dp, 48.dp).offset(x = 8.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        onCopy(); copied = true
                    },
                contentAlignment = Alignment.Center,
            ) { HiIcon(if (copied) Hi.Tick else Hi.Copy, top, 20.dp) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(w.username ?: w.name, color = D.Black.takeIf { w.isSeeker } ?: ink,
                    style = Type.body.copy(fontWeight = FontWeight.Bold), maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Space.tight))
                Balance(usd, loading, if (w.isSeeker) D.Black else ink)
            }
            corner?.invoke()
            // White on #1F7A35 is 5.39:1; the bright green is 3.32 and fails at this size. The
            // white ring is what separates the pill from Phantom's purple and Backpack's black.
            AnimatedVisibility(
                visible = active,
                enter = scaleIn(tween(250), initialScale = .5f) + fadeIn(tween(250)),
                exit = fadeOut(tween(120)),
            ) {
                Row(
                    Modifier.clip(CircleShape).background(D.SuccessInk)
                        .border(2.dp, D.White, CircleShape)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Active", color = D.White, style = Type.caption.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

@Composable
private fun WalletSheet(
    w: Linked,
    active: Boolean,
    load: () -> Loaded?,
    standing: Standing,
    handle: Modifier,
    bottomInset: androidx.compose.ui.unit.Dp,
    onUse: () -> Unit,
    onReceive: () -> Unit,
    onRename: () -> Unit,
    onReconnect: () -> Unit,
    onVouch: () -> Unit,
    onRemove: () -> Unit,
    onCopyMint: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var confirming by remember(w.address) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(D.White)
            // A press on the sheet must not fall through to the dim behind it and close it.
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Box(handle.fillMaxWidth().height(28.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.padding(top = 8.dp).size(36.dp, 4.dp).clip(CircleShape).background(D.Line))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter),
        ) {
            TokensSection(w.address, load, onCopyMint)
            RowLine()
            SheetRow(Hi.Receive, "Receive", "Show this wallet's QR code", onClick = onReceive)
            RowLine()
            SheetRow(Hi.Edit, "Rename", "A name of your own for it", onClick = onRename)
            RowLine()
            // A wallet whose session is gone says so here, in the warning ink: it is still listed
            // and still shows a balance, so nothing else on the card or the sheet gave it away.
            SheetRow(
                Hi.Refresh, "Reconnect",
                if (w.token == null) "Not connected. Tap to link this wallet again"
                else "If the wallet has stopped answering",
                detailTint = if (w.token == null) D.OrangeInk else D.Quiet,
                onClick = onReconnect,
            )
            RowLine()
            val v = standing.row()
            SheetRow(
                if (standing.verified) Hi.Verified else Hi.CheckCircle, v.first, v.second,
                iconTint = if (standing.verified) D.BlueInk else D.Black,
                detailTint = if (standing.expiringSoon) D.OrangeInk else D.Quiet,
                onClick = onVouch,
            )
            RowLine()
            // Red: the one row here that cannot be undone from this sheet. Asks twice.
            SheetRow(
                Hi.Delete, if (confirming) "Tap again to remove" else "Remove wallet",
                "Unlinks it from AirPay. Your funds stay in the wallet", danger = true,
            ) { if (confirming) onRemove() else { confirming = true; Haptics.tick(ctx) } }

            Spacer(Modifier.height(32.dp))
            BrandMark(28.dp, Modifier.align(Alignment.CenterHorizontally).alpha(0.55f))
            // A line box taller than the glyphs: Bricolage's default one cut the tail of the y.
            Text(
                "AirPay", color = D.Neutral, fontSize = 19.sp, lineHeight = 30.sp,
                fontWeight = FontWeight.ExtraBold, fontFamily = Display, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(32.dp))
        }

        // The one thing this sheet is for, pinned to the foot so it never scrolls away: green on a
        // wallet that is not the active one, a statement on the one that is. White 19 bold on the
        // green is 3.32:1 (large text needs 3).
        Box(
            Modifier.fillMaxWidth().background(D.White)
                .padding(start = Space.gutter, end = Space.gutter, top = 12.dp, bottom = 12.dp + bottomInset),
        ) {
            if (active) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp)
                        .border(1.5.dp, D.SuccessInk, RoundedCornerShape(18.dp)),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    HiIcon(Hi.Tick, D.SuccessInk, 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Your active wallet", color = D.SuccessInk, style = Type.heading)
                }
            } else {
                val press = remember { MutableInteractionSource() }
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).then(PressScale(press))
                        .clip(RoundedCornerShape(18.dp)).background(D.Success)
                        .clickable(press, indication = null) { Haptics.tick(ctx); onUse() }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Set as active wallet", color = D.White, style = Type.heading) }
            }
        }
    }
}

/**
 * Tokens, as a row that opens onto Home's own token cards for this wallet.
 *
 * Its own composable so the loaded balances are read here: when they arrive, this section
 * recomposes and the rest of the sheet — and the screen behind it — does not.
 */
@Composable
private fun TokensSection(address: String, load: () -> Loaded?, onCopyMint: (String) -> Unit) {
    var open by rememberSaveable(address) { mutableStateOf(false) }
    val loaded = load()
    val worth = loaded?.worth
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(200), label = "tokens")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HiIcon(Hi.Wallet, D.Black, 24.dp)
        Spacer(Modifier.width(Space.inline))
        Column(Modifier.weight(1f)) {
            Text("Tokens", color = D.Black, style = Type.body)
            Spacer(Modifier.height(Space.tight))
            if (loaded == null) SkeletonLine(96.dp, 11.dp)
            else Text(
                if (worth == null) "Couldn't reach Solana" else "${shown(worth).size} in this wallet",
                color = D.Quiet, style = Type.caption,
            )
        }
        HiIcon(Hi.ArrowRight, D.Quiet, 20.dp, modifier = Modifier.graphicsLayer { rotationZ = turn })
    }
    // The token row runs out to the screen edge, past this column's gutter, and
    // AnimatedVisibility would clip it to the gutter while it opens — so the clip is swapped for
    // one that is tight vertically and wide horizontally (as on Home).
    val bleed = with(LocalDensity.current) { Space.gutter.toPx() }
    AnimatedVisibility(
        open && worth != null,
        modifier = Modifier.drawWithContent {
            clipRect(-bleed, 0f, size.width + bleed, size.height) { this@drawWithContent.drawContent() }
        },
        enter = fadeIn() + expandVertically(clip = false),
        exit = fadeOut() + shrinkVertically(clip = false),
    ) {
        if (worth != null) TokenBadges(shown(worth), worth.quotes, onCopyMint)
    }
}

@Composable
private fun SheetRow(
    icon: Hi, title: String, detail: String, danger: Boolean = false,
    iconTint: Color? = null, detailTint: Color = D.Quiet, onClick: () -> Unit,
) {
    val tint = if (danger) D.DangerInk else D.Black
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HiIcon(icon, iconTint ?: tint, 24.dp)
        Spacer(Modifier.width(Space.inline))
        Column(Modifier.weight(1f)) {
            Text(title, color = tint, style = Type.body)
            Spacer(Modifier.height(Space.tight))
            Text(detail, color = detailTint, style = Type.caption)
        }
        if (!danger) HiIcon(Hi.ArrowRight, D.Quiet, 20.dp)
    }
}

/** The wallet app's own icon from the system; these artwork files are the fallback. */
private fun brandIcon(app: WalletLink.App): Int? {
    val s = "${app.label} ${app.pkg}".lowercase()
    return when {
        "phantom" in s -> R.drawable.ic_wallet_phantom
        "solflare" in s -> R.drawable.ic_wallet_solflare
        "backpack" in s -> R.drawable.ic_wallet_backpack
        "seeker" in s || "solanamobile" in s || "seed vault" in s -> R.drawable.ic_wallet_seeker
        else -> null
    }
}

@Composable
internal fun WalletPicker(apps: List<WalletLink.App>, onPick: (WalletLink.App) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Add a wallet", color = D.Black, style = Type.title)
        Spacer(Modifier.height(Space.tight))
        Text("AirPay never sees your keys. The wallet signs.", color = D.Quiet, style = Type.caption)
        Spacer(Modifier.height(Space.lead))
        if (apps.isEmpty()) {
            Text(
                "No wallet apps found on this phone. Install one, such as Phantom or Solflare, then try again.",
                color = D.Quiet, style = Type.body,
            )
        }
        apps.forEachIndexed { i, app ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(app) }
                    .padding(vertical = Space.row),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = remember(app.pkg) { app.icon?.let { runCatching { it.toBitmap().asImageBitmap() }.getOrNull() } }
                val art = brandIcon(app)
                val mark = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                when {
                    icon != null -> Image(BitmapPainter(icon), null, mark, contentScale = ContentScale.Fit)
                    art != null -> Image(painterResource(art), null, mark, contentScale = ContentScale.Fit)
                    else -> Box(mark.background(D.White).border(1.5.dp, D.Line, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center) { HiIcon(Hi.Wallet, D.BlueInk, 20.dp) }
                }
                Spacer(Modifier.width(Space.inline))
                Text(app.label, color = D.Black, style = Type.body, modifier = Modifier.weight(1f))
                HiIcon(Hi.ArrowUpRight, D.Quiet, 20.dp)
            }
            if (i < apps.lastIndex) RowLine()
        }
        Spacer(Modifier.height(Space.lead))
    }
}

/** Which page of the add-a-wallet sheet is showing. */
private enum class AddStep { Choose, External, Hardware }

/**
 * Adding a wallet, in two steps: first which KIND, then the list or the tap.
 *
 * A wallet app (Phantom, Solflare, the Seeker's own) is linked through Mobile Wallet Adapter, as
 * before. A hardware wallet is a card held against the back of the phone. That second path is the
 * screen only for now: it shows what to do and says plainly that it is not live, and it never
 * pretends to have read a card.
 */
@Composable
internal fun AddWalletFlow(apps: List<WalletLink.App>, onPick: (WalletLink.App) -> Unit) {
    var step by rememberSaveable { mutableStateOf(AddStep.Choose) }
    Column(Modifier.fillMaxWidth()) {
        when (step) {
            AddStep.Choose -> {
                Text("Add a wallet", color = D.Black, style = Type.title)
                Spacer(Modifier.height(Space.tight))
                Text("AirPay never sees your keys. The wallet signs.", color = D.Quiet, style = Type.caption)
                Spacer(Modifier.height(Space.lead))
                SheetRow(Hi.Wallet, "Connect a wallet app", "Phantom, Solflare and other wallets on this phone") {
                    step = AddStep.External
                }
                RowLine()
                SheetRow(Hi.Nfc, "Connect a hardware wallet", "Tap a card on the back of your phone") {
                    step = AddStep.Hardware
                }
                Spacer(Modifier.height(Space.lead))
            }
            AddStep.External -> {
                BackLink { step = AddStep.Choose }
                WalletPicker(apps, onPick)
            }
            AddStep.Hardware -> {
                BackLink { step = AddStep.Choose }
                CardTapAnimation()
                Spacer(Modifier.height(Space.lead))
                Text("Tap your card on the back of your phone", color = D.Black, style = Type.title)
                Spacer(Modifier.height(Space.tight))
                Text(
                    "Hold it flat against the back, near the top, until the phone buzzes.",
                    color = D.Quiet, style = Type.body,
                )
                Spacer(Modifier.height(Space.lead))
                Text(
                    "Hardware wallets are coming soon. Nothing is read yet.",
                    color = D.Black, style = Type.caption,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(D.Highlight)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(Space.section))
            }
        }
    }
}

/** A quiet "Back" at the head of a sheet's second page. */
@Composable
private fun BackLink(onBack: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onBack)
            .heightIn(min = 48.dp).padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HiIcon(Hi.ArrowLeft, D.Quiet, 20.dp)
        Spacer(Modifier.width(4.dp))
        Text("Back", color = D.Quiet, style = Type.body)
    }
}

/**
 * A card coming down onto the back of a phone, over and over: the gesture, shown rather than
 * described. The rings appear as the card arrives, where the phone's NFC coil reads it.
 */
@Composable
private fun CardTapAnimation() {
    val loop = androidx.compose.animation.core.rememberInfiniteTransition(label = "cardTap")
    val t by loop.animateFloat(
        0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(
            tween(1400, easing = FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "approach",
    )
    Box(Modifier.fillMaxWidth().height(210.dp), contentAlignment = Alignment.BottomCenter) {
        // The back of the phone, rising out of the sheet: body, camera, and the edge of a lens.
        Box(
            Modifier.width(132.dp).height(168.dp)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(D.Black),
        ) {
            Box(Modifier.padding(start = 16.dp, top = 16.dp).size(34.dp).clip(CircleShape).background(Color(0xFF4A4A49)))
            Box(Modifier.padding(start = 25.dp, top = 25.dp).size(16.dp).clip(CircleShape).background(Color(0xFF1E1E1D)))
        }
        // Where the card is read. Faint while the card is away, clear as it lands.
        repeat(2) { i ->
            Box(
                Modifier.padding(bottom = 96.dp).size((70 + i * 34).dp)
                    .graphicsLayer { alpha = (t - 0.35f).coerceIn(0f, 1f) * (0.9f - i * 0.4f) }
                    .border(2.dp, D.Blue, CircleShape),
            )
        }
        // The card: a brand-blue face with a chip, tilted a little, travelling down to the phone.
        Box(
            Modifier.padding(bottom = 74.dp)
                .graphicsLayer {
                    translationY = -(1f - t) * 64.dp.toPx()
                    rotationZ = -8f + 4f * t
                }
                .width(168.dp).height(104.dp)
                .cardShadow(14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF0B6FD6), D.Blue))),
        ) {
            Box(
                Modifier.padding(start = 16.dp, top = 18.dp).size(width = 30.dp, height = 22.dp)
                    .clip(RoundedCornerShape(5.dp)).background(Color(0xFFFFD98A)),
            )
            Box(Modifier.align(Alignment.BottomEnd).padding(14.dp)) { HiIcon(Hi.Nfc, D.White, 22.dp) }
        }
    }
}

/** Naming a wallet: a name only this phone knows, shown on its card and wherever the wallet is listed. */
@Composable
internal fun WalletNameSheet(w: Linked, onClose: () -> Unit, onSave: (String?) -> Unit) {
    var text by rememberSaveable(w.address) { mutableStateOf(w.alias.orEmpty()) }
    Tray(onClose = onClose) {
        Spacer(Modifier.height(Space.lead))
        Text("Rename wallet", color = D.Black, style = Type.title)
        Spacer(Modifier.height(Space.tight))
        Text("A name only you see, on this phone.", color = D.Quiet, style = Type.caption)
        Spacer(Modifier.height(Space.lead))
        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= 24) text = it },
            singleLine = true,
            placeholder = { Text(w.name, color = D.Quiet, style = Type.body) },
            supportingText = { Text("${text.trim().length}/24", color = D.Quiet, style = Type.caption) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Space.lead))
        PrimaryButton(
            if (text.isBlank()) "Use the wallet's own name" else "Save",
            onClick = { onSave(text.trim().ifBlank { null }) },
        )
        Spacer(Modifier.height(Space.lead))
        // Lifts the sheet clear of the keyboard: it is anchored to the screen's foot, which the
        // keyboard covers, so without this the field and the button typed into were hidden.
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime))
    }
}
