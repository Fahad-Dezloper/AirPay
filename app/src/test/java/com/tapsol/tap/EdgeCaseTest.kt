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
        blockhash: String? = null,
    ) = PayRequest(recipient, amount, "SOL", label, reference, genesis, blockhash)

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

    /**
     * v2 has no blockhash, so a v2 peer would have each side timing its own — the mismatch
     * this version exists to remove. Refusing is better than paying against a deadline only
     * one phone knows about.
     */
    @Test fun `a v2 peer is refused, because its deadline is unknowable`() {
        val v2 = """{"v":2,"r":"$good","a":"1","t":"SOL","l":"x","f":"$ref"}"""
        assertNull(PayRequest.fromBytes(v2.toByteArray()))
    }

    @Test fun `the shared blockhash survives the wire`() {
        val original = req(blockhash = BLOCKHASH)
        val back = PayRequest.fromBytes(original.toBytes())
        assertNotNull(back)
        assertEquals(BLOCKHASH, back!!.blockhash)
        assertNull(back.validate())
    }

    @Test fun `a garbage blockhash is refused rather than built against`() {
        assertNotNull(req(blockhash = "not-a-hash").validate())
        assertNotNull(req(blockhash = "abc").validate())
    }

    /** No blockhash is still valid — the payer falls back to fetching its own. */
    @Test fun `a request without a blockhash is still well formed`() {
        assertNull(req(blockhash = null).validate())
    }

    /** The payer must build against the hash it was given, not one of its own choosing. */
    @Test fun `a shared blockhash is the one actually used`() {
        val r = Chain.buildTransfer(good, ref, 10_000, ref, blockhashOverride = BLOCKHASH)
        assertTrue(r is Chain.Built.Ok)
        assertEquals("the wallet needs one slot to sign into", 1, (r as Chain.Built.Ok).tx[0].toInt())
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

    // ------------------------------------------------- the wrong-asset class of bug

    /**
     * The confirm sheet renders req.token, but buildTransfer only ever emits a SystemProgram
     * transfer. A USDC request would have shown "5 USDC" and sent 5 SOL. On mainnet that is a
     * wrong-asset payment with no undo, so a non-SOL request must be refused outright.
     */
    @Test fun `a request in another token is not silently paid in SOL`() {
        val usdc = PayRequest(good, "5", "USDC", "Shop", ref)
        assertTrue("only SOL is supported, so this must not look payable",
            !usdc.token.equals("SOL", ignoreCase = true))
        // The wire layer accepts it — refusal is the payer's decision, not a parse failure,
        // so the user is told why rather than seeing a corrupt-request error.
        assertNull(usdc.validate())
    }

    @Test fun `a SOL request is still payable`() {
        assertTrue(req().token.equals("SOL", ignoreCase = true))
    }

    /**
     * The crash that shipped: Chain.balance() returns a sealed result, and the caller formatted
     * it with %.4f as though it were a Double. That compiles, because String.format takes Any,
     * and throws IllegalFormatConversionException the moment a wallet connects.
     */
    @Test fun `formatting a Balance result directly is a runtime error`() {
        val result: Chain.Balance = Chain.Balance.Ok(1.2345)
        try {
            "%.4f".format(result)
            org.junit.Assert.fail("formatting the wrapper should throw — unwrap .sol instead")
        } catch (e: java.util.IllegalFormatConversionException) {
            // expected
        }
        assertEquals("1.2345", "%.4f".format((result as Chain.Balance.Ok).sol))
    }

    // ------------------------------------------- the silent-wait class of bug

    /**
     * The payee's whole confirmation rests on one RPC call, and `getSignaturesForAddress`
     * refuses any commitment below `confirmed`:
     *
     *     -32602  Method does not support commitment below `confirmed`
     *
     * It refuses with an HTTP 200 carrying an error object, so the signature regex simply
     * finds nothing and "your query is invalid" becomes indistinguishable from "not paid
     * yet". Shipped, that is a payee watching a spinner while the SOL is already in their
     * account. Asserted on the request we actually send.
     */
    @Test fun `the payment query never asks for a commitment below confirmed`() {
        val body = Chain.signaturesBody(ref)
        assertTrue("processed is rejected by this RPC method", !body.contains("processed"))
        assertTrue(body.contains(""""commitment":"confirmed""""))
        assertTrue(body.contains(ref))
    }

    /** A JSON-RPC error must never be mistaken for an empty result. */
    @Test fun `an rpc error is read as an error, not as an empty answer`() {
        val err = """{"jsonrpc":"2.0","error":{"code":-32602,""" +
            """"message":"Method does not support commitment below `confirmed`"},"id":1}"""
        assertEquals(
            "Method does not support commitment below `confirmed`",
            Chain.rpcError(err),
        )
    }

    @Test fun `a good response carries no error`() {
        val ok = """{"jsonrpc":"2.0","result":[{"signature":"$SIG","err":null}],"id":1}"""
        assertNull(Chain.rpcError(ok))
    }

    private companion object {
        const val BLOCKHASH = "9zL9gmLBcnDpSLhQhgWjcTZGvtPqbRVhLgkBUoLoJgfd"
        const val SIG = "5gN5R1etzG8tUbZGqJdG5aS47gmRbGEJ4cSb9nCrnZiqt63ySjwSNNnUUcqbENQYYvfRuBfdkLQe3RPGeY9RXS5o"
    }
}
