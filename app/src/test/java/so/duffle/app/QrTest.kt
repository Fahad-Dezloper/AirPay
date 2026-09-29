package so.duffle.app

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.BarcodeFormat
import com.google.zxing.LuminanceSource
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receive code, round-tripped.
 *
 * A QR that renders but does not scan looks perfect in a screenshot and fails in a shop, so the
 * assertion that matters is not "a matrix came back" — it is that a reader gets the original
 * string out of it again.
 */
class QrTest {

    private val address = "b7aXLq81gY1SSdRwn1jUCyQeB282GJwrzBVCsJ3nFK2"

    /** The matrix as the decoder wants it: black is a set bit. */
    private fun read(cells: Array<BooleanArray>): String {
        val m = BitMatrix(cells.size, cells.size)
        for (y in cells.indices) for (x in cells.indices) if (cells[y][x]) m.set(x, y)
        val source: LuminanceSource = MatrixSource(cells)
        return QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(source)),
            mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)),
        ).text
    }

    /** A luminance source straight off the boolean grid, one pixel per module. */
    private class MatrixSource(val cells: Array<BooleanArray>) :
        LuminanceSource(cells.size, cells.size) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = row?.takeIf { it.size >= width } ?: ByteArray(width)
            for (x in 0 until width) out[x] = if (cells[y][x]) 0 else 255.toByte()
            return out
        }

        override fun getMatrix(): ByteArray {
            val out = ByteArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                out[y * width + x] = if (cells[y][x]) 0 else 255.toByte()
            }
            return out
        }
    }

    @Test fun `a solana pay uri survives the round trip`() {
        val cells = Qr.matrix("solana:$address")
        assertNotNull(cells)
        assertEquals("solana:$address", read(cells!!))
    }

    /** The scanner this app ships must accept what this app draws. */
    @Test fun `the app can read its own code`() {
        val text = read(Qr.matrix("solana:$address")!!)
        val scanned = SolanaPay.parse(text)
        assertTrue("parsed as $scanned", scanned !is SolanaPay.Scanned.Unknown)
    }

    /** Margin 0 is deliberate — the quiet zone is added in white by the composable. */
    @Test fun `no quiet zone is baked into the matrix`() {
        val cells = Qr.matrix("solana:$address")!!
        // A finder pattern starts at the very first module when there is no margin.
        assertTrue("top-left module should be dark", cells[0][0])
    }

    @Test fun `the matrix is square`() {
        val cells = Qr.matrix("solana:$address")!!
        assertTrue(cells.all { it.size == cells.size })
    }

    /** Encoding must never take the screen down; the caller says so in words instead. */
    @Test fun `an impossible payload returns null rather than throwing`() {
        assertNull(Qr.matrix(""))
    }
}
