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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.util.lerp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
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
    // Headings may tighten; NUMBERS NEVER DO (ui/PROJECT.md §3). `amount` and `display` were
    // tracked -1 and -2 like the heading steps above them, and heavy tabular digits pulled that
    // close were touching — which is the one thing tabular figures exist to prevent.
    /** The amount on a sheet, the rewards total. */
    val amount = step(Figure, 40, 44, 700, tnum = true)
    /** The balance and the keypad amount. **A maximum** — nothing is larger. */
    val display = step(Figure, 60, 64, 800, tnum = true)
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
internal fun HIcon(@DrawableRes id: Int, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    // A line icon is drawn by [HiIcon], whose stroke follows the text (ui/PORT.md §5). The
    // drawables hard-code strokeWidth 1.5 and cannot. Anything else here is a picture — a
    // brand logo — and is painted as it ships.
    val line = HI_FOR_RES[id]
    if (line != null) HiIcon(line, tint, size, modifier = modifier)
    else Icon(painterResource(id), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

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

/**
 * "Your network fee is paid for you", as a mark: ink on the marker-pen yellow, which in this app
 * means "you gain". Shown only where [FreeGas] says it is true.
 */
@Composable
internal fun FreeFeeMark(text: String = "Network fee on us", modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.clip(CircleShape).background(D.Highlight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HiIcon(Hi.Gift, D.Black, 16.dp, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(text, color = D.Black, style = Type.caption.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

@Composable
internal fun PrimaryButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true, loading: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val live = enabled || loading
    // Disabled is GREY, not a faded blue. A 35% blue is a tint, which rule 4 forbids, and it
    // reads as a lighter live button rather than a dead one — the one thing a disabled
    // control must not do. Disabled text is exempt from the contrast floor; looking disabled
    // is not.
    val bg by animateColorAsState(if (live) D.Blue else D.Line, label = "bg")
    val fg by animateColorAsState(if (live) D.White else D.Quiet, label = "fg")
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
        // An ink outline on white, not a grey body (ui/PORT.md §9). The grey was a container,
        // and with the containers gone it was the only thing on the screen still drawing a box
        // — a filled shape reads as the primary action, which is the one thing this button is
        // not. The border is the boundary, so it is 1.5dp and in ink rather than D.Line.
        modifier.then(PressScale(interaction)).heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp))
            .background(D.White)
            .border(1.5.dp, if (enabled) D.Black else D.Line, RoundedCornerShape(16.dp))
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Every button label is Type.heading (§3), so the two buttons read as one pair.
        Text(label, color = if (enabled) D.Black else D.Quiet, style = Type.heading)
    }
}

@Composable
internal fun TextLink(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Box(
        Modifier.padding(top = 6.dp).then(modifier).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) D.Black else D.Quiet, style = Type.heading) }
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


// ---------------------------------------------------------------------- the back arrow

/** How far the arrow glyph's ink starts inside its own 24 box: the path begins at x = 9 of 24. */
private val BACK_INK_INSET = 9.dp

/**
 * What the arrow takes in a row: its ink (6) and the gap to what follows (12). The *touch target*
 * is still 48 — it overhangs this slot to the right, under whatever comes next.
 */
private val BACK_SLOT = 18.dp

/**
 * The back arrow of every screen's title row, with its tip on the page's start edge.
 *
 * It used to be a 48 circle with the 24 glyph centred in it. The glyph's ink is only 6 wide and
 * starts 9 into its own box, and the box is centred in a 48 target — so the arrow's tip sat 21dp
 * in from the edge, 9dp to the right of the content under it, and the rows that tried to fix it
 * with a negative offset (`-12`) only got two-thirds of the way. Here the glyph is slid left by
 * its own margin and the 48 target starts exactly on the edge, so **the tip aligns with the
 * content** and the target is still a full 48.
 *
 * It occupies [BACK_SLOT] in the row (ink plus a 12 gap) rather than the 48, so a title follows it
 * at a sensible distance; the target overhangs that slot to the right and is hit-tested through
 * the row. [onBack] null draws the arrow without a click, for a screen reached with no back.
 */
@Composable
internal fun BackArrow(onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val press = remember { MutableInteractionSource() }
    val tap = onBack?.let { hapticBack(it) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.width(BACK_SLOT).wrapContentWidth(Alignment.Start, unbounded = true)) {
        Box(
            Modifier.then(PressScale(press)).size(48.dp)
                .then(
                    if (tap == null) Modifier
                    else Modifier
                        .clickable(press, indication = null, onClick = tap)
                        .semantics { contentDescription = "Back" },
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            HiIcon(
                Hi.ArrowLeft, D.Black, 24.dp,
                modifier = Modifier.offset(x = if (rtl) BACK_INK_INSET else -BACK_INK_INSET),
            )
        }
    }
}

// ---------------------------------------------------------------------- sheet motion

/**
 * How a sheet rises: a plain ease, and **no overshoot**.
 *
 * Sheets used to rise on `Arrive`, a spring with damping 0.75, which passes its resting position
 * and comes back. For a panel that sits against the bottom edge that is not a flourish, it is a
 * fault: the overshoot lifts the panel *above* where it belongs, opening a strip at the bottom of
 * the screen where the page behind shows through, and the whole sheet visibly rides up and drops
 * back. A panel that arrives from an edge stops at the edge.
 */
internal val SheetRise = tween<Float>(320, easing = FastOutSlowInEasing)

/** Letting go of a drag that did not dismiss: back to rest, critically damped so it cannot pass it. */
internal val SheetSettle = spring<Float>(dampingRatio = 1f, stiffness = 300f)

/**
 * Lets a sheet be pulled down by dragging its *content*, the way Google Pay's and Photos' sheets
 * are — not only by their handle.
 *
 * A sheet whose body scrolls swallows every drag in the body: the scroll takes it, and the sheet's
 * own `draggable` never hears about it, so the sheet only moved if you happened to grab the few
 * pixels of handle. This connection sits between them. A downward drag the content cannot use any
 * more (it is already at the top) moves the sheet; an upward drag while the sheet is displaced
 * moves the sheet back first; and letting go hands the velocity to [onRelease], which decides
 * between dismissing and settling, exactly as the handle does.
 *
 * [offset] is the sheet's own displacement (positive is down); it is never driven below zero, so
 * the sheet cannot be pulled up past its resting edge.
 */
internal fun sheetNestedScroll(
    offset: Animatable<Float, *>,
    scope: CoroutineScope,
    enabled: () -> Boolean,
    onRelease: (velocity: Float) -> Unit,
): NestedScrollConnection = object : NestedScrollConnection {
    private fun move(to: Float) {
        // Undispatched, so the sheet follows the finger in the same frame rather than one later.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(to) }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!enabled() || source != NestedScrollSource.UserInput) return Offset.Zero
        // Content dragged up while the sheet is down: bring the sheet back before scrolling it.
        if (available.y < 0f && offset.value > 0f) {
            val next = (offset.value + available.y).coerceAtLeast(0f)
            val used = next - offset.value
            move(next)
            return Offset(0f, used)
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (!enabled() || source != NestedScrollSource.UserInput) return Offset.Zero
        // What the content could not use. Downward, that is the sheet's to take.
        if (available.y > 0f) {
            move(offset.value + available.y)
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!enabled() || offset.value <= 0f) return Velocity.Zero
        onRelease(available.y)
        return available
    }
}

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
        offset.animateTo(0f, SheetRise)
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
            // Never above its resting edge: pulled up, it simply stays put.
            offset.snapTo((offset.value + delta).coerceAtLeast(0f))
        }
    }
    // Dragging the content pulls the tray too, not only the handle — see [sheetNestedScroll].
    val nested = remember {
        sheetNestedScroll(offset, scope, enabled = { dismissible && !leaving }) { v ->
            if (offset.value > height * .25f || v > 1_200f) leave()
            else scope.launch { offset.animateTo(0f, SheetSettle, initialVelocity = v) }
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
                .graphicsLayer { translationY = if (shown) offset.value.coerceAtLeast(0f) else size.height }
                .nestedScroll(nested)
                .draggable(
                    drag, Orientation.Vertical, enabled = shown && !leaving && dismissible,
                    onDragStopped = { velocity ->
                        // Far enough, or flicked: go. Otherwise settle back.
                        if (offset.value > height * .25f || velocity > 1_200f) leave()
                        else scope.launch { offset.animateTo(0f, SheetSettle, initialVelocity = velocity) }
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
 * Slots are staggered left to right, because a number is read that way. Slots are keyed from the
 * RIGHT, so the units digit is the units digit whether the number has two digits or three.
 *
 * **The roll is drawn, never laid out.** It used to be `AnimatedContent` with slide transitions,
 * which move a digit with a layout offset — and a layout offset moves the digit's *baseline* too.
 * The Row is baseline-aligned, so while a digit slid the whole figure grew taller by the distance
 * it travelled, and everything under the balance was pushed down until the roll finished. Here the
 * digit that stays in layout is the *new* one, at rest, so size and baseline never change; the
 * old digit is a zero-size ghost and both are moved with `graphicsLayer`, which is draw-only.
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
    // False through the first composition, true after: a digit that appears LATER (the number
    // gained a place) rolls in, while the figure's first draw does not animate at all.
    var settled by remember { mutableStateOf(false) }
    SideEffect { settled = true }
    // Clipped to the figure's own box, so a digit rolling in from below or out above is cut at the
    // edge of the number the way an odometer's window cuts it, instead of drawing over whatever
    // sits next to it. Draw-only, like the roll itself: it changes no size and no baseline.
    //
    // A custom layout and not a `Row`, because this figure has to REPORT A BASELINE and a `Row` of
    // `alignByBaseline` children does not reliably pass one up: with some values the whole part
    // had none, the balance row then aligned it by its bottom, and the whole digit sat a line
    // below its own `$` and cents. Here the digits are lined up on their shared baseline and that
    // baseline is handed to the parent explicitly, whatever the value.
    Layout(
        content = {
            text.forEachIndexed { i, ch ->
                key(text.length - 1 - i) {
                    RollingDigit(
                        ch, index = i, rollIn = settled,
                        fontSize = fontSize, color = color, fontWeight = fontWeight,
                        fontFamily = fontFamily,
                        modifier = Modifier,
                    )
                }
            }
        },
        modifier = modifier.clipToBounds(),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        // Each digit's own baseline; a Text always has one, but fall back to its height so a
        // surprise can never produce a negative size.
        val baselines = placeables.map { p ->
            p[FirstBaseline].let { if (it == AlignmentLine.Unspecified) p.height else it }
        }
        val above = baselines.maxOrNull() ?: 0
        val below = placeables.indices.maxOfOrNull { placeables[it].height - baselines[it] } ?: 0
        val width = placeables.sumOf { it.width }
        layout(
            width.coerceIn(constraints.minWidth, constraints.maxWidth.coerceAtLeast(constraints.minWidth)),
            above + below,
            mapOf(FirstBaseline to above, LastBaseline to above),
        ) {
            var x = 0
            placeables.forEachIndexed { i, p ->
                p.placeRelative(x, above - baselines[i])
                x += p.width
            }
        }
    }
}

/**
 * One slot of [FlowFigure]. See there for why the movement is draw-only.
 *
 * The digit is a bare `Text` straight in the row — no wrapper — because it is the `Text` that
 * carries the baseline `alignByBaseline()` reads; a `Box` around it did not pass the baseline up,
 * and the digits then sat on different lines. The OLD digit is not a second child at all: it is
 * painted by `drawBehind` with a `TextMeasurer`, outside the `graphicsLayer` that moves the new
 * one, so it cannot take space or move a baseline however it is animated.
 */
@Composable
private fun RollingDigit(
    ch: Char,
    index: Int,
    rollIn: Boolean,
    fontSize: TextUnit,
    color: Color,
    fontWeight: FontWeight,
    fontFamily: FontFamily?,
    modifier: Modifier,
) {
    var shown by remember { mutableStateOf(ch) }
    var ghost by remember { mutableStateOf<Char?>(null) }
    var up by remember { mutableStateOf(true) }
    // 0 → 1 over one roll. Starts finished so a first draw is still, and so is a digit that
    // never changes.
    val roll = remember { Animatable(if (rollIn) 0f else 1f) }

    LaunchedEffect(ch) {
        if (ch == shown && roll.value == 1f) return@LaunchedEffect
        if (ch != shown) {
            ghost = shown
            up = ch > shown
            shown = ch
            roll.snapTo(0f)
        }
        // Left to right: a number is read that way.
        delay(index * 35L)
        roll.animateTo(1f, Settle)
        ghost = null
    }

    val measurer = rememberTextMeasurer()
    val face = Figures.copy(
        fontSize = fontSize, fontWeight = fontWeight,
        fontFamily = fontFamily ?: Figures.fontFamily,
    )
    val t = roll.value
    Text(
        shown.toString(), color = color, fontSize = fontSize, fontWeight = fontWeight,
        fontFamily = fontFamily, style = Figures, maxLines = 1,
        modifier = modifier
            // Outside the layer below, so it is not moved by it: the digit that was here, sliding
            // out the way the new one slides in, fading as it goes.
            .drawBehind {
                val old = ghost ?: return@drawBehind
                val travel = size.height
                drawText(
                    measurer, old.toString(),
                    topLeft = Offset(0f, t * (if (up) -travel else travel)),
                    style = face.copy(color = color.copy(alpha = (1f - t / .4f).coerceIn(0f, 1f))),
                    maxLines = 1,
                )
            }
            .graphicsLayer {
                val travel = size.height
                translationY = (1f - t) * (if (up) travel else -travel)
                alpha = (t / .6f).coerceIn(0f, 1f)
            },
    )
}

// ------------------------------------------------------------------- shared primitives
//
// Each of these existed three or four times over, privately, in the screen files. Kotlin lets
// two files in one package each keep their own `private` top-level declaration, so the
// compiler never said a word — and the copies drifted. The row chevron was shipping at two
// different sizes AND two different stroke weights; one of the four did not mirror for RTL.
// That is the drift PARITY.md keeps a record of, and the cure is that there is one of each.

/**
 * The disclosure chevron at the end of a row.
 *
 * The geometry is the sandbox's `chevron` mark: a 16 box with the glyph on the 24 grid
 * (`M4.8 0 19.2 12 4.8 24`, stroke 3.744) — hence `.2`→`.8` and `.156`. The two 9x15 copies
 * were a different shape at a different weight.
 *
 * It mirrors for Arabic and Hebrew, because it means "forward" and forward is not always
 * right. [turn] is for the ones that point down when a section is open; a rotation needs no
 * mirroring of its own, which is why it is a rotation and not a second glyph.
 */
@Composable
internal fun Chevron(side: Dp = 16.dp, tint: Color = D.Quiet, turn: Float = 0f) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(
        Modifier.size(side).graphicsLayer {
            rotationZ = turn
            if (rtl) scaleX = -1f
        },
    ) {
        // `size` here is the DrawScope's, not the Dp parameter — the parameter is shadowed
        // inside this lambda, which is why the box side is read off the scope.
        val w = size.width
        val h = size.height
        val stroke = w * .156f
        drawLine(tint, Offset(w * .2f, 0f), Offset(w * .8f, h / 2), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * .8f, h / 2), Offset(w * .2f, h), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

/**
 * This step, set in the figure face with tabular digits.
 *
 * The sandbox's `<Amount>` chooses the face and leaves the size to the caller, so "a caption
 * that happens to be a number" is a scale step wearing Geist rather than a step of its own.
 * Four files had written this one line out for themselves.
 */
internal fun TextStyle.figure(): TextStyle = copy(fontFamily = Figure, fontFeatureSettings = "tnum")

/**
 * This step, set in the monospace face.
 *
 * [Type.mono] welds a size (13/18) to the face, so there was no way to say "an address at the
 * body step" without writing the copy out again — which three files did.
 */
internal fun TextStyle.monospaced(): TextStyle = copy(fontFamily = Mono)

/**
 * An SKR figure.
 *
 * `Int` in, because the server sends whole SKR and the unit has no decimals — and still
 * through [Money], because the phone's locale is what groups the digits. `"$n"` reads "1000"
 * everywhere; this reads "1,000" in the US and "1.000" in Germany.
 */
internal fun skr(n: Int, sign: Boolean = false): String =
    Money.tokenAmount(java.math.BigDecimal(n), "SKR", sign = sign)

/**
 * A letter in a disc — the app's one avatar.
 *
 * Five places drew this, and they disagreed about the letter's weight. The letter is 0.38x the
 * disc: part of the avatar's own composition, not a step on the type scale, which is why it is
 * a ratio here and not an `sp`. A white disc with a hairline is everybody; 2dp of blue is a
 * verified Seeker.
 */
@Composable
internal fun Initial(
    letter: String,
    size: Dp,
    seeker: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(D.White)
            .border(if (seeker) 2.dp else 1.5.dp, if (seeker) D.Blue else D.Line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter, color = if (seeker) D.Black else D.Quiet,
            fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.Bold, fontFamily = Ui,
        )
    }
}

// ------------------------------------------------------------------------ copying a value

/** How long the tick stays after a copy, and how long it takes to pop in. §5. */
private const val COPIED_MS = 1_600L
private const val COPIED_POP_MS = 180

/**
 * One copy behaviour for the whole app: the code, the PIN, a signature, the referral code, the
 * wallet address.
 *
 * ui/PORT-2026-10-02.md §5. Three things it guarantees, each of which was wrong somewhere
 * before:
 *
 *  - **The clipboard gets the value, not the display.** A code is shown in groups for reading
 *    and pasted as one run of characters; copying what is on screen pastes the spaces with it.
 *  - **It says it worked.** The mark becomes a tick for 1.6s. A copy that looks identical
 *    before and after leaves somebody pressing it again to be sure.
 *  - **It says so out loud too**, so the confirmation is not only a colour.
 */
@Composable
internal fun rememberCopy(): (String) -> Unit {
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    return remember(clipboard, view) {
        { value ->
            clipboard.setText(AnnotatedString(value.filterNot { it.isWhitespace() }))
            view.announceForAccessibility("Copied")
        }
    }
}

/**
 * CSS `ease-out`, which is what `copy-pop` is timed on. Compose ships no equivalent — its
 * `LinearOutSlowIn` is Material's curve, not the browser's.
 */
private val PopEase = CubicBezierEasing(0f, 0f, .58f, 1f)

/**
 * The tick that replaces a copy mark, popping in rather than cutting.
 *
 * One 0→1 run drives both channels, because `copy-pop` is one keyframe: `from { scale(.5);
 * opacity: 0 }`. Scale and alpha are read off the same progress rather than alpha being read off
 * the scale — doing the latter starts the tick at 50% opacity, so the pop is half a fade-in and
 * the first frame is already visible.
 */
@Composable
private fun CopiedTick(size: Dp) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, tween(COPIED_POP_MS, easing = PopEase)) }
    HiIcon(
        Hi.Tick, D.SuccessInk, size,
        modifier = Modifier.graphicsLayer {
            val s = lerp(.5f, 1f, pop.value)
            scaleX = s; scaleY = s; alpha = pop.value
        },
    )
}

/**
 * A copy mark alone, in a 48 touch target — the form used at the end of a row.
 *
 * The mark is blue ink, not black: it is the one thing in the row you can act on, and `Copy.tsx`
 * marks it `text-blue-ink` for that reason. `label` names what is being copied, so a screen
 * reader says "Copy PIN" rather than "Copy" four times down one card.
 *
 * @param size 24 at the end of a row, 20 beside a line of body text — `CopyIcon`'s own note.
 */
@Composable
internal fun CopyMark(
    value: String,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val copy = rememberCopy()
    var done by remember(value) { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) { delay(COPIED_MS); done = false } }
    val say = if (done) "Copied" else listOfNotNull("Copy", label).joinToString(" ")
    Box(
        modifier.size(48.dp).clip(CircleShape)
            .clickable { copy(value); done = true }
            .semantics { contentDescription = say },
        contentAlignment = Alignment.Center,
    ) {
        if (done) CopiedTick(size) else HiIcon(Hi.Copy, D.BlueInk, size)
    }
}

/**
 * The outlined pill with the word — the referral code and the wallet address.
 *
 * The word changes with the mark and the outline goes green with it, because at pill size the
 * mark alone is a small thing to notice.
 */
@Composable
internal fun CopyPill(value: String, modifier: Modifier = Modifier) {
    val copy = rememberCopy()
    var done by remember(value) { mutableStateOf(false) }
    LaunchedEffect(done) { if (done) { delay(COPIED_MS); done = false } }
    val press = remember { MutableInteractionSource() }
    val edge = if (done) D.SuccessInk else D.Line
    val ink = if (done) D.SuccessInk else D.Black
    Box(
        modifier.then(PressScale(press)).heightIn(min = 48.dp)
            .clickable(press, indication = null) { copy(value); done = true }
            .semantics { contentDescription = if (done) "Copied" else "Copy" },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.border(1.5.dp, edge, CircleShape).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (done) CopiedTick(16.dp) else HiIcon(Hi.Copy, ink, 16.dp)
            Spacer(Modifier.width(Space.tight))
            Text(if (done) "Copied" else "Copy", color = ink, style = Type.caption)
        }
    }
}

/**
 * The AirPay mark: a wallet with two cards coming out of it (airpay-brand/README.md).
 *
 * Two tones of the one drawing. The colour mark is for white and light grounds; [white] is the
 * one-colour mark, for a blue or dark ground, where the dark card becomes a gap. The mark is
 * wider than it is tall, so it is fitted inside a square of [side] and never stretched.
 *
 * It sits beside the name or above it, as in the lockups; it is never used as a letter of it.
 */
@Composable
internal fun BrandMark(side: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, white: Boolean = false) {
    androidx.compose.foundation.Image(
        painterResource(if (white) R.drawable.brand_mark_white else R.drawable.brand_mark),
        contentDescription = null,
        modifier = modifier.size(side),
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
    )
}
