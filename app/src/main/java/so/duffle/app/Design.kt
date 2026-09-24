package so.duffle.app

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AirPay's look, in one place.
 *
 * These began inside the tap sheet, which was the only screen built to DUFFLE_DESIGN.md. The
 * rest of the app was still the dark developer console it started as, so opening AirPay meant
 * meeting two different products a second apart. Lifting them out rather than copying them is
 * the point: a second set of buttons and springs would drift from this one within a week, and
 * the copy that drifts is always the one somebody is looking at.
 *
 * Colours live in [D] (Palette.kt). Motion follows the design doc: springs described by damping
 * and response, with stiffness = (2π / response)².
 */

/** Spent on arrivals — a sheet rising, a mark landing. Everything else settles instead. */
internal val Arrive = spring<Float>(dampingRatio = 0.75f, stiffness = 158f)

/** For anything that answers a finger: no overshoot, just quick. */
internal val Snap = spring<Float>(dampingRatio = 1f, stiffness = 439f)

/** Bricolage Grotesque for names and amounts; Figtree for everything else. Both variable fonts. */
@OptIn(ExperimentalTextApi::class)
private fun bricolage(w: Int) = Font(R.font.bricolage, FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w), FontVariation.Setting("opsz", 96f)))

@OptIn(ExperimentalTextApi::class)
private fun figtree(w: Int) = Font(R.font.figtree, FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w)))

internal val Display = FontFamily(bricolage(700), bricolage(800))
internal val Ui = FontFamily(figtree(400), figtree(500), figtree(600), figtree(700))

/** Every amount uses tabular figures, so digits don't shift sideways as they change. */
internal val Figures = TextStyle(fontFamily = Ui, fontFeatureSettings = "tnum")

@Composable
internal fun HIcon(@DrawableRes id: Int, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) =
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = modifier.size(size))

@Composable
internal fun IconDisc(
    @DrawableRes id: Int, tint: Color, background: Color, modifier: Modifier = Modifier,
    size: Dp = 60.dp, icon: Dp = 28.dp,
) {
    Box(modifier.size(size).background(background, CircleShape), contentAlignment = Alignment.Center) {
        HIcon(id, tint, icon)
    }
}

/** Presses scale to 0.97 on the `snap` spring. The label morphs rather than swapping. */
@Composable
internal fun PressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, Snap, label = "press")
    return Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
internal fun PrimaryButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true, loading: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val live = enabled || loading
    val bg by animateColorAsState(if (live) D.Blue else D.Blue.copy(alpha = .35f), label = "bg")
    val fg = D.White
    Box(
        modifier.then(PressScale(interaction)).height(60.dp).clip(RoundedCornerShape(18.dp)).background(bg)
            .clickable(interaction, indication = null, enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The button carries its own progress: no spinner in a box beside it, and the words
        // slide rather than swap, so pressing it never looks like a different button appeared.
        AnimatedContent(
            targetState = label to loading,
            transitionSpec = {
                (slideInVertically(spring(0.9f, 247f)) { it / 2 } + fadeIn(tween(150))) togetherWith
                    (slideOutVertically(spring(0.9f, 247f)) { -it / 2 } + fadeOut(tween(100)))
            },
            label = "label",
        ) { (text, spinning) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (spinning) {
                    CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(text, color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold, style = Figures)
            }
        }
    }
}

@Composable
internal fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.then(PressScale(interaction)).height(52.dp).clip(RoundedCornerShape(16.dp)).background(D.Fill)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) D.Black else D.Quiet, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = Figures)
    }
}

@Composable
internal fun TextLink(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Box(
        Modifier.padding(top = 6.dp).then(modifier).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) D.Black else D.Quiet, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
}

/**
 * The card shadow, ported from the CSS the design settled on:
 *
 *     0 2px 3px -1px rgba(0,0,0,.1), 0 1px 0 0 rgba(25,28,33,.02), 0 0 0 1px rgba(25,28,33,.08)
 *
 * A soft drop, a crisp lip, and a hairline ring — the ring is what separates a white card from
 * the white sheet under it. Drawn by hand because Compose's shadow() has no spread and no
 * outline.
 */
internal fun Modifier.cardShadow(radius: Dp) = drawBehind {
    val r = radius.toPx()
    val px = 1.dp.toPx()
    drawIntoCanvas { canvas ->
        // 0 2px 3px -1px rgba(0,0,0,.1): the shape shrunk by the spread, casting a blurred shadow.
        val paint = androidx.compose.ui.graphics.Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            setShadowLayer(3 * px, 0f, 2 * px, android.graphics.Color.argb(26, 0, 0, 0))
        }
        canvas.nativeCanvas.drawRoundRect(px, px, size.width - px, size.height - px, r - px, r - px, paint)
    }
    // 0 1px 0 0 rgba(25,28,33,.02): the same shape, one pixel lower.
    drawRoundRect(Color(25, 28, 33).copy(alpha = .02f), topLeft = Offset(0f, px), size = size, cornerRadius = CornerRadius(r))
    // 0 0 0 1px rgba(25,28,33,.08): a one-pixel ring outside the edge.
    drawRoundRect(
        Color(25, 28, 33).copy(alpha = .08f), topLeft = Offset(-px, -px),
        size = Size(size.width + 2 * px, size.height + 2 * px), cornerRadius = CornerRadius(r + px),
    )
}
