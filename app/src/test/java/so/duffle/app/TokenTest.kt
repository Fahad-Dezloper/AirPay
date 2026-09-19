package so.duffle.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sol4k.PublicKey

/**
 * Paying in tokens other than SOL: amounts, addresses, the transfer we build, and the check that
 * decides a token request is paid.
 */
class TokenTest {

    private val payer = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
    private val recipient = "2qxPntVgWyxU2jQz2MLcUuCp71D9D1ZSTcbFvCcXWrUc"
    private val usdcDevnet = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    private val ref = "GwQqYKapoH4mS1M4G8NFvLBWcWNQTpoJBGBDyKi92KrU"
    private val blockhash = "EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N"

    // ------------------------------------------------------------------------- amounts

    @Test fun `amounts convert in the token's own decimals`() {
        assertEquals(500_000L, PayRequest.baseUnits("0.5", 6))
        assertEquals(500_000_000L, PayRequest.baseUnits("0.5", 9))
        assertEquals(7L, PayRequest.baseUnits("7", 0))
    }

    @Test fun `finer than the token can express is refused, not rounded`() {
        assertNull(PayRequest.baseUnits("0.0000001", 6))
        assertNull(PayRequest.baseUnits("1.5", 0))
    }

    @Test fun `units read back as a trimmed amount`() {
        assertEquals("1.5", Chain.fmtUnits(1_500_000, 6))
        assertEquals("0.000001", Chain.fmtUnits(1, 6))
        assertEquals("12", Chain.fmtUnits(12, 0))
    }

    // ------------------------------------------------------------------------- the wire

    @Test fun `a token request survives the radio with its decimals and name`() {
        val req = PayRequest(recipient, "2.5", usdcDevnet, "Shop", ref, decimals = 6, symbol = "USDC")
        val back = PayRequest.fromBytes(req.toBytes())!!
        assertEquals(req, back)
        assertEquals(2_500_000L, back.units())
        assertEquals(usdcDevnet, back.mint)
        assertNull(back.validate())
    }

    @Test fun `a SOL request still reads as SOL`() {
        val back = PayRequest.fromBytes(PayRequest(recipient, "0.5", "SOL", "Shop", ref).toBytes())!!
        assertTrue(back.isSol)
        assertNull(back.mint)
        assertEquals(9, back.decimals)
        assertEquals("SOL", back.symbol)
    }

    @Test fun `impossible decimals are refused`() {
        assertTrue(PayRequest(recipient, "1", usdcDevnet, "", ref, decimals = 40, symbol = "X").validate() != null)
    }

    // ---------------------------------------------------------------------- addresses

    /** The expected value came from `spl-token address`, not from this code. */
    @Test fun `token accounts are derived like the SPL tooling derives them`() {
        assertEquals("7dQhNaFiV579YSWeCczUgqQFzRB6YgUn8coqan8f4n7L",
            Chain.tokenAccount(payer, usdcDevnet, Chain.TOKEN_PROGRAM))
    }

    /** Computed independently (a Python PDA search), because Metaplex seeds aren't 32 bytes. */
    @Test fun `the Metaplex metadata address is derived correctly from an 8-byte seed`() {
        val meta = "metaqbxxUerdq28cj1RbAWkYQm3ybzjb6a8bt518x1s"
        val pda = PublicKey.findProgramAddress(
            listOf(PublicKey("metadata".toByteArray()), PublicKey(meta), PublicKey("EPjFWdd5AufqSSqeM2qJxdxLTSnu4ZsdH6DsjCfCnDF6")),
            PublicKey(meta),
        ).publicKey.toBase58()
        assertEquals("862oUxXRoXxNoigJuFqAkJLBttfapMukpPXt3zgM2Duq", pda)
    }

    // ----------------------------------------------------------------- the transaction

    @Test fun `transferChecked is instruction 12, amount little-endian, then decimals`() {
        assertArrayEquals(
            byteArrayOf(12, 0x20, 0xA1.toByte(), 0x07, 0, 0, 0, 0, 0, 6),
            Chain.transferCheckedData(500_000, 6),
        )
    }

    private fun build(exists: Boolean, program: String = Chain.TOKEN_PROGRAM) =
        Chain.buildTokenTransfer(payer, recipient, usdcDevnet, 500_000, 6, ref, "USDC",
            blockhashOverride = blockhash, programOverride = program, recipientAccountExists = exists)

    private fun ByteArray.has(needle: ByteArray): Boolean =
        (0..size - needle.size).any { i -> needle.indices.all { this[i + it] == needle[it] } }

    @Test fun `a first payment creates the recipient's token account`() {
        val tx = (build(exists = false) as Chain.Built.Ok).tx
        assertEquals("one signature slot, reserved for the wallet", 1, tx[0].toInt())
        assertTrue(tx.has(PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL").bytes()))
        assertTrue(tx.has(PublicKey(ref).bytes()))
        assertTrue(tx.has(Chain.transferCheckedData(500_000, 6)))
    }

    @Test fun `a repeat payment skips creating the account`() {
        val tx = (build(exists = true) as Chain.Built.Ok).tx
        assertFalse(tx.has(PublicKey("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL").bytes()))
        assertTrue(tx.has(PublicKey(ref).bytes()))
    }

    @Test fun `Token-2022 mints are paid through Token-2022`() {
        val tx = (build(exists = true, program = Chain.TOKEN_2022_PROGRAM) as Chain.Built.Ok).tx
        assertTrue(tx.has(PublicKey(Chain.TOKEN_2022_PROGRAM).bytes()))
        assertFalse(tx.has(PublicKey(Chain.TOKEN_PROGRAM).bytes()))
    }

    @Test fun `garbage never reaches the wallet`() {
        assertTrue(Chain.buildTokenTransfer(payer, recipient, "garbage", 1, 6, ref, "X",
            blockhashOverride = blockhash, programOverride = Chain.TOKEN_PROGRAM, recipientAccountExists = true) is Chain.Built.Bad)
        assertTrue(Chain.buildTokenTransfer(payer, recipient, usdcDevnet, 0, 6, ref, "X",
            blockhashOverride = blockhash, programOverride = Chain.TOKEN_PROGRAM, recipientAccountExists = true) is Chain.Built.Bad)
    }

    // ------------------------------------------------------------------ verification

    /**
     * A token payment as getTransaction reports it. The recipient's wallet key is deliberately
     * absent: tokens land in their token account, and the check must not require the wallet.
     */
    private fun tokenTx(pre: Long?, post: Long, owner: String = recipient, mint: String = usdcDevnet, err: String = "null") = """
        {"jsonrpc":"2.0","id":1,"result":{
          "meta":{"err":$err,"preBalances":[1,2,3],"postBalances":[1,2,3],
            "preTokenBalances":[${if (pre == null) "" else """{"accountIndex":1,"mint":"$mint","owner":"$owner","uiTokenAmount":{"amount":"$pre","decimals":6}}"""}],
            "postTokenBalances":[{"accountIndex":1,"mint":"$mint","owner":"$owner","uiTokenAmount":{"amount":"$post","decimals":6}}]},
          "transaction":{"message":{"accountKeys":["$payer","7dQhNaFiV579YSWeCczUgqQFzRB6YgUn8coqan8f4n7L","$ref"]}}}}
    """.trimIndent()

    @Test fun `a token payment in full is paid`() {
        assertEquals(Chain.Verdict.Paid, Chain.verifyTransfer(tokenTx(1_000_000, 1_500_000), ref, recipient, 500_000, usdcDevnet))
    }

    @Test fun `a first-ever token payment, with no balance before, is paid`() {
        assertEquals(Chain.Verdict.Paid, Chain.verifyTransfer(tokenTx(null, 500_000), ref, recipient, 500_000, usdcDevnet))
    }

    @Test fun `a short token payment is not paid`() {
        val v = Chain.verifyTransfer(tokenTx(0, 499_999), ref, recipient, 500_000, usdcDevnet)
        assertEquals("paid 499999 of 500000 token units", (v as Chain.Verdict.Rejected).why)
    }

    /** The wrong-asset attack: pay a worthless token with the right reference and amount. */
    @Test fun `the right amount of a different token is not paid`() {
        val other = "So11111111111111111111111111111111111111112"
        assertTrue(Chain.verifyTransfer(tokenTx(0, 500_000, mint = other), ref, recipient, 500_000, usdcDevnet) is Chain.Verdict.Rejected)
    }

    @Test fun `tokens that went to someone else are not paid`() {
        assertTrue(Chain.verifyTransfer(tokenTx(0, 500_000, owner = payer), ref, recipient, 500_000, usdcDevnet) is Chain.Verdict.Rejected)
    }

    @Test fun `a failed token transaction is not paid`() {
        assertTrue(Chain.verifyTransfer(tokenTx(0, 500_000, err = """{"InstructionError":[0,"Custom"]}"""), ref, recipient, 500_000, usdcDevnet) is Chain.Verdict.Rejected)
    }

    @Test fun `a token payment without this reference is not this request`() {
        val otherRef = "11111111111111111111111111111112"
        assertTrue(Chain.verifyTransfer(tokenTx(0, 500_000), otherRef, recipient, 500_000, usdcDevnet) is Chain.Verdict.Rejected)
    }

    /** And a SOL request can't be satisfied by tokens: SOL checks lamports, which didn't move. */
    @Test fun `tokens don't satisfy a SOL request`() {
        assertTrue(Chain.verifyTransfer(tokenTx(0, 500_000_000), ref, recipient, 500_000) is Chain.Verdict.Rejected)
    }

    // ------------------------------------------------------ the real devnet USDC payment

    /**
     * The first token payment Duffle made: 1 USDC, Seeker to OnePlus, 2026-09-19, saved verbatim
     * from getTransaction. It created the OnePlus's USDC account, and Seeker Wallet added two
     * ComputeBudget instructions before signing — the fixture is what actually landed.
     */
    private val realTx = javaClass.classLoader!!.getResource("devnet_usdc_payment.json")!!.readText()
    private val realRef = "8sRMPp6pyRP3Lq6nxmQ719CMAZXud68x1W3QmTecme38"

    @Test fun `the real USDC payment is paid in full`() {
        assertEquals(Chain.Verdict.Paid, Chain.verifyTransfer(realTx, realRef, recipient, 1_000_000, usdcDevnet))
    }

    @Test fun `the real USDC payment does not cover a larger request`() {
        assertTrue(Chain.verifyTransfer(realTx, realRef, recipient, 1_000_001, usdcDevnet) is Chain.Verdict.Rejected)
    }

    @Test fun `the real USDC payment does not pay a SOL request of the same size`() {
        assertTrue(Chain.verifyTransfer(realTx, realRef, recipient, 1_000_000) is Chain.Verdict.Rejected)
    }

    @Test fun `the real USDC payment does not pay EURC`() {
        val eurc = "HzwqbKZw8HxMN6bF2yFZNrht3c2iXXzpKcFu7uBEDKtr"
        assertTrue(Chain.verifyTransfer(realTx, realRef, recipient, 1_000_000, eurc) is Chain.Verdict.Rejected)
    }
}
