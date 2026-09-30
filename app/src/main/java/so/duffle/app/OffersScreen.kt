package so.duffle.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Offers: pay a particular app, earn SKR.
 *
 * ### Grouped by what you can do, not by how good it looks
 *
 * The screens this is modelled on sort offers into "exclusive" and "popular" — words that
 * describe how an offer was *merchandised*, not anything a reader can check. Ours has two
 * facts worth sorting on and they are both about the person looking: an offer you have already
 * claimed is **running against a clock** and needs paying; an offer you have not is a thing to
 * decide about. So claimed comes first, available second, finished last, and nothing is called
 * exclusive.
 *
 * ### The referral is listed as an offer, because it is one
 *
 * There is exactly one funded offer on this server today. Padding the page out with variants
 * of it would be the shape of dishonesty this project keeps catching — so instead the standing
 * AirPay offer that genuinely exists sits in the list beside it, marked as ours and routed to
 * the screen that explains it.
 *
 * ### Why rows open a sheet
 *
 * Every offer carries conditions — a minimum, a window, a token — that decide whether it pays.
 * Crushed into a card they became a subtitle nobody read; behind a chevron they get room to be
 * stated before somebody spends money on the strength of them.
 *
 * ### Ported to the settled system, 2026-09-30
 *
 * From `ui/components/screens/Offers.tsx` (ui/PORT.md §3, §4, §9). The banner is a poster: one
 * solid blue, one large line, the drawn tickets as its graphic — everything smaller than 19sp
 * bold has come off the blue, because white on `D.Blue` is 3.30:1 and passes only as large
 * text. The rows lost their grey bodies and their blue-tinted discs; they are divided rows with
 * a hairline avatar, like every other list in the app.
 */
@Composable
internal fun OffersTab(
    connected: String?,
    isSeeker: Boolean,
    onOpenOffer: (Rewards.Offer) -> Unit,
    onReferrals: () -> Unit,
    /** Set once the sheet has claimed something, so the list re-reads. */
    reloadKey: Int,
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Before the poster, not after it. With no wallet there is nothing to claim with, so
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

        OffersBanner()
        // The rule the banner used to carry in 12.5sp white. It is the load-bearing sentence on
        // the screen — claiming late earns nothing — so it sits on white where it can be read.
        Spacer(Modifier.height(Space.row))
        Text("Claim first, then pay — that order is what proves it.",
            color = D.Black, style = Type.body)

        val waiting = offers.filter { it.waiting }
        val available = offers.filter { it.available }
        val done = offers.filter { it.state == "paid" || it.state == "expired" }

        // Claimed and running against a clock. First because it is the only group with a
        // deadline attached, and the only one where doing nothing loses something.
        if (waiting.isNotEmpty()) {
            Spacer(Modifier.height(Space.section))
            OffersHeading("Waiting on you")
            waiting.forEachIndexed { i, offer ->
                if (i > 0) OffersHairline()
                OfferRow(offer, first = i == 0) { onOpenOffer(offer) }
            }
        }

        Spacer(Modifier.height(Space.section))
        // Spelled out rather than [OffersHeading], because the Seeker note is a line that
        // QUALIFIES the heading: it sits `tight` under it, and the `lead` gap down to the first
        // row comes after the pair instead of between them.
        Text("Offers for you", color = D.Black, style = Type.heading)
        if (!isSeeker) {
            // Said once, at the top, rather than repeated as a refusal on every row.
            Spacer(Modifier.height(Space.tight))
            Text("Offers are for Seeker owners — this wallet holds no Genesis Token.",
                color = D.Quiet, style = Type.caption)
        }
        Spacer(Modifier.height(Space.lead))
        referral?.let { ReferralOfferRow(it, first = true, onOpen = onReferrals) }
        available.forEachIndexed { i, offer ->
            if (i > 0 || referral != null) OffersHairline()
            OfferRow(offer, first = i == 0 && referral == null) { onOpenOffer(offer) }
        }
        if (available.isEmpty() && !loading) {
            Text(
                if (referral == null) "Nothing live right now."
                else "That is the only funded offer right now. More arrive as apps fund them.",
                color = D.Quiet, style = Type.caption, modifier = Modifier.padding(top = Space.inline),
            )
        }
        if (loading && offers.isEmpty()) {
            Text("Checking…", color = D.Quiet, style = Type.caption)
        }

        if (done.isNotEmpty()) {
            Spacer(Modifier.height(Space.section))
            OffersHeading("Finished")
            done.forEachIndexed { i, offer ->
                if (i > 0) OffersHairline()
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
 * The hairline between rows.
 *
 * With the grey row bodies gone (ui/PORT.md §9) this and the row's own padding are the only
 * things separating one offer from the next, so it is drawn rather than implied.
 */
@Composable
private fun OffersHairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(D.Line))
}

/**
 * The poster across the top.
 *
 * One solid blue and one large line — not a gradient, and nothing small on it: white on
 * `D.Blue` is 3.30:1, which passes as large text only, so the 11sp "OFFERS" label went (the
 * screen already says it) and the rule underneath moved onto white.
 *
 * The tickets are drawn rather than an illustration asset, from the ticket the Offers tile
 * already uses. A graphic, so their white alphas are depth rather than faded text.
 */
@Composable
private fun OffersBanner() {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 128.dp).clip(RoundedCornerShape(22.dp))
            .background(D.Blue),
    ) {
        // matchParentSize, not fillMaxSize: the text decides how tall the banner is, and a
        // fixed height cropped the last line at this font scale.
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            // Three 56dp tickets, fanned: [fraction across, fraction down, white alpha], the
            // same three as `TICKETS` in ui/components/screens/Offers.tsx. Each is centred on
            // its point, so a ticket may run off the poster and be clipped by its 22dp corner.
            //
            // Redrawn here rather than called: `MarkCanvas` and its `Mark` enum are private to
            // MainActivity.kt, so the ticket's own geometry — slab at (.08,.22) sized
            // (.84,.56) radius .14, bites of r .12 at (.08,.5) and (.92,.5) — is copied.
            //
            // The bites really are punched, and no offscreen layer is needed for it: the ground
            // is one solid D.Blue, so an OPAQUE blue circle restores it exactly. The earlier
            // attempt painted the bites in the ticket's own faded white, which composites to
            // nothing — which is why they shipped invisible and a dotted seam stood in.
            listOf(Triple(.55f, .34f, .15f), Triple(.70f, .58f, .20f), Triple(.86f, .28f, .30f))
                .forEach { (fx, fy, alpha) ->
                    val side = 56.dp.toPx()
                    val left = w * fx - side / 2
                    val top = h * fy - side / 2
                    drawRoundRect(Color.White.copy(alpha = alpha),
                        Offset(left + side * .08f, top + side * .22f),
                        Size(side * .84f, side * .56f), CornerRadius(side * .14f))
                    drawCircle(D.Blue, side * .12f, Offset(left + side * .08f, top + side * .5f))
                    drawCircle(D.Blue, side * .12f, Offset(left + side * .92f, top + side * .5f))
                }
        }
        // 24 on every edge, off the spacing scale rather than a role: this is a component's
        // own inside, not the page's gutter.
        Column(Modifier.padding(24.dp)) {
            // 24sp bold, clear of the 19sp floor for white on D.Blue.
            Text("Pay an app, earn SKR", color = Color.White, style = Type.title,
                modifier = Modifier.fillMaxWidth(.60f))
        }
    }
}

/** One offer, compact. The conditions live behind it, in [OfferSheet]. */
@Composable
private fun OfferRow(offer: Rewards.Offer, first: Boolean, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val finished = offer.state == "paid" || offer.state == "expired"
    Row(
        Modifier.then(PressScale(interaction)).fillMaxWidth().heightIn(min = 48.dp)
            .clickable(interaction, indication = null, onClick = onOpen)
            .padding(top = if (first) 0.dp else Space.row, bottom = Space.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OfferMark(offer.app)
        Spacer(Modifier.width(Space.inline))
        Column(Modifier.weight(1f)) {
            // Who funded it is a label, not a sentence.
            Text(
                if (offer.funder == "duffle") "From AirPay" else "From ${offer.funder}",
                color = D.Quiet, style = Type.caption,
            )
            Spacer(Modifier.height(Space.tight))
            Text(
                when {
                    offer.state == "paid" -> "Earned ${offer.earned} SKR"
                    offer.state == "expired" -> "Window closed"
                    else -> "Earn ${offer.reward} SKR"
                },
                // An amount inside a sentence keeps the sentence's face: swapping to the figure
                // face mid-line reads as a rendering bug.
                color = if (finished) D.Quiet else D.Black, style = Type.body,
            )
            Spacer(Modifier.height(Space.tight))
            Text(
                // The reason it has not paid beats the offer's own blurb once it is claimed:
                // "pay again" is actionable, the blurb is not.
                if (offer.waiting) Rewards.offerWhy(offer.refused)
                    ?: "Claimed · pay ${offer.app} within ${offer.windowHours}h"
                else offer.what,
                // One line with an ellipsis, which is `truncate` in the sandbox and what every
                // other row in the app does (16 of them). §8's "nothing clips" is about a
                // container being too SHORT when text grows at 2x font scale — not about a
                // long name ending in "…". `maxLines = 2` without an overflow was the worse
                // of the two anyway: it cut the third line off with no sign it had.
                color = D.Quiet, style = Type.caption,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Space.inline))
        Chevron()
    }
}

/**
 * The standing AirPay offer, in the list because it is genuinely one.
 *
 * Routed to the referrals screen rather than the offer sheet: it has no claim, no window and
 * no minimum, so a sheet built around those would have nothing to put in them.
 */
@Composable
private fun ReferralOfferRow(s: Referrals.Summary, first: Boolean, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.then(PressScale(interaction)).fillMaxWidth().heightIn(min = 48.dp)
            .clickable(interaction, indication = null, onClick = onOpen)
            .padding(top = if (first) 0.dp else Space.row, bottom = Space.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The two rings mark on the page, not on a tinted disc — the tint was a wash behind a
        // graphic that already carries its own depth.
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(32.dp)) {
                // The `rings` mark's own numbers (MainActivity.kt `MarkCanvas`), copied because
                // that composable is private to it: r = w*.26, Stroke(w*.11), back ring at .35.
                val r = size.minDimension * .26f
                drawCircle(D.Blue.copy(alpha = .35f), r, Offset(size.width * .34f, size.height * .5f),
                    style = Stroke(size.minDimension * .11f))
                drawCircle(D.Blue, r, Offset(size.width * .66f, size.height * .5f),
                    style = Stroke(size.minDimension * .11f))
            }
        }
        Spacer(Modifier.width(Space.inline))
        Column(Modifier.weight(1f)) {
            Text("From AirPay", color = D.Quiet, style = Type.caption)
            Spacer(Modifier.height(Space.tight))
            // "Earn 300 SKR" is an amount inside a sentence, so it stays in the sentence's face.
            Text("Earn ${s.reward} SKR", color = D.Black, style = Type.body)
            Spacer(Modifier.height(Space.tight))
            Text("Bring a friend. Paid when their first tap lands.",
                color = D.Quiet, style = Type.caption)
        }
        Spacer(Modifier.width(Space.inline))
        Chevron()
    }
}

/**
 * An app's mark. No logos to draw from, so it is the initial on its own disc.
 *
 * White inside a hairline rather than a blue tint: with the containers gone a tinted disc was
 * the last wash of colour on the page, and the ring is what the avatar reads as everywhere else.
 */
@Composable
private fun OfferMark(app: String) {
    // `Initial` (Design.kt) — the app's one avatar. This drew its own, and set the letter at
    // `Type.body` (a flat 15sp) where the rule is 0.38 x the disc (15.2 at 40dp). Near enough
    // to look right and wrong enough to drift at any other size.
    Initial(app.take(1).uppercase(), 40.dp)
}


/**
 * One offer in full: what it pays, and every condition that decides whether it does.
 *
 * The conditions are the point. An offer pays on a payment to a particular address, in a
 * particular token, over a minimum, inside a window that starts **when you claim** — and
 * somebody who learns the last of those after paying has been misled by omission. They are
 * listed before the button, not under it, and they are body text rather than a caption for the
 * same reason.
 */
@Composable
internal fun OfferSheet(
    offer: Rewards.Offer,
    isSeeker: Boolean,
    claiming: Boolean,
    problem: String?,
    onClaim: () -> Unit,
    onClose: () -> Unit,
) {
    Tray(onClose = onClose) {
        Spacer(Modifier.height(Space.row))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OfferMark(offer.app)
            Spacer(Modifier.width(Space.inline))
            Column(Modifier.weight(1f)) {
                Text(offer.app, color = D.Black, style = Type.heading)
                Text(
                    if (offer.funder == "duffle") "Funded by AirPay" else "Funded by ${offer.funder}",
                    color = D.Quiet, style = Type.caption,
                )
            }
            // The reward stands alone here rather than sitting in a sentence, so it takes the
            // figure face and tabular figures. The step's size, weight and tracking are the
            // heading's — only the face changes.
            Text("${offer.reward} SKR", color = D.BlueInk,
                style = Type.heading.copy(fontFamily = Figure, fontFeatureSettings = "tnum"))
        }
        Spacer(Modifier.height(Space.row))
        Text(offer.what, color = D.Black, style = Type.body)
        Spacer(Modifier.height(Space.row))

        Condition("Pay at least", "${Chain.fmtUnits(offer.minimum, offer.decimals)} ${offer.symbol}")
        Condition("Within", "${offer.windowHours} hours of claiming")
        Condition("Counts from", "the moment you claim — not before")

        Spacer(Modifier.height(Space.row))
        when {
            offer.state == "paid" ->
                Text("✓ Earned ${offer.earned} SKR", color = D.BlueInk, style = Type.body)
            offer.state == "expired" ->
                Text("The window closed. Claim it again to restart the clock.",
                    color = D.Quiet, style = Type.caption)
            offer.waiting ->
                Text(Rewards.offerWhy(offer.refused)
                    ?: "Claimed. Pay ${offer.app} within ${offer.windowHours}h.",
                    color = D.Quiet, style = Type.caption)
            !isSeeker ->
                Text("Seeker only — this wallet holds no Genesis Token.",
                    color = D.Quiet, style = Type.caption)
            else -> {
                problem?.let {
                    // Ink, not danger red: nothing was sent and nothing was refused by the
                    // chain — the claim simply did not go through, and it can be tried again.
                    Text(it, color = D.Black, style = Type.caption)
                    Spacer(Modifier.height(Space.tight))
                }
                PrimaryButton("Claim this offer", onClaim, enabled = !claiming, loading = claiming)
            }
        }
        Spacer(Modifier.height(Space.tight))
    }
}

@Composable
private fun Condition(label: String, value: String) {
    // No SpaceBetween and no fixed widths: the value takes the rest of the line and wraps, so a
    // long condition grows the row instead of being pushed off the end of it at 2× text.
    Row(Modifier.fillMaxWidth().padding(vertical = Space.tight)) {
        Text(label, color = D.Quiet, style = Type.body)
        Spacer(Modifier.width(Space.inline))
        Text(
            value, color = D.Black, style = Type.body.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End, modifier = Modifier.weight(1f),
        )
    }
}
