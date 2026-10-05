package so.duffle.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.lerp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.delay

/**
 * The first screen of a fresh install, and the one a log out returns to.
 *
 * It shows the app instead of describing it, in four pictures that take turns, each drawn with
 * the app's own parts so none of them can drift from the screen it stands for:
 *  - the tap sheet: the card held out, the sheet under it, the other wallets, the button;
 *  - a wheel of the things the shop sells, one in focus and its neighbours faint;
 *  - the SKR token, and small ones gathering round it ring by ring;
 *  - the four things it is for, as one mark that keeps becoming the next, among small ones;
 *
 * Nothing here touches the network or any stored state. It is a door, and Connect is the only
 * thing behind it. [startAt] exists for the preview build, to open on a given scene.
 */
@Composable
internal fun Landing(busy: Boolean, startAt: Int = 0, onConnect: () -> Unit) {
    // Counts up for ever and is never wound back, so going from the last picture to the first
    // is one step forward like any other and not a rewind through the two in between.
    var step by remember { mutableIntStateOf(startAt.coerceIn(0, SCENES.lastIndex)) }
    val scene = step % SCENES.size
    // Holds still while the wallet is opening: a page that moves behind a wallet sheet is noise.
    LaunchedEffect(busy, step) {
        if (busy) return@LaunchedEffect
        delay(SCENES[scene].holdMs)
        step++
    }
    val at by animateFloatAsState(step.toFloat(), tween(900, easing = FastOutSlowInEasing), label = "scene")
    // How much of picture [i] is showing: 1 when it is the one on screen, 0 when it is not.
    fun showing(i: Int): Float {
        val d = kotlin.math.abs((at % SCENES.size) - i)
        return (1f - minOf(d, SCENES.size - d)).coerceIn(0f, 1f)
    }
    val p = 1f - showing(3)

    BoxWithConstraints(
        Modifier.fillMaxSize().background(D.White)
            .clickable(remember { MutableInteractionSource() }, indication = null) { step++ },
    ) {
        val w = maxWidth
        val h = maxHeight
        val bars = WindowInsets.systemBars.asPaddingValues()
        val top = bars.calculateTopPadding() + 64.dp
        val foot = bars.calculateBottomPadding()
        // The room the pictures have: from under the name to where the glow begins.
        val room = maxHeight - top - foot - GLOW_RISE + 14.dp
        // 282 is everything in the first picture but the held-out card: the gaps, the sheet's
        // heading, one card's peek, and the button. Only the card gives when the room is short.
        val k = ((room - 282.dp) / 196.dp).coerceIn(0.62f, 1.18f)

        // What is behind the sheet on a real phone, dimmed: one flat grey, and nothing in it.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - p }.background(BACKDROP))

        // ---- The tap sheet -------------------------------------------------------------------
        val cardH = 196.dp * k
        val cardY = top + 24.dp
        val sheetY = cardY + cardH + 18.dp
        val stackY = sheetY + 66.dp
        val peek = 64.dp
        // The sheet leaving: it sinks a little, shrinks a little and thins out. Used twice, so
        // the button drawn over the glow leaves exactly as the sheet under it does.
        val leaving = Modifier.fillMaxSize().graphicsLayer {
            alpha = (1f - p * 1.8f).coerceIn(0f, 1f)
            translationY = 36.dp.toPx() * p
            val s = 1f - 0.05f * p
            scaleX = s; scaleY = s
            transformOrigin = TransformOrigin(0.5f, 0.3f)
        }
        if (p < 1f) Box(leaving) {
            SampleCard(SAMPLES[0], Modifier.offset(x = Space.gutter, y = cardY).width(w - Space.gutter * 2).height(cardH), held = true)
            Column(
                Modifier.offset(y = sheetY).fillMaxWidth().height(h)
                    .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(D.White),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.size(width = 40.dp, height = 5.dp).clip(CircleShape).background(D.Line))
                Spacer(Modifier.height(14.dp))
                Text("Hold near to pay", color = D.Black, style = Type.title)
            }
            for (i in 1..2) SampleCard(
                SAMPLES[i],
                Modifier.offset(x = Space.gutter, y = stackY + peek * (i - 1)).width(w - Space.gutter * 2).height(196.dp),
            )
            // The last card fades into the sheet, as it does on the real one.
            Column(Modifier.offset(y = stackY + peek + 50.dp).fillMaxWidth().height(h)) {
                Box(Modifier.fillMaxWidth().height(70.dp).background(Brush.verticalGradient(0f to D.White.copy(alpha = 0f), 1f to D.White)))
                Box(Modifier.fillMaxSize().background(D.White))
            }
        }

        // ---- The wheel -----------------------------------------------------------------------
        // Each of the other pictures arrives the same way: it comes down a little into place
        // as it sharpens out of nothing, once the one before it is most of the way gone.
        fun arriving(i: Int): Modifier {
            val v = showing(i)
            return Modifier.offset(y = top).fillMaxWidth().height(room)
                .graphicsLayer { alpha = ((v - 0.35f) / 0.65f).coerceIn(0f, 1f); translationY = -28.dp.toPx() * (1f - v) }
        }
        if (showing(1) > 0f) Wheel(running = scene == 1, modifier = arriving(1))
        if (showing(2) > 0f) Earn(running = scene == 2, modifier = arriving(2))
        if (showing(0) > 0f) Four(shown = showing(0), running = scene == 0, modifier = arriving(0))

        // ---- The glow ------------------------------------------------------------------------
        // A dome and not a band: tallest in the middle and falling away to the corners, so it
        // reads as light coming up from under the button and not as a blue footer.
        // The top of it is a long, eased fade and not a short one: a short fade reads as an edge,
        // and the glow should have no edge, only a place where the white has stopped.
        Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(GLOW_RISE + GLOW_HAZE + foot)) {
            val r = size.height
            val centre = Offset(size.width / 2f, size.height)
            scale(scaleX = (size.width * 1.08f) / r, scaleY = 1f, pivot = centre) {
                drawCircle(
                    Brush.radialGradient(
                        0f to D.Blue, 0.52f to D.Blue,
                        0.58f to D.Blue.copy(alpha = 0.9f), 0.64f to D.Blue.copy(alpha = 0.72f),
                        0.70f to D.Blue.copy(alpha = 0.52f), 0.76f to D.Blue.copy(alpha = 0.34f),
                        0.82f to D.Blue.copy(alpha = 0.2f), 0.88f to D.Blue.copy(alpha = 0.1f),
                        0.94f to D.Blue.copy(alpha = 0.035f), 1f to D.Blue.copy(alpha = 0f),
                        center = centre, radius = r,
                    ),
                    radius = r, center = centre,
                )
            }
        }

        // Over the glow, not under it: a blue button seen through blue haze goes grey.
        if (p < 1f) Box(leaving) {
            Text(
                "Request instead", color = D.White, style = Type.heading, maxLines = 1,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = stackY + peek + 66.dp)
                    .clip(CircleShape).background(D.Blue).padding(horizontal = 28.dp, vertical = 15.dp),
            )
        }

        // The wordmark as Home draws it. It stays through every picture, Home's included: that
        // picture leaves out Home's own header, so the name is never on the page twice.
        Text(
            "AirPay", color = D.Black, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
            fontFamily = Display, letterSpacing = (-0.5).sp,
            modifier = Modifier.statusBarsPadding().padding(start = Space.gutter, top = 13.dp),
        )

        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().fillMaxWidth()
                .padding(horizontal = Space.gutter).padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The words change the way the picture does: the old line sinks and thins out as the
            // new one rises through it. Centred, because the middle is where the glow is deepest.
            AnimatedContent(
                scene, label = "words",
                transitionSpec = {
                    (fadeIn(tween(520, delayMillis = 240)) + slideInVertically(tween(620, delayMillis = 160, easing = FastOutSlowInEasing)) { it / 3 }) togetherWith
                        (fadeOut(tween(320)) + slideOutVertically(tween(480, easing = FastOutSlowInEasing)) { -it / 3 })
                },
            ) { i ->
                Column(Modifier.fillMaxWidth().height(84.dp), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(SCENES[i].title, color = D.White, style = Type.title.copy(fontSize = 28.sp, lineHeight = 33.sp), textAlign = TextAlign.Center, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        SCENES[i].line, color = D.White, style = Type.body.copy(fontSize = 16.sp, lineHeight = 22.sp),
                        textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 290.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (i in SCENES.indices) {
                    val on = scene == i
                    val dot by animateDpAsState(if (on) 22.dp else 6.dp, spring(dampingRatio = 0.8f, stiffness = 300f), label = "dot")
                    Box(Modifier.padding(horizontal = 3.dp).height(6.dp).width(dot).clip(CircleShape).background(D.White.copy(alpha = if (on) 1f else 0.45f)))
                }
            }
            Spacer(Modifier.height(14.dp))
            WhiteButton(if (busy) "Opening your wallet…" else "Connect wallet", enabled = !busy, onClick = onConnect)
            Spacer(Modifier.height(12.dp))
            // Which wallets this opens, which is the one thing worth knowing before pressing the
            // button above: any wallet app that speaks Mobile Wallet Adapter, these four among them.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Works with", color = D.White, style = Type.body)
                Spacer(Modifier.width(8.dp))
                // Each wallet's mark, whole, on that wallet's own colour: the marks are drawn
                // to sit on those colours (Backpack's red bag on its black, Phantom's ghost on
                // its purple) and mean nothing cut out of them. Overlapped like a stack of
                // coins, so four marks read as one line and not a menu.
                Row(horizontalArrangement = Arrangement.spacedBy((-7).dp)) {
                    for ((mark, ground) in WALLET_MARKS) Box(
                        Modifier.size(26.dp).border(1.5.dp, D.White, CircleShape).padding(1.5.dp).clip(CircleShape).background(ground),
                        contentAlignment = Alignment.Center,
                    ) { Image(painterResource(mark), null, Modifier.size(14.dp), contentScale = ContentScale.Fit) }
                }
                Spacer(Modifier.width(8.dp))
                Text("and more", color = D.White, style = Type.body)
            }
        }
    }
}

private class Scene(val title: String, val line: String, val holdMs: Long)

private val SCENES = listOf(
    Scene("Your daily payments app", "Pay people and bills, earn SKR, from\nyour wallet.", 6_600L),
    // Long enough for the wheel to go once round.
    Scene("Spend it on real things", "Paid for in USDC or SOL, straight from your wallet.", 8_600L),
    Scene("Earn SKR on payments", "Scratch cards, offers and invites on your Seeker.", 6_400L),
    Scene("Tap phones to pay", "Hold two phones together and the money moves.", 4_600L),
)

private val WALLET_MARKS = listOf(
    R.drawable.ic_wallet_seeker to Color(0xFF000000),
    R.drawable.ic_wallet_phantom to Color(0xFF9886E5),
    R.drawable.ic_wallet_solflare to Color(0xFFFFEF47),
    R.drawable.ic_wallet_backpack to Color(0xFF212122),
)

private val BACKDROP = Color(0xFFECEDEF)
/** The Connect button: the width the widest title comes to, near enough. */
private val BUTTON_WIDTH = 318.dp

// Illustration pigments for the first scene. Declared up here because the list of the four
// uses two of them, and a top-level value has to exist before the one that reads it.
private val SKY = Color(0xFF7CC4FF)
private val SUN = Color(0xFFFFC53D)
private val EMBER = Color(0xFFFF6A2B)
private val LEAF = Color(0xFF14B866)
private val SAND = Color(0xFFEFE7DA)
private val ROSE = Color(0xFFFF4F7B)

/** How high the glow reaches in the middle, above the navigation bar. */
private val GLOW_RISE = 300.dp
/** How much further its fade carries on above that, thinning to nothing. */
private val GLOW_HAZE = 130.dp

/**
 * The wallets in the first picture. They are [Linked] so the cards are drawn by [WalletFace], the
 * same function that draws them everywhere else, and cannot drift from it. The addresses are made
 * up and far too short to be anybody's; the balances are examples.
 */
private class Sample(val wallet: Linked, val usd: Double)

private fun sample(name: String, address: String, usd: Double) =
    Sample(Linked(address = address, token = null, name = name, uri = null, addedAt = 0L), usd)

private val SAMPLES = listOf(
    sample("Seeker", "7xKpairpayq3Fn", 128.40),
    sample("Phantom", "Bq2mairpayWd8T", 56.02),
    sample("Solflare", "Hn5vairpayc9Rz", 12.75),
)

@Composable
private fun SampleCard(s: Sample, modifier: Modifier, held: Boolean = false) {
    WalletFace(
        s.wallet, active = false, usd = { s.usd }, loading = { false }, lift = { if (held) 1f else 0f }, onCopy = {},
        // Unbounded and top-aligned, as the real stack does it: the card keeps its own layout
        // and is cut off, instead of squeezing itself into the room it is given.
        modifier = modifier,
        corner = if (held) ({ ContactlessPulse(D.Black) }) else null,
    )
}

/**
 * SKR piling up. The token, large, in the middle; then small ones appear round its edge one at a
 * time until they close a ring, then a second ring outside that, fainter
 * than the first. Once they are all there the rings turn slowly, each
 * the other way from its neighbour. It starts again from the bare token every time it comes round.
 */
@Composable
private fun Earn(running: Boolean, modifier: Modifier) {
    val fill = remember { Animatable(0f) }
    LaunchedEffect(running) {
        if (!running) { fill.snapTo(0f); return@LaunchedEffect }
        delay(350)
        fill.animateTo(1f, tween(3_400, easing = LinearEasing))
    }
    val turn by rememberInfiniteTransition(label = "rings").animateFloat(
        0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart), label = "turn",
    )
    val coin = ImageBitmap.imageResource(R.drawable.skr_token)
    val total = RINGS.sumOf { it.count }
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            // Sized off the room there is: the outermost ring just reaches the sides.
            val unit = (size.width / 2f) / RING_FIELD
            var n = 0
            for ((r, ring) in RINGS.withIndex()) {
                val side = ring.size * unit
                for (i in 0 until ring.count) {
                    // Each coin has its own moment, in order round the ring and ring by ring,
                    // and arrives over the space of a few of them so it grows in and is not switched on.
                    val born = ((fill.value * (total + 8) - n) / 8f).coerceIn(0f, 1f)
                    n++
                    if (born <= 0f) continue
                    val pop = FastOutSlowInEasing.transform(born)
                    val a = 2 * Math.PI * (i.toFloat() / ring.count + turn * (if (r % 2 == 0) 1 else -1) * ring.speed) - Math.PI / 2
                    val cx = center.x + kotlin.math.cos(a).toFloat() * ring.radius * unit
                    val cy = center.y + kotlin.math.sin(a).toFloat() * ring.radius * unit
                    val d = (side * (0.4f + 0.6f * pop)).toInt().coerceAtLeast(1)
                    drawImage(
                        coin,
                        dstOffset = androidx.compose.ui.unit.IntOffset((cx - d / 2f).toInt(), (cy - d / 2f).toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(d, d),
                        alpha = ring.alpha * pop,
                    )
                }
            }
        }
        Image(
            painterResource(R.drawable.skr_token), null,
            Modifier.size(164.dp).offset(y = float(2800, 3.dp)),
        )
    }
}

/** One ring of small coins: how far out (in units of the picture), how many, how big, how faint, how fast. */
private class Ring(val radius: Float, val count: Int, val size: Float, val alpha: Float, val speed: Float)

/** Half the width of the page, in the units the rings are measured in: sets how big they are drawn. */
private const val RING_FIELD = 182f

private val RINGS = listOf(
    Ring(102f, 22, 24f, 1f, 3f),
    Ring(134f, 28, 24f, 0.6f, 2.2f),
)

/**
 * What the app is for, as one mark in the middle that keeps becoming the next: pay somebody, pay
 * a bill, buy something, earn SKR. Its colour, its corners and its name change with it. Round it,
 * on faint rings, the same four small, among the things that go with money changing hands kindly:
 * stars, a coin, arrows on their way, a heart, a shield, a padlock, a wallet, a present. No rings and no loose dots: every piece
 * is something. They come out from the middle when the
 * picture arrives and then drift, each at its own pace.
 *
 * The colours of the scattered pieces are illustration pigments, not palette tokens: the same
 * licence the wallet cards and the artwork on the rewards page take.
 */
@Composable
private fun Four(shown: Float, running: Boolean, modifier: Modifier) {
    // Counts up and is never wound back: going from the fourth to the first is one more quarter
    // turn like the three before it. Wound back, the shape span three quarters the wrong way
    // on every lap, which was the flick at the end of each round.
    var turns by remember { mutableIntStateOf(0) }
    val which = turns % USES.size
    val bump = remember { Animatable(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            delay(1_250)
            turns++
            // Up and back, both animated: snapped up, it was a jump in size on every change.
            bump.animateTo(1f, tween(140, easing = FastOutSlowInEasing))
            bump.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 240f))
        }
    }
    val use = USES[which]
    val ground by animateColorAsState(use.ground, tween(420), label = "ground")
    val corner by animateFloatAsState(use.corner, spring(dampingRatio = 0.6f, stiffness = 220f), label = "corner")
    val tilt by animateFloatAsState(turns * 90f, spring(dampingRatio = 0.7f, stiffness = 160f), label = "tilt")
    val drift by rememberInfiniteTransition(label = "drift").animateFloat(
        0f, 1f, infiniteRepeatable(tween(8_000, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    // Out from the middle as the picture arrives.
    val out = FastOutSlowInEasing.transform(((shown - 0.4f) / 0.6f).coerceIn(0f, 1f))

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // The scatter is laid out for a stage 400 wide and drawn to whatever there is.
        val u = (maxWidth / 400.dp).coerceAtMost(maxHeight / 430.dp)
        Canvas(Modifier.fillMaxSize()) {
            val unit = 1.dp.toPx() * u
            for ((i, b) in BITS.withIndex()) {
                val a = 2 * Math.PI * (drift * b.pace + i * 0.137)
                val c = Offset(
                    center.x + (b.x * out + kotlin.math.cos(a).toFloat() * 3f) * unit,
                    center.y + (b.y * out + kotlin.math.sin(a).toFloat() * 5f) * unit,
                )
                val size = b.size * unit * (0.3f + 0.7f * out)
                rotate(b.turn + kotlin.math.sin(a).toFloat() * 6f, c) { bit(b.kind, c, size, b.colour, out) }
            }
        }
        // The four, small, where the eye finds them on the middle ring.
        for ((i, small) in USES.withIndex()) {
            val a = 2 * Math.PI * (drift * 0.5 + i * 0.25)
            val p = SMALL_AT[i]
            Box(
                Modifier.offset(x = (p.first * out).dp * u + (kotlin.math.cos(a).toFloat() * 3f).dp, y = (p.second * out).dp * u + (kotlin.math.sin(a).toFloat() * 4f).dp)
                    .size(68.dp * u).graphicsLayer { val z = 0.4f + 0.6f * out; scaleX = z; scaleY = z; alpha = out }
                    .clip(CircleShape).background(small.ground),
                contentAlignment = Alignment.Center,
            ) { UseArt(i, Modifier.fillMaxSize(if (small.picture != null) 0.72f else 0.56f)) }
        }
        // The one in the middle.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(172.dp * u)
                    .graphicsLayer {
                        val z = (0.5f + 0.5f * out) * (1f + 0.1f * bump.value)
                        scaleX = z; scaleY = z; rotationZ = tilt
                        shadowElevation = 14.dp.toPx(); shape = RoundedCornerShape((corner * 100).toInt()); clip = false
                        ambientShadowColor = ground.copy(alpha = .25f); spotShadowColor = ground.copy(alpha = .45f)
                    }
                    .clip(RoundedCornerShape((corner * 100).toInt())).background(ground),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    which, label = "mark",
                    // Turned back, so the mark stays upright while the shape under it turns.
                    modifier = Modifier.graphicsLayer { rotationZ = -tilt },
                    transitionSpec = {
                        (fadeIn(tween(260, delayMillis = 120)) + scaleIn(tween(360, delayMillis = 120), initialScale = 0.5f)) togetherWith
                            (fadeOut(tween(160)) + scaleOut(tween(220), targetScale = 0.5f))
                    },
                ) { i ->
                    UseArt(i, Modifier.size((if (USES[i].picture != null) 122.dp else 98.dp) * u))
                }
            }
            Spacer(Modifier.height(18.dp))
            AnimatedContent(
                which, label = "name",
                transitionSpec = {
                    (fadeIn(tween(260, delayMillis = 100)) + slideInVertically(tween(320, delayMillis = 100)) { it / 2 }) togetherWith
                        (fadeOut(tween(160)) + slideOutVertically(tween(220)) { -it / 2 })
                },
            ) { i ->
                Text(
                    USES[i].label, color = D.Black, style = Type.title, maxLines = 1,
                    modifier = Modifier.graphicsLayer { alpha = out }.background(D.White, CircleShape).padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * One of the four: what it is called, the colour it sits on, how round its corners are when it is
 * the one in the middle (0.5 is a circle), and its picture when it has one. SKR has: the token is
 * the token. The other three are drawn by [useArt], in white on their colour with one accent, as
 * things a person knows on sight: a paper plane for sending, a bill with a coin on it, a shopping
 * bag. The app's own line marks for these were read as marks only by somebody who already used
 * the app, which nobody on this page does yet.
 */
private class Use(val label: String, val ground: Color, val corner: Float, val picture: Int? = null)

private val USES = listOf(
    Use("Pay anyone", D.Blue, 0.5f),
    Use("Pay bills", LEAF, 0.3f),
    Use("Shop", EMBER, 0.5f),
    Use("Earn SKR", D.Black, 0.36f, R.drawable.skr_token),
)

@Composable
private fun UseArt(i: Int, modifier: Modifier) {
    val use = USES[i]
    if (use.picture != null) Image(painterResource(use.picture), null, modifier)
    else Canvas(modifier) { useArt(i, use.ground) }
}

/** The drawn three, filling the canvas they are given. [ground] is the disc's colour, for cut-outs. */
private fun DrawScope.useArt(i: Int, ground: Color) {
    val r = size.minDimension / 2f
    fun at(x: Float, y: Float) = Offset(center.x + x * r, center.y + y * r)
    val round = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.1f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    when (i) {
        // A paper plane, nose up and to the right: two wings folded along one crease, the under
        // one in shade.
        0 -> {
            val nose = at(0.96f, -0.9f); val tail = at(-0.98f, -0.12f); val foot = at(0.1f, 0.98f); val fold = at(-0.16f, 0.22f)
            val under = Path().apply { moveTo(nose.x, nose.y); lineTo(foot.x, foot.y); lineTo(fold.x, fold.y); close() }
            val over = Path().apply { moveTo(nose.x, nose.y); lineTo(tail.x, tail.y); lineTo(fold.x, fold.y); close() }
            drawPath(under, Color(0xFFCDE7FF)); drawPath(under, Color(0xFFCDE7FF), style = round)
            drawPath(over, Color.White); drawPath(over, Color.White, style = round)
        }
        // A bill: paper with a torn foot and three lines of print, and a coin laid on its corner.
        1 -> {
            val paper = Path().apply {
                moveTo(center.x - 0.86f * r, center.y - 0.74f * r)
                quadraticTo(center.x - 0.86f * r, center.y - 0.92f * r, center.x - 0.68f * r, center.y - 0.92f * r)
                lineTo(center.x + 0.42f * r, center.y - 0.92f * r)
                quadraticTo(center.x + 0.6f * r, center.y - 0.92f * r, center.x + 0.6f * r, center.y - 0.74f * r)
                lineTo(center.x + 0.6f * r, center.y + 0.9f * r)
                for (k in 1..6) lineTo(center.x + (0.6f - k * 0.2433f) * r, center.y + (if (k % 2 == 1) 0.72f else 0.9f) * r)
                close()
            }
            drawPath(paper, Color.White)
            for ((y, to) in listOf(-0.54f to 0.32f, -0.2f to 0.32f, 0.14f to -0.1f))
                drawLine(ground, at(-0.58f, y), at(to, y), strokeWidth = r * 0.13f, cap = StrokeCap.Round)
            drawCircle(ground, r * 0.5f, at(0.6f, 0.5f))
            drawCircle(Color(0xFFE9A51C), r * 0.42f, at(0.6f, 0.5f))
            drawCircle(SUN, r * 0.34f, at(0.6f, 0.5f))
            drawLine(Color.White.copy(alpha = 0.7f), at(0.5f, 0.36f), at(0.46f, 0.56f), strokeWidth = r * 0.07f, cap = StrokeCap.Round)
        }
        // A shopping bag: the body, its two handles, and a smile on it in the disc's own colour.
        else -> {
            drawArc(Color.White, 180f, 180f, false, at(-0.42f, -0.92f), Size(0.84f * r, 0.9f * r), style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.15f, cap = StrokeCap.Round))
            drawRoundRect(Color.White, at(-0.8f, -0.42f), Size(1.6f * r, 1.36f * r), androidx.compose.ui.geometry.CornerRadius(r * 0.24f))
            drawArc(ground, 20f, 140f, false, at(-0.4f, -0.22f), Size(0.8f * r, 0.7f * r), style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.14f, cap = StrokeCap.Round))
        }
    }
}

/** Where the four small ones sit, from the middle, on a stage 400 wide. */
private val SMALL_AT = listOf(-134f to -128f, 130f to -150f, -144f to 104f, 140f to 116f)

private enum class ScrapKind { STAR, COIN, ARROW, HEART, SHIELD, FLOWER, SPARK, LOCK, GIFT, WALLET }

/** One scattered piece: where from the middle, how big, what, what colour, how tilted, how fast it drifts. */
private class Scrap(val x: Float, val y: Float, val size: Float, val kind: ScrapKind, val colour: Color, val turn: Float = 0f, val pace: Float = 1f)


private val BITS = listOf(
    Scrap(-176f, -200f, 56f, ScrapKind.HEART, ROSE, -12f, 0.8f),
    Scrap(-52f, -198f, 30f, ScrapKind.STAR, SUN, 0f, 1.3f),
    Scrap(28f, -186f, 60f, ScrapKind.SHIELD, D.Blue, 8f, 0.7f),
    Scrap(178f, -214f, 48f, ScrapKind.COIN, SUN, -14f, 1.1f),
    Scrap(-194f, -66f, 54f, ScrapKind.ARROW, LEAF, -45f, 0.9f),
    Scrap(76f, -92f, 18f, ScrapKind.SPARK, SAND, 0f, 1.2f),
    Scrap(190f, -54f, 28f, ScrapKind.STAR, SUN, 12f, 1f),
    Scrap(-178f, 16f, 42f, ScrapKind.LOCK, SUN, -10f, 1.2f),
    Scrap(176f, 26f, 46f, ScrapKind.WALLET, SKY, 10f, 0.9f),
    Scrap(-62f, 172f, 26f, ScrapKind.STAR, SUN, -10f, 1.1f),
    Scrap(-190f, 190f, 52f, ScrapKind.FLOWER, SKY, 0f, 0.6f),
    Scrap(26f, 196f, 50f, ScrapKind.GIFT, ROSE, 8f, 0.9f),
    Scrap(100f, 176f, 18f, ScrapKind.SPARK, SAND, 0f, 1.3f),
    Scrap(200f, 192f, 52f, ScrapKind.ARROW, EMBER, 135f, 0.8f),
)

/** Draws one scattered piece, centred on [c] and [d] across. */
private fun DrawScope.bit(kind: ScrapKind, c: Offset, d: Float, colour: Color, alpha: Float) {
    val r = d / 2f
    when (kind) {
        // Four points with hollowed sides: the sparkle. STAR is the same shape, plumper.
        ScrapKind.STAR, ScrapKind.SPARK -> {
            val waist = if (kind == ScrapKind.STAR) 0.42f else 0.26f
            val p = Path()
            for (k in 0 until 4) {
                val a = Math.PI / 2 * k - Math.PI / 2
                val tip = Offset(c.x + (kotlin.math.cos(a) * r).toFloat(), c.y + (kotlin.math.sin(a) * r).toFloat())
                val b = a + Math.PI / 4
                val mid = Offset(c.x + (kotlin.math.cos(b) * r * waist).toFloat(), c.y + (kotlin.math.sin(b) * r * waist).toFloat())
                if (k == 0) p.moveTo(tip.x, tip.y) else p.lineTo(tip.x, tip.y)
                p.lineTo(mid.x, mid.y)
            }
            p.close()
            drawPath(p, colour, alpha = alpha)
            drawPath(p, colour, alpha = alpha, style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.28f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        // A coin seen face on: a darker rim, the face, a ring struck into it, and a glint.
        ScrapKind.COIN -> {
            drawCircle(Color(0xFFE9A51C), r, c, alpha = alpha)
            drawCircle(colour, r * 0.82f, c, alpha = alpha)
            drawCircle(Color(0xFFFFE08A), r * 0.5f, c, alpha = alpha, style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.14f))
            drawLine(Color.White.copy(alpha = 0.75f * alpha), Offset(c.x - r * 0.5f, c.y - r * 0.36f), Offset(c.x - r * 0.3f, c.y - r * 0.56f), strokeWidth = r * 0.14f, cap = StrokeCap.Round)
        }
        // A padlock, shut: the shackle behind, the body in front, a keyhole.
        ScrapKind.LOCK -> {
            drawArc(Color(0xFFE9A51C), 180f, 180f, false, Offset(c.x - r * 0.5f, c.y - r * 0.98f), Size(r, r * 1.1f), alpha = alpha, style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.24f, cap = StrokeCap.Round))
            drawRoundRect(colour, Offset(c.x - r * 0.82f, c.y - r * 0.3f), Size(r * 1.64f, r * 1.26f), androidx.compose.ui.geometry.CornerRadius(r * 0.3f), alpha = alpha)
            drawCircle(Color(0xFF8A5A00), r * 0.17f, Offset(c.x, c.y + r * 0.22f), alpha = alpha)
            drawLine(Color(0xFF8A5A00), Offset(c.x, c.y + r * 0.22f), Offset(c.x, c.y + r * 0.56f), strokeWidth = r * 0.14f, cap = StrokeCap.Round, alpha = alpha)
        }
        // A present: the box, its lid a little wider, a ribbon up the middle and a bow on top.
        ScrapKind.GIFT -> {
            drawRoundRect(colour, Offset(c.x - r * 0.74f, c.y - r * 0.2f), Size(r * 1.48f, r * 1.14f), androidx.compose.ui.geometry.CornerRadius(r * 0.16f), alpha = alpha)
            drawRoundRect(Color(0xFFE23A66), Offset(c.x - r * 0.88f, c.y - r * 0.5f), Size(r * 1.76f, r * 0.42f), androidx.compose.ui.geometry.CornerRadius(r * 0.14f), alpha = alpha)
            drawRect(SUN, Offset(c.x - r * 0.15f, c.y - r * 0.5f), Size(r * 0.3f, r * 1.44f), alpha = alpha)
            drawCircle(SUN, r * 0.26f, Offset(c.x - r * 0.26f, c.y - r * 0.72f), alpha = alpha)
            drawCircle(SUN, r * 0.26f, Offset(c.x + r * 0.26f, c.y - r * 0.72f), alpha = alpha)
        }
        // A wallet: the body, the flap folded over its top, and the stud that holds it shut.
        ScrapKind.WALLET -> {
            drawRoundRect(Color(0xFF4AA8F5), Offset(c.x - r * 0.9f, c.y - r * 0.66f), Size(r * 1.8f, r * 1.36f), androidx.compose.ui.geometry.CornerRadius(r * 0.28f), alpha = alpha)
            drawRoundRect(colour, Offset(c.x - r * 0.9f, c.y - r * 0.66f), Size(r * 1.8f, r * 0.7f), androidx.compose.ui.geometry.CornerRadius(r * 0.28f), alpha = alpha)
            drawRoundRect(colour, Offset(c.x + r * 0.2f, c.y - r * 0.2f), Size(r * 0.84f, r * 0.5f), androidx.compose.ui.geometry.CornerRadius(r * 0.25f), alpha = alpha)
            drawCircle(SUN, r * 0.14f, Offset(c.x + r * 0.5f, c.y + r * 0.05f), alpha = alpha)
        }
        ScrapKind.ARROW -> {
            val w = r * 0.42f
            drawLine(colour, Offset(c.x - r * 0.8f, c.y), Offset(c.x + r * 0.7f, c.y), strokeWidth = w, cap = StrokeCap.Round, alpha = alpha)
            drawLine(colour, Offset(c.x + r * 0.7f, c.y), Offset(c.x + r * 0.05f, c.y - r * 0.62f), strokeWidth = w, cap = StrokeCap.Round, alpha = alpha)
            drawLine(colour, Offset(c.x + r * 0.7f, c.y), Offset(c.x + r * 0.05f, c.y + r * 0.62f), strokeWidth = w, cap = StrokeCap.Round, alpha = alpha)
        }
        ScrapKind.HEART -> {
            val p = Path().apply {
                moveTo(c.x, c.y + r * 0.85f)
                cubicTo(c.x - r * 1.5f, c.y - r * 0.1f, c.x - r * 0.7f, c.y - r * 1.25f, c.x, c.y - r * 0.4f)
                cubicTo(c.x + r * 0.7f, c.y - r * 1.25f, c.x + r * 1.5f, c.y - r * 0.1f, c.x, c.y + r * 0.85f)
                close()
            }
            drawPath(p, colour, alpha = alpha)
            drawCircle(Color.White.copy(alpha = 0.45f * alpha), r * 0.14f, Offset(c.x - r * 0.42f, c.y - r * 0.42f))
        }
        // A shield as on a coat of arms: a peak in the middle of its top edge, square shoulders,
        // sides drawn in to a point. One half is in shade, there is a paler line set in from the
        // edge, and the tick is on top of both.
        ScrapKind.SHIELD -> {
            fun outline(k: Float) = Path().apply {
                moveTo(c.x, c.y - r * 0.98f * k)
                quadraticTo(c.x + r * 0.42f * k, c.y - r * 0.7f * k, c.x + r * 0.86f * k, c.y - r * 0.72f * k)
                cubicTo(c.x + r * 0.92f * k, c.y + r * 0.2f * k, c.x + r * 0.56f * k, c.y + r * 0.74f * k, c.x, c.y + r * k)
                cubicTo(c.x - r * 0.56f * k, c.y + r * 0.74f * k, c.x - r * 0.92f * k, c.y + r * 0.2f * k, c.x - r * 0.86f * k, c.y - r * 0.72f * k)
                quadraticTo(c.x - r * 0.42f * k, c.y - r * 0.7f * k, c.x, c.y - r * 0.98f * k)
                close()
            }
            val whole = outline(1f)
            drawPath(whole, colour, alpha = alpha)
            val shade = Path().apply {
                moveTo(c.x, c.y - r * 0.98f)
                quadraticTo(c.x + r * 0.42f, c.y - r * 0.7f, c.x + r * 0.86f, c.y - r * 0.72f)
                cubicTo(c.x + r * 0.92f, c.y + r * 0.2f, c.x + r * 0.56f, c.y + r * 0.74f, c.x, c.y + r)
                close()
            }
            drawPath(shade, Color.Black.copy(alpha = 0.16f * alpha))
            drawPath(outline(0.76f), Color.White.copy(alpha = 0.4f * alpha), style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.07f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            val tick = Path().apply {
                moveTo(c.x - r * 0.34f, c.y - r * 0.02f); lineTo(c.x - r * 0.08f, c.y + r * 0.26f); lineTo(c.x + r * 0.38f, c.y - r * 0.3f)
            }
            drawPath(tick, Color.White.copy(alpha = alpha), style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.2f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        ScrapKind.FLOWER -> {
            for (k in 0 until 5) {
                val a = 2 * Math.PI / 5 * k - Math.PI / 2
                drawCircle(colour, r * 0.46f, Offset(c.x + (kotlin.math.cos(a) * r * 0.56f).toFloat(), c.y + (kotlin.math.sin(a) * r * 0.56f).toFloat()), alpha = alpha)
            }
            drawCircle(SUN, r * 0.34f, c, alpha = alpha)
        }
    }
}

/** One thing the shop sells: its mark, its name, and the colour its mark is drawn in. */
private class Thing(val mark: Int, val word: String, val colour: Color)

private val THINGS = listOf(
    Thing(R.drawable.ic_recharge, "Recharge", D.Blue),
    Thing(R.drawable.hi_gift, "Gift cards", D.Success),
    Thing(R.drawable.hi_receipt, "Pay bills", D.Black),
    Thing(R.drawable.hi_stream, "Streaming", D.Blue),
    Thing(R.drawable.ic_meal, "Food", D.Success),
    Thing(R.drawable.ic_bag, "Shopping", D.Black),
)

private const val WHEEL_STEP_MS = 1_400L
private val WHEEL_ROW = 76.dp

/**
 * The things the shop sells, as a wheel: one in focus with its mark beside it, the ones above and
 * below it faint and out of focus, and the whole list moving down one place at a time, for ever.
 *
 * [running] stops it turning while the other picture is showing, so it always starts from rest.
 */
@Composable
private fun Wheel(running: Boolean, modifier: Modifier) {
    val turn = remember { Animatable(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            delay(WHEEL_STEP_MS - 620)
            turn.animateTo(turn.value + 1f, tween(620, easing = FastOutSlowInEasing))
        }
    }
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        val at = turn.value
        val base = kotlin.math.floor(at).toInt()
        for (n in base - 2..base + 3) {
            // How many places this word is from the middle. Words move down, so a later word
            // starts above and arrives.
            val d = at - n
            val far = kotlin.math.abs(d)
            if (far > 2.4f) continue
            val thing = THINGS[((n % THINGS.size) + THINGS.size) % THINGS.size]
            val focus = (1f - far).coerceIn(0f, 1f)
            Row(
                Modifier.padding(start = 44.dp).offset(y = WHEEL_ROW * d)
                    .graphicsLayer {
                        alpha = (0.16f + 0.84f * focus) * (1f - ((far - 1.2f) / 1.2f).coerceIn(0f, 1f))
                        val s = 0.86f + 0.14f * focus
                        scaleX = s; scaleY = s
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .blur((far * 2.2f).coerceAtMost(4f).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The mark belongs to the word in focus only; it opens out as the word arrives.
                Box(Modifier.width(54.dp * focus).graphicsLayer { alpha = focus }) {
                    if (focus > 0.02f) Icon(painterResource(thing.mark), null, Modifier.size(38.dp), tint = thing.colour)
                }
                Text(thing.word, color = D.Black, style = Type.title.copy(fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.Medium), maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * The page's one button, white because the ground it sits on is the blue a button usually is.
 * [PrimaryButton]'s height and type; its own width and a fully round end.
 */
@Composable
private fun WhiteButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        // A full pill, and about as wide as the title over it and no wider: edge to edge it was
        // the heaviest thing on the page, on a page whose pictures are meant to be.
        Modifier.width(BUTTON_WIDTH).height(60.dp).clip(CircleShape).background(D.White)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = D.BlueInk, style = Type.heading, maxLines = 1) }
}

/** A slow rise and fall, so a picture that has nothing else to do is still never quite still. */
@Composable
private fun float(period: Int, travel: Dp, delay: Int = 0): Dp {
    val t by rememberInfiniteTransition(label = "float").animateFloat(
        -1f, 1f,
        infiniteRepeatable(tween(period, delayMillis = delay, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "rise",
    )
    return travel * t
}
