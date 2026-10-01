package so.duffle.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Every candidate vibration, by name, to be felt and picked.
 *
 * Feel cannot be chosen from a description, and it cannot be chosen from a list played once in
 * a row either — by the fourth nobody remembers the first. So each one is a button with a name
 * that can be said back ("Paid 3"), pressed as many times as it takes.
 *
 * Debug source set only. This phone's motor has no strength control and no primitives, so the
 * whole vocabulary is the built-in effects plus on/off timing; everything here is one of those.
 */
class HapticLabActivity : ComponentActivity() {

    private sealed interface Beat {
        data class Built(val id: Int) : Beat
        data class Pulse(val ms: Long) : Beat
    }

    /** [at] is when the beat STARTS, counted from the start of the pattern. */
    private data class Step(val at: Long, val beat: Beat)
    private data class Option(val name: String, val what: String, val steps: List<Step>)

    private val vibrator by lazy { getSystemService(VibratorManager::class.java).defaultVibrator }
    private val main = Handler(Looper.getMainLooper())
    private var last by mutableStateOf("nothing yet")

    private val click = Beat.Built(VibrationEffect.EFFECT_CLICK)
    private val heavy = Beat.Built(VibrationEffect.EFFECT_HEAVY_CLICK)
    private val double = Beat.Built(VibrationEffect.EFFECT_DOUBLE_CLICK)

    private fun one(b: Beat) = listOf(Step(0, b))
    private fun every(gap: Long, vararg b: Beat) = b.mapIndexed { i, beat -> Step(i * gap, beat) }

    private data class Section(val title: String, val whenItPlays: String, val options: List<Option>)

    private val sections = listOf(
        Section(
            "1 · PHONES TOUCH",
            "Plays the instant the two phones tap together. It means: got it, you can pull the phones apart. One heavy, longer buzz.",
            listOf(
                Option("Touch 1", "one heavy click — shortest of these", one(heavy)),
                Option("Touch 2", "one solid buzz, a bit longer", one(Beat.Pulse(120))),
                Option("Touch 3", "one solid buzz, longer", one(Beat.Pulse(180))),
                Option("Touch 4", "one solid buzz, longest", one(Beat.Pulse(260))),
                Option("Touch 5", "a quick knock, then a solid buzz", listOf(Step(0, click), Step(70, Beat.Pulse(140)))),
                Option("Touch 6", "two heavy clicks run together into one long thump", every(110, heavy, heavy)),
            ),
        ),
        Section(
            "2 · MONEY SENT",
            "Plays when the payment has really gone through. Two beats, like a happy double tap.",
            listOf(
                Option("Paid 1", "two clicks, close together", every(110, click, click)),
                Option("Paid 2", "two clicks, further apart", every(160, click, click)),
                Option("Paid 3", "light tap, then a firmer tap", every(110, Beat.Pulse(10), Beat.Pulse(20))),
                Option("Paid 4", "light tap, then a heavy click", every(120, Beat.Pulse(10), heavy)),
                Option("Paid 5", "the phone's own double click", one(double)),
            ),
        ),
        Section(
            "3 · PAYMENT FAILED",
            "Plays when the payment is declined, cancelled or runs out of time. It should feel clearly different from the happy one.",
            listOf(
                Option("Failed 1", "three short taps, quick", every(100, Beat.Pulse(20), Beat.Pulse(20), Beat.Pulse(20))),
                Option("Failed 2", "three clicks, slower", every(130, click, click, click)),
                Option("Failed 3", "two heavy clicks, slow", every(220, heavy, heavy)),
                Option("Failed 4", "three longer taps, buzzy", every(90, Beat.Pulse(35), Beat.Pulse(35), Beat.Pulse(35))),
            ),
        ),
    )

    private fun play(o: Option) {
        last = o.name
        main.removeCallbacksAndMessages(null)
        vibrator.cancel()
        // All pulses: one waveform, so the motor driver keeps the rhythm and not this thread.
        if (o.steps.all { it.beat is Beat.Pulse }) {
            val timings = ArrayList<Long>()
            var cursor = 0L
            for (s in o.steps) {
                val ms = (s.beat as Beat.Pulse).ms
                timings += (s.at - cursor).coerceAtLeast(0)
                timings += ms
                cursor = s.at + ms
            }
            vibrator.vibrate(VibrationEffect.createWaveform(timings.toLongArray(), -1))
            return
        }
        // A built-in effect cannot go inside a waveform, and this motor has no composition
        // support, so mixed patterns are started beat by beat.
        for (s in o.steps) main.postDelayed({
            vibrator.vibrate(
                when (val b = s.beat) {
                    is Beat.Built -> VibrationEffect.createPredefined(b.id)
                    is Beat.Pulse -> VibrationEffect.createOneShot(b.ms, VibrationEffect.DEFAULT_AMPLITUDE)
                },
            )
        }, s.at)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column(
                Modifier.fillMaxSize().background(Color(0xFF101214)).systemBarsPadding()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text("Feel test", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Press a button to feel it. Nothing is saved here. Pick ONE from each of the 3 groups and tell Claude the names.",
                    color = Color(0xCCFFFFFF), fontSize = 15.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text("Last played: $last", color = Color(0xFF7FE0A7), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                for (section in sections) {
                    Spacer(Modifier.height(28.dp))
                    Text(section.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(section.whenItPlays, color = Color(0xB3FFFFFF), fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    for (o in section.options) {
                        Spacer(Modifier.height(8.dp))
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                                .background(if (last == o.name) Color(0xFF1F4D36) else Color(0xFF1C2024))
                                .clickable { play(o) }
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                        ) {
                            Text(o.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(o.what, color = Color(0xB3FFFFFF), fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        vibrator.cancel()
    }
}
