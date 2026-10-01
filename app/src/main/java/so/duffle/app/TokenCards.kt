package so.duffle.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.decode.SvgDecoder
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import java.math.BigDecimal
import kotlin.math.ceil

/**
 * §2d — one card per token held: a day of its price on a dark band, and what you hold under it.
 *
 * Ported from `ui/components/sections/TokenCards.tsx` against ui/PORT-2026-10-01.md §3, which
 * restructured this section, so it is a rewrite rather than an edit.
 *
 * Two things the card is ABOUT, both easy to undo by accident:
 *
 *  1. **The pill is gone; the price rides the line's end.** The chart is the price's day, so its
 *     last point IS the price now — and when the price updates the tip moves with the dot.
 *  2. **No market means no claim.** A mint with no market gets an empty band and the words
 *     "No market price" where the worth goes — never "$0.00", which reads as worthless when the
 *     honest answer is unknown. `Market.Quote` keeps "could not ask" apart from "no market" for
 *     exactly this reason, and only the second one may say so.
 *
 * The sandbox still sizes this card in viewport units; §3.1 gives the dp they measure to on the
 * Seeker, and those are what is used here — Compose has no viewport units.
 */
private val CARD_W = 200.dp
private val CARD_H = 128.dp
private val CARD_R = 32.dp
/**
 * The dark band: the full card width less [CARD_R], taken off the START side.
 *
 * It runs to the card's end edge and stops part-way under the logo, so the logo still sits on
 * the chart's ground at its trailing half while the black pulls back from the card's start.
 *
 * Nothing in the band may reach the end corner, because that is where the price tip rides and
 * the card's clip was taking a bite out of the pill. That clearance is [CHART_END]'s job now,
 * not the band's — the same thing `ui/components/sections/TokenCards.tsx` says about its own
 * chart: "ends 24 in to clear the card's 32 radius corner".
 */
private val BAND_W = CARD_W - CARD_R

private val BAND_H = 68.dp
private val CHART_H = 43.dp
private val PANEL_H = 85.dp
private val LOGO = 64.dp

/**
 * Where the chart's drawing starts INSIDE the band: past the logo, plus 12 to clear its ring,
 * less the band's own start inset. Declared after [LOGO] because a top-level `val` that reads
 * another initialises in file order, and above it the logo is still zero.
 */
private val CHART_INSET = LOGO + 12.dp - CARD_R

/** And where it stops: clear of the card's end corner, so the price tip is never clipped. */
private val CHART_END = 24.dp

/** How far the logo hangs above the card's top-start corner, and the white ring that parts it. */
private val LOGO_RISE = 12.dp
private val LOGO_RING = 3.dp

/** Past this many characters the amount steps down a size rather than being cut. Money is never truncated. */
private const val AMOUNT_STEP_DOWN = 11

@Composable
internal fun TokenBadges(
    holdings: List<Tokens.Holding>,
    quotes: Map<String, Market.Quote>,
    onCopy: (String) -> Unit,
) {
    if (holdings.isEmpty()) return

    // Aligned with every other section at rest, and running off the SCREEN when scrolled.
    //
    // The parent's gutter would do the first but not the second: a card scrolling away would
    // stop at an invisible margin 20dp in. So the row breaks out of that gutter and carries
    // the start inset as content instead — a leading spacer, which a scroller is guaranteed
    // to scroll. There is no trailing spacer, because the end edge is meant to run out to the
    // screen.
    //
    // `layout` reports the parent's OWN width and places the measured-wider content one gutter
    // to the start. Reporting `placeable.width` here instead shifted the row by two gutters,
    // not one, and left the start edge on 0dp — measured on the phone, twice.
    Row(
        Modifier
            .layout { measurable, constraints ->
                val gutter = Space.gutter.roundToPx()
                val placeable = measurable.measure(
                    // TWO gutters wider than the parent allows: one buys back the gutter the
                    // row is shifted into, the other buys the one at the end. One gutter here
                    // left the end edge 20dp short of the screen — measured.
                    constraints.copy(minWidth = 0, maxWidth = constraints.maxWidth + 2 * gutter),
                )
                layout(constraints.maxWidth, placeable.height) { placeable.place(-gutter, 0) }
            }
            .horizontalScroll(rememberScrollState())
            .padding(vertical = Space.row),
    ) {
        Spacer(Modifier.width(Space.gutter))
        holdings.forEachIndexed { i, h ->
            if (i > 0) Spacer(Modifier.width(Space.inline))
            TokenCard(h, quotes[h.mint ?: Market.WSOL], onCopy)
        }
        // The same gutter after the last card as before the first. It costs nothing at rest —
        // it sits off the end of the viewport — and it is what stops the last card dead against
        // the screen edge once the row is scrolled all the way.
        Spacer(Modifier.width(Space.gutter))
    }
}

@Composable
private fun TokenCard(h: Tokens.Holding, quote: Market.Quote?, onCopy: (String) -> Unit) {
    val press = remember { MutableInteractionSource() }
    val priceUsd = quote?.usd
    val series = quote?.series

    // Worth = what you hold x the price now, multiplied once and then cut by Money.fiat — never
    // rounded up. No price means no worth: a gap, not a zero.
    val worth = remember(h.units, h.decimals, priceUsd) {
        priceUsd?.let { BigDecimal.valueOf(h.units, h.decimals).toDouble() * it }
    }
    val amount = remember(h.units, h.decimals, h.symbol) {
        Money.token(BigDecimal.valueOf(h.units, h.decimals), h.symbol)
    }

    // Two layers, and the split matters: the OUTER box only sizes the card, so the logo can be
    // its child and hang over the corner unclipped. The inner box clips everything else.
    Box(Modifier.then(PressScale(press)).size(CARD_W, CARD_H)) {
        Box(
            Modifier.fillMaxSize().cardShadow(CARD_R)
                .clip(RoundedCornerShape(CARD_R)).background(D.White)
                .clickable(press, indication = null) { onCopy(h.mint ?: "SOL") },
        ) {
            // The dark band, in the top END corner.
            Box(
                // Anchored to the END edge, so the width that was taken off came off the
                // START side — the black pulls back from under the logo rather than away
                // from the card's end. The tip is kept off the corner by the chart's own end
                // padding ([CHART_END]) instead of by the band stopping short.
                Modifier.align(Alignment.TopEnd).size(BAND_W, BAND_H).background(D.Night)
                    .padding(start = CHART_INSET, end = CHART_END),
            ) {
                Chart(
                    series = series,
                    priceUsd = priceUsd,
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().height(CHART_H),
                )
            }

            // The content panel, pinned to the bottom and overlapping the band's lower half, so
            // about 43 of the band's 68 shows above it. Bottom-aligned inside, so it clears the
            // logo hanging over the top-start corner.
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(PANEL_H)
                    .clip(RoundedCornerShape(topStart = CARD_R, topEnd = CARD_R))
                    .background(D.White)
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                // Money is never cut off. A long amount steps DOWN one size instead: "1,250,000
                // BONK" was rendering as "1,250,000 B…", which is not a number anybody can act on.
                Text(
                    amount, color = D.Black,
                    style = (if (amount.length > AMOUNT_STEP_DOWN) Type.heading else Type.title).figure(),
                    maxLines = 1,
                )
                Spacer(Modifier.height(Space.tight))
                // "<name> · <worth>" on one line. The name is dropped when the mint has no real
                // one — blank, equal to the symbol, or starting with it, which is what an
                // unnamed mint's "ABCD…WXYZ" placeholder does.
                val named = h.name.isNotBlank() &&
                    !h.name.equals(h.symbol, true) && !h.name.startsWith(h.symbol)
                val tail = worth?.let { Money.fiat(BigDecimal(it.toString()), "USD") } ?: "No market price"
                Text(
                    currencyStyled(if (named) "${h.name} · $tail" else tail),
                    color = D.Quiet,
                    // A worth is money and takes the figure face; "No market price" is a
                    // sentence and keeps the words face.
                    style = if (worth != null) Type.caption.figure() else Type.caption,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // The logo, on the OUTER layer so the card's clip does not cut it. The white ring parts
        // it from the dark band it overlaps.
        TokenLogo(
            h, quote?.icon,
            Modifier.align(Alignment.TopStart).offset(y = -LOGO_RISE),
        )
    }
}

/**
 * The token's own icon, or its letter while that loads and if there is none.
 *
 * The letter is the fallback for both cases on purpose: a blank circle that later fills in reads
 * as broken, where a letter that becomes a logo reads as loading.
 */
@Composable
private fun TokenLogo(h: Tokens.Holding, icon: String?, modifier: Modifier) {
    // A stable colour per mint, so the same token always looks the same. The same hash
    // `TokenImage` uses, so the letter does not change colour between here and the tap sheet.
    val hue = ((h.token.hashCode() and 0x7fffffff) % 360).toFloat()
    Box(
        modifier.size(LOGO).clip(CircleShape)
            .background(Color.hsv(hue, .55f, .78f))
            .border(LOGO_RING, D.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(h.symbol.take(1).uppercase(), color = D.White, style = Type.title)
        if (icon != null) {
            AsyncImage(
                model = icon, contentDescription = null, imageLoader = svgCapable(),
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** The tip's text style: 10sp, 600, the figure face — small, because it rides the line. */
private val TipStyle = TextStyle(
    fontFamily = Figure, fontSize = 10.sp, lineHeight = 14.sp,
    fontWeight = FontWeight(600), fontFeatureSettings = "tnum", color = Color.White,
)

/** Pill padding, the tail that points at the dot, and the line's weight. All §3.5. */
private val TIP_PAD_H = 6.dp
private val TIP_H = 16.dp
private val TIP_TAIL = 4.dp
private val LINE_W = 2.dp
private val CHART_PAD_V = 6.dp

/** What the tip needs beside its text: both pads, the tail, and clearance from the edge. */
private val TIP_ROOM = 18.dp

/**
 * A day of this token's price, with the price itself riding the line's last point.
 *
 * Drawn, not charted: no grid, no axes, no fill under the line, no dashed price line and no
 * scrubbing — the row scrolls sideways, so a scrub gesture would fight it.
 *
 * **Red here is not a payment state.** A falling day is `D.DangerInk` and a rising one
 * `D.SuccessInk` (ui/PORT-2026-10-01.md §1.3): market direction only, and the one sanctioned
 * red outside a chain refusal. The INK shades are required — white tip text on them is 5.38:1,
 * where the bright fills give 3.31:1 and fail.
 *
 * Three states, and they are not the same:
 *  - a series and a price: the line, the dot and the tip;
 *  - a price but no day: the price alone at the band's end;
 *  - no market at all: nothing. "No market price" under the amount already says it, and saying
 *    it twice reads like an error.
 */
@Composable
private fun Chart(series: List<Double>?, priceUsd: Double?, modifier: Modifier) {
    if (priceUsd == null) return
    val label = remember(priceUsd) { Money.priceLabel(BigDecimal(priceUsd.toString())) }

    if (series == null || series.size < 2) {
        Box(modifier, contentAlignment = Alignment.CenterEnd) {
            Text(
                currencyStyled(label), color = D.White,
                // `micro`'s size and weight without its tracking: micro is a tracked uppercase
                // LABEL, and this is a figure.
                style = Type.micro.copy(letterSpacing = 0.sp).figure(), maxLines = 1,
            )
        }
        return
    }

    val rising = series.last() >= series.first()
    val ink = if (rising) D.SuccessInk else D.DangerInk
    val measurer = rememberTextMeasurer()

    // The halo breathes so a live price looks live. It is the dot's own pulse, not a transition,
    // so it is not one of the app's springs.
    val pulse by rememberInfiniteTransition(label = "tip").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart), label = "halo",
    )

    // The room the tip needs on THIS card, measured rather than fixed: "$117" keeps a long line
    // and "$0.0₅38" gives up only what it needs. Every digit becomes an 8 so the width does not
    // jitter as the price ticks. Fixed widths cut the long tips off at the card's edge.
    val tipText = remember(label) { measurer.measure(currencyStyled(label), TipStyle) }
    val gauge = remember(label) {
        measurer.measure(currencyStyled(label.map { if (it.isDigit()) '8' else it }.joinToString("")), TipStyle)
    }

    Canvas(modifier) {
        // ui/PORT-2026-10-01.md §3.5: ceil(textWidth + 18). The 18 is the pill's own width
        // either side of the figure plus the tail, and it is the brief's number rather than a
        // sum reassembled here, so the two cannot drift apart.
        val room = ceil(gauge.size.width + TIP_ROOM.toPx())
        val right = (size.width - room).coerceAtLeast(1f)
        val top = CHART_PAD_V.toPx()
        val usable = (size.height - 2 * top).coerceAtLeast(1f)

        val lo = series.min()
        val hi = series.max()
        val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        val dx = right / (series.size - 1)
        fun y(v: Double) = top + usable * (1f - ((v - lo) / span).toFloat())

        val path = Path().apply {
            moveTo(0f, y(series[0]))
            for (i in 1 until series.size) lineTo(dx * i, y(series[i]))
        }
        drawPath(path, ink, style = Stroke(LINE_W.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val end = Offset(right, y(series.last()))
        // The halo fades as it grows, so the dot never looks like it has a ring.
        drawCircle(ink.copy(alpha = .35f * (1f - pulse)), LINE_W.toPx() * (1.6f + 2.4f * pulse), end)
        drawCircle(ink, LINE_W.toPx() * 1.6f, end)
        tip(end, tipText, ink)
    }
}

/** The pill, its tail, and the figure inside — drawn at the line's end and following it. */
private fun DrawScope.tip(
    at: Offset,
    text: androidx.compose.ui.text.TextLayoutResult,
    ink: Color,
) {
    val h = TIP_H.toPx()
    val pad = TIP_PAD_H.toPx()
    val tail = TIP_TAIL.toPx()
    val w = text.size.width + 2 * pad
    val left = at.x + tail + LINE_W.toPx()
    val top = (at.y - h / 2).coerceIn(0f, (size.height - h).coerceAtLeast(0f))

    // The tail is a triangle off the pill's start edge, pointing back at the dot, so the pill
    // reads as attached to the line rather than floating beside it.
    drawPath(
        Path().apply {
            moveTo(left, top + h / 2 - tail)
            lineTo(left - tail, at.y.coerceIn(top, top + h))
            lineTo(left, top + h / 2 + tail)
            close()
        },
        ink,
    )
    drawRoundRect(
        ink, Offset(left, top), Size(w, h),
        androidx.compose.ui.geometry.CornerRadius(h / 2),
    )
    drawText(text, topLeft = Offset(left + pad, top + (h - text.size.height) / 2))
}


/**
 * An image loader that can decode SVG as well as the raster formats.
 *
 * Token logos come from whatever each mint lists, and a good share of them are SVG — Jupiter
 * serves USDT's as `logo.svg`. Coil's default decoders are raster only, so those tokens drew
 * nothing and fell through to the letter, which looked exactly like a token with no logo at
 * all. That was nearly mistaken for a token to hide.
 *
 * Remembered against the context so one loader (and so one memory and disk cache) serves every
 * card, rather than one per card per recomposition.
 */
@Composable
private fun svgCapable(): ImageLoader {
    val context = LocalContext.current
    return remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
}
