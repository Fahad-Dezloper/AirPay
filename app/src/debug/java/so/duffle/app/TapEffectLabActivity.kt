package so.duffle.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The phones-touch effect, played without two phones.
 *
 * A stand-in screen with the real glow over it, and a button for each candidate so one can be
 * named back ("Split 2"). Pressing a button is the tap: it fires the same buzz the real tap
 * does, so the picture and the buzz are judged together.
 *
 * Debug source set only.
 */
class TapEffectLabActivity : ComponentActivity() {

    /**
     * The light parts at the top, runs down the sides and settles along the bottom edge only.
     * [rest] is how bright it stays once it is there, 0–1.
     */
    private data class Split(val ms: Int, val rest: Float)
    private data class Touch(val name: String, val what: String, val spec: RippleSpec? = null, val split: Split? = null)

    private val touches = listOf(
        Touch("Split 1", "quick, rests light on the bottom edge", split = Split(600, rest = 0.30f)),
        Touch("Split 2", "same, slower so you can follow it", split = Split(1000, rest = 0.30f)),
        Touch("Split 3", "quick, rests even fainter", split = Split(600, rest = 0.16f)),
        Touch("Split 4", "quick, rests a little stronger", split = Split(600, rest = 0.45f)),
        Touch("Glow only", "edges flash, nothing bends (Google style)"),
        Touch("Ripple 1", "Apple's own numbers", RippleSpec(amplitude = 12f, frequency = 15f, decay = 8f, speed = 1200f)),
        Touch("Ripple 2", "soft and slow", RippleSpec(amplitude = 8f, frequency = 10f, decay = 5f, speed = 800f)),
        Touch("Ripple 3", "big and fast", RippleSpec(amplitude = 20f, frequency = 15f, decay = 8f, speed = 1800f)),
        Touch("Ripple 4", "one deep swell, no wobble", RippleSpec(amplitude = 18f, frequency = 6f, decay = 9f, speed = 1200f)),
        Touch("Ripple 5", "long wobble, like water", RippleSpec(amplitude = 10f, frequency = 18f, decay = 3.5f, speed = 1000f)),
    )

    private val waits = listOf("Glow A" to "all four edges", "Glow B" to "top edge only", "Glow off" to "no waiting glow")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { Lab() }
    }

    @Composable
    private fun Lab() {
        val context = LocalContext.current
        val density = LocalDensity.current.density
        val scope = rememberCoroutineScope()
        val clock = remember { Animatable(0f) }
        val flash = remember { Animatable(0f) }
        val sheet = remember { Animatable(0f) }
        var playing by remember { mutableStateOf<RippleSpec?>(null) }
        var split by remember { mutableStateOf<Split?>(null) }
        var got by remember { mutableStateOf(false) }
        var last by remember { mutableStateOf("nothing yet") }
        var wait by remember { mutableIntStateOf(1) }
        var screen by remember { mutableStateOf(IntSize.Zero) }
        var job by remember { mutableStateOf<Job?>(null) }

        fun play(t: Touch) {
            last = t.name
            job?.cancel()
            job = scope.launch {
                playing = null
                split = null
                got = false
                sheet.snapTo(0f)
                Haptics.contact(context)

                if (t.split != null) {
                    split = t.split
                    // The sheet starts up while the light is still on its way down, so the two
                    // arrive at the bottom together.
                    launch {
                        delay(t.split.ms / 4L)
                        sheet.animateTo(1f, spring(dampingRatio = 1f, stiffness = 300f))
                    }
                    delay(t.split.ms + 5000L)
                    sheet.animateTo(0f, tween(220))
                    split = null
                    return@launch
                }

                got = true
                launch {
                    flash.snapTo(1f)
                    flash.animateTo(0f, tween(900, easing = LinearOutSlowInEasing))
                }
                if (t.spec != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val seconds = t.spec.seconds(screen.height.toFloat(), density)
                    clock.snapTo(0f)
                    playing = t.spec
                    clock.animateTo(seconds, tween((seconds * 1000).toInt(), easing = LinearEasing))
                    playing = null
                }
                delay(2200)
                got = false
            }
        }

        val s = split

        Box(Modifier.fillMaxSize().background(D.White).onSizeChanged { screen = it }) {
            Box(
                Modifier.fillMaxSize().tapRipple(
                    seconds = { if (playing != null) clock.value else null },
                    spec = { playing },
                    tint = D.Blue,
                ),
            ) { MockRequest(got) }

            if (s != null) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f * sheet.value))
                        .clickable { job?.cancel(); scope.launch { sheet.snapTo(0f); split = null } },
                )
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(SHEET)
                        .graphicsLayer { translationY = (1f - sheet.value) * size.height },
                ) { MockSheet() }
            }

            // The real thing, exactly as the pay sheet draws it; only the speed and the resting
            // brightness are swapped per button.
            if (wait == 1 || s != null) {
                TapGlow(
                    waiting = s == null && wait == 1 && !got,
                    touched = s != null,
                    modifier = Modifier.fillMaxSize(),
                    runMs = s?.ms ?: TapGlowSpec.RUN_MS,
                    rest = s?.rest ?: TapGlowSpec.REST,
                )
            }
            if (s == null && (wait == 0 || flash.value > 0f)) {
                EdgeGlow(
                    tint = D.Blue,
                    level = if (wait != 0 || got) 0f else 0.45f,
                    flash = flash.value * 0.6f,
                    topOnly = wait == 1,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (s == null) Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color(0xF2101214)).navigationBarsPadding().padding(vertical = 12.dp),
            ) {
                Text(
                    "Last played: $last", color = Color(0xFF7FE0A7), fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp),
                )
                Label("WHILE WAITING FOR A TAP")
                Chips {
                    waits.forEachIndexed { i, (name, what) -> Chip(name, what, wait == i) { wait = i } }
                }
                Label("WHEN THE PHONES TOUCH — press to play, swipe for more")
                Chips {
                    touches.forEach { t -> Chip(t.name, t.what, last == t.name) { play(t) } }
                }
            }
        }
    }

    @Composable
    private fun Label(text: String) = Text(
        text, color = Color(0x99FFFFFF), fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp),
    )

    @Composable
    private fun Chips(content: @Composable () -> Unit) = Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }

    @Composable
    private fun Chip(name: String, what: String, on: Boolean, press: () -> Unit) = Column(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (on) Color(0xFF1F4D36) else Color(0xFF262B31))
            .clickable(onClick = press).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(what, color = Color(0xB3FFFFFF), fontSize = 12.sp)
    }

    /** A stand-in for the pay sheet that opens on the payer's phone at contact. */
    @Composable
    private fun MockSheet() = Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(D.White)
            .navigationBarsPadding().padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.width(44.dp).height(5.dp).clip(CircleShape).background(D.Line))
        Spacer(Modifier.height(26.dp))
        Text("Fahad is asking for", color = D.Quiet, fontSize = 16.sp)
        Text("0.50 SOL", color = D.Black, fontSize = 48.sp, fontWeight = FontWeight.Bold)
        Text("≈ $72.40", color = D.Quiet, fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        Text("✓ Verified Seeker", color = D.BlueInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(D.Blue).padding(vertical = 17.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Pay", color = D.White, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(10.dp))
        Text("Tap outside to play another", color = D.Quiet, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
    }

    /** Something with enough edges and text in it that a bend is easy to see. */
    @Composable
    private fun MockRequest(got: Boolean) = Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Text("Requesting", color = D.Quiet, fontSize = 16.sp)
        Text("0.50 SOL", color = D.Black, fontSize = 52.sp, fontWeight = FontWeight.Bold)
        Text("≈ $72.40 · to Fahad", color = D.Quiet, fontSize = 15.sp)
        Spacer(Modifier.height(26.dp))
        Crossfade(got, label = "mockState") { done ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (done) {
                    Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(132.dp).clip(CircleShape).background(D.Blue), contentAlignment = Alignment.Center) {
                            Text("✓", color = D.White, fontSize = 72.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("Got it", color = D.Black, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "You can pull the phones apart.", color = D.Quiet, fontSize = 16.sp, textAlign = TextAlign.Center,
                    )
                } else {
                    Canvas(Modifier.size(232.dp)) {
                        val cells = 25
                        val cell = size.width / cells
                        val random = Random(7)
                        for (y in 0 until cells) for (x in 0 until cells) {
                            if (random.nextBoolean()) {
                                drawRect(D.Black, Offset(x * cell, y * cell), Size(cell, cell))
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("Waiting for a tap", color = D.Black, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Hold the top of the other phone against this one.", color = D.Quiet, fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    private companion object {
        /** How much of the screen the stand-in sheet covers. */
        const val SHEET = 0.46f
    }
}
