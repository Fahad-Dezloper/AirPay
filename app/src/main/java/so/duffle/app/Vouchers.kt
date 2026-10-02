package so.duffle.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import kotlin.math.pow

/**
 * §5 — "Recommended for you": a sideways row of gift cards from the shop's catalogue, each one
 * opening that product in the shop.
 *
 * Ported from `ui/components/sections/Vouchers.tsx` against ui/PORT-2026-10-01-3.md §5.
 *
 * Three things this section is ABOUT, and all three are easy to undo by accident:
 *
 *  1. **The badge is a PRICE, not cashback.** The design this came from said "5% back". AirPay
 *     pays no percentage back on a gift card — rewards are SKR, on the rules the rest of this
 *     screen lists — so a "% back" badge would be a claim the app does not keep. The pill says
 *     what the card *starts at*, and nothing here may ever render a percentage.
 *  2. **The catalogue is the server's.** The sandbox hard-codes eight Indian products; this reads
 *     whatever `Shop.sections()` returned. No brand is named in this file, no brand list is
 *     ordered here, and a product with a shape this card cannot draw is skipped rather than
 *     guessed at (see [lowestPrice]).
 *  3. **The row runs to both screen edges**, with the gutter carried as content. The heading
 *     above it does not — it sits in the parent's gutter like every other section heading.
 *
 * The order is the catalogue's own. §5 asks for "well-known brands first" and `Shop.Product`
 * carries no popularity, rank or featured field to sort on — only id, name, currency, recipient,
 * redemption, categories, image and the denominations — so the server's order is passed through
 * untouched. Sorting by a list of brands written into the app would be exactly the fixed list
 * §5 says not to use, and it would rot the first time the catalogue changed. If "well known"
 * is to be honoured it has to arrive as a field on the product.
 *
 * **Parent contract:** like [TokenBadges], this expects a parent padded by [Space.gutter]
 * horizontally (the tab host at `MainActivity` does this). The heading uses that gutter; the row
 * breaks back out of it.
 */
private val CARD_W = 136.dp
private val CARD_R = 16.dp

/** The brand's panel: the full card width, and the logo sits inside its own padding. */
private val HEADER_H = 80.dp
private val LOGO_PAD_H = 16.dp
private val LOGO_PAD_V = 12.dp

/** The outline, on the card and on the badge. Same 1.5 as every other hairline in the app. */
private val OUTLINE = 1.5.dp

private val BODY_PAD = 12.dp

/**
 * The badge's own spacing. 8 above it and 8 × 4 inside it are §5's numbers; no [Space] role is
 * 8, because 8 is not a role here — it is the gap that keeps a pill off the line above it.
 */
private val BADGE_GAP = 8.dp
private val BADGE_PAD_H = 8.dp
private val BADGE_PAD_V = 4.dp

/** Fully round, the way every other pill in this app is drawn. */
private val BADGE_R = 999.dp

/**
 * The two grounds a logo can be padded onto, and the panel shown while there is no logo yet.
 *
 * §5 asks for "the brand's own colour" and **the live catalogue does not carry one**:
 * `Shop.Product` has no colour field and the server's `_trim` does not send one. The first port
 * asked the CDN to pad every logo onto a fixed near-white `b_rgb:F8F8F8` and painted the header
 * that same colour, so the padded PNG's ground would match the header rather than show as a
 * lighter rectangle. That holds for a dark wordmark and **fails completely for a white one**:
 * Vodafone's logo was all but invisible on the phone — a white mark on F8F8F8 is 1.06:1, which
 * is not a contrast ratio so much as a blank card. That is the bug this replaced.
 *
 * The honest fix needs no data the server does not have: ask for the logo on a transparent
 * ground (see [headerArt]) and **choose the ground from the logo itself** (see [groundFor]) —
 * dark under a light logo, light under a dark one. No hex and no brand list: both grounds are
 * palette roles, and [D.Night]'s documented use is exactly this, the head of a card.
 *
 * [HeaderIdle] is the header before a logo exists — while it loads, if it fails, and for a
 * product the catalogue gave no image id for at all. All three are the same quiet panel on
 * purpose; see [VoucherCard].
 */
private val GroundLight = D.White
private val GroundDark = D.Night
private val HeaderIdle = D.Fill

/** A non-breaking space, so a trailing currency symbol never wraps off its figures. */
private const val NBSP = " "

/**
 * The logo at the header's shape, from Bitrefill's image CDN.
 *
 * Built here rather than taken from `Shop.Product.logoUrl` for two reasons. That one asks for
 * `h_256,w_256` — a square, for the 40dp square tile the shop list draws. Fitted into this
 * header's 104 × 56 logo area a square source scales to its height, so a wordmark that should
 * run 104 wide renders about 45 wide and the card reads half-empty. These are the same numbers
 * at 3×, which is what `Vouchers.tsx`'s `art()` requests.
 *
 * And it asks for **no `b_rgb:`**, so the padding comes back transparent and the ground is this
 * card's to choose rather than the CDN's to bake in. ui/PORT-2026-10-01-3.md §3.4 already relies
 * on this form of the URL giving transparency, for the logo drawn on the tilted gift card.
 *
 * Padded, never cropped, for the reason `Shop.logoUrl` documents: these are logos of wildly
 * different shapes and cropping them to one box cuts the wordmarks in half.
 */
private fun headerArt(p: Shop.Product): String? =
    p.image?.takeIf { it.isNotBlank() }?.let {
        "https://www.bitrefill.com/content/cn/c_pad,h_168,w_312/v1/$it.png"
    }

/** Which way round a card's header is drawn: a light logo on dark, or a dark logo on light. */
internal enum class Ground { Light, Dark }

/** sRGB → linear light, the one form of the transfer function everything below is built on. */
private fun toLinear(v: Float): Float =
    if (v <= .04045f) v / 12.92f else ((v + .055f) / 1.055f).pow(2.4f)

/**
 * The same curve for the 256 byte values a pixel can carry, so the per-pixel work in [groundFor]
 * is three array reads and a multiply-add instead of three `pow` calls.
 */
private val LINEAR = FloatArray(256) { toLinear(it / 255f) }

/** WCAG relative luminance of one non-premultiplied ARGB pixel's colour. */
private fun luminanceOf(argb: Int): Float =
    .2126f * LINEAR[(argb ushr 16) and 0xFF] +
        .7152f * LINEAR[(argb ushr 8) and 0xFF] +
        .0722f * LINEAR[argb and 0xFF]

private fun luminanceOf(c: Color): Float =
    .2126f * toLinear(c.red) + .7152f * toLinear(c.green) + .0722f * toLinear(c.blue)

/** WCAG contrast between two relative luminances, whichever way round they arrive. */
private fun contrast(a: Float, b: Float): Float =
    (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)

/** The grounds' own luminance, so the choice below is derived from the palette, not guessed. */
private val DARK_L = luminanceOf(GroundDark)
private val LIGHT_L = luminanceOf(GroundLight)

/**
 * Whether a logo of mean luminance [logo] can be read on [ground]: WCAG's 3:1, the bar for large
 * graphics. Used to decide whether a brand colour the server names is a usable ground for that
 * brand's own logo — Tinder's white wordmark on its pink is 3.6:1 and reads; the same wordmark on
 * a pale brand colour would not, and then the measured palette ground wins.
 */
internal fun readableOn(logo: Float, ground: Color): Boolean = contrast(logo, luminanceOf(ground)) >= 3f

/**
 * Every Nth pixel is plenty for an average, and 7 shares no factor with any plausible row
 * stride, so the walk drifts across the image instead of resampling one column. The decode
 * [LogoCache] hands back for this URL is about 156 × 84, which still leaves ~1,900 samples.
 */
private const val GROUND_STEP = 7

/**
 * **Pixels in, a choice out:** the ground this logo should be padded onto, whichever of the two
 * its own average luminance contrasts better against.
 *
 * @param pixels the logo in **non-premultiplied** ARGB, the way `Bitmap.getPixels` — and so
 *   `ImageBitmap.toPixelMap` — hands pixels over. Premultiplied pixels would drag every
 *   antialiased edge toward black and bias the whole measurement light-ward.
 *
 * Three things this has to get right, each of them a way of accidentally giving every brand the
 * same answer:
 *
 *  1. **Fully transparent pixels are not evidence.** `c_pad` returns a 312 × 168 frame with a
 *     small mark somewhere in the middle; most of the frame is empty. Averaging the empty part
 *     in is averaging the padding, not the logo.
 *  2. **Partial alpha is weighted by it**, so a soft or antialiased edge counts for exactly as
 *     much as it is actually painted.
 *  3. **An image with no transparent pixel at all brings its own ground** — a logo with a white
 *     plate baked in. Padding that onto [GroundDark] would frame it in a visible rectangle, so
 *     it takes the light ground whatever its average says.
 *
 * The crossover sits at luminance ≈ 0.19 (grey #797979), where both grounds give the same
 * ≈4.4:1 — so **every** logo clears 4.35:1 on the ground it gets. The in-between case and exact
 * ties go to [Ground.Light], because a row of mostly-light cards with one dark one in it reads
 * as a chequerboard, and light is the resting state of every other surface in the app. An empty
 * or wholly transparent array is Light too: there is nothing there to put on a dark panel.
 */
internal fun groundFor(pixels: IntArray, step: Int = GROUND_STEP): Ground {
    var sum = 0.0
    var weight = 0L
    var clear = 0
    val every = step.coerceAtLeast(1)
    var i = 0
    while (i < pixels.size) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        if (a == 0) clear++ else {
            sum += a * luminanceOf(p)
            weight += a
        }
        i += every
    }
    if (weight == 0L || clear == 0) return Ground.Light
    val logo = (sum / weight).toFloat()
    return if (contrast(logo, DARK_L) > contrast(logo, LIGHT_L)) Ground.Dark else Ground.Light
}

/**
 * The lowest price this product can be bought at, in its own currency — or null when it has no
 * numeric price at all, in which case the card shows no badge.
 *
 * Read through `Shop.Package.price`, which is the one place that knows `value` wins over `amount`
 * whenever it is a number: KFC's ₹1000 card has `value: "1000"` and `amount: 999.92`, a rounding
 * artefact of their pricing, and "From ₹999" would be the wrong number on the pill. Products with
 * no packages fall back to their bare denominations, the same way the shop's own price row does.
 *
 * A non-numeric price is dropped rather than shown: a bundle's value is a sentence
 * ("2GB/Day 5G Plan - 84 Days"), and `price` hands back its `amount` instead, so anything still
 * unparseable here is a product this badge has nothing true to say about.
 */
private fun lowestPrice(p: Shop.Product): BigDecimal? =
    p.packages.ifEmpty { p.values.map { Shop.Package(it, 0) } }
        .mapNotNull { it.price.toBigDecimalOrNull() }
        .filter { it.signum() > 0 }
        .minOrNull()

/**
 * `From ₹100` — the price **whole**, in the product's own currency.
 *
 * `Money.fiat` cannot do this: unlike the sandbox's `fiat(…, { whole: true })` the Kotlin one
 * takes no `whole` flag and always prints the currency's own decimals, so ₹100 would read
 * "₹100.00" — a balance's format on a shop's price list. So the figure comes out of
 * `Money.fiatParts`, which exists precisely to hand back a formatted value in parts, and the
 * fraction is left off. The locale still decides the symbol, which side it goes on and how the
 * digits group; nothing is assembled from a symbol table here. Reassembling symbol + figures
 * with [NBSP] on the trailing-symbol side is what `Money.priceLabel` already does.
 *
 * **Only when the value is truly whole.** A price with real paise keeps them — `Money.fiat`
 * prints ₹12.46 in full, dust and all — because dropping them would understate what something
 * costs, which is the one direction this app never rounds.
 *
 * Null when the currency is one `Currency.getInstance` does not know. Three of the catalogue's
 * products are already priced in dollars rather than rupees, the code arrives as a server string,
 * and both `Money` entry points take it straight to ICU — so an unrecognised code throws, inside
 * composition, from a decoration. No badge is the honest outcome; a crashed Rewards screen is not.
 */
private fun fromLabel(value: BigDecimal, currency: String): String? = runCatching {
    val whole = value.stripTrailingZeros().scale() <= 0
    if (!whole) return@runCatching "From ${Money.fiat(value, currency)}"
    val parts = Money.fiatParts(value, currency)
    val figure =
        if (parts.symbolFirst) "${parts.symbol}${parts.whole}" else "${parts.whole}$NBSP${parts.symbol}"
    "From $figure"
}.getOrNull()

/**
 * The badge's face: `Type.caption`, with tabular figures so the prices in a scrolled row line up
 * as they pass. The sandbox's `tnum` class, and not `.figure()` — that would also swap the face,
 * and "From" is a word.
 */
private val BadgeStyle: TextStyle = Type.caption.copy(fontFeatureSettings = "tnum")

/**
 * @param products the catalogue's products, in the order the server gave them. Nothing is
 *   rendered — heading included — when this is empty: a heading over an empty row is a section
 *   that looks broken rather than absent.
 * @param onOpen opens that product in the shop.
 * @param onSeeAll the `See all` text button. **Null by default and the button is then not drawn**,
 *   because §10 records that `See all` has no destination designed yet and a visible button that
 *   does nothing is the dishonest half of this screen's own rules. Pass one when there is
 *   somewhere for it to go and the button appears.
 */
@Composable
internal fun Recommended(
    products: List<Shop.Product>,
    onOpen: (Shop.Product) -> Unit,
    onSeeAll: (() -> Unit)? = null,
) {
    if (products.isEmpty()) return

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Recommended for you", color = D.Black, style = Type.heading)
            if (onSeeAll != null) {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSeeAll)
                        .heightIn(min = 48.dp).padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("See all", color = D.BlueInk, style = Type.body) }
            }
        }
        Spacer(Modifier.height(Space.lead))

        // Aligned with every other section at rest, and running off the SCREEN when scrolled.
        //
        // The parent's gutter would do the first but not the second: a card scrolling away would
        // stop at an invisible margin 20dp in. So the row breaks out of that gutter and carries
        // the inset as content instead — leading and trailing spacers, which a scroller is
        // guaranteed to scroll. **Padding does not survive the scroller; spacers do.** This is
        // `TokenBadges`' layout, which took four measured attempts on the phone: `layout`
        // measures TWO gutters wider than the parent allows (one buys back the gutter the row is
        // shifted into, the other buys the one at the end — one gutter left the end edge 20dp
        // short), reports the parent's OWN `constraints.maxWidth`, and places at `-gutter`.
        // Reporting `placeable.width` instead shifts the row by two gutters, not one.
        Row(
            Modifier
                .layout { measurable, constraints ->
                    val gutter = Space.gutter.roundToPx()
                    val placeable = measurable.measure(
                        constraints.copy(minWidth = 0, maxWidth = constraints.maxWidth + 2 * gutter),
                    )
                    layout(constraints.maxWidth, placeable.height) { placeable.place(-gutter, 0) }
                }
                .horizontalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.width(Space.gutter))
            products.forEachIndexed { i, p ->
                if (i > 0) Spacer(Modifier.width(Space.inline))
                VoucherCard(p, onOpen)
            }
            // The same gutter after the last card as before the first. It costs nothing at rest —
            // it sits off the end of the viewport — and it is what stops the last card dead
            // against the screen edge once the row is scrolled all the way.
            Spacer(Modifier.width(Space.gutter))
        }
    }
}

/**
 * A logo, the ground measured from it, and how light it is. One value, so a card can never draw a
 * logo without the ground chosen for it — a logo on the wrong ground for even a frame is the bug,
 * briefly. [luminance] rides along because a brand colour the server names is only a usable
 * ground if the logo can be read on it (see `PageLogo` in ShopScreen.kt), and that needs the
 * logo's own lightness, not just which of the two palette grounds it prefers.
 */
internal class LogoArt(
    val logo: ImageBitmap,
    val ground: Ground,
    val luminance: Float?,
    /** No transparent pixel at all: the logo brings its own plate, which no ground should frame. */
    val plated: Boolean,
)

/**
 * The mean WCAG luminance of the logo's painted pixels, alpha-weighted, or null when nothing is
 * painted. The same walk as [groundFor] — transparent pixels are padding, not evidence — kept
 * separate so [groundFor]'s contract (and its tests) stays exactly what it was.
 */
internal fun logoLuminance(pixels: IntArray, step: Int = GROUND_STEP): Float? {
    var sum = 0.0
    var weight = 0L
    val every = step.coerceAtLeast(1)
    var i = 0
    while (i < pixels.size) {
        val a = (pixels[i] ushr 24) and 0xFF
        if (a != 0) {
            sum += a * luminanceOf(pixels[i])
            weight += a
        }
        i += every
    }
    return if (weight == 0L) null else (sum / weight).toFloat()
}

/**
 * Every logo this screen has decoded **and measured**, held in memory by URL and bounded by
 * BYTES, not by count.
 *
 * **Why it exists.** [LogoCache] holds 48 bitmaps by count, and the "More" list draws a hundred
 * and more rows at once — so by the time the last row was decoded the first had been evicted,
 * and going back from a service page to the list showed a letter on every row until each logo
 * was fetched again. This cache is sized for the whole list (a decoded logo is ~50 KB, so the
 * budget holds several hundred) and holds the measured ground alongside the bitmap, so a logo
 * seen once is complete — bitmap and ground — on the very first frame of every later
 * composition. [LogoCache] is still what fetches and decodes; this is what remembers.
 */
internal object ArtCache {
    private const val BUDGET = 24 * 1024 * 1024
    private val cache = object : android.util.LruCache<String, LogoArt>(BUDGET) {
        override fun sizeOf(key: String, value: LogoArt): Int =
            (value.logo.width * value.logo.height * 4).coerceAtLeast(1)
    }

    /** A full-resolution decode is a different bitmap of the same URL, so it is its own entry. */
    private fun keyOf(url: String, full: Boolean) = if (full) "$url#full" else url

    fun get(url: String, full: Boolean = false): LogoArt? = cache.get(keyOf(url, full))

    /**
     * Off the main thread only: it fetches, and [toPixelMap] copies the bitmap to measure it.
     *
     * [full] decodes at the source's own size. [LogoCache] halves the image until it is about
     * 128 px wide — right for a 48dp tile, but a service page draws the logo ~64dp tall across
     * the screen's width, where that decode is upscaled several times over and looks soft. Falls
     * back to [LogoCache]'s decode if the full one fails.
     */
    fun load(url: String, full: Boolean = false): LogoArt? {
        val key = keyOf(url, full)
        cache.get(key)?.let { return it }
        val bmp = (if (full) fetchFull(url) else null) ?: LogoCache.load(url) ?: return null
        val px = bmp.toPixelMap().buffer
        return LogoArt(bmp, groundFor(px), logoLuminance(px), px.none { (it ushr 24) != 0xFF })
            .also { cache.put(key, it) }
    }

    private fun fetchFull(url: String): ImageBitmap? = try {
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 6_000; readTimeout = 6_000
        }
        val bytes = c.inputStream.use { it.readNBytes(2_000_000) }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/**
 * The logo for [url], fetched and decoded off the main thread, its ground measured in the same
 * breath — and **already there on the first frame when it has been seen before**.
 *
 * The first frame is the point. The earlier version seeded `produceState` from the cache but
 * only when both halves were cached in two separate, differently-sized stores, and a
 * count-bounded one evicted first; a recomposition then started from null and drew the letter
 * placeholder until the producer got through IO. Here the cache is read synchronously on every
 * composition and wins over the async result, so a hit never passes through the null state.
 *
 * [LogoCache] rather than Coil, for the reason it documents: from API 31 Coil decodes to
 * `HARDWARE` bitmaps, whose pixels cannot be read, and the ground has to be measured from the
 * very bitmap that is drawn.
 */
@Composable
internal fun rememberLogoArt(url: String?, full: Boolean = false): LogoArt? {
    val loaded by produceState<LogoArt?>(null, url, full) {
        // A new url must not keep showing the previous one's logo while it loads.
        value = null
        if (url != null && ArtCache.get(url, full) == null) {
            value = withContext(Dispatchers.IO) { ArtCache.load(url, full) }
        }
    }
    return url?.let { ArtCache.get(it, full) } ?: loaded
}

@Composable
private fun VoucherCard(p: Shop.Product, onOpen: (Shop.Product) -> Unit) {
    val press = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(CARD_R)
    val badge = remember(p.id, p.packages, p.values, p.currency) {
        lowestPrice(p)?.let { fromLabel(it, p.currency) }
    }

    Column(
        Modifier.then(PressScale(press))
            .width(CARD_W)
            // Clipped, so the header's colour takes the card's top corners.
            .clip(shape)
            .border(OUTLINE, D.Line, shape)
            .clickable(press, indication = null) { onOpen(p) },
    ) {
        // The ground is measured FROM the logo, so there is no ground to draw until there is a
        // logo: until then — and for ever, if the fetch fails or the product carries no image id
        // — the header is the one quiet [HeaderIdle] panel. Nothing spins and nothing is
        // guessed at; an empty panel is what this card has always shown for a product with no
        // logo, so a failed fetch looks like a card without a logo rather than a broken one.
        // And because the logo and its ground arrive together, the swap happens in a single
        // frame: the panel fills and the mark appears at once, which reads as the card loading
        // rather than as a flash.
        val art = rememberLogoArt(headerArt(p))
        Box(
            Modifier.fillMaxWidth().height(HEADER_H)
                .background(
                    when (art?.ground) {
                        Ground.Dark -> GroundDark
                        Ground.Light -> GroundLight
                        null -> HeaderIdle
                    },
                )
                // 16 × 12 of ground around the logo: a logo run to the header's edges reads
                // cropped — the wordmarks touched both sides.
                .padding(horizontal = LOGO_PAD_H, vertical = LOGO_PAD_V),
        ) {
            art?.let {
                Image(
                    it.logo, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                )
            }
        }
        Column(Modifier.padding(BODY_PAD)) {
            Text(
                // " India" off the end, the same trim the shop's own product rows use: the
                // catalogue is per-country, so the suffix says nothing the screen does not
                // already say, and on one ellipsised line it is what eats the brand's name.
                p.name.removeSuffix(" India"),
                color = D.Black, style = Type.body,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Space.tight))
            Text("Gift card", color = D.Quiet, style = Type.caption)
            // No price in the catalogue means no badge. An empty pill, or one with a guessed
            // figure in it, would both be worse than the card simply not claiming a price.
            if (badge != null) {
                Spacer(Modifier.height(BADGE_GAP))
                Box(
                    Modifier.clip(RoundedCornerShape(BADGE_R))
                        .border(OUTLINE, D.Line, RoundedCornerShape(BADGE_R))
                        .padding(horizontal = BADGE_PAD_H, vertical = BADGE_PAD_V),
                ) {
                    Text(
                        currencyStyled(badge),
                        color = D.Black, style = BadgeStyle, maxLines = 1,
                    )
                }
            }
        }
    }
}
