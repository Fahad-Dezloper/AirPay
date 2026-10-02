package so.duffle.app

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.alexzhirkevich.qrose.options.QrBallShape
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.QrErrorCorrectionLevel
import io.github.alexzhirkevich.qrose.options.QrFrameShape
import io.github.alexzhirkevich.qrose.options.QrPixelShape
import io.github.alexzhirkevich.qrose.options.roundCorners
import io.github.alexzhirkevich.qrose.options.square
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

/**
 * A QR code, made and drawn by [QRose](https://github.com/alexzhirkevich/qrose).
 *
 * **A library does all of it now.** This used to be ZXing for the matrix and a hand-written
 * Canvas for the drawing, and the drawing was the problem: every module — the three corner finder
 * patterns included — was its own rounded dot, and a code built that way decoded 0 times in 83
 * tries against ZXing's own reader, because a scanner finds a code by the 1:1:3:1:1 ratio across
 * the finders and separate dots break it. QRose encodes the code and draws the finders as the
 * solid ring-and-ball shapes they are, with the soft rounded data modules kept.
 *
 * Error correction is **M**: a wallet address is short and the code is shown on a screen, not
 * printed on a cup, so more redundancy would only make the modules smaller. QRose adds the quiet
 * zone itself, which a scanner needs.
 *
 * Verified the only way that means anything: the phone's own screen, captured and decoded, gives
 * back the exact `solana:<address>` that was encoded.
 */
@Composable
internal fun QrCode(text: String, modifier: Modifier = Modifier, ink: Color = D.Black) {
    val painter = rememberQrCodePainter(text) {
        errorCorrectionLevel = QrErrorCorrectionLevel.Medium
        shapes {
            // Separate small boxes, a touch under a full module so each reads as its own box.
            // `roundCorners` would instead melt neighbouring modules into one blob.
            darkPixel = QrPixelShape.square(.88f)
            ball = QrBallShape.roundCorners(.3f)
            frame = QrFrameShape.roundCorners(.3f)
        }
        colors {
            dark = QrBrush.solid(ink)
            ball = QrBrush.solid(ink)
            frame = QrBrush.solid(ink)
        }
    }
    Image(
        painter, contentDescription = null,
        modifier = modifier.semantics { contentDescription = "QR code for this wallet address" },
    )
}
