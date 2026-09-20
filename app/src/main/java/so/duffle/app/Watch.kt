package so.duffle.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The payee's side: every request handed out, until someone pays it or it is removed.
 *
 * **No expiry.** A request is valid for as long as its reference is unused, so a payer who pays
 * an hour later — or tomorrow, from their Requests tab — must still be recognised. The timers
 * this used to carry existed only because a blockhash once rode inside the request; it no longer
 * does. What is left is a list, and the chain as the judge of what has been paid.
 *
 * On disk, because the payment arrives whether or not this app is running, and a payee who is
 * never told is worse than one told late.
 */
class Watch(context: Context) {

    private val prefs = context.getSharedPreferences("watch", Context.MODE_PRIVATE)

    data class Entry(
        val reference: String,
        /** Our address when the request was made — the one the payment must reach. */
        val recipient: String,
        val amount: String,
        val createdAt: Long,
        val signature: String? = null,
        val paidAt: Long? = null,
        /** The payer declined. Kept and greyed, since they may still pay or tap again. */
        val rejectedBy: String? = null,
        /** The payer said "later". Not an answer: still expected money, just not yet. */
        val deferredBy: String? = null,
        /** "SOL" or the SPL mint asked for — with its decimals and name, as in [PayRequest]. */
        val token: String = "SOL",
        val decimals: Int = 9,
        val symbol: String = "SOL",
    ) {
        val paid get() = signature != null
        val rejected get() = rejectedBy != null
        val pending get() = deferredBy != null && !paid && !rejected
        val mint: String? get() = if (token == "SOL") null else token
        /** The amount in the token's smallest unit. */
        fun units(): Long? = PayRequest.baseUnits(amount, decimals)
        fun lamports(): Long? = units()
    }

    fun add(e: Entry) {
        save(listOf(e) + all().filter { it.reference != e.reference })
    }

    /**
     * Records a payment. Refuses a signature that already settled a DIFFERENT request: one
     * transaction can carry several references while paying only once, and without this check
     * a single 0.01 SOL transfer could mark two 0.01 SOL requests as paid.
     */
    fun markPaid(reference: String, signature: String): Boolean {
        val all = all()
        if (all.any { it.signature == signature && it.reference != reference }) return false
        save(all.map {
            if (it.reference == reference && !it.paid)
                it.copy(signature = signature, paidAt = System.currentTimeMillis())
            else it
        })
        return true
    }

    fun markRejected(reference: String, by: String) {
        save(all().map { if (it.reference == reference && !it.paid) it.copy(rejectedBy = by.ifBlank { "They" }) else it })
    }

    /** They tapped "Later". Recorded, never overriding a decline or a payment. */
    fun markDeferred(reference: String, by: String) {
        save(all().map {
            if (it.reference == reference && !it.paid && !it.rejected) it.copy(deferredBy = by.ifBlank { "They" }) else it
        })
    }

    fun remove(reference: String) = save(all().filter { it.reference != reference })

    fun all(): List<Entry> = try {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ref = o.optString("r")
            val to = o.optString("to")
            if (!PayRequest.isBase58Address(ref) || !PayRequest.isBase58Address(to)) return@mapNotNull null
            Entry(
                reference = ref,
                recipient = to,
                amount = o.optString("a"),
                createdAt = o.optLong("t"),
                signature = o.optString("s").takeIf { it.isNotBlank() },
                paidAt = o.optLong("p").takeIf { it > 0 },
                rejectedBy = o.optString("rb").takeIf { it.isNotBlank() },
                deferredBy = o.optString("db").takeIf { it.isNotBlank() },
                token = o.optString("k", "SOL").ifBlank { "SOL" },
                decimals = o.optInt("d", 9),
                symbol = o.optString("y", "SOL").ifBlank { "SOL" },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<Entry>) {
        val open = list.filter { !it.paid }.take(MAX_OPEN)
        val done = list.filter { it.paid }.sortedByDescending { it.paidAt }.take(MAX_HISTORY)
        val arr = JSONArray()
        (open + done).sortedByDescending { it.paidAt ?: it.createdAt }.forEach { e ->
            arr.put(JSONObject().apply {
                put("r", e.reference)
                put("to", e.recipient)
                put("a", e.amount)
                put("t", e.createdAt)
                e.signature?.let { put("s", it) }
                e.paidAt?.let { put("p", it) }
                e.rejectedBy?.let { put("rb", it) }
                e.deferredBy?.let { put("db", it) }
                put("k", e.token)
                put("d", e.decimals)
                put("y", e.symbol)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        // A new key: entries written by the timer-era build lack a recipient and are ignored.
        const val KEY = "requests_v2"
        const val MAX_OPEN = 50
        const val MAX_HISTORY = 30
    }
}
