package so.duffle.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
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
 *
 * ### Ported to the settled system, 2026-09-30
 *
 * From `ui/components/screens/Rewards.tsx` (ui/PORT.md §3, §4, §9). The grey `D.Fill` bodies are
 * gone: the total, the week and the doors sit on the page, grouped by space and a `D.Line`
 * hairline, each row still clearing the 48dp touch minimum. The scratch cover is solid blue
 * carrying one 19sp bold word, because white on `D.Blue` is 3.30:1 and passes only as large
 * text — "to reveal" moved off the blue and under the grid.
 *
 * ### Arrangement brought to parity with the sandbox
 *
 * Every gap, size, radius and string now matches `Rewards.tsx` at 1 dp = 1 px, and every amount
 * goes through [Money] instead of being interpolated. What still differs, on purpose:
 *
 *  - **Three lines the sandbox cannot have**, because it has no server: what is still locked,
 *    what has been paid out, and the month's cap. Each is a fact that changes whether a payment
 *    will earn, so leaving them off to match a mock would be the screen lying by omission.
 *  - **"Earned nothing" is gated on there being a refusal**, not on there being a card. The
 *    sandbox's refusals are a constant, so it gates on the cards instead; here an empty section
 *    takes its spacer with it, which is the rule for every other section in the app.
 *  - **The card keeps its clip.** See [ScratchCard].
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
            Spacer(Modifier.height(Space.section))
            Text("Connect a wallet", color = D.Black, style = Type.heading)
            Spacer(Modifier.height(Space.tight))
            Text("Rewards are earned by the wallet that pays, so there is nothing to show until one is connected.",
                color = D.Quiet, style = Type.caption)
            return@Column
        }
        val s = summary
        if (s == null) {
            Spacer(Modifier.height(Space.section))
            Text(if (loading) "Checking…" else "Can't reach the server", color = D.Black,
                style = Type.heading)
            if (!loading) {
                Spacer(Modifier.height(Space.tight))
                Text("Your rewards are safe on the server — this screen just can't read them right now.",
                    color = D.Quiet, style = Type.caption)
            }
            return@Column
        }

        RewardsHero(s, isSeeker)
        Spacer(Modifier.height(Space.section))

        // ---------------------------------------------------------------- the cards
        Text("Your rewards", color = D.Black, style = Type.heading)
        if (s.rows.isEmpty()) {
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
            val pairs = s.rows.chunked(2)
            pairs.forEachIndexed { i, pair ->
                // The 12dp is a grid GAP, so the last row does not carry one: a trailing
                // padding here would silently add 12 to every section gap below the grid.
                Row(Modifier.fillMaxWidth()
                    .padding(bottom = if (i == pairs.lastIndex) 0.dp else Space.inline),
                    horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
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
            // The instruction the foil used to carry at 12sp white, which failed on blue. It
            // says the honest part too: the number was decided before the card was drawn.
            if (s.rows.any { !it.revealed && it.state != "reversed" }) {
                // 12 from the grid, not Space.lead: this note trails the grid, it does not
                // lead a section. (The sandbox writes it `mt-3`.)
                Spacer(Modifier.height(12.dp))
                Text("Scratch a card to reveal it. The amount was set when the payment settled.",
                    color = D.Quiet, style = Type.caption)
            }
        }

        Spacer(Modifier.height(Space.section))
        WeekProgress(s)

        // ---------------------------------------------------------------- more ways to earn
        Spacer(Modifier.height(Space.section))
        Text("More ways to earn", color = D.Black, style = Type.heading)
        Spacer(Modifier.height(Space.lead))

        // Two doors rather than the offers themselves. They used to be listed here *and*
        // on the Offers screen, which meant two places to claim the same thing and two places
        // to keep right; a list that exists twice is a list that disagrees with itself.
        EarnDoor(DoorMark.Ticket, "Offers", "Pay an app, earn SKR", first = true, onOpen = onOffers)
        RewardsHairline()
        EarnDoor(DoorMark.Rings, "Invite a friend", "They tap, you both earn",
            first = false, onOpen = onInvite)

        // ---------------------------------------------------------------- what earned nothing
        if (s.refusals.isNotEmpty()) {
            Spacer(Modifier.height(Space.section))
            Text("Earned nothing", color = D.Black, style = Type.heading)
            Spacer(Modifier.height(Space.tight))
            Text("Payments the rules turned down, and why.", color = D.Quiet, style = Type.caption)
            Spacer(Modifier.height(Space.lead))
            s.refusals.forEachIndexed { i, ref ->
                if (i > 0) RewardsHairline()
                Column(
                    Modifier.fillMaxWidth()
                        .padding(top = if (i == 0) 0.dp else Space.row, bottom = Space.row),
                ) {
                    // Prose, so body. The counterparty beside it is an identifier, so mono.
                    Text(refusalText(ref.why), color = D.Black, style = Type.body)
                    Spacer(Modifier.height(Space.tight))
                    Text("${ref.counterparty.take(4)}…${ref.counterparty.takeLast(4)}",
                        color = D.Quiet, style = Type.mono)
                }
            }
        }

        Spacer(Modifier.height(Space.section))
    }
}

/**
 * The hairline between rows.
 *
 * With the grey container bodies gone (ui/PORT.md §9) this and the row's own padding are the
 * only things separating one row from the next, so it is drawn rather than implied.
 */
@Composable
private fun RewardsHairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(D.Line))
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row {
                // Type.amount is the Figure face with tabular figures — the step this number is.
                // The code shares the figures' baseline rather than being nudged onto it with a
                // bottom padding, so it stays put when the text scale changes.
                // `text-ink` in Rewards.tsx:71-72, not blue. Blue ink is for a LINK or a
                // thing you can act on; a total is a fact you read.
                Text(skr(s.total), color = D.Black,
                    style = Type.amount, modifier = Modifier.alignByBaseline())
                Spacer(Modifier.width(Space.tight))
                Text("SKR", color = D.Black, style = Type.heading,
                    modifier = Modifier.alignByBaseline())
            }
            Spacer(Modifier.height(Space.tight))
            Text("Total rewards", color = D.Black, style = Type.body)
            Spacer(Modifier.height(Space.tight))
            Text(
                when {
                    s.total == 0 && !isSeeker ->
                        "Rewards are for Seeker owners — this wallet holds no Genesis Token"
                    s.total == 0 ->
                        "Nothing yet — a payment made by tapping earns ${skr(s.perPayment)} SKR"
                    // The sandbox has no server, so it can only say "N SKR ready" — everything
                    // it holds is ready. The real summary knows what is still locked, and a
                    // reward that cannot be spent yet must not be counted as one that can.
                    s.locked > 0 && s.ready > 0 ->
                        "${skr(s.ready)} ready · ${skr(s.locked)} still locked"
                    s.locked > 0 -> "Locked for ${s.lockDays} days, then yours"
                    else -> "${skr(s.ready)} SKR ready"
                },
                color = D.Quiet, style = Type.caption,
            )
            if (s.settled > 0) {
                Text("${skr(s.settled)} paid out", color = D.Quiet, style = Type.caption)
            }
        }
        Spacer(Modifier.width(Space.inline))
        // Drawn, not an illustration asset: the same coin stack `MarkCanvas` draws for the
        // Rewards tile, on the same numbers, so this screen looks like the tile that opened it.
        // Three equal discs with the ones behind faded — a graphic, so the fill and the alphas
        // stay; this is not text. An earlier copy here tapered the discs and faded the wrong
        // end, which is the drift ui/PARITY.md records against the `coins` mark.
        Canvas(Modifier.size(40.dp)) {
            val w = size.width
            repeat(3) { i ->
                drawOval(
                    color = if (i == 0) D.Blue else D.Blue.copy(alpha = .3f + .2f * (2 - i)),
                    topLeft = Offset(w * .12f, w * (.52f - i * .19f)),
                    size = Size(w * .76f, w * .3f),
                )
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
 *
 * The card itself is white inside a hairline, not a grey box: with no containers left, the
 * outline is what still makes the grid read as cards.
 *
 * **One known difference from the sandbox.** `Rewards.tsx` puts no overflow clip on the card,
 * because a CSS `aspect-square` box GROWS past square when the text scale does and the words
 * survive. Compose's `aspectRatio(1f)` is a hard constraint, not a minimum, so the box cannot
 * grow — dropping the clip here would spill the words over the cards beside it instead of
 * keeping them. The clip stays until the square is a minimum rather than a size; see
 * ui/PORT.md §8.
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
        Modifier.fillMaxWidth().aspectRatio(1f)
            .border(1.5.dp, D.Line, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(D.White),
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
                // A poster, not a gradient: one solid blue, so the one word on it is the only
                // thing that has to hold a contrast ratio.
                drawRect(D.Blue)
                // Coins scattered under the foil, so a covered card still reads as money.
                // A fixed 16dp disc, not a fraction of the card: they are 16 in the sandbox,
                // and at .07 of a 174dp card they came out half again too big.
                val r = 8.dp.toPx()
                listOf(.22f to .28f, .74f to .22f, .58f to .62f, .28f to .72f, .86f to .66f)
                    .forEach { (fx, fy) ->
                        drawCircle(Color.White.copy(alpha = .20f), r,
                            Offset(size.width * fx, size.height * fy))
                    }
                drawPath(
                    path, Color.Transparent,
                    style = Stroke(width = size.minDimension * .22f, cap = StrokeCap.Round,
                        join = StrokeJoin.Round),
                    blendMode = BlendMode.Clear,
                )
            }
            // Drawn above the foil, so it disappears with it. One word, at Type.heading: 19sp
            // bold is the smallest white passes at on D.Blue, and "to reveal" was 12sp, so it
            // is a caption under the grid now instead.
            if (strokes == 0) {
                Text("Scratch", color = Color.White, style = Type.heading,
                    modifier = Modifier.graphicsLayer { alpha = cover })
            }
        }
    }
}

/** What is under the foil. Drawn always, so scratching uncovers it rather than swapping it in. */
@Composable
private fun RevealedFace(row: Rewards.Row, reversed: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row {
            Text(
                // The sign comes from Money, so a reversed card reads with the true minus
                // (U+2212) and a rewarded one carries its +, the same as every other amount.
                skr(if (reversed) -row.skr else row.skr, sign = true),
                // Money IN is the success ink (Rewards.tsx:194), the same green every
                // other money-in figure in the app uses. It was blue, which says "link".
                color = if (reversed) D.Quiet else D.SuccessInk,
                style = TitleAmount,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.width(Space.tight))
            Text("SKR", color = if (reversed) D.Quiet else D.SuccessInk, style = Type.caption,
                modifier = Modifier.alignByBaseline())
        }
        Spacer(Modifier.height(8.dp))
        Text(Rewards.nameOf(row.kind), color = D.Black, style = Type.body,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.tight))
        // A reward taken back says why, in the place the reward was. It is not deleted:
        // somebody who saw 300 SKR and later sees 0 deserves the reason on the same screen.
        // Quiet, not red: red is only a transaction the chain refused, and a counterparty who
        // took their money back is not that.
        Text(
            when {
                reversed -> row.reason?.substringAfter(": ") ?: "taken back"
                row.state == "locked" -> "locked"
                else -> "yours"
            },
            color = D.Quiet, style = Type.caption, textAlign = TextAlign.Center,
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
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text("This week", color = D.Black, style = Type.heading,
                modifier = Modifier.weight(1f).alignByBaseline())
            Spacer(Modifier.width(Space.inline))
            Text("${s.weekPayments} of ${s.weekAllows}", color = D.Quiet,
                style = Type.caption.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.alignByBaseline())
        }
        Spacer(Modifier.height(Space.lead))
        // Fills on a graphic, not text: the pips are the one place D.Blue and D.Line are the
        // thing itself rather than the colour of something written.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(s.weekAllows) { i ->
                Box(
                    // rounded-full on an 8dp bar: a pill, whatever the week allows.
                    Modifier.weight(1f).height(8.dp)
                        .background(if (i < s.weekPayments) D.Blue else D.Line, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (s.weekPayments >= s.streakNeeded) "Streak complete — ${skr(s.streak)} SKR earned"
            else "${s.streakNeeded - s.weekPayments} more this week for the ${skr(s.streak)} SKR streak",
            color = D.Black, style = Type.body,
        )
        Spacer(Modifier.height(Space.tight))
        // The month's cap is not in the sandbox, which has no server to know one. It is the
        // ceiling the week's pool is spent against, and somebody who hits it earns nothing
        // from a payment that satisfied every other rule.
        Row {
            Text("This month", color = D.Quiet, style = Type.caption,
                modifier = Modifier.weight(1f).alignByBaseline())
            Spacer(Modifier.width(Space.inline))
            Text("${skr(s.monthEarned)} of ${skrWithCode(s.monthCap)}",
                color = D.Black, style = CaptionAmount, modifier = Modifier.alignByBaseline())
        }
        Spacer(Modifier.height(Space.tight))
        // Plain ink words when the pool is empty, not red: nothing was refused by the chain,
        // there is simply nothing left to pay out this week.
        if (s.poolLeft > 0) {
            Row {
                Text("Left in this week's pool", color = D.Quiet, style = Type.caption,
                    modifier = Modifier.weight(1f).alignByBaseline())
                Spacer(Modifier.width(Space.inline))
                Text(skrWithCode(s.poolLeft), color = D.Black,
                    style = CaptionAmount, modifier = Modifier.alignByBaseline())
            }
        } else {
            Text("This week's pool is empty — nothing earns until it refills",
                color = D.Black, style = Type.caption)
        }
    }
}

/** Which mark a door carries. The sandbox's `Door` takes the same two. */
private enum class DoorMark { Ticket, Rings }

/**
 * A way out of this screen to one that earns.
 *
 * One composable for both doors, not one each: they differ only in the mark and where they go,
 * and two copies of a row shape are two rows that drift apart. The mark, the title with what is
 * behind it, then a chevron — no card and no grey body, so [Space.row] and a [RewardsHairline]
 * are the only things separating one door from the next. The first in a group takes no top
 * padding, because the heading above it has already left the gap.
 *
 * The trailing chevron replaced a word ("Open" / "Invite"): it is an affordance rather than
 * information, and a second piece of blue text on the row competed with the title.
 */
@Composable
private fun EarnDoor(
    mark: DoorMark,
    title: String,
    detail: String,
    first: Boolean,
    onOpen: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.then(PressScale(interaction)).fillMaxWidth().heightIn(min = 48.dp)
            .clickable(interaction, indication = null, onClick = onOpen)
            .padding(top = if (first) 0.dp else Space.row, bottom = Space.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DoorMarkCanvas(mark)
        Spacer(Modifier.width(Space.inline))
        Column(Modifier.weight(1f)) {
            Text(title, color = D.Black, style = Type.body)
            Spacer(Modifier.height(Space.tight))
            Text(detail, color = D.Quiet, style = Type.caption)
        }
        Spacer(Modifier.width(Space.inline))
        Chevron()
    }
}

/**
 * A door's mark, at the 32dp of the Earn tiles.
 *
 * `MarkCanvas` itself, not a redrawing of it. This was a byte-for-byte copy of its `Ticket` and
 * `Rings` branches, kept only because `Mark`/`MarkCanvas` were private to MainActivity.kt. Two
 * copies of one drawing is how the ticket ended up shipping at `.26/.48/.12` here and
 * `.22/.56/.14` there — the drift PARITY.md keeps a record of. The notch stays white because
 * this row sits on the page, not on a poster.
 */
@Composable
private fun DoorMarkCanvas(mark: DoorMark) {
    MarkCanvas(
        when (mark) {
            DoorMark.Ticket -> Mark.Ticket
            DoorMark.Rings -> Mark.Rings
        },
        32.dp,
    )
}

/**
 * The row chevron. Quiet, because it is an affordance and not information, and unlabelled: the
 * row's own title is what a screen reader should read.
 *
 * 9 × 15, the ratio ui/PARITY.md holds the sandbox's `chevron` mark to. It means "forward", so
 * it points the other way in a right-to-left layout.
 *
 * A third private copy of this drawing in the app (OffersScreen.kt and ProfileDrawer.kt have
 * the other two, at 9×15 and 8×13) — it belongs in Design.kt, which is owned elsewhere today.
 */


/** How much of the card must go before the rest wipes itself. *//**
 * The two figure-face steps this screen sets SKR in.
 *
 * `<Amount>` in the sandbox chooses the FACE and leaves size, weight and colour to the caller
 * (ui/PROJECT.md §3), so a caption-sized amount is the caption step wearing Geist. These are
 * not new steps in the scale — the scale is the seven sizes, and these are two of them in
 * another face. `skr` and `TextStyle.figure()` now live in Design.kt; this screen had its own
 * copies of both, inside an object, only because a stub file had taken the shared names.
 */
private val CaptionAmount = Type.caption.figure()
private val TitleAmount = Type.title.figure()

/** The figure with its code after it, joined by the no-break space [Money] uses. */
private fun skrWithCode(n: Int) = Money.token(BigDecimal(n), "SKR")


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
