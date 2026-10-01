package so.duffle.app

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * What the screen does when two phones touch.
 *
 * Two pieces, borrowed from the two tap gestures people already know:
 *  - while waiting, light sits along the edges of the screen (Google's Tap to Share) — it says
 *    "ready, bring the other phone";
 *  - at contact, a wave enters from the top edge, where the other phone is, and bends the whole
 *    screen as it passes (Apple's NameDrop).
 *
 * Neither is driven by distance. NFC cannot say how close the other phone is, and Apple's does
 * not either: one radio event starts a show that then runs on its own clock. So this takes a
 * time, not a position.
 */
internal data class RippleSpec(
    /** How far a pixel is pushed at the crest, in dp. */
    val amplitude: Float,
    /** How fast it swings back and forth once the wave has reached it. */
    val frequency: Float,
    /** How quickly that swing dies. Higher is a single shove, lower is a wobble. */
    val decay: Float,
    /** How fast the wavefront crosses the screen, in dp per second. */
    val speed: Float,
    /** Width of the coloured band riding the front, in dp. */
    val band: Float = 70f,
    /** How much of the tint the band carries, 0–1. */
    val tint: Float = 0.35f,
) {
    /** Until the front has crossed [heightPx] and the last pixel has stopped swinging. */
    fun seconds(heightPx: Float, density: Float) = heightPx * 1.05f / (speed * density) + 0.8f
}

/**
 * Bends everything inside this modifier with a wave from just above the top edge.
 *
 * [seconds] is the time since contact, or null when nothing is playing. It is read inside the
 * layer block, so a running wave redraws the layer each frame without recomposing anything.
 *
 * Below Android 13 there are no custom shaders and this does nothing; the edge glow carries the
 * moment on its own there.
 */
internal fun Modifier.tapRipple(seconds: () -> Float?, spec: () -> RippleSpec?, tint: Color): Modifier = composed {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@composed this
    // Compiled once. Building a shader costs main-thread time, and contact is the one frame
    // that must not be late.
    val shader = remember { RuntimeShader(RIPPLE) }
    graphicsLayer {
        val t = seconds()
        val s = spec()
        if (t == null || s == null) {
            renderEffect = null
            return@graphicsLayer
        }
        shader.setFloatUniform("size", size.width, size.height)
        // Just off the top of the glass: the wave arrives from where the other phone is.
        shader.setFloatUniform("origin", size.width / 2f, -0.05f * size.height)
        shader.setFloatUniform("time", t)
        shader.setFloatUniform("amplitude", s.amplitude * density)
        shader.setFloatUniform("frequency", s.frequency)
        shader.setFloatUniform("decay", s.decay)
        shader.setFloatUniform("speed", s.speed * density)
        shader.setFloatUniform("band", s.band * density)
        shader.setFloatUniform("tint", tint.red, tint.green, tint.blue, s.tint)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

/**
 * The wave is Apple's published sample ripple (WWDC24 "Create custom visual effects with
 * SwiftUI"): each pixel waits for the front to reach it, then swings along the line from the
 * origin and dies away. Two changes for this app. Samples are clamped to the screen, or the
 * edges pull in transparent pixels and flash black. And the front carries a band of colour,
 * because Apple's version only brightens, and brightening a white page shows nothing.
 */
private const val RIPPLE = """
uniform shader content;
uniform float2 size;
uniform float2 origin;
uniform float time;
uniform float amplitude;
uniform float frequency;
uniform float decay;
uniform float speed;
uniform float band;
uniform float4 tint;

half4 main(float2 p) {
    float d = length(p - origin);
    float t = max(0.0, time - d / speed);
    float push = amplitude * sin(frequency * t) * exp(-decay * t);
    float2 dir = normalize(p - origin);
    float2 from = clamp(p + push * dir, float2(0.0), size - float2(1.0));
    float4 c = content.eval(from);
    float front = d - speed * time;
    float glow = exp(-(front * front) / (2.0 * band * band)) * tint.a;
    c.rgb = mix(c.rgb, tint.rgb * c.a, glow);
    return half4(c);
}
"""

/**
 * The whole tap light, start to finish, as one thing a screen can drop in.
 *
 * [waiting]: the radio is on and nobody has arrived — a soft bar of light along the top.
 * [touched]: the phones met. The bar parts in the middle, runs down both sides and comes to
 * rest as a faint line along the bottom, where it stays for as long as this is on screen.
 *
 * A screen that opens BECAUSE of a tap passes [touched] from its first frame; the bar is then
 * there for an instant and parts at once, so the story still starts at the top.
 */
@Composable
internal fun TapGlow(
    waiting: Boolean,
    touched: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = D.Blue,
    /** How long the light takes to get from the top to the bottom. */
    runMs: Int = TapGlowSpec.RUN_MS,
    /** How bright it stays once it is there, 0–1. Low: an amount and a Pay button sit above it. */
    rest: Float = TapGlowSpec.REST,
) {
    val run = remember { Animatable(0f) }
    // A screen rebuilt after the light has landed must not run it down the sides again.
    var landed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(touched, runMs) {
        if (!touched) {
            landed = false
            run.snapTo(0f)
        } else if (landed) {
            run.snapTo(1f)
        } else {
            run.animateTo(1f, tween(runMs, easing = LinearEasing))
            landed = true
        }
    }
    var screen by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    Box(modifier.onSizeChanged { screen = it }) {
        val w = screen.width.toFloat()
        val h = screen.height.toFloat()
        if (w == 0f || h == 0f) return@Box
        val ease = FastOutSlowInEasing
        val t = run.value
        // The front of the light leaves the middle of the top at once; the back of it follows a
        // beat later, which is what opens the gap and makes it read as one light splitting.
        val head = ease.transform((t / 0.85f).coerceIn(0f, 1f))
        // The back comes all the way down too, so nothing is left on the sides.
        val tail = rimBottomStart(w, h, density) * ease.transform(((t - 0.2f) / 0.8f).coerceIn(0f, 1f))
        val gap = if (touched) (tail * (w + h) / (w / 2f)).coerceAtMost(1f) else 0f
        // Full strength while it travels, so the run is seen; then down to a rest that does not
        // compete with what is above it.
        val strength = 1f + (rest - 1f) * ease.transform(((t - 0.6f) / 0.4f).coerceIn(0f, 1f))
        if (waiting || (touched && gap < 1f)) {
            EdgeGlow(tint, level = TapGlowSpec.TOP, flash = 0f, topOnly = true, modifier = Modifier.fillMaxSize(), gap = gap)
        }
        if (touched) RunningGlow(tint, tail, head, strength, Modifier.fillMaxSize())
    }
}

/** Chosen by hand on the Seeker, in the debug tap-effect screen ("Split 1"). */
internal object TapGlowSpec {
    const val RUN_MS = 600
    const val REST = 0.30f
    const val TOP = 0.45f
}

/**
 * Light that travels round the rim of the screen: this app's own tap gesture.
 *
 * While waiting, the light sits along the top, where the other phone will be. At contact it
 * parts in the middle and runs down both sides to the bottom, where the pay sheet is coming up,
 * and stays there as a faint line along the bottom edge. Read in order it says: something came
 * across from their phone, and it is now held here. Faint, because what it sits under is an
 * amount and a Pay button, and those are what the eye is for. Unlike a ripple it never moves
 * either of them out from under it.
 *
 * The rim is one line from the top centre, round a corner, down a side, round the bottom corner
 * to the bottom centre — drawn twice, mirrored. [tail] and [head] are how far along that line
 * the lit stretch starts and ends, 0–1.
 */
@Composable
internal fun RunningGlow(tint: Color, tail: Float, head: Float, strength: Float, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    // The same light as the top edge, only moving: a wide wash that fades inward, with no line
    // in it. The first version had a bright core and a dense body, which read as a neon tube
    // next to the soft glow it had just split from — two different lights, not one. The second,
    // narrower pass only keeps the wash from thinning to nothing right at the glass edge.
    // The blur filters are built once; making one per frame shows up as a dropped frame.
    val passes = remember(density, tint) {
        fun pass(width: Float, blur: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = width * density
            maskFilter = BlurMaskFilter(blur * density, BlurMaskFilter.Blur.NORMAL)
            color = tint.toArgb()
        }
        // Narrow: it reaches about a finger's width into the screen, not a third of the way
        // across it. No brighter for being narrower — same light, less of it.
        listOf(
            pass(24f, 20f) to 0.40f,
            pass(9f, 8f) to 0.20f,
        )
    }
    val rim = remember { Path() }
    val lit = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(modifier) {
        if (head <= tail || strength <= 0f) return@Canvas
        val r = RIM_RADIUS.dp.toPx()
        val cx = size.width / 2f
        for (side in intArrayOf(1, -1)) {
            val edge = if (side > 0) size.width else 0f
            rim.rewind()
            rim.moveTo(cx, 0f)
            rim.lineTo(edge - side * r, 0f)
            rim.quadraticTo(edge, 0f, edge, r)
            rim.lineTo(edge, size.height - r)
            rim.quadraticTo(edge, size.height, edge - side * r, size.height)
            rim.lineTo(cx, size.height)
            measure.setPath(rim, false)
            lit.rewind()
            measure.getSegment(tail.coerceIn(0f, 1f) * measure.length, head.coerceIn(0f, 1f) * measure.length, lit, true)
            drawIntoCanvas { canvas ->
                for ((paint, alpha) in passes) {
                    paint.alpha = (255 * alpha * strength).toInt().coerceIn(0, 255)
                    canvas.nativeCanvas.drawPath(lit.asAndroidPath(), paint)
                }
            }
        }
    }
}

/**
 * How far along the rim the bottom edge begins — see [RunningGlow]. Resting the back of the
 * light here leaves it along the bottom only, with nothing left on the sides. The 0.24 and 0.76
 * are the two rounded corners: each cuts 2r of straight line and puts back about 1.62r of curve.
 */
internal fun rimBottomStart(width: Float, height: Float, density: Float): Float {
    val r = RIM_RADIUS * density
    return (width / 2f + height + 0.24f * r) / (width + height - 0.76f * r)
}

private const val RIM_RADIUS = 30f

/**
 * Light along the edges of the screen.
 *
 * [level] is the steady brightness, [flash] an extra burst on top of it at contact. The top
 * edge is deeper and brighter than the rest: that is the edge the other phone meets.
 */
@Composable
internal fun EdgeGlow(
    tint: Color, level: Float, flash: Float, topOnly: Boolean, modifier: Modifier = Modifier,
    /** 0–1: how far the top light has parted from the middle. 1 is gone to the corners. */
    gap: Float = 0f,
) {
    val breathing = rememberInfiniteTransition(label = "edgeGlow")
    val breath by breathing.animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath",
    )
    // Offscreen, so the parting can be CUT out of the light rather than painted over it with
    // the page colour — which would only work on a page that is one flat colour.
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val a = (level * breath + flash).coerceIn(0f, 1f)
        if (a <= 0f) return@Canvas
        val clear = tint.copy(alpha = 0f)
        val top = (110.dp.toPx()) * (1f + flash * 0.8f)
        drawRect(
            Brush.verticalGradient(listOf(tint.copy(alpha = a), clear), 0f, top),
            Offset.Zero, Size(size.width, top),
        )
        if (gap > 0f) {
            val half = size.width / 2f
            val open = half * gap
            val soft = 48.dp.toPx()
            drawRect(
                Brush.horizontalGradient(
                    0f to Color.Transparent,
                    ((half - open - soft) / size.width).coerceIn(0f, 1f) to Color.Transparent,
                    ((half - open) / size.width).coerceIn(0f, 1f) to Color.Black,
                    ((half + open) / size.width).coerceIn(0f, 1f) to Color.Black,
                    ((half + open + soft) / size.width).coerceIn(0f, 1f) to Color.Transparent,
                    1f to Color.Transparent,
                ),
                Offset.Zero, Size(size.width, top), blendMode = BlendMode.DstOut,
            )
        }
        if (topOnly) return@Canvas
        val side = 40.dp.toPx() * (1f + flash * 0.8f)
        val rest = tint.copy(alpha = a * 0.7f)
        drawRect(
            Brush.verticalGradient(listOf(clear, rest), size.height - side, size.height),
            Offset(0f, size.height - side), Size(size.width, side),
        )
        drawRect(Brush.horizontalGradient(listOf(rest, clear), 0f, side), Offset.Zero, Size(side, size.height))
        drawRect(
            Brush.horizontalGradient(listOf(clear, rest), size.width - side, size.width),
            Offset(size.width - side, 0f), Size(side, size.height),
        )
    }
}
