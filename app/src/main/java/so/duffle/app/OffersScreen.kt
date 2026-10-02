package so.duffle.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
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
    var offers by remember { mutableStateOf<List<Rewards.Offer>>(emptyList()) }
    var referral by remember { mutableStateOf<Referrals.Summary?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(connected, reloadKey) {
        loading = true
        val me = connected
        if (me == null) {
            offers = emptyList(); referral = null
        } else coroutineScope {
            val o = async { Rewards.offers(me) }
            val r = async { Referrals.summary(me) }
            offers = o.await() ?: emptyList()
            referral = r.await()
        }
        loading = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberHeldScroll("offers"))) {
        // Before the top bar, not after it. With no wallet there is nothing to claim with, so
        // advertising offers above the reason none can be shown was the wrong order — and it
        // is the order RewardsScreen and ReferralsScreen already use for the same state.
        if (connected == null) {
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
            Text("Offers are for Seeker owners — this wallet holds no Genesis Token.",
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
        if (available.isEmpty() && referral == null && !loading) {
            Text("Nothing live right now.", color = D.Quiet, style = Type.caption,
                modifier = Modifier.padding(top = Space.inline))
        }
        if (loading && offers.isEmpty()) {
            Text("Checking…", color = D.Quiet, style = Type.caption)
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
 * A 40 face at the top start; beside it the app's name (caption, quiet), what it earns (body,
 * **bold**), and the task in at most two lines (caption, quiet). 16 above and below, none above
 * the first; the dotted line between rows is [DottedRule]'s.
 */
@Composable
private fun OfferCard(
    from: String,
    line: String,
    detail: String,
    first: Boolean,
    quiet: Boolean = false,
    onOpen: () -> Unit,
    face: @Composable () -> Unit,
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
                line,
                // An amount inside a sentence keeps the sentence's face: swapping to the figure
                // face mid-line reads as a rendering bug.
                color = if (quiet) D.Quiet else D.Black, style = BodyBold,
            )
            Spacer(Modifier.height(Space.tight))
            Text(
                detail, color = D.Quiet, style = Type.caption,
                // Two lines, which is what §1.4 asks for. It was one with an ellipsis, on the
                // argument that every other row in the app truncates — the brief overrides
                // that for this row, and the overflow stays so a third line cannot be cut off
                // with no sign that it was.
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** §1.4's line 2: `Type.body` **bold**. `Type.body` is weight 500, which is not bold. */
private val BodyBold = Type.body.copy(fontWeight = FontWeight.Bold)

/** One of the server's offers. The conditions live behind it, in [OfferSheet]. */
@Composable
private fun OfferRow(offer: Rewards.Offer, first: Boolean, onOpen: () -> Unit) {
    val finished = offer.state == "paid" || offer.state == "expired"
    OfferCard(
        // Line 1 is the APP's name, not who funded it (§1.4). An offer is a task in that app,
        // so the app is the thing being named; "From <funder>" was a label about us.
        from = offer.app,
        line = when {
            offer.state == "paid" -> "Earned ${skr(offer.earned)} SKR"
            offer.state == "expired" -> "Window closed"
            // A claimed-but-unpaid offer reads the same as an unclaimed one, because it has
            // earned nothing yet. The clock it is running against is stated in [OfferSheet].
            else -> "Earn ${skr(offer.reward)} SKR"
        },
        // The task, as one sentence. It no longer swaps to the reason a claim has not paid:
        // §1.4 defines this line as the task, and §6 makes [OfferSheet] the place the
        // claim-first rule is stated.
        detail = offer.what,
        first = first,
        quiet = finished,
        onOpen = onOpen,
    ) { OfferFace(offer) }
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
        line = "Earn ${skr(s.reward)} SKR",
        detail = "Bring a friend. Paid when their first tap lands.",
        first = first,
        onOpen = onOpen,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            // [MarkCanvas] itself, not a copy of its geometry. This file carried its own
            // version of the rings because that composable used to be private to
            // MainActivity.kt; it is `internal` now, and a second slightly-wrong copy of a
            // mark is exactly the drift PARITY.md keeps a record of.
            MarkCanvas(Mark.Rings, 32.dp, tint = D.Blue)
        }
    }
}

/**
 * An offer's face: the app's icon, round and cropped to fill — except that **the server does not
 * send one**.
 *
 * `Rewards.Offer` (Rewards.kt, `/v1/offers`) carries `app` and `funder` and no icon field, while
 * the sandbox's `Offer` type has an optional `icon`. §1.2 says the app must take icons from the
 * server, so this is a gap in the server and the model, not something to paper over with a
 * hard-coded name→asset map: a map would be right for the five sample apps and wrong for the
 * sixth, and it would read as working.
 *
 * So every row takes §1.4's no-icon case: the app's initial on its `faceColour`, through
 * [FaceDisc]. Seeded on `funder` — the app's stable id — rather than on `app`, for the reason
 * `faceColour` is seeded on an address and never a name: a display name can change and the id
 * does not, so an app keeps its colour across a rename.
 *
 * [OfferSheet] draws this too, at 48 (ui/PORT-2026-10-02.md §6.1). One function rather than two,
 * because the row and the sheet showing one app in two different colours would be a bug the
 * sandbox cannot catch — it has icons for all five of its sample apps.
 */
@Composable
private fun OfferFace(offer: Rewards.Offer, size: Dp = 40.dp) {
    val seed = offer.funder.ifBlank { offer.app }.takeIf { it.isNotBlank() }
    FaceDisc(offer.app, seed, size)
}

// ---------------------------------------------------------------------- §6 the offer sheet
//
// ui/PORT-2026-10-02.md §6, from ui/components/sections/OfferSheet.tsx.

/**
 * Where a Seeker is bought — Solana Mobile's own site, opened in the browser.
 *
 * §13 lists this as an open point: `solanamobile.com` is the site, and the exact purchase
 * address is not confirmed. The front page is the honest target until it is.
 */
private const val SEEKER_STORE = "https://solanamobile.com"

/** The claim refusal that is a dead end rather than something to try again. */
private const val ALREADY_CLAIMED = "already_claimed"

/** Three steps: claim, install, do the task. §6.2. */
private const val OFFER_STEPS = 3

/**
 * What the one button **is**, which is also what the state of the offer is said with.
 *
 * - [Go] — live, blue, and it does something.
 * - [Over] — the grey disabled button. The offer is finished or refused and the button's place is
 *   used to say so, because there is no status row any more.
 * - [Earned] — the green report. Not a button at all; it has nothing to press for.
 * - [Store] — blue, with the `open` mark: it leaves the app.
 */
private enum class Look { Go, Over, Earned, Store }

/**
 * One state of the sheet, as the things that differ between them (§6's table).
 *
 * @param done how many of the three steps carry a tick and have gone quiet.
 * @param running the ticking clock stands in for the app's icon, and the time left stands in for
 *   "Task · dApp Store". Only true while a claim is actually running against a deadline.
 * @param reward whether the reward still sits on the highlight at the end of the header. It comes
 *   off once it has been *earned*, because then the button says the figure instead.
 */
private data class OfferView(
    val label: String,
    val look: Look,
    val done: Int,
    val running: Boolean,
    val reward: Boolean,
)

/**
 * Which state the sheet is in, from what the server said plus what the phone can work out.
 *
 * The order matters. **Finished beats everything**: an expired offer shown to a wallet with no
 * Genesis Token is over, not an invitation to buy a phone. And `paid` is checked before expiry,
 * because a claim that paid and then ran past its window still paid.
 *
 * @param expired the window has run out — see [OfferSheet]'s deadline. It is checked here as well
 *   as against `state`, because the server marks a claim expired on its next sweep rather than on
 *   the stroke of the deadline, so for a minute or two `state` still reads "open".
 * @param counting there is a real deadline to count down to. A claim with no `claimed_at` cannot
 *   be counted, and the clock plus "0m left" would be a made-up figure sitting on the highlight —
 *   so such an offer keeps the app's icon and the line that is still true of it.
 * @param refused the claim attempt came back [ALREADY_CLAIMED], which no retry can fix.
 */
private fun offerView(
    offer: Rewards.Offer,
    isSeeker: Boolean,
    expired: Boolean,
    counting: Boolean,
    refused: Boolean,
): OfferView = when {
    // What was earned, not what was advertised. The sandbox has one figure for both; the app has
    // two, and the one that is a fact is the one the server credited.
    offer.state == "paid" -> OfferView(
        "Earned ${Money.token(java.math.BigDecimal(offer.earned), "SKR")}",
        Look.Earned, done = OFFER_STEPS, running = false, reward = false,
    )
    // Grey, never red. §10: red is a transaction the chain refused, and an offer's clock running
    // out is neither a transaction nor a refusal.
    offer.state == "expired" || expired -> OfferView("Expired", Look.Over, 0, false, reward = true)
    offer.waiting ->
        OfferView("Open ${offer.app}", Look.Go, done = 1, running = counting, reward = true)
    !isSeeker -> OfferView("Get a Seeker", Look.Store, 0, false, reward = true)
    refused -> OfferView("Already claimed", Look.Over, 0, false, reward = true)
    else -> OfferView("Claim this offer", Look.Go, 0, false, reward = true)
}

/**
 * One offer in full: what it pays, what to do, and every condition that decides whether it pays.
 *
 * Redesigned for ui/PORT-2026-10-02.md §6. Three parts in one order for every state, compact so
 * that nothing scrolls: the header, the three steps, the rules card. Then the button.
 *
 * **The state is said on the button, and there is no status row.** The old sheet had both, and
 * they said the same thing twice — a grey line reading "The window closed" above a button that
 * also had to be dealt with. The sheet closes by being dragged down or tapped outside, so a
 * finished offer needs no "Done": the button's place is where the eye already goes, so it is
 * where the outcome is said, and it goes grey because it no longer does anything. See [offerView]
 * for the full table.
 *
 * The rules are still stated **before** the button rather than under it, for the reason they were
 * before: somebody who learns after paying that the clock started at the claim and not at the
 * payment has been misled by omission.
 *
 * @param problem why a claim attempt did not go through, already in words. Not in §6's table —
 *   the sandbox has no failing network — but real, and it sits above the button where the
 *   not-a-Seeker line sits.
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
) {
    val context = LocalContext.current

    // The deadline is DERIVED from two server fields with the server's own rule — `offers.settle`
    // is `claimed_at + window_hours * 3600`. Nothing here invents a time: an unclaimed offer has
    // no `claimed_at`, so there is no deadline and no countdown. §13 says the sandbox's
    // `21h 14m left` is a fixed sample and the claim time is not in the sample DATA — but
    // `/v1/offers` really does send `claimed_at`, so this counts down from the real one.
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
    val refused = refusedCode == ALREADY_CLAIMED ||
        // Fallback for the call site that hands over the translated sentence (see [refusedCode]).
        // Compared against `offerWhy`'s own output rather than a copy of the words, so the two
        // cannot drift apart.
        (refusedCode == null && problem != null && problem == Rewards.offerWhy(ALREADY_CLAIMED))
    val view = offerView(offer, isSeeker, expired, counting = deadline > 0L, refused = refused)

    // "Install Jupiter and swap any token once" → "Swap any token once." — the install half is
    // already step 2, so repeating it in step 3 would number the same instruction twice.
    val task = offer.what.removePrefix("Install ${offer.app} and ")
        .trimEnd('.', ' ')
        .replaceFirstChar { it.uppercase() }
    val steps = buildList {
        add("Claim this offer.")
        add("Install ${offer.app} from the dApp Store.")
        // Only when the server sent one. A bare "." is what writing it unconditionally produces.
        if (task.isNotBlank()) add("$task.")
    }

    Tray(onClose = onClose) {
        Spacer(Modifier.height(Space.row))

        // ---- 1. the header: who the task is in, how long is left, and what it pays
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (view.running) {
                // Running against a clock, so the clock takes the icon's place and its hand
                // turns. 48 disc, 38 mark — four fifths of the disc, because the disc is the
                // clock's own face (Icons.kt).
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(D.Blue),
                    contentAlignment = Alignment.Center,
                ) { TickingClock(38.dp, D.White) }
            } else {
                // The row's face, at 48 — same seed, so one app is one colour in both places.
                OfferFace(offer, 48.dp)
            }
            Spacer(Modifier.width(Space.inline))
            Column(Modifier.weight(1f)) {
                Text(offer.app, color = D.Black, style = Type.heading,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Space.tight))
                if (view.running) {
                    // How long is left: the one fact that matters once it is claimed. On the
                    // highlight, which is the marker pen that replaced the tints — and `tnum`,
                    // so the digits do not shift sideways as they count down.
                    OnHighlight(timeLeft(deadline - now), Modifier.offset(x = -4.dp))
                } else {
                    Text("Task · dApp Store", color = D.Quiet, style = Type.caption)
                }
            }
            if (view.reward) {
                Spacer(Modifier.width(Space.inline))
                // The reward stands alone rather than sitting in a sentence, so it takes the
                // figure face. Through [Money] like every other amount: "1,000 SKR" in the US
                // and "1.000 SKR" in Germany, and a non-breaking space before the unit.
                OnHighlight(
                    Money.token(java.math.BigDecimal(offer.reward), "SKR"),
                    style = Type.heading.figure(),
                )
            }
        }

        // ---- 2. what to do
        Spacer(Modifier.height(Space.row))
        steps.forEachIndexed { i, step ->
            if (i > 0) Spacer(Modifier.height(Space.inline))
            OfferStep(i, step, done = i < view.done)
        }

        // ---- 3. the rules. Always, for every state, and above the button.
        Spacer(Modifier.height(Space.row))
        Column(
            Modifier.fillMaxWidth()
                .border(1.5.dp, D.Line, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp),
        ) {
            OfferRule("At least",
                Money.token(java.math.BigDecimal.valueOf(offer.minimum, offer.decimals), offer.symbol),
                figure = true)
            OfferRuleLine()
            // Not the figure face: a number inside a sentence keeps the sentence's face, and
            // switching mid-phrase reads as a rendering bug.
            OfferRule("Time limit", "${offer.windowHours} hours")
            OfferRuleLine()
            OfferRule("Counts from", "when you claim — not before")
        }

        // ---- 4. the button, which is also the state
        if (view.look == Look.Store) {
            Spacer(Modifier.height(Space.inline))
            Text("Offers are for Seeker owners.", color = D.Black, style = Type.body)
        }
        problem?.takeIf { !refused }?.let {
            Spacer(Modifier.height(Space.inline))
            // Ink, not danger red: nothing was sent and nothing was refused by the chain — the
            // claim simply did not go through, and it can be tried again (§10).
            Text(it, color = D.Black, style = Type.body)
        }
        Spacer(Modifier.height(Space.row))
        when (view.look) {
            // Not a button: it reports. The paid disc's green with white on it — the same pairing
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

            // `waiting`, not `running`: what the button does is settled by the claim existing,
            // not by whether there is a clock to show for it.
            Look.Go -> if (offer.waiting) {
                // `link` is the offer's own, from the server. Blank is possible — `window_hours`
                // has a default and `link` does not — and a blue button that opens nothing is
                // worse than a grey one that admits it has nowhere to go.
                PrimaryButton(
                    view.label, { openLink(context, offer.link) },
                    enabled = offer.link.isNotBlank(),
                )
            } else {
                PrimaryButton(view.label, onClaim, enabled = !claiming, loading = claiming)
            }
        }
        Spacer(Modifier.height(Space.tight))
    }
}

/**
 * How long is left, to the minute: `21h 14m left`.
 *
 * Minutes floor rather than round, so this never claims more time than there is. Under an hour
 * the hours are dropped — `0h 14m left` reads as a bug — which is a shape the sandbox's fixed
 * sample never shows.
 */
private fun timeLeft(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0L) / 60_000L)
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m left" else "${m}m left"
}

/**
 * One numbered step, and the tick it gets once it is behind you.
 *
 * A finished step takes the paid disc — the same green disc with the same thick white tick as a
 * payment that landed — and its words go quiet. The unfinished ones are an outlined 24 disc with
 * the number in it, which is a count and not a state, so it carries no colour.
 */
@Composable
private fun OfferStep(index: Int, text: String, done: Boolean) {
    // Top, not centre: a step that runs to two lines must not float its disc into the middle of
    // the sentence.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (done) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(D.Success),
                contentAlignment = Alignment.Center,
            ) { HiIcon(Hi.Tick, D.White, 20.dp, disc = true) }
        } else {
            Box(
                Modifier.size(24.dp).clip(CircleShape).border(1.5.dp, D.Line, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("${index + 1}", color = D.Black,
                    style = Type.caption.copy(fontFeatureSettings = "tnum"))
            }
        }
        Spacer(Modifier.width(Space.inline))
        Text(text, color = if (done) D.Quiet else D.Black, style = Type.body)
    }
}

/**
 * One rule: its name at the start, what it is at the end.
 *
 * No `SpaceBetween` and no fixed widths — the value takes the rest of the line and wraps, so a
 * long rule grows its row instead of being pushed off the end of it at 2× text. Baselines are
 * aligned rather than the boxes, so a wrapped value still sits on the label's line.
 */
@Composable
private fun OfferRule(label: String, value: String, figure: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = Space.inline)) {
        Text(label, color = D.Quiet, style = Type.body, modifier = Modifier.alignByBaseline())
        Spacer(Modifier.width(Space.inline))
        Text(
            value, color = D.Black,
            style = if (figure) Type.body.figure() else Type.body,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
    }
}

/** The hairline between two rules. Inside the card's padding, so it is inset with the rows. */
@Composable
private fun OfferRuleLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(D.Line))
}

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
