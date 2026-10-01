package so.duffle.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The frame that goes and gets the code.
 *
 * The usual scanner draws a square and makes the person aim into it. This one reads the whole
 * picture, and the square is only the answer: it rests in the middle, and the moment a code is
 * anywhere on screen it travels to it and holds on while the hand moves. Nobody has to line
 * anything up.
 *
 * The numbers are Telegram's (`CameraScanActivity`), because the feel is the numbers:
 *  - rest → code is one spring with no bounce (stiffness 500, damping 1);
 *  - between sightings the frame eases for 75ms, so it glides rather than jumping frame to frame;
 *  - on opening it grows from half size on a slightly bouncy spring (stiffness 250, damping 0.8);
 *  - the picture outside the frame darkens from 50% to 75% once it has hold of something.
 */
internal object ScanFrameSpec {
    /** A resting square two-thirds of the short edge. */
    const val REST_DIVISOR = 1.5f
    const val TRACK_MS = 75
    const val DIM_MS = 300
    const val DIM_REST = 0.5f
    const val DIM_LOCKED = 0.75f

    /** Telegram's `CubicBezierInterpolator.DEFAULT`. */
    val Ease = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** How far outside the code's own corners the brackets sit. */
    val Pad = 14.dp

    /** A code across the room is a speck; the frame around it should still read as a frame. */
    val MinSide = 72.dp
    val Line = 4.dp
    val Arm = 20.dp

    fun <T> lockSpring() = spring<T>(dampingRatio = 1f, stiffness = 500f)
}

/**
 * @param code where the code is, in this canvas's own pixels, or null before the first sighting.
 * @param lock 0 at rest, 1 holding a code — the caller owns it so the words can fade with it.
 */
@Composable
internal fun ScanFrame(code: Rect?, locked: Boolean, lock: Float, modifier: Modifier = Modifier) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 250f)) }

    val tracked = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    LaunchedEffect(code) {
        if (code == null) return@LaunchedEffect
        // At rest the tracked position is not on screen, so there is nothing to glide from —
        // start on the code and let the lock spring do the travelling.
        if (lock == 0f) tracked.snapTo(code)
        else tracked.animateTo(code, tween(ScanFrameSpec.TRACK_MS, easing = LinearEasing))
    }

    val dim by animateFloatAsState(
        if (locked) ScanFrameSpec.DIM_LOCKED else ScanFrameSpec.DIM_REST,
        tween(ScanFrameSpec.DIM_MS, easing = ScanFrameSpec.Ease),
        label = "scanDim",
    )

    Canvas(modifier) {
        val a = appear.value
        val side = min(size.width, size.height) / ScanFrameSpec.REST_DIVISOR
        val rest = Rect(center, side / 2f)
        val want = if (code == null) rest else lerp(rest, around(tracked.value), lock)

        val grow = 0.5f + a * 0.5f
        val w = want.width * grow
        val h = want.height * grow
        val box = Rect(Offset(want.center.x - w / 2f, want.center.y - h / 2f), Size(w, h))

        // Four slabs round the frame rather than one sheet with a hole cut in it: nothing to
        // clip, and the camera picture inside is never drawn over.
        val shade = Color.Black.copy(alpha = (1f - (1f - dim) * min(1f, a)).coerceIn(0f, 1f))
        drawRect(shade, Offset.Zero, Size(size.width, max(0f, box.top)))
        drawRect(shade, Offset(0f, box.bottom), Size(size.width, max(0f, size.height - box.bottom)))
        drawRect(shade, Offset(0f, box.top), Size(max(0f, box.left), box.height))
        drawRect(shade, Offset(box.right, box.top), Size(max(0f, size.width - box.right), box.height))
        // The screen opens black and the picture arrives inside the frame as it grows.
        if (a < 1f) drawRect(Color.Black.copy(alpha = (1f - a).coerceIn(0f, 1f)), box.topLeft, box.size)

        val line = lerp(0f, ScanFrameSpec.Line.toPx(), min(1f, a * 20f))
        if (line <= 0f) return@Canvas
        // The arms start as the whole edge and draw back into four corners.
        val arm = lerp(min(w, h), ScanFrameSpec.Arm.toPx(), min(1.2f, a.pow(1.8f)))
        val ink = Color.White.copy(alpha = min(1f, a))
        val stroke = Stroke(
            width = line, cap = StrokeCap.Round, join = StrokeJoin.Round,
            pathEffect = PathEffect.cornerPathEffect(line * 1.5f),
        )
        corner(box.left, box.top, 1f, 1f, arm, ink, stroke)
        corner(box.right, box.top, -1f, 1f, arm, ink, stroke)
        corner(box.left, box.bottom, 1f, -1f, arm, ink, stroke)
        corner(box.right, box.bottom, -1f, -1f, arm, ink, stroke)
    }
}

/** The code's own corners, pushed out so the brackets sit round it rather than on it. */
private fun DrawScope.around(code: Rect): Rect {
    val padded = code.inflate(ScanFrameSpec.Pad.toPx())
    val floor = ScanFrameSpec.MinSide.toPx()
    val w = max(padded.width, floor)
    val h = max(padded.height, floor)
    return Rect(Offset(padded.center.x - w / 2f, padded.center.y - h / 2f), Size(w, h))
}

/** One bracket: an arm down the side, round the corner, an arm along the top. */
private fun DrawScope.corner(x: Float, y: Float, dx: Float, dy: Float, arm: Float, ink: Color, stroke: Stroke) {
    val path = Path().apply {
        moveTo(x, y + dy * arm)
        lineTo(x, y)
        lineTo(x + dx * arm, y)
    }
    drawPath(path, ink, style = stroke)
}
