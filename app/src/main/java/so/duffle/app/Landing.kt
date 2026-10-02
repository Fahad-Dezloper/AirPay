package so.duffle.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The first screen of a fresh install: a name, one line, and one button.
 *
 * Shown whenever no wallet is linked, so a cleared app reads as a new one. Nothing here touches
 * the network or any stored state — it is a door, and Connect is the only thing behind it.
 */
@Composable
internal fun Landing(busy: Boolean, onConnect: () -> Unit) {
    val drift = rememberInfiniteTransition(label = "drift")
    val phase by drift.animateFloat(
        0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )

    Box(Modifier.fillMaxSize().background(D.White)) {
        Canvas(Modifier.fillMaxSize().statusBarsPadding()) {
            for ((i, b) in BLOBS.withIndex()) {
                val t = 2 * PI * (phase + i * 0.17)
                val c = Offset(
                    size.width * b.x + (sin(t) * 5.dp.toPx()).toFloat(),
                    size.height * b.y + (cos(t) * 7.dp.toPx()).toFloat(),
                )
                blob(b, c, b.r.dp.toPx())
            }
        }

        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("AirPay", color = D.Black, fontSize = 34.sp, fontWeight = FontWeight.Medium)
            Text(
                "Tap phones to pay.\nNo address to type.",
                color = D.Quiet, style = Type.body, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .fillMaxWidth().padding(horizontal = Space.gutter, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton(
                if (busy) "Opening your wallet…" else "Connect wallet",
                onClick = onConnect, loading = busy,
            )
        }
    }
}

private enum class Shape { CIRCLE, EGG, SQUARE, HEX, CLOUD }

/** Centre as a fraction of the canvas, radius in dp. Some sit half off the edge on purpose. */
private class Blob(val shape: Shape, val x: Float, val y: Float, val r: Float, val color: Color, val tilt: Float)

private val BLOBS = listOf(
    Blob(Shape.CLOUD, 0.26f, 0.22f, 34f, Color(0xFF00B85C), 0f),
    Blob(Shape.EGG, 0.62f, 0.215f, 30f, Color(0xFFFF2D8A), 20f),
    Blob(Shape.CIRCLE, 0.95f, 0.33f, 31f, Color(0xFF7A4B2B), -10f),
    Blob(Shape.EGG, 0.02f, 0.40f, 28f, Color(0xFF7A3CFF), -15f),
    Blob(Shape.EGG, 1.0f, 0.54f, 32f, Color(0xFFFF2236), 10f),
    Blob(Shape.CIRCLE, 0.0f, 0.60f, 46f, Color(0xFFFF8A00), 0f),
    Blob(Shape.CIRCLE, 0.92f, 0.70f, 30f, Color(0xFF00A88E), 0f),
    Blob(Shape.SQUARE, 0.20f, 0.78f, 30f, Color(0xFF1665FF), -6f),
    Blob(Shape.HEX, 0.55f, 0.80f, 36f, Color(0xFFFF5A00), 0f),
)

private fun DrawScope.blob(b: Blob, c: Offset, r: Float) {
    rotate(b.tilt, c) {
        when (b.shape) {
            Shape.CIRCLE -> drawCircle(b.color, r, c)
            Shape.EGG -> drawOval(b.color, Offset(c.x - r * 0.85f, c.y - r), Size(r * 1.7f, r * 2f))
            Shape.SQUARE -> drawRoundRect(
                b.color, Offset(c.x - r, c.y - r), Size(r * 2, r * 2),
                androidx.compose.ui.geometry.CornerRadius(r * 0.35f),
            )
            Shape.HEX -> {
                val p = Path()
                for (k in 0 until 6) {
                    val a = PI / 3 * k - PI / 2
                    val pt = Offset(c.x + (cos(a) * r).toFloat(), c.y + (sin(a) * r).toFloat())
                    if (k == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
                }
                p.close()
                drawPath(p, b.color)
            }
            Shape.CLOUD -> {
                drawCircle(b.color, r * 0.55f, Offset(c.x - r * 0.5f, c.y + r * 0.1f))
                drawCircle(b.color, r * 0.55f, Offset(c.x + r * 0.5f, c.y + r * 0.1f))
                drawCircle(b.color, r * 0.62f, Offset(c.x, c.y - r * 0.2f))
                drawCircle(b.color, r * 0.5f, Offset(c.x, c.y + r * 0.3f))
            }
        }
        // The two small slanted marks every one of them carries — the only "face" there is.
        val w = r * 0.09f
        for (dx in listOf(-0.26f, 0.2f)) {
            drawLine(
                Color.White,
                Offset(c.x + r * dx - r * 0.06f, c.y - r * 0.2f),
                Offset(c.x + r * dx + r * 0.06f, c.y + r * 0.2f),
                strokeWidth = w * 2f, cap = StrokeCap.Round,
            )
        }
    }
}
