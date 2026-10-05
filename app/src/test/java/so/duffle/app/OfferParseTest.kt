package so.duffle.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/v1/offers` grew six optional fields (kind, steps, rules, cta, progress, reward_text), and an
 * older server sends none of them. What is pinned here is that all three shapes of "not there"
 * (missing, JSON null, the wrong type) land on the same fallback, so the sheet writes its own
 * steps and "Open {app}" instead of printing `null` or drawing a button that goes nowhere.
 */
class OfferParseTest {

    private fun parse(json: String) = Rewards.offerFrom(JSONObject(json))

    private val old = """{"id":"jup","app":"Jupiter","what":"Install Jupiter and swap once",
        "funder":"AirPay","reward":50,"token":"SOL","minimum":0,"link":"https://jup.ag",
        "window_hours":24,"state":"open","claimed_at":1700000000}"""

    @Test fun `a full new-style offer is read as sent`() {
        val o = parse("""{"id":"rc","app":"AirPay","what":"Recharge a phone in AirPay",
            "reward":50,"token":"SOL","state":"open","kind":"shop",
            "steps":["Claim this offer.","Recharge any phone.","Get your SKR."],
            "rules":["One per wallet","Paid within a day"],
            "cta":{"label":"Recharge now","target":"recharge"},
            "progress":{"done":2,"need":3},
            "reward_text":"Flat 50 SKR"}""")
        assertEquals("shop", o.kind)
        assertTrue(o.inAirPay)
        assertEquals(listOf("Claim this offer.", "Recharge any phone.", "Get your SKR."), o.steps)
        assertEquals(listOf("One per wallet", "Paid within a day"), o.rules)
        assertEquals(Rewards.Cta("Recharge now", "recharge"), o.cta)
        assertEquals(Rewards.Progress(2, 3), o.progress)
        assertEquals("Flat 50 SKR", o.rewardText)
    }

    @Test fun `every documented cta target is accepted`() {
        for (t in listOf("recharge", "shop", "tap", "pay", "link")) {
            val o = parse("""{"id":"x","cta":{"label":"Go","target":"$t"}}""")
            assertEquals(t, o.cta?.target)
        }
    }

    @Test fun `an old-style offer keeps its fields and falls back on the new ones`() {
        val o = parse(old)
        assertEquals("Jupiter", o.app)
        assertEquals(50, o.reward)
        assertEquals("https://jup.ag", o.link)
        assertEquals(24, o.windowHours)
        assertEquals(1_700_000_000_000L, o.claimedAt)
        assertTrue(o.waiting)
        assertNull(o.kind)
        assertFalse(o.inAirPay)
        assertTrue(o.steps.isEmpty())
        assertTrue(o.rules.isEmpty())
        assertNull(o.cta)
        assertNull(o.progress)
        assertNull(o.rewardText)
    }

    @Test fun `json null is the same as missing`() {
        val o = parse("""{"id":"x","kind":null,"steps":null,"rules":null,"cta":null,
            "progress":null,"reward_text":null}""")
        assertEquals(parse("""{"id":"x"}"""), o)
        // The one that matters on a phone: Android's optString turns a JSON null into "null".
        assertNull(o.rewardText)
    }

    @Test fun `wrong types fall back instead of being coerced`() {
        val o = parse("""{"id":"x","kind":7,"steps":"Claim it","rules":{"a":1},
            "cta":"Open","progress":[1,2],"reward_text":50}""")
        assertNull(o.kind)
        assertTrue(o.steps.isEmpty())
        assertTrue(o.rules.isEmpty())
        assertNull(o.cta)
        assertNull(o.progress)
        assertNull(o.rewardText)
    }

    @Test fun `an unknown kind is no kind`() = assertNull(parse("""{"id":"x","kind":"lend"}""").kind)

    @Test fun `bad list elements drop themselves and the rest survive in order`() {
        val o = parse("""{"id":"x","steps":["One.",null,3,"  ",{"a":1},"Two."]}""")
        assertEquals(listOf("One.", "Two."), o.steps)
    }

    @Test fun `steps stop at three`() {
        val o = parse("""{"id":"x","steps":["1","2","3","4","5"]}""")
        assertEquals(listOf("1", "2", "3"), o.steps)
    }

    @Test fun `a cta needs a label and a target this build can act on`() {
        assertNull(parse("""{"id":"x","cta":{"label":"Go","target":"stake"}}""").cta)
        assertNull(parse("""{"id":"x","cta":{"label":"","target":"tap"}}""").cta)
        assertNull(parse("""{"id":"x","cta":{"label":null,"target":"tap"}}""").cta)
        assertNull(parse("""{"id":"x","cta":{"target":"tap"}}""").cta)
        assertNull(parse("""{"id":"x","cta":{"label":"Go"}}""").cta)
        assertNull(parse("""{"id":"x","cta":{"label":"Go","target":3}}""").cta)
    }

    @Test fun `progress needs two numbers and something to do`() {
        assertNull(parse("""{"id":"x","progress":{"done":1}}""").progress)
        assertNull(parse("""{"id":"x","progress":{"done":"1","need":"3"}}""").progress)
        assertNull(parse("""{"id":"x","progress":{"done":0,"need":0}}""").progress)
        assertNull(parse("""{"id":"x","progress":{"done":1,"need":-2}}""").progress)
    }

    @Test fun `progress never runs past its ends`() {
        assertEquals(Rewards.Progress(3, 3), parse("""{"id":"x","progress":{"done":9,"need":3}}""").progress)
        assertEquals(Rewards.Progress(0, 3), parse("""{"id":"x","progress":{"done":-1,"need":3}}""").progress)
    }

    // ---- about, where, guide, payout, needs, reward_min

    @Test fun `a full descriptive offer is read as sent`() {
        val o = parse("""{"id":"jup-swap","app":"Jupiter","what":"Swap any token once on Jupiter",
            "reward":250,"reward_min":25,"state":"available","kind":"swap",
            "about":"Jupiter finds the best price for a swap. Do one and earn SKR.",
            "where":"In the Jupiter app or on jup.ag",
            "guide":[{"title":"Claim this offer","detail":"This starts your 48 hours."},
                     {"title":"Open Jupiter","detail":"Connect the same wallet."},
                     {"title":"Swap any token"}],
            "payout":"Your scratch card arrives within a few minutes.",
            "needs":["A little SOL for the fee","The Jupiter app"],
            "steps":["Claim.","Swap."]}""")
        assertEquals("Jupiter finds the best price for a swap. Do one and earn SKR.", o.about)
        assertEquals("In the Jupiter app or on jup.ag", o.where)
        assertEquals(
            listOf(
                Rewards.Step("Claim this offer", "This starts your 48 hours."),
                Rewards.Step("Open Jupiter", "Connect the same wallet."),
                Rewards.Step("Swap any token", null),
            ),
            o.guide,
        )
        assertEquals("Your scratch card arrives within a few minutes.", o.payout)
        assertEquals(listOf("A little SOL for the fee", "The Jupiter app"), o.needs)
        assertEquals(25, o.rewardMin)
        // The guide does not erase the old list; the sheet chooses between them.
        assertEquals(listOf("Claim.", "Swap."), o.steps)
    }

    @Test fun `an old-style offer has none of the descriptive fields`() {
        val o = parse(old)
        assertNull(o.about)
        assertNull(o.where)
        assertTrue(o.guide.isEmpty())
        assertNull(o.payout)
        assertTrue(o.needs.isEmpty())
        assertNull(o.rewardMin)
    }

    @Test fun `descriptive fields sent as json null are the same as missing`() {
        val o = parse("""{"id":"x","about":null,"where":null,"guide":null,"payout":null,
            "needs":null,"reward_min":null}""")
        assertEquals(parse("""{"id":"x"}"""), o)
        assertNull(o.about)
    }

    @Test fun `descriptive fields of the wrong type fall back`() {
        val o = parse("""{"id":"x","reward":50,"about":7,"where":["a"],"guide":"Claim it",
            "payout":{"a":1},"needs":"SOL","reward_min":"25"}""")
        assertNull(o.about)
        assertNull(o.where)
        assertTrue(o.guide.isEmpty())
        assertNull(o.payout)
        assertTrue(o.needs.isEmpty())
        assertNull(o.rewardMin)
    }

    @Test fun `blank words are not words`() {
        val o = parse("""{"id":"x","about":"   ","where":"","payout":" "}""")
        assertNull(o.about)
        assertNull(o.where)
        assertNull(o.payout)
    }

    @Test fun `a guide step needs a title and may go without a detail`() {
        val o = parse("""{"id":"x","guide":[{"detail":"no title"},{"title":"  ","detail":"blank"},
            null,4,{"title":"Kept","detail":7},{"title":" Trimmed ","detail":" too "},"Bare"]}""")
        assertEquals(
            listOf(Rewards.Step("Kept", null), Rewards.Step("Trimmed", "too"), Rewards.Step("Bare", null)),
            o.guide,
        )
    }

    @Test fun `a guide holds the five steps the server may send`() {
        val o = parse("""{"id":"x","guide":[{"title":"1"},{"title":"2"},{"title":"3"},
            {"title":"4"},{"title":"5"}]}""")
        assertEquals(5, o.guide.size)
    }

    @Test fun `bad needs drop themselves`() {
        val o = parse("""{"id":"x","needs":["SOL",null,2," ","A Seeker"]}""")
        assertEquals(listOf("SOL", "A Seeker"), o.needs)
    }

    @Test fun `reward_min is only a floor under the reward`() {
        assertEquals(25, parse("""{"id":"x","reward":250,"reward_min":25}""").rewardMin)
        assertNull(parse("""{"id":"x","reward":50,"reward_min":50}""").rewardMin)
        assertNull(parse("""{"id":"x","reward":50,"reward_min":80}""").rewardMin)
        assertNull(parse("""{"id":"x","reward":50,"reward_min":0}""").rewardMin)
        assertNull(parse("""{"id":"x","reward":50,"reward_min":-5}""").rewardMin)
        assertNull(parse("""{"id":"x","reward_min":25}""").rewardMin)
    }
}
