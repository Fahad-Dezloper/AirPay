package so.duffle.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * keeps that true. A card is never covered over a *refusal* either — something that earned
 * nothing is listed plainly further down rather than dressed up as a prize.
 *
 * ### Why the covered/uncovered state is the server's
 *
 * Kept on the phone it would reset on every reinstall, and every reward would look new again.
 * That is a small lie, but it is a lie about the one thing this screen exists to tell you.
 *
 * The refusals stay on the screen, under the cards. A rewards screen that lists only what was
 * paid makes the rules look arbitrary the first time one of them refuses.
 */
@Composable
internal fun RewardsTab(
    connected: String?,
    isSeeker: Boolean,
    onInvite: () -> Unit,
    onOffers: () -> Unit,
) {
    var summary by remember { mutableStateOf<Rewards.Summary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(connected, refresh) {
        loading = true
        summary = connected?.let { Rewards.summary(it) }
        loading = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (connected == null) {
            Spacer(Modifier.height(40.dp))
            Text("Connect a wallet", color = D.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Rewards are earned by the wallet that pays, so there is nothing to show until one is connected.",
                color = D.Quiet, fontSize = 13.sp)
            return@Column
        }
        val s = summary
        if (s == null) {
            Spacer(Modifier.height(40.dp))
            Text(if (loading) "Checking…" else "Can't reach the server", color = D.Black,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (!loading) {
                Spacer(Modifier.height(6.dp))
                Text("Your rewards are safe on the server — this screen just can't read them right now.",
                    color = D.Quiet, fontSize = 13.sp)
            }
            return@Column
        }

        RewardsHero(s, isSeeker)
        Spacer(Modifier.height(24.dp))

        // ---------------------------------------------------------------- the cards
        Text("Your rewards", color = D.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (s.rows.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(D.Fill).padding(20.dp),
            ) {
                Text("No cards yet", color = D.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (!isSeeker) "Rewards are for Seeker owners — this wallet holds no Genesis Token"
                    else "Pay someone by tapping and a card lands here.",
                    color = D.Quiet, fontSize = 13.sp,
                )
            }
        } else {
            // Two columns, laid out by hand rather than with a lazy grid: this list is capped
            // at twenty by the server and sits inside a scrolling column, where nesting a
            // second scrollable would take the gesture away from the page.
            s.rows.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { row ->
                        Box(Modifier.weight(1f)) {
                            ScratchCard(row, onScratched = {
                                val me = connected ?: return@ScratchCard
                                scope.launch { Rewards.reveal(me, row.id) }
                            })
                        }
                    }
                    // A lone card on the last row must stay one column wide, not stretch.
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        WeekProgress(s)

        // ---------------------------------------------------------------- more ways to earn
        Spacer(Modifier.height(24.dp))
        Text("More ways to earn", color = D.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        // Two doors rather than the offers themselves. They used to be listed here *and*
        // on the Offers screen, which meant two places to claim the same thing and two places
        // to keep right; a list that exists twice is a list that disagrees with itself.
        EarnDoor("Offers", "Pay an app, earn SKR", onOffers)
        InviteCard(onInvite)

        // ---------------------------------------------------------------- what earned nothing
        if (s.refusals.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Earned nothing", color = D.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Payments the rules turned down, and why.", color = D.Quiet, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            s.refusals.forEach { ref ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(refusalText(ref.why), color = D.Black, fontSize = 13.sp)
                        Text("${ref.counterparty.take(4)}…${ref.counterparty.takeLast(4)}",
                            color = D.Quiet, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}

/**
 * The headline total.
 *
 * One number, because that is the question the screen is opened to answer. The split under it
 * is not a footnote: a reward stays reversible for a week, and saying so now is cheaper than
 * explaining it after somebody has watched a balance go down.
 */
@Composable
private fun RewardsHero(s: Rewards.Summary, isSeeker: Boolean) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(D.Fill)) {
        // Drawn, not an illustration asset: the same coin discs the Rewards tile uses, so this
        // screen looks like the tile that opened it.
        Canvas(Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).size(96.dp)) {
            val cx = size.width * .55f
            listOf(.78f to .30f, .62f to .16f, .46f to .02f).forEachIndexed { i, (w, dy) ->
                drawOval(
                    color = D.Blue.copy(alpha = .16f + i * .12f),
                    topLeft = Offset(cx - size.width * w / 2, size.height * (.52f + dy)),
                    size = androidx.compose.ui.geometry.Size(size.width * w, size.height * .20f),
                )
            }
            // Two sparks, the only flourish on the screen.
            listOf(Offset(size.width * .18f, size.height * .22f) to size.minDimension * .08f,
                   Offset(size.width * .86f, size.height * .38f) to size.minDimension * .05f)
                .forEach { (at, r) ->
                    drawCircle(D.Blue.copy(alpha = .35f), radius = r, center = at)
                }
        }
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${s.total}", color = D.Blue, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                    style = Figures)
                Spacer(Modifier.width(6.dp))
                Text("SKR", color = D.Blue, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            Text("Total rewards", color = D.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    s.total == 0 && !isSeeker ->
                        "Rewards are for Seeker owners — this wallet holds no Genesis Token"
                    s.total == 0 -> "Nothing yet — a payment made by tapping earns ${s.perPayment} SKR"
                    s.locked > 0 && s.ready > 0 -> "${s.ready} ready · ${s.locked} still locked"
                    s.locked > 0 -> "Locked for ${s.lockDays} days, then yours"
                    else -> "${s.ready} ready"
                },
                color = D.Quiet, fontSize = 13.sp,
            )
            if (s.settled > 0) {
                Text("${s.settled} paid out", color = D.Quiet, fontSize = 12.sp)
            }
        }
    }
}

/**
 * One reward, covered until it is scratched.
 *
 * The foil is drawn into an offscreen layer so the scratch path can be erased out of it with
 * [BlendMode.Clear] — clearing pixels only works against a layer the composable owns; done
 * straight onto the window it punches a hole through everything behind it.
 *
 * It gives up on precision deliberately. Coverage is measured on an 8×8 grid of the card, and
 * at [SCRATCHED_ENOUGH] the rest wipes on its own — nobody should have to scrub the corners of
 * a card whose number is already decided, and a card left half-scratched forever is a worse
 * outcome than one that opens a little early.
 *
 * A **reversed** reward is never covered. Dressing a reward that was taken back as a prize to
 * unwrap is the one version of this component that would be dishonest.
 */
@Composable
private fun ScratchCard(row: Rewards.Row, onScratched: () -> Unit) {
    val reversed = row.state == "reversed"
    var scratched by remember(row.id) { mutableStateOf(row.revealed || reversed) }
    // The path is mutable and drawn every frame; `strokes` exists only to tell Compose that it
    // changed, since mutating a Path in place is invisible to recomposition.
    val path = remember(row.id) { Path() }
    var strokes by remember(row.id) { mutableIntStateOf(0) }
    val cells = remember(row.id) { mutableSetOf<Int>() }
    val cover by animateFloatAsState(if (scratched) 0f else 1f, label = "cover")

    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp))
            .background(if (reversed) D.Fill else D.Fill),
        contentAlignment = Alignment.Center,
    ) {
        RevealedFace(row, reversed)

        if (cover > 0f) {
            Canvas(
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        alpha = cover
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .pointerInput(row.id) {
                        detectDragGestures(
                            onDragStart = { at -> path.moveTo(at.x, at.y); strokes++ },
                            onDragEnd = {
                                if (!scratched && cells.size >= (GRID * GRID * SCRATCHED_ENOUGH)) {
                                    scratched = true
                                    onScratched()
                                }
                            },
                        ) { change, _ ->
                            path.lineTo(change.position.x, change.position.y)
                            val col = (change.position.x / size.width * GRID).toInt().coerceIn(0, GRID - 1)
                            val rowN = (change.position.y / size.height * GRID).toInt().coerceIn(0, GRID - 1)
                            cells += rowN * GRID + col
                            strokes++
                        }
                    },
            ) {
                strokes // read, so the canvas redraws as the path grows
                drawRect(
                    Brush.linearGradient(
                        listOf(D.Blue, Color(0xFF6AB8FF)),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                )
                // Coins scattered under the foil, so a covered card still reads as money.
                val r = size.minDimension * .07f
                listOf(.22f to .28f, .74f to .22f, .58f to .62f, .28f to .72f, .86f to .66f)
                    .forEach { (fx, fy) ->
                        drawCircle(Color.White.copy(alpha = .18f), r,
                            Offset(size.width * fx, size.height * fy))
                    }
                drawPath(
                    path, Color.Transparent,
                    style = Stroke(width = size.minDimension * .22f, cap = StrokeCap.Round,
                        join = StrokeJoin.Round),
                    blendMode = BlendMode.Clear,
                )
            }
            // Drawn above the foil, so it disappears with it.
            if (strokes == 0) {
                Column(
                    Modifier.graphicsLayer { alpha = cover },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Scratch", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("to reveal", color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
                }
            }
        }
    }
}

/** What is under the foil. Drawn always, so scratching uncovers it rather than swapping it in. */
@Composable
private fun RevealedFace(row: Rewards.Row, reversed: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (reversed) "−${row.skr}" else "+${row.skr}",
            color = if (reversed) D.Quiet else D.Blue,
            fontSize = 26.sp, fontWeight = FontWeight.Bold, style = Figures,
        )
        Text("SKR", color = if (reversed) D.Quiet else D.Blue, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(Rewards.nameOf(row.kind), color = D.Black, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        // A reward taken back says why, in the place the reward was. It is not deleted:
        // somebody who saw 300 SKR and later sees 0 deserves the reason on the same screen.
        Text(
            when {
                reversed -> row.reason?.substringAfter(": ") ?: "taken back"
                row.state == "locked" -> "locked"
                else -> "yours"
            },
            color = if (reversed) D.Danger else D.Quiet, fontSize = 11.sp,
        )
    }
}

/**
 * This week, as pips rather than a number.
 *
 * Four is small enough to see at a glance, and the streak is the thing worth finishing. The
 * pool line is here for the unhappy case: an empty pool pays nobody, and a screen that stayed
 * quiet about it would let the next payment earn nothing without warning.
 */
@Composable
private fun WeekProgress(s: Rewards.Summary) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(D.Fill).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("This week", color = D.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("${s.weekPayments} of ${s.weekAllows}", color = D.Quiet, fontSize = 13.sp, style = Figures)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(s.weekAllows) { i ->
                Box(
                    Modifier.weight(1f).height(6.dp)
                        .background(if (i < s.weekPayments) D.Blue else D.Line, RoundedCornerShape(3.dp)),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (s.weekPayments >= s.streakNeeded) "Streak complete — ${s.streak} SKR earned"
            else "${s.streakNeeded - s.weekPayments} more this week for the ${s.streak} SKR streak",
            color = D.Quiet, fontSize = 12.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row {
            Text("This month", color = D.Quiet, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${s.monthEarned} of ${s.monthCap} SKR", color = D.Black, fontSize = 12.sp, style = Figures)
        }
        Row {
            Text("Left in this week's pool", color = D.Quiet, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(if (s.poolLeft > 0) "${s.poolLeft} SKR" else "empty — nothing earns until it refills",
                color = if (s.poolLeft > 0) D.Black else D.Danger, fontSize = 12.sp, style = Figures)
        }
    }
}

/** A way out of this screen to one that earns. Same shape as [InviteCard], deliberately. */
@Composable
private fun EarnDoor(title: String, detail: String, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.then(PressScale(interaction)).fillMaxWidth().padding(bottom = 10.dp)
            .clip(RoundedCornerShape(16.dp)).background(D.Fill)
            .clickable(interaction, indication = null, onClick = onOpen).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(38.dp)) {
            // The notched ticket of the Offers tile, so the door looks like where it goes.
            val w = size.width
            drawRoundRect(D.Blue, Offset(w * .08f, w * .26f), androidx.compose.ui.geometry.Size(w * .84f, w * .48f),
                androidx.compose.ui.geometry.CornerRadius(w * .12f))
            drawCircle(D.Fill, w * .10f, Offset(w * .08f, w * .5f))
            drawCircle(D.Fill, w * .10f, Offset(w * .92f, w * .5f))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = D.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = D.Quiet, fontSize = 12.sp)
        }
        Text("Open", color = D.Blue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** The one way to earn that needs no Seeker and no offer: bring somebody. */
@Composable
private fun InviteCard(onInvite: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.then(PressScale(interaction)).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(D.Fill).clickable(interaction, indication = null, onClick = onInvite)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The same two rings the Referrals tile carries, so the row reads as that tile moved
        // here rather than as a new thing. An empty disc was the placeholder, and a placeholder
        // that ships is just a blank circle nobody can explain.
        Canvas(Modifier.size(38.dp)) {
            val r = size.minDimension * .26f
            drawCircle(D.Blue.copy(alpha = .35f), r, Offset(size.width * .34f, size.height * .5f),
                style = Stroke(size.minDimension * .11f))
            drawCircle(D.Blue, r, Offset(size.width * .66f, size.height * .5f),
                style = Stroke(size.minDimension * .11f))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Invite a friend", color = D.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("They tap, you both earn", color = D.Quiet, fontSize = 12.sp)
        }
        Text("Invite", color = D.Blue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** How much of the card must go before the rest wipes itself. */
private const val SCRATCHED_ENOUGH = 0.40f

/** The card is sampled this many cells on a side to measure coverage. */
private const val GRID = 8

/**
 * Every refusal the server can give, said plainly.
 *
 * Unknown ones are shown as-is rather than hidden — a reason nobody can read is still better
 * than a payment that silently earned nothing.
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
    "chain_unreachable" -> "Couldn't check the chain — will retry"
    "no_payer" -> "We never learned who paid"
    else -> why.replace('_', ' ')
}
