package so.duffle.app

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Referrals: your code, what it pays, and who has used it.
 *
 * ### The one number this screen must not invent
 *
 * Every app this is modelled on writes "Invite & Win Assured ₹80" against each row. Ours does
 * not, and the difference is not squeamishness: the inviter is paid on the invitee's **first
 * rewarded payment**, not when they join, because a wallet is free and "somebody signed up
 * with my code" costs an attacker nothing. "Assured" would be false on the only question that
 * matters. So the card states the rule and the condition together, and the list below shows
 * which invitations have actually turned into a payment.
 *
 * ### Why the list is people you invited, not people you could invite
 *
 * The obvious version reads the phone's address book. That needs READ_CONTACTS — a directory
 * of everybody you know, requested so a promo screen can look busier. [Contacts] is no help
 * either: everyone in it has already tapped with you, so they are already here and inviting
 * them is meaningless. What is worth showing is the half that is true and actually changes:
 * who took the code, and who has paid.
 */
@Composable
internal fun ReferralsTab(
    connected: String?,
    onShare: (String) -> Unit,
    /** The name to put in the message — the same one the rest of the app shows for you. */
    myName: String,
) {
    var summary by remember { mutableStateOf<Referrals.Summary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var howOpen by rememberSaveable { mutableStateOf(false) }
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
            Spacer(Modifier.height(40.dp))
            Text("Connect a wallet", color = D.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Your code belongs to a wallet, so there is nothing to show until one is connected.",
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
                Text("Your code is made by the server, so this screen can't show one right now.",
                    color = D.Quiet, fontSize = 13.sp)
            }
            return@Column
        }

        HowToRefer(howOpen, s) { howOpen = !howOpen }
        Spacer(Modifier.height(14.dp))
        ReferHero(s) { onShare(Referrals.message(myName, s.code, s.theirReward)) }
        Spacer(Modifier.height(12.dp))
        CodeRail(
            code = s.code,
            onCopy = { clipboard.setText(AnnotatedString(s.code)) },
            onShare = { onShare(Referrals.message(myName, s.code, s.theirReward)) },
        )

        // What the code has actually done. Only once it has done something: a row of zeroes
        // above an empty list says the same thing twice.
        if (s.invited > 0) {
            Spacer(Modifier.height(12.dp))
            Progress(s)
        }

        Spacer(Modifier.height(24.dp))
        Text("People you invited", color = D.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            if (s.invited == 0) "Nobody yet."
            else "They earn on their first tap. You earn when it lands.",
            color = D.Quiet, fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))

        if (s.people.isEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(D.Fill)
                .padding(20.dp)) {
                Text("Share your code to start", color = D.Black, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Anyone who uses it shows up here, with whether their first tap has landed.",
                    color = D.Quiet, fontSize = 13.sp)
            }
        } else {
            // Only worth a search box once the list is long enough to need one.
            if (s.people.size > 5) {
                SearchBox(query) { query = it }
                Spacer(Modifier.height(10.dp))
            }
            val shown = s.people.filter {
                query.isBlank() || it.address.contains(query.trim(), ignoreCase = true)
            }
            if (shown.isEmpty()) {
                Text("Nobody matching that.", color = D.Quiet, fontSize = 13.sp)
            }
            shown.forEach { InvitedRow(it, s.reward) }
        }

        // The other half of the loop. There is no deep link to carry a code — duffle.so does
        // not resolve — so somebody arriving with one needs a door, and this is it.
        Spacer(Modifier.height(24.dp))
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
        Spacer(Modifier.height(28.dp))
    }
}

/** The three steps, folded away until asked for. */
@Composable
private fun HowToRefer(open: Boolean, s: Referrals.Summary, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column {
        Box(
            Modifier.then(PressScale(interaction)).clip(RoundedCornerShape(999.dp))
                .background(D.Fill).clickable(interaction, indication = null, onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(if (open) "How to refer  ▴" else "How to refer  ▾", color = D.Blue,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(D.Fill).padding(16.dp)) {
                listOf(
                    "1" to "Send them your code.",
                    "2" to "They enter it in AirPay before their first payment.",
                    "3" to "They pay someone by tapping. They earn ${s.theirReward} SKR, you earn ${s.reward}.",
                ).forEach { (n, line) ->
                    Row(Modifier.padding(bottom = 10.dp)) {
                        Text(n, color = D.Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(20.dp))
                        Text(line, color = D.Black, fontSize = 13.sp)
                    }
                }
                // The condition, said where somebody reading the rules will look for it rather
                // than in small print under the button.
                Text(
                    "Nothing is paid for joining. A wallet costs nothing to make, so the reward " +
                        "waits until a real payment has happened.",
                    color = D.Quiet, fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * The promo card.
 *
 * Loud enough to be the thing you see, and it still refuses to say "assured" — the line under
 * the button is the condition, not small print, because it is the whole difference between
 * this offer and one that can be farmed.
 */
@Composable
private fun ReferHero(s: Referrals.Summary, onShare: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(D.Blue, Color(0xFF4DA9FF)))),
    ) {
        // Two rings and a scatter of coins, drawn from the app's own vocabulary rather than
        // stock confetti — the same marks the Referrals tile and the reward cards use.
        Canvas(Modifier.fillMaxSize()) {
            val r = size.height * .22f
            drawCircle(Color.White.copy(alpha = .13f), r, Offset(size.width * .86f, size.height * .18f),
                style = Stroke(size.height * .05f))
            drawCircle(Color.White.copy(alpha = .20f), r, Offset(size.width * .97f, size.height * .30f),
                style = Stroke(size.height * .05f))
            listOf(.08f to .78f, .20f to .92f, .70f to .88f).forEach { (fx, fy) ->
                drawCircle(Color.White.copy(alpha = .12f), size.height * .07f,
                    Offset(size.width * fx, size.height * fy))
            }
        }
        Column(Modifier.padding(22.dp)) {
            Text("BRING A FRIEND", color = Color.White.copy(alpha = .75f), fontSize = 11.sp,
                letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${s.reward}", color = Color.White, fontSize = 42.sp,
                    fontWeight = FontWeight.Bold, fontFamily = Display)
                Spacer(Modifier.width(6.dp))
                Text("SKR", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 7.dp))
            }
            Text("for you, every friend who taps", color = Color.White, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text("They get ${s.theirReward} SKR on their first tap.",
                color = Color.White.copy(alpha = .85f), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.then(PressScale(interaction)).clip(RoundedCornerShape(14.dp))
                    .background(Color.White).clickable(interaction, indication = null, onClick = onShare)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text("Share your code  →", color = D.Blue, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text("Paid when their first tap lands — not when they join.",
                color = Color.White.copy(alpha = .75f), fontSize = 11.5.sp)
        }
    }
}

/** The code itself, on the one dark rail in the app, because it is the thing to be read off. */
@Composable
private fun CodeRail(code: String, onCopy: () -> Unit, onShare: () -> Unit) {
    val share = remember { MutableInteractionSource() }
    val copy = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(D.Black)
            .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).then(PressScale(copy))
                .clickable(copy, indication = null, onClick = onCopy),
        ) {
            Text("Your code · tap to copy", color = D.White.copy(alpha = .6f), fontSize = 11.sp)
            Spacer(Modifier.height(2.dp))
            // Tabular figures and wide tracking: this is read aloud and typed by hand.
            Text(code, color = D.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp, style = Figures)
        }
        Box(
            Modifier.then(PressScale(share)).clip(RoundedCornerShape(12.dp)).background(D.Blue)
                .clickable(share, indication = null, onClick = onShare)
                .padding(horizontal = 18.dp, vertical = 11.dp),
        ) {
            Text("Share", color = D.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Invited and paid, kept apart — one is a hope and the other is money. */
@Composable
private fun Progress(s: Referrals.Summary) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(D.Fill).padding(16.dp)) {
        listOf(
            "${s.invited}" to "invited",
            "${s.paid}" to "have paid",
            "${s.earned}" to "SKR earned",
        ).forEachIndexed { i, (n, label) ->
            Column(Modifier.weight(1f)) {
                Text(n, color = if (i == 2) D.Blue else D.Black, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, style = Figures)
                Text(label, color = D.Quiet, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun InvitedRow(p: Referrals.Person, reward: Int) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(14.dp))
            .background(D.Fill).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).background(
                if (p.paid) D.Blue.copy(alpha = .14f) else D.Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(p.address.take(1), color = if (p.paid) D.Blue else D.Quiet, fontSize = 15.sp,
                fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // The address, not a name: nobody named them, and inventing one here would be the
            // only unverified thing on the screen.
            Text(p.short, color = D.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                style = Figures)
            Text(
                if (p.paid) "Joined ${ago(p.at)} · their first tap landed"
                else "Joined ${ago(p.at)} · waiting for their first tap",
                color = D.Quiet, fontSize = 12.sp,
            )
        }
        Text(if (p.paid) "+$reward" else "—", color = if (p.paid) D.Blue else D.Quiet,
            fontSize = 14.sp, fontWeight = FontWeight.Bold, style = Figures)
    }
}

@Composable
private fun SearchBox(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Search by wallet", color = D.Quiet, fontSize = 14.sp) },
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
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(D.Fill).padding(16.dp)) {
        if (claimed) {
            Text("Code accepted", color = D.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Your first tap will earn you — and whoever invited you.",
                color = D.Quiet, fontSize = 13.sp)
            return@Column
        }
        val interaction = remember { MutableInteractionSource() }
        Row(
            Modifier.then(PressScale(interaction))
                .clickable(interaction, indication = null, onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Have a code?", color = D.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Enter it before your first payment", color = D.Quiet, fontSize = 12.sp)
            }
            Text(if (open) "▴" else "▾", color = D.Blue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 12.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = onCode,
                    placeholder = { Text("8 characters", color = D.Quiet, fontSize = 14.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    // Codes carry no lowercase and no punctuation, so the keyboard should not
                    // offer either.
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = D.Blue, unfocusedBorderColor = D.Line,
                        focusedTextColor = D.Black, unfocusedTextColor = D.Black,
                        cursorColor = D.Blue,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                problem?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = D.Danger, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Use this code", onClaim, enabled = code.length == 8 && !claiming,
                    loading = claiming)
            }
        }
    }
}
