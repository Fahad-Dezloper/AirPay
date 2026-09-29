package so.duffle.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Drawing a QR code, which is the one thing ML Kit cannot do.
 *
 * The scanner reads codes; nothing in the app could write one, so the receive sheet needed an
 * encoder. ZXing's core is pure Java — no Android dependency, no view, no bitmap — so it hands
 * back a matrix of booleans and the drawing stays ours.
 */
object Qr {

    /**
     * The square matrix for [text], or null if it cannot be encoded.
     *
     * **Margin zero on purpose.** ZXing's default quiet zone is four modules drawn *inside* the
     * requested size, which shrinks the code and then sits it on whatever the caller's
     * background happens to be. The quiet zone is real and a scanner needs it — so it is added
     * as padding by the composable, in white, where it is guaranteed to be white.
     *
     * Error correction is **M**, not H. A wallet address is short, the code is displayed on a
     * screen rather than printed on a cup, and H would spend a third of the capacity on
     * redundancy nobody here needs — making the modules smaller and the scan harder.
     */
    fun matrix(text: String): Array<BooleanArray>? = try {
        val bits = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 0,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
        Array(bits.height) { y -> BooleanArray(bits.width) { x -> bits.get(x, y) } }
    } catch (e: Exception) {
        // A code that cannot be drawn must not take the screen down with it. The caller says
        // so in words instead.
        null
    }
}

/**
 * A QR code, drawn as rounded dots rather than hard squares.
 *
 * Every module is drawn at its exact grid position and size computed from the module count, so
 * nothing accumulates rounding error across the matrix — the classic cause of a code whose
 * right-hand column is a pixel thin and which then will not scan.
 */
@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier, ink: Color = D.Black) {
    val cells = remember(text) { Qr.matrix(text) }
    Box(modifier) {
        if (cells == null) return@Box
        Canvas(Modifier.fillMaxSize()) {
            val n = cells.size
            val step = size.minDimension / n
            // Slightly over one module, so neighbouring dots meet instead of leaving a hairline
            // gap that a camera reads as a break in a finder pattern.
            val dot = step * 1.02f
            val r = CornerRadius(step * .28f)
            for (y in 0 until n) for (x in 0 until n) {
                if (!cells[y][x]) continue
                drawRoundRect(ink, Offset(x * step, y * step), Size(dot, dot), r)
            }
        }
    }
}
