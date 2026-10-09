package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request screen holds one request out as a QR code and over NFC. The code has to be a
 * Solana Pay URL this app's own scanner reads back to the same payment, reference included.
 */
class RequestScreenTest {
    private val me = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
    private val usdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    private fun request(token: String, decimals: Int, symbol: String) = PayRequest(
        recipient = me, amount = "2.5", token = token, label = "Fahad Khan",
        reference = PayRequest.newReference(), decimals = decimals, symbol = symbol,
    )

    @Test fun aSolRequestScansBackToTheSamePayment() {
        val r = request("SOL", 9, "SOL")
        val got = SolanaPay.parse(RequestActivity.solanaPayUrl(r)) as SolanaPay.Scanned.Request
        assertEquals(me, got.recipient)
        assertEquals("2.5", got.amount)
        assertEquals("SOL", got.token)
        assertEquals(r.reference, got.reference)
        assertEquals("Fahad Khan", got.label)
    }

    @Test fun aTokenRequestNamesItsMint() {
        val r = request(usdc, 6, "USDC")
        val url = RequestActivity.solanaPayUrl(r)
        assertTrue(url.contains("spl-token=$usdc"))
        val got = SolanaPay.parse(url) as SolanaPay.Scanned.Request
        assertEquals(usdc, got.token)
        assertEquals(r.reference, got.reference)
    }

    @Test fun theAmountKeysRespectTheTokensDecimals() {
        var a = ""
        "2.5".forEach { a = RequestActivity.typedAmount(a, it, 6) }
        assertEquals("2.5", a)
        assertEquals("0.", RequestActivity.typedAmount("", '.', 6))
        assertEquals("1.5", RequestActivity.typedAmount("1.5", '.', 6))            // one point only
        assertEquals("1.123456", RequestActivity.typedAmount("1.123456", '7', 6))  // no seventh place
        assertEquals("7", RequestActivity.typedAmount("0", '7', 6))                // no leading zero
        assertEquals("1", RequestActivity.typedAmount("12", '<', 6))
    }
}
