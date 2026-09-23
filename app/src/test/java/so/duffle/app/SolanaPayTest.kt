package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Known answers for the QR parser.
 *
 * Every case here is something a camera can be pointed at, and the two that matter most are
 * the refusals: a code that asks for an amount nobody can read, and a transaction request,
 * which is a stranger's server building something for someone to sign.
 *
 * The parser deliberately touches no Android class, so all of it runs here.
 */
class SolanaPayTest {

    private val addr = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
    private val mint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"

    @Test
    fun `a bare address is someone to pay, not an amount`() {
        val got = SolanaPay.parse(addr)
        assertTrue("$got", got is SolanaPay.Scanned.Address)
        assertEquals(addr, (got as SolanaPay.Scanned.Address).recipient)
    }

    @Test
    fun `surrounding whitespace does not make a code unreadable`() {
        assertTrue(SolanaPay.parse("  $addr\n") is SolanaPay.Scanned.Address)
    }

    @Test
    fun `a transaction request is refused by name`() {
        val got = SolanaPay.parse("solana:https://shop.example/checkout/9")
        assertTrue("$got", got is SolanaPay.Scanned.Refused)
        assertTrue((got as SolanaPay.Scanned.Refused).why.contains("transaction request"))
    }

    @Test
    fun `things that are not solana codes are not guessed at`() {
        assertEquals(SolanaPay.Scanned.Unknown, SolanaPay.parse("https://duffle.so"))
        assertEquals(SolanaPay.Scanned.Unknown, SolanaPay.parse("WIFI:S=cafe;T=WPA;P=hunter2;;"))
        assertEquals(SolanaPay.Scanned.Unknown, SolanaPay.parse(""))
        // Right shape, wrong alphabet: base58 has no 0, O, I or l.
        assertEquals(SolanaPay.Scanned.Unknown, SolanaPay.parse("solana:0OIl000000000000000000000000000000000000000"))
    }

    @Test
    fun `an amount makes it a request, with its token and label`() {
        val got = SolanaPay.parse("solana:$addr?amount=12.5&spl-token=$mint&label=Blue%20Bottle")
        assertTrue("$got", got is SolanaPay.Scanned.Request)
        got as SolanaPay.Scanned.Request
        assertEquals("12.5", got.amount)
        assertEquals(mint, got.token)
        assertEquals("Blue Bottle", got.label)
        assertEquals(addr, got.recipient)
    }

    @Test
    fun `no amount means an address, even with everything else present`() {
        val got = SolanaPay.parse("solana:$addr?label=Shop&reference=$mint")
        assertTrue("$got", got is SolanaPay.Scanned.Address)
        assertEquals("Shop", (got as SolanaPay.Scanned.Address).label)
    }

    @Test
    fun `sol is the token when no mint is named`() {
        val got = SolanaPay.parse("solana:$addr?amount=0.5")
        assertEquals("SOL", (got as SolanaPay.Scanned.Request).token)
        assertEquals(null, got.reference)
    }

    @Test
    fun `an amount that cannot be paid is refused, never quietly dropped`() {
        // Dropping it would turn "pay 12.5" into "pay how much?" — an invitation to type a
        // different number than the code asked for.
        for (bad in listOf("abc", "-1", "0", "1e9", "999999999999")) {
            val got = SolanaPay.parse("solana:$addr?amount=$bad")
            assertTrue("$bad gave $got", got is SolanaPay.Scanned.Refused)
        }
    }

    @Test
    fun `the reference is kept when the code carries one`() {
        val got = SolanaPay.parse("solana:$addr?amount=1&reference=$mint")
        assertEquals(mint, (got as SolanaPay.Scanned.Request).reference)
    }

    @Test
    fun `an spl mint is a valid address, so it parses as one`() {
        assertTrue(SolanaPay.parse(mint) is SolanaPay.Scanned.Address)
    }
}
