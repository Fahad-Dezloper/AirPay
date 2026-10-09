package so.duffle.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * Offers: a task in a dApp Store app — install it, do one thing in it, earn SKR.
 *
 * ### An offer is a task in somebody else's app
 *
 * Redefined by the user on 2026-10-01 (ui/PORT-2026-10-01-4.md §1.2). It is no longer "pay a
 * particular app and earn"; it is install an app and do one thing in it — swap, stake, deposit,
 * buy. Which means every offer now needs two things that belong to the app rather than to us:
 * its **name** and its **icon**. The server sends the name (`Rewards.Offer.app`) and does not
 * send an icon, so every row here falls back to §1.4's no-icon case. See [OfferFace].
 *
 * ### Two groups, and nothing about merchandising
 *
 * The screens this was modelled on sorted offers into "exclusive" and "popular" — words about
 * how an offer was *promoted*, which a reader cannot check. This has two groups and they are
 * both facts: an offer is **available** ("Popular offers") or it is **Finished**, meaning paid
 * or its window closed.
 *
 * The earlier "Waiting on you" group is **gone** (§1.3). A claimed-but-unpaid offer stays in
 * Popular offers until it is paid or its window closes, because claiming is not progress a
 * reader needs a separate heading for — it is a clock, and the clock is stated in [OfferSheet].
 *
 * ### The referral is listed as an offer, because it is one
 *
 * There is exactly one offer AirPay itself funds today. Padding the page out with variants of
 * it would be the shape of dishonesty this project keeps catching — so instead the standing
 * AirPay offer that genuinely exists sits first in the list, marked as ours and routed to the
 * Refer sheet rather than to the offer sheet (it has no claim, no window and no minimum, so a
 * sheet built around those would have nothing to put in them).
 *
 * ### Why the dApp rows open a sheet
 *
 * Every offer carries conditions — a minimum, a window, a token — that decide whether it pays.
 * Crushed into a row they became a subtitle nobody read; behind a sheet they get room to be
 * stated before somebody spends money on the strength of them. [OfferSheet] is **the only place
 * the claim-first rule can be stated**, which is why that line came off this screen and not out
 * of the app. ui/PORT-2026-10-01-4.md §6 left the sheet alone; ui/PORT-2026-10-02.md §6
 * redesigned it — see [OfferSheet].
 *
 * ### Ported 2026-10-01 from `ui/components/screens/Offers.tsx`
 *
 * Gone with the redesign: the solid blue poster, the "Claim first, then pay — that order is what
 * proves it." line, the "That is the only funded offer right now…" line, the hairline between
 * rows, the chevron on each row, and the "From <funder>" label (line 1 is the app's name now).
 *
 * @param onRefer the Refer sheet (§2) — what the first row, AirPay's own, opens. Defaulted so
 *   this file compiles against a call site that has not been wired yet; wire it.
 * @param onReferrals the old name of [onRefer], kept only so MainActivity.kt's existing
 *   `onReferrals = { tab = Tab.REFERRALS }` still compiles while that file is edited elsewhere.
 *   Pass [onRefer] instead and delete this.
 * @param onBack §1.1's back arrow. Optional for the same reason [RewardsTab]'s is: MainActivity.kt
 *   still draws its own `BackHeader("Offers")` above this screen, so until that call site passes
 *   a lambda the arrow is drawn (it is part of §1.1's title row) and does nothing, and the screen
 *   shows two back rows. Removing `Tab.OFFERS -> "Offers"` from that header and passing `onBack`
 *   is the one-line change that fixes it.
 */
@Composable
internal fun OffersTab(
    connected: String?,
    isSeeker: Boolean,
    onOpenOffer: (Rewards.Offer) -> Unit,
    onRefer: () -> Unit = {},
    onReferrals: () -> Unit = onRefer,
    /** Set once the sheet has claimed something, so the list re-reads. */
    reloadKey: Int,
    onBack: (() -> Unit)? = null,
) {
    // Keyed on the wallet, so another wallet's offers are never shown while this one's load.
    var offers by remember(connected) { mutableStateOf<List<Rewards.Offer>>(emptyList()) }
    var referral by remember(connected) { mutableStateOf<Referrals.Summary?>(null) }
    var loading by remember { mutableStateOf(true) }
    // The last read got no answer at all. Kept apart from "the server answered with no offers",
    // because the page said "Nothing live right now." for both and only one of them is true.
    var failed by remember(connected) { mutableStateOf(false) }

    LaunchedEffect(connected, reloadKey, Session.version, Live.tick) {
        loading = true
        val me = connected
        if (me != null) coroutineScope {
            val o = async { Rewards.offers(me) }
            val r = async { Referrals.summary(me) }
            val got = o.await()
            // A refresh that fails keeps what is already on screen. Emptying the list on one
            // dropped request made every offer vanish and come back on the next tick.
            if (got != null) offers = got
            failed = got == null
            r.await()?.let { referral = it }
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberHeldScroll("offers"))) {
        // Before the top bar, not after it. With no wallet there is nothing to claim with, so
        // advertising offers above the reason none can be shown was the wrong order — and it
        // is the order RewardsScreen and ReferralsScreen already use for the same state.
        if (connected == null) {
            // The top bar is not drawn in this state and MainActivity draws no header for this
            // tab, so without this row the page had no title and no back arrow at all.
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackArrow(onBack)
                Text("Offers", color = D.Black, style = Type.title)
            }
            Spacer(Modifier.height(Space.section))
            Text("Connect a wallet", color = D.Black, style = Type.heading)
            Spacer(Modifier.height(Space.tight))
            Text("An offer is claimed by the wallet that will pay, so there is nothing to show yet.",
                color = D.Quiet, style = Type.caption)
            return@Column
        }

        OffersTopBar(onBack)

        val available = offers.filter { it.available || it.waiting }
        val done = offers.filter { it.state == "paid" || it.state == "expired" }

        Spacer(Modifier.height(Space.section))
        // Spelled out rather than [OffersHeading], because the Seeker note is a line that
        // QUALIFIES the heading: it sits `tight` under it, and the `lead` gap down to the first
        // row comes after the pair instead of between them.
        Text("Popular offers", color = D.Black, style = Type.heading)
        if (!isSeeker) {
            // Said once, at the top, rather than repeated as a refusal on every row.
            Spacer(Modifier.height(Space.tight))
            Text("Offers are for Seeker owners. This wallet does not hold a Seeker Genesis Token.",
                color = D.Quiet, style = Type.caption)
        }
        Spacer(Modifier.height(Space.lead))
        // AirPay's own offer is the FIRST row of this group, always (§1.4). Its reward comes
        // from the server like every other reward — the brief's "Earn 300 SKR" is the sandbox's
        // mock figure, the same way its "Earn 400 SKR" is — so the row waits on the referral
        // summary rather than printing a number this screen made up.
        referral?.let { ReferralOfferRow(it, first = true, onOpen = onReferrals) }
        available.forEachIndexed { i, offer ->
            if (i > 0 || referral != null) DottedRule()
            OfferRow(offer, first = i == 0 && referral == null) { onOpenOffer(offer) }
        }
        // "Nothing live right now." only when the group really is empty — AirPay's own row
        // counts. On the phone it read as a flat contradiction: a live offer with a line
        // underneath saying there were none.
        if (failed && offers.isEmpty()) {
            // No answer is not the same as no offers. A lapsed session gets the way to sign in
            // again; anything else is said plainly, the way the Refer sheet says it.
            if (!Session.valid(connected)) {
                SignInNeeded("see offers")
            } else {
                if (referral != null) Spacer(Modifier.height(Space.row))
                Text("Can't reach the server", color = D.Black, style = Type.body)
                Spacer(Modifier.height(Space.tight))
                Text("Offers come from the server, so none can be shown right now. " +
                    "Check your connection and come back.",
                    color = D.Quiet, style = Type.caption)
            }
        } else if (available.isEmpty() && referral == null && !loading) {
            Text("Nothing live right now.", color = D.Quiet, style = Type.caption,
                modifier = Modifier.padding(top = Space.inline))
        }
        // Only on a first load. With AirPay's own row already showing, a skeleton under it on
        // every refresh was a flicker about nothing.
        if (loading && offers.isEmpty() && referral == null && !failed) {
            Spacer(Modifier.height(Space.lead))
            OffersSkeleton()
        }

        // Only when there is at least one (§1.3). Both endings live here: paid, and the window
        // closed without a payment.
        if (done.isNotEmpty()) {
            Spacer(Modifier.height(Space.section))
            OffersHeading("Finished")
            done.forEachIndexed { i, offer ->
                if (i > 0) DottedRule()
                OfferRow(offer, first = i == 0) { onOpenOffer(offer) }
            }
        }
        Spacer(Modifier.height(Space.section))
    }
}

@Composable
private fun OffersHeading(text: String) {
    Text(text, color = D.Black, style = Type.heading)
    Spacer(Modifier.height(Space.lead))
}

/**
 * The line between two offer rows: **dotted**, 1.5 thick, `D.Neutral` (§1.4).
 *
 * Drawn as circles rather than with `PathEffect.dashPathEffect`, because a dotted CSS border is
 * round dots and the dash that produces those is a degenerate zero-length segment with a round
 * cap — which is a Skia detail, not a guarantee. A dot of the rule's own thickness with a gap of
 * the same width is what `divide-dotted` renders, and it is three lines of arithmetic here.
 *
 * With the boxes and the chevrons gone this and the row's own padding are the only things
 * separating one offer from the next, so it is drawn rather than implied.
 */
@Composable
private fun DottedRule() {
    Canvas(Modifier.fillMaxWidth().height(RULE)) {
        val r = size.height / 2f
        // Dot, then a gap its own width.
        val step = size.height * 2f
        var x = r
        while (x <= size.width - r) {
            drawCircle(D.Neutral, r, Offset(x, r))
            x += step
        }
    }
}

/** The dotted rule's thickness, which is also a dot's diameter. */
private val RULE = 1.5.dp

// ----------------------------------------------------------------------------- §1.1 the top bar

/** §1.1: full width, 184 tall, on the page's white. No coloured bar. */
private val BAR_H = 184.dp

/** The illustration at full size, and the 2:1 ratio `offers_banner` (1056 × 528) is drawn at. */
private val ART_W = 272.dp
private val ART_H = 136.dp

/** How far down the bar the illustration starts, and how far past the screen's end edge it runs. */
private val ART_TOP = 40.dp
private val ART_PAST_END = 8.dp

/** §1.1: the tagline is at most 158 wide, which puts it on two lines. */
private val TAGLINE_MAX = 158.dp

/**
 * The three edge ramps, as fractions of the illustration's own box (§1.1).
 *
 * They exist so the picture's pale ground melts into the page's white, which is why the **end**
 * edge has none: that edge is cut by the screen, so there is no join to hide.
 */
private const val FADE_START = .25f
private const val FADE_TOP = .14f
private const val FADE_BOTTOM = .18f

/** The gap kept between where the words end and where the illustration becomes opaque. */
private val ART_CLEARANCE = 8.dp

private const val INTRO_A = "More ways"
private const val INTRO_B = "to earn"
private const val TAGLINE = "Complete simple actions and earn rewards"

/**
 * The top bar: the back arrow and "Offers" on one line, then what the screen is for, with the
 * user's illustration on the end side.
 *
 * **It bleeds through the 20dp gutter.** The page applies that gutter (MainActivity.kt), so the
 * bar buys it back the way `TokenBadges` does: measured two gutters wider than the page allows,
 * reported at the page's own width, and placed one gutter to the start. Inside this box the
 * coordinates are then the **screen's**, which is why the words carry `Space.gutter` as padding
 * of their own and the illustration can run off the end edge.
 *
 * `clipToBounds` is on the widened box, not the page: the illustration is pushed 8 past the end
 * edge on purpose and that 8 has to be cut by the screen. Relying on an ancestor to cut it would
 * be relying on `clipScrollableContainer`'s 30dp of slack, which is a Compose implementation
 * detail and not a promise.
 *
 * `heightIn(min = …)` rather than `height(…)`: 184 is the design's height, and at a large font
 * scale the words need more than that. §1.1's rule is that the words win — so the bar grows and
 * the illustration shrinks, never the other way round. See [artWidthFor].
 */
@Composable
private fun OffersTopBar(onBack: (() -> Unit)?) {
    BoxWithConstraints(
        Modifier
            .layout { measurable, constraints ->
                val gutter = Space.gutter.roundToPx()
                // EXACT, not `minWidth = 0`: this box is the screen, so it must be the screen's
                // width whatever its content measures to. (`TokenBadges` passes 0 because a
                // scrolling row is sized by its cards; this is sized by the page.)
                val wide = constraints.maxWidth + 2 * gutter
                val placeable = measurable.measure(
                    constraints.copy(minWidth = wide, maxWidth = wide),
                )
                // The parent's OWN width is reported, and the wider content placed one gutter
                // to the start. Reporting the placeable's width here shifts the bar by two
                // gutters instead of one — the mistake `TokenBadges` records having measured.
                layout(constraints.maxWidth, placeable.height) { placeable.place(-gutter, 0) }
            }
            .heightIn(min = BAR_H)
            .clipToBounds(),
    ) {
        val barW = maxWidth
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()

        // How wide the words actually are, MEASURED — not inferred from `fontScale`. The same
        // reasoning as the pay bar's two-line labels: the type, the tracking and the 158dp cap
        // are all real numbers, and a threshold on the scale would be a different wrong answer
        // on every device.
        val wordsW = remember(measurer, density.density, density.fontScale) {
            val intro = maxOf(
                measurer.measure(INTRO_A, Type.title).size.width,
                measurer.measure(INTRO_B, Type.title).size.width,
            )
            val tagline = measurer.measure(
                TAGLINE, Type.caption,
                constraints = Constraints(maxWidth = with(density) { TAGLINE_MAX.roundToPx() }),
            ).size.width
            with(density) { maxOf(intro, tagline).toDp() }
        }
        val artW = artWidthFor(barW, wordsW)

        if (artW > 0.dp) {
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            Image(
                painterResource(R.drawable.offers_banner),
                contentDescription = null,
                // 1056 × 528 into a 2:1 box, so Fit and Crop are the same picture. Fit, because
                // nothing of it is meant to be cut except by the screen's end edge.
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.TopEnd)
                    .size(artW, artW * (ART_H / ART_W))
                    // `offset` is layout-direction aware, so +8 is "past the END edge" in both
                    // directions, and 40 down is 40 down in both.
                    .offset(x = ART_PAST_END, y = ART_TOP)
                    // The layer is OUTSIDE the wipe, so the alpha comes out of the picture
                    // alone — the same reason the Rewards hero's gift and grid each own one.
                    .graphicsLayer {
                        // The illustration is lit and has a direction, so it mirrors. The mirror
                        // also carries the start-edge ramp to the other side for free, which is
                        // what makes one wipe correct in both layout directions.
                        if (rtl) scaleX = -1f
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        // Two wipes, which multiply — the same thing CSS `mask-composite:
                        // intersect` does to the sandbox's two gradients.
                        drawRect(
                            Brush.horizontalGradient(
                                0f to Color.Transparent, FADE_START to Color.Black,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                FADE_TOP to Color.Black,
                                1f - FADE_BOTTOM to Color.Black,
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }

        Column(Modifier.align(Alignment.TopStart).padding(horizontal = Space.gutter)) {
            // The whole row is pulled 12 into the gutter rather than just the arrow's box, which
            // is what the sandbox's `-ms-3` does and what `RewardsTitle` already does: the
            // arrow's INK then starts on the page edge the tagline starts on, and the title
            // follows it by the same 12. A negative padding would throw in Compose, so the shift
            // is an offset — it moves the drawing without changing the row's measured size.
            Row(verticalAlignment = Alignment.CenterVertically) {
                BackArrow(onBack)
                Text("Offers", color = D.Black, style = Type.title)
            }
            Spacer(Modifier.height(16.dp))
            // Two lines, written as two lines: the break is the design's, not the wrap's.
            Text("$INTRO_A\n$INTRO_B", color = D.Black, style = Type.title)
            Spacer(Modifier.height(8.dp))
            Text(TAGLINE, color = D.Quiet, style = Type.caption,
                modifier = Modifier.widthIn(max = TAGLINE_MAX))
        }
    }
}

/**
 * How wide the illustration may be drawn, given how wide the words turned out to be.
 *
 * §1.1 ends with a rule: the text column and the illustration **must not overlap**, and if they
 * do at a larger font scale it is the illustration that shrinks. This is that rule as
 * arithmetic.
 *
 * The thing that must clear the words is not the picture's box — its first [FADE_START] is wiped
 * to nothing, and at full size that transparent run is 68 of its 272. It is the point where the
 * picture becomes **opaque**:
 *
 * ```
 * opaque edge = barW + ART_PAST_END - artW + FADE_START * artW
 * words end   = Space.gutter + wordsW
 * ```
 *
 * Requiring the first to stay [ART_CLEARANCE] past the second and solving for `artW` gives the
 * line below. At 1× on the Seeker's 400dp it allows 307 and the illustration keeps its full 272,
 * which is the check that this is a ceiling rather than a resize. At 2× text the tagline is
 * still capped at 158dp but `More ways` measures about 236, and the illustration comes down to
 * roughly 200 × 100 — smaller, and still not under the words.
 */
private fun artWidthFor(barW: Dp, wordsW: Dp): Dp {
    val room = barW + ART_PAST_END - Space.gutter - wordsW - ART_CLEARANCE
    return minOf(ART_W, room / (1f - FADE_START)).coerceAtLeast(0.dp)
}

// ------------------------------------------------------------------------------- §1.4 the rows

/**
 * One offer, as a row on the page (§1.4): no box, no chevron, the whole row the tap target.
 *
 * A 40 face at the top start; beside it the app's name (caption, quiet), the task as a sentence
 * (body, **bold**), and under that whatever [below] draws: the reward on the highlight, and the
 * progress of a counted task. 16 above and below, none above the first; the dotted line between
 * rows is [DottedRule]'s.
 *
 * The task leads and the reward sits under it as a badge, the way payment apps list offers. It was
 * the other way round ("Earn 50 SKR" in bold, the task in grey), which made every row read the
 * same until the small print.
 */
@Composable
private fun OfferCard(
    from: String,
    title: String,
    first: Boolean,
    quiet: Boolean = false,
    onOpen: () -> Unit,
    face: @Composable () -> Unit,
    below: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.then(PressScale(interaction)).fillMaxWidth().heightIn(min = 48.dp)
            .clickable(interaction, indication = null, onClick = onOpen)
            .padding(top = if (first) 0.dp else Space.row, bottom = Space.row),
        // Top, not centre: the face lines up with the app's name, and a row whose task runs to
        // two lines does not float its icon into the middle of the sentence.
        verticalAlignment = Alignment.Top,
    ) {
        face()
        Spacer(Modifier.width(Space.inline))
        Column(Modifier.weight(1f)) {
            Text(from, color = D.Quiet, style = Type.caption)
            Spacer(Modifier.height(Space.tight))
            Text(
                title, color = if (quiet) D.Quiet else D.Black, style = BodyBold,
                // Two lines, and the overflow stays so a third cannot be cut off with no sign
                // that it was.
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space.tight))
            below()
        }
    }
}

/** §1.4's bold line: `Type.body` **bold**. `Type.body` is weight 500, which is not bold. */
private val BodyBold = Type.body.copy(fontWeight = FontWeight.Bold)

/**
 * What an offer pays, as it is advertised: the server's own words ("Flat 50 SKR", "Up to
 * 200 SKR") when it sent them, and "Earn N SKR" from an older server that did not.
 */
private fun rewardLine(offer: Rewards.Offer): String =
    offer.rewardText ?: "Earn ${skr(offer.reward)} SKR"

/** One of the server's offers. The conditions live behind it, in [OfferSheet]. */
@Composable
private fun OfferRow(offer: Rewards.Offer, first: Boolean, onOpen: () -> Unit) {
    val finished = offer.state == "paid" || offer.state == "expired"
    OfferCard(
        // Line 1 is the APP's name, not who funded it (§1.4). An offer is a task in that app,
        // so the app is the thing being named; "From <funder>" was a label about us.
        from = offer.app,
        // The task, as one sentence.
        title = offer.what,
        first = first,
        quiet = finished,
        onOpen = onOpen,
        face = { OfferFace(offer) },
    ) {
        when {
            // What was earned, not what was advertised, and still on the highlight: it is the
            // one figure on a finished row worth finding.
            offer.state == "paid" -> OnHighlight("Earned ${skr(offer.earned)} SKR")
            // Plain and quiet. Nothing was earned, so there is nothing to mark.
            offer.state == "expired" ->
                Text("Window closed", color = D.Quiet, style = Type.caption)
            else -> {
                OnHighlight(rewardLine(offer))
                // Only once it is claimed. Before that nothing counts towards it, so "0 of 3
                // done" on an unclaimed offer would be a count of things that do not count.
                offer.progress?.takeIf { offer.waiting }?.let { OfferProgress(it) }
            }
        }
    }
}

/**
 * How far along a counted offer is: "2 of 3 done" over a thin bar.
 *
 * The bar is `D.Blue` on `D.Line`, not the highlight: the highlight only ever sits behind ink
 * text, and alone on white it cannot be seen.
 */
@Composable
private fun OfferProgress(p: Rewards.Progress) {
    Spacer(Modifier.height(Space.inline))
    Text(
        "${p.done} of ${p.need} done", color = D.Quiet,
        style = Type.caption.copy(fontFeatureSettings = "tnum"),
    )
    Spacer(Modifier.height(Space.tight))
    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(D.Line)) {
        if (p.done > 0) {
            Box(
                Modifier.fillMaxWidth(p.done.toFloat() / p.need).height(4.dp)
                    .clip(CircleShape).background(D.Blue),
            )
        }
    }
}

/**
 * The standing AirPay offer, in the list because it is genuinely one. Opens the Refer sheet.
 *
 * The `rings` mark on the page, not on a tinted disc — the tint was a wash behind a graphic that
 * already carries its own depth. 32 inside the 40 box the app icons fill, so the row's faces all
 * line up whether they are a picture or a mark.
 */
@Composable
private fun ReferralOfferRow(s: Referrals.Summary, first: Boolean, onOpen: () -> Unit) {
    OfferCard(
        from = "AirPay",
        title = "Bring a friend. Paid when their first tap lands.",
        first = first,
        onOpen = onOpen,
        face = {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                // The brand's own mark, from the one place that draws it.
                BrandMark(34.dp)
            }
        },
    ) { OnHighlight("Earn ${skr(s.reward)} SKR") }
}

/**
 * An offer's face: the app's icon, round and cropped to fill.
 *
 * The icon comes from the server (`icon` on `/v1/offers`, an https address stored with the offer),
 * not from a name→asset map in the app: a map would be right for the apps it knew and wrong for
 * the next one, while looking like it worked. It is drawn OVER the app's initial on its own
 * colour, so the row is never blank — the initial shows while the picture loads and stays if it
 * cannot (offline, a dead link, an offer with no icon yet).
 *
 * AirPay's own offers (the app is AirPay) carry AirPay's own mark — the rings the referral row
 * draws — because there is no outside logo to fetch for ourselves. NOT keyed on `funder`: that
 * is who pays the reward, and AirPay pays for the Jupiter offer too.
 *
 * [OfferSheet] draws this too, at 48 (ui/PORT-2026-10-02.md §6.1). One function rather than two,
 * so the row and the sheet cannot show one app two ways.
 */
@Composable
private fun OfferFace(offer: Rewards.Offer, size: Dp = 40.dp) {
    // SKR staking is a task about the token itself, so its face is the token. Bundled, because
    // the token is ours to draw and a staking row with a letter on a disc read as a stranger's app.
    if (offer.app.equals("SKR Staking", ignoreCase = true) || offer.id.startsWith("skr-")) {
        Image(
            painterResource(R.drawable.skr_token), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape),
        )
        return
    }
    if (offer.app.equals("AirPay", ignoreCase = true)) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            BrandMark(size * 0.86f)
        }
        return
    }
    // Seeded on the app, not on `funder`: that is who PAYS the reward, which is AirPay for every
    // launch offer, so seeding on it gave every app the same colour.
    val seed = offer.app.takeIf { it.isNotBlank() }
    Box(Modifier.size(size)) {
        FaceDisc(offer.app, seed, size)
        offer.icon?.takeIf { it.startsWith("https://") }?.let { url ->
            AsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}

// ---------------------------------------------------------------------- the offer sheet
//
// First laid out for ui/PORT-2026-10-02.md §6 as a compact card that never scrolled. Rewritten
// 2026-10-05 as a guide: the owner's brief was that somebody who has never swapped or staked
// should finish reading it knowing what to do, where to do it and how.

/**
 * Where a Seeker is bought: Solana Mobile's own site, opened in the browser.
 *
 * §13 lists this as an open point: `solanamobile.com` is the site, and the exact purchase
 * address is not confirmed. The front page is the honest target until it is.
 */
private const val SEEKER_STORE = "https://solanamobile.com"

/** The claim refusal that is a dead end rather than something to try again. */
private const val ALREADY_CLAIMED = "already_claimed"

/**
 * Every claim refusal that no retry can fix, and what the grey button says for it. The offer
 * ending and its budget running out are as final as a claim that already exists; left out of this
 * they kept a live blue "Claim this offer" that could be pressed for ever.
 */
private val DEAD_ENDS = mapOf(
    ALREADY_CLAIMED to "Already claimed",
    "not_live" to "Offer ended",
    "offer_budget" to "Fully claimed",
)

/** Two presses closer together than this are one press. See the claim button in [OfferSheet]. */
private const val CLAIM_DEBOUNCE_MS = 1_000L

/** More steps than any offer has, so "all of them" is ticked however many the server sent. */
private const val OFFER_STEPS = Int.MAX_VALUE

/** One section of the sheet and the next. Half a page's section gap, because a sheet is denser. */
private val SHEET_GAP = Space.inline * 2

/**
 * The column every leading mark sits in: a step's disc, a tick, the gift, the clock. One width,
 * so the words beside them all start on the same edge whatever the mark is.
 */
private val MARK_COL = 24.dp

/** How much of the screen's height the scrolling middle may take. The rest is header and button. */
private const val BODY_SHARE = 0.6f

/**
 * What the one button **is**, which is also what the state of the offer is said with.
 *
 * - [Go]: live, blue, and it does something.
 * - [Over]: the grey disabled button. The offer is finished or refused and the button's place is
 *   used to say so, because there is no status row.
 * - [Earned]: the green report. Not a button at all; it has nothing to press for.
 * - [Store]: blue, with the `open` mark: it leaves the app.
 */
private enum class Look { Go, Over, Earned, Store }

/**
 * One state of the sheet, as the things that differ between them.
 *
 * @param done how many of the steps carry a tick and have gone quiet.
 * @param live the prize can still be won or was won, so it keeps the highlight. An expired or
 *   refused offer keeps its figure and loses the marker pen.
 */
private data class OfferView(val label: String, val look: Look, val done: Int, val live: Boolean)

/**
 * Which state the sheet is in, from what the server said plus what the phone can work out.
 *
 * The order matters. **Finished beats everything**: an expired offer shown to a wallet with no
 * Genesis Token is over, not an invitation to buy a phone. And `paid` is checked before expiry,
 * because a claim that paid and then ran past its window still paid.
 *
 * @param expired the window has run out. It is checked here as well as against `state`, because
 *   the server marks a claim expired on its next sweep rather than on the stroke of the deadline,
 *   so for a minute or two `state` still reads "open".
 * @param refused what the grey button says when the claim attempt came back as one of
 *   [DEAD_ENDS], which no retry can fix. Null when it did not.
 */
private fun offerView(
    offer: Rewards.Offer,
    first: String?,
    isSeeker: Boolean,
    expired: Boolean,
    refused: String?,
): OfferView = when {
    // What was earned, not what was advertised. The one that is a fact is the one the server
    // credited.
    offer.state == "paid" -> OfferView(
        "Earned ${Money.token(java.math.BigDecimal(offer.earned), "SKR")}",
        Look.Earned, done = OFFER_STEPS, live = true,
    )
    // Grey, never red. §10: red is a transaction the chain refused, and an offer's clock running
    // out is neither a transaction nor a refusal.
    offer.state == "expired" || expired -> OfferView("Expired", Look.Over, 0, live = false)
    // The server's label when it sent a button, because "Open AirPay" is nonsense for a task
    // that is done in the app already open.
    offer.waiting -> OfferView(
        offer.cta?.label ?: "Open ${offer.app}", Look.Go, done = claimedSteps(first), live = true,
    )
    !isSeeker -> OfferView("Get a Seeker", Look.Store, 0, live = true)
    refused != null -> OfferView(refused, Look.Over, 0, live = false)
    else -> OfferView("Claim this offer", Look.Go, 0, live = true)
}

/**
 * How many steps a claimed offer has behind it, given the first step's title.
 *
 * The server's steps are its own words and need not start with the claim, so the first is ticked
 * only when it says it is the claim. Ticking "Recharge any phone" because a claim happened would
 * be a tick for something nobody did.
 */
private fun claimedSteps(first: String?): Int =
    if (first != null && first.startsWith("claim", ignoreCase = true)) 1 else 0

/**
 * The steps the sheet lists, best first: the server's `guide` (a title and how to do it), else its
 * older `steps` (titles only), else the three this sheet always wrote, which are only right for a
 * task in a dApp Store app.
 */
private fun guideFor(offer: Rewards.Offer): List<Rewards.Step> {
    if (offer.guide.isNotEmpty()) return offer.guide
    if (offer.steps.isNotEmpty()) return offer.steps.map { Rewards.Step(it) }
    // "Install Jupiter and swap any token once" becomes "Swap any token once". The install half
    // is already step 2, so repeating it in step 3 would number the same instruction twice.
    val task = offer.what.removePrefix("Install ${offer.app} and ")
        .trimEnd('.', ' ')
        .replaceFirstChar { it.uppercase() }
    return buildList {
        add(Rewards.Step("Claim this offer", "Claiming starts the clock. Only what you do after it counts."))
        // Not for a task done inside AirPay: there is nothing to install, the app is already open.
        if (!offer.inAirPay && !offer.app.equals("AirPay", ignoreCase = true)) {
            add(Rewards.Step("Install ${offer.app} from the dApp Store"))
        }
        // Only when the server sent one. An empty step is what writing it unconditionally produces.
        if (task.isNotBlank()) add(Rewards.Step(task))
    }
}

/**
 * The rules behind "Rules and limits": the server's as it wrote them, else the conditions the
 * phone can state from the offer's own fields. They replace each other rather than joining,
 * because the server knows which conditions this offer really has.
 */
private fun rulesFor(offer: Rewards.Offer): List<String> {
    if (offer.rules.isNotEmpty()) return offer.rules
    return buildList {
        // Only when there is a minimum. Most offers have none, and "At least 0 SOL" states a
        // condition that is not one.
        if (offer.minimum > 0L) {
            val least = Money.token(
                java.math.BigDecimal.valueOf(offer.minimum, offer.decimals), offer.symbol,
            )
            add("The amount must be at least $least.")
        }
        add("Finish within ${windowWords(offer.windowHours)} of claiming.")
        add("Only what you do after claiming counts, not before.")
    }
}

/**
 * Where the task is done, as the chip says it: the server's own label, else the most the phone can
 * honestly say. Null when it can say nothing, and the chip is then left off.
 */
private fun whereFor(offer: Rewards.Offer): String? = offer.where ?: when {
    offer.inAirPay || offer.app.equals("AirPay", ignoreCase = true) -> "In AirPay"
    offer.app.isNotBlank() -> "In the ${offer.app} app"
    else -> null
}

/**
 * One offer in full, written for somebody who has never done the task: what it is, what it pays,
 * what is needed, where and how to do it, when the reward arrives, and the rules.
 *
 * Top to bottom, in one order for every state: the header, the reward, what it is, what you need,
 * how to do it, when you get paid, the time limit and the rules. Then the button.
 *
 * **The middle scrolls and the button does not.** The sheet is now as tall as its words, and a
 * button that scrolls away under five steps is a sheet with no way forward. This is the shape
 * `PurchaseSheet` already has.
 *
 * **The state is said on the button, and there is no status row.** The sheet closes by its cross,
 * by being dragged down or by a tap outside, so a finished offer needs no "Done": the button's
 * place is where the eye already goes, so it is where the outcome is said, and it goes grey
 * because it no longer does anything. See [offerView] for the full table.
 *
 * **The time limit is never folded away.** The rules are long, so they sit behind one row. The
 * clock does not, because somebody who learns after paying that it started at the claim and not at
 * the payment has been misled by omission.
 *
 * **Claiming does not close the sheet.** The caller swaps [offer] for the claimed one, and the
 * same sheet ticks its first step and turns its button into the way to the task. Closing it sent
 * people back to a list to find the offer they had just chosen.
 *
 * Everything descriptive is the server's when it sends it (`about`, `where`, `guide`, `payout`,
 * `needs`, `rules`, `cta`), because only it knows whether the task is in a dApp Store app or
 * inside AirPay. An older server sends none, and each section then falls back or leaves itself
 * out: see [guideFor], [rulesFor] and [whereFor].
 *
 * @param problem why a claim attempt did not go through, already in words. It sits above the
 *   button, where the not-a-Seeker line sits.
 * @param refusedCode the same refusal as its machine-readable code, which is what distinguishes
 *   "try again" from the dead end that is [ALREADY_CLAIMED]. Defaulted because MainActivity.kt
 *   translates the code before passing it; pass the raw `why` here and this stops guessing.
 */
@Composable
internal fun OfferSheet(
    offer: Rewards.Offer,
    isSeeker: Boolean,
    claiming: Boolean,
    problem: String?,
    onClaim: () -> Unit,
    onClose: () -> Unit,
    refusedCode: String? = null,
    /**
     * The claimed offer's button, when it leads somewhere inside AirPay: one of "recharge",
     * "shop", "tap" or "pay". The sheet does not close itself or navigate; the caller owns both,
     * because the pages it leads to are the caller's state.
     */
    onGo: (String) -> Unit = {},
) {
    val context = LocalContext.current

    // The deadline is DERIVED from two server fields with the server's own rule: `offers.settle`
    // is `claimed_at + window_hours * 3600`. Nothing here invents a time: an unclaimed offer has
    // no `claimed_at`, so there is no deadline and no countdown.
    val deadline =
        if (offer.claimedAt > 0L) offer.claimedAt + offer.windowHours * 3_600_000L else 0L
    var now by remember(deadline) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(deadline) {
        // Only while there is something to count, and it stops itself at zero rather than
        // leaving a one-second loop running behind a sheet that says nothing new.
        while (deadline > System.currentTimeMillis()) {
            delay(1_000L)
            now = System.currentTimeMillis()
        }
        now = System.currentTimeMillis()
    }

    val expired = deadline > 0L && now >= deadline
    val refusedLabel = DEAD_ENDS.entries.firstOrNull { (code, _) ->
        refusedCode == code ||
            // Fallback for the call site that hands over the translated sentence (see
            // [refusedCode]). Compared against `offerWhy`'s own output rather than a copy of the
            // words, so the two cannot drift apart.
            (refusedCode == null && problem != null && problem == Rewards.offerWhy(code))
    }?.value
    val refused = refusedLabel != null

    // When Claim was last pressed. `claiming` only disables the button on the next frame, so two
    // presses inside one frame both reached the caller and sent two claims.
    val lastClaim = remember { longArrayOf(0L) }

    val guide = guideFor(offer)
    val view = offerView(offer, guide.firstOrNull()?.title, isSeeker, expired, refusedLabel)
    val rules = rulesFor(offer)

    // What the claimed offer's button does. "link" leaves AirPay; anything else is a page in it.
    val target = offer.cta?.target ?: "link"
    val leaves = view.look == Look.Go && offer.waiting && target == "link" && offer.link.isNotBlank()

    Tray(onClose = onClose) {
        Spacer(Modifier.height(Space.lead))

        // ---- 1. the header: whose task it is, and the task. It stays put while the rest scrolls.
        Row(verticalAlignment = Alignment.Top) {
            // The row's face, at 48: same picture, so one app is one face in both places.
            OfferFace(offer, 48.dp)
            Spacer(Modifier.width(Space.inline))
            Column(Modifier.weight(1f)) {
                // The task is the title and the app is the line above it, the same order as the
                // row this sheet was opened from. An older server can send no task at all, and
                // then the app's name is the only title there is.
                if (offer.what.isNotBlank()) {
                    Text(offer.app, color = D.Quiet, style = Type.caption,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(Space.tight))
                }
                Text(
                    offer.what.ifBlank { offer.app }, color = D.Black, style = Type.heading,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            // The cross every other sheet has, in the place it has it. 48 so it is a full touch
            // target; pushed out by the box's own slack so the cross's ink ends on the sheet's edge.
            Box(
                Modifier.offset(x = 13.dp, y = (-10).dp).size(48.dp).clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { HiIcon(Hi.Close, D.Black, 22.dp, contentDescription = "Close") }
        }
        Spacer(Modifier.height(Space.row))

        // Everything between the header and the button scrolls. `fill = false` so a short offer
        // makes a short sheet, and the cap so a long one stops short of the top of the screen.
        val bodyMax = (LocalConfiguration.current.screenHeightDp * BODY_SHARE).dp
        Column(
            Modifier.weight(1f, fill = false).heightIn(max = bodyMax)
                .verticalScroll(rememberScrollState()),
        ) {
            // ---- 2. the reward: the one thing on the sheet that is a prize, so the only block
            // with a frame and the only place the highlight is used.
            RewardBlock(offer, live = view.live, where = whereFor(offer))

            // ---- 3. what it is
            offer.about?.let {
                Spacer(Modifier.height(SHEET_GAP))
                Text(it, color = D.Black, style = Type.body)
            }

            // ---- 4. "What you need" used to sit here. It said a Seeker phone (the page already
            // says who the offers are for), the amount (the steps say it, where it is used) and
            // a little SOL for the fee (true of everything on Solana). Three lines that told
            // nobody anything, so the section is gone and the steps carry the amount.

            // ---- 5. how to do it
            SheetHeading("How to do it")
            guide.forEachIndexed { i, step ->
                GuideStep(i, step, done = i < view.done, last = i == guide.lastIndex)
            }

            // ---- 6. when you get paid
            offer.payout?.let {
                SheetHeading("When you get paid")
                MarkRow({ HiIcon(Hi.Gift, D.Black, 20.dp) }) {
                    Text(it, color = D.Black, style = Type.body)
                }
            }

            // ---- 7. the clock and the rules, as one panel. The clock is its first line and is
            // always in view; the rest opens under it.
            Spacer(Modifier.height(SHEET_GAP))
            RulesPanel(
                rules,
                // A paid offer has no clock left to state: it beat it.
                clock = if (view.look != Look.Earned) ({
                    TimeLimitRow(offer, deadline, now, expired = expired || offer.state == "expired")
                }) else null,
            )
            Spacer(Modifier.height(Space.tight))
        }

        // ---- 8. the button, which is also the state. Under a line, so words scrolling up to it
        // are cut by something rather than by nothing.
        RowLine()
        if (view.look == Look.Store) {
            Spacer(Modifier.height(Space.inline))
            Text("Offers are for Seeker owners.", color = D.Black, style = Type.body)
        }
        problem?.takeIf { !refused }?.let {
            Spacer(Modifier.height(Space.inline))
            // Ink, not danger red: nothing was sent and nothing was refused by the chain. The
            // claim simply did not go through, and it can be tried again (§10).
            Text(it, color = D.Black, style = Type.body)
        }
        Spacer(Modifier.height(Space.inline))
        when (view.look) {
            // Not a button: it reports. The paid disc's green with white on it, the same pairing
            // as that disc and its tick, and 19sp bold is the floor white clears on this fill.
            Look.Earned -> Box(
                Modifier.fillMaxWidth().heightIn(min = 60.dp)
                    .clip(RoundedCornerShape(18.dp)).background(D.Success)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(view.label, color = D.White, style = Type.heading.copy(fontFeatureSettings = "tnum")) }

            // The way in, not a dead end: the offer cannot be claimed without a Seeker, so the
            // button is where to get one. It leaves the app, and the mark says so.
            Look.Store -> LeaveButton(view.label) { openLink(context, SEEKER_STORE) }

            Look.Over -> PrimaryButton(view.label, {}, enabled = false)

            // `waiting`: what the button does is settled by the claim existing, not by whether
            // there is a clock to show for it.
            Look.Go -> if (offer.waiting) {
                if (target == "link") {
                    // `link` is the offer's own, from the server. Blank is possible, and a blue
                    // button that opens nothing is worse than a grey one that admits it has
                    // nowhere to go.
                    PrimaryButton(
                        view.label, { openLink(context, offer.link) },
                        enabled = offer.link.isNotBlank(),
                    )
                } else {
                    // A page inside AirPay. Always live: it needs no link.
                    PrimaryButton(view.label, { onGo(target) })
                }
            } else {
                PrimaryButton(
                    view.label,
                    {
                        val t = android.os.SystemClock.uptimeMillis()
                        if (t - lastClaim[0] >= CLAIM_DEBOUNCE_MS) {
                            lastClaim[0] = t
                            onClaim()
                        }
                    },
                    enabled = !claiming, loading = claiming,
                )
            }
        }
        if (leaves) {
            // Said only when the button really is a door out. Somebody sent to another app with
            // no word about coming back has no reason to think AirPay will notice what they did.
            Spacer(Modifier.height(Space.tight * 2))
            Text(
                "You will leave AirPay. Come back when you are done; we check it on the chain.",
                color = D.Quiet, style = Type.caption, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Space.tight))
    }
}

/**
 * A section's name: small, quiet capitals, with the section gap above it built in.
 *
 * Not [Type.heading]. The task in the header is the sheet's one heading, and five more at its
 * weight would make every section shout as loudly as the title.
 */
@Composable
private fun SheetHeading(text: String) {
    Spacer(Modifier.height(SHEET_GAP))
    Text(text.uppercase(), color = D.Quiet, style = Type.micro)
    Spacer(Modifier.height(Space.lead))
}

/**
 * A line with a mark in front of it. The mark sits in [MARK_COL], centred on the first line of
 * the words, so every marked line on the sheet starts its words on the same edge as a step does.
 */
@Composable
private fun MarkRow(
    mark: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Top, not centre: words that run to two lines must not float their mark into the middle.
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // 20 tall, which is one line of `Type.body`, so the mark centres on the first line.
        Box(Modifier.size(MARK_COL, 20.dp), contentAlignment = Alignment.Center) { mark() }
        Spacer(Modifier.width(Space.inline))
        Box(Modifier.weight(1f)) { content() }
    }
}

/**
 * What the offer pays, as the sheet's anchor: what kind of reward, the figure, where it is earned,
 * and how far along a counted task is.
 *
 * The frame is the rules card's old one (1.5 `D.Line`, corner 16), so the prize is the one framed
 * thing on a sheet of plain lines.
 *
 * @param live false once the prize can no longer be won. The figure stays, because the offer was
 *   for that much; the marker pen comes off, because it marks something to go and get.
 */
@Composable
private fun RewardBlock(offer: Rewards.Offer, live: Boolean, where: String?) {
    val paid = offer.state == "paid"
    val floor = offer.rewardMin
    // "Up to 200 SKR" is a different promise from "200 SKR", and the bare figure would overstate
    // it. So a server that words the reward itself, and does not call it flat, is quoted.
    val phrase = offer.rewardText?.takeIf {
        !paid && floor == null && !it.startsWith("flat", ignoreCase = true)
    }
    Column(
        Modifier.fillMaxWidth()
            .border(1.5.dp, D.Line, RoundedCornerShape(16.dp))
            .padding(Space.row),
    ) {
        Text(
            when {
                paid -> "Earned"
                floor != null -> "Scratch card"
                phrase != null -> "Reward"
                else -> "Flat reward"
            }.uppercase(),
            color = D.Quiet, style = Type.micro,
        )
        Spacer(Modifier.height(Space.tight * 2))
        Row(
            // Pulled back by its own padding, so the figure's ink starts on the label's edge and
            // the highlight overhangs it the way a marker stroke overhangs a word.
            Modifier.offset(x = (-6).dp)
                .background(if (live) D.Highlight else D.Fill, RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp),
        ) {
            when {
                phrase != null -> Text(phrase, color = D.Black, style = Type.title.figure(), maxLines = 1)
                else -> {
                    // What was earned once it is paid, not what was advertised.
                    val top = if (paid) offer.earned else offer.reward
                    if (floor != null && !paid) {
                        RewardFigure(skr(floor))
                        // A word in a sentence of figures, so it takes the sentence's face.
                        Text(" to ", color = D.Black, style = Type.heading,
                            modifier = Modifier.alignByBaseline())
                    }
                    RewardFigure(skr(top))
                    Text(" SKR", color = D.Black, style = Type.heading,
                        modifier = Modifier.alignByBaseline())
                }
            }
        }
        where?.let {
            Spacer(Modifier.height(Space.inline))
            // A chip, because it is a place and not a sentence. `D.Fill` rather than a tint: the
            // highlight is the reward's alone.
            Text(
                it, color = D.Black, style = Type.caption,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.background(D.Fill, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        // Only once it is claimed. Before that nothing counts towards it, so "0 of 3 done" on an
        // unclaimed offer would be a count of things that do not count.
        offer.progress?.takeIf { offer.waiting }?.let { OfferProgress(it) }
    }
}

/** One number of the reward, in the amount face. Baseline-aligned with the words beside it. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.RewardFigure(text: String) {
    Text(text, color = D.Black, style = Type.amount, maxLines = 1, softWrap = false,
        modifier = Modifier.alignByBaseline())
}

/**
 * How long is left, to the minute: `21h 14m left`.
 *
 * Minutes floor rather than round, so this never claims more time than there is. Under an hour
 * the hours are dropped, because `0h 14m left` reads as a bug. From two days up it is days and
 * hours, because most windows are a month and `719h 14m left` is a sum left for the reader. And
 * the last minute says so, because `0m left` on an offer that is still open reads as over.
 */
private fun timeLeft(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0L) / 60_000L)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h >= 48 -> "${h / 24}d ${h % 24}h left"
        h > 0 -> "${h}h ${m}m left"
        m > 0 -> "${m}m left"
        else -> "Under 1m left"
    }
}

/**
 * An offer's window in the unit somebody would say it in: "3 days", "30 days", "36 hours".
 *
 * The server sends hours, and a month-long window read "720 hours from when you claim". Days only
 * when the hours are whole days, so nothing is rounded into a longer window than there is.
 */
private fun windowWords(hours: Int): String = when {
    hours >= 48 && hours % 24 == 0 -> "${hours / 24} days"
    hours == 1 -> "1 hour"
    else -> "$hours hours"
}

/**
 * One step of the guide: its number in a disc, the title in ink, how to do it in quiet text under
 * it, and a hairline running down to the next step's disc.
 *
 * A finished step takes the paid disc (the same green disc with the same thick white tick as a
 * payment that landed) and its title goes quiet. The unfinished ones are an outlined disc with
 * the number in it, which is a count and not a state, so it carries no colour.
 *
 * `IntrinsicSize.Min` is what lets the hairline be exactly as tall as the words beside it: the
 * row is as tall as its text, and the line takes whatever the disc leaves.
 */
@Composable
private fun GuideStep(index: Int, step: Rewards.Step, done: Boolean, last: Boolean) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(
            Modifier.width(MARK_COL).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (done) {
                Box(
                    Modifier.size(MARK_COL).clip(CircleShape).background(D.Success),
                    contentAlignment = Alignment.Center,
                ) { HiIcon(Hi.Tick, D.White, 20.dp, disc = true) }
            } else {
                Box(
                    Modifier.size(MARK_COL).clip(CircleShape).border(1.5.dp, D.Line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", color = D.Black,
                        style = Type.caption.copy(fontFeatureSettings = "tnum"))
                }
            }
            // `D.Line`, not the highlight or a colour: it joins the steps, it does not report on
            // them. The discs do that.
            if (!last) {
                Box(
                    Modifier.weight(1f).padding(vertical = Space.tight)
                        .width(1.5.dp).background(D.Line),
                )
            }
        }
        Spacer(Modifier.width(Space.inline))
        Column(
            // 2 down, so a 20 line of body text sits centred on the 24 disc.
            Modifier.weight(1f).padding(top = 2.dp, bottom = if (last) 0.dp else Space.row),
        ) {
            // The amount is the one thing in a step that can be got wrong, so it is marked.
            Text(amountsMarked(step.title, done), color = if (done) D.Quiet else D.Black, style = Type.body)
            step.detail?.let {
                Spacer(Modifier.height(Space.tight))
                Text(it, color = D.Quiet, style = Type.caption)
            }
        }
    }
}

/**
 * The time limit, on a row of its own that is never folded away.
 *
 * Three things it can say. Before the claim: how long there will be, and that it counts from the
 * claim. While it runs: what is left, on the highlight, in tabular figures so the digits do not
 * shift as they count down. After: that it ended. A claim with no `claimed_at` cannot be counted,
 * and "0m left" would be a made-up figure, so it gets the first sentence instead.
 */
@Composable
private fun TimeLimitRow(offer: Rewards.Offer, deadline: Long, now: Long, expired: Boolean) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = Space.inline),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(MARK_COL), contentAlignment = Alignment.Center) {
            HiIcon(Hi.Clock, D.Black, 20.dp)
        }
        Spacer(Modifier.width(Space.inline))
        Text("Time limit", color = D.Black, style = Type.body)
        Spacer(Modifier.width(Space.inline))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            when {
                expired -> Text("Ended", color = D.Quiet, style = Type.caption)
                offer.waiting && deadline > 0L -> OnHighlight(timeLeft(deadline - now))
                else -> Text(
                    "${windowWords(offer.windowHours)} from when you claim",
                    color = D.Quiet, style = Type.caption, textAlign = TextAlign.End,
                )
            }
        }
    }
}

/**
 * The time limit and the rules, as one soft panel.
 *
 * They were three hairline rows at the foot of the sheet: a clock, then a row that opened into
 * grey bullets in small type. It read as fine print bolted on. Now it is one rounded panel on the
 * page's quiet fill, set apart from the steps above it: the clock on top, always showing, then
 * "Rules and limits" with how many there are, opening into the rules at reading size, each with
 * its own mark. Still closed by default, because open they push the steps off the first screen.
 */
@Composable
private fun RulesPanel(rules: List<String>, clock: (@Composable () -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "rules")
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(D.Fill).padding(horizontal = Space.row)) {
        if (clock != null) {
            clock()
            Box(Modifier.fillMaxWidth().height(1.dp).background(D.Line))
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null) { open = !open }
                .semantics { contentDescription = if (open) "Hide rules and limits" else "Show rules and limits" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(MARK_COL), contentAlignment = Alignment.Center) {
                HiIcon(Hi.Info, D.Black, 20.dp)
            }
            Spacer(Modifier.width(Space.inline))
            Text("Rules and limits", color = D.Black, style = Type.body.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.width(8.dp))
            // How many there are, so opening it is a known quantity.
            Text(
                "${rules.size}", color = D.Black, style = Type.caption.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.background(D.White, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            HiIcon(Hi.ArrowDown, D.Black, 18.dp, modifier = Modifier.graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(bottom = Space.row)) {
                rules.forEachIndexed { i, rule ->
                    if (i > 0) Spacer(Modifier.height(Space.inline))
                    Row(verticalAlignment = Alignment.Top) {
                        // A small white disc with a tick: each rule is a thing that holds, not
                        // a bullet in a list of small print.
                        Box(
                            Modifier.padding(top = 1.dp).size(MARK_COL - 4.dp).background(D.White, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { HiIcon(Hi.Tick, D.Black, 12.dp) }
                        Spacer(Modifier.width(Space.inline + 4.dp))
                        Text(rule, color = D.Black, style = Type.body, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * [text] with its amounts on the highlight: "$10", "0.1 SOL", "100 SKR".
 *
 * Not once the step is done. A finished step is quiet, and a marked figure in it would still be
 * asking to be read.
 */
private fun amountsMarked(text: String, done: Boolean): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        if (done) return@buildAnnotatedString
        AMOUNT.findAll(text).forEach { m ->
            addStyle(
                androidx.compose.ui.text.SpanStyle(background = D.Highlight, fontWeight = FontWeight.SemiBold),
                m.range.first, m.range.last + 1,
            )
        }
    }

/** A dollar figure, or a number followed by a token's symbol. */
private val AMOUNT = Regex("""\$\d+(?:\.\d+)?|\d+(?:\.\d+)?\s(?:SOL|SKR|USDC)""")

/**
 * A value worth noticing, on the highlight — the marker pen that replaced the tints.
 *
 * `D.Highlight` only ever sits behind ink text; alone on white it is invisible at 1.15:1, so it
 * is never a dot, a line, a ring or a border.
 */
@Composable
private fun OnHighlight(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.caption,
) {
    Text(
        text, color = D.Black, style = style.copy(fontFeatureSettings = "tnum"), maxLines = 1,
        modifier = modifier.background(D.Highlight, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp),
    )
}

/**
 * The blue button that leaves the app, with the `open` mark after its label.
 *
 * [PrimaryButton] takes a label and nothing else, and the mark is the half of this button that
 * says it is a door out — so it is built here rather than by widening the shared button for one
 * call site. Everything else about it is [PrimaryButton]'s: 60 min height, corner 18, `D.Blue`,
 * white `Type.heading`, and the 0.97 press.
 */
@Composable
private fun LeaveButton(label: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.then(PressScale(press)).fillMaxWidth().heightIn(min = 60.dp)
            .clip(RoundedCornerShape(18.dp)).background(D.Blue)
            .clickable(press, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = D.White, style = Type.heading)
            Spacer(Modifier.width(8.dp))
            // `hi_open` through HIcon, which routes it to the stroke-following vector the day
            // Icons.kt adopts it and paints the drawable until then.
            HIcon(R.drawable.hi_open, D.White, 20.dp)
        }
    }
}

/**
 * Opens a link in whatever the phone browses with.
 *
 * `runCatching`, because `startActivity` throws on a phone with nothing that handles the scheme,
 * and the flag because a sheet's context is not always the Activity.
 */
private fun openLink(context: Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(
            if (context is android.app.Activity) intent
            else intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
