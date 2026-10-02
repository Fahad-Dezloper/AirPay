package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two rules people noticed on the phone:
 *
 *  * Transaction history shows a reward only once its card has been SCRATCHED, as a settled
 *    "Earned" line — never as a shop order "getting your code" with a clock on it.
 *  * The notification page lists an offer only once a push has gone out for it, so offers arrive
 *    one at a time instead of all at once at sign-up.
 */
class RewardNoticeRulesTest {
    private fun row(id: Int, kind: String, revealed: Boolean, state: String = "locked", skr: Int = 10) =
        Rewards.Row(
            id = id, kind = kind, skr = skr, counterparty = "", at = 1_000L * id, unlockAt = 9_999_999L,
            state = state, reason = null, revealed = revealed,
        )

    private fun history(rows: List<Rewards.Row>, payouts: List<Rewards.Payout> = emptyList()) =
        History.build(
            toPay = emptyList(), owed = emptyList(), orders = emptyList(),
            name = { "Someone" }, rewards = rows, payouts = payouts,
        )

    @Test fun anUnscratchedCardIsNotATransactionYet() {
        assertTrue(history(listOf(row(1, "signup", revealed = false))).isEmpty())
    }

    @Test fun aScratchedCardIsASettledEarnedLineNotAnInProgressOne() {
        val t = history(listOf(row(1, "signup", revealed = true))).single()
        assertEquals(History.Kind.REWARD, t.kind)
        assertEquals("Welcome bonus", t.title)
        assertEquals("10 SKR", t.amount)
        // The clock badge and "Paid · getting your code" are keyed on these two states.
        assertEquals(History.State.SETTLED, t.state)
        assertTrue(t.note == null && t.code == null)
    }

    @Test fun reversedAndPaidRewardsAreNotListedTwiceOrAtAll() {
        val rows = listOf(
            row(1, "per_payment", revealed = true, state = "reversed"),
            row(2, "per_payment", revealed = true, state = "paid"),
            row(3, "per_payment", revealed = true, state = "settled"),
        )
        assertEquals(1, history(rows).size)
    }

    @Test fun aConfirmedPayoutIsSettledAndHasNoClock() {
        // The server's word for a payout that has landed is "confirmed", not "paid".
        val t = history(emptyList(), listOf(Rewards.Payout("p", 10, "confirmed", "sig", 1L))).single()
        assertEquals(History.State.SETTLED, t.state)
    }

    @Test fun aPayoutThatIsOnItsWayStillShowsItsClock() {
        val t = history(emptyList(), listOf(Rewards.Payout("p", 100, "sent", null, 1L))).single()
        assertEquals(History.State.CONFIRMING, t.state)
    }

    private fun offer(id: String, notifiedAt: Long, state: String = "available") = Rewards.Offer(
        id = id, app = "Jupiter", what = "Swap", funder = "duffle", reward = 300, symbol = "SOL",
        minimum = 0, decimals = 9, link = "", windowHours = 48, state = state, earned = 0,
        signature = null, refused = null, notifiedAt = notifiedAt,
    )

    private fun alerts(offers: List<Rewards.Offer>) = Alerts.build(
        toPay = emptyList(), owed = emptyList(), notes = emptyList(), orders = emptyList(),
        offers = offers, name = { "Someone" },
    ).filter { it.kind == Alerts.Kind.OFFER }

    @Test fun anOfferNobodyHasBeenToldAboutIsNotOnTheNotificationPage() {
        assertTrue(alerts(listOf(offer("a", notifiedAt = 0L), offer("b", notifiedAt = 0L))).isEmpty())
    }

    @Test fun onlyTheOffersThatWerePushedAreListedAtTheTimeTheyWerePushed() {
        val shown = alerts(listOf(offer("a", 5_000L), offer("b", 0L), offer("c", 9_000L)))
        assertEquals(listOf(9_000L, 5_000L), shown.map { it.at })
        assertFalse(shown.any { it.at == 0L })
    }

    @Test fun aToldOfferThatIsAlreadyClaimedIsHistoryNotANotification() {
        assertTrue(alerts(listOf(offer("a", 5_000L, state = "open"))).isEmpty())
    }

    @Test fun theNewOfferPushNamesTheOfferAndWhatItAsks() {
        val p = Progress.from("offer") { mapOf("state" to "new", "skr" to "300", "app" to "Jupiter",
            "detail" to "Swap \$5 or more on Jupiter")[it] }!!
        assertEquals("Earn 300 SKR with Jupiter" to "Swap \$5 or more on Jupiter", p.words())
    }
}
