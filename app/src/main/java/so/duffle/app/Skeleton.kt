package so.duffle.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Loading states: the shape of what is coming, so a page is never a blank or a "Checking…".
 *
 * Built here rather than taken from a library: it is one modifier, and the part that matters for
 * smoothness — the sweep is read only in the DRAW phase — is the same few lines whichever way it
 * is packaged. Nothing recomposes while it runs, so a page full of skeletons costs the animation
 * clock and one gradient per block, and the rest of the screen is left alone to load.
 *
 * The colours are the app's own: the hairline grey as the resting block and white as the band that
 * passes over it, so it reads on the white page and needs no new token. On a coloured card (a
 * wallet's) the same shapes are drawn in translucent white instead.
 */
private const val SWEEP_MS = 1_400

/** A block that is not there yet: the resting colour, with a lighter band sweeping across it. */
internal fun Modifier.skeleton(
    shape: Shape = RoundedCornerShape(8.dp),
    base: Color = D.Line,
    band: Color = D.White,
): Modifier = composed {
    val clock = rememberInfiniteTransition(label = "skeleton")
    // `.value` is read inside the draw lambda below, never here: a State read in composition would
    // recompose this block sixty times a second.
    val sweep = clock.animateFloat(
        0f, 1f, infiniteRepeatable(tween(SWEEP_MS, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
    )
    this.clip(shape).drawWithContent {
        val w = size.width.coerceAtLeast(1f)
        // Enters past the left edge and leaves past the right, so there is a beat of plain colour
        // between sweeps instead of a band that is always somewhere.
        val x = (-1f + 3f * sweep.value) * w * 0.66f
        drawRect(base)
        drawRect(
            Brush.linearGradient(
                0f to base, .5f to band, 1f to base,
                start = Offset(x, 0f), end = Offset(x + w * .66f, size.height * .2f),
            ),
        )
    }
}

@Composable
internal fun SkeletonBox(modifier: Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    Box(modifier.skeleton(shape))
}

/** A line of text that is not there yet. Fully round ends, like a bar, not a box. */
@Composable
internal fun SkeletonLine(width: Dp, height: Dp = 12.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(width, height).skeleton(RoundedCornerShape(height / 2)))
}

/** Said once for the whole screen, so a screen reader hears "Loading" and not a row of silent blocks. */
internal fun Modifier.loadingFor(what: String): Modifier = semantics { contentDescription = "Loading $what" }

// ----------------------------------------------------------------------------- the shapes

/** The big balance on Home: one bar the size of the figure. */
@Composable
internal fun BalanceSkeleton() {
    Box(Modifier.size(176.dp, 60.dp).skeleton(RoundedCornerShape(16.dp)))
}

/** "Pay a bill": the two rows of four tiles, so the grid is already where it will be. */
@Composable
internal fun BillTilesSkeleton() {
    Column(Modifier.fillMaxWidth().loadingFor("bills"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        repeat(2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                repeat(4) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(48.dp).skeleton(RoundedCornerShape(12.dp)))
                        Spacer(Modifier.height(8.dp))
                        SkeletonLine(44.dp, 10.dp)
                    }
                }
            }
        }
    }
}

/** The Rewards page below its title: the total, the claim row, the cards, the week. */
@Composable
internal fun RewardsSkeleton() {
    Column(Modifier.fillMaxWidth().loadingFor("rewards")) {
        Spacer(Modifier.height(24.dp))
        SkeletonBox(Modifier.fillMaxWidth().height(168.dp), RoundedCornerShape(24.dp))
        Spacer(Modifier.height(Space.lead))
        SkeletonBox(Modifier.fillMaxWidth().height(56.dp), RoundedCornerShape(18.dp))
        Spacer(Modifier.height(Space.section))
        SkeletonLine(160.dp, 20.dp)
        Spacer(Modifier.height(Space.lead))
        repeat(2) { row ->
            if (row > 0) Spacer(Modifier.height(Space.inline))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
                repeat(2) { SkeletonBox(Modifier.weight(1f).aspectRatio(1f), RoundedCornerShape(24.dp)) }
            }
        }
        Spacer(Modifier.height(Space.section))
        SkeletonLine(110.dp, 20.dp)
        Spacer(Modifier.height(Space.lead))
        SkeletonBox(Modifier.fillMaxWidth().height(120.dp), RoundedCornerShape(24.dp))
    }
}

/** The referral sheet: a heading, the code, the two counts, and a few people. */
@Composable
internal fun ReferralSkeleton() {
    Column(Modifier.fillMaxWidth().loadingFor("your referral code")) {
        SkeletonLine(190.dp, 24.dp)
        Spacer(Modifier.height(Space.inline))
        SkeletonLine(260.dp, 12.dp)
        Spacer(Modifier.height(Space.lead))
        SkeletonBox(Modifier.fillMaxWidth().height(64.dp), RoundedCornerShape(16.dp))
        Spacer(Modifier.height(Space.inline))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
            repeat(2) { SkeletonBox(Modifier.weight(1f).height(72.dp), RoundedCornerShape(16.dp)) }
        }
        Spacer(Modifier.height(Space.section))
        repeat(3) { PersonSkeleton() }
    }
}

@Composable
private fun PersonSkeleton() {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).skeleton(CircleShape))
        Spacer(Modifier.width(Space.inline))
        Column {
            SkeletonLine(120.dp, 14.dp)
            Spacer(Modifier.height(6.dp))
            SkeletonLine(80.dp, 10.dp)
        }
    }
}

/** A few offer rows: a round face, three lines. */
@Composable
internal fun OffersSkeleton() {
    Column(Modifier.fillMaxWidth().loadingFor("offers"), verticalArrangement = Arrangement.spacedBy(Space.section)) {
        repeat(3) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(40.dp).skeleton(CircleShape))
                Spacer(Modifier.width(Space.inline))
                Column {
                    SkeletonLine(64.dp, 10.dp)
                    Spacer(Modifier.height(8.dp))
                    SkeletonLine(140.dp, 18.dp)
                    Spacer(Modifier.height(8.dp))
                    SkeletonLine(230.dp, 12.dp)
                }
            }
        }
    }
}

/** A wallet card's balance while it loads: a translucent bar on the card's own colour. */
@Composable
internal fun CardBalanceSkeleton() {
    Box(
        Modifier.size(96.dp, 26.dp).skeleton(
            RoundedCornerShape(13.dp), base = Color.White.copy(alpha = .35f), band = Color.White.copy(alpha = .7f),
        ),
    )
}
