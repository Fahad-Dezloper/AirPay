package so.duffle.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A payment signed with no connection and handed back over NFC.
 *
 * Two things have to be exact and neither needs a phone: the bytes survive being cut into APDUs
 * and put back together, and the asking phone refuses anything that is not a signed payment for
 * its own request before it sends it anywhere.
 */
class OfflineTxTest {

    private val payer = "2qxPntVgWyxU2jQz2MLcUuCp71D9D1ZSTcbFvCcXWrUc"
    private val payee = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
    private val stranger = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
    private val reference = "F5DwhUpkd4TQigGKZyJRwEfjAMUAvww6CieDbqMcJ3tn"
    private val otherReference = "GwQqYKapoH4mS1M4G8NFvLBWcWNQTpoJBGBDyKi92KrU"
    private val blockhash = "9zL9gmLBcnDpSLhQhgWjcTZGvtPqbRVhLgkBUoLoJgfd"

    private val solRequest = PayRequest(payee, "0.01", "SOL", "Shop", reference)
    private val usdcRequest = PayRequest(payee, "0.31", Shop.USDC_MAINNET, "Shop", reference, decimals = 6, symbol = "USDC")

    private fun sol(to: String = payee, ref: String = reference): ByteArray =
        (Chain.buildTransfer(payer, to, 10_000_000L, ref, blockhashOverride = blockhash) as Chain.Built.Ok).tx

    private fun usdc(to: String = payee): ByteArray = (Chain.buildTokenTransfer(
        payer, to, Shop.USDC_MAINNET, 310_000, 6, reference, "USDC",
        blockhashOverride = blockhash, programOverride = Chain.TOKEN_PROGRAM, recipientAccountExists = true,
    ) as Chain.Built.Ok).tx

    /** What a wallet does to the empty slot: fills it. The check does not verify the signature. */
    private fun signed(tx: ByteArray): ByteArray = tx.copyOf().also { for (i in 1..64) it[i] = (i * 3 + 1).toByte() }

    private fun ok(tx: ByteArray, request: PayRequest) = OfflineTx.check(tx, request) == OfflineTx.Verdict.Ok

    // ---------------------------------------------------------------------- the check

    @Test fun `a signed transfer for this request passes`() {
        assertTrue(OfflineTx.check(signed(sol()), solRequest).toString(), ok(signed(sol()), solRequest))
    }

    @Test fun `a signed usdc payment to the payee's token account passes`() {
        assertTrue(OfflineTx.check(signed(usdc()), usdcRequest).toString(), ok(signed(usdc()), usdcRequest))
    }

    @Test fun `an unsigned one is refused`() {
        assertTrue(!ok(sol(), solRequest))
        assertNull(OfflineTx.accountKeys(sol()))
    }

    @Test fun `one for a different recipient is refused`() {
        assertTrue(!ok(signed(sol(to = stranger)), solRequest))
        assertTrue(!ok(signed(usdc(to = stranger)), usdcRequest))
    }

    @Test fun `one missing the reference is refused`() {
        assertTrue(!ok(signed(sol(ref = otherReference)), solRequest))
    }

    @Test fun `sol does not settle a usdc request, nor usdc a sol one`() {
        assertTrue(!ok(signed(sol()), usdcRequest))
        assertTrue(!ok(signed(usdc()), solRequest))
    }

    @Test fun `a token nobody can pay offline is refused`() {
        val other = PayRequest(payee, "1", "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263", "Shop", reference, decimals = 5, symbol = "BONK")
        assertTrue(!ok(signed(usdc()), other))
    }

    @Test fun `a versioned message and a second signer are refused`() {
        val versioned = signed(sol()).also { it[65] = 0x80.toByte() }
        assertTrue(!ok(versioned, solRequest))
        val twoSigners = signed(sol()).also { it[65] = 2 }
        assertTrue(!ok(twoSigners, solRequest))
        val twoSignatures = signed(sol()).also { it[0] = 2 }
        assertTrue(!ok(twoSignatures, solRequest))
    }

    @Test fun `garbage, truncated and oversize bytes are refused without throwing`() {
        val good = signed(sol())
        assertTrue(!ok(ByteArray(0), solRequest))
        assertTrue(!ok(byteArrayOf(1), solRequest))
        assertTrue(!ok(ByteArray(300) { 0x7F }, solRequest))
        assertTrue(!ok(ByteArray(300) { 0xFF.toByte() }, solRequest))
        assertTrue(!ok(good + ByteArray(OfflineTx.MAX_SIZE), solRequest))
        // Cut anywhere, it is either refused or still whole enough to name its accounts.
        for (n in 0 until good.size) OfflineTx.check(good.copyOf(n), solRequest)
        assertTrue(!ok(good.copyOf(120), solRequest))
        // A key count that promises more keys than there are bytes.
        assertTrue(!ok(good.copyOf().also { it[68] = 0x7F }, solRequest))
        assertTrue(!ok(good.copyOf().also { it[68] = 0xFF.toByte(); it[69] = 0xFF.toByte() }, solRequest))
        // And a request that is itself rubbish does not throw either.
        assertTrue(!ok(signed(usdc()), usdcRequest.copy(recipient = "not-an-address")))
    }

    // -------------------------------------------------------------------- the pieces

    private fun bytes(n: Int) = ByteArray(n) { (it * 7 + 3).toByte() }

    private fun assemble(apdus: List<ByteArray>, into: OfflineTx.Assembler = OfflineTx.Assembler()): OfflineTx.Step? {
        var last: OfflineTx.Step? = null
        for (a in apdus) {
            last = into.accept(a)
            if (last is OfflineTx.Step.Bad) return last
        }
        return last
    }

    @Test fun `any size goes across in pieces and comes back the same`() {
        for (n in listOf(1, 255, 256, 600, OfflineTx.MAX_SIZE)) {
            val tx = bytes(n)
            val apdus = OfflineTx.apdus(tx)
            assertEquals("pieces for $n", (n + 254) / 255, apdus.size)
            assertTrue(apdus.all { it.size <= 5 + 255 && Apdu.isHand(it) })
            apdus.forEachIndexed { i, a ->
                assertEquals(i, a[2].toInt() and 0xFF)
                assertEquals(if (i == apdus.size - 1) 1 else 0, a[3].toInt())
            }
            val done = assemble(apdus)
            assertTrue("$n bytes", done is OfflineTx.Step.Done)
            assertArrayEquals(tx, (done as OfflineTx.Step.Done).tx)
        }
    }

    @Test fun `a smaller radio gets smaller pieces`() {
        val tx = bytes(600)
        val apdus = OfflineTx.apdus(tx, chunk = 248)
        assertEquals(3, apdus.size)
        assertTrue(apdus.all { it.size <= 253 })
        assertArrayEquals(tx, (assemble(apdus) as OfflineTx.Step.Done).tx)
    }

    @Test fun `a real signed transfer survives the trip and still passes the check`() {
        val tx = signed(usdc())
        val done = assemble(OfflineTx.apdus(tx)) as OfflineTx.Step.Done
        assertArrayEquals(tx, done.tx)
        assertTrue(ok(done.tx, usdcRequest))
    }

    @Test fun `a missing piece is refused`() {
        val apdus = OfflineTx.apdus(bytes(600))
        val step = assemble(listOf(apdus[0], apdus[2]))
        assertArrayEquals(Apdu.SW_WRONG_DATA, (step as OfflineTx.Step.Bad).sw)
    }

    @Test fun `a repeated piece is refused`() {
        val apdus = OfflineTx.apdus(bytes(600))
        val step = assemble(listOf(apdus[0], apdus[0]))
        assertArrayEquals(Apdu.SW_WRONG_DATA, (step as OfflineTx.Step.Bad).sw)
    }

    @Test fun `pieces out of order are refused`() {
        val apdus = OfflineTx.apdus(bytes(600))
        assertTrue(assemble(listOf(apdus[1], apdus[0], apdus[2])) is OfflineTx.Step.Bad)
        assertTrue(assemble(listOf(apdus[0], apdus[2], apdus[1])) is OfflineTx.Step.Bad)
    }

    @Test fun `a refusal starts over, so the next whole try works`() {
        val tx = bytes(600)
        val apdus = OfflineTx.apdus(tx)
        val a = OfflineTx.Assembler()
        assertTrue(assemble(listOf(apdus[0], apdus[2]), a) is OfflineTx.Step.Bad)
        // Carrying on from where it broke is refused too: nothing of the old try is kept.
        assertTrue(a.accept(apdus[1]) is OfflineTx.Step.Bad)
        assertArrayEquals(tx, (assemble(apdus, a) as OfflineTx.Step.Done).tx)
    }

    @Test fun `reset drops a half-sent payment`() {
        val tx = bytes(600)
        val apdus = OfflineTx.apdus(tx)
        val a = OfflineTx.Assembler()
        assertEquals(OfflineTx.Step.More, a.accept(apdus[0]))
        a.reset()
        assertArrayEquals(tx, (assemble(apdus, a) as OfflineTx.Step.Done).tx)
    }

    @Test fun `more than a transaction can hold is refused`() {
        assertTrue(OfflineTx.apdus(bytes(OfflineTx.MAX_SIZE + 1)).isEmpty())
        assertTrue(OfflineTx.apdus(ByteArray(0)).isEmpty())
        // A reader that ignores the cap and never says "last".
        val a = OfflineTx.Assembler()
        var step: OfflineTx.Step = OfflineTx.Step.More
        var i = 0
        while (step == OfflineTx.Step.More && i < 10) {
            step = a.accept(byteArrayOf(0x80.toByte(), Apdu.INS_HAND, i.toByte(), 0, 0xFF.toByte()) + ByteArray(255))
            i++
        }
        assertArrayEquals(Apdu.SW_WRONG_LENGTH, (step as OfflineTx.Step.Bad).sw)
        assertEquals("refused on the piece that crosses 1232 bytes", 5, i)
    }

    @Test fun `malformed pieces are refused`() {
        val a = OfflineTx.Assembler()
        val good = OfflineTx.apdus(bytes(10))[0]
        assertTrue(a.accept(good.copyOf(good.size - 1)) is OfflineTx.Step.Bad)   // shorter than it says
        assertTrue(a.accept(good + byteArrayOf(0)) is OfflineTx.Step.Bad)         // longer than it says
        assertTrue(a.accept(byteArrayOf(0x80.toByte(), Apdu.INS_HAND, 0, 1, 0)) is OfflineTx.Step.Bad) // empty
        assertTrue(a.accept(good.copyOf().also { it[3] = 7 }) is OfflineTx.Step.Bad) // P2 neither 0 nor 1
        assertTrue(a.accept(Apdu.select()) is OfflineTx.Step.Bad)
        assertTrue(a.accept(good) is OfflineTx.Step.Done)
    }

    // ------------------------------------------------------------------- the request

    @Test fun `the blockhash rides on the request and an old phone's request still reads`() {
        val with = PayRequest.fromBytes(solRequest.copy(blockhash = blockhash).toBytes())!!
        assertEquals(blockhash, with.blockhash)
        assertNull(with.validate())
        assertNull(PayRequest.fromBytes(solRequest.toBytes())!!.blockhash)
    }

    @Test fun `an unreadable blockhash is dropped, not a reason to refuse the request`() {
        val json = String(solRequest.toBytes()).replace("{", """{"h":"not a hash",""").toByteArray()
        val back = PayRequest.fromBytes(json)!!
        assertNull(back.blockhash)
        assertEquals(reference, back.reference)
    }
}
