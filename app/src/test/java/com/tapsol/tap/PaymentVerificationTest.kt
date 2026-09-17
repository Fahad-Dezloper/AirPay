package com.tapsol.tap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The only path to "✓ Paid", tested against a REAL devnet transaction: the 0.01 SOL tap
 * payment from Phase 2, saved verbatim from getTransaction.
 *
 * It matters that the fixture is real. Seeker Wallet rewrote that transaction before signing
 * — it added two ComputeBudget instructions — so a check written against the transaction we
 * built, rather than what landed, would pass on a hand-made fixture and fail in the field.
 */
class PaymentVerificationTest {

    private val tx = fixture("devnet_tap_payment.json")
    private val reference = "GwQqYKapoH4mS1M4G8NFvLBWcWNQTpoJBGBDyKi92KrU"
    private val recipient = "2qxPntVgWyxU2jQz2MLcUuCp71D9D1ZSTcbFvCcXWrUc"
    private val payer = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"

    @Test fun `the real payment verifies as paid in full`() {
        assertEquals(Chain.Verdict.Paid, Chain.verifyTransfer(tx, reference, recipient, 10_000_000))
    }

    @Test fun `asking for more than arrived is not paid`() {
        val v = Chain.verifyTransfer(tx, reference, recipient, 10_000_001)
        assertTrue(v is Chain.Verdict.Rejected)
        assertEquals("paid 10000000 of 10000001 lamports", (v as Chain.Verdict.Rejected).why)
    }

    /**
     * The attack this whole check exists for: someone attaches your reference to a transfer
     * that pays somebody else. Trusting the reference alone would have shown ✓ Paid.
     */
    @Test fun `a transaction carrying the reference but paying someone else is not paid`() {
        assertTrue(Chain.verifyTransfer(tx, reference, payer, 10_000_000) is Chain.Verdict.Rejected)
        assertTrue(Chain.verifyTransfer(tx, reference, "11111111111111111111111111111112", 1) is Chain.Verdict.Rejected)
    }

    @Test fun `a transaction for a different request is not paid`() {
        val other = "8eYAaFEyuwxKwpa9qSbCTWj8VStg5MeAE9GPMyyMTfSn"
        assertTrue(Chain.verifyTransfer(tx, other, recipient, 10_000_000) is Chain.Verdict.Rejected)
    }

    @Test fun `a failed transaction is never paid, whatever its balances say`() {
        val failed = tx.replace("\"err\":null", "\"err\":{\"InstructionError\":[2,\"Custom\"]}")
        assertTrue("fixture must actually contain the field", failed != tx)
        assertTrue(Chain.verifyTransfer(failed, reference, recipient, 10_000_000) is Chain.Verdict.Rejected)
    }

    /** getTransaction can return null for a signature that is listed but not yet served. */
    @Test fun `a transaction not yet available is pending, not rejected`() {
        assertEquals(Chain.Verdict.Pending, Chain.verifyTransfer("""{"jsonrpc":"2.0","result":null,"id":1}""", reference, recipient, 1))
    }

    @Test fun `garbage is rejected rather than thrown`() {
        assertTrue(Chain.verifyTransfer("not json", reference, recipient, 1) is Chain.Verdict.Rejected)
    }

    @Test fun `signature listing keeps only successful transactions`() {
        val sigs = Chain.successfulSignatures(fixture("devnet_signatures.json"))
        assertEquals(listOf("5gN5R1etzG8tUbZGqJdG5aS47gmRbGEJ4cSb9nCrnZiqt63ySjwSNNnUUcqbENQYYvfRuBfdkLQe3RPGeY9RXS5o"), sigs)
        val mixed = """{"result":[{"signature":"A","err":{"x":1}},{"signature":"B","err":null}]}"""
        assertEquals(listOf("B"), Chain.successfulSignatures(mixed))
    }

    @Test fun `server events are parsed strictly`() {
        val sig = "5gN5R1etzG8tUbZGqJdG5aS47gmRbGEJ4cSb9nCrnZiqt63ySjwSNNnUUcqbENQYYvfRuBfdkLQe3RPGeY9RXS5o"
        assertEquals(ServerClient.Paid(reference, sig),
            ServerClient.parsePaid("""{"reference":"$reference","signature":"$sig"}"""))
        assertNull(ServerClient.parsePaid("""{"reference":"nope","signature":"$sig"}"""))
        assertNull(ServerClient.parsePaid("""{"reference":"$reference"}"""))
        assertNull(ServerClient.parsePaid("garbage"))
    }

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource(name)!!.readText()
}
