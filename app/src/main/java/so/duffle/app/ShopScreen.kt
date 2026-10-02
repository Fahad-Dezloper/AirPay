package so.duffle.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import java.math.BigDecimal
import kotlinx.coroutines.launch

/**
 * §3 of ui/PORT-2026-10-02.md — the shop, in three levels, each doing one job.
 *
 * 1. **The list** (§3.1) — only the services: a logo, a name, one line of what it is for. No
 *    mainnet note, no email row, no chevron. The well-known brands lead.
 * 2. **A service's page** (§3.2) — a **full screen**, not a sheet. It is the disclosure: what
 *    this is, what must be read before buying, where it works, how to claim it, and who sells
 *    it. The catalogue's own text is long and uneven and used to be dumped on the buyer; this
 *    page says the same things for every service, in the same order, and leaves a row out
 *    rather than invent one.
 * 3. **The buy sheet** (§3.3) — the price points, what an amount means, where the code goes,
 *    and Pay.
 *
 * ### What this screen cannot say yet
 *
 * §3.2's page is made almost entirely of words `duffleserver/shop.py` does not send:
 * `_trim` gives nine fields (id, name, currency, categories, image, recipient, redemption,
 * values, packages) and the kind, the line, where it works, the validity, the usage, the
 * warnings, the claim steps and what an amount means are none of them. §3.4 says so plainly —
 * "the words there were written by hand… they need a home on AirPay's server" — and §13 keeps
 * the wording as an open point.
 *
 * So they are nullable fields on [Shop.Guide], every one of them null today, and this screen
 * **renders nothing where there is nothing**. Nothing per-service is hardcoded here. What IS
 * hardcoded is the handful of sentences that are AirPay's own and that no server would ever
 * send — the seller disclosure, the delivery line, and the labels — and each of those is a
 * named constant below saying as much.
 *
 * ### Reaching the page
 *
 * [ShopSection] keeps its signature. `picked` used to mean "the row that is expanded"; it now
 * means "the service whose page is open", which is the same state MainActivity already holds
 * (`shopProduct`), already clears on back, and already sets from the Rewards screen's product
 * cards. The one thing the host must do is not draw its own `BackHeader` over this page's title
 * row — see [ShopSection]'s own note.
 */

// -- the words that are ours, not the server's -------------------------------------------------

/**
 * §3.2's seller disclosure, verbatim.
 *
 * **AirPay's own sentence about AirPay**, which is why it is a constant rather than a field:
 * Bitrefill has no reason to describe our relationship to it and the server has nothing to add.
 * §13 flags that the "cannot change or refund it" clause has legal weight and has not been
 * reviewed — it is reproduced here exactly as the sandbox words it, and changing it is a legal
 * decision rather than a layout one.
 */
private const val SOLD_BY =
    "This is bought from Bitrefill, who issue and deliver the code. AirPay passes on your " +
        "order and payment; it does not issue the code and cannot change or refund it. If " +
        "something goes wrong, contact AirPay support or Bitrefill support."

/** §3.3's line under the prices. A statement about this app's own behaviour. */
private const val ALSO_IN_APP =
    "The code also appears in AirPay once delivered. Sold and delivered by Bitrefill."

/** §3.2's heading over the warnings — marked, not iconed (§3.2). */
private const val READ_FIRST = "Read before you buy"

/** §3.2's three fact labels, in §3.2's order. */
private const val WHERE_LABEL = "Where it works"
private const val VALID_LABEL = "Valid for"
private const val USES_LABEL = "How it is used"

// -- the brand's ground ------------------------------------------------------------------------

/**
 * The two grounds a logo can be put on, and nothing in between.
 *
 * §3.1 asks for the tile to carry "the brand's colour" and §3.2 for a dark brand colour to be
 * kept behind a logo with white lettering. **The brand colour is not on the wire** —
 * [Shop.Guide.brandHex] is null until the server grows it — so the ground is measured from the
 * logo's own pixels by `groundFor` (Vouchers.kt), which is the same substitute the voucher
 * cards already make for the same missing field. Two named palette colours, never a tint of
 * the logo: sampling a brand colour and lightening it is exactly what the colour rules forbid,
 * and from API 31 Coil's HARDWARE bitmaps cannot be sampled at all.
 */
private val GroundLight = D.White
private val GroundDark = D.Night

/** The quiet panel a tile shows before its logo and ground have arrived, or if they never do. */
private val GroundIdle = D.Fill

/**
 * The ground a logo is drawn on — **one rule for every logo**: square, wide wordmark, dark, white
 * or transparent.
 *
 *  1. The brand's own colour, if the server named one **and the logo can be read on it** (3:1,
 *     [readableOn]). The colour is the brand's, so it is the first choice.
 *  2. Otherwise the palette ground the logo's own pixels contrast best with ([LogoArt.ground]):
 *     dark under a light logo, light under a dark one.
 *
 * The readability test is the bug this replaced. The page used to ask only whether the brand
 * colour was DARK, and a mid-tone brand colour is neither: Tinder's pink `#fc3a64` is lighter than
 * the cut-off, so the page decided no box was needed and drew Tinder's all-white wordmark bare on
 * the page's white — invisible. A logo that brings its own opaque plate ([LogoArt.plated]) skips
 * the brand colour altogether, or the plate would show as a rectangle inside it.
 *
 * Before the logo has arrived there is nothing to judge, so the brand colour (if any) or the idle
 * panel stands in.
 */
private fun logoGround(art: LogoArt?, brand: Color?): Color = when {
    art == null -> brand ?: GroundIdle
    brand != null && !art.plated && art.luminance?.let { readableOn(it, brand) } == true -> brand
    else -> when (art.ground) {
        Ground.Dark -> GroundDark
        Ground.Light -> GroundLight
    }
}

/** A brand colour the server named, if it ever does. Anything unparseable is simply absent. */
private fun brandColour(hex: String?): Color? = hex?.trim()?.removePrefix("#")
    ?.takeIf { it.length == 6 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
    ?.let { Color(it.toLong(16) or 0xFF000000L) }

// -- small pieces ------------------------------------------------------------------------------

/**
 * §3.2's kind badge: `GIFT CARD`, `SUBSCRIPTION CODE`. `rounded-[5px] px-2`, micro, uppercase,
 * on the highlight.
 *
 * `D.Highlight` only ever sits behind ink text (10.83:1); it is never a dot, line or border.
 */
@Composable
private fun KindBadge(kind: String) = Text(
    kind.uppercase(),
    color = D.Black,
    style = Type.micro,
    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(D.Highlight)
        .padding(horizontal = 8.dp),
)

/**
 * The marker pen behind a heading — `-mx-1` in the sandbox, so the marked words still begin on
 * the same edge as every other line on the page.
 */
@Composable
private fun MarkedHeading(text: String) = Text(
    text,
    color = D.Black,
    style = Type.body.copy(fontWeight = FontWeight.Bold),
    modifier = Modifier.offset(x = (-4).dp).clip(RoundedCornerShape(4.dp))
        .background(D.Highlight).padding(horizontal = 4.dp),
)

/** A `Type.micro` uppercase section label — §3.2's "How to claim it" and "Sold by Bitrefill". */
@Composable
private fun MicroLabel(text: String) =
    Text(text.uppercase(), color = D.Quiet, style = Type.micro)

/**
 * §3.3's `Change` — a text button at the end of the email row, in blue ink.
 *
 * Not `TextLink`: that one is a full-width centred block in `Type.heading`, for the bottom of a
 * sheet. This is the inline kind — `Type.body`, hugging its word, in a 48 target of its own,
 * because here the row itself is not the thing being tapped.
 *
 * §13 records that `Change` has no destination designed. It is wired to the host's own email
 * dialog, which is the one the app already has, rather than left inert.
 */
@Composable
private fun ChangeLink(label: String, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        Modifier.then(PressScale(press)).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(press, indication = null, onClick = onClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = D.BlueInk, style = Type.body) }
}

/**
 * A face value written **whole**: `₹5,000`, not `₹5,000.00`.
 *
 * `Money.fiat` takes no `whole` flag and always prints the currency's own decimals, which is a
 * balance's format on a shop's price list. So the figure comes out of `Money.fiatParts` — which
 * exists to hand a formatted value back in parts — and the fraction is left off. The locale
 * still decides the symbol, which side of the figures it goes on and how the digits group;
 * nothing is assembled from a symbol table here. The same trick, and the same reasoning, as
 * `Vouchers`' own `From ₹100` badge.
 *
 * **Only when the value is truly whole.** A price with real paise keeps them, because dropping
 * them would understate what something costs — the one direction this app never rounds.
 *
 * Null when ICU does not know the currency code, which arrives as a server string: three of the
 * catalogue's products are already priced in dollars rather than rupees, and an unrecognised
 * code throws from inside composition. No figure is the honest outcome; a crashed shop is not.
 */
private fun faceValue(value: BigDecimal, currency: String): String? = runCatching {
    if (value.stripTrailingZeros().scale() > 0) return@runCatching Money.fiat(value, currency)
    val parts = Money.fiatParts(value, currency)
    // A non-breaking space on the trailing-symbol side, so "5.000 €" never wraps apart.
    if (parts.symbolFirst) "${parts.symbol}${parts.whole}" else "${parts.whole} ${parts.symbol}"
}.getOrNull()

/** A token amount, or null when ICU cannot write it. Same guard, same reason, as [faceValue]. */
private fun usdcLabel(value: BigDecimal): String? =
    runCatching { Money.token(value, "USDC") }.getOrNull()

/**
 * Whether to say "Not enough USDC" — only when it is certain.
 *
 * An exact price is compared as it is. An ESTIMATE has to clear the balance by 3% first, because
 * the two real purchases it was measured on sat about a cent high, and refusing somebody who can
 * in fact afford a card is the worse mistake: `buy()` checks the invoice's real price against the
 * balance anyway, and refuses there with the true figure. Unknown cost or unknown balance is never
 * "not enough".
 */
internal fun notEnough(cost: BigDecimal?, balance: BigDecimal?, estimate: Boolean): Boolean {
    if (cost == null || balance == null) return false
    return cost > if (estimate) balance.multiply(BigDecimal("1.03")) else balance
}

/**
 * A package's cost as the price box writes it: "5.49 USDC", or "about 5.49 USDC" when the server
 * says the figure is an estimate. Only the invoice's own number is ever called the price.
 */
private fun costLabel(pack: Shop.Package): String? =
    pack.usdPrice?.toBigDecimalOrNull()?.let(::usdcLabel)
        ?.let { if (pack.usdEstimate) "about $it" else it }

// -- §3.1 the list -----------------------------------------------------------------------------

/**
 * One section of the shop, as §3 draws it: the list of services, or one service's page.
 *
 * **The signature is unchanged on purpose**, so MainActivity's call site still compiles. What
 * `picked` MEANS has changed, to the thing the host's state was always called: it is the
 * service whose full-screen page is open, not a row that is expanded.
 *
 * The host needs one change for §3.2's "this is a full screen, not a sheet": it must not draw
 * its own back-and-title row above this one while `picked != null`, or the page opens with two
 * titles. System back already works — MainActivity's own `BackHandler` clears `shopProduct`
 * before `shopSection`.
 *
 * @param picked the service page to open, by any one of its cards. Null is the list.
 * @param email where a delivered code is copied to. §3.3's email row; null means none is set.
 * @param onEmail opens the host's email dialog — §3.3's `Change`. §13 lists `Change` as having
 *   no destination designed; this is the one the app already has.
 * @param onBuy the existing invoice path, unchanged: a product and the denomination's `value`.
 * @param usdc the wallet's USDC balance, for §3.3's `Balance 25.00 USDC` and for the
 *   `Not enough USDC` button. **Null renders no balance line and never disables Pay**: the
 *   authoritative price comes back on the invoice and `buy()` already refuses honestly there,
 *   so a balance this screen does not know must not be allowed to look like one it does.
 */
@Composable
internal fun ShopSection(
    section: Shop.Section,
    picked: Shop.Product?,
    onPick: (Shop.Product?) -> Unit,
    email: String?,
    onEmail: () -> Unit,
    onBuy: (Shop.Product, String) -> Unit,
    usdc: BigDecimal? = null,
) {
    // Order and grouping only — §3.1's lead order, with MakeMyTrip's five products as one row.
    val services = remember(section) { Shop.services(section.products) }
    val open = remember(services, picked?.id) {
        picked?.let { p -> services.firstOrNull { s -> s.cards.any { it.product.id == p.id } } }
    }

    if (open != null) {
        ServicePage(
            service = open,
            // The card the host asked for, so a Rewards card that names one MakeMyTrip product
            // opens on that product's tab rather than on the brand's first one.
            startCard = open.cards.indexOfFirst { it.product.id == picked?.id }.coerceAtLeast(0),
            email = email,
            onEmail = onEmail,
            usdc = usdc,
            onBack = { onPick(null) },
            onBuy = onBuy,
        )
        return
    }

    // Held above the screen: opening a service and coming back must land on the row that was
    // tapped, not the top of the list.
    val scroll = rememberHeldScroll("shop:${section.title}")
    val searchable = section.title == MORE_TITLE
    val query = ShopQueryHold.query("shop:${section.title}", scroll)
    val scope = rememberCoroutineScope()
    val needle = if (searchable) query.value.trim() else ""
    val shown = remember(services, needle) {
        if (needle.isEmpty()) services
        else services.filter { s ->
            s.name.contains(needle, ignoreCase = true) ||
                s.lead.guide.line?.contains(needle, ignoreCase = true) == true
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Pinned above the list, which scrolls under it, so it is reachable from any row.
        if (searchable) {
            Spacer(Modifier.height(8.dp))
            SearchField(query.value) {
                query.value = it
                // A shorter list under an old scroll offset would open part-way down it.
                scope.launch { scroll.scrollTo(0) }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            Spacer(Modifier.height(8.dp))
            if (shown.isEmpty()) {
                Text(
                    "Nothing matches \"$needle\"", color = D.Quiet, style = Type.body,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                )
            }
            shown.forEachIndexed { i, s ->
                // A hairline BETWEEN rows, as `divide-y` draws it: none above the first, none
                // below the last.
                if (i > 0) RowLine()
                ServiceRow(s) { onPick(s.lead) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * What was typed into each list's search field, held above the screen with the scroll position.
 *
 * **Why it exists.** Opening a service removes the list from composition, and a `remember`ed
 * query would go with it: the person searched "hot", opened Hotstar, pressed back, and got the
 * whole list at the top again. It is keyed like [ScrollHold] and tied to the very [ScrollState]
 * it was made beside — when `ScrollHold.keepOnly` drops the screen's scroll (Home is back in
 * front) the state object changes and the query starts empty, so a list is never opened at the
 * top with last visit's filter still applied.
 */
private object ShopQueryHold {
    private class Held(val scroll: ScrollState, val query: androidx.compose.runtime.MutableState<String>)

    private val held = mutableMapOf<String, Held>()

    fun query(key: String, scroll: ScrollState): androidx.compose.runtime.MutableState<String> {
        held[key]?.takeIf { it.scroll === scroll }?.let { return it.query }
        return mutableStateOf("").also { held[key] = Held(scroll, it) }
    }
}

/**
 * The "More" list's search field: 48 tall, outlined, a search icon at the start, a clear button
 * at the end once there is something to clear.
 *
 * Plain outlined field, no tints, no fill — the same hairline every other control here uses. The
 * clear target is the full 48 even though its glyph is 20, and the field filters live as you
 * type, so the Search key on the keyboard only has to put the keyboard away.
 */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(shape).border(1.5.dp, D.Line, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(Space.inline))
        HiIcon(Hi.Search, D.Quiet, 20.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("Search", color = D.Quiet, style = Type.body, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Type.body.copy(color = D.Black),
                cursorBrush = SolidColor(D.Blue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable { onChange("") },
                contentAlignment = Alignment.Center,
            ) { HiIcon(Hi.Close, D.Quiet, 20.dp, contentDescription = "Clear search") }
        }
    }
}

/**
 * §3.1's row: a 48 tile, the name, one line of what it is for. The whole row is the tap target.
 *
 * No chevron and no link icon (§3.1). The row's own press scale is the affordance; an arrow on
 * every one of sixty-odd rows was decoration that said nothing the tap did not already say.
 */
@Composable
private fun ServiceRow(service: Shop.Service, onOpen: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Row(
        Modifier.then(PressScale(press)).fillMaxWidth().heightIn(min = 48.dp)
            .clickable(press, indication = null, onClick = onOpen)
            .padding(vertical = Space.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandTile(service.lead, service.name)
        Spacer(Modifier.width(Space.inline))
        Column(Modifier.weight(1f)) {
            Text(
                service.name, color = D.Black, style = Type.body,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // One line of what it is for — §3.4's curated words, so nothing until the server
            // has them. The row is a logo and a name until then rather than a guess.
            service.lead.guide.line?.let {
                Spacer(Modifier.height(Space.tight))
                Text(
                    it, color = D.Quiet, style = Type.caption,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** §3.1's logo: a 48 tile, corner 12, on the brand's ground, hairline outline, 4 of padding. */
@Composable
private fun BrandTile(product: Shop.Product, name: String) {
    val art = rememberLogoArt(product.artUrl)
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.size(48.dp).clip(shape)
            .background(logoGround(art, brandColour(product.guide.brandHex)))
            .border(1.5.dp, D.Line, shape)
            // 4, "never cropped": these are logos of wildly different shapes and a wordmark run
            // to the tile's edges reads as cut off.
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Image(
                art.logo, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            )
        } else {
            // A brand with no logo id, or a fetch that failed. Its initial, not a spinner: the
            // row is readable either way and nothing is pretending to still be on its way.
            Text(name.take(1).uppercase(), color = D.Black, style = Type.heading)
        }
    }
}

// -- §3.2 the service's page -------------------------------------------------------------------

/**
 * §3.2 — a service's page. A **full screen**: its own back-and-title row at the head, and
 * `Buy now` pinned to the foot however long the page runs.
 *
 * Top to bottom: title row · logo · kind · line · tabs · Read before you buy · the facts · how
 * to claim it · sold by Bitrefill · Buy now. "Read before you buy" comes FIRST of the prose,
 * because it is the part that must not be missed.
 *
 * Every block is drawn only if it has something in it. That is §3.2's own rule for the facts
 * ("leave a row out when the data has no value for it") applied to the whole page, which is
 * what it has to be while §3.4's words have no home on the server: an invented validity would
 * be worse than none, and so would an empty card with a heading over it.
 */
@Composable
private fun ServicePage(
    service: Shop.Service,
    startCard: Int,
    email: String?,
    onEmail: () -> Unit,
    usdc: BigDecimal?,
    onBack: () -> Unit,
    onBuy: (Shop.Product, String) -> Unit,
) {
    var card by remember(service.id) { mutableStateOf(startCard) }
    var buying by remember(service.id) { mutableStateOf(false) }
    val chosen = service.cards.getOrNull(card) ?: service.cards.first()
    val product = chosen.product
    val guide = product.guide

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberHeldScroll("shopPage:${service.id}"))) {
            // The back target is a full 48 and the row is pulled 12 back, so the arrow itself
            // lands on the page's start edge with the touch target still intact.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackArrow(onBack)
                Text(
                    service.name, color = D.Black, style = Type.title,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(12.dp))
            PageLogo(product)

            guide.kind?.let {
                Spacer(Modifier.height(16.dp))
                KindBadge(it)
            }
            guide.line?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = D.Black, style = Type.body)
            }

            // The tabs sit ABOVE everything they change — the warnings, the validity, the
            // usage and the prices all move with them.
            if (service.tabbed) {
                Spacer(Modifier.height(24.dp))
                CardTabs(service, card) { card = it }
                chosen.label?.let {
                    Spacer(Modifier.height(12.dp))
                    // "For {what this card covers}." The tab's own noun phrase ("Train tickets")
                    // first, because `line` is the whole product's sentence and reads wrongly
                    // after "For"; then the line, then the tab's words.
                    val what = product.guide.tabLine ?: product.guide.line ?: it
                    Text(
                        "For ${what.trimEnd('.').lowercase()}.",
                        color = D.Quiet, style = Type.caption,
                    )
                }
            }

            if (guide.know.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                ReadFirst(guide.know)
            }

            Facts(guide)

            if (guide.steps.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                MicroLabel("How to claim it")
                Spacer(Modifier.height(Space.lead))
                guide.steps.forEachIndexed { i, step ->
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    Step(i + 1, step)
                }
            }

            // Who the buyer is actually dealing with, and who to ask. Never conditional: it is
            // true of every product in the catalogue and it is the one thing on this page that
            // does not depend on the server having words for it.
            Spacer(Modifier.height(24.dp))
            RowLine()
            Spacer(Modifier.height(Space.row))
            MicroLabel("Sold by Bitrefill")
            Spacer(Modifier.height(Space.lead))
            Text(SOLD_BY, color = D.Quiet, style = Type.caption)

            Spacer(Modifier.height(24.dp))
        }

        // Pinned, so the way forward is there however long the page runs. On the page's own
        // white, so the body scrolls under it rather than showing through.
        Column(Modifier.fillMaxWidth().background(D.White).padding(top = 12.dp, bottom = 8.dp)) {
            PrimaryButton("Buy now", { buying = true })
        }
    }

    if (buying) {
        BuySheet(
            service = service,
            card = chosen,
            email = email,
            onEmail = onEmail,
            usdc = usdc,
            onClose = { buying = false },
            onBuy = onBuy,
        )
    }
}

/** The logo panel's height, and the inset the logo sits inside: 88 − 2 × 12 leaves a 64 logo. */
private val PAGE_LOGO_H = 88.dp
private val PAGE_LOGO_PAD_H = 16.dp
private val PAGE_LOGO_PAD_V = 12.dp

/**
 * §3.2's logo: a full-width panel, 88 tall, with the logo centred and fitted inside 16 × 12 of
 * ground — never cropped, never stretched.
 *
 * **Why a panel and not the logo bare.** The first version drew the logo 48 tall at its natural
 * width on the page's white, and that fails three ways at once. A square mark is 48 wide and a
 * wide wordmark is only as tall as its letters — Himalaya (302 × 168) came out about 86 wide and
 * Disney+ Hotstar's lettering about 20 tall, both read as "small". A white logo (Tinder's) has
 * nothing to show against white. And a mid-tone brand colour fooled the dark/light test (see
 * [logoGround]). A fixed panel fixes all three with one rule: the logo is fitted into the same
 * 64-tall, width-bounded box whatever its shape, so wordmarks run wide and squares stay square,
 * and the panel's ground is chosen by [logoGround] so the logo is always legible on it.
 *
 * The logo is the full-resolution decode (`full = true`): the 128 px one the list tiles use is a
 * quarter of what this box needs and would draw soft.
 */
@Composable
private fun PageLogo(product: Shop.Product) {
    val art = rememberLogoArt(product.artUrl, full = true)
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.fillMaxWidth().height(PAGE_LOGO_H).clip(shape)
            .background(logoGround(art, brandColour(product.guide.brandHex)))
            .border(1.5.dp, D.Line, shape)
            .padding(horizontal = PAGE_LOGO_PAD_H, vertical = PAGE_LOGO_PAD_V),
        contentAlignment = Alignment.Center,
    ) {
        art?.let {
            Image(
                it.logo, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            )
        }
    }
}

/**
 * §3.2's tabs, for a brand that sells several cards: a sideways row of pills, 48 tall, the
 * chosen one `D.Ink` filled with white text and the others outlined.
 */
@Composable
private fun CardTabs(service: Shop.Service, chosen: Int, onPick: (Int) -> Unit) {
    Row(
        // No `fillMaxWidth`: the scroll hands its content an unbounded width, and a row told to
        // fill the screen as well has two answers to how wide it is.
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        service.cards.forEachIndexed { i, c ->
            val on = i == chosen
            val press = remember { MutableInteractionSource() }
            Box(
                Modifier.then(PressScale(press)).heightIn(min = 48.dp).clip(CircleShape)
                    .then(if (on) Modifier.background(D.Black) else Modifier.border(1.5.dp, D.Line, CircleShape))
                    .clickable(press, indication = null) { onPick(i) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    c.label ?: c.product.name,
                    color = if (on) D.White else D.Black, style = Type.body, maxLines = 1,
                )
            }
        }
        // The same gutter after the last pill as before the first, so a scrolled row does not
        // end dead against the screen edge.
        Spacer(Modifier.width(Space.gutter))
    }
}

/**
 * §3.2's "Read before you buy": one card, corner 16, hairline outline, 16 of padding, with the
 * heading on the marker and the warnings as bullets.
 *
 * No icon and no heavy border (§3.2): the marker on the heading IS the emphasis, and a thick
 * edge with a lone "!" in it was noise around the thing it was meant to point at.
 */
@Composable
private fun ReadFirst(know: List<String>) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.5.dp, D.Line, shape).padding(16.dp)) {
        MarkedHeading(READ_FIRST)
        Spacer(Modifier.height(Space.lead))
        know.forEachIndexed { i, k ->
            if (i > 0) Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                // The dot sits on the first line's optical middle rather than its top.
                Box(Modifier.padding(top = 8.dp).size(6.dp).background(D.Black, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(k, color = D.Black, style = Type.body)
            }
        }
    }
}

/**
 * §3.2's facts: `Where it works`, `Valid for`, `How it is used`. No heading, 12 apart.
 *
 * **A row is left out when the data has no value for it** — §3.2's own instruction, and the one
 * that matters most while §3.4's words are missing: an invented validity would be worse than
 * none. With all three empty the block is not drawn, and neither is its leading gap.
 */
@Composable
private fun Facts(guide: Shop.Guide) {
    val rows = listOfNotNull(
        guide.where?.let { WHERE_LABEL to it },
        guide.valid?.let { VALID_LABEL to it },
        guide.uses?.let { USES_LABEL to it },
    )
    if (rows.isEmpty()) return
    Spacer(Modifier.height(24.dp))
    rows.forEachIndexed { i, (label, value) ->
        if (i > 0) Spacer(Modifier.height(Space.lead))
        Text(label, color = D.Quiet, style = Type.caption)
        Spacer(Modifier.height(Space.tight))
        Text(value, color = D.Black, style = Type.body)
    }
}

/** One numbered claim step: a 24 outlined disc with the number, the step in body. */
@Composable
private fun Step(n: Int, step: String) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier.size(24.dp).border(1.5.dp, D.Line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Tabular figures, so step 9 and step 10 sit the same way inside the disc.
            Text(n.toString(), color = D.Black, style = Type.caption.copy(fontFeatureSettings = "tnum"))
        }
        Spacer(Modifier.width(Space.inline))
        Text(step, color = D.Black, style = Type.body)
    }
}

// -- §3.3 the buy sheet ------------------------------------------------------------------------

/**
 * §3.3 — the buy sheet, over the page. A [Tray], at most 88% of the screen.
 *
 * Drawn in a [Dialog] because this composable is nested inside the host's padded, inset content
 * column: a `Tray`'s own `fillMaxSize` would only fill that column's slot, inset by the gutter,
 * rather than the window. `StreakSheet` in RewardsScreen.kt opens the same way, for the same
 * reason, and the two `DialogProperties` and the zeroed dim amount are all load-bearing — see
 * its note.
 */
@Composable
private fun BuySheet(
    service: Shop.Service,
    card: Shop.Card,
    email: String?,
    onEmail: () -> Unit,
    usdc: BigDecimal?,
    onClose: () -> Unit,
    onBuy: (Shop.Product, String) -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            // The Tray draws its own scrim and animates itself out when it is tapped. Letting
            // the window dismiss as well would skip that.
            dismissOnClickOutside = false,
        ),
    ) {
        val host = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { host?.setDimAmount(0f) }

        Tray(onClose = onClose) {
            // 88% of the screen, and no more: the sheet is allowed to be long for a brand with
            // eleven price points, but never a second full screen on top of a full screen.
            val tallest = LocalConfiguration.current.screenHeightDp.dp * 0.88f
            Column(Modifier.heightIn(max = tallest)) {
                Amounts(service, card, email, onEmail, usdc) { value ->
                    onBuy(card.product, value)
                }
            }
        }
    }
}

/**
 * The buy sheet's content: the balance, where the code goes, the price points, what an amount
 * means, and Pay.
 *
 * **Every box can be chosen whatever the balance** (§3.3). No box is greyed and none is
 * labelled as unaffordable — what the balance cannot cover is said once, on the button, and the
 * disabled button is the ordinary disabled look rather than a refusal in red.
 */
@Composable
private fun ColumnScope.Amounts(
    service: Shop.Service,
    card: Shop.Card,
    email: String?,
    onEmail: () -> Unit,
    usdc: BigDecimal?,
    onPay: (String) -> Unit,
) {
    val product = card.product
    // The denominations, the way every other shop surface reads them: the packages when there
    // are any, the bare values when there are not.
    val prices = remember(product.id) {
        product.packages.ifEmpty { product.values.map { Shop.Package(it, 0) } }
    }
    var chosen by remember(product.id) { mutableStateOf<Int?>(null) }
    val pick = chosen?.let { prices.getOrNull(it) }
    // What it costs in USDC — an estimate, from the server (see [Shop.Package.usdEstimate]). Null
    // where it has none, in which case the button cannot claim a price and does not.
    val cost = pick?.usdPrice?.toBigDecimalOrNull()
    val short = notEnough(cost, usdc, pick?.usdEstimate == true)

    // The sheet is two parts: everything that can be long scrolls in the middle, and the button is
    // pinned beneath it. With one scroll over the whole sheet, a brand with many price points
    // pushed Pay off the bottom and it had to be scrolled to — the one control that matters, and
    // the one that must always be in reach. `weight(fill = false)` lets the scroll area be as
    // short as its content when there is little of it, so a short sheet is still short.
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth()) {
        Text(
            "Choose an amount", color = D.Black, style = Type.heading,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
        // The balance beside the question it qualifies. Nothing at all when it is unknown: a
        // blank where a figure goes is better than a figure that might be wrong.
        usdc?.let { b ->
            usdcLabel(b)?.let {
                Spacer(Modifier.width(Space.inline))
                Text(
                    "Balance $it", color = D.Quiet,
                    style = Type.caption.copy(fontFeatureSettings = "tnum"),
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
    Spacer(Modifier.height(Space.tight))
    Text(
        service.name + (card.label?.let { " · $it" } ?: ""),
        color = D.Quiet, style = Type.caption,
    )

    // Where the code goes — FIRST, so a wrong address is noticed before an amount is chosen.
    Spacer(Modifier.height(Space.lead))
    RowLine()
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // No email set is not a failure: the code is shown in the app either way, and
            // nobody should have to hand over an address to spend their own money.
            Text(
                if (email == null) "The code is shown here only" else "The code is sent to",
                color = D.Quiet, style = Type.caption,
            )
            Spacer(Modifier.height(Space.tight))
            Text(
                email ?: "Add an email for a copy",
                color = if (email == null) D.BlueInk else D.Black, style = Type.body,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Space.inline))
        ChangeLink(if (email == null) "Add" else "Change", onEmail)
    }
    RowLine()

    Spacer(Modifier.height(16.dp))
    // Two per row, 12 apart. Chunked rather than a lazy grid: this sheet already scrolls, and a
    // lazy grid inside a scrollable column has no height to measure against.
    prices.chunked(2).forEachIndexed { r, pair ->
        if (r > 0) Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEachIndexed { c, p ->
                val i = r * 2 + c
                PriceBox(p, product.currency, i == chosen, Modifier.weight(1f)) { chosen = i }
            }
            // An odd last box keeps its half of the row rather than stretching across it.
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }

    // What an amount means — directly above the button it explains. §3.4's words again, so
    // nothing until the server has them; §13 notes Bitrefill does not say which plan Hotstar's
    // and Tinder's price points buy, which is exactly what this sentence exists to admit.
    Spacer(Modifier.height(24.dp))
    val means = product.guide.amounts
    if (means != null) {
        Text(means, color = D.Black, style = Type.body)
        Spacer(Modifier.height(Space.tight))
    }
    Text(ALSO_IN_APP, color = D.Quiet, style = Type.caption)
    }

    Spacer(Modifier.height(12.dp))
    PrimaryButton(
        label = when {
            short -> "Not enough USDC"
            // A price to say, and the balance covers it. Only an EXACT price goes on the button:
            // an estimate is in the box ("about 5.49 USDC") and the button stays a bare Pay
            // until the invoice returns the real figure, which `buy()` checks against the
            // balance again before anything is signed.
            cost != null && pick?.usdEstimate != true -> usdcLabel(cost)?.let { "Pay $it" } ?: "Pay"
            else -> "Pay"
        },
        onClick = { pick?.let { onPay(it.value) } },
        // Disabled until an amount is chosen, and disabled when the balance cannot cover a
        // price we actually know. An unknown price is not a reason to refuse: `buy()` prices
        // the order on the invoice and refuses there, honestly, with the real figure.
        enabled = pick != null && !short,
    )
    Spacer(Modifier.height(8.dp))
}

/**
 * One price point: the face value, what it costs in USDC under it, and a tick when it is chosen.
 *
 * The chosen box takes a 2 ink outline and a 20 ink disc with a white tick; the rest keep the
 * hairline. **Nothing here says a box is out of reach** (§3.3) — the box itself looks like any
 * other whatever the balance.
 */
@Composable
private fun PriceBox(
    pack: Shop.Package,
    currency: String,
    on: Boolean,
    modifier: Modifier,
    onPick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.then(PressScale(press)).clip(shape)
            .border(if (on) 2.dp else 1.5.dp, if (on) D.Black else D.Line, shape)
            .clickable(press, indication = null, onClick = onPick),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                // The face value whole, through Money — never a symbol concatenated here. A
                // value Money cannot write (an unknown currency code, or a bundle whose value
                // is a sentence) falls back to the value as the server wrote it.
                pack.price.toBigDecimalOrNull()?.let { faceValue(it, currency) } ?: pack.value,
                color = D.Black, style = Type.title.figure(),
            )
            // What it costs, in the token it is paid with — null until the server sends one.
            costLabel(pack)?.let {
                Spacer(Modifier.height(Space.tight))
                Text(
                    it, color = D.Quiet,
                    style = Type.caption.copy(fontFeatureSettings = "tnum"),
                )
            }
        }
        if (on) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(12.dp).size(20.dp)
                    .background(D.Black, CircleShape),
                contentAlignment = Alignment.Center,
            ) { HiIcon(Hi.Tick, D.White, 12.dp, disc = true) }
        }
    }
}
