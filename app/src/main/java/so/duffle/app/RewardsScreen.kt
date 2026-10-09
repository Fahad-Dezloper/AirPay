package so.duffle.app

import androidx.compose.ui.draw.drawBehind
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.AsyncImage
import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What paying in person has earned — as cards to scratch.
 *
 * ### Why a scratch card here, when the research said not to
 *
 * The rewards design deliberately rejected random scratch cards: this layer's entire defence
 * against farming is that every reward is a **fixed consequence of a rule**, and a payout that
 * depends on opening a screen is a lottery with all the legal weather that brings. That has not
 * changed. What is drawn here is a scratch card over a decision **already made** — the amount
 * was evaluated, credited and counted against the week's pool the moment the payment settled,
 * and [Rewards.reveal] only records that somebody has now looked at it.
 *
 * So the gesture is theatre, and it is honest theatre: scratching cannot change the number
 * underneath, and the server test `test_scratching_does_not_change_what_was_earned` is what
 * keeps that true. A card is never covered over a *refusal* either.
 *
 * ### Why the covered/uncovered state is the server's
 *
 * Kept on the phone it would reset on every reinstall, and every reward would look new again.
 * That is a small lie, but it is a lie about the one thing this screen exists to tell you.
 *
 * ### Rebuilt on the illustrated language, 2026-10-01
 *
 * From `ui/components/screens/Rewards.tsx` with `sections/WeekInfo.tsx`, to
 * ui/PORT-2026-10-01-3.md. This is a layout rewrite, not an edit: the total is a card on the
 * Rewards illustration's own ground, a card can now hold a **gift card** as well as SKR, and
 * This week is four steps on a track with an explainer sheet behind an "i".
 *
 * **What §7 took off this screen**, and why none of it is hiding somewhere else here:
 *
 *  - The **"Earned nothing"** list. `Rewards.Summary.refusals` is still read from the server and
 *    is left alone; §4.1's sheet is now the only place the refusal RULES are stated, which is
 *    the part that stops them looking arbitrary. Where the individual refused payments go is
 *    §10's open point, so nothing was invented for them — see [refusalText].
 *  - The card states **"locked"** and **"taken back"**: no reversed face, no reason line, no
 *    minus amount. §10 leaves what a taken-back reward looks like undecided, so a reversed row
 *    now draws as an ordinary card rather than as a state this port made up.
 *  - The **"Left in this week's pool"** row (only the empty-pool line survives), the **200 SKR
 *    badge**, the **"View history"** button, the **"Scratch a card to reveal it…"** helper, the
 *    old solid-blue foil, and the drawn coin stack beside the total.
 *
 * **The gesture is untouched.** `CompositingStrategy.Offscreen` + [BlendMode.Clear],
 * [SCRATCHED_ENOUGH] and [GRID] are exactly as they were; only the cover's artwork and what is
 * under it changed.
 *
 * @param onBack §1's back arrow. Optional because MainActivity.kt — which this port may not
 *   edit — still draws its own `BackHeader("Earn")` above this screen for every non-tap tab.
 *   Until that call site passes a lambda here, the arrow is drawn (it is part of §1's title row)
 *   and does nothing, and the screen shows two back rows. Removing `Tab.REWARDS -> "Earn"` from
 *   that header and passing `onBack` is the one-line change that fixes it.
 * @param onProduct where §5's gift cards go: that product, opened in the shop. Optional for the
 *   same reason — the shop's own screens are MainActivity.kt's state, so until that call site
 *   passes a lambda the cards are drawn and tapping one does nothing.
 */
@Composable
internal fun RewardsTab(
    connected: String?,
    isSeeker: Boolean,
    onInvite: () -> Unit,
    onOffers: () -> Unit,
    onBack: (() -> Unit)? = null,
    onProduct: ((Shop.Product) -> Unit)? = null,
    /** §5's "See all": the same shelf Pay a bill's More tile opens. Optional for previews. */
    onSeeAll: (() -> Unit)? = null,
    /** A card was tapped: where it sits in the window, so the stage can lift it from there. */
    onOpenCard: ((Rewards.Row, androidx.compose.ui.geometry.Rect) -> Unit)? = null,
    /** The card being scratched right now, hidden in its slot while the stage holds it. */
    openId: Int? = null,
) {
    var summary by remember { mutableStateOf<Rewards.Summary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var streakInfo by remember { mutableStateOf(false) }
    // §5's catalogue. Read here rather than taken as a parameter because MainActivity.kt holds
    // its own copy and cannot be edited to hand it over; `Recommended` draws nothing on an empty
    // list, so a server that has no shop simply drops the section.
    var products by remember { mutableStateOf<List<Shop.Product>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(connected, refresh, Session.version, Live.tick) {
        loading = true
        summary = connected?.let { Rewards.summary(it) }
        loading = false
    }
    LaunchedEffect(Unit) {
        Shop.sections()?.let { all -> products = all.flatMap { it.products } }
    }
    // The time every expiry on this page is judged against. Held in state and moved on, because
    // a clock read once while drawing stays where it was: a page left open past a card's last
    // minute went on offering it, and the server then refused the scratch.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    // Cards seen covered during this visit. One of them scratched here keeps its place in the
    // grid: sorted away with the opened ones, it left the slot the stage was carrying it back to.
    val seenCovered = remember { mutableSetOf<Int>() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberHeldScroll("rewards"))) {
        // The three blank states are unchanged, and deliberately carry no title row: they are a
        // heading and one line, the way `Rewards.tsx` draws them.
        if (connected == null) {
            Spacer(Modifier.height(Space.section))
            Text("Connect a wallet", color = D.Black, style = Type.heading)
            Spacer(Modifier.height(Space.tight))
            Text("Rewards are earned by the wallet that pays, so there is nothing to show until one is connected.",
                color = D.Quiet, style = Type.caption)
            return@Column
        }
        val s = summary
        if (s == null && !loading && !Session.valid(connected)) {
            Spacer(Modifier.height(Space.section))
            SignInNeeded("see your rewards")
            return@Column
        }
        if (s == null && loading) {
            // The page's own shape while it loads, under the title it will have, so nothing jumps.
            RewardsTitle(onBack)
            RewardsSkeleton()
            return@Column
        }
        if (s == null) {
            Spacer(Modifier.height(Space.section))
            Text("Can't reach the server", color = D.Black, style = Type.heading)
            Spacer(Modifier.height(Space.tight))
            Text("Your rewards are safe on the server, this screen just can't read them right now.",
                color = D.Quiet, style = Type.caption)
            return@Column
        }

        // ------------------------------------------------------------------ §1 title row
        RewardsTitle(onBack)

        // 24 below the title block — the one gap on this page that is neither `lead` nor
        // `section`, and §2 states it outright.
        Spacer(Modifier.height(24.dp))
        TotalCard(s, isSeeker, now)
        ClaimRow(s, connected) { refresh++ }

        // ------------------------------------------------------------- §3 scratch & reveal
        Spacer(Modifier.height(Space.section))
        // No `View all` beside this heading. §3 specifies one; **user feedback 2026-10-01
        // OVERRIDES that** — there are only ever a handful of cards, and the one screen the
        // button could have opened would have shown the same boxes again further down. It is
        // removed outright, not hidden on an empty list, so do not restore it from the brief.
        // Cards still to scratch come first, so the ones that want attention lead the grid.
        // Then the opened ones, then any that ran out, which want nothing from anybody.
        val rows = remember(s.rows, now) {
            val all = s.rows
            all.forEach { if (!it.revealed) seenCovered += it.id }
            all.sortedBy {
                when {
                    it.expired(now) -> 2
                    it.revealed && it.id !in seenCovered -> 1
                    else -> 0
                }
            }
        }
        // A card that ran out is still covered, but it is not one to scratch.
        val waiting = rows.count { !it.revealed && !it.expired(now) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scratch & reveal", color = D.Black, style = Type.heading)
            if (waiting > 0) {
                // How many are waiting, on the highlight: the one mark on this page that means
                // "something here is yours".
                Spacer(Modifier.width(Space.inline))
                Text(
                    if (waiting == 1) "1 to scratch" else "$waiting to scratch",
                    color = D.Black, style = Type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    modifier = Modifier.background(D.Highlight, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        if (rows.isEmpty()) {
            // No box: two lines on the page, the headline in body and the reason in caption.
            // It takes no Space.lead — its own row padding is the gap, which is why an empty
            // section is not a shorter version of a full one with a hole where the grid was.
            Column(Modifier.fillMaxWidth().padding(vertical = Space.row)) {
                Text("No cards yet", color = D.Black, style = Type.body)
                Spacer(Modifier.height(Space.tight))
                Text(
                    if (!isSeeker) "Only a wallet holding a Genesis Token earns cards."
                    else "Pay someone by tapping and a card lands here.",
                    color = D.Quiet, style = Type.caption,
                )
            }
        } else {
            Spacer(Modifier.height(Space.lead))
            // Two columns, laid out by hand rather than with a lazy grid: this list is capped
            // at twenty by the server and sits inside a scrolling column, where nesting a
            // second scrollable would take the gesture away from the page.
            val pairs = rows.chunked(2)
            pairs.forEachIndexed { i, pair ->
                // The 12dp is a grid GAP, so the last row does not carry one: a trailing
                // padding here would silently add 12 to every section gap below the grid.
                Row(Modifier.fillMaxWidth()
                    .padding(bottom = if (i == pairs.lastIndex) 0.dp else Space.inline),
                    horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
                    pair.forEach { row ->
                        Box(Modifier.weight(1f)) {
                            // `connected` is a val and the null case returned above, so it is
                            // smart-cast here — the old `?: return@ScratchCard` guard could
                            // never fire and the compiler said so.
                            RewardCard(row, now, hidden = openId == row.id) { bounds -> onOpenCard?.invoke(row, bounds) }
                        }
                    }
                    // A lone card on the last row must stay one column wide, not stretch.
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        // ------------------------------------------------------------------- §4 this week
        Spacer(Modifier.height(Space.section))
        WeekCard(s, onInfo = { streakInfo = true })

        // -------------------------------------------------------- §5 recommended for you
        // §5 is `Recommended` in Vouchers.kt, and owned there — this is only its call site.
        // It draws its own heading and `See all`, so the section gap is all that belongs here;
        // it renders nothing at all on an empty catalogue, which takes this spacer with it.
        if (products.isNotEmpty()) {
            Spacer(Modifier.height(Space.section))
            // "See all" goes where Pay a bill's More tile goes — the leftover shelf, built by
            // the one `moreSection` both routes call, so adding a bill tile can never leave the
            // two pointing at different lists (user call, 2026-10-01; §10 had left it undesigned).
            Recommended(products, onOpen = { onProduct?.invoke(it) }, onSeeAll = onSeeAll)
        }

        // -------------------------------------------------------- §6 more ways to earn
        Spacer(Modifier.height(Space.section))
        Text("More ways to earn", color = D.Black, style = Type.heading)
        Spacer(Modifier.height(Space.lead))
        // Two doors rather than the offers themselves. They used to be listed here *and* on the
        // Offers screen, which meant two places to claim the same thing and two places to keep
        // right; a list that exists twice is a list that disagrees with itself.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
            MoreWayFace(MORE_WAYS[0], Modifier.weight(1f), onOpen = onOffers)
            MoreWayFace(MORE_WAYS[1], Modifier.weight(1f), onOpen = onInvite)
        }

        Spacer(Modifier.height(Space.section))

        // §4.1. Hosted here rather than at the activity root, where every other sheet in this
        // app lives, because MainActivity.kt is off-limits to this port.
        //
        // It was a [Popup]. A popup window is laid out INSIDE the system-bar insets, so the
        // `Tray` stopped a navigation bar's height short of the screen and a strip of this page
        // showed through under `Got it` — confirmed on the phone, 2026-10-01. It is now a
        // full-screen [Dialog] whose window is told not to fit the system windows, which is the
        // one overlay a screen composable can open that genuinely reaches the display's edges.
        // If a strip is ever seen again, the real fix is hoisting this to MainActivity.kt
        // beside `OfferSheet` and `HowToReferSheet`. It measures zero in this column.
        if (streakInfo) StreakSheet(steps = s.streakNeeded.coerceAtLeast(1), bonus = s.streak, onClose = { streakInfo = false })
    }
}

// ------------------------------------------------------------------------------ §1 title row

/**
 * The screen's own title: the back arrow and "Rewards" on one line, then what the screen is for.
 *
 * The whole row is pulled 12 into the gutter rather than just the arrow's box, which is what the
 * sandbox's `-ms-3` does: the arrow's INK then starts on the page edge the caption starts on,
 * and the title follows it left by the same 12. A negative padding would throw in Compose, so
 * the shift is an offset — it moves the drawing without changing the row's measured size.
 */
@Composable
private fun RewardsTitle(onBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BackArrow(onBack)
        Text("Rewards", color = D.Black, style = Type.title)
    }
    Text("Earn. Save. Spend with AirPay", color = D.Quiet, style = Type.caption)
}

// -------------------------------------------------------------------------- §2 the total card

/**
 * The total's ground and its pattern.
 *
 * These are the ARTWORK's own colours, under the tinted-card exception in ui/PROJECT.md §3 —
 * the same exception the three illustrated cards on Home sit under. They are deliberately not
 * `Palette.kt` tokens: they describe one picture's background, not a state, a tier or a
 * severity, which is what the no-tints rule exists to stop. Do not reuse them elsewhere.
 */
private val HERO_TOP = Color(0xFFECF3FC)
private val HERO_BOTTOM = Color(0xFFD3E3FB)
private val HERO_R = 24.dp

/** How far above the card's top edge the gift is allowed to stand (`-top-8` in the sandbox). */
private val HERO_RISE = 32.dp

/**
 * The pattern's filled squares, on its 24 grid: (columns in from the END, rows down from the
 * top). Scattered over the end half, where the gift is; none of them under the amount.
 */
private val HERO_SQUARES = listOf(1 to 0, 4 to 1, 0 to 2, 6 to 0, 3 to 3, 5 to 4)

/**
 * The headline total.
 *
 * One number, because that is the question the screen is opened to answer — and from §2 it is a
 * **value in dollars** rather than an SKR count, because a reward is now SKR or a gift card and
 * no single token can count both.
 *
 * ### Why there is a fallback, and why it is not computed here
 *
 * §2 wants `$16.24`, which needs the SKR price and each gift card's dollar worth — and §2 is
 * explicit that the phone does not convert currencies, so neither may be worked out here.
 * `server/duffleserver/rewards.py` supplies none of it today: its `total` is `sum(a.skr)`, an
 * integer, and there is no USD anywhere in it. So [Rewards.Summary.totalUsd] is null on every
 * response, and this card falls back to the SKR figure **with its unit** rather than printing a
 * dollar sign in front of a number nobody priced. The app does now carry a market price; using
 * it here would be exactly the conversion §2 forbids, and a total nobody on the server agrees
 * with.
 *
 * The unit is the one place this leaves §2's wording ("no token name after it"): a bare `16` at
 * 40sp on a money card reads as money. It goes when the server sends a price.
 */
@Composable
private fun TotalCard(s: Rewards.Summary, isSeeker: Boolean, now: Long) {
    val usd = s.totalUsd
    // Cards still to scratch. Not a card that ran out, which can no longer add anything to this figure.
    val toScratch = s.rows.count { !it.revealed && !it.expired(now) }
    // Zero is judged on whatever is actually being shown, so the lines below never describe a
    // figure the card is not displaying.
    val empty = (usd ?: s.total.toDouble()) <= 0.0

    Box(Modifier.fillMaxWidth()) {
        // The card itself: it is what gives this Box its size, which is what the gift's layer
        // below then matches.
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(HERO_R))
                // 165°, which is nearly straight down with a slight lean to the end side — so
                // the pale top sits under the label and the deeper blue gathers under the gift.
                // Built from the measured size rather than passed to `background()` as a fixed
                // `linearGradient`: an angle is only an angle once the card's width and height
                // are known, and corner-to-corner would have been about 113° on this card.
                .drawWithCache {
                    val brush = angledGradient(size, 165f, HERO_TOP, HERO_BOTTOM)
                    onDrawBehind { drawRect(brush) }
                },
        ) {
            HeroPattern(Modifier.matchParentSize())
            // 16 at the start, 32 top and bottom, and 46% of the card kept clear at the end for
            // the gift — so the words get 54% of the width, less their own 16.
            Column(
                Modifier.fillMaxWidth(.54f)
                    .padding(start = 16.dp, top = 32.dp, bottom = 32.dp),
            ) {
                Text("YOUR REWARDS", color = D.BlueInk, style = Type.micro)
                Spacer(Modifier.height(8.dp))
                if (usd != null) {
                    // 40, not 60: a dollar figure is longer than an SKR count, and at 60
                    // "$16.24" ran under the gift.
                    Text(Money.fiat(BigDecimal.valueOf(usd), "USD"), color = D.Black,
                        style = Type.amount)
                } else {
                    Row {
                        // One line each, whatever the figure. A total too wide for this column
                        // ("12,500") used to leave "SKR" a sliver to wrap in, one letter to a
                        // line; it now runs on under the gift instead, which is still readable.
                        Text(skr(s.total), color = D.Black, style = Type.amount,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Visible,
                            modifier = Modifier.alignByBaseline())
                        Spacer(Modifier.width(Space.tight))
                        Text("SKR", color = D.Black, style = Type.heading,
                            maxLines = 1, softWrap = false, overflow = TextOverflow.Visible,
                            modifier = Modifier.alignByBaseline())
                    }
                }
                Spacer(Modifier.height(Space.tight))
                // Ink, not quiet: quiet grey fails contrast on this ground.
                Text(
                    when {
                        // A card is waiting: it is not counted until it is scratched, so say so
                        // rather than "Nothing yet", which would be false.
                        empty && toScratch == 1 ->
                            "Scratch your card below to see what you've earned"
                        empty && toScratch > 1 ->
                            "Scratch your cards below to see what you've earned"
                        empty && !isSeeker ->
                            "Rewards are for Seeker owners, this wallet holds no Genesis Token"
                        empty ->
                            "Nothing yet, a payment made by tapping earns ${skr(s.perPayment)} SKR"
                        else -> "Total rewards value"
                    },
                    color = D.Black, style = Type.caption,
                )
            }
        }

        // The gift, on its own layer so it can be cut by the card's FLOOR and by nothing else:
        // the box is clipped by the bottom corners only, and stands [HERO_RISE] clear of the
        // card's top edge, so the ribbon and coins are never sliced off there.
        Box(
            Modifier.matchParentSize()
                .layout { measurable, constraints ->
                    val rise = HERO_RISE.roundToPx()
                    val tall = measurable.measure(
                        constraints.copy(
                            minHeight = constraints.maxHeight + rise,
                            maxHeight = constraints.maxHeight + rise,
                        ),
                    )
                    // The CARD's height is what gets reported, so this layer costs the section
                    // nothing; the extra is placed above it.
                    layout(tall.width, constraints.maxHeight) { tall.place(0, -rise) }
                }
                .clip(RoundedCornerShape(bottomStart = HERO_R, bottomEnd = HERO_R)),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                Image(
                    painterResource(R.drawable.rewards),
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .offset(x = -maxWidth * .01f, y = maxHeight * .06f)
                        // Width only: the height then follows the drawable's own 720 × 496,
                        // which is what keeps the illustration undistorted.
                        .fillMaxWidth(.52f),
                )
            }
        }
    }
}

/**
 * A two-stop linear gradient at a CSS angle, over a box of a known size.
 *
 * CSS measures the angle clockwise from "to top", and runs the gradient along a line through the
 * box's centre whose length is `|w·sinθ| + |h·cosθ|` — which is what makes the first colour land
 * exactly on the starting corner rather than part-way up the side. Compose takes two points
 * instead, so the line is worked out here. Only §2 needs it: §4's and §6's grounds are vertical,
 * where the two are the same thing.
 */
private fun angledGradient(size: Size, degrees: Float, from: Color, to: Color): Brush {
    val rad = Math.toRadians(degrees.toDouble())
    val dir = Offset(kotlin.math.sin(rad).toFloat(), -kotlin.math.cos(rad).toFloat())
    val length = kotlin.math.abs(size.width * dir.x) + kotlin.math.abs(size.height * dir.y)
    val centre = Offset(size.width / 2f, size.height / 2f)
    return Brush.linearGradient(
        listOf(from, to),
        start = centre - dir * (length / 2f),
        end = centre + dir * (length / 2f),
    )
}

/**
 * The faint grid behind the total.
 *
 * Decoration, and the one thing on this screen that has to not be read: it fades out from the
 * end side towards the start — fully there for the end 25%, gone by 80% across — so nothing of
 * it crosses the amount. The fade is a `DstIn` wipe over an offscreen layer, which is the same
 * mechanism the scratch cover uses, and for the same reason: alpha can only be taken out of a
 * layer the composable owns.
 *
 * White at 70% and 45% on the artwork's ground is the pattern itself, not a tint standing for a
 * state — the distinction ui/PORT.md §1 rule 3 draws.
 */
@Composable
private fun HeroPattern(modifier: Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val step = 24.dp.toPx()
        val thick = 1.5.dp.toPx()
        val line = Color.White.copy(alpha = .70f)

        // The lines are anchored at the start edge, as the sandbox's `background-size` tiling
        // is. At the 360 content width that is 15 whole cells, so the end edge lands on a line
        // either way.
        var x = 0f
        while (x <= size.width) {
            drawRect(line, Offset(x, 0f), Size(thick, size.height))
            x += step
        }
        var y = 0f
        while (y <= size.height) {
            drawRect(line, Offset(0f, y), Size(size.width, thick))
            y += step
        }

        val fill = Color.White.copy(alpha = .45f)
        HERO_SQUARES.forEach { (col, row) ->
            val left = if (rtl) col * step else size.width - (col + 1) * step
            drawRect(fill, Offset(left, row * step), Size(step, step))
        }

        drawRect(
            if (rtl) Brush.horizontalGradient(0.25f to Color.Black, 0.80f to Color.Transparent)
            else Brush.horizontalGradient(0.20f to Color.Transparent, 0.75f to Color.Black),
            blendMode = BlendMode.DstIn,
        )
    }
}

// ------------------------------------------------------------------------ §3 scratch & reveal

/** The grid's corner, shared by the covered artwork and the opened face. */
private val CARD_R = 24.dp

/**
 * One reward in the grid: a card to open.
 *
 * Tapping lifts it into the middle of the screen (see [ScratchStage]) where it is scratched; the
 * grid itself no longer takes the scratch gesture, which is what let a card be half-scratched
 * while the page scrolled under the finger. While the stage holds it the slot goes quiet
 * ([hidden]) so the card is never in two places, and it comes back exactly where it was.
 */
@Composable
private fun RewardCard(row: Rewards.Row, now: Long, hidden: Boolean, onOpen: (androidx.compose.ui.geometry.Rect) -> Unit) {
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val press = remember { MutableInteractionSource() }
    // A card that ran out does not open. Lifted into the stage it could be scratched all the
    // way to a win the server then refuses to pay, which is the one thing a card must not show.
    val gone = row.expired(now)
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f)
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
            .then(PressScale(press))
            // Not before it has been placed: the stage sizes the lift from these bounds.
            .clickable(press, indication = null, enabled = !gone) { if (bounds.width > 0f) onOpen(bounds) },
    ) {
        CardBody(row, cover = if (row.revealed) 0f else 1f)
        if (!row.revealed) ScratchCue(row, now)
    }
}

/**
 * What makes a covered card read as a scratch card rather than as a picture.
 *
 * User feedback, 2026-10-05: between the hero above and the streak card below, the covered card
 * was one more illustration, and nothing on it said it could be scratched. Two plain things say
 * it now, the way the big payment apps do:
 *
 *  - **"Tap to scratch"** at the top of the card, which is the instruction;
 *  - **a band along the foot**, part of the card, carrying the one fact with a clock on it: the
 *    day it expires, in the card's own colour, or "Expiring today!" in red when that day is today.
 *
 * Nothing on it moves. A first version had a foil strip, a coin rubbing across it and a glint
 * crossing the card; it was three animations on a page that already has plenty to look at.
 */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.ScratchCue(row: Rewards.Row, now: Long) {
    // Red for the last 24 hours. The WORD is the calendar's, though: a card that runs out at
    // nine tomorrow morning is urgent tonight, but "today" would be false.
    val urgent = row.expiresAt > 0L && row.expiresAt - now in 0..86_400_000L
    val gone = row.expired(now)
    // The instruction, as a white label with ink words: the strongest contrast there is, on the
    // blue card and the gold one alike, and it reads as a sticker on the card rather than as a
    // shadow over the picture, which is what the dark pill looked like.
    Text(
        if (gone) "Expired" else "Tap to scratch",
        color = D.Black, style = Type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
            .background(D.White, CircleShape).padding(horizontal = 12.dp, vertical = 4.dp),
    )
    // The band. It belongs to the card: full width, flush with the foot, cut by the card's corners.
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp)
            .clip(RoundedCornerShape(bottomStart = CARD_R, bottomEnd = CARD_R))
            // A solid colour that says what kind of news the band carries. The card's own
            // deeper shade when there is time, so it reads as part of the card and stays calm;
            // red on the last day, the one colour that means "now"; grey once it is over.
            .background(when {
                gone -> BAND_OVER
                urgent -> BAND_URGENT
                row.isSpecial -> BAND_GOLD
                else -> BAND_BLUE
            }),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when {
                gone -> "Not scratched in time"
                urgent && sameDay(row.expiresAt, now) -> "Expiring today!"
                urgent -> "Expiring tomorrow!"
                row.expiresAt > 0L -> "Expires on ${EXPIRY_DAY.format(java.util.Date(row.expiresAt))}"
                else -> "Scratch now"
            },
            color = D.White, style = Type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            // Kept off the card's rounded corners, and ended with "…" when a large font or a long
            // month name will not fit, where it used to be cut off mid-letter.
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

// White words on each of these is at least 4.5:1. Blue and gold are the two covers' own deeper
// shades, so they stay with the artwork. Red and grey are states, and were raw near-copies of
// the palette's own danger ink and quiet grey, so they are those tokens now.
private val BAND_BLUE = Color(0xFF0B6FD6)
private val BAND_GOLD = Color(0xFFB85C00)
private val BAND_URGENT = D.DangerInk
private val BAND_OVER = D.Quiet

/**
 * Whether a covered card ran out before it was scratched. The server refuses to open one
 * (`refused: "expired"`), so the page must not offer it. Zero means the server gave no date.
 */
private fun Rewards.Row.expired(now: Long): Boolean = !revealed && expiresAt in 1..now

/** Whether two moments fall on the same day of this phone's calendar. */
private fun sameDay(a: Long, b: Long): Boolean {
    val x = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val y = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return x.get(java.util.Calendar.YEAR) == y.get(java.util.Calendar.YEAR) &&
        x.get(java.util.Calendar.DAY_OF_YEAR) == y.get(java.util.Calendar.DAY_OF_YEAR)
}

/** "16 Sep": the day and the month, which is all an expiry needs. */
private val EXPIRY_DAY = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())

/**
 * The card itself: the opened face, with the cover artwork over it by [cover] (1 = covered,
 * 0 = open). Used by the grid and by the stage, so a card looks the same in both places and the
 * lift from one to the other has nothing to jump between.
 */
@Composable
internal fun CardBody(row: Rewards.Row, cover: Float, modifier: Modifier = Modifier.fillMaxSize()) {
    val special = row.isSpecial
    Box(
        modifier
            // The hairline belongs to the OPENED face: the cover is artwork to the edge with
            // nothing drawn over it. It fades in with the reveal rather than popping in at the end.
            .border(1.5.dp, D.Line.copy(alpha = 1f - cover), RoundedCornerShape(CARD_R))
            .clip(RoundedCornerShape(CARD_R))
            .background(D.White),
    ) {
        OpenedFace(row, special)
        if (cover > 0f) {
            androidx.compose.foundation.Image(
                painterResource(if (special) R.drawable.special else R.drawable.simple),
                contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = cover },
            )
        }
    }
}

/**
 * What is under the cover. Drawn always, so scratching uncovers it rather than swapping it in.
 *
 * §3.3's two parts: a picture strip filling everything above the text, then the answer on white.
 * The card splits the way Google Pay's does, and an outlined card with words on it cannot be
 * mistaken for one still to scratch.
 *
 * A card holds SKR or a gift card. The gift-card path is built and rendered, but **nothing in
 * this app fabricates one**: [Rewards.Row.gift] is null on every row the server sends today
 * (`rewards.py` has no gift-card concept), and §10 lists the reward record's gift fields as
 * undesigned. The day the server sends one, the card appears with no further work.
 */
@Composable
internal fun OpenedFace(row: Rewards.Row, special: Boolean) {
    val gift = row.gift
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Image(
                painterResource(
                    when {
                        // Every SKR reward opens onto the coins, whichever colour covered it.
                        // §10: a gold version of this picture may come later.
                        gift == null -> R.drawable.skr_open
                        special -> R.drawable.voucher_special
                        else -> R.drawable.voucher_simple
                    },
                ),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // §3.4. The two voucher pictures already contain a blank, tilted gift card with a
            // ribbon, in the same place in both — so one placement serves both, and only the
            // brand's logo is drawn on top. The CDN pads it onto a transparent ground, so no
            // box shows around it on the card.
            if (gift?.logoId != null) {
                BoxWithConstraints(Modifier.matchParentSize()) {
                    AsyncImage(
                        model = "https://www.bitrefill.com/content/cn/c_pad,h_256,w_256/v1/${gift.logoId}.png",
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .offset(x = maxWidth * .52f - LOGO / 2, y = maxHeight * .41f - LOGO / 2)
                            .size(LOGO)
                            // The card's own tilt, so the logo lies ON it rather than over it.
                            .rotate(16.5f)
                            .brandInk(gift.brand),
                    )
                }
            }
        }
        Column(Modifier.padding(12.dp)) {
            // The number alone: the picture above and the line below already say what it is a
            // number of. A gift card shows its FACE value, in its own currency — never the
            // dollar figure, which is a different fact and belongs only to the total.
            Text(
                if (gift != null) fiatWhole(gift.face, gift.currency) else skr(row.skr, sign = true),
                color = D.Black, style = TitleAmount,
            )
            Spacer(Modifier.height(Space.tight))
            Text(
                if (gift != null) "${gift.brand} gift card" else "SKR earned",
                color = D.Quiet, style = Type.caption,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The brand logo on a gift card's blank: 28 × 28, fitted inside. */
private val LOGO = 46.dp

/**
 * Gold or blue: which cover a reward wears.
 *
 * A special reward is one that happens once — the first payment, and an invite that turned into
 * one. Everything else is everyday, and everyday is blue. The sandbox carries this as a
 * `special` flag on its mock row; the server has no such field, so it is read off the `kind` it
 * does send.
 */
internal val Rewards.Row.isSpecial: Boolean
    get() = kind == "first_payment" || kind == "referral"

/**
 * A fiat amount with no fraction when there genuinely is none: `₹250`, not `₹250.00`.
 *
 * A gift card's face value is a shop price, and a shop price is written whole. [Money] has no
 * `whole` option — the sandbox's `fiat()` does — and Money.kt is owned elsewhere today, so the
 * figure is reassembled from [Money.fiatParts] instead: the symbol, the side it sits on, the
 * grouping and the decimal mark all still come out of the locale rather than being guessed
 * here, which is the whole point of going through [Money] at all.
 *
 * A value that is not whole keeps its fraction, exactly as the sandbox does: ₹12.46 has paise
 * and dropping them would be a different number.
 */
/**
 * A brand's own colours, for a logo the catalogue only has in white.
 *
 * The blank card in the voucher pictures is white, and a white logo on it cannot be seen. Where
 * the brand's colours are known, the logo's own shape is filled with them; any other logo is
 * drawn as it came.
 */
internal val BRAND_INK: Map<String, Brush> = mapOf(
    // Tinder's flame gradient, pink to orange.
    "Tinder" to Brush.linearGradient(listOf(Color(0xFFFD267A), Color(0xFFFF6036))),
)

internal fun Modifier.brandInk(brand: String): Modifier {
    val ink = BRAND_INK[brand] ?: return this
    return graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(ink, blendMode = BlendMode.SrcIn)
        }
}

internal fun fiatWhole(value: Double, currency: String): String {
    val v = BigDecimal.valueOf(value)
    if (v.stripTrailingZeros().scale() > 0) return Money.fiat(v, currency)
    val p = Money.fiatParts(v, currency)
    return if (p.symbolFirst) "${p.symbol}${p.whole}" else "${p.whole} ${p.symbol}"
}

// -------------------------------------------------------------------------- §4 this week

/**
 * This week's ground: the gift's own green, the one the Rewards card on Home sits on, held at
 * its light top colour for the upper 45% where the words are. The artwork's colours again, not
 * palette tokens — see [HERO_TOP].
 */
private val WEEK_TOP = Color(0xFFE8FCEF)
private val WEEK_BOTTOM = Color(0xFFD0FADD)

/** The room the content column keeps clear at the card's end, for the gift. */
private val WEEK_CLEAR = 148.dp

/**
 * How wide the gift is drawn at the card's end: 168, which is 20 more than [WEEK_CLEAR].
 *
 * The overlap is deliberate and it is what [WEEK_FADE] is for — the picture reaches a little way
 * under the words' column and is wiped out again before it gets to them.
 */
private val WEEK_ART = 168.dp

/** How far in from the gift's start edge the wipe runs. */
private val WEEK_FADE = 56.dp

/**
 * The coin's diameter, and how much of its face-on radius is the lit ring around the face.
 *
 * 40, not 96: the coin's home is the info sheet's second row, at the same 40 lead the other row's
 * disc uses. See [SkrCoin] for why it moved there on 2026-10-01.
 */
private val COIN = 40.dp
private const val COIN_EDGE = .085f

/**
 * The coin's THICKNESS, as a fraction of its diameter — half of it either side of the face.
 *
 * A real coin is nearer 7% and at 40dp that is under 3dp of rim, which is not a coin, it is a
 * rendering artefact. 22% is a poker chip, and it is the point: the rim has to be seen for the
 * turn to read as a solid object rather than a card flipping over.
 */
private const val COIN_THICK = .22f

/**
 * The narrowest the face ellipse is allowed to get, as a fraction of the coin's radius.
 *
 * At exactly 90° the face has no width at all, and an arc across a zero-width box draws nothing —
 * the silhouette would collapse to two straight lines for those frames. Holding 1.5% keeps the
 * rim's ends rounded, which is also what a coin on edge looks like.
 */
private const val COIN_FACE_MIN = .015f

/**
 * The SKR coin's own colours, sampled from `skr_open.webp` so this card and the opened scratch
 * cards show the same coin: a near-black navy face, a lighter edge catching the light from above,
 * and an almost-white mark. The artwork's colours again, under the same tinted-card exception as
 * [HERO_TOP] — not `Palette.kt` tokens, and not for reuse.
 */
private val COIN_FACE_TOP = Color(0xFF1E2334)
private val COIN_FACE_BOTTOM = Color(0xFF0C1020)
private val COIN_EDGE_TOP = Color(0xFF4A5574)
private val COIN_EDGE_BOTTOM = Color(0xFF0C1430)
private val COIN_MARK = Color(0xFFF2F6FF)

/**
 * The idle flip: a long hold, then a half turn.
 *
 * **Its own spec, and it does not touch the project's springs.** `Arrive`, `Snap` and `Settle`
 * answer something — a gesture, a state change — and overshoot because an answer should feel
 * like one. Nothing is being answered here: the coin turns because nobody asked it to. So it is
 * an easing curve rather than a spring, and the ratio is what keeps it alive rather than busy —
 * [COIN_HOLD_MS] still for every [COIN_TURN_MS] moving, about six parts stillness to one.
 */
private const val COIN_HOLD_MS = 3600
private const val COIN_TURN_MS = 620

/** A step disc, and the mark inside it: 20 in a row disc, the icon scale's small disc size. */
private val DISC = 32.dp
private val DISC_ICON = 20.dp

/**
 * This week, as four steps on a track.
 *
 * Four is small enough to see at a glance, and the streak is the thing worth finishing. What the
 * card does not say — what counts as a step, what finishing earns, which payments do not count
 * and that the pool can run dry — is behind the "i", in [StreakSheet]. ("It starts over each
 * week" used to be in there too; the 2026-10-01 feedback cut it, so the sheet no longer says it
 * and neither does this card.) The card itself carries
 * only progress, and one line for the unhappy case: an empty pool pays nobody, and a screen
 * that stayed quiet about it would let the next payment earn nothing without warning.
 *
 * §7 took the "Left in this week's pool" row and the 200 SKR badge off this card. How much is
 * left is no longer on this screen; only that there is nothing left.
 */
@Composable
private fun WeekCard(s: Rewards.Summary, onInfo: () -> Unit) {
    // Four discs and a track divided in thirds is §4's rule, and `streak_needed` is the server
    // field that says four — so the label, the discs and the fill all count against the same
    // number rather than against `weekAllows`, which could disagree with the drawing.
    val steps = s.streakNeeded.coerceAtLeast(1)
    val done = s.weekPayments.coerceIn(0, steps)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("This week", color = D.Black, style = Type.heading, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Space.inline))
        Text("$done of $steps", color = D.Quiet,
            style = Type.caption.copy(fontFeatureSettings = "tnum"))
    }
    Spacer(Modifier.height(Space.lead))

    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(CARD_R))
            // The top colour is HELD to 45% and only then moves, so the message sits on the
            // colour the contrast was measured on.
            .background(Brush.verticalGradient(0f to WEEK_TOP, .45f to WEEK_TOP, 1f to WEEK_BOTTOM)),
    ) {
        // What the four lead to: §4's gift, [WEEK_ART] wide at the card's end and its full
        // height, cropped.
        //
        // **The coin is NOT here.** It was, for one build — an earlier brief put the animated SKR
        // token on this card — and the user corrected that on 2026-10-01: the coin belongs in the
        // info sheet, as the lead on "All four, 200 SKR". Do not move it back; see [SkrCoin].
        //
        // Two things the picture needs that the coin did not, both restored with it:
        //
        //  - **The start-edge fade.** `week_gift` carries its own pale green ground, and where
        //    that ground meets this card's gradient there is a visible seam. Wiping the picture's
        //    own start edge out with [BlendMode.DstIn] is what hides the join — so the artwork
        //    has no edge at all on the side the words are on. (A coin needs none of this: it is
        //    drawn on nothing, so there is no ground to join.)
        //  - **The RTL mirror.** The gift is lit and tilted, so it has a direction: mirrored, it
        //    leans into the page the way it leans out of it in a left-to-right layout. The mirror
        //    also carries the fade to the other side for free, which is what makes one wipe
        //    correct in both directions.
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Box(Modifier.matchParentSize()) {
            Image(
                painterResource(R.drawable.week_gift),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.CenterEnd)
                    .width(WEEK_ART).fillMaxHeight()
                    // The layer is OUTSIDE the wipe, so the alpha comes out of the picture alone
                    // — the same reason the scratch cover and [HeroPattern] own a layer.
                    .graphicsLayer {
                        if (rtl) scaleX = -1f
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, Color.Black),
                                startX = 0f, endX = WEEK_FADE.toPx(),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }

        // At least 168 tall: the 120 card was too small for what it carries. The height is set
        // here, on the content, so the card wraps it — a `heightIn` on the card itself would
        // not reach a child that wants to fill it inside a scrolling column.
        Column(
            Modifier.fillMaxWidth().heightIn(min = 168.dp)
                .padding(16.dp).padding(end = WEEK_CLEAR),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (done >= steps) "Streak complete, ${skr(s.streak)} SKR earned"
                else "${steps - done} more this week for the ${skr(s.streak)} SKR streak",
                color = D.Black, style = Type.body,
            )
            WeekSteps(done, steps)
            // Plain ink words, not red: nothing was refused by the chain, there is simply
            // nothing left to pay out this week.
            if (s.poolLeft <= 0) {
                Text("This week's pool is empty, nothing earns until it refills",
                    color = D.Black, style = Type.caption)
            }
        }

        // The "i". On a white disc because the gift's black bow sits behind this corner, and an
        // ink icon straight on it disappears.
        val press = remember { MutableInteractionSource() }
        Box(
            Modifier.align(Alignment.TopEnd).then(PressScale(press)).size(48.dp)
                .clip(CircleShape)
                .clickable(press, indication = null, onClick = onInfo)
                .semantics { contentDescription = "How this week's streak works" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(28.dp).background(D.White, CircleShape),
                contentAlignment = Alignment.Center) {
                HiIcon(Hi.Info, D.Black, 20.dp)
            }
        }
    }
}

/**
 * Four discs on one track.
 *
 * **The track runs from the centre of the first disc to the centre of the fourth and stops
 * there.** That is the whole rule: it is inset half a disc at both ends, so it never continues
 * past step 4 towards the gift, which would draw a fifth step that does not exist. The filled
 * part runs from the first disc to the last one done — `(done − 1) / 3` of the track at four
 * steps, nothing at none or one, all of it at four.
 *
 * The track is drawn first and centred on the discs, so it passes behind them.
 */
@Composable
private fun WeekSteps(done: Int, steps: Int) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().padding(horizontal = DISC / 2).height(4.dp)) {
            Box(Modifier.fillMaxSize().background(D.White, CircleShape))
            val filled =
                if (done <= 1 || steps <= 1) 0f
                else (done - 1).toFloat() / (steps - 1)
            if (filled > 0f) {
                Box(Modifier.fillMaxWidth(filled).fillMaxHeight()
                    .background(D.Success, CircleShape))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            repeat(steps) { i ->
                when {
                    i < done -> StepDisc(DiscTone.Paid, DISC)
                    else -> Box(
                        Modifier.size(DISC).clip(CircleShape).background(D.White)
                            // The next one to do is ringed in blue ink — "you are here". The
                            // rest are plain.
                            .then(
                                if (i == done) Modifier.border(1.5.dp, D.BlueInk, CircleShape)
                                else Modifier,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${i + 1}", color = if (i == done) D.BlueInk else D.Quiet,
                            style = Type.caption.copy(fontFeatureSettings = "tnum"))
                    }
                }
            }
        }
    }
}

/**
 * The SKR coin, drawn rather than pictured, turning over every few seconds.
 *
 * **Where it lives.** The info sheet's second row, as the lead on "All four, 200 SKR" — the bonus
 * the fourth step buys is SKR, so the SKR token is the picture for it. It spent one build on the
 * This week card; **the user corrected that on 2026-10-01** and it is not to go back there. The
 * card's own picture is the gift (see [WeekCard]).
 *
 * **Drawn, not a drawable.** There is no SKR token image anywhere in this project — `res/` and
 * the sandbox's `public/` were both checked — and a coin is a disc and a glyph, so drawing it
 * costs less than finding one.
 *
 * ### Why the turn is drawn by hand and not by `rotationY`
 *
 * It was a `rotationY` on a flat disc, and that is the one thing the user said it must not look
 * like: a flat disc turning has no silhouette at 90°, so it vanishes to a line and reads as a
 * spinning card. A coin has thickness, and thickness is a SHAPE, not a transform — so the shape
 * is what is drawn.
 *
 * A cylinder of radius `r` and thickness `h`, turned by θ about its vertical axis, projects as
 * two face ellipses: half-height `r`, half-width `r·|cos θ|`, their centres `±(h/2)·sin θ` apart.
 * The silhouette is the hull of the two — one ellipse's far half, a straight top and bottom, then
 * the other's far half — and the rim is everything in that hull the near face does not cover. So:
 * fill the hull, then lay the near face on top of it. Face-on the hull IS the face's circle and
 * nothing of the rim shows; edge-on the face has no width and the hull is all rim. Nothing has to
 * be faded in or out between those two, which is what keeps it from looking staged.
 *
 * The near face is the front one for θ < 90 and the back one after, and both sit at `+(h/2)·sin θ`
 * and `−(h/2)·sin θ` respectively — which is why the rim swaps sides at the halfway point on its
 * own. The back needs no artwork of its own: both gradients are VERTICAL, so the back is the
 * front mirrored about an axis the drawing is already symmetric on. Only the mark would read
 * backwards, so it is squashed by `|cos θ|` rather than turned, and reads the right way round on
 * either face. 180 therefore draws the same coin as 0 and the repeat has no jump in it.
 *
 * ### What was traded away at 40dp
 *
 * **Legibility over physics, as instructed.** [COIN_THICK] is 22% of the diameter, three times a
 * real coin, because 7% of 40dp is under 3dp and cannot be seen. The rim is shaded with the
 * artwork's own edge colours — the lit [COIN_EDGE_TOP] → [COIN_EDGE_BOTTOM] roll it already had
 * face-on — and then darkened towards the silhouette **in proportion to `sin θ`**, so the
 * face-on coin is pixel-for-pixel the one that was there before and the darkening only appears as
 * the side comes into view. There is no specular highlight, no bevel between rim and face and no
 * perspective: at this size each of those is one pixel of noise, and the near edge of a 40dp face
 * swinging out would read as a wobble rather than as a turn.
 */
@Composable
private fun SkrCoin(modifier: Modifier = Modifier) {
    val idle = rememberInfiniteTransition(label = "coin")
    val turn by idle.animateFloat(
        initialValue = 0f,
        targetValue = 180f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = COIN_HOLD_MS + COIN_TURN_MS
                // Still, then over. The easing is declared on the keyframe the turn STARTS at,
                // which is what makes it the turn's curve rather than the hold's.
                0f at 0
                0f at COIN_HOLD_MS using FastOutSlowInEasing
                180f at COIN_HOLD_MS + COIN_TURN_MS
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "flip",
    )
    Box(modifier.size(COIN), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val rad = Math.toRadians(turn.toDouble())
            // Over 0..180 the sine never goes negative, so this is the projected half-separation
            // of the two faces outright; the cosine's SIGN is what says which of them is facing.
            val cos = kotlin.math.cos(rad).toFloat()
            val lean = kotlin.math.sin(rad).toFloat()
            val r = size.minDimension / 2f
            val cx = size.width / 2f
            val cy = size.height / 2f
            // Half the face's width, held off zero so the hull's ends stay arcs — see
            // [COIN_FACE_MIN].
            val a = (r * kotlin.math.abs(cos)).coerceAtLeast(r * COIN_FACE_MIN)
            val d = r * COIN_THICK * lean

            val hull = Path().apply {
                // Start at the start-side ellipse's bottom and take its far half up to the top
                // (90° is 6 o'clock on a y-down canvas, and the sweep runs clockwise through
                // 9 o'clock), then straight across the rim's top, then the other ellipse's far
                // half back down, then straight across the bottom.
                moveTo(cx - d, cy + r)
                arcTo(Rect(cx - d - a, cy - r, cx - d + a, cy + r), 90f, 180f, false)
                lineTo(cx + d, cy - r)
                arcTo(Rect(cx + d - a, cy - r, cx + d + a, cy + r), 270f, 180f, false)
                close()
            }
            drawPath(hull, Brush.verticalGradient(listOf(COIN_EDGE_TOP, COIN_EDGE_BOTTOM)))
            // The side of a solid object: darkest where it curves away from you. Scaled by the
            // lean so that at 0° — where there is no side to see — this pass draws nothing and
            // the coin is exactly the disc it was before.
            if (lean > .01f) {
                drawPath(
                    hull,
                    Brush.horizontalGradient(
                        listOf(COIN_EDGE_BOTTOM, Color.Transparent, COIN_EDGE_BOTTOM),
                        startX = cx - d - a, endX = cx + d + a,
                    ),
                    alpha = lean,
                )
            }
            // The face, inset all round by the lit ring the coin has face-on. Past about 85° the
            // inset has eaten the whole width and there is no face left to draw, which is
            // correct: edge-on, a coin is all rim.
            val ring = r * COIN_EDGE
            val faceA = a - ring
            if (faceA > 0f) {
                drawOval(
                    Brush.verticalGradient(listOf(COIN_FACE_TOP, COIN_FACE_BOTTOM)),
                    topLeft = Offset(cx + (if (cos >= 0f) d else -d) - faceA, cy - (r - ring)),
                    size = Size(faceA * 2f, (r - ring) * 2f),
                )
            }
        }
        // The mark, at [Type.title] — the step that fits a 40 disc, the same relationship
        // [DISC_ICON]'s 20 has to a 32 one. It rides the face rather than the coin: squashed by
        // the same `|cos θ|`, shifted onto whichever face is showing, and gone before the coin is
        // edge-on, because a glyph one pixel wide is a smear and not a letter.
        Text(
            "S", color = COIN_MARK, style = Type.title,
            modifier = Modifier.graphicsLayer {
                val rad = Math.toRadians(turn.toDouble())
                val cos = kotlin.math.cos(rad).toFloat()
                val squash = kotlin.math.abs(cos)
                scaleX = squash
                translationX =
                    (COIN.toPx() / 2f) * COIN_THICK * kotlin.math.sin(rad).toFloat() *
                        (if (cos >= 0f) 1f else -1f)
                alpha = (squash / .35f).coerceAtMost(1f)
            },
        )
    }
}

/**
 * The three status tones this screen needs.
 *
 * `StatusDisc` and its `Tone` table are private to PopupUi.kt, which this port may not edit, so
 * these are drawn here on the same rules: one bright fill carrying a thick white mark, and never
 * a bare disc — all the fills share one brightness, so the SHAPE inside is what a colour-blind
 * eye reads. The marks and the stroke come from the same places the originals do: [Hi] for the
 * tick and the clock, `markStroke(disc = true)` for the weight.
 */
private enum class DiscTone(val fill: Color) {
    /** A step that has been paid. */
    Paid(D.Success),

    /** Waiting — here, a week that has not started yet. */
    Pending(D.Blue),

    /** Nothing happened: a payment that did not count. Grey, because it is not a failure. */
    Nothing(D.Neutral),
}

@Composable
private fun StepDisc(tone: DiscTone, side: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(side).background(tone.fill, CircleShape), contentAlignment = Alignment.Center) {
        when (tone) {
            DiscTone.Paid -> HiIcon(Hi.Tick, D.White, DISC_ICON, disc = true)
            DiscTone.Pending -> HiIcon(Hi.Clock, D.White, DISC_ICON, disc = true)
            // There is no `Hi` for a dash. `Tone.Nothing`'s mark is the path "M7 12H17" on the
            // 24-unit icon grid, so it is drawn here at the same disc stroke rather than
            // approximated with a different line.
            DiscTone.Nothing -> Canvas(Modifier.size(DISC_ICON)) {
                val unit = size.minDimension / 24f
                scale(unit, unit, pivot = Offset.Zero) {
                    drawLine(
                        D.White, Offset(7f, 12f), Offset(17f, 12f),
                        strokeWidth = markStroke(DISC_ICON, disc = true), cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------ §4.1 the info sheet

/**
 * What the This week card does not say.
 *
 * What counts as a step, what finishing earns, and — in the note at the foot — which payments do
 * not count and that the rewards come out of a pool. Those last two are the rules somebody
 * otherwise meets only by being turned down, and with §7's "Earned nothing" list gone, **this
 * sheet is the only place they are stated**. That is why it is not optional polish.
 *
 * ### User feedback 2026-10-01 OVERRIDES §4.1's four rows
 *
 * The sheet was "really not clear". It is now two rows and a footnote:
 *
 *  - **"It starts again every week" is gone outright.** Not moved — gone.
 *  - **"Some payments do not count" is no longer a row.** As a row it read as a fifth thing to
 *    do; its content is now the note at the foot, under a small [Hi.Info] mark, which is what
 *    small print looks like. Nothing was dropped: §4.1 makes this the only statement of those
 *    rules anywhere in the app.
 *  - **The note then became a disclosure** (second round of feedback, same day). One short line
 *    saying what it is about, a "…", and the paragraph on tap. See [NOTE_LEAD] and [NOTE_FULL]:
 *    the open version is deliberately FULLER than the single sentence it replaced, because the
 *    complaint was never that the rules were too long, it was that nobody read them.
 *  - **Both surviving rows were rewritten shorter.** §10 lists the streak as undesigned, so no
 *    shipped behaviour is pinned to the old wording. The facts are, and they are kept: four tap
 *    payments inside one week, and the bonus is **on top of** what those four already earned.
 *
 * ### Why it is a Dialog
 *
 * It was a [Popup] positioned at the top left of the display, because `RewardsTab` cannot reach
 * the activity root where every other sheet in this app lives. A popup window is laid out inside
 * the system-bar insets, so the `Tray` stopped a navigation bar's height short of the bottom and
 * a strip of the page showed through under `Got it`. A [Dialog] told not to use the platform's
 * default width and not to fit the system windows is a full-screen overlay window, which is what
 * the scrim and the tray both need. See the call site in [RewardsTab].
 *
 * §10: the week's reset DAY is not in here, because nobody has said what it is. The refusal
 * rules in the note are the sandbox's three sample reasons and want confirming against the
 * server's own list.
 */
/**
 * The footnote, shut: what the paragraph is about, and a "…" that says there is more.
 *
 * It names the QUESTION rather than summarising the answer, because a summary of these rules is
 * what the old one-liner already was and it got skipped. The ellipsis is the affordance and the
 * whole row is the target; it is not coloured like a link, because the small print's one
 * treatment is [D.Quiet] and a blue word in grey type is a second voice on the sheet.
 */
private const val NOTE_LEAD = "Why a payment might not earn …"

/**
 * The footnote, open.
 *
 * **Every sentence here is the server's behaviour, not this screen's invention.** The three
 * refusals are `below_minimum`, `new_counterparty` and `counterparty_cooldown` — the same three
 * [refusalText] translates — and the pool is `Rewards.Summary.poolLeft`, a finite weekly figure
 * shared by everyone. Two things are said about WHY, and both are the rules' own logic rather
 * than new policy: a cooldown and a new-wallet check exist because a wallet is free to make, and
 * the amount is fixed at settlement, which is the fact the scratch card's theatre rests on (see
 * [ScratchCard]).
 *
 * §10: the week's reset DAY is still not in here, because nobody has said what it is. "Until the
 * pool refills" is the wording the card itself already uses when the pool is empty, so the sheet
 * and the card cannot disagree.
 */
private const val NOTE_FULL =
    "Three things stop a tap payment from earning: it was under the minimum amount, it went to " +
        "a wallet that is brand new, or you had already earned with that person this month. The " +
        "last two are there because the cheapest way to farm a reward is to pay a wallet you " +
        "just made yourself, over and over. Everything else earns, and the amount is decided " +
        "the moment the payment settles, not when you open this screen.\n\n" +
        "The weekly pool is the other limit. There is only so much SKR to give out each week " +
        "and everyone draws from the same pot, first come, first served. Once it is empty, " +
        "payments still go through but earn nothing until the pool refills, and the card " +
        "behind this sheet says so as soon as that happens."

@Composable
private fun StreakSheet(steps: Int, bonus: Int, onClose: () -> Unit) {
    // The count and the bonus are the server's, the same two figures the card behind this sheet
    // prints. Written into the sentences as "four" and "200" they would disagree with the card
    // the day either is changed.
    val count = countWord(steps)
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            // Both of these matter, for different halves of the same problem: without the first
            // the window is a floating dialog inset from every edge, and without the second it
            // is laid out inside the system-bar insets — which is exactly how the popup this
            // replaced came to stop short of the bottom edge.
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            // The `Tray` draws its own scrim and dismisses itself when it is tapped, with the
            // exit animation. Letting the window dismiss as well would skip that animation.
            dismissOnClickOutside = false,
        ),
    ) {
        // A dialog window can dim behind itself. The tray's scrim is the only scrim this app
        // has; a system dim under it would be a second one, at an opacity nobody chose.
        val host = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { host?.setDimAmount(0f) }

        Tray(onClose = onClose) {
            Spacer(Modifier.height(16.dp))
            Text("This week's streak", color = D.Black, style = Type.heading)
            Spacer(Modifier.height(Space.tight))
            Text("${count.replaceFirstChar { it.uppercase() }} tap payments in a week earn a bonus on top.",
                color = D.Quiet, style = Type.caption)

            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoRow(
                    title = "Tap to pay, that's a step",
                    detail = "Any tap payment that earns a reward fills one.",
                ) { StepDisc(DiscTone.Paid, 40.dp) }
                InfoRow(
                    title = "All $count, ${skr(bonus)} SKR",
                    detail = "Paid on top of what those $count already earned.",
                ) {
                    // **The animated coin's home**, corrected by the user on 2026-10-01 — it was
                    // briefed onto the This week card and does not belong there. This row is the
                    // one place on the screen that names SKR as the prize, so the SKR token is
                    // its picture. It replaces `week_gift`, which is now back on the card.
                    //
                    // No `clip(CircleShape)`: [SkrCoin] draws its own silhouette and that
                    // silhouette is not a circle for most of the turn — a round clip would shave
                    // the rim off exactly when the rim is the point. Its [COIN] is the 40 this
                    // row's lead box already is, so it needs no size here either.
                    // Now the token's own picture, not the drawn coin with an "S" typed on it.
                    Image(
                        painterResource(R.drawable.skr_token), contentDescription = "SKR",
                        modifier = Modifier.size(40.dp).clip(CircleShape),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            // The small print, under its own mark so it reads as a footnote rather than as one
            // more step. Quiet and 16: the mark is the same weight as the words beside it.
            //
            // **It collapses** (user feedback, 2026-10-01). The full paragraph is the only
            // statement of the refusal rules anywhere in the app, so it cannot be cut — but as
            // four lines of grey under two rows it was read as boilerplate and skipped. So the
            // row carries a lead that says what it is ABOUT and a "…", and the paragraph it opens
            // is LONGER than the one it replaced rather than shorter: somebody who taps a
            // footnote asking "why didn't that earn?" wants the reason and the reason behind it.
            //
            // §10 still leaves the streak undesigned, so nothing shipped is pinned to this
            // wording — but the three refusals and the finite shared pool are the server's own
            // behaviour (`refusalText`, `Rewards.Summary.poolLeft`) and may not drift.
            var open by remember { mutableStateOf(false) }
            val note = remember { MutableInteractionSource() }
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(Space.inline))
                    .clickable(note, indication = null) { open = !open }
                    .semantics {
                        contentDescription =
                            if (open) "Hide why a payment might not earn"
                            else "Why a payment might not earn. Tap to read more"
                    }
                    // 48 on a 18dp line of caption: the whole row is the target, not the "…".
                    .heightIn(min = 48.dp)
                    .padding(vertical = Space.inline),
                verticalAlignment = Alignment.Top,
            ) {
                HiIcon(Hi.Info, D.Quiet, 16.dp)
                Spacer(Modifier.width(Space.inline))
                Text(
                    if (open) NOTE_FULL else NOTE_LEAD,
                    color = D.Quiet, style = Type.caption, modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(24.dp))
            PrimaryButton("Got it", onClick = onClose)
        }
    }
}

/** A small count as a word, the way a sentence says it. Past ten it is a figure. */
private fun countWord(n: Int): String = when (n) {
    1 -> "one"; 2 -> "two"; 3 -> "three"; 4 -> "four"; 5 -> "five"
    6 -> "six"; 7 -> "seven"; 8 -> "eight"; 9 -> "nine"; 10 -> "ten"
    else -> "$n"
}

/** One row of the sheet: a 40 lead, 12, then the rule and what it means. */
@Composable
private fun InfoRow(title: String, detail: String, lead: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { lead() }
        Spacer(Modifier.width(Space.inline))
        Column(Modifier.weight(1f)) {
            Text(title, color = D.Black, style = Type.body)
            Spacer(Modifier.height(Space.tight))
            Text(detail, color = D.Quiet, style = Type.caption)
        }
    }
}

// ----------------------------------------------------------------------- §6 more ways to earn

/**
 * One of §6's two cards: a title, the line under it, and its own illustration.
 *
 * The same shape as Home's §5 cards — `Tiles.kt`'s `EarnCard` — because a destination should
 * look one way wherever it is offered. It is declared again here rather than reused because
 * that class and its table are `private` to Tiles.kt, which this port may not edit. The two
 * copies must stay in step: if one card's ground or inset moves there, move it here too.
 *
 * [endPct] and [belowPct] are fractions of the card's own width and height rather than dp,
 * because the design states them that way and the card's width here is half the content column
 * rather than Home's fixed 184.
 */
private class MoreWay(
    val title: String,
    val line: String,
    @DrawableRes val art: Int,
    val top: Color,
    val bottom: Color,
    val endPct: Float,
    val belowPct: Float,
)

/**
 * Offers and Referrals, with §6's own titles and lines — which differ from Home's.
 *
 * **A sanctioned exception to "no tinted backgrounds, no boxes"** (ui/PROJECT.md §3): the tint
 * is the ILLUSTRATION'S OWN GROUND, not a state, a tier or a severity, which is what the rule
 * exists to stop. Quiet grey on these two top colours measures 4.84 and 4.61:1, both clear of
 * 4.5 — which is why the top is held for the card's first 45% and why the tops must not be
 * darkened to "improve" the figure. Not palette tokens; not for reuse.
 */
private val MORE_WAYS = listOf(
    MoreWay(
        "Offers", "Pay an app, earn SKR", R.drawable.offers,
        top = Color(0xFFFEF9E7), bottom = Color(0xFFFEEAB9), endPct = .04f, belowPct = .08f,
    ),
    MoreWay(
        // A shade lighter than the design's #EBF0FD: quiet text on that is 4.47:1, under the bar.
        "Invite a friend", "They tap, you both earn", R.drawable.referrals,
        top = Color(0xFFEFF3FE), bottom = Color(0xFFDBE4FD), endPct = .02f, belowPct = .10f,
    ),
)

/** The illustration's width, as a fraction of the card: 78%. */
private const val MORE_ART = .78f

/** One card: the ground, the two lines, and the artwork behind them. */
@Composable
private fun MoreWayFace(card: MoreWay, modifier: Modifier, onOpen: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    BoxWithConstraints(
        modifier.then(PressScale(press))
            .height(168.dp)
            // The clip lives HERE, on the card, because it is the card that is meant to cut the
            // illustration off at its bottom edge.
            .clip(RoundedCornerShape(CARD_R))
            .background(Brush.verticalGradient(0f to card.top, .45f to card.top, 1f to card.bottom))
            .clickable(press, indication = null, onClick = onOpen),
    ) {
        // Drawn FIRST, so it sits behind the words: the link bubble passes under the title
        // rather than across it. It runs off the bottom on purpose.
        Image(
            painterResource(card.art),
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomEnd)
                .offset(x = -maxWidth * card.endPct, y = maxHeight * card.belowPct)
                // Width only: the height follows the drawable's own ratio, which is what keeps
                // each illustration undistorted at one width.
                .width(maxWidth * MORE_ART),
        )
        Column(Modifier.padding(16.dp)) {
            Text(card.title, color = D.Black, style = Type.heading)
            Spacer(Modifier.height(Space.tight))
            Text(card.line, color = D.Quiet, style = Type.caption)
        }
    }
}

// ------------------------------------------------------------------------------------- bits

/**
 * The figure step the opened card's amount is set in.
 *
 * The sandbox's `<Amount>` chooses the FACE and leaves size, weight and colour to the caller
 * (ui/PROJECT.md §3), so a title-sized amount is the title step wearing Geist. It is not a new
 * step in the scale — the scale is the seven sizes, and this is one of them in another face.
 */
private val TitleAmount = Type.title.figure()

/** How much of the card must go before the rest wipes itself. */
internal const val SCRATCHED_ENOUGH = 0.40f

/** The card is sampled this many cells on a side to measure coverage. */
internal const val GRID = 8

/**
 * Every refusal the server can give, said plainly.
 *
 * Unknown ones are shown as-is rather than hidden — a reason nobody can read is still better
 * than a payment that silently earned nothing.
 *
 * **Nothing on this screen calls this any more.** §7 removed the "Earned nothing" list, and §10
 * records that where the refused payments go is not designed yet. §10's own suggestion was a
 * rewards history behind `View all`, and that button has since been removed too (see the
 * Scratch & reveal heading), so the destination is now wholly open. The mapping is kept
 * rather than deleted because it is the
 * honest half of the rules, written once; the sentences are what the refusals need wherever
 * they land. §4.1's sheet now states the rules themselves.
 */
internal fun refusalText(why: String): String = when (why) {
    "new_counterparty" -> "Their wallet was too new"
    "counterparty_cooldown" -> "Already earned with them this month"
    "below_minimum" -> "Under the minimum amount"
    "not_a_tap" -> "Not paid by tapping"
    "not_a_seeker" -> "Only a Seeker earns"
    "self_payment" -> "Paid your own wallet"
    "unknown_token" -> "That token doesn't earn"
    "weekly_cap" -> "This week's four were already earned"
    "monthly_cap" -> "This month's cap was reached"
    "campaign_cap" -> "The campaign cap was reached"
    "pool_empty" -> "The pool was empty"
    "chain_unreachable" -> "Couldn't check the chain, will retry"
    "no_payer" -> "We never learned who paid"
    else -> why.replace('_', ' ')
}


/**
 * What has reached the wallet. There is nothing to press: a card is paid the moment it is
 * scratched, so this only reports — SKR on its way, SKR already paid — and is absent until there
 * is something to report.
 */
@Composable
private fun ClaimRow(s: Rewards.Summary, @Suppress("UNUSED_PARAMETER") connected: String?, @Suppress("UNUSED_PARAMETER") onDone: () -> Unit) {
    if (s.claimable <= 0 && s.claiming <= 0 && s.paid <= 0) return
    Spacer(Modifier.height(Space.lead))
    val parts = buildList {
        if (s.claimable + s.claiming > 0) add("${skr(s.claimable + s.claiming)} SKR on its way to your wallet")
        if (s.paid > 0) add("${skr(s.paid)} SKR sent to your wallet")
    }
    Text(parts.joinToString(" · "), color = D.Quiet, style = Type.caption)
}
