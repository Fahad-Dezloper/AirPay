package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId

/**
 * The month list behind the history page's chevrons.
 *
 * Under test here rather than on the phone because a real device only ever holds the months it
 * happens to have been used in — one, so far — and the interesting cases are a year boundary
 * and a gap, neither of which can be produced without waiting for them.
 */
class HistoryMonthsTest {

    private fun at(y: Int, m: Int, d: Int): Long =
        YearMonth.of(y, m).atDay(d).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun txn(at: Long) = History.Txn(
        kind = History.Kind.SENT, title = "T", category = "c", amount = "1 SOL",
        at = at, signature = null,
    )

    @Test
    fun `no history still offers this month`() {
        assertEquals(listOf(YearMonth.now()), History.months(emptyList()))
    }

    @Test
    fun `runs unbroken back to the first transaction, newest first`() {
        val now = YearMonth.now()
        val start = now.minusMonths(4)
        val list = listOf(txn(at(start.year, start.monthValue, 3)), txn(System.currentTimeMillis()))
        val months = History.months(list)
        assertEquals(5, months.size)
        assertEquals(now, months.first())
        assertEquals(start, months.last())
        // Each step is exactly one month back — the chevrons walk this list by index, so a
        // skipped entry would jump a month and a repeat would stall on one.
        months.zipWithNext { newer, older -> assertEquals(older, newer.minusMonths(1)) }
    }

    @Test
    fun `a month with nothing in it is still offered`() {
        val now = YearMonth.now()
        val old = now.minusMonths(2)
        // Nothing in the month between. It must still appear, or stepping back lands nowhere
        // and the phone looks like it was not owned that month.
        val months = History.months(listOf(txn(at(old.year, old.monthValue, 9))))
        assertEquals(listOf(now, now.minusMonths(1), old), months)
    }

    @Test
    fun `crossing a year end counts in months, not in numbers`() {
        val dec = YearMonth.of(2025, 12)
        val jan = YearMonth.of(2026, 1)
        assertEquals(dec, jan.minusMonths(1))
        val months = History.months(listOf(txn(at(2025, 12, 31)), txn(at(2026, 1, 1))))
        assertTrue("December must be in $months", months.contains(dec))
        assertTrue("January must be in $months", months.contains(jan))
        assertEquals(months.sortedDescending(), months)
    }

    @Test
    fun `a transaction is filed in the month it happened`() {
        assertEquals(YearMonth.of(2026, 3), History.monthOf(at(2026, 3, 17)))
    }
}
