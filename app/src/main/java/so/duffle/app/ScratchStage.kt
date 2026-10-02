package so.duffle.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A card lifted out of the grid: the reward, and where it sat in the window when it was tapped. */
internal class ScratchTarget(val row: Rewards.Row, val from: Rect)

/** One speck: foil dust while scratching, confetti when the card opens. Positions are in the card's own px. */
private class Bit(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    var life: Float, val maxLife: Float, val color: Color, val size: Float, val square: Boolean,
    var spin: Float = 0f,
)

/**
 * A reward card, lifted to the middle of the screen to be scratched.
 *
 * **The lift.** The card is laid out ONCE, at its final size, and moved by a transform alone: at
 * progress 0 it is scaled down onto the slot it came from (so the hand-off from the grid has
 * nothing to jump between) and at 1 it is full size in the centre. Its contents are scaled by
 * giving the subtree a larger density rather than by stretching pixels, so the words and the art
 * are drawn sharp at the big size and are exactly the grid card enlarged.
 *
 * **The page behind** is dimmed here and blurred by the host (it owns the page); both follow [t].
 * Tapping outside, going back, or a couple of seconds after the card is open, sends it home along
 * the same path.
 *
 * **The scratch** is meant to be felt: a small haptic tick for every stretch of finger travel,
 * foil dust thrown off at the touch, and — the moment enough is clear — the rest wipes away on its
 * own, the card gives one springy pulse, confetti bursts off it and the phone gives its success
 * pattern. The amount was settled when the payment did; scratching only shows it (see
 * [Rewards.reveal]).
 */
@Composable
internal fun ScratchStage(
    target: ScratchTarget,
    t: Animatable<Float, AnimationVector1D>,
    onClose: () -> Unit,
    onRevealed: (Rewards.Row) -> Unit,
) {
    val row = target.row
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val startedOpen = remember(row.id) { row.revealed }
    var revealed by remember(row.id) { mutableStateOf(row.revealed) }
    var closing by remember { mutableStateOf(false) }
    val pulse = remember { Animatable(1f) }

    fun close() {
        if (closing) return
        closing = true
        scope.launch {
            t.animateTo(0f, tween(340, easing = FastOutSlowInEasing))
            onClose()
        }
    }

    // Up with a little spring, so it arrives rather than just appears.
    LaunchedEffect(row.id) {
        t.snapTo(0f)
        t.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 280f))
    }
    BackHandler { close() }
    // "A couple of seconds" after it is open — measured from the reveal, so a card is never taken
    // away mid-scratch.
    LaunchedEffect(revealed) {
        if (revealed) {
            delay(if (startedOpen) 2_400 else 2_800)
            close()
        }
    }

    // Specks, shared by the dust and the confetti. Written by the gesture, advanced here.
    val bits = remember { ArrayList<Bit>() }
    var frame by remember { mutableIntStateOf(0) }
    var burstKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(burstKey) {
        var last = withFrameNanos { it }
        while (bits.isNotEmpty()) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            val i = bits.iterator()
            while (i.hasNext()) {
                val b = i.next()
                b.life -= dt
                if (b.life <= 0f) { i.remove(); continue }
                b.vy += b.size * 90f * dt     // gravity, in the speck's own scale
                b.x += b.vx * dt
                b.y += b.vy * dt
                b.spin += dt * 360f * (if (b.vx > 0) 1f else -1f)
            }
            frame++
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val margin = with(density) { 32.dp.toPx() }
        val side = min(screenW - 2 * margin, with(density) { 320.dp.toPx() })
        val kf = side / target.from.width            // how much bigger than its slot it ends up
        val centreLeft = (screenW - side) / 2f
        val centreTop = (screenH - side) / 2f - with(density) { 28.dp.toPx() }
        val baseDp = with(density) { target.from.width.toDp() }

        // The dim. The page behind is blurred by the host.
        Box(
            Modifier.fillMaxSize()
                .drawBehind { drawRect(Color.Black.copy(alpha = 0.5f * t.value.coerceIn(0f, 1f))) }
                .clickable(remember { MutableInteractionSource() }, indication = null) { close() },
        )

        Box(
            Modifier.size(with(density) { side.toDp() }).graphicsLayer {
                val p = t.value
                val s = lerp(1f / kf, 1f, p)
                scaleX = s; scaleY = s
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = lerp(target.from.left, centreLeft, p)
                translationY = lerp(target.from.top, centreTop, p)
            },
        ) {
            CompositionLocalProvider(LocalDensity provides Density(density.density * kf, density.fontScale)) {
                Box(Modifier.size(baseDp).graphicsLayer { scaleX = pulse.value; scaleY = pulse.value }) {
                    CardBody(row, cover = 0f)
                    ScratchLayer(
                        row = row, open = revealed,
                        onSpawn = { x, y, n, confetti, size ->
                            val pal = if (confetti) CONFETTI else if (row.isSpecial) GOLD_DUST else DUST
                            repeat(n) {
                                val a = Random.nextFloat() * 6.2832f
                                val v = size * (if (confetti) 0.5f + Random.nextFloat() * 1.1f else 0.25f + Random.nextFloat() * 0.6f)
                                val life = if (confetti) 0.9f + Random.nextFloat() * 0.7f else 0.35f + Random.nextFloat() * 0.35f
                                bits += Bit(
                                    x, y,
                                    kotlin.math.cos(a) * v, kotlin.math.sin(a) * v - (if (confetti) size * 0.6f else size * 0.15f),
                                    life, life, pal[Random.nextInt(pal.size)],
                                    size * (if (confetti) 0.022f + Random.nextFloat() * 0.02f else 0.008f + Random.nextFloat() * 0.012f),
                                    square = confetti,
                                )
                            }
                            burstKey++
                        },
                        onFeel = { Haptics.tick(ctx) },
                        onOpened = { w ->
                            revealed = true
                            Haptics.success(ctx)
                            onRevealed(row)
                            scope.launch {
                                pulse.animateTo(1.07f, spring(dampingRatio = 0.45f, stiffness = 700f))
                                pulse.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 280f))
                            }
                        },
                    )
                    // Specks, above everything and free to fly past the card's edge.
                    Canvas(Modifier.fillMaxSize()) {
                        frame // read here so only the drawing, not the layout, runs each frame
                        for (b in bits) {
                            val a = (b.life / b.maxLife).coerceIn(0f, 1f)
                            val c = b.color.copy(alpha = a)
                            if (b.square) {
                                rotate(b.spin, Offset(b.x, b.y)) {
                                    drawRect(c, Offset(b.x - b.size, b.y - b.size * 0.6f), Size(b.size * 2f, b.size * 1.2f))
                                }
                            } else {
                                drawCircle(c, b.size * (0.5f + 0.5f * a), Offset(b.x, b.y))
                            }
                        }
                    }
                }
            }
        }

        // What to do, said under the card and gone once it is done.
        Text(
            if (revealed) "Tap anywhere to close" else "Scratch to reveal",
            color = Color.White.copy(alpha = 0.9f * t.value.coerceIn(0f, 1f)),
            style = Type.body, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter)
                .offset { IntOffset(0, (centreTop + side + with(density) { 28.dp.toPx() }).toInt()) },
        )
    }
}

// Fixed on purpose: foil dust and confetti are colours of a thing in the world, not of the theme.
private val DUST = listOf(Color.White, Color(0xFFCFE3FF), Color(0xFF9FC4FF))
private val GOLD_DUST = listOf(Color.White, Color(0xFFFFE9A8), Color(0xFFFFC94D))
private val CONFETTI = listOf(Color(0xFF008FFF), Color(0xFF00A344), Color(0xFFD7FF3A), Color(0xFFE46A00), Color(0xFFFF4B45), Color.White)

/**
 * The covering foil and the finger that rubs it off.
 *
 * The same erase as before — the cover is drawn into its own layer and the path is cleared out of
 * it, because clearing pixels only works on a layer the composable owns — now measured as it
 * goes, so the card opens the moment enough is gone instead of when the finger lifts.
 */
@Composable
private fun ScratchLayer(
    row: Rewards.Row,
    open: Boolean,
    onSpawn: (x: Float, y: Float, n: Int, confetti: Boolean, size: Float) -> Unit,
    onFeel: () -> Unit,
    onOpened: (Unit) -> Unit,
) {
    val path = remember(row.id) { Path() }
    var strokes by remember(row.id) { mutableIntStateOf(0) }
    val cells = remember(row.id) { mutableSetOf<Int>() }
    var done by remember(row.id) { mutableStateOf(open) }
    val cover by animateFloatAsState(if (done) 0f else 1f, tween(380), label = "cover")
    val art = painterResource(if (row.isSpecial) R.drawable.special else R.drawable.simple)
    if (cover <= 0f) return

    Canvas(
        Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp))
            .graphicsLayer { alpha = cover; compositingStrategy = CompositingStrategy.Offscreen }
            .pointerInput(row.id) {
                var travelled = 0f
                var last = Offset.Zero
                var lastTick = 0L
                detectDragGestures(
                    onDragStart = { at ->
                        path.moveTo(at.x, at.y); last = at; strokes++
                        onSpawn(at.x, at.y, 3, false, size.width.toFloat())
                    },
                ) { change, _ ->
                    change.consume()
                    val p = change.position
                    path.lineTo(p.x, p.y)
                    // Every cell the brush touches, not only the one under the finger: a fast
                    // swipe samples sparsely and would never reach "enough".
                    val reach = min(size.width, size.height) * .11f
                    val cw = size.width / GRID.toFloat()
                    val ch = size.height / GRID.toFloat()
                    for (r in 0 until GRID) for (c in 0 until GRID) {
                        val dx = (c + .5f) * cw - p.x
                        val dy = (r + .5f) * ch - p.y
                        if (dx * dx + dy * dy <= reach * reach) cells += r * GRID + c
                    }
                    strokes++
                    // Dust off the foil where the finger is.
                    onSpawn(p.x, p.y, 2, false, size.width.toFloat())
                    // A tick for every stretch of travel (and not faster than the motor can play).
                    travelled += hypot(p.x - last.x, p.y - last.y)
                    last = p
                    val now = System.nanoTime()
                    if (travelled >= size.width * 0.07f && now - lastTick > 28_000_000L) {
                        travelled = 0f; lastTick = now; onFeel()
                    }
                    if (!done && cells.size >= (GRID * GRID * SCRATCHED_ENOUGH)) {
                        done = true
                        onSpawn(size.width / 2f, size.height / 2f, 46, true, size.width.toFloat())
                        onOpened(Unit)
                    }
                }
            },
    ) {
        strokes // read, so the canvas redraws as the path grows
        with(art) { draw(size) }
        drawPath(
            path, Color.Transparent,
            style = Stroke(width = size.minDimension * .20f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.Clear,
        )
    }
}
