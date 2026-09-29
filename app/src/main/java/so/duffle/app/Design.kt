package so.duffle.app

import androidx.compose.foundation.layout.Column
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineHeightStyle
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

// ------------------------------------------------------------------------- type
//
// Four faces, each with one job. See ui/PORT.md §2 and ui/PROJECT.md §3.
//
//   Inter       everything that is words — body, headings, labels
//   Geist       money, and only money
//   Geist Mono  addresses, signatures, codes — things read character by character
//   Bricolage   the "AirPay" wordmark, and currency symbols
//
// Figtree is retired; `figtree.ttf` can go once nothing references it.

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, w: Int, opsz: Float? = null) = Font(res, FontWeight(w),
    variationSettings = FontVariation.Settings(
        *listOfNotNull(
            FontVariation.weight(w),
            opsz?.let { FontVariation.Setting("opsz", it) },
        ).toTypedArray(),
    ))

private fun faces(res: Int, opsz: Float? = null, weights: List<Int> = listOf(400, 500, 600, 700, 800)) =
    FontFamily(weights.map { variable(res, it, opsz) })

/**
 * The wordmark only.
 *
 * Bricolage is a display face: it is doing a logo's job here, not a typeface's, and the plan is
 * to replace the wordmark with a vector and drop the font.
 */
internal val Display = faces(R.font.bricolage, opsz = 96f, weights = listOf(700, 800))

/** Everything that is words. Inter carries an `opsz` axis; 16 is its text optical size. */
internal val Ui = faces(R.font.inter, opsz = 16f)

/** Money, and only money. */
internal val Figure = faces(R.font.geist)

/** Addresses, signatures, gift-card codes — anything read one character at a time. */
internal val Mono = faces(R.font.geist_mono, weights = listOf(400, 500))

/**
 * Currency symbols, and nothing else.
 *
 * A 24 KB subset of Bricolage holding only `$ ¢ £ ¥ € ₹ ₽ ₿ …`. On the web it simply sits first
 * in the font stack and answers for those characters. Compose picks a font by **weight, not by
 * which characters it contains**, so there is no stack to sit in — [currencyStyled] applies it
 * per character instead.
 */
internal val CurrencyFace = faces(R.font.bricolage_currency, opsz = 96f, weights = listOf(400, 700, 800))

/** The characters [currencyStyled] hands to Bricolage. */
private val CURRENCY = "$¢£¥€₹₽₿₩₪₫₱₦₴₸₺₼₾﷼".toSet()

/**
 * An amount with its currency symbol set in Bricolage and the digits left alone.
 *
 * The Android-level answer is `Typeface.CustomFallbackBuilder`, which needs a `Context` and so
 * cannot build a top-level `FontFamily`. This is the fallback ui/PORT.md §2 names, and it is
 * enough: currency symbols only ever appear inside an amount.
 */
internal fun currencyStyled(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    text.forEachIndexed { i, c ->
        if (c in CURRENCY) addStyle(SpanStyle(fontFamily = CurrencyFace), i, i + 1)
    }
}

/**
 * The type scale. Seven steps, and nothing between them.
 *
 * `lineHeightStyle` matches CSS leading — half the extra space above, half below, nothing
 * trimmed — so a line box here is the same height as the same line in the sandbox.
 */
internal object Type {
    private fun step(
        family: FontFamily, size: Int, line: Int, weight: Int,
        tracking: Float = 0f, tnum: Boolean = false,
    ) = TextStyle(
        fontFamily = family, fontSize = size.sp, lineHeight = line.sp,
        fontWeight = FontWeight(weight), letterSpacing = tracking.sp,
        fontFeatureSettings = if (tnum) "tnum" else null,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    /** Tags, badges, uppercase labels. */
    val micro = step(Ui, 11, 14, 700, tracking = 1.5f)
    /** Secondary lines, times, addresses. */
    val caption = step(Ui, 13, 18, 400)
    /** Row titles, sentences, inputs. */
    val body = step(Ui, 15, 20, 500)
    /** Section headings **and every button label** — white on blue needs 19sp bold. */
    val heading = step(Ui, 19, 24, 700, tracking = -0.2f)
    /** Screen and sheet titles, the success line. */
    val title = step(Ui, 24, 28, 700, tracking = -0.4f)
    /** The amount on a sheet, the rewards total. */
    val amount = step(Figure, 40, 44, 700, tracking = -1f, tnum = true)
    /** The balance and the keypad amount. **A maximum** — nothing is larger. */
    val display = step(Figure, 60, 64, 800, tracking = -2f, tnum = true)
    /** Addresses, signatures, codes. */
    val mono = step(Mono, 13, 18, 500)
}

/**
 * Tabular figures, so digits do not shift sideways as they change.
 *
 * Superseded by [Type.amount] and [Type.display], which also carry the right face and size.
 * Kept because ~80 call sites still use it, and they move screen by screen.
 */
internal val Figures = TextStyle(fontFamily = Ui, fontFeatureSettings = "tnum")

// ------------------------------------------------------------------------- space

/**
 * Space, by the job it does.
 *
 * With the containers gone, space is the only thing that groups content — so the roles are
 * named rather than numbered. Heading-to-content is 12 against a 40 section gap, a ratio of
 * 3.3, which is what makes grouping read without drawing a box around it.
 */
internal object Space {
    /** A line and the line that qualifies it. */
    val tight = 4.dp
    /** An icon or avatar and its text; chips in a row. */
    val inline = 12.dp
    /** Top and bottom of a list row. One line is then 52dp, clear of the 48dp touch minimum. */
    val row = 16.dp
    /** A section heading and its first item. */
    val lead = 12.dp
    /** One section and the next. */
    val section = 40.dp
    /** The page's left and right. */
    val gutter = 20.dp
}

/**
 * How thick a mark's stroke is drawn, on the 24-unit grid.
 *
 * **The stroke follows the text, not the icon.** 1.5px on screen beside a caption, 2px above
 * that — so an icon next to 13sp text has the same weight as the text, and the pair reads as
 * one thing. Inside a status disc it is 2.75 on the grid, because that icon is carrying the
 * meaning a colour-blind person reads and has to hold at a glance.
 *
 * Ported from `markStroke()` in ui/components/ui/Marks.tsx.
 */
internal fun markStroke(size: Dp, disc: Boolean = false): Float =
    if (disc) 2.75f else (if (size <= 16.dp) 1.5f else 2f) * 24f / size.value

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
        modifier.then(PressScale(interaction)).heightIn(min = 60.dp).clip(RoundedCornerShape(18.dp)).background(bg)
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
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (spinning) {
                    CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(text, color = fg, style = Type.heading.copy(color = fg, fontFeatureSettings = "tnum"))
            }
        }
    }
}

@Composable
internal fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.then(PressScale(interaction)).heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(D.Fill)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) D.Black else D.Quiet, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = Figures)
    }
}

@Composable
internal fun TextLink(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Box(
        Modifier.padding(top = 6.dp).then(modifier).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
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

/** `settle` from DUFFLE_DESIGN.md §3 — tray height and content swaps. */
internal val Settle = spring<Float>(dampingRatio = 0.9f, stiffness = 247f)

/**
 * The app's tray, extracted so there is one of them.
 *
 * The tap sheet had this chrome built into it and every later sheet reached for Material's
 * `ModalBottomSheet` instead — a different scrim, a different spring, a different radius, in the
 * same app. This is the one in DUFFLE_DESIGN.md: it rises on `arrive`, overshooting a little to
 * reward the gesture; it can be dragged down and caught mid-rise, because one number drives
 * rising, dragging, springing back and leaving; and it leaves faster and quieter than it came,
 * which is the rule about things leaving the way they arrived.
 *
 * The panel's corner is [OUTER]. Anything nested inside it should use `OUTER - its padding`, so
 * the curves stay concentric rather than merely both being round.
 */
internal object Tray {
    val OUTER = 28.dp
    /** Side padding inside the tray, and therefore what an inner radius subtracts. */
    val PAD = 20.dp
    /** A block sitting directly inside the tray: concentric with it. */
    val INNER = OUTER - PAD
}

@Composable
internal fun Tray(
    onClose: () -> Unit,
    /** False while something must not be interrupted — a payment in flight. */
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val scrim = remember { Animatable(0f) }
    val offset = remember { Animatable(0f) }
    var height by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val h = snapshotFlow { height }.first { it > 0f }
        // Below the screen until it has been measured, so it never flashes in place.
        offset.snapTo(h)
        shown = true
        launch { scrim.animateTo(1f, tween(220)) }
        offset.animateTo(0f, Arrive)
    }

    fun leave() {
        if (leaving || !dismissible) return
        leaving = true
        scope.launch { scrim.animateTo(0f, tween(150)) }
        scope.launch {
            // Quicker and quieter than the entrance.
            offset.animateTo(height.coerceAtLeast(1f), tween(200, easing = FastOutLinearInEasing))
            onClose()
        }
    }

    BackHandler(enabled = dismissible) { leave() }

    val drag = rememberDraggableState { delta ->
        scope.launch {
            val next = offset.value + delta
            // Upward drag is resisted rather than refused, so the tray feels attached.
            offset.snapTo(if (next < 0f) (offset.value + delta * .25f).coerceAtLeast(-40f) else next)
        }
    }

    Box(
        Modifier.fillMaxSize()
            .drawBehind {
                val gone = if (height > 0f) (offset.value / height).coerceIn(0f, 1f) else 0f
                drawRect(Color.Black.copy(alpha = .28f * scrim.value * (1f - gone)))
            }
            .clickable(remember { MutableInteractionSource() }, indication = null) { leave() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .onSizeChanged { height = it.height.toFloat() }
                .graphicsLayer { translationY = if (shown) offset.value else size.height }
                .draggable(
                    drag, Orientation.Vertical, enabled = shown && !leaving && dismissible,
                    onDragStopped = { velocity ->
                        // Far enough, or flicked: go. Otherwise settle back.
                        if (offset.value > height * .25f || velocity > 1_200f) leave()
                        else scope.launch { offset.animateTo(0f, Settle, initialVelocity = velocity) }
                    },
                )
                .background(D.White, RoundedCornerShape(topStart = Tray.OUTER, topEnd = Tray.OUTER))
                // Swallow taps so touching the tray never dismisses it.
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .padding(start = Tray.PAD, end = Tray.PAD, top = 10.dp, bottom = 12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(D.Line, CircleShape))
            content()
        }
    }
}

/**
 * A figure whose digits roll when it changes, the way an odometer does.
 *
 * DUFFLE_DESIGN.md asks for exactly this — "the amount rolls up in tabular figures" — and it is
 * the right treatment for the one number on the home screen: switching wallets replaces every
 * digit at once, and a straight swap gives no sense that one balance BECAME another.
 *
 * Each character is its own slot, so only the digits that actually changed move; the rest hold
 * still. Direction carries meaning: a digit going up rolls up, one going down rolls down.
 * Slots are staggered left to right, because a number is read that way.
 *
 * Tabular figures do the real work — without them every roll would also resize the slot and the
 * whole figure would jitter sideways as it settled.
 */
@Composable
internal fun FlowFigure(
    text: String,
    fontSize: TextUnit,
    color: Color,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily? = null,
) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        text.forEachIndexed { i, ch ->
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    val up = targetState > initialState
                    val delay = i * 35
                    val enter = slideInVertically(Settle.toInt(delay)) { h -> if (up) h else -h } +
                        fadeIn(tween(160, delayMillis = delay))
                    val exit = slideOutVertically(Settle.toInt(delay)) { h -> if (up) -h else h } +
                        fadeOut(tween(120, delayMillis = delay))
                    (enter togetherWith exit).using(SizeTransform(clip = false))
                },
                label = "digit",
            ) { c ->
                Text(
                    c.toString(), color = color, fontSize = fontSize, fontWeight = fontWeight,
                    fontFamily = fontFamily, style = Figures, maxLines = 1,
                )
            }
        }
    }
}

/** The `settle` spring as an Int animation, for the slide offsets. */
private fun FiniteAnimationSpec<Float>.toInt(delay: Int): FiniteAnimationSpec<IntOffset> =
    spring(dampingRatio = 0.9f, stiffness = 247f, visibilityThreshold = IntOffset.VisibilityThreshold)
