package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrandTest {

    /** What Build reports on the two phones in hand, plus the usual suspects. */
    @Test fun `phones are recognised from what Android reports`() {
        assertEquals(Brand.SEEKER, Brand.keyFor("Solana Mobile Inc.", "solanamobile", "Seeker"))
        assertEquals("oneplus", Brand.keyFor("OnePlus", "OnePlus", "CPH2401"))
        assertEquals("google", Brand.keyFor("Google", "google", "Pixel 8"))
        assertEquals("samsung", Brand.keyFor("samsung", "samsung", "SM-S918B"))
        assertEquals("xiaomi", Brand.keyFor("Xiaomi", "Redmi", "23076RN4BI"))
        assertEquals("motorola", Brand.keyFor("motorola", "motorola", "moto g84 5G"))
        assertNull(Brand.keyFor("Fairphone", "Fairphone", "FP5"))
    }

    @Test fun `the brand travels on a tap`() {
        val card = TapCard("2qxPntVgWyxU2jQz2MLcUuCp71D9D1ZSTcbFvCcXWrUc", "CPH2401", brand = "oneplus")
        assertEquals("oneplus", (TapPayload.parse(card.toBytes()) as TapPayload.Card).card.brand)
    }

    /** It only ever picks a picture, so anything unknown is dropped rather than refusing the tap. */
    @Test fun `an unknown brand is dropped, not trusted and not fatal`() {
        val raw = """{"v":4,"k":"card","r":"2qxPntVgWyxU2jQz2MLcUuCp71D9D1ZSTcbFvCcXWrUc","l":"x","b":"<script>"}"""
        val card = (TapPayload.parse(raw.toByteArray()) as TapPayload.Card).card
        assertNull(card.brand)
    }

    /** The Seeker mark is earned on-chain, never by saying so — over the radio or in the brand field. */
    @Test fun `the Seeker mark needs the chain's confirmation`() {
        val claims = TapPayload.Card(TapCard("2qxPntVgWyxU2jQz2MLcUuCp71D9D1ZSTcbFvCcXWrUc", "Seeker",
            genesis = "4WZ9hkr9h743Kufw7zHYj9YAiYHMKW8fbHBUnGohD18D", brand = Brand.SEEKER))
        assertNull(Brand.of(claims, verified = false))
        assertEquals(Brand.SEEKER, Brand.of(claims, verified = true))
        // A verified Seeker on an older build that sends no brand still gets the mark.
        assertEquals(Brand.SEEKER, Brand.of(TapPayload.Card(claims.card.copy(brand = null)), verified = true))
        // Other brands are just labels, shown either way.
        val onePlus = TapPayload.Card(claims.card.copy(genesis = null, brand = "oneplus"))
        assertEquals("oneplus", Brand.of(onePlus, verified = false))
    }
}
