package so.duffle.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
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
 * Every SKR figure on this screen, written the one way the app writes numbers.
 *
 * `Int` in, because the server sends whole SKR and the unit has no decimals — but it still goes
 * through [Money], because the phone's locale is what groups the digits. `"$n"` reads "1000"
 * everywhere; this reads "1,000" in the US and "1.000" in Germany. The screen used to
 * interpolate the raw int in five places.
*
 * Lives in Design.kt now, shared with RewardsScreen, which had its own copy.
 */

/**
 * Referrals: your code, what it pays, and who has used it.
 *
 * ### The one number this screen must not invent
 *
 * Every app this is modelled on writes "Invite & Win Assured ₹80" against each row. Ours does
 * not, and the difference is not squeamishness: the inviter is paid on the invitee's **first
 * rewarded payment**, not when they join, because a wallet is free and "somebody signed up
 * with my code" costs an attacker nothing. "Assured" would be false on the only question that
 * matters. So the condition sits directly under the card — on white, in body text, where it can
 * actually be read — and the list below shows which invitations have turned into a payment.
 *
 * ### Why the list is people you invited, not people you could invite
 *
 * The obvious version reads the phone's address book. That needs READ_CONTACTS — a directory
 * of everybody you know, requested so a promo screen can look busier. [Contacts] is no help
 * either: everyone in it has already tapped with you, so they are already here and inviting
 * them is meaningless. What is worth showing is the half that is true and actually changes:
 * who took the code, and who has paid.
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
    var entering by rememberSaveable { mutableStateOf(false) }
    var codeIn by rememberSaveable { mutableStateOf("") }
    var claimProblem by remember { mutableStateOf<String?>(null) }
    var claimed by remember { mutableStateOf(false) }
    var claiming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(connected, refresh) {
        loading = true
        summary = connected?.let { Referrals.summary(it) }
        loading = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (connected == null) {
            Blank(
                "Connect a wallet",
                "Your code belongs to a wallet, so there is nothing to show until one is connected.",
            )
            return@Column
        }
        val s = summary
        if (s == null) {
            Blank(
                if (loading) "Checking…" else "Can't reach the server",
                if (loading) null
                else "Your code is made by the server, so this screen can't show one right now.",
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
        Text("Paid when their first tap lands — not when they join.",
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

        // The other half of the loop. There is no deep link to carry a code — duffle.so does
        // not resolve — so somebody arriving with one needs a door, and this is it.
        Spacer(Modifier.height(Space.section))
        HaveACode(
            open = entering,
            code = codeIn,
            claiming = claiming,
            claimed = claimed,
            problem = claimProblem,
            onToggle = { entering = !entering; claimProblem = null },
            onCode = { codeIn = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8); claimProblem = null },
            onClaim = {
                val me = connected
                claiming = true
                scope.launch {
                    val why = Referrals.claim(codeIn, me)
                    claiming = false
                    if (why == null) { claimed = true; refresh++ } else claimProblem = Referrals.why(why)
                }
            },
        )
        Spacer(Modifier.height(Space.section))
    }
}

/** What the screen says when there is nothing to show yet. Two lines, no box. */
@Composable
private fun Blank(title: String, detail: String?) {
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
            Text("—", color = D.Quiet, style = Type.body,
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

/** Entering somebody else's code — the receiving end of every share this screen sends. */
@Composable
private fun HaveACode(
    open: Boolean, code: String, claiming: Boolean, claimed: Boolean, problem: String?,
    onToggle: () -> Unit, onCode: (String) -> Unit, onClaim: () -> Unit,
) {
    // The grey panel is replaced by a rule and the space above it: this is a new section, not a
    // card parked at the bottom of the screen.
    Column(Modifier.fillMaxWidth()) {
        Divider()
        Spacer(Modifier.height(Space.row))
        if (claimed) {
            Text("Code accepted", color = D.Black, style = Type.body)
            Spacer(Modifier.height(Space.tight))
            Text("Your first tap will earn you — and whoever invited you.",
                color = D.Quiet, style = Type.caption)
            return@Column
        }
        val interaction = remember { MutableInteractionSource() }
        Row(
            Modifier.then(PressScale(interaction)).fillMaxWidth().heightIn(min = 48.dp)
                .clickable(interaction, indication = null, onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Have a code?", color = D.Black, style = Type.body)
                Spacer(Modifier.height(Space.tight))
                Text("Enter it before your first payment", color = D.Quiet, style = Type.caption)
            }
            Spacer(Modifier.width(Space.inline))
            Chevron(turn = if (open) -90f else 90f)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = Space.lead)) {
                CodeField(code, onCode, refused = problem != null)
                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    // Ink, not red. None of these refusals is the chain refusing a transaction —
                    // no money was in flight — and red is reserved for the one that is.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HiIcon(Hi.Alert, D.Black, 16.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(it, color = D.Black, style = Type.caption)
                    }
                }
                Spacer(Modifier.height(Space.lead))
                PrimaryButton("Use this code", onClaim, enabled = code.length == 8 && !claiming,
                    loading = claiming)
            }
        }
    }
}

/**
 * The row chevron: pointing down while the door is shut, up once it is open.
 *
 * Quiet, because it is an affordance and not information — it was a blue-ink `▾` glyph, which
 * read as a link and took whatever shape the system font happened to have for it.
 *
 * The geometry is `Chevron` in OffersScreen.kt — two round-capped lines across a 9:15 box,
 * which on the 24-unit grid is x 4.8 → 19.2 at a 3.744 stroke. It is redrawn here because that
 * one is private to its own file; it belongs in Design.kt beside the other shared bits, and
 * this port was not allowed to touch that file.
 *
 * Rotated rather than mirrored, so RTL needs nothing: up and down are the same in every script.
 */

/**
 * The field somebody types an invite code into.
 *
 * Not a Material `OutlinedTextField`: that one arrives with its own 56dp height, its own label
 * gutter and a 1dp border, and none of those are the numbers this design settled on — 52dp
 * minimum, a 14dp radius, 16/12 padding and a 1.5dp inset ring. So the box is drawn here and
 * `BasicTextField` puts the text inside it. `heightIn`, not `height`, so the field grows with
 * the font scale instead of clipping the code it holds.
 *
 * The ring goes to ink when the code was refused. **Not red:** none of these refusals is the
 * chain refusing a transaction — no money was ever in flight — and red is reserved for the one
 * that is.
 */
@Composable
private fun CodeField(code: String, onCode: (String) -> Unit, refused: Boolean) {
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = code,
        onValueChange = onCode,
        // A code is an identifier, so it is typed and shown in the identifier face — at the body
        // step, because it is what this control is for and not a caption beside something else.
        textStyle = MonoBody.copy(color = D.Black),
        singleLine = true,
        cursorBrush = SolidColor(D.Blue),
        // Codes carry no lowercase and no punctuation, so the keyboard should not offer either.
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { field ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape)
                    .border(1.5.dp, if (refused) D.Black else D.Blue, shape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                // The hint says how long the code is, which is the one thing somebody typing
                // one from a message needs to know.
                if (code.isEmpty()) Text("8 characters", color = D.Quiet, style = MonoBody)
                field()
            }
        },
    )
}

/**
 * The explainer: three steps, and the one condition that makes them honest.
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
             "Before their first payment — a code is an invitation, not a receipt.",
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
