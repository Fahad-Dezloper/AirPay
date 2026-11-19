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
