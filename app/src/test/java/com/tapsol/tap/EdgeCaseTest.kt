package com.tapsol.tap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hostile-input suite.
 *
 * Everything arriving over NFC comes from a phone we do not control. `PublicKey(...)` throws on
 * malformed base58, so an unvalidated payload would crash the payer mid-tap — in front of
 * someone, during the one moment the product has to look trustworthy.
 *
 * Each case here is a specific way the app could have died.
 */
class EdgeCaseTest {

    private val good = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
    private val ref = "GwQqYKapoH4mS1M4G8NFvLBWcWNQTpoJBGBDyKi92KrU"

    private fun req(
        recipient: String = good,
        amount: String = "0.01",
        reference: String = ref,
        label: String = "Seeker",
        genesis: String? = null,
    ) = PayRequest(recipient, amount, "SOL", label, reference, genesis)

    // ---------------------------------------------------------------- must reject

    @Test fun `garbage destination is refused, not crashed on`() {
        assertNotNull(req(recipient = "not-an-address").validate())
        assertNotNull(req(recipient = "").validate())
        // Right alphabet, wrong length — the shape that slips past a naive check.
        assertNotNull(req(recipient = "abc").validate())
        // 0, O, I and l are excluded from base58 precisely to avoid confusion.
        assertNotNull(req(recipient = "0OIl000000000000000000000000000000").validate())
    }

    @Test fun `bad reference is refused`() {
        assertNotNull(req(reference = "nope").validate())
    }

    @Test fun `malformed genesis does not earn a badge`() {
        assertNotNull(req(genesis = "fake").validate())
    }

    @Test fun `unreadable or absurd amounts are refused`() {
        assertNotNull(req(amount = "abc").validate())
        assertNotNull(req(amount = "-5").validate())
        assertNotNull(req(amount = "999999999").validate())
    }

    @Test fun `oversized label is refused`() {
        assertNotNull(req(label = "x".repeat(500)).validate())
    }

    @Test fun `junk bytes over the radio parse to null rather than throwing`() {
        assertNull(PayRequest.fromBytes(ByteArray(0)))
        assertNull(PayRequest.fromBytes("not json".toByteArray()))
        assertNull(PayRequest.fromBytes(byteArrayOf(0, 1, 2, 3, -1, -2)))
        // Valid JSON, wrong shape.
        assertNull(PayRequest.fromBytes("""{"v":2}""".toByteArray()))
    }

    @Test fun `a newer wire version is refused rather than guessed at`() {
        val future = """{"v":99,"r":"$good","a":"1","t":"SOL","l":"x","f":"$ref"}"""
        assertNull(PayRequest.fromBytes(future.toByteArray()))
    }

    // ---------------------------------------------------------------- must accept

    @Test fun `a well-formed request passes`() {
        assertNull(req().validate())
    }

    @Test fun `an empty amount is allowed — the payer decides`() {
        assertNull(req(amount = "").validate())
    }

    @Test fun `round trip survives the wire`() {
        val original = req(genesis = "8eYAaFEyuwxKwpa9qSbCTWj8VStg5MeAE9GPMyyMTfSn")
        val back = PayRequest.fromBytes(original.toBytes())
        assertNotNull(back)
        assertEquals(original.recipient, back!!.recipient)
        assertEquals(original.reference, back.reference)
        assertEquals(original.genesis, back.genesis)
        assertNull(back.validate())
    }

    // ---------------------------------------------------------- build never throws

    @Test fun `building against bad input returns a reason, never an exception`() {
        val cases = listOf(
            Chain.buildTransfer("garbage", good, 1000, ref, blockhashOverride = BLOCKHASH),
            Chain.buildTransfer(good, "garbage", 1000, ref, blockhashOverride = BLOCKHASH),
            Chain.buildTransfer(good, good, 1000, "garbage", blockhashOverride = BLOCKHASH),
            Chain.buildTransfer(good, good, 0, ref, blockhashOverride = BLOCKHASH),
            Chain.buildTransfer(good, good, -100, ref, blockhashOverride = BLOCKHASH),
        )
        cases.forEach { assertTrue("expected a Bad result, got $it", it is Chain.Built.Bad) }
    }

    @Test fun `a valid build still produces a signable transaction`() {
        val r = Chain.buildTransfer(good, ref, 10_000_000, ref, blockhashOverride = BLOCKHASH)
        assertTrue(r is Chain.Built.Ok)
        val tx = (r as Chain.Built.Ok).tx
        assertEquals("the wallet needs one slot to sign into", 1, tx[0].toInt())
    }

    @Test fun `a solana pay url is emitted for wallets that are not this app`() {
        val url = req().toSolanaPayUrl()
        assertTrue(url.startsWith("solana:$good"))
        assertTrue(url.contains("reference=$ref"))
        assertTrue(url.contains("amount=0.01"))
    }

    private companion object {
        const val BLOCKHASH = "9zL9gmLBcnDpSLhQhgWjcTZGvtPqbRVhLgkBUoLoJgfd"
    }
}
