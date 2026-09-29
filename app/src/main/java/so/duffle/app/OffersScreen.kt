package so.duffle.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

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
        OffersBanner()
        Spacer(Modifier.height(22.dp))

        if (connected == null) {
            Text("Connect a wallet", color = D.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("An offer is claimed by the wallet that will pay, so there is nothing to show yet.",
                color = D.Quiet, fontSize = 13.sp)
            return@Column
        }

        val waiting = offers.filter { it.waiting }
        val available = offers.filter { it.available }
        val done = offers.filter { it.state == "paid" || it.state == "expired" }

        // Claimed and running against a clock. First because it is the only group with a
        // deadline attached, and the only one where doing nothing loses something.
        if (waiting.isNotEmpty()) {
            Heading("Waiting on you")
            waiting.forEach { OfferRow(it) { onOpenOffer(it) } }
            Spacer(Modifier.height(22.dp))
        }

        Heading("Offers for you")
        if (!isSeeker) {
            // Said once, at the top, rather than repeated as a refusal on every row.
            Text("Offers are for Seeker owners — this wallet holds no Genesis Token.",
                color = D.Quiet, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 12.dp))
        }
        referral?.let { ReferralOfferRow(it, onReferrals) }
        available.forEach { OfferRow(it) { onOpenOffer(it) } }
        if (available.isEmpty() && !loading) {
            Text(
                if (referral == null) "Nothing live right now."
                else "That is the only funded offer right now. More arrive as apps fund them.",
                color = D.Quiet, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (loading && offers.isEmpty()) {
            Text("Checking…", color = D.Quiet, fontSize = 13.sp)
        }

        if (done.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Heading("Finished")
            done.forEach { OfferRow(it) { onOpenOffer(it) } }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = D.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(12.dp))
}

/**
 * The strip across the top.
 *
 * Drawn rather than an illustration asset, from the ticket the Offers tile already uses — a
 * notched rectangle — with coins behind it. The one flourish on the page.
 */
@Composable
private fun OffersBanner() {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(D.Blue, Color(0xFF4DA9FF)))),
    ) {
        // matchParentSize, not fillMaxSize: the text decides how tall the banner is, and a
        // fixed height cropped the last line at this font scale.
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            listOf(.12f to .82f, .26f to .98f, .80f to .92f, .94f to .74f).forEach { (fx, fy) ->
                drawCircle(Color.White.copy(alpha = .13f), h * .12f, Offset(w * fx, h * fy))
            }
            // Three tickets, fanned. A ticket is a rectangle plus a perforation — the first
            // attempt punched notches in the edges, which cannot be done over a gradient
            // without an offscreen layer, and shipped as two invisible zero-alpha circles.
            // A dotted seam says the same thing and is actually drawn.
            listOf(.55f to .34f, .70f to .54f, .85f to .28f).forEachIndexed { i, (fx, fy) ->
                val tw = w * .15f
                val th = h * .34f
                val left = w * fx - tw / 2
                val top = h * fy - th / 2
                drawRoundRect(Color.White.copy(alpha = .15f + i * .06f), Offset(left, top),
                    Size(tw, th), CornerRadius(h * .06f))
                var y = top + th * .18f
                while (y < top + th * .85f) {
                    drawCircle(Color.White.copy(alpha = .30f), th * .035f,
                        Offset(left + tw * .62f, y))
                    y += th * .14f
                }
            }
        }
        Column(Modifier.padding(20.dp)) {
            Text("OFFERS", color = Color.White.copy(alpha = .75f), fontSize = 11.sp,
                letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Pay an app, earn SKR", color = Color.White, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, fontFamily = Display)
            Spacer(Modifier.height(4.dp))
            Text("Claim first, then pay — that order is what proves it.",
                color = Color.White.copy(alpha = .85f), fontSize = 12.5.sp)
        }
    }
}

/** One offer, compact. The conditions live behind it, in [OfferSheet]. */
@Composable
private fun OfferRow(offer: Rewards.Offer, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val finished = offer.state == "paid" || offer.state == "expired"
    Row(
        Modifier.then(PressScale(interaction)).fillMaxWidth().padding(bottom = 10.dp)
            .clip(RoundedCornerShape(16.dp)).background(D.Fill)
            .clickable(interaction, indication = null, onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OfferMark(offer.app, dim = finished)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (offer.funder == "duffle") "From AirPay" else "From ${offer.funder}",
                color = D.Quiet, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    offer.state == "paid" -> "Earned ${offer.earned} SKR"
                    offer.state == "expired" -> "Window closed"
                    else -> "Earn ${offer.reward} SKR"
                },
                color = if (finished) D.Quiet else D.Black, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                // The reason it has not paid beats the offer's own blurb once it is claimed:
                // "pay again" is actionable, the blurb is not.
                if (offer.waiting) Rewards.offerWhy(offer.refused)
                    ?: "Claimed · pay ${offer.app} within ${offer.windowHours}h"
                else offer.what,
                color = D.Quiet, fontSize = 12.5.sp, maxLines = 2,
            )
        }
        Spacer(Modifier.width(10.dp))
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
private fun ReferralOfferRow(s: Referrals.Summary, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.then(PressScale(interaction)).fillMaxWidth().padding(bottom = 10.dp)
            .clip(RoundedCornerShape(16.dp)).background(D.Fill)
            .clickable(interaction, indication = null, onClick = onOpen)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).background(D.Blue.copy(alpha = .12f), CircleShape)) {
            Canvas(Modifier.fillMaxSize().padding(11.dp)) {
                val r = size.minDimension * .28f
                drawCircle(D.Blue.copy(alpha = .40f), r, Offset(size.width * .34f, size.height * .5f),
                    style = Stroke(size.minDimension * .12f))
                drawCircle(D.Blue, r, Offset(size.width * .66f, size.height * .5f),
                    style = Stroke(size.minDimension * .12f))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("From AirPay", color = D.Quiet, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text("Earn ${s.reward} SKR", color = D.Black, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text("Bring a friend. Paid when their first tap lands.",
                color = D.Quiet, fontSize = 12.5.sp)
        }
        Spacer(Modifier.width(10.dp))
        Chevron()
    }
}

/** An app's mark. No logos to draw from, so it is the initial on the app's own disc. */
@Composable
private fun OfferMark(app: String, dim: Boolean) {
    Box(
        Modifier.size(42.dp).background(
            if (dim) D.Line else D.Blue.copy(alpha = .12f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(app.take(1).uppercase(), color = if (dim) D.Quiet else D.BlueInk, fontSize = 17.sp,
            fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Chevron() {
    Canvas(Modifier.size(9.dp, 15.dp)) {
        val w = size.width
        val h = size.height
        drawLine(D.Quiet, Offset(0f, 0f), Offset(w, h / 2), strokeWidth = w * .26f, cap = StrokeCap.Round)
        drawLine(D.Quiet, Offset(w, h / 2), Offset(0f, h), strokeWidth = w * .26f, cap = StrokeCap.Round)
    }
}

/**
 * One offer in full: what it pays, and every condition that decides whether it does.
 *
 * The conditions are the point. An offer pays on a payment to a particular address, in a
 * particular token, over a minimum, inside a window that starts **when you claim** — and
 * somebody who learns the last of those after paying has been misled by omission. They are
 * listed before the button, not under it.
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
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OfferMark(offer.app, dim = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(offer.app, color = D.Black, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    fontFamily = Display)
                Text(
                    if (offer.funder == "duffle") "Funded by AirPay" else "Funded by ${offer.funder}",
                    color = D.Quiet, fontSize = 12.sp,
                )
            }
            Text("${offer.reward} SKR", color = D.BlueInk, fontSize = 18.sp,
                fontWeight = FontWeight.Bold, style = Figures)
        }
        Spacer(Modifier.height(16.dp))
        Text(offer.what, color = D.Black, fontSize = 15.sp)
        Spacer(Modifier.height(18.dp))

        Condition("Pay at least", "${Chain.fmtUnits(offer.minimum, offer.decimals)} ${offer.symbol}")
        Condition("Within", "${offer.windowHours} hours of claiming")
        Condition("Counts from", "the moment you claim — not before")

        Spacer(Modifier.height(16.dp))
        when {
            offer.state == "paid" ->
                Text("✓ Earned ${offer.earned} SKR", color = D.BlueInk, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold)
            offer.state == "expired" ->
                Text("The window closed. Claim it again to restart the clock.",
                    color = D.Quiet, fontSize = 13.sp)
            offer.waiting ->
                Text(Rewards.offerWhy(offer.refused)
                    ?: "Claimed. Pay ${offer.app} within ${offer.windowHours}h.",
                    color = D.Quiet, fontSize = 13.sp)
            !isSeeker ->
                Text("Seeker only — this wallet holds no Genesis Token.",
                    color = D.Quiet, fontSize = 13.sp)
            else -> {
                problem?.let {
                    Text(it, color = D.DangerInk, fontSize = 12.5.sp)
                    Spacer(Modifier.height(8.dp))
                }
                PrimaryButton("Claim this offer", onClaim, enabled = !claiming, loading = claiming)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun Condition(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = D.Quiet, fontSize = 13.sp)
        Text(value, color = D.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            style = Figures)
    }
}
