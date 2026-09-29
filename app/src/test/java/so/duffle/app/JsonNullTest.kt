package so.duffle.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Optional text must survive a JSON `null`.
 *
 * **This test could not have caught the bug it exists for**, and that is worth stating rather
 * than quietly leaving a green tick to imply otherwise. Android's `JSONObject.optString` turns
 * `JSONObject.NULL` into the four characters `"null"`; the desktop `org.json` on this test
 * classpath returns `""` for the same input. The two disagree at exactly the point that failed,
 * so the broken code passes here and only the phone showed it — a product with no description
 * rendered the word `null` on screen, and a card with no PIN would have printed `null` as its
 * PIN.
 *
 * What is pinned below is the contract [text] is supposed to honour on both: absent, null and
 * blank are all "no value". A regression to bare `optString` still passes on the JVM, so this
 * one is checked on the device.
 */
class JsonNullTest {

    private val o = JSONObject("""{"a":null,"b":"","c":"  ","d":"Netflix India"}""")

    @Test fun `json null is no value`() = assertNull(o.text("a"))

    @Test fun `absent key is no value`() = assertNull(o.text("missing"))

    @Test fun `blank and whitespace are no value`() {
        assertNull(o.text("b"))
        assertNull(o.text("c"))
    }

    @Test fun `real text survives`() = assertEquals("Netflix India", o.text("d"))

    /** The divergence itself, recorded so the next reader does not re-derive it. */
    @Test fun `desktop and android disagree on optString of a json null`() {
        assertEquals("", o.optString("a"))   // Android returns "null" here.
        assertEquals(true, o.isNull("a"))    // Both agree on this, which is why text() uses it.
    }
}
