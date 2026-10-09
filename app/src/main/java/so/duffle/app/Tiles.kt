package so.duffle.app

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * §4 "Pay a bill" — a grid of tiles — and §5 "Offers and rewards" — a row of three cards.
 *
 * Lifted out of MainActivity.kt so this section can be worked on without touching a 5,600-line
 * file. What the §4 tiles DRAW is ui/PORT-2026-10-01.md §5; the §5 cards are
 * ui/PORT-2026-10-01-2.md §2.
 */

/**
 * One of §5's three cards: a title, the line under it, and its own illustration.
 *
 * [endPct] and [belowPct] are fractions of the CARD's width and height rather than dp, because
 * the design states them that way and because §5 of ui/PORT-2026-10-01-2.md leaves the 184
 * width open to retuning — a dp inset would drift off the artwork's corner the moment it moves.
 */
private class EarnCard(
    val title: String,
    val line: String,
    @DrawableRes val art: Int,
    val top: Color,
    val bottom: Color,
    /** Inset from the card's END edge, as a fraction of the card's width. */
    val endPct: Float,
    /** How far the artwork is pushed BELOW the bottom edge, as a fraction of the card's height. */
    val belowPct: Float,
)

/**
 * Rewards, Offers, Referrals — the three, in order (ui/PORT-2026-10-01-2.md §2.2).
 *
 * **A sanctioned exception to "no tinted backgrounds, no boxes"** (ui/PROJECT.md §3). These
 * three are the app's only illustrated surfaces, and the tint here is the ILLUSTRATION'S OWN
 * GROUND — not a state, a tier or a severity, which is what the rule exists to stop. So the
 * six colours below are sampled from the artwork and are deliberately **not** palette tokens:
 * they do not belong in Palette.kt and they are not to be reused anywhere else.
 *
 * Quiet grey on the three top colours measures 4.76 / 4.84 / 4.61:1, all clear of 4.5 — which
 * is why the top is HELD for the first 45% of the card (see [EarnCardFace]) and why the tops
 * must not be darkened to "improve" the figure.
 */
private val EARN_CARDS = listOf(
    EarnCard(
        "Rewards", "Earn rewards on every transaction", R.drawable.rewards,
        // The gift box sits on the card's floor and is cut by it, as in the design.
        top = Color(0xFFE8FCEF), bottom = Color(0xFFD0FADD), endPct = .02f, belowPct = .04f,
    ),
    EarnCard(
        "Offers", "Grab the best deals and discounts", R.drawable.offers,
        top = Color(0xFFFEF9E7), bottom = Color(0xFFFEEAB9), endPct = .04f, belowPct = .08f,
    ),
    EarnCard(
        "Referrals", "Invite friends and earn together", R.drawable.referrals,
        // A shade lighter than the design's #EBF0FD: quiet text on that is 4.47:1, under the
        // 4.5 bar.
        top = Color(0xFFEFF3FE), bottom = Color(0xFFDBE4FD), endPct = .02f, belowPct = .10f,
    ),
)

private val EARN_W = 184.dp
private val EARN_H = 180.dp
private val EARN_R = 24.dp
private val EARN_PAD = 16.dp

/**
 * The caption's own ceiling, 2dp inside the 152 the padding already leaves.
 *
 * It is there to make the line BREAK rather than run to the padding's edge: two short lines
 * clear of the artwork read better than one long one ending against it.
 */
private val EARN_LINE = 150.dp

/** The illustration's width, as a fraction of the card: 78%, about 144 at 184. */
private const val EARN_ART = .78f

/**
 * Rewards, offers, referrals — three ways in, side by side.
 *
 * Three illustrated cards, replacing the three small drawn-mark tiles (user's design,
 * 2026-10-01; ui/components/sections/Earn.tsx). Three tiles squeezed across the screen left
 * each of them too narrow for two lines of caption, so the row scrolls instead: at 184 two
 * cards show and the third is cut by the screen edge, which is what says there is more.
 *
 * The heading "Offers and rewards" and its 12 gap are the call site's, not this row's.
 */
@Composable
internal fun EarnTiles(onRewards: () -> Unit, onOffers: () -> Unit, onReferrals: () -> Unit) {
    // Rewards across the whole width, then Offers and Referrals sharing the row below it — all
    // three on screen at once instead of two and a cut-off third behind a scroll.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.inline)) {
        EarnCardFace(EARN_CARDS[0], onRewards, Modifier.fillMaxWidth(), height = 150.dp, art = .46f)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
            EarnCardFace(EARN_CARDS[1], onOffers, Modifier.weight(1f), height = EARN_H, art = EARN_ART)
            EarnCardFace(EARN_CARDS[2], onReferrals, Modifier.weight(1f), height = EARN_H, art = EARN_ART)
        }
    }
}

/** One card: the ground, the two lines, and the artwork behind them. */
@Composable
private fun EarnCardFace(card: EarnCard, onOpen: () -> Unit, modifier: Modifier, height: androidx.compose.ui.unit.Dp, art: Float) {
    val press = remember { MutableInteractionSource() }
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.then(PressScale(press))
            .height(height)
            // The clip lives HERE, on the card itself, because it is the card that is meant to
            // cut the illustration off at its bottom edge.
            .clip(RoundedCornerShape(EARN_R))
            // The top colour is HELD to 45% and only then moves. A plain two-stop gradient
            // would put the mid tone straight behind the caption, and the 4.6:1 was measured
            // on the top colour — the hold is what keeps the words on the colour that passed.
            .background(Brush.verticalGradient(0f to card.top, .45f to card.top, 1f to card.bottom))
            .clickable(press, indication = null, onClick = onOpen),
    ) {
        val w = maxWidth
        // Drawn FIRST, so it sits behind the words: the gift tag's loop and the link bubble
        // pass under the title rather than across it. It runs off the bottom on purpose.
        Image(
            painterResource(card.art),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = -(w * card.endPct), y = height * card.belowPct)
                // Width only: the height then follows the drawable's own ratio, which is what
                // keeps each illustration undistorted at any width.
                .width(w * art),
        )
        Column(Modifier.padding(EARN_PAD)) {
            Text(card.title, color = D.Black, style = Type.heading)
            Spacer(Modifier.height(Space.tight))
            Text(
                card.line, color = D.Quiet, style = Type.caption,
                modifier = Modifier.widthIn(max = EARN_LINE),
            )
        }
    }
}

/** The side of every §4 tile's icon box — line icon and brand image alike. */
private val ICON_BOX = 48.dp

/**
 * One of §4's eight tiles, and which of the server's shelves it is the door to.
 *
 * [sections] is a preference order, not a set: the server has renamed its shelves before
 * ("Streaming" became "TV & streaming"), and a tile that silently disappears when a title
 * changes is worse than one that tries the name it knew second.
 */
private class BillTile(
    val label: String,
    @DrawableRes val icon: Int,
    /**
     * True for the three tiles that are a brand's own app icon rather than a drawn line icon.
     * It changes how the icon is drawn (40 inside the 48 box, clipped), not what it does.
     */
    val brand: Boolean,
    val sections: List<String>,
    /**
     * For a brand tile: the service it is the door to, by [Shop.services]' own key — a product id,
     * or "makemytrip" for the grouped brand. With a page per service, a tile that IS one brand
     * opens that brand's page rather than a list it is the first row of
     * (ui/PORT-2026-10-02-shop.md §5). Null for a shelf tile.
     */
    val service: String? = null,
) {
    fun shelf(stocked: List<Shop.Section>): Shop.Section? = sections.firstNotNullOfOrNull { want ->
        stocked.firstOrNull { it.title.equals(want, ignoreCase = true) }
    }
}

/**
 * The eight, in order (ui/PORT-2026-10-01.md §5.1). The old twelve-wide grid was one tile per
 * shelf, which put the catalogue's own shape on the screen; these are the eight things people
 * actually come here to do, and the shelves that are not one of them sit behind More.
 *
 * Three of them are a BRAND rather than a category, because the brand is what somebody is
 * looking for and the category is our word for it: Hotstar stands for streaming, PhonePe for
 * gift cards and bill wallets, MakeMyTrip for travel. Each still opens the whole shelf it
 * stands for — the brand is the face on the door, not a filter.
 *
 * More is not in this list: it has no shelf of its own, it is built from whatever is left over.
 */
private val BILL_TILES = listOf(
    BillTile("Phone", R.drawable.ic_recharge, brand = false, sections = listOf("Phone")),
    BillTile(
        "Hotstar", R.drawable.hotstar, brand = true,
        sections = listOf("TV & streaming", "Streaming", "TV"), service = "hotstar-india",
    ),
    BillTile("Cinema", R.drawable.ic_movie, brand = false, sections = listOf("Cinema")),
    BillTile(
        "PhonePe", R.drawable.phonepe, brand = true,
        sections = listOf("Bills & wallets", "Bills"), service = "phonepe-india",
    ),
    BillTile("Food", R.drawable.ic_meal, brand = false, sections = listOf("Food")),
    BillTile("Shopping", R.drawable.ic_bag, brand = false, sections = listOf("Shopping")),
    BillTile(
        "MakeMyTrip", R.drawable.makemytrip, brand = true, sections = listOf("Travel"),
        service = "makemytrip",
    ),
)

/**
 * What can actually be bought, as tiles.
 *
 * The catalogue has 156 products in eight sections; a person opening this wants "top up my
 * phone", not a shop. So the sections are the doorway and the products live behind them —
 * and a section with nothing in it is not drawn at all, because a tile that opens an empty
 * shelf is worse than no tile. That is still why a tile can be missing: the eight are fixed,
 * but a tile whose shelf is absent or bare is dropped rather than drawn as a dead end.
 */
/** What the full list is called, and what [ShopSection]'s host tests to know it is not a shelf. */
internal const val MORE_TITLE = "More"

/**
 * Every service, as one list — the page the More tile and Rewards' "See all" both open
 * (ui/PORT-2026-10-02-shop.md §3, §5). [Shop.services] puts the well-known brands first and
 * groups MakeMyTrip's five products into one row.
 *
 * It was the leftovers: whatever the seven named tiles did not claim. With a page per service that
 * made Hotstar and PhonePe reachable only through their tiles and absent from "More", and the
 * sandbox's shop is one list with every service in it. De-duplicated by product id, because two
 * shelves can list the same voucher. Null when nothing is stocked — a door onto an empty list is
 * worse than no door.
 *
 * `internal` and shared because two routes to one page means one function; built twice, they
 * would drift the first time a shelf was added.
 */
internal fun moreSection(sections: List<Shop.Section>): Shop.Section? =
    sections.flatMap { it.products }.distinctBy { it.id }
        .takeIf { it.isNotEmpty() }?.let { Shop.Section(MORE_TITLE, it) }

/**
 * Whether a section is the phone-recharge shelf, which gets its own screen. The full list contains
 * recharge products too, so "has a recharge product" no longer means "is the Phone shelf".
 */
internal fun Shop.Section.isRechargeShelf(): Boolean =
    title != MORE_TITLE && products.any { it.isMobileRecharge }

/**
 * @param onOpen the shelf to show, and the product to open on top of it when the tile is a single
 *   brand. A brand whose product is not in the catalogue (out of stock) opens the full list
 *   instead of an empty page.
 */
@Composable
internal fun ShopTiles(sections: List<Shop.Section>, onOpen: (Shop.Section, Shop.Product?) -> Unit) {
    val stocked = sections.filter { it.products.isNotEmpty() }
    val all = moreSection(sections)
    val allServices = remember(all) { all?.let { Shop.services(it.products) }.orEmpty() }

    // Where each tile goes. A brand tile with its product in stock opens that page straight away;
    // one without falls back to its shelf, and a shelf that is not stocked either is no tile.
    val shown = BILL_TILES.mapNotNull { tile ->
        val brand = tile.service?.let { key -> allServices.firstOrNull { it.id == key }?.lead }
        val shelf = tile.shelf(stocked)
        when {
            brand != null && all != null -> tile to (all to brand)
            shelf != null -> tile to (shelf to null)
            else -> null
        }
    }
    val more = all?.let {
        BillTile("More", R.drawable.ic_more, brand = false, sections = emptyList()) to (it to null)
    }

    // 20 on both axes, and 20 rather than 10: with the tile's grey body gone, the gap is the
    // only thing saying where one tile ends. 8 inside against 20 outside is x2.5, clear of the
    // x2 the eye needs before it groups a label with its own mark instead of the one below it.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // Four to a row, and the last row padded with empty weights rather than stretched —
        // three wide tiles beside four narrow ones is the giveaway that a grid is faked.
        (shown + listOfNotNull(more)).chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { (tile, target) ->
                    Tile(tile.label, Modifier.weight(1f), onClick = { onOpen(target.first, target.second) }) {
                        if (tile.brand) BrandIcon(tile.icon) else LineIcon(tile.icon)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * One of the five two-colour line icons (recharge, movie, meal, bag, more).
 *
 * [Image] and not `Icon`: both colours are baked into the vector — an ink outline with one
 * detail in blue — and anything that tints flattens the pair back to one colour. Their stroke
 * is part of the drawing, so it does not follow the `markStroke` size rule either.
 */
@Composable
private fun LineIcon(@DrawableRes res: Int) {
    Image(painterResource(res), contentDescription = null, modifier = Modifier.size(ICON_BOX))
}

/**
 * A brand's own app icon, 40 inside the 48 box.
 *
 * Smaller than the box on purpose: a line icon's ink only reaches about 36 of its 48, while an
 * app icon is solid square artwork edge to edge, so drawn at the same box it reads a size
 * larger than the icons beside it. The box itself stays 48 so every label in a row sits on one
 * line. Corner 9 is the 22% an app icon is cut at.
 */
@Composable
private fun BrandIcon(@DrawableRes res: Int) {
    Box(Modifier.size(ICON_BOX), contentAlignment = Alignment.Center) {
        Image(
            painterResource(res),
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(9.dp)),
        )
    }
}

/**
 * One tile: an icon, a label, and space around them. Both rows.
 *
 * The grey body is gone, and the padding that held it out went with it (ui/PORT.md §9) —
 * **the icon carries the tile** now. The 18dp corner stays even with nothing to fill, because
 * it is the shape the press scales.
 *
 * The height is the content — 48 + 8 + one 18sp caption line — which clears the 48dp touch
 * minimum on its own, so there is nothing left to pad.
 */
@Composable
private fun Tile(
    label: String,
    modifier: Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.then(PressScale(interaction))
            .clickable(interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(Modifier.height(8.dp))
        // One line, always. "MakeMyTrip" is a hair wider than a quarter of the row: wrapping split
        // the word in two ("MakeMyTri / p"). It is centred under its mark and allowed to overhang
        // the tile into the 20dp gap between tiles. That only works because the tile no longer
        // clips: its rounded `clip` cut the overhang off ("MakeMyTri▯"), and a tile has nothing
        // for a clip to do — there is no indication, and a click is hit-tested by bounds.
        Text(
            label, color = D.Black, style = Type.caption,
            textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Visible,
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally, unbounded = true),
        )
    }
}

