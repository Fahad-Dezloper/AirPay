package so.duffle.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app signs the amount the server's order asks for; it must be the plan the person saw. */
class OrderMatchesPlanTest {
    private fun plan(id: String = "p1", usdc: String = "5.00") =
        TopUp.Plan(id, "airtime", "Plan", usdc, null, null, "instant", null, null, emptyList())

    private fun order(usdc: String, planId: String? = "p1") = TopUp.Order(
        id = "cu_1", state = "waiting", phone = "+10000000000", country = "US", operator = "op",
        label = "Plan", face = null, usdc = usdc, address = "addr", payTo = "owner", createdAt = 0L, planId = planId,
    )

    @Test fun samePriceMatches() = assertTrue(TopUp.matchesPlan(order("5.00"), plan()))
    @Test fun smallRefreshAllowed() = assertTrue(TopUp.matchesPlan(order("5.10"), plan()))
    @Test fun inflatedAmountRefused() = assertFalse(TopUp.matchesPlan(order("500"), plan()))
    @Test fun justOverAllowanceRefused() = assertFalse(TopUp.matchesPlan(order("5.11"), plan()))
    @Test fun otherPlanRefused() = assertFalse(TopUp.matchesPlan(order("5.00", planId = "p2"), plan()))
    @Test fun zeroRefused() = assertFalse(TopUp.matchesPlan(order("0"), plan()))
    @Test fun tooManyDecimalsIsNullNotACrash() = assertNull(TopUp.units("1.0000001"))
}
