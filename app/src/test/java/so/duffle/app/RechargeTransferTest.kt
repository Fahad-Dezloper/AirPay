package so.duffle.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sol4k.PublicKey

/** The USDC transfer AirPay builds for a recharge: its accounts, its instruction, its slots. */
class RechargeTransferTest {

    private val payer = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
    private val recipient = "2qxPntVgWyxU2jQz2MLcUuCp71D9D1ZSTcbFvCcXWrUc"
    private val usdcDevnet = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
    private val ref = "GwQqYKapoH4mS1M4G8NFvLBWcWNQTpoJBGBDyKi92KrU"
    private val blockhash = "EkSnNWid2cvwEVnVx9aBqawnmiCNiDgp3gUdkDPTKN1N"


    /** The expected value came from `spl-token address`, not from this code. */
    @Test fun `token accounts are derived like the SPL tooling derives them`() {
        assertEquals("7dQhNaFiV579YSWeCczUgqQFzRB6YgUn8coqan8f4n7L",
            Chain.tokenAccount(payer, usdcDevnet, Chain.TOKEN_PROGRAM))
    }

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
}
