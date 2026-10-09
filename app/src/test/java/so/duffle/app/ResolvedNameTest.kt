package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A dotted name on a payment: refused from anywhere else, allowed when this phone proved it. */
class ResolvedNameTest {
    private val to = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D"
    private fun req(label: String) = PayRequest(to, "1", "SOL", label, PayRequest.newReference())

    @Test fun `a dotted name that arrived from elsewhere is still refused`() {
        assertEquals("The request has a name shaped like a domain.", req("dezloper.skr").validate())
    }

    @Test fun `a dotted name this phone resolved is allowed`() {
        assertNull(req("dezloper.skr").validate(nameResolved = true))
    }

    @Test fun `being resolved excuses the name and nothing else`() {
        val bad = PayRequest("not an address", "1", "SOL", "dezloper.skr", PayRequest.newReference())
        assertEquals("The request has an invalid destination address.", bad.validate(nameResolved = true))
    }

    @Test fun `an ordinary name was never in question`() {
        assertNull(req("Aashu").validate())
    }
}
