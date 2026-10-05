package so.duffle.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.itemsIndexed
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.core.view.WindowCompat
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.drawBehind
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §4 — the sheet that shows a delivered code, or says why there is none.
 *
 * Lifted out of MainActivity.kt so this surface can be worked on without touching a
 * 5,000-line file. Rebuilt compact for ui/PORT-2026-10-02.md §4.
 */

// Card geometry, §4: corner 16, a 1.5 outline, 16 side padding, rows 12 top and bottom.
private val CARD_CORNER = 16.dp
private val CARD_EDGE = 1.5.dp
private val CARD_PAD = 16.dp
private val CARD_ROW = 12.dp

/** The status disc and the mark in it, and the clock, which is four fifths of the disc (§2). */
private val CODE_DISC = 40.dp
private val CODE_DISC_MARK = 20.dp
private val CLOCK_MARK = 32.dp

/** The brand's icon at the end of the header row. */
private val BRAND = 40.dp
private val BRAND_CORNER = 10.dp

/**
 * How far a row's 48 touch target overhangs the card's side padding.
 *
 * The sandbox's `-me-3`. The target is 48 and the mark in it is 24, so without this the mark
 * sits 12 inside the card's 16 padding and reads as misaligned with the text column above it.
 * Pushed out by 12, the mark's own edge lines up with the padding edge while the target keeps
 * its full 48. An offset and not a negative padding, so nothing in the layout moves.
 */
private val TARGET_OUT = 12.dp

/**
 * The 8 the sandbox spends on `mt-2` and `gap-2`: a caption under the thing it qualifies, and
 * the gap between a line of text and the mark at its end.
 *
 * A literal from the 4px scale rather than a `Space` role. The roles name the jobs that GROUP
 * content — a heading and its first item, one section and the next — and 8 inside a card is
 * component internals, which the scale says is where the plain steps belong.
 */
private val GAP = 8.dp

/**
 * A payment state, as a colour and a shape — the three tones this sheet uses.
 *
 * [fill] is the disc; [ink] is the headline beside it, and the two are never swapped: a fill is
 * about 3.3:1 with white, which passes for a disc carrying a thick mark and fails for a
 * sentence. `StatusDisc` and its own `Tone` table are private to PopupUi.kt, which this port
 * may not edit, so these are drawn here on the same rules.
 *
 * **A failed order is the neutral disc, not the red one.** The seller turned the order down;
 * the chain refused nothing. Red is for a transaction that went out and came back refused, and
 * for the two places §10 adds — neither of which is this.
 */
private enum class CodeTone(val fill: Color, val ink: Color) {
    /** Paid and delivered. */
    Paid(D.Success, D.SuccessInk),

    /** Bought, not here yet. */
    Pending(D.Blue, D.BlueInk),

    /** It did not happen. Grey, because it is an outcome and not an alarm. */
    Nothing(D.Neutral, D.Quiet),
}

/**
 * What a state shows. [detail] only where the headline leaves something unsaid.
 *
 * The six rows of §4's table, derived from the order rather than passed in as a name: the sheet
 * is handed a real [Orders.Order] and `Orders.Order.delivered` / `waiting` / `failed` already
 * hold Bitrefill's vocabulary, read off a real invoice.
 */
private data class CodeView(
    val tone: CodeTone,
    val title: String,
    val detail: String? = null,
    val codes: Boolean = false,
    val receipt: Boolean = true,
)

/**
 * The thing you bought, put in front of you.
 *
 * This did not exist, and its absence was the whole failure: a real PhonePe card was paid for,
 * delivered, and captured correctly — and the screen said "Sent" and then nothing. The code sat
 * in storage while the buyer went to their email to find it.
 *
 * ### §4: compact, in three parts, and **no state scrolls**
 *
 * 1. **One header row** — the 40 status disc, the headline in the tone's ink, what was bought
 *    under it, the brand's icon at the end. One line of `Type.body` follows only where the
 *    headline leaves something unsaid.
 * 2. **One card for the code**, when there is one — a row each for the code, the PIN, or where
 *    to redeem; the value in the mono face; one 48 action at the row's end. No full-width Copy
 *    buttons, which is what made the old sheet tall.
 * 3. **One card for the receipt**, whenever money actually moved — what was paid (the largest
 *    thing in it), where the code went, and the transaction. **Left out entirely when nothing
 *    was charged**, because a receipt for a payment that never happened is a false record.
 *
 * Nothing here is inside a scroll container, and that is deliberate rather than incidental:
 * [Tray] does not scroll, so the content has to fit, and every value that could run long is
 * capped instead of being allowed to push the button off the screen.
 *
 * Each part of a redemption is its own labelled row rather than one blob. A card number and a
 * PIN are used in different boxes on someone else's website, and a sixteen-digit run followed
 * by a six-digit run says nothing about which is which.
 *
 * @param currency the product's own currency, for the face value: "₹100". **Not stored on the
 *   order** — `Orders.Order` keeps the bare denomination and `Shop.Product.currency` is only in
 *   hand while buying. Null until it is, and then the figure shows without a symbol rather than
 *   being guessed at (see the final note in §13).
 * @param logoUrl the brand's icon, from Bitrefill's image CDN as `Shop.Product.logoUrl` builds
 *   it (§11). Also not stored on the order; null draws nothing rather than a placeholder.
 * @param paidLocal the same payment in the shop's display currency, at the rate when it was
 *   paid. §4 requires this to come from the server and §13 lists "which server field supplies
 *   it" as undecided — `/v1/shop/order` sends `status`, `delivered_time` and `redemption` and
 *   nothing else — so it is nullable and renders nothing. The phone must never convert it here.
 * @param localCurrency the currency [paidLocal] is in. Shown only when it is not dollars, which
 *   is the condition §4 states.
 */
@Composable
internal fun CodeSheet(
    order: Orders.Order,
    currency: String? = null,
    logoUrl: String? = null,
    paidLocal: BigDecimal? = null,
    localCurrency: String? = null,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val r = remember(order.code) { Shop.Redemption.of(order.code) }

    // "Money moved" is "there is a transaction", and nothing else. A blank signature is a real
    // shape here — `Orders.all()` reads it with `optString`, so a row written without one comes
    // back blank — and it is exactly §4's `failed (not charged)`: nothing to show a receipt for
    // and nothing to look up on an explorer.
    val signature = order.signature.takeIf { it.isNotBlank() }
    val v = remember(order, r, signature) { viewOf(order, r, signature != null) }

    Tray(onClose = onClose) {
        // 16 under the handle.
        Spacer(Modifier.height(Space.row))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CodeDisc(v.tone)
            Spacer(Modifier.width(Space.inline))
            Column(Modifier.weight(1f)) {
                Text(v.title, color = v.tone.ink, style = Type.heading, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Space.tight))
                // A face value and what it was: a sentence with a figure in it, so it keeps the
                // words' face and takes tabular digits — and the currency symbol still goes to
                // Bricolage, because the currency cut leads both stacks.
                Text(
                    currencyStyled("${faceValue(order.value, currency)} · " +
                        order.name.removeSuffix(" India")),
                    color = D.Quiet, style = Type.caption.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            // The brand, as it ships. Nothing is drawn without a logo: an empty tile at the end
            // of the row is a thing the reader has to decide to ignore.
            logoUrl?.let { url ->
                Spacer(Modifier.width(Space.inline))
                val logo by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                    LogoCache.get(url), url,
                ) { if (value == null) value = withContext(Dispatchers.IO) { LogoCache.load(url) } }
                logo?.let {
                    Image(
                        it, contentDescription = null,
                        modifier = Modifier.size(BRAND).clip(RoundedCornerShape(BRAND_CORNER)),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }

        v.detail?.let {
            Spacer(Modifier.height(CARD_ROW))
            Text(it, color = D.Black, style = Type.body)
        }

        if (v.codes && r != null) {
            // A redemption can be instructions alone, or only a field we have no name for. Then
            // there is no row to put in the card, and an empty card drew as a bare outline.
            val rows = r.code != null || r.pin != null || r.link != null
            if (rows) {
                Spacer(Modifier.height(Space.row))
                SheetCard {
                    r.code?.let { CodeField("Code", it) }
                    r.pin?.let {
                        if (r.code != null) RowLine()
                        CodeField("PIN", it)
                    }
                    r.link?.let {
                        if (r.code != null || r.pin != null) RowLine()
                        LinkField("Redeem at", it, uri)
                    }
                }
            }
            // Whatever came with the code and could not be made a row. A field we have not seen
            // before is still worth showing: the alternative is silently dropping part of the
            // thing somebody paid for.
            val extras = listOfNotNull(
                r.instructions,
                r.raw,
                r.expires?.let { "Valid until ${it.take(10)}" },
            )
            extras.forEach {
                Spacer(Modifier.height(if (rows) GAP else CARD_ROW))
                // Three lines beside a code. With no code these lines ARE what was bought, so
                // they get the room the card would have had, and ink instead of grey.
                Text(it, color = if (rows) D.Quiet else D.Black, style = Type.caption,
                    maxLines = if (rows) 3 else 12, overflow = TextOverflow.Ellipsis)
            }
        }

        if (v.receipt) {
            // 12 between cards — which is what the sandbox gives the receipt in every state,
            // including the ones where it is the first card on the sheet.
            Spacer(Modifier.height(CARD_ROW))
            SheetCard {
                Row(Modifier.fillMaxWidth().padding(vertical = CARD_ROW)) {
                    Text("You paid", color = D.Quiet, style = Type.body,
                        modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        // The largest thing in the receipt, because it is the thing being
                        // checked. Already formatted when the invoice was paid.
                        Text(currencyStyled(order.paid), color = D.Black,
                            style = Type.heading.figure(), maxLines = 1)
                        if (paidLocal != null && localCurrency != null && localCurrency != "USD") {
                            Spacer(Modifier.height(Space.tight))
                            Text(
                                currencyStyled(Money.fiat(paidLocal, localCurrency)),
                                color = D.Quiet, style = Type.caption.figure(), maxLines = 1,
                            )
                        }
                    }
                }
                // Where the copy went. Only when there is one: the email is optional (see
                // `Identity.email`), and an order placed without one had nothing emailed — a
                // row reading "Sent to —" would claim a delivery that never happened.
                Identity.email(context)?.let { email ->
                    RowLine()
                    Row(Modifier.fillMaxWidth().padding(vertical = CARD_ROW)) {
                        Text("Sent to", color = D.Quiet, style = Type.body,
                            modifier = Modifier.alignByBaseline())
                        Spacer(Modifier.width(Space.inline))
                        Text(
                            email, color = D.Black, style = Type.body,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f).alignByBaseline(),
                        )
                    }
                }
                // Proof the payment happened. Blue ink: it is the one thing in the receipt to
                // tap. §13 lists the explorer's destination as undesigned, so it goes where the
                // rest of the app already sends a signature.
                signature?.let { sig ->
                    RowLine()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable { runCatching { uri.openUri(Chain.explorerUrl(sig)) } },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("View on explorer", color = D.BlueInk, style = Type.body,
                            modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(GAP))
                        HIcon(R.drawable.hi_open, D.BlueInk, 20.dp)
                    }
                }
            }
            Spacer(Modifier.height(GAP))
            Text("Sold and delivered by Bitrefill.", color = D.Quiet, style = Type.caption)
        }

        Spacer(Modifier.height(Space.row))
        // "Close" while it is still coming: there is nothing finished to be done with.
        PrimaryButton(if (order.waiting) "Close" else "Done", onClick = onClose)
        Spacer(Modifier.height(Space.lead))
    }
}

/**
 * §4's table, as a function of the order.
 *
 * `delivered` with nothing to redeem is the seventh row the table does not have, and it is a
 * real one: a phone top-up credits the number and Bitrefill correctly returns no
 * `redemption_info` for it (verified against a real delivered order). It keeps the paid tone
 * and says what happened, rather than claiming a code is ready.
 */
private fun viewOf(order: Orders.Order, r: Shop.Redemption?, charged: Boolean): CodeView = when {
    // A code in hand wins over any status. `Orders.setCode` writes the redemption the moment it
    // exists and `setStatus` is a separate call, so the two can be a poll apart — and of the two
    // facts, "the code is here" is the one worth acting on.
    r != null -> CodeView(CodeTone.Paid, "Your code is ready", codes = true)
    order.waiting -> CodeView(
        CodeTone.Pending, "On its way", "Usually a few seconds. You can close this.",
    )
    // The split §4 asks for: the receipt and the explorer link appear only where a payment
    // actually happened. Without one there is nothing to receipt and nothing to refund.
    order.failed && !charged -> CodeView(
        CodeTone.Nothing, "It did not go through", "Nothing was charged.", receipt = false,
    )
    order.failed -> CodeView(
        // §13 flags "refunded automatically" as unconfirmed on this app's side. It is §4's
        // wording and it is the user's to confirm; it is not softened here on a guess.
        CodeTone.Nothing, "It did not arrive", "Your payment is refunded automatically.",
    )
    else -> CodeView(
        CodeTone.Paid, "Delivered",
        "This one returns no code, the value went straight to the recipient.",
    )
}

/**
 * The face value, written the way a shop price is written: whole when it is whole.
 *
 * [Money] has no `whole` option — the sandbox's `fiat()` does — so the figure is reassembled
 * from [Money.fiatParts]: the symbol, the side it sits on, the grouping and the decimal mark
 * all still come out of the locale rather than being guessed here, which is the whole point of
 * going through [Money]. A value with a fraction keeps it: ₹12.46 has paise.
 *
 * Two things fall back to the bare string rather than failing. A **plan** product's value is a
 * sentence and not a number ("2GB/Day 5G Plan - 84 Days"), and the currency may be absent or a
 * code `java.util.Currency` does not know. Both print what was stored, which is true.
 */
private fun faceValue(value: String, currency: String?): String {
    if (currency == null) return value
    val v = value.toBigDecimalOrNull() ?: return value
    return runCatching {
        if (v.stripTrailingZeros().scale() > 0) {
            Money.fiat(v, currency)
        } else {
            val p = Money.fiatParts(v, currency)
            if (p.symbolFirst) "${p.symbol}${p.whole}" else "${p.whole} ${p.symbol}"
        }
    }.getOrDefault(value)
}

/**
 * The disc: a bright circle carrying a thick white mark, and never a bare disc.
 *
 * All three fills share one brightness, so the SHAPE inside is what a colour-blind eye reads —
 * which is why the mark is stroked at `markStroke(disc = true)` and not at the weight an icon
 * of that size would otherwise take.
 */
@Composable
private fun CodeDisc(tone: CodeTone) {
    Box(Modifier.size(CODE_DISC).background(tone.fill, CircleShape), contentAlignment = Alignment.Center) {
        when (tone) {
            CodeTone.Paid -> HiIcon(Hi.Tick, D.White, CODE_DISC_MARK, disc = true)
            // The disc IS the clock's face, so the hands run close to the rim — 32 in the 40
            // (§2) rather than the 20 every other mark takes. And it ticks: this is the one
            // state where time is actually passing.
            CodeTone.Pending -> TickingClock(CLOCK_MARK, D.White)
            // There is no `Hi` for a dash. PopupUi's `Tone.Nothing` is the path "M7 12H17" on
            // the 24-unit icon grid, so it is drawn here at the same disc stroke rather than
            // approximated with a different line.
            CodeTone.Nothing -> Canvas(Modifier.size(CODE_DISC_MARK)) {
                val unit = size.minDimension / 24f
                drawLine(
                    D.White, Offset(7f * unit, 12f * unit), Offset(17f * unit, 12f * unit),
                    strokeWidth = markStroke(CODE_DISC_MARK, disc = true) * unit,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * A card: corner 16, a 1.5 outline, 16 side padding, hairlines between its rows.
 *
 * An outline and not a fill, because there are no containers left (ui/PORT.md §9) — the edge is
 * what says "these rows belong together" on a white sheet. The hairlines sit inside the side
 * padding, like the sandbox's `divide-y` inside its `px-4`.
 */
@Composable
private fun SheetCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(CARD_CORNER))
            .border(CARD_EDGE, D.Line, RoundedCornerShape(CARD_CORNER))
            .padding(horizontal = CARD_PAD),
        content = content,
    )
}

/**
 * A code or a PIN: its label, the value, and one copy action in a 48 target.
 *
 * The value is in the mono face because it is an **identifier**, read a character at a time —
 * none of these is money, and the figure face would say it was. `Type.heading` for a code and a
 * PIN: they are the thing the sheet exists for.
 *
 * [CopyMark] is §5's one copy behaviour, and it is the reason this row has no Copy button: it
 * copies the value with the whitespace stripped, so a code shown in groups of four pastes as
 * one run, and it turns into a green tick for 1.6s so the press is answered.
 */
@Composable
private fun CodeField(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = CARD_ROW),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = D.Quiet, style = Type.caption)
            Spacer(Modifier.height(Space.tight))
            Text(value, color = D.Black, style = Type.heading.monospaced(), maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        CopyMark(value, 24.dp, Modifier.offset(x = TARGET_OUT))
    }
}

/**
 * Where to redeem: the address, and `open` instead of copy.
 *
 * An address is for going to, not for pasting, so the action is the one that takes you there.
 * `Type.body` and not `heading` — §4 keeps the bigger step for the code and the PIN, and a URL
 * at 19sp would be the loudest thing on a sheet whose point is the code.
 *
 * The scheme is dropped from what is shown and kept in what is opened: "www.phonepe.com/redeem"
 * is the part somebody reads, and "https://" is eight characters of a line that has to fit.
 */
@Composable
private fun LinkField(label: String, url: String, uri: UriHandler) {
    val shown = url.removePrefix("https://").removePrefix("http://")
    Row(
        Modifier.fillMaxWidth().padding(vertical = CARD_ROW),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = D.Quiet, style = Type.caption)
            Spacer(Modifier.height(Space.tight))
            Text(shown, color = D.Black, style = Type.body.monospaced(), maxLines = 2,
                overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.offset(x = TARGET_OUT).size(48.dp).clip(CircleShape)
                .clickable { runCatching { uri.openUri(url) } }
                .semantics { contentDescription = "Open $shown" },
            contentAlignment = Alignment.Center,
        ) { HIcon(R.drawable.hi_open, D.BlueInk, 24.dp) }
    }
}
