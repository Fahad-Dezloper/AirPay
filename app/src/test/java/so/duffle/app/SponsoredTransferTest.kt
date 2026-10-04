package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sol4k.PublicKey
import java.io.File

/**
 * A payment whose network fee is paid by AirPay's fee wallet.
 *
 * The fee wallet signs only a transaction of one exact shape (payer/skrpayer/sponsor.py), so the
 * shape built here is the contract. The bytes are also written out, and the payer's own checker
 * is run over them: the check is run by hand from the repo root after this test (it needs the payer's Python).
 */
class SponsoredTransferTest {
    private val user = "9C6hybhQ6Aycep9jaUnP6uL9ZYvDjUp1aSkFWPUFJtpj"
    private val feeWallet = "5ZgHPb447UcXavVTorgYBr8egi3sw7dXKpX4Frec9rYS"
    private val feeAccount = "AMm8mJhjG1Q6JkbVx4LpFKjFUqg5KGqU96ZdEzWyT4c5"
    private val shop = "b7aXLq81gY1SSdRwn1jUCyQeB282GJwrzBVCsJ3nFK2"
    private val hash = "9zL9gmLBcnDpSLhQhgWjcTZGvtPqbRVhLgkBUoLoJgfd"

    private fun build(sponsor: Chain.Sponsor?) = Chain.buildTokenTransfer(
        payer = user, recipient = shop, mint = Shop.USDC_MAINNET, units = 310_000, decimals = 6,
        reference = "GwQqYKapoH4mS1M4G8NFvLBWcWNQTpoJBGBDyKi92KrU", symbol = "USDC",
        blockhashOverride = hash, programOverride = Chain.TOKEN_PROGRAM, recipientAccountExists = true,
        sponsor = sponsor,
    )

    @Test fun `the fee wallet is the fee payer and the buyer the second signer`() {
        val built = build(Chain.Sponsor(feeWallet, feeAccount, 10_000))
        assertTrue(built.toString(), built is Chain.Built.Ok)
        val tx = (built as Chain.Built.Ok).tx
        assertEquals("two signature slots", 2, tx[0].toInt())
        assertTrue("both slots are empty", (1..128).all { tx[it].toInt() == 0 })
        val msg = tx.copyOfRange(129, tx.size)
        assertEquals("two required signers", 2, msg[0].toInt())
        assertEquals(feeWallet, PublicKey(msg.copyOfRange(4, 36)).toBase58())
        assertEquals(user, PublicKey(msg.copyOfRange(36, 68)).toBase58())
        File("build/sponsored-tx.hex").apply { parentFile?.mkdirs() }.writeText(tx.joinToString("") { "%02x".format(it) })
    }

    @Test fun `without a sponsor it is the ordinary one-signer transfer`() {
        val tx = (build(null) as Chain.Built.Ok).tx
        assertEquals(1, tx[0].toInt())
        assertEquals(user, PublicKey(tx.copyOfRange(65 + 4, 65 + 36)).toBase58())
    }

    @Test fun `a payment that must create an account is not sponsored`() {
        val built = Chain.buildTokenTransfer(
            payer = user, recipient = shop, mint = Shop.USDC_MAINNET, units = 1, decimals = 6,
            reference = "GwQqYKapoH4mS1M4G8NFvLBWcWNQTpoJBGBDyKi92KrU", symbol = "USDC",
            blockhashOverride = hash, programOverride = Chain.TOKEN_PROGRAM, recipientAccountExists = false,
            sponsor = Chain.Sponsor(feeWallet, feeAccount, 10_000),
        )
        assertTrue(built is Chain.Built.Bad)
    }
}

/** Sending all the SOL in a wallet: the fee comes out of the amount, it is not a refusal. */
class SendableTest {
    @Test fun `asking for everything sends everything less the fee`() {
        assertEquals(16_895_000L, Chain.sendable(16_900_000, 16_900_000))
    }

    @Test fun `asking for nearly everything leaves the fee and sends the rest`() {
        assertEquals(16_895_000L, Chain.sendable(16_898_000, 16_900_000))
    }

    @Test fun `the balance as shown is all of it, though the wallet holds a few lamports more`() {
        // Shown as 0.0169; really 0.016912345. Typing 0.0169 means everything.
        assertEquals(16_907_345L, Chain.sendable(16_900_000, 16_912_345))
    }

    @Test fun `an amount that leaves a balance the chain allows is sent as asked`() {
        assertEquals(1_000_000L, Chain.sendable(1_000_000, 16_900_000))
    }

    @Test fun `a remainder too small to keep and too big to be dust is refused`() {
        assertEquals(null, Chain.sendable(16_400_000, 16_900_000))
    }

    @Test fun `more than is held is still refused`() {
        assertEquals(null, Chain.sendable(16_900_001, 16_900_000))
        assertEquals(null, Chain.sendable(5_000, 5_000))
        assertEquals(null, Chain.sendable(0, 16_900_000))
    }
}
