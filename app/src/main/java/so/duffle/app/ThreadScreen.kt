package so.duffle.app

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal

/**
 * §8 — one person's thread: payments, asks, rewards and messages, oldest first, and the full
 * page behind any one of them.
 *
 * ui/PORT-2026-10-02.md §8.1–§8.4, against `ui/components/sections/Thread.tsx`.
 *
 * What the rebuild changed, and why each change is not cosmetic:
 *
 *  - **Every item is a card with a status dot and a word**, where before it was a coloured
 *    bubble with the state said underneath in one grey line. The dot's SHAPE carries the
 *    state — tick, clock, cross, dash — so the eight endings are told apart without colour.
 *  - **A card opens its own page** (§8.4), which is where the facts live: both parties, the
 *    wallet, the signature, the network. The bubble had nowhere to put any of that, so none of
 *    it was anywhere.
 *  - **A declined ask is red** (§10): `D.Danger` fill, `D.DangerInk` words, and a CROSS rather
 *    than a dash. This is one of the three sanctioned reds. Expired and "nothing was sent" stay
 *    neutral grey — a person saying no is a refusal, a request simply lapsing is not.
 *  - **The message bar replaces "Pay or ask"** (§8.3): the field is always there and the one
 *    button at its end is Pay until something is typed, then Send.
 */

// ───────────────────────────────────────────────────────── §8.1 one row of the conversation

/** What a row IS. A message is the only one with no amount and no state. */
private enum class ThreadKind { PAY, ASK, REWARD, MSG }

/**
 * Where a row stands — §8.1's eight statuses, plus the one this app actually produces that
 * §8.1 has no name for.
 *
 * **Three of §8.1's eight cannot occur today, and are here because the table asks for them:**
 *
 *  - `CONFIRMING` — [Inbox] and [Watch] only ever learn "paid", and they learn it from a
 *    signature. There is no half-settled row in either store to read, the same gap
 *    `History.State.CONFIRMING` documents. It is the one stage that TICKS, so when a source
 *    appears the clock starts moving with no further work.
 *  - `EXPIRED` — [Watch] is explicit that a request has no expiry: its reference is valid for
 *    as long as it is unused. Nothing times out, so nothing is expired.
 *  - `FAILED` — a transaction the chain refused is reported on the tap sheet and is not written
 *    to either store, so the thread never sees one.
 *
 * `DROPPED` is the reverse case: a withdrawn request is real, frequent and has no row in
 * §8.1's table. It takes the neutral dash, because nothing was sent and nothing was going to
 * be — the same reading §10 gives Expired — and says who dropped it in its own word.
 */
private enum class ThreadStage { PAID, CONFIRMING, WAITING, LATER, DECLINED, EXPIRED, FAILED, DROPPED }

/** Which mark a stage's disc carries. The shape, not the colour, is what is read. */
private enum class ThreadMark { TICK, CLOCK, CROSS, DASH }

/** A stage's fill, its text ink, and its mark. A fill is never text — hence the pair (§10). */
private data class ThreadLook(
    val fill: Color,
    val ink: Color,
    val mark: ThreadMark,
    /** Only `CONFIRMING` moves: see [ThreadRow] and `Thread.tsx`, which holds the rest still. */
    val ticking: Boolean = false,
)

private fun lookOf(stage: ThreadStage): ThreadLook = when (stage) {
    ThreadStage.PAID -> ThreadLook(D.Success, D.SuccessInk, ThreadMark.TICK)
    ThreadStage.CONFIRMING -> ThreadLook(D.Blue, D.BlueInk, ThreadMark.CLOCK, ticking = true)
    ThreadStage.WAITING -> ThreadLook(D.Blue, D.BlueInk, ThreadMark.CLOCK)
    ThreadStage.LATER -> ThreadLook(D.Blue, D.BlueInk, ThreadMark.CLOCK)
    // The third sanctioned red (§10). A cross, not a dash: somebody answered.
    ThreadStage.DECLINED -> ThreadLook(D.Danger, D.DangerInk, ThreadMark.CROSS)
    ThreadStage.EXPIRED -> ThreadLook(D.Neutral, D.Quiet, ThreadMark.DASH)
    ThreadStage.FAILED -> ThreadLook(D.Danger, D.DangerInk, ThreadMark.CROSS)
    ThreadStage.DROPPED -> ThreadLook(D.Neutral, D.Quiet, ThreadMark.DASH)
}

/**
 * One row, whatever it came from.
 *
 * A flat shape rather than a sealed hierarchy over [Inbox.Item] / [Watch.Entry] /
 * [Messages.Note] / [Rewards.Row], because the thread asks each row the same six questions and
 * the four sources answer them in four different vocabularies. [item] and [entry] are kept so
 * the buttons on a row can act on the real thing.
 *
 * [mine] is **who did what the card names**, not who holds the money: a payment of theirs that
 * landed on us is "Payment to you" and sits on the start side, exactly as `Thread.tsx` files it.
 */
private data class ThreadRow(
    /** Stable across refreshes, because it is the key the open page is held by in MainActivity. */
    val id: String,
    val kind: ThreadKind,
    val mine: Boolean,
    val stage: ThreadStage,
    val at: Long,
    /** A decimal string, as it came off the wire or the keypad. Never pre-formatted. */
    val amount: String = "",
    val symbol: String = "",
    /** A message's words, and nothing else carries them. */
    val text: String? = null,
    val signature: String? = null,
    /** SKR this row earned, when the row IS the reward. See [threadRows]. */
    val earned: Int? = null,
    val item: Inbox.Item? = null,
    val entry: Watch.Entry? = null,
) {
    val look: ThreadLook get() = lookOf(stage)
    /** True once something is on the chain. Decides the transaction fact and the explorer. */
    val settled: Boolean get() = signature != null
}

/**
 * Everything that has passed between this phone and one person, oldest first.
 *
 * Deliberately **not** `remember`ed: `toPay`, `owed` and `notes` are `SnapshotStateList`s whose
 * identity never changes, so a key on them would hold the first list this screen ever saw while
 * a payment landed behind it. The lists are tens of rows long; rebuilding is cheaper than the
 * staleness bug.
 */
private fun threadRows(
    who: Contact,
    toPay: List<Inbox.Item>,
    owed: List<Watch.Entry>,
    notes: List<Messages.Note>,
    rewards: List<Rewards.Row>,
): List<ThreadRow> {
    val out = mutableListOf<ThreadRow>()

    // Their asks. Once paid the row is no longer a question but a payment we made, so it
    // changes KIND as well as stage — otherwise the thread would read "they asked you · Paid".
    for (i in toPay.filter { it.request.recipient == who.address }) {
        out += ThreadRow(
            id = "i:${i.request.reference}",
            kind = if (i.paid) ThreadKind.PAY else ThreadKind.ASK,
            mine = i.paid,
            stage = when {
                i.paid -> ThreadStage.PAID
                i.declined -> ThreadStage.DECLINED
                i.withdrawn -> ThreadStage.DROPPED
                else -> ThreadStage.WAITING
            },
            at = i.paidAt ?: i.readAt,
            amount = i.request.amount,
            symbol = i.request.symbol,
            signature = i.signature,
            item = i,
        )
    }

    // Our asks, settled by the chain.
    for (e in owed.filter { it.peer == who.address }) {
        out += ThreadRow(
            id = "o:${e.reference}",
            kind = if (e.paid) ThreadKind.PAY else ThreadKind.ASK,
            mine = !e.paid,
            stage = when {
                e.paid -> ThreadStage.PAID
                e.rejected -> ThreadStage.DECLINED
                e.withdrawn -> ThreadStage.DROPPED
                e.pending -> ThreadStage.LATER
                else -> ThreadStage.WAITING
            },
            at = e.paidAt ?: e.withdrawnAt ?: e.createdAt,
            amount = e.amount,
            symbol = e.symbol,
            signature = e.signature,
            entry = e,
        )
    }

    for (n in notes.filter { it.peer == who.address }) {
        out += ThreadRow(
            // The server's row id for anything received; our own are keyed by when they were
            // written, which is unique per message because nothing writes two in a millisecond.
            id = "n:${if (n.id > 0) n.id else n.at}",
            kind = ThreadKind.MSG, mine = n.mine, stage = ThreadStage.PAID, at = n.at, text = n.body,
        )
    }

    // A reward is filed under the person who earned it for us — `counterparty` is an address,
    // so this needs no matching by amount or by time.
    for (r in rewards.filter { it.counterparty == who.address }) {
        out += ThreadRow(
            id = "r:${r.id}", kind = ThreadKind.REWARD, mine = true, stage = ThreadStage.PAID,
            at = r.at, amount = r.skr.toString(), symbol = "SKR", earned = r.skr,
        )
    }

    return out.sortedBy { it.at }
}

// ───────────────────────────────────────────────────────────────────────────── words

/** "aashu.skr" → "aashu". §8.1's card titles and §8.4's outcome lines all use the first name. */
/**
 * The short form used mid-sentence: "Payment to Aashu", not "Payment to Aashu Kumar".
 *
 * [PLACEHOLDERS] are left whole. They are not names — they are what the app writes down when
 * nobody told it a name — and cutting one to its first word produced "Payment to Scanned" on a
 * real screen. A label the app wrote itself is either said in full or not treated as a name.
 */
private fun firstName(name: String): String =
    if (name in PLACEHOLDERS) name
    else name.split(' ', '.').firstOrNull { it.isNotBlank() } ?: name

/** Labels this app assigns when a payload carried none — see `ScanActivity`. */
private val PLACEHOLDERS = setOf("Scanned code")

/** §8.1: "Payment to Aashu" · "Payment to you" · "You asked Aashu" · "Aashu asked you". */
private fun titleOf(row: ThreadRow, them: String): String = when (row.kind) {
    ThreadKind.PAY -> if (row.mine) "Payment to $them" else "Payment to you"
    ThreadKind.ASK -> if (row.mine) "You asked $them" else "$them asked you"
    ThreadKind.REWARD -> "You earned a reward"
    ThreadKind.MSG -> ""
}

/** §8.1's status word. Waiting and dropped read differently from each side. */
private fun wordOf(row: ThreadRow, them: String): String = when (row.stage) {
    ThreadStage.PAID -> "Paid"
    ThreadStage.CONFIRMING -> "Confirming"
    ThreadStage.WAITING -> if (row.mine) "Requested" else "Asked you"
    ThreadStage.LATER -> "Will pay later"
    ThreadStage.DECLINED -> "Declined"
    ThreadStage.EXPIRED -> "Expired"
    ThreadStage.FAILED -> "Failed"
    ThreadStage.DROPPED -> if (row.mine) "Taken back" else "$them took it back"
}

/**
 * §8.4's one line under the amount: where it stands, and — when no money moved — that it did not.
 *
 * No dot beside it and nothing under it: the 64 disc above already carries the state's shape
 * and colour, and a second copy of the same fact is what pushed the amount off the fold.
 */
private fun outcomeOf(row: ThreadRow, them: String): String = when (row.stage) {
    ThreadStage.PAID -> if (row.kind == ThreadKind.REWARD) "Added to your rewards" else "Paid successfully"
    ThreadStage.CONFIRMING -> "Confirming on Solana"
    ThreadStage.WAITING -> if (row.mine) "Waiting for $them" else "Waiting for you to pay"
    ThreadStage.LATER -> "$them will pay later"
    ThreadStage.DECLINED -> "Declined · nothing was paid"
    ThreadStage.EXPIRED -> "Expired · nothing was paid"
    ThreadStage.FAILED -> "Failed · nothing left your wallet"
    ThreadStage.DROPPED ->
        if (row.mine) "You took it back · nothing was paid" else "$them took it back · nothing was paid"
}

/**
 * An amount and its token code through [Money] — locale grouping, cut never rounded up, dust
 * said as "<", and a no-break space before the code.
 *
 * A string that is not a decimal at all is shown as it came rather than swallowed: this screen
 * is about money, so it never has nothing to say about it.
 */
private fun money(amount: String, symbol: String): String =
    runCatching { Money.token(BigDecimal(amount), symbol) }.getOrDefault("$amount $symbol")

/** Just the figure, for §8.4's `display` step, which sets the code beside it at its own size. */
private fun moneyFigure(amount: String, symbol: String): String =
    runCatching { Money.tokenAmount(BigDecimal(amount), symbol) }.getOrDefault(amount)

/**
 * The day a row is filed under — §8.1's divider.
 *
 * "Today" and a date, which is what the sandbox's dividers hold. A "Yesterday" step is NOT
 * invented here: the sandbox does not state one, and the date is never wrong.
 */
private val DayDate by lazy { java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()) }
private val DayDateYear by lazy { java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()) }

private fun dayOf(at: Long, now: Long = System.currentTimeMillis()): String {
    val cal = { t: Long -> java.util.Calendar.getInstance().apply { timeInMillis = t } }
    val a = cal(at)
    val b = cal(now)
    val sameYear = a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
    val sameDay = sameYear && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> "Today"
        sameYear -> DayDate.format(java.util.Date(at))
        else -> DayDateYear.format(java.util.Date(at))
    }
}

/** §8.4's "28 Sep 2026, 7:32 pm". The marker is lowercased, which is a no-op where it is not Latin. */
private val FullDate by lazy { java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()) }
private val FullTime by lazy { java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()) }

private fun fullWhen(at: Long): String {
    val d = java.util.Date(at)
    return "${FullDate.format(d)}, ${FullTime.format(d).lowercase(java.util.Locale.getDefault())}"
}

// ───────────────────────────────────────────────────────────────────── measurements

/** §8.1: the card's width, corner and inner padding. */
private val CARD_W = 260.dp
private val CARD_R = 20.dp
private val CARD_PAD = 16.dp

/** §8.3: the bar's field and button, and therefore how much room the list leaves under itself. */
private val BAR_H = 48.dp
private val BAR_BUTTON_W = 104.dp
private val BAR_TOP = 12.dp
private val BAR_BOTTOM = 16.dp
private val BAR_SPACE = BAR_H + BAR_TOP + BAR_BOTTOM

/**
 * The reward tray's ground.
 *
 * The ARTWORK's own colour, under the tinted-card exception in ui/PROJECT.md §3 — the same
 * exception `RewardsScreen.kt`'s hero and the three illustrated cards on Home sit under. It is
 * deliberately not a `Palette.kt` token: it describes one picture's background, not a state, a
 * tier or a severity, which is what the no-tints rule exists to stop. Do not reuse it.
 */
private val REWARD_TRAY = Color(0xFFECF3FC)

// ───────────────────────────────────────────────────────────────── §8.1 the status disc

/**
 * A status as a disc: the stage's fill, a white mark at the disc weight.
 *
 * The clock is drawn at 80% of the disc and every other mark at 70% — §2's rule, because the
 * clock's face fills its box where a tick or a cross does not.
 *
 * **Only `CONFIRMING` ticks.** Everything else holds still, which is `Thread.tsx`'s
 * `clock-still` rule: a hand moving on a row says "this is changing as you look at it", and of
 * the clock stages only confirming is. A request that has been waiting since Tuesday is not.
 */
@Composable
private fun StatusDot(stage: ThreadStage, size: Dp, modifier: Modifier = Modifier) {
    val look = lookOf(stage)
    Box(modifier.size(size).background(look.fill, CircleShape), contentAlignment = Alignment.Center) {
        val mark = (size.value * 0.7f).dp
        when (look.mark) {
            ThreadMark.TICK -> HiIcon(Hi.Tick, D.White, mark, disc = true)
            ThreadMark.CROSS -> HiIcon(Hi.Close, D.White, mark, disc = true)
            ThreadMark.CLOCK -> TickingClock((size.value * 0.8f).dp, D.White, ticking = look.ticking)
            // No `Hi` for a dash. It is the "nothing happened" mark from the tap sheet's own
            // tone table — one stroke across the 24 grid at the disc weight.
            ThreadMark.DASH -> Canvas(Modifier.size(mark)) {
                val u = this.size.minDimension / 24f
                drawLine(
                    D.White, Offset(7f * u, 12f * u), Offset(17f * u, 12f * u),
                    strokeWidth = markStroke(mark, disc = true) * u, cap = StrokeCap.Round,
                )
            }
        }
    }
}

// ───────────────────────────────────────────────────────────── bars that reach the screen edge

/**
 * Paints a bar's ground out past the page's gutter.
 *
 * Both bars on this screen are full-bleed (`-mx-5` in the sandbox) while the screen itself is
 * drawn inside MainActivity's 20dp gutter, and Compose has no negative padding. So the ground is
 * DRAWN rather than laid out: `drawBehind` is free to paint outside its own bounds, and no
 * ancestor here clips.
 *
 * [corner] rounds the TOP two corners only, by drawing the rounded rect a full radius taller
 * than the bar so its bottom curve falls off the screen.
 */
private fun Modifier.barGround(
    fill: Color,
    corner: Dp = 0.dp,
    hairline: Boolean = false,
    shadow: Boolean = false,
) = drawBehind {
    val bleed = Space.gutter.toPx()
    val r = corner.toPx()
    val w = size.width + bleed * 2
    if (shadow) {
        // `0 -6px 16px -8px rgba(0,0,0,.18)` — a soft drop UPWARDS, off the bar's top edge, the
        // way Design.kt's cardShadow draws its own: a framework paint, because Compose's
        // shadow() has no spread and cannot cast upward alone.
        drawIntoCanvas { canvas ->
            val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
                isAntiAlias = true
                color = android.graphics.Color.WHITE
                setShadowLayer(16.dp.toPx(), 0f, -6.dp.toPx(), android.graphics.Color.argb(46, 0, 0, 0))
            }
            canvas.nativeCanvas.drawRoundRect(-bleed, 0f, -bleed + w, size.height, r, r, paint)
        }
    }
    drawRoundRect(
        fill, topLeft = Offset(-bleed, 0f), size = Size(w, size.height + r),
        cornerRadius = CornerRadius(r),
    )
    if (hairline) {
        drawLine(D.Line, Offset(-bleed, 0f), Offset(-bleed + w, 0f), strokeWidth = 1.dp.toPx())
    }
}

/**
 * A 48dp touch target that only takes [to] of the layout.
 *
 * §8.4 puts a copy icon inside a fact row whose neighbours are 8 + text tall, and §5 requires
 * the copy target to be 48. The sandbox resolves that with negative margins; this measures the
 * child at its natural 48 and reports [to], centring the overhang — so the finger gets 48 and
 * the row keeps its neighbours' height.
 */
private fun Modifier.shrinkTarget(to: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val side = to.roundToPx()
    layout(side, side) { placeable.place((side - placeable.width) / 2, (side - placeable.height) / 2) }
}

/** A value worth noticing, on the highlight — the marker pen that replaced the tints. */
@Composable
private fun Highlighted(text: String) = Text(
    text, color = D.Black, style = Type.body, maxLines = 1,
    modifier = Modifier.background(D.Highlight, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
)

// ──────────────────────────────────────────────────────────────────────── the screen

/**
 * One person's thread, and the page behind any item in it.
 *
 * The open item is **hoisted**: [openItem] is a row id and [onOpenItem] moves it, so the page is
 * a destination the host can back out of (and a back press can close the page without closing
 * the thread). The row is re-resolved from the lists on every composition rather than captured,
 * so paying from the page updates the page.
 *
 * Every parameter after [onRename] has a default, so a caller that has not been updated yet
 * still compiles — it simply gets a thread with no rewards, no wallet fact and no item pages.
 */
@Composable
internal fun Thread(
    who: Contact,
    toPay: List<Inbox.Item>,
    owed: List<Watch.Entry>,
    notes: List<Messages.Note>,
    paying: String?,
    onPay: (Inbox.Item) -> Unit,
    onDecline: (Inbox.Item) -> Unit,
    onWithdraw: (Watch.Entry) -> Unit,
    onBack: () -> Unit,
    onOpen: () -> Unit,
    onSay: (String) -> Unit,
    onRename: (String) -> Unit,
    /** §8.1's reward card. `Rewards.Row.counterparty` is an address, so this filters exactly. */
    rewards: List<Rewards.Row> = emptyList(),
    /** §8.4's `Wallet` fact: which of OUR wallets this went through. */
    wallet: Linked? = null,
    /** Which row's page is open, by [ThreadRow.id]. Null for the thread itself. */
    openItem: String? = null,
    onOpenItem: (String?) -> Unit = {},
    /** §8.4's `Later` — not an answer, but not silence either. */
    onLater: (Inbox.Item) -> Unit = {},
    /** §8.4's foot bar. Drawn only where there is somewhere for it to go — see §13. */
    onHelp: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
) {
    val called = rememberCalled(who)
    var renaming by remember(who.address) { mutableStateOf(false) }
    if (renaming) {
        ContactNameDialog(
            called = called,
            current = who.yours.orEmpty(),
            onDismiss = { renaming = false },
            onSave = { renaming = false; onRename(it) },
        )
    }

    val rows = threadRows(who, toPay, owed, notes, rewards)
    val them = firstName(called.shown)

    val open = openItem?.let { id -> rows.firstOrNull { it.id == id } }
    if (open != null) {
        ItemPage(
            row = open, name = called.shown, them = them, address = who.short, wallet = wallet,
            paying = paying,
            onPay = onPay, onDecline = onDecline, onLater = onLater, onWithdraw = onWithdraw,
            onHelp = onHelp, onShare = onShare,
            onBack = { onOpenItem(null) },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        ThreadHeader(
            who = who, called = called, onBack = onBack, onRename = { renaming = true },
        )
        // A Box, so the bar floats OVER the conversation rather than queueing up after it —
        // which is the whole reason §8.3 gives it a see-through ground.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                NothingYet(who, called, them, onSay, Modifier.align(Alignment.Center))
            } else {
                Conversation(
                    rows = rows, them = them, paying = paying,
                    onPay = onPay, onDecline = onDecline, onOpenItem = onOpenItem,
                )
            }
            MessageBar(
                peer = who.address, onSay = onSay, onPay = onOpen,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * §8.1's top bar: back, their face, their name and address, Rename.
 *
 * The tick is the **Seeker** badge at 20 (§8.1), which is a claim about their hardware. The
 * "linked wallet" note survives from the old header: it says the name came from a DIFFERENT
 * wallet vouching for this one, and that is worth saying out loud on a screen where money
 * moves — §8.1 does not mention it because the sandbox's sample peer has no linked wallet.
 */
@Composable
private fun ThreadHeader(who: Contact, called: Called, onBack: () -> Unit, onRename: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = Space.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack)
                .semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) { HiIcon(Hi.ArrowLeft, D.Black, 24.dp) }
        Spacer(Modifier.width(Space.tight))
        // The face on the person's OWN colour, seeded by the address so they look the same here,
        // in §3 People and on a transaction row.
        FaceDisc(called.shown, who.address, 40.dp)
        Spacer(Modifier.width(Space.inline))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    called.shown, color = D.Black, style = Type.heading, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                if (who.seeker) {
                    Spacer(Modifier.width(Space.tight))
                    HiIcon(Hi.Verified, D.BlueInk, 20.dp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(who.short, color = D.Quiet, style = Type.mono, maxLines = 1)
                if (called.borrowed) Text(" · linked wallet", color = D.Quiet, style = Type.caption)
            }
        }
        // §8.1: a text button at the end, not the whole name column. The name is a label to
        // read far more often than it is a control to press.
        Box(
            Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onRename).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Rename", color = D.BlueInk, style = Type.body) }
    }
}

/**
 * §8.1's "No history": their face, what it means, and the two ways to start.
 *
 * The pills send that word; the first payment goes through the bar's Pay button below. Bottom
 * padding of a bar's worth, so this centres in the room ABOVE the bar rather than under it.
 */
@Composable
private fun NothingYet(
    who: Contact,
    called: Called,
    them: String,
    onSay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(bottom = BAR_SPACE),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FaceDisc(called.shown, who.address, 64.dp)
        Spacer(Modifier.height(Space.row))
        Text("Nothing between you yet", color = D.Black, style = Type.heading)
        Spacer(Modifier.height(Space.tight))
        Text(
            "Say hello, or send $them their first payment.",
            color = D.Quiet, style = Type.caption, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(Space.row))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Hi", "Hello").forEach { word ->
                val press = remember { MutableInteractionSource() }
                Box(
                    Modifier.then(PressScale(press)).heightIn(min = 48.dp).clip(CircleShape)
                        .border(1.5.dp, D.Line, CircleShape)
                        .clickable(press, indication = null) { onSay(word) }
                        .padding(horizontal = Space.row),
                    contentAlignment = Alignment.Center,
                ) { Text(word, color = D.Black, style = Type.body) }
            }
        }
    }
}

/**
 * The conversation itself.
 *
 * Built from the bottom up, newest first, which is what `reverseLayout` means: the list is
 * ANCHORED to the end instead of being scrolled there afterwards. Two attempts at scrolling to
 * the end — by pixel and by item — both landed a bubble short, because the height they aim at
 * is only known after layout. There is nothing to aim at here.
 *
 * So the rows are flattened WITH their dividers in reading order and then reversed: each row
 * still owns the space above it (§8.1's 24 over a divider, 12 between cards), and padding is
 * not mirrored by `reverseLayout`, so the spacing comes out the right way up.
 */
@Composable
private fun Conversation(
    rows: List<ThreadRow>,
    them: String,
    paying: String?,
    onPay: (Inbox.Item) -> Unit,
    onDecline: (Inbox.Item) -> Unit,
    onOpenItem: (String?) -> Unit,
) {
    // Divider, then its day's rows — one flat list, so one `items` call can draw both. `Any`
    // rather than a wrapper type: a divider IS its day, and a type whose only job is to say
    // "this one is a string" would be a third shape for the two that already exist.
    val flat = buildList<Any> {
        var day: String? = null
        rows.forEach { row ->
            val d = dayOf(row.at)
            if (d != day) { add(d); day = d }
            add(row)
        }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = PaddingValues(bottom = BAR_SPACE),
    ) {
        items(flat.asReversed(), key = { if (it is ThreadRow) it.id else "d:$it" }) { entry ->
            if (entry is String) {
                DayDivider(entry)
            } else {
                val row = entry as ThreadRow
                Box(
                    Modifier.fillMaxWidth().padding(top = Space.inline),
                    contentAlignment = if (row.mine) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    when (row.kind) {
                        ThreadKind.MSG -> MessageBubble(row)
                        ThreadKind.REWARD -> RewardCard { onOpenItem(row.id) }
                        else -> ItemCard(
                            row = row, them = them, paying = paying,
                            onPay = onPay, onDecline = onDecline, onOpen = { onOpenItem(row.id) },
                        )
                    }
                }
            }
        }
    }
}

/** §8.1: a hairline either side of the day, 24 above and 12 below. */
@Composable
private fun DayDivider(day: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(D.Line))
        Text(
            day, color = D.Quiet, style = Type.caption,
            modifier = Modifier.padding(horizontal = Space.inline),
        )
        Box(Modifier.weight(1f).height(1.dp).background(D.Line))
    }
}

/**
 * §8.1's message: a bubble, 260 at most, `D.Ink` filled when it is ours and outlined when it is
 * theirs. Not a card — there is nothing behind a message to open.
 *
 * White on `D.Black` is 12.5:1, so unlike the blue ground this bubble used to have, a 15sp
 * sentence is legible on it.
 */
@Composable
private fun MessageBubble(row: ThreadRow) {
    val shape = RoundedCornerShape(CARD_R)
    Box(
        Modifier.widthIn(max = CARD_W).clip(shape)
            .then(if (row.mine) Modifier.background(D.Black) else Modifier.border(1.5.dp, D.Line, shape))
            .padding(horizontal = CARD_PAD, vertical = 12.dp),
    ) {
        Text(row.text.orEmpty(), color = if (row.mine) D.White else D.Black, style = Type.body)
    }
}

/**
 * §8.1's payment or ask: title, amount, and a status row that says where it stands and when.
 *
 * **Their open ask is the one card with buttons.** They are inside the card and under the status
 * row, because the question and its answer are one thing; a Pay button floating on the page
 * below would not say which row it answered.
 */
@Composable
private fun ItemCard(
    row: ThreadRow,
    them: String,
    paying: String?,
    onPay: (Inbox.Item) -> Unit,
    onDecline: (Inbox.Item) -> Unit,
    onOpen: () -> Unit,
) {
    val shape = RoundedCornerShape(CARD_R)
    val answerable = row.item?.payable == true && !row.mine
    Column(Modifier.width(CARD_W).clip(shape).border(1.5.dp, D.Line, shape)) {
        val press = remember { MutableInteractionSource() }
        Column(
            Modifier.then(PressScale(press)).clickable(press, indication = null, onClick = onOpen)
                .padding(CARD_PAD),
        ) {
            Text(titleOf(row, them), color = D.Quiet, style = Type.caption, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Space.tight))
            Text(money(row.amount, row.symbol), color = D.Black, style = Type.title.figure(), maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(row.stage, 20.dp)
                Spacer(Modifier.width(8.dp))
                // The word takes the stage's INK and the rest stays quiet, so the colour and the
                // words say the same thing on one line.
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = row.look.ink)) { append(wordOf(row, them)) }
                        append(" · ${ago(row.at)}")
                    },
                    color = D.Quiet, style = Type.caption, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Chevron(16.dp, D.Quiet)
            }
        }
        if (answerable) {
            val item = row.item!!
            Row(
                Modifier.padding(start = CARD_PAD, end = CARD_PAD, bottom = CARD_PAD),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CardButton(
                    label = if (paying == item.request.reference) "…" else "Pay",
                    fill = D.Blue, enabled = paying == null, modifier = Modifier.weight(1f),
                ) { onPay(item) }
                // Red, by §10's second exception: the Decline button on an incoming ask.
                CardButton(
                    label = "Decline", fill = D.Danger, enabled = paying == null,
                    modifier = Modifier.weight(1f),
                ) { onDecline(item) }
            }
        }
    }
}

/** One of the two answers on a card: 48 tall, corner 16, white `Type.heading` on its fill. */
@Composable
private fun CardButton(
    label: String,
    fill: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.then(PressScale(press)).heightIn(min = 48.dp).clip(shape)
            .background(if (enabled) fill else D.Line)
            .clickable(press, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // White on D.Blue (3.3:1) and on D.Danger (3.31:1) pass only as LARGE text, which is
        // why every button label in this app is the 19sp heading step.
        Text(label, color = if (enabled) D.White else D.Quiet, style = Type.heading, maxLines = 1)
    }
}

/**
 * §8.1's reward card: the covered scratch card rising out of a tray.
 *
 * The wide card is tilted and set low in a tray that CLIPS it, so only its upper part shows and
 * the tray's edge cuts the rest — it never reaches the line under it. The tilt flips for a
 * right-to-left script, so the card always leans away from where reading starts.
 */
@Composable
private fun RewardCard(onOpen: () -> Unit) {
    val shape = RoundedCornerShape(CARD_R)
    val press = remember { MutableInteractionSource() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(
        Modifier.width(CARD_W).then(PressScale(press)).clip(shape).border(1.5.dp, D.Line, shape)
            .clickable(press, indication = null, onClick = onOpen).padding(CARD_PAD),
    ) {
        Text("You earned a reward", color = D.Black, style = Type.body)
        Spacer(Modifier.height(Space.inline))
        Box(
            Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(12.dp))
                .background(REWARD_TRAY),
        ) {
            Image(
                painter = painterResource(R.drawable.simple_wide),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
                    .width(200.dp).aspectRatio(16f / 9f)
                    .graphicsLayer { rotationZ = if (rtl) 6f else -6f }
                    .shadow(6.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp)),
            )
        }
        Spacer(Modifier.height(Space.inline))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tap to view", color = D.Quiet, style = Type.caption, modifier = Modifier.weight(1f))
            Chevron(16.dp, D.Quiet)
        }
    }
}

// ───────────────────────────────────────────────────────────────────── §8.3 the message bar

/**
 * §8.3 — pinned to the foot, and the only way out of this screen into money.
 *
 * The bar's own ground is **not a solid slab**: it is white at 60%, so what scrolls under it
 * shows through, and only the field is opaque white.
 *
 * One button at the end, 104 wide: **Pay** until something is typed, then **Send**. The two
 * labels trade places over 200ms — the old one slides up 12 and fades out, the new one slides up
 * into place and fades in — rather than swapping, so pressing it never looks like a different
 * button appeared. After sending, the field clears and it is Pay again. The keyboard's enter key
 * sends, because a message bar where enter does nothing is a message bar nobody finishes using.
 */
@Composable
private fun MessageBar(
    /** Keys the draft, so walking into somebody else's thread does not carry it with you. */
    peer: String,
    onSay: (String) -> Unit,
    onPay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(peer) { mutableStateOf("") }
    val typing = draft.isNotBlank()
    val send = {
        if (draft.isNotBlank()) {
            onSay(draft.trim())
            draft = ""
        }
    }
    // One number drives both labels, so they can never both be in view or both be gone.
    val swap by animateFloatAsState(if (typing) 1f else 0f, tween(200), label = "payOrSend")

    Row(
        modifier.fillMaxWidth()
            .barGround(D.White.copy(alpha = .6f))
            .padding(top = BAR_TOP, bottom = BAR_BOTTOM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.weight(1f).heightIn(min = BAR_H).clip(CircleShape).background(D.White)
                .border(1.5.dp, D.Line, CircleShape)
                .padding(horizontal = CARD_PAD, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (draft.isEmpty()) Text("Message…", color = D.Quiet, style = Type.body, maxLines = 1)
            BasicTextField(
                value = draft,
                // Capped where the server caps it, so nothing is typed that cannot be sent.
                onValueChange = { if (it.length <= Messages.MAX_BODY) draft = it },
                textStyle = Type.body.copy(color = D.Black),
                cursorBrush = SolidColor(D.Blue),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        val press = remember { MutableInteractionSource() }
        Box(
            Modifier.then(PressScale(press)).width(BAR_BUTTON_W).height(BAR_H)
                .clip(CircleShape).background(D.Blue)
                .clickable(press, indication = null) { if (typing) send() else onPay() }
                .semantics { contentDescription = if (typing) "Send message" else "Pay" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Pay", color = D.White, style = Type.heading, maxLines = 1,
                modifier = Modifier.graphicsLayer {
                    alpha = 1f - swap
                    translationY = -12.dp.toPx() * swap
                },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.graphicsLayer {
                    alpha = swap
                    translationY = 12.dp.toPx() * (1f - swap)
                },
            ) {
                Text("Send", color = D.White, style = Type.heading, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                HIcon(R.drawable.hi_send, D.White, 20.dp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────── §8.4 an item's page

/**
 * One item's own page — a full screen, not a sheet.
 *
 * The state's disc is at the top where the person's face would be, because the page is about
 * what happened and the name is in the line under it. Then the amount, one outcome line, and
 * when. Every FACT about it goes in one outlined card below, label at the start and value at
 * the end on one line, with no rules between rows: eight hairlines in a 200dp card read as a
 * table of unrelated things, and these are all one thing.
 *
 * **Rows appear only when they apply, and a fact this app cannot establish is left out rather
 * than filled in.** Three of §8.4's rows are absent for that reason:
 *
 *  - `Paid` / `Asked` ("In person, by tap" / "Through AirPay") — neither [Inbox] nor [Watch]
 *    records how a request arrived. A tap and a server delivery both call `Inbox.add` and
 *    neither leaves a mark.
 *  - `Network fee` — nothing stores the fee a transaction paid; it is not in the payment path.
 *  - `Reward` on a payment — the SKR a payment earned is known to the server by `reference`,
 *    but `Rewards.Row` carries no reference, so a payment cannot be matched to its reward
 *    without guessing on amount and time. It is shown on the reward's OWN page, where it is a
 *    fact rather than an inference.
 */
@Composable
private fun ItemPage(
    row: ThreadRow,
    name: String,
    them: String,
    address: String,
    wallet: Linked?,
    paying: String?,
    onPay: (Inbox.Item) -> Unit,
    onDecline: (Inbox.Item) -> Unit,
    onLater: (Inbox.Item) -> Unit,
    onWithdraw: (Watch.Entry) -> Unit,
    onHelp: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onBack: () -> Unit,
) {
    val uri = LocalUriHandler.current
    // Which way the money goes, or would: a payment I made and an ask of theirs both send it
    // to them.
    val toThem = if (row.kind == ThreadKind.PAY) row.mine else !row.mine
    val answerable = row.item?.payable == true && !row.mine
    val heading = when (row.kind) {
        ThreadKind.REWARD -> "Reward"
        ThreadKind.PAY -> if (toThem) "To $name" else "From $name"
        else -> if (row.mine) "You asked $name" else "$name asked you"
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack)
                    .semantics { contentDescription = "Back" },
                contentAlignment = Alignment.Center,
            ) { HiIcon(Hi.ArrowLeft, D.Black, 24.dp) }

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                StatusDot(row.stage, 64.dp)
                Spacer(Modifier.height(Space.inline))
                Text(heading, color = D.Black, style = Type.heading, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                // The figure at the `display` step with its code beside it at `heading`, which is
                // how every other big amount in this app is set (PopupUi's `AmountDisplay`): one
                // 60sp run carrying "0.50 USDC" cannot wrap — Money joins them with a no-break
                // space — so it would simply run off a 360dp screen.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        moneyFigure(row.amount, row.symbol), color = D.Black,
                        style = Type.display, maxLines = 1, softWrap = false,
                    )
                    Spacer(Modifier.width(Space.tight))
                    Text(
                        row.symbol, color = D.Quiet, style = Type.heading,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Spacer(Modifier.height(Space.row))
                Text(
                    outcomeOf(row, them), color = row.look.ink, style = Type.body,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Space.tight))
                // `tnum` on the caption, not the figure face: it is a date, not an amount, and
                // the sandbox's `tnum` class only asks the digits to hold their column.
                Text(
                    fullWhen(row.at), color = D.Quiet,
                    style = Type.caption.copy(fontFeatureSettings = "tnum"),
                )
            }

            // §8.4's answer row, on their open ask and nowhere else: Pay, then Decline and Later.
            if (answerable) {
                val item = row.item!!
                Spacer(Modifier.height(Space.row))
                PrimaryButton(
                    label = if (paying == item.request.reference) "…" else "Pay",
                    onClick = { onPay(item) },
                    enabled = paying == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Space.inline))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
                    val press = remember { MutableInteractionSource() }
                    Box(
                        Modifier.weight(1f).then(PressScale(press)).heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(16.dp)).background(D.Danger)
                            .clickable(press, indication = null, enabled = paying == null) {
                                onDecline(item); onBack()
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text("Decline", color = D.White, style = Type.heading) }
                    SecondaryButton(
                        "Later",
                        onClick = { onLater(item); onBack() },
                        modifier = Modifier.weight(1f),
                        enabled = paying == null,
                    )
                }
            }

            Spacer(Modifier.height(Space.row))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, D.Line, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                // A reward is not a transfer: nobody sent SKR to anybody, so there is no To and
                // no From to state. `Thread.tsx` reuses a payment's party rows here, which would
                // have this page claim they paid us the SKR.
                if (row.kind != ThreadKind.REWARD) {
                    Fact("To") {
                        if (toThem) Party(name, address) else Party("You", null)
                    }
                    Fact("From") {
                        if (toThem) Party("You", null) else Party(name, address)
                    }
                }
                if (wallet != null) {
                    Fact("Wallet") {
                        WalletMarkSmall(wallet)
                        Spacer(Modifier.width(8.dp))
                        Text(wallet.name, color = D.Black, style = Type.body, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
                row.signature?.let { sig ->
                    Fact("Transaction") {
                        Text(
                            sig, color = D.Black, style = Type.body.monospaced(), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        // §5's one copy behaviour, in its 48 target, shrunk to 24 of layout so
                        // this row stays as tall as its neighbours.
                        CopyMark(sig, 20.dp, Modifier.shrinkTarget(24.dp))
                    }
                }
                Fact("Network") { Text("Solana ${Chain.NETWORK}", color = D.Black, style = Type.body) }
                row.earned?.let { n -> Fact("Reward") { Highlighted("Earned ${skr(n)} SKR") } }
                // Only on an ask that was never paid — the one row that exists to say a number
                // nobody should go looking for.
                if (row.kind == ThreadKind.ASK && !row.settled) {
                    Fact("Money moved") { Text("None", color = D.Black, style = Type.body) }
                }
            }

            // Not in §8.4, and kept anyway: this is the only way to take a request back, and
            // §8 does not replace it. It is a quiet text link rather than a button because
            // withdrawing is the rare answer to a question we asked.
            row.entry?.takeIf { it.live && !it.rejected }?.let { entry ->
                TextLink("Take it back", onClick = { onWithdraw(entry); onBack() })
            }
            Spacer(Modifier.height(Space.row))
        }

        // §8.4's foot bar. A chip is drawn only where it has somewhere to go: Explorer needs a
        // transaction, and `Get help` and `Share` need the destinations §13 says are not
        // designed. A button that does nothing is worse than a bar with two on it.
        val sig = row.signature
        val explorer: (() -> Unit)? =
            if (sig != null) ({ uri.openUri(Chain.explorerUrl(sig)) }) else null
        if (explorer != null || onHelp != null || onShare != null) {
            Row(
                Modifier.fillMaxWidth()
                    .barGround(D.White, corner = 24.dp, hairline = true, shadow = true)
                    .padding(top = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                explorer?.let { FootChip(R.drawable.hi_open, "Explorer", Modifier.weight(1f), it) }
                onHelp?.let { FootChip(R.drawable.hi_help, "Get help", Modifier.weight(1f), it) }
                onShare?.let { FootChip(R.drawable.hi_share, "Share", Modifier.weight(1f), it) }
            }
        }
    }
}

/**
 * The wallet's own mark at 20, for §8.4's `Wallet` fact.
 *
 * Two kinds live behind [Linked.iconRes] and they cannot be drawn the same way: a line vector
 * that must be tinted to be visible, and the wallet's real app icon, which is a tile with its own
 * background and its own colours. Tinting the second flattens it to a silhouette — so `iconTint`
 * being null is an instruction to draw it as an image, not a reason to reach for a default ink.
 * Fit, not crop: these icons are not all square (Backpack is 117x142, Seeker 681x607).
 *
 * **This is the third copy of this branch** — `MainActivity.WalletLogo` and
 * `ProfileDrawer.WalletMark` are the other two, and both are `private` to their files. It belongs
 * in `Design.kt` beside `Initial`, which is a shared file this change may not touch.
 */
@Composable
private fun WalletMarkSmall(w: Linked) {
    val tint = w.iconTint
    if (tint == null) {
        Image(
            painterResource(w.iconRes), contentDescription = null,
            modifier = Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)),
            contentScale = ContentScale.Fit,
        )
    } else {
        HIcon(w.iconRes, tint, 20.dp)
    }
}

/**
 * One of the foot bar's actions: a 20 icon and a word, 48 tall, white on ink.
 *
 * All three look the same, so none outranks another; ink on the white bar is what sets them
 * apart from it. White on ink is 12.5:1, so unlike a blue chip this one carries the 15sp body
 * step rather than needing the 19sp heading.
 */
@Composable
private fun FootChip(
    @DrawableRes icon: Int,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    Row(
        modifier.then(PressScale(press)).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .background(D.Black).clickable(press, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        HIcon(icon, D.White, 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(label, color = D.White, style = Type.body, maxLines = 1)
    }
}

/**
 * §8.4: one fact, its label at the start and its value at the end, on one line and no rule under
 * it.
 *
 * The value is a `RowScope` lambda so a long value can take `weight` and truncate — written as a
 * plain lambda it would bind `Modifier.weight` to whatever Column the call site happens to sit
 * in, which compiles and then quietly does nothing.
 */
@Composable
private fun Fact(label: String, value: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = D.Quiet, style = Type.body, maxLines = 1)
        Spacer(Modifier.width(Space.inline))
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) { value() }
    }
}

/**
 * A party to the payment: the name in ink and, after it, the short address quiet and mono.
 *
 * One `Text`, not two, so the pair wraps and truncates as the one run it reads as. Our own side
 * is "You" with no address: it is this phone's wallet, and the address is already a fact of the
 * Wallet row.
 */
@Composable
private fun Party(name: String, address: String?) {
    Text(
        buildAnnotatedString {
            append(name)
            if (address != null) {
                append(" ")
                withStyle(SpanStyle(fontFamily = Mono, fontSize = 13.sp, color = D.Quiet)) {
                    append(address)
                }
            }
        },
        color = D.Black, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
    )
}
