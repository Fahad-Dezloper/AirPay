package so.duffle.app

import androidx.annotation.DrawableRes
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

    /** Who the other side is, which decides what the row's icon shows. */
    enum class Who { PERSON, SHOP, ADDRESS }

    /** The five categories a spend can be filed under. Travel and Bills have no icon yet. */
    enum class Spend { FOOD, SHOPPING, PHONE, MOVIES, OTHER }

    /**
     * Where the row has got to, which decides the line under the title.
     *
     * **CONFIRMING and REFUNDED are never produced by [build] today.** They are here because the
     * row draws them and because both are real states the app will reach, but neither has a
     * source yet: `build` admits only rows that already have a signature, so nothing in it is
     * still confirming, and an order that failed is not the same fact as money coming back —
     * `Orders.failed` says the order did not arrive, not that it was refunded. Saying
     * "Refunded to your wallet" off `failed` would be inventing a payment. See
     * ui/PORT-2026-10-01-2.md §5, which lists this as undesigned.
     */
    enum class State { SETTLED, CONFIRMING, PREPARING, DELIVERED, REFUNDED }

    /** The three shop brands that ship an icon. Anything else falls back to its category. */
    private val BRANDS = mapOf(
        "hotstar" to R.drawable.hotstar,
        "jiohotstar" to R.drawable.hotstar,
        "phonepe" to R.drawable.phonepe,
        "makemytrip" to R.drawable.makemytrip,
    )

    private fun brandFor(name: String): Int? {
        val key = name.lowercase().filter { it.isLetterOrDigit() }
        return BRANDS.entries.firstOrNull { key.startsWith(it.key) }?.value
    }

    /**
     * A shop shelf to one of the five categories.
     *
     * The shelf is the server's own wording and it has changed before ("Streaming" became
     * "TV & streaming"), so this matches loosely and falls back to OTHER rather than guessing.
     */
    internal fun spendFor(shelf: String): Spend {
        val s = shelf.lowercase()
        return when {
            "phone" in s || "recharge" in s || "mobile" in s -> Spend.PHONE
            "movie" in s || "cinema" in s || "stream" in s || "tv" in s -> Spend.MOVIES
            "food" in s || "meal" in s || "restaurant" in s -> Spend.FOOD
            "shop" in s || "retail" in s || "store" in s -> Spend.SHOPPING
            else -> Spend.OTHER
        }
    }

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
        /** Who the other side is — decides whether the icon is a face, a brand or a category. */
        val who: Who = Who.ADDRESS,
        val state: State = State.SETTLED,
        /** The other wallet. Seeds the face colour, and is the key the user's category is filed under. */
        val address: String? = null,
        /** A shop that is one brand, as a drawable. Null for everything else. */
        @DrawableRes val brand: Int? = null,
        /** What kind of expense. Null for a payment to an address nobody has filed yet. */
        val spend: Spend? = null,
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
        /** Whether this wallet is somebody in Contacts. An address that is not gets the "+". */
        known: (String) -> Boolean = { false },
        /** The category the user filed this payment under, by signature. */
        filed: (String?) -> Spend? = { null },
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
                who = if (known(i.request.recipient)) Who.PERSON else Who.ADDRESS,
                address = i.request.recipient,
                spend = filed(i.signature),
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
                // Money IN from a stranger still draws a face, never the "+": income is not an
                // expense to file (ui/PORT-2026-10-01-2.md §1.4).
                who = if (e.peer != null && known(e.peer)) Who.PERSON else Who.ADDRESS,
                address = e.peer,
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
                who = Who.SHOP,
                // PREPARING while it is still owed to us, DELIVERED once it arrives. A FAILED
                // order is neither: it is not on its way and it did not arrive, and calling it
                // REFUNDED would claim money came back that nothing here has seen. It keeps
                // SETTLED, and `note` already says what happened.
                state = when {
                    o.delivered -> State.DELIVERED
                    o.waiting -> State.PREPARING
                    else -> State.SETTLED
                },
                brand = brandFor(o.name),
                spend = spendFor(o.category),
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
