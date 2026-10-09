package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading Jupiter's answers, and the figure typed on the Buy SKR sheet. */
class SwapTest {

    private val order = """{"inAmount":"5000000","outAmount":"273500000","otherAmountThreshold":"272100000",
        "slippageBps":50,"priceImpact":0.12,"router":"metis","gasless":false,
        "transaction":"AQID","requestId":"req-1"}"""

    @Test fun `an order with a transaction can be signed`() {
        val o = (Swap.parseOrder(order, 1_000) as Swap.Asked.Ok).order
        assertEquals(5_000_000L, o.inUnits)
        assertEquals(273_500_000L, o.outUnits)
        assertEquals(272_100_000L, o.minOut)
        assertEquals(0.12, o.impact!!, 1e-9)
        assertTrue(o.signable)
        assertNull(o.problem)
    }

    @Test fun `a priced order with no transaction says why and cannot be signed`() {
        val body = order.replace("\"AQID\"", "\"\"").replace("\"requestId\"", "\"errorCode\":2,\"requestId\"")
        val o = (Swap.parseOrder(body, 1_000) as Swap.Asked.Ok).order
        assertFalse(o.signable)
        assertEquals("You need a little SOL for the network fee.", o.problem)
        // The rate is still shown: it was priced, only not built.
        assertEquals(273_500_000L, o.outUnits)
    }

    @Test fun `a null transaction is not a transaction`() {
        val o = (Swap.parseOrder(order.replace("\"AQID\"", "null"), 1_000) as Swap.Asked.Ok).order
        assertFalse(o.signable)
    }

    @Test fun `no figures means no swap, not a swap of nothing`() {
        assertTrue(Swap.parseOrder("""{"error":"No route found"}""", 0) is Swap.Asked.None)
        assertTrue(Swap.parseOrder("""{"inAmount":"5","outAmount":"0"}""", 0) is Swap.Asked.None)
        assertTrue(Swap.parseOrder("not json", 0) is Swap.Asked.Offline)
    }

    @Test fun `an order goes stale`() {
        val o = (Swap.parseOrder(order, 1_000) as Swap.Asked.Ok).order
        assertTrue(o.fresh(1_000 + Swap.FRESH_MS - 1))
        assertFalse(o.fresh(1_000 + Swap.FRESH_MS))
    }

    @Test fun `only a success with a signature is a success`() {
        val sig = "5".repeat(88)
        val ok = Swap.parseExecute("""{"status":"Success","signature":"$sig","totalOutputAmount":"273400000","code":0}""")
        assertEquals(Swap.Done.Ok(sig, 273_400_000L), ok)
        assertTrue(Swap.parseExecute("""{"status":"Failed","signature":"$sig","code":-1000}""") is Swap.Done.Failed)
        assertTrue(Swap.parseExecute("""{"status":"Success","signature":""}""") is Swap.Done.Failed)
        assertTrue(Swap.parseExecute("oops") is Swap.Done.Offline)
    }

    @Test fun `typing a figure`() {
        fun type(keys: String, decimals: Int = 6) = keys.fold("") { t, k -> press(t, k, decimals) }
        assertEquals("12.5", type("12.5"))
        assertEquals("0.5", type(".5"))
        assertEquals("1.5", type("1..5"))
        assertEquals("7", type("07"))
        assertEquals("1.123456", type("1.1234567"))
        assertEquals("1.12", type("1.123<"))
        assertEquals("12", type("12.", decimals = 0))
        assertEquals("", type("<"))
    }

    @Test fun `a typed figure in base units`() {
        assertEquals(12_500_000L, toUnits("12.5", 6))
        assertEquals(1_000_000_000L, toUnits("1.", 9))
        assertEquals(0L, toUnits("", 6))
        assertEquals(0L, toUnits("0.", 6))
        assertEquals(0L, toUnits(".", 6))
    }

    @Test fun `max is a figure that can be typed back`() {
        assertEquals("12.5", plain(12_500_000L, 6))
        // Nine places are cut to six, down, so Max never asks for more than is held.
        assertEquals("0.123456", plain(123_456_789L, 9))
        assertEquals(123_456_000L, toUnits(plain(123_456_789L, 9), 9))
        assertEquals("", plain(0L, 6))
    }
}
