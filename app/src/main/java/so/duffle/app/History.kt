package so.duffle.app

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Everything that moved money, in one list.
 *
 * Derived from [Inbox], [Watch] and [Orders] for the same reason [Alerts] is: each of those
 * already holds the truth about one direction, and a fourth table that copied all three would
 * be free to disagree with them.
 *
 * **Only settled things appear here.** A request waiting to be paid is not a transaction — it
 * is a question — and a history that lists what might happen alongside what did cannot be read
 * as a record of anything. Anything here has a signature behind it, except a shop order, whose
 * signature is the payment and whose delivery is a separate fact worth showing.
 */
object History {

    enum class Kind { SENT, RECEIVED, SHOP }

    data class Txn(
        val kind: Kind,
        /** Who or what: a person's name, or "Jio India". */
        val title: String,
        /** The category line under it: "Paid in person", "Phone", "TV & streaming". */
        val category: String,
        /** Already formatted with its symbol: "0.5 USDC". */
        val amount: String,
        val at: Long,
        val signature: String?,
        /** Set when the other side is a person, so a row can open their thread. */
        val peer: String? = null,
        /** Shop orders only: what became of it, when that is still worth saying. */
        val note: String? = null,
        /** Shop orders only: the code, PIN or link it produced. Worth copying, so kept apart. */
        val code: String? = null,
    ) {
        val incoming get() = kind == Kind.RECEIVED
        val month: YearMonth get() = monthOf(at)
    }

    fun monthOf(at: Long): YearMonth =
        YearMonth.from(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

    fun build(
        toPay: List<Inbox.Item>,
        owed: List<Watch.Entry>,
        orders: List<Orders.Order>,
        name: (String) -> String,
    ): List<Txn> {
        val out = mutableListOf<Txn>()

        for (i in toPay.filter { it.paid }) {
            out += Txn(
                kind = Kind.SENT,
                title = name(i.request.recipient),
                category = "Paid in person",
                amount = "${i.request.amount} ${i.request.symbol}",
                at = i.paidAt ?: i.readAt,
                signature = i.signature,
                peer = i.request.recipient,
            )
        }

        for (e in owed.filter { it.paid }) {
            out += Txn(
                kind = Kind.RECEIVED,
                title = e.peer?.let(name) ?: "Someone",
                category = "Received in person",
                amount = "${e.amount} ${e.symbol}",
                at = e.paidAt ?: e.createdAt,
                signature = e.signature,
                peer = e.peer,
            )
        }

        for (o in orders) {
            out += Txn(
                kind = Kind.SHOP,
                title = o.name,
                // Bought before categories were recorded, or from a shelf with no name.
                category = o.category.ifBlank { "Bill" },
                amount = o.paid,
                at = o.at,
                signature = o.signature,
                note = when {
                    // The code IS the purchase, where there is one.
                    !o.code.isNullOrBlank() -> o.code
                    // A top-up has nothing to redeem — the money went to the number, and
                    // saying so is the useful fact. Confirmed against a real delivered order:
                    // Bitrefill returns no redemption_info for a phone product, correctly.
                    o.delivered && !o.recipient.isNullOrBlank() -> "Credited to ${o.recipient}"
                    o.delivered -> "Delivered"
                    o.failed -> "Didn't go through — ${o.status}"
                    else -> "On its way"
                },
                code = o.code,
            )
        }

        return out.sortedByDescending { it.at }
    }

    /**
     * Every month from the first transaction to this one, newest first.
     *
     * Built as a continuous range rather than from the months that happen to have transactions
     * in them: a picker that silently omits an empty month makes it look like the app was not
     * installed then, and leaves nowhere to land when stepping back through with the chevrons.
     */
    fun months(list: List<Txn>): List<YearMonth> {
        val now = YearMonth.now()
        val first = list.minOfOrNull { it.at }?.let(::monthOf) ?: return listOf(now)
        val out = mutableListOf<YearMonth>()
        var m = maxOf(now, list.maxOf { it.at }.let(::monthOf))
        while (!m.isBefore(first)) {
            out += m
            m = m.minusMonths(1)
        }
        return out
    }
}
