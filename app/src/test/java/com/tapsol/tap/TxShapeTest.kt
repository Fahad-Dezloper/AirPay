package com.tapsol.tap

import org.junit.Test
import org.sol4k.AccountMeta
import org.sol4k.PublicKey
import org.sol4k.Transaction
import org.sol4k.instruction.BaseInstruction
import org.sol4k.instruction.TransferInstruction

/**
 * What does an UNSIGNED sol4k transaction actually look like on the wire?
 *
 * Seeker Wallet refused ours as "not properly formed and can't be signed", and the wire format
 * is the only place that can be true. Solana's layout is:
 *     compact-u16 signature count | 64 bytes per signature | message
 * A wallet asked to sign needs a slot reserved for its signature. Zero slots means there is
 * nowhere to put one.
 */
class TxShapeTest {

    private val payer = PublicKey("2qxPntVgWyxU2jQz2MLcUuCp71D9D1ZSTcbFvCcXWrUc")
    private val payee = PublicKey("7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU")
    private val reference = PublicKey("F5DwhUpkd4TQigGKZyJRwEfjAMUAvww6CieDbqMcJ3tn")
    private val blockhash = "9zL9gmLBcnDpSLhQhgWjcTZGvtPqbRVhLgkBUoLoJgfd"

    /** The regression: a transaction handed to a wallet must reserve a slot to sign into. */
    @Test fun `unsigned transfer reserves one signature slot`() {
        val tx = Chain.buildTransfer(
            payer = payer.toBase58(),
            recipient = payee.toBase58(),
            lamports = 10_000_000L,
            reference = reference.toBase58(),
            blockhashOverride = blockhash,
        )
        org.junit.Assert.assertEquals(
            "signature count must be 1, or the wallet has nowhere to sign", 1, tx[0].toInt())
        org.junit.Assert.assertTrue(
            "the reserved slot must be zero-filled", (1..64).all { tx[it].toInt() == 0 })
        // 1 count + 64 slot + the original 183-byte message
        org.junit.Assert.assertEquals(248, tx.size)
    }

    @Test fun `show the bytes`() {
        val transfer = TransferInstruction(payer, payee, 10_000_000L)
        val withRef = BaseInstruction(
            transfer.data,
            transfer.keys + AccountMeta(reference, signer = false, writable = false),
            transfer.programId,
        )
        val tx = Transaction(blockhash, withRef, feePayer = payer)

        val bytes = try {
            tx.serialize()
        } catch (e: Throwable) {
            println("!! serialize() THREW: ${e.javaClass.name}: ${e.message}")
            return
        }

        println("total length : ${bytes.size}")
        println("first byte   : ${bytes[0].toInt()}   <-- compact-u16 signature COUNT")
        val sigCount = bytes[0].toInt()
        println("expected     : 1 (the fee payer must have a slot)")
        if (sigCount > 0) {
            val allZero = (1..64).all { bytes[it].toInt() == 0 }
            println("sig slot is zero-filled placeholder: $allZero")
        }
        println("first 24 hex : ${bytes.take(24).joinToString(""){ "%02x".format(it) }}")
    }
}
