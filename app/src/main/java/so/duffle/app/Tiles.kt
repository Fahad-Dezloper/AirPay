package so.duffle.app

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * §4 "Pay a bill" and §5 "Offers and rewards" — one tile, two rows.
 *
 * Lifted out of MainActivity.kt so this section can be worked on without touching a 5,600-line
 * file. What the tiles DRAW is ui/PORT-2026-10-01.md §5.
 */

/**
 * Rewards, offers, referrals — three ways in, side by side.
 *
 * Drawn rather than iconed, and square rather than round, because the obvious version of this
 * row is three flat circles and that is a shape people have already seen in every payments app.
 * The marks are built from AirPay's own vocabulary: the discs of a balance, the notch of a
 * ticket, two rings meeting the way the avatars do on a tap.
 *
 * These three keep the solid blue [MarkCanvas] shapes while §4 moves to the two-colour line
 * icons — whether they get redrawn in that style is an open question (PORT-2026-10-01 §7), so
 * only the box grew to 48 with everything else.
 */
@Composable
internal fun EarnTiles(onRewards: () -> Unit, onOffers: () -> Unit, onReferrals: () -> Unit) {
    // 20, not 10. With the tile's grey body gone, the gap is the only thing saying where one
    // tile ends: 8 inside against 20 outside is x2.5, clear of the x2 the eye needs before it
    // groups a label with its own mark instead of the one below it.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        EarnTile("Rewards", Mark.Coins, Modifier.weight(1f), onRewards)
        EarnTile("Offers", Mark.Ticket, Modifier.weight(1f), onOffers)
        EarnTile("Referrals", Mark.Rings, Modifier.weight(1f), onReferrals)
    }
}

/** The side of every tile's icon box — line icon, brand image and drawn mark alike. */
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
        sections = listOf("TV & streaming", "Streaming", "TV"),
    ),
    BillTile("Cinema", R.drawable.ic_movie, brand = false, sections = listOf("Cinema")),
    BillTile(
        "PhonePe", R.drawable.phonepe, brand = true,
        sections = listOf("Bills & wallets", "Bills"),
    ),
    BillTile("Food", R.drawable.ic_meal, brand = false, sections = listOf("Food")),
    BillTile("Shopping", R.drawable.ic_bag, brand = false, sections = listOf("Shopping")),
    BillTile("MakeMyTrip", R.drawable.makemytrip, brand = true, sections = listOf("Travel")),
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
@Composable
internal fun ShopTiles(sections: List<Shop.Section>, onOpen: (Shop.Section) -> Unit) {
    val stocked = sections.filter { it.products.isNotEmpty() }
    val shown = BILL_TILES.mapNotNull { tile -> tile.shelf(stocked)?.let { tile to it } }

    // More is the rest of the catalogue, flattened into one shelf. It is built here rather than
    // routed to a "More" section on the server, because what is left over depends on which of
    // the seven above found a shelf — and Games, or anything the server adds later, must not
    // vanish just because it lost its own tile. De-duplicated by product id: two shelves can
    // list the same voucher.
    val spoken = shown.map { it.second.title }.toSet()
    val rest = stocked.filterNot { it.title in spoken }
    val more = rest.takeIf { it.isNotEmpty() }?.let { left ->
        BillTile("More", R.drawable.ic_more, brand = false, sections = emptyList()) to
            Shop.Section("More", left.flatMap { it.products }.distinctBy { p -> p.id })
    }

    // 20 on both axes, for the reason [EarnTiles] gives: the gap is what groups a tile now
    // that no box does.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // Four to a row, and the last row padded with empty weights rather than stretched —
        // three wide tiles beside four narrow ones is the giveaway that a grid is faked.
        (shown + listOfNotNull(more)).chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { (tile, shelf) ->
                    Tile(tile.label, Modifier.weight(1f), onClick = { onOpen(shelf) }) {
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
 * **the icon carries the tile** now, which is why a drawn mark keeps its blue FILL: a drawn
 * mark is one of the three places a fill still belongs, and in §5 it is the only colour in the
 * row. The 18dp corner stays even with nothing to fill, because it is the shape the press
 * scales.
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
        modifier.then(PressScale(interaction)).clip(RoundedCornerShape(18.dp))
            .clickable(interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(Modifier.height(8.dp))
        // Two lines, centred, and the tile grows to hold them. The sandbox's note assumes
        // "one 18px caption line", which is true of every label but "MakeMyTrip" — that one
        // wraps in a quarter-width column, and the second line was being cut off. §8 of
        // ui/PORT.md: text grows and nothing clips. Centred, so a wrapped label still sits
        // under its own mark rather than hanging to one side of it.
        Text(
            label, color = D.Black, style = Type.caption,
            textAlign = TextAlign.Center, maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** §5's tile: the same [Tile] with a drawn [Mark] in its icon box. */
@Composable
internal fun EarnTile(label: String, mark: Mark, modifier: Modifier, onClick: () -> Unit) {
    Tile(label, modifier, onClick) { MarkCanvas(mark, ICON_BOX) }
}
