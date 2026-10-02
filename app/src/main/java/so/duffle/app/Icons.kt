package so.duffle.app

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * Every line icon the app draws, rebuilt so its stroke can follow the text beside it.
 *
 * ui/PORT.md §5 asks for "the seven icons the app uses". The app uses NINETEEN — and the most
 * common of them, `hi_verified` at 11 call sites, is not among the seven. Porting seven would
 * have left two stroke weights on one screen, which reads worse than one wrong weight
 * everywhere, so all nineteen are here.
 *
 * They ship as `res/drawable/hi_*.xml`, and every one of those hard-codes
 * `android:strokeWidth="1.5"` across its paths. A drawable cannot vary that by size, so a 16dp
 * icon and a 40dp icon were drawn with the same absolute stroke on different grids — the small
 * one heavy against its caption, the large one spidery inside a status disc.
 *
 * [markStroke] decides the weight (ui/PORT.md §5): 1.5px on screen beside a caption, 2px above
 * it, and 2.75 on the 24-unit grid inside a status disc, where the icon carries the meaning a
 * colour-blind person reads and has to hold at a glance.
 *
 * The `pathData` is copied verbatim from the drawables, so these are the same shapes — Hugeicons
 * stroke-rounded, free set — and not redrawn approximations. The XML files stay: they are still
 * the source, and anything that has not moved to [HiIcon] yet still uses them.
 */
internal enum class Hi {
    Tick, Bell, Info, Copy, Close, Scan, Nfc,
    Verified, ArrowUpRight, Wallet, ArrowLeft, ArrowRight, Gift, ArrowDown, Alert, Search,
    LinkOut, Exchange, Clock, Backspace,
}

/**
 * The arrows that mean "forward", which have to point the other way in Arabic or Hebrew.
 *
 * `ArrowDown` is not here: down is down in every script. Nor is `Exchange`, whose two arrows
 * already point both ways — mirroring it would produce the same drawing.
 */
private val MIRROR = setOf(Hi.ArrowUpRight, Hi.ArrowLeft, Hi.ArrowRight, Hi.LinkOut)

/** One path, and whether it is filled rather than stroked. */
private data class P(val d: String, val filled: Boolean)

private val PATHS: Map<Hi, List<P>> = mapOf(
    Hi.Tick to listOf(
        P("M5 14L8.5 17.5L19 6.5", false),
    ),
    Hi.Bell to listOf(
        P("M2.52992 14.394C2.31727 15.7471 3.268 16.6862 4.43205 17.1542C8.89481 18.9486 15.1052 18.9486 19.5679 17.1542C20.732 16.6862 21.6827 15.7471 21.4701 14.394C21.3394 13.5625 20.6932 12.8701 20.2144 12.194C19.5873 11.2975 19.525 10.3197 19.5249 9.27941C19.5249 5.2591 16.1559 2 12 2C7.84413 2 4.47513 5.2591 4.47513 9.27941C4.47503 10.3197 4.41272 11.2975 3.78561 12.194C3.30684 12.8701 2.66061 13.5625 2.52992 14.394Z", false),
        P("M9 21C9.79613 21.6219 10.8475 22 12 22C13.1525 22 14.2039 21.6219 15 21", false),
    ),
    Hi.Info to listOf(
        P("M2.0,12.0a10.0,10.0 0 1,0 20.0,0a10.0,10.0 0 1,0 -20.0,0", false),
        P("M12 16V12", false),
        P("M12.125 8.25H12M12.25 8.25C12.25 8.11193 12.1381 8 12 8C11.8619 8 11.75 8.11193 11.75 8.25C11.75 8.38807 11.8619 8.5 12 8.5C12.1381 8.5 12.25 8.38807 12.25 8.25Z", false),
    ),
    Hi.Copy to listOf(
        P("M7.5 14.5C7.5 11.2002 7.5 9.55025 8.52513 8.52513C9.55025 7.5 11.2002 7.5 14.5 7.5C17.7998 7.5 19.4497 7.5 20.4749 8.52513C21.5 9.55025 21.5 11.2002 21.5 14.5C21.5 17.7998 21.5 19.4497 20.4749 20.4749C19.4497 21.5 17.7998 21.5 14.5 21.5C11.2002 21.5 9.55025 21.5 8.52513 20.4749C7.5 19.4497 7.5 17.7998 7.5 14.5Z", false),
        P("M7.5 16.5C6.10355 16.5 5.40533 16.5 4.84402 16.3036C3.83866 15.9518 3.0482 15.1613 2.69641 14.156C2.5 13.5947 2.5 12.8964 2.5 11.5V9.5C2.5 6.20017 2.5 4.55025 3.52513 3.52513C4.55025 2.5 6.20017 2.5 9.5 2.5H11.5C12.8964 2.5 13.5947 2.5 14.156 2.69641C15.1613 3.0482 15.9518 3.83866 16.3036 4.84402C16.5 5.40533 16.5 6.10355 16.5 7.5", false),
    ),
    Hi.Close to listOf(
        P("M18 6L6.00081 17.9992M17.9992 18L6 6.00085", false),
    ),
    Hi.Scan to listOf(
        P("M3 9V6.5C3 4.57 4.57 3 6.5 3H9", false),
        P("M15 3H17.5C19.43 3 21 4.57 21 6.5V9", false),
        P("M21 15V17.5C21 19.43 19.43 21 17.5 21H15", false),
        P("M9 21H6.5C4.57 21 3 19.43 3 17.5V15", false),
        P("M3.5 12H20.5", false),
    ),
    Hi.Nfc to listOf(
        P("M15.5 3C17.2934 4.79648 18.5 8.15423 18.5 12C18.5 15.8458 17.2934 19.2035 15.5 21", false),
        P("M12.5 5C13.6956 6.39726 14.5 9.00885 14.5 12C14.5 14.9912 13.6956 17.6027 12.5 19", false),
        P("M9 7C9.8967 7.99804 10.5 9.86346 10.5 12C10.5 14.1365 9.8967 16.002 9 17", false),
        P("M5.5 9C6.0978 9.59883 6.5 10.7181 6.5 12C6.5 13.2819 6.0978 14.4012 5.5 15", false),
    ),
    Hi.Verified to listOf(
        P("M18.9905 19H19M18.9905 19C18.3678 19.6175 17.2393 19.4637 16.4479 19.4637C15.4765 19.4637 15.0087 19.6537 14.3154 20.347C13.7251 20.9374 12.9337 22 12 22C11.0663 22 10.2749 20.9374 9.68457 20.347C8.99128 19.6537 8.52349 19.4637 7.55206 19.4637C6.76068 19.4637 5.63218 19.6175 5.00949 19C4.38181 18.3776 4.53628 17.2444 4.53628 16.4479C4.53628 15.4414 4.31616 14.9786 3.59938 14.2618C2.53314 13.1956 2.00002 12.6624 2 12C2.00001 11.3375 2.53312 10.8044 3.59935 9.73817C4.2392 9.09832 4.53628 8.46428 4.53628 7.55206C4.53628 6.76065 4.38249 5.63214 5 5.00944C5.62243 4.38178 6.7556 4.53626 7.55208 4.53626C8.46427 4.53626 9.09832 4.2392 9.73815 3.59937C10.8044 2.53312 11.3375 2 12 2C12.6625 2 13.1956 2.53312 14.2618 3.59937C14.9015 4.23907 15.5355 4.53626 16.4479 4.53626C17.2393 4.53626 18.3679 4.38247 18.9906 5C19.6182 5.62243 19.4637 6.75559 19.4637 7.55206C19.4637 8.55858 19.6839 9.02137 20.4006 9.73817C21.4669 10.8044 22 11.3375 22 12C22 12.6624 21.4669 13.1956 20.4006 14.2618C19.6838 14.9786 19.4637 15.4414 19.4637 16.4479C19.4637 17.2444 19.6182 18.3776 18.9905 19Z", false),
        P("M9 12.8929C9 12.8929 10.2 13.5447 10.8 14.5C10.8 14.5 12.6 10.75 15 9.5", false),
    ),
    Hi.ArrowUpRight to listOf(
        P("M9 6.65032C9 6.65032 15.9383 6.10759 16.9154 7.08463C17.8924 8.06167 17.3496 15 17.3496 15M16.5 7.5L6.5 17.5", false),
    ),
    Hi.Wallet to listOf(
        P("M14 3H5C3.89543 3 3 3.89543 3 5C3 6.10457 3.89543 7 5 7H18C18 6.07003 18 5.60504 17.8978 5.22354C17.6204 4.18827 16.8117 3.37962 15.7765 3.10222C15.395 3 14.93 3 14 3Z", false),
        P("M3 5V15C3 17.8284 3 19.2426 3.87868 20.1213C4.75736 21 6.17157 21 9 21H15C17.8284 21 19.2426 21 20.1213 20.1213C21 19.2426 21 17.8284 21 15V13C21 10.1716 21 8.75736 20.1213 7.87868C19.2426 7 17.8284 7 15 7H7", false),
        P("M21 12H19C18.535 12 18.3025 12 18.1118 12.0511C17.5941 12.1898 17.1898 12.5941 17.0511 13.1118C17 13.3025 17 13.535 17 14C17 14.465 17 14.6975 17.0511 14.8882C17.1898 15.4059 17.5941 15.8102 18.1118 15.9489C18.3025 16 18.535 16 19 16H21", false),
    ),
    Hi.ArrowRight to listOf(
        P("M9.00005 6C9.00005 6 15 10.4189 15 12C15 13.5812 9 18 9 18", false),
    ),
    Hi.ArrowLeft to listOf(
        P("M15 6C15 6 9.00001 10.4189 9 12C8.99999 13.5812 15 18 15 18", false),
    ),
    Hi.Gift to listOf(
        P("M4 11V15C4 18.2998 4 19.9497 5.02513 20.9749C6.05025 22 7.70017 22 11 22H13C16.2998 22 17.9497 22 18.9749 20.9749C20 19.9497 20 18.2998 20 15V11", false),
        P("M3 9C3 8.25231 3 7.87846 3.20096 7.6C3.33261 7.41758 3.52197 7.26609 3.75 7.16077C4.09808 7 4.56538 7 5.5 7H18.5C19.4346 7 19.9019 7 20.25 7.16077C20.478 7.26609 20.6674 7.41758 20.799 7.6C21 7.87846 21 8.25231 21 9C21 9.74769 21 10.1215 20.799 10.4C20.6674 10.5824 20.478 10.7339 20.25 10.8392C19.9019 11 19.4346 11 18.5 11H5.5C4.56538 11 4.09808 11 3.75 10.8392C3.52197 10.7339 3.33261 10.5824 3.20096 10.4C3 10.1215 3 9.74769 3 9Z", false),
        P("M6 3.78571C6 2.79949 6.79949 2 7.78571 2H8.14286C10.2731 2 12 3.7269 12 5.85714V7H9.21429C7.43908 7 6 5.56091 6 3.78571Z", false),
        P("M18 3.78571C18 2.79949 17.2005 2 16.2143 2H15.8571C13.7269 2 12 3.7269 12 5.85714V7H14.7857C16.5609 7 18 5.56091 18 3.78571Z", false),
        P("M12 11L12 22", false),
    ),
    Hi.ArrowDown to listOf(
        P("M18 9.00005C18 9.00005 13.5811 15 12 15C10.4188 15 6 9 6 9", false),
    ),
    Hi.Alert to listOf(
        P("M13.9248 21H10.0752C5.44476 21 3.12955 21 2.27636 19.4939C1.42317 17.9879 2.60736 15.9914 4.97574 11.9985L6.90057 8.75333C9.17559 4.91778 10.3131 3 12 3C13.6869 3 14.8244 4.91777 17.0994 8.75332L19.0243 11.9985C21.3926 15.9914 22.5768 17.9879 21.7236 19.4939C20.8704 21 18.5552 21 13.9248 21Z", false),
        P("M12 9V13", false),
        P("M12.125 16.75H12M12.25 16.75C12.25 16.8881 12.1381 17 12 17C11.8619 17 11.75 16.8881 11.75 16.75C11.75 16.6119 11.8619 16.5 12 16.5C12.1381 16.5 12.25 16.6119 12.25 16.75Z", false),
    ),
    Hi.Search to listOf(
        P("M11 19C15.42 19 19 15.42 19 11C19 6.58 15.42 3 11 3C6.58 3 3 6.58 3 11C3 15.42 6.58 19 11 19Z", false),
        P("M21 21L17 17", false),
    ),
    Hi.LinkOut to listOf(
        P("M11.0991 3.00012C7.45013 3.00669 5.53932 3.09629 4.31817 4.31764C3.00034 5.63568 3.00034 7.75704 3.00034 11.9997C3.00034 16.2424 3.00034 18.3638 4.31817 19.6818C5.63599 20.9999 7.75701 20.9999 11.9991 20.9999C16.241 20.9999 18.3621 20.9999 19.6799 19.6818C20.901 18.4605 20.9906 16.5493 20.9972 12.8998", false),
        P("M20.556 3.49612L11.0487 13.0586M20.556 3.49612C20.062 3.00151 16.7343 3.04761 16.0308 3.05762M20.556 3.49612C21.05 3.99074 21.0039 7.32273 20.9939 8.02714", false),
    ),
    Hi.Exchange to listOf(
        P("M16.9767 19.5C19.4017 17.8876 21 15.1305 21 12C21 7.02944 16.9706 3 12 3C11.3126 3 10.6432 3.07706 10 3.22302M16.9767 19.5V16M16.9767 19.5H20.5M7 4.51555C4.58803 6.13007 3 8.87958 3 12C3 16.9706 7.02944 21 12 21C12.6874 21 13.3568 20.9229 14 20.777M7 4.51555V8M7 4.51555H3.5", false),
    ),
    Hi.Clock to listOf(
        P("M2.0,12.0a10.0,10.0 0 1,0 20.0,0a10.0,10.0 0 1,0 -20.0,0", false),
        P("M12 8V12L14 14", false),
    ),
    Hi.Backspace to listOf(
        P("M9.2,5.5H18a3,3 0 0 1 3,3v7a3,3 0 0 1 -3,3H9.2a2,2 0 0 1 -1.5,-0.7L3.4,12.6a1,1 0 0 1 0,-1.2L7.7,6.2A2,2 0 0 1 9.2,5.5Z", false),
        P("M11.5,9.5L16,14.5M16,9.5L11.5,14.5", false),
    ),
)

/**
 * Builds the vector at this size.
 *
 * Remembered on (icon, size, disc) because the stroke is baked into the vector — the width is a
 * property of the path, not something a caller can tint afterwards — so a new size means a new
 * vector. Colour is not baked in: [HiIcon] tints, so one vector serves every colour.
 */
@Composable
private fun vectorFor(icon: Hi, size: Dp, disc: Boolean): ImageVector {
    val stroke = markStroke(size, disc)
    return remember(icon, size, disc) {
        ImageVector.Builder(
            name = icon.name,
            defaultWidth = size, defaultHeight = size,
            viewportWidth = 24f, viewportHeight = 24f,
            autoMirror = icon in MIRROR,
        ).apply {
            PATHS.getValue(icon).forEach { p ->
                if (p.filled) {
                    addPath(addPathNodes(p.d), fill = SolidColor(Color.Black))
                } else {
                    addPath(
                        pathData = addPathNodes(p.d),
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = stroke,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
        }.build()
    }
}

/**
 * A line icon whose stroke matches the text beside it.
 *
 * The drop-in replacement for `HIcon`, which draws the fixed-stroke drawables. `disc = true`
 * is for an icon sitting inside a filled status disc.
 */
@Composable
internal fun HiIcon(
    icon: Hi,
    tint: Color,
    size: Dp = 24.dp,
    disc: Boolean = false,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) = Icon(
    imageVector = vectorFor(icon, size, disc),
    contentDescription = contentDescription,
    tint = tint,
    modifier = modifier.size(size),
)

/**
 * The drawable a [Hi] was built from.
 *
 * `HIcon` takes an `@DrawableRes Int` because fifteen call sites carry the icon as DATA — a
 * wallet's `iconRes`, a brand's `Look.drawable`, an alert's `icon` — and those ids also point
 * at brand logos, which are pictures and not line icons. Rather than change every one of those
 * types, `HIcon` looks the id up here: a line icon gets the stroke-following vector, anything
 * else falls back to the drawable. So the stroke rule reaches the data-driven sites too.
 */
internal val HI_FOR_RES: Map<Int, Hi> = mapOf(
    R.drawable.hi_tick to Hi.Tick,
    R.drawable.hi_bell to Hi.Bell,
    R.drawable.hi_info to Hi.Info,
    R.drawable.hi_copy to Hi.Copy,
    R.drawable.hi_cancel to Hi.Close,
    R.drawable.hi_scan to Hi.Scan,
    R.drawable.hi_nfc to Hi.Nfc,
    R.drawable.hi_verified to Hi.Verified,
    R.drawable.hi_arrow_up_right to Hi.ArrowUpRight,
    R.drawable.hi_wallet to Hi.Wallet,
    R.drawable.hi_arrow_left to Hi.ArrowLeft,
    R.drawable.hi_gift to Hi.Gift,
    R.drawable.hi_arrow_down to Hi.ArrowDown,
    R.drawable.hi_alert to Hi.Alert,
    R.drawable.hi_search to Hi.Search,
    R.drawable.hi_link_out to Hi.LinkOut,
    R.drawable.hi_exchange to Hi.Exchange,
    R.drawable.hi_clock to Hi.Clock,
    R.drawable.hi_backspace to Hi.Backspace,
)

// ─── The five bill-tile category marks, optically sized ──────────────────────────────────────

/**
 * One of the five two-colour category marks (ui/PORT-2026-10-01-2.md §1.5).
 *
 * These are NOT [Hi] icons and deliberately do not go through [HiIcon]:
 *
 *  - **Both colours are baked into the vector** — an ink outline with one detail in `#008FFF`.
 *    Anything that tints (an `Icon`, a `ColorFilter`) flattens the pair to one colour and
 *    destroys the drawing, so they are painted with `Image` as they ship.
 *  - **Their 0.833 stroke is part of the drawing**, traced from the user's reference sheet at
 *    12 reference px to a grid unit. These five are the standing exception to the [markStroke]
 *    size rule, which is why the stroke scaling with the vector below is expected and not a bug.
 *
 * [ink] is the mark's ink box on its own 24 × 24 grid, width to height, read off the path data
 * in `res/drawable/ic_*.xml` (and `ui/components/ui/Marks.tsx` `TILE_INK`, which carries the
 * same five pairs). The five were traced at ONE scale, so their ink differs a lot — the phone is
 * 10.9 × 17, the ticket 20.8 × 13.3, More 14 × 14 — and dropped into one box the phone and More
 * read a size smaller than the ticket. [unitFor] is the fix.
 *
 * [res] is a `get()` and not a constructor property on purpose: it is the only member that
 * touches `R`, so the sizing arithmetic stays loadable (and testable) on a bare JVM, where
 * Android resources do not exist.
 */
internal enum class CatMark(internal val ink: Pair<Float, Float>) {
    Recharge(10.916f to 16.958f),
    Movie(20.75f to 13.334f),
    Meal(14.584f to 16.916f),
    Bag(16.456f to 16.75f),
    More(14f to 14.084f);

    @get:DrawableRes
    internal val res: Int
        get() = when (this) {
            Recharge -> R.drawable.ic_recharge
            Movie -> R.drawable.ic_movie
            Meal -> R.drawable.ic_meal
            Bag -> R.drawable.ic_bag
            More -> R.drawable.ic_more
        }

    internal companion object {
        /**
         * Category → mark, from ui/PORT-2026-10-01-2.md §1.2. `null` for a payment the user has
         * not filed yet, which gets the dashed "+" of §1.4 instead of a mark.
         *
         * Travel and Bills are absent because they have no line icon yet (§5 "Open points"),
         * so they are not offered in the picker either.
         */
        internal fun forCategory(category: String?): CatMark? = when (category) {
            "Phone" -> Recharge
            "Movies" -> Movie
            "Food" -> Meal
            "Shopping" -> Bag
            "Other" -> More
            else -> null
        }

        /** §1.5: the transaction row's box and optical target. */
        internal val RowBox: Dp = 40.dp
        internal const val ROW_OPTICAL: Float = 26f

        /** §1.5: the category picker's box and optical target (§1.4's 4-column grid). */
        internal val PickerBox: Dp = 48.dp
        internal const val PICKER_OPTICAL: Float = 31f
    }
}

/**
 * dp per grid unit that puts this mark's ink at [optical] — the §1.5 rule, verbatim:
 * scale the vector so `sqrt(inkWidth × inkHeight)` equals the target.
 *
 * **Equal AREA, not equal box.** The geometric mean of the ink box is what reads as "the same
 * size" across a tall shape, a wide one and a square one; matching the bounding box instead
 * would make the ticket look twice the phone's weight. The box the mark sits in is left alone,
 * so nothing in the layout moves.
 */
internal fun CatMark.unitFor(optical: Float): Float {
    val (w, h) = ink
    return optical / sqrt(w * h)
}

/** The size this mark's ink actually ends up drawn at, in dp, width to height. Pinned by test. */
internal fun CatMark.drawnSize(optical: Float): Pair<Float, Float> {
    val u = unitFor(optical)
    return ink.first * u to ink.second * u
}

/**
 * One category mark, drawn at [optical] and centred in a [box]-sized box.
 *
 * The whole 24-unit grid is painted at `24 × unit` dp, which is LARGER than the box — the
 * drawing is scaled, not the frame. That is the point of §1.5: the box stays [box] so every
 * title in a transaction row still starts at the same x, while the ink inside it covers the
 * same area for all five marks. Every one of the five is centred on 12,12 by its own ink box,
 * so centring the grid in the box centres the ink.
 *
 * [requiredSize] and not [androidx.compose.foundation.layout.size]: `size` is coerced into the
 * incoming constraints, which the enclosing box has already fixed at [box] — it would silently
 * clamp the scaled grid back down and undo the whole change. The overhang carries no ink (the
 * widest case, recharge in the picker, reaches 54.7dp of grid for a 25 × 39 inked area), so
 * nothing is cut and the box is not clipped.
 *
 * `Image` and not `Icon`: see [CatMark] — tinting flattens the two-colour drawing.
 */
@Composable
internal fun CategoryMark(
    mark: CatMark,
    box: Dp,
    optical: Float,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Box(modifier.size(box), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(mark.res),
            contentDescription = contentDescription,
            modifier = Modifier.requiredSize((24f * mark.unitFor(optical)).dp),
        )
    }
}

/**
 * The waiting clock — a face drawn on the disc it sits in, and it ticks.
 *
 * ui/PORT-2026-10-02.md §2. There is no ring: the disc behind this IS the face, so everything
 * here is drawn in [tint] (white on a `D.Blue` disc). Twelve dots mark the hours at radius 10 on
 * the 24 grid, the four quarters fatter than the rest; two hands and a centre pin.
 *
 * **It is drawn, not an asset, because it moves.** The long hand holds for about three quarters
 * of a second and then snaps a whole hour with a small overshoot — a clock that sweeps smoothly
 * reads as a spinner, and a spinner says "working", not "waiting". A full turn is 12s; the short
 * hand advances one hour per turn, so it takes 144s to come round.
 *
 * Under 24dp the dots are below a pixel, so they are left off and only the hands and pin are
 * drawn — the sandbox calls that `clockSmall`.
 *
 * [ticking] is false where the clock only illustrates waiting rather than reporting it (the
 * Rewards info sheet's row), and under reduced motion: then it rests at the hour it starts on.
 */
@Composable
internal fun TickingClock(
    size: Dp,
    tint: Color = D.White,
    ticking: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val step = 30f
    val turn = 12_000

    // Hold, then snap. The overshoot is what makes it a tick rather than a slide.
    val minute = if (!ticking) 0f else rememberInfiniteTransition(label = "clock").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = turn
                for (i in 0..11) {
                    val at = i * (turn / 12)
                    (i * step) at at
                    (i * step) at at + 760 using CubicBezierEasing(.3f, 1.9f, .5f, 1f)
                    ((i + 1) * step) at at + (turn / 12)
                }
            },
            RepeatMode.Restart,
        ),
        label = "minute",
    ).value

    // One hour per turn of the long hand, and it jumps rather than creeps, for the same reason.
    val hour = if (!ticking) 120f else 120f + rememberInfiniteTransition(label = "clockHour")
        .animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = turn * 12
                    for (i in 0..11) (i * step) at i * turn
                },
                RepeatMode.Restart,
            ),
            label = "hour",
        ).value

    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val c = Offset(12f * u, 12f * u)
        fun at(deg: Float, len: Float): Offset {
            val r = Math.toRadians((deg - 90f).toDouble())
            return Offset(c.x + (len * u * kotlin.math.cos(r)).toFloat(),
                          c.y + (len * u * kotlin.math.sin(r)).toFloat())
        }
        // Dots only where they are bigger than a pixel.
        if (size >= 24.dp) {
            for (i in 0 until 12) {
                val quarter = i % 3 == 0
                drawCircle(tint, (if (quarter) 1.05f else .65f) * u, at(i * step, 10f))
            }
        }
        val stroke = 1.9f * u
        drawLine(tint, c, at(hour, 4.2f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, c, at(minute, 6.6f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(tint, 1.5f * u, c)
    }
}
