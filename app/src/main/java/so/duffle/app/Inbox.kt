package so.duffle.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every request this phone has read, kept until it is paid or dismissed.
 *
 * A tap used to open a confirm sheet with a clock on it, and if the payer was not quick the
 * request was gone. Nothing about a payment request needs to expire — it is "pay this amount to
 * this address, with this one-time reference" — so it now lands here and stays. The payer can
 * close the app, switch the phone off, and pay tomorrow from the Requests tab.
 *
 * Paid entries are kept for a while as history, so "did I pay them?" has an answer.
 */
class Inbox(context: Context) {

    private val prefs = context.getSharedPreferences("inbox", Context.MODE_PRIVATE)

    data class Item(
        val request: PayRequest,
        val readAt: Long,
        val signature: String? = null,
        val paidAt: Long? = null,
        /** The person who asked took it back. Kept visible, greyed, rather than vanishing. */
        val withdrawn: Boolean = false,
        /**
         * We said no. Kept, like every other ending: a row that vanishes when you decline
         * leaves the thread unable to answer "did they ask me, and what did I say" — which is
         * the whole reason it is a thread. They can always ask again; that is a new row.
         */
        val declined: Boolean = false,
    ) {
        val paid get() = signature != null
        val payable get() = !paid && !withdrawn && !declined
    }

    /** Adds a freshly read request. Returns false if this exact request is already here. */
    fun add(request: PayRequest): Boolean {
        val all = all()
        if (all.any { it.request.reference == request.reference }) return false
        save(listOf(Item(request, System.currentTimeMillis())) + all)
        return true
    }

    fun markPaid(reference: String, signature: String) {
        save(all().map {
            if (it.request.reference == reference && !it.paid)
                it.copy(signature = signature, paidAt = System.currentTimeMillis())
            else it
        })
    }

    /**
     * The requester took it back. Not deleted: a row disappearing while someone is inside their
     * wallet — or has just approved — is worse than one that stays and explains itself.
     */
    fun markWithdrawn(reference: String) {
        save(all().map { if (it.request.reference == reference && !it.paid) it.copy(withdrawn = true) else it })
    }

    /** We declined. Never over-writing a payment: a paid request has no "no" left in it. */
    fun markDeclined(reference: String) {
        save(all().map { if (it.request.reference == reference && !it.paid) it.copy(declined = true) else it })
    }

    fun remove(reference: String) = save(all().filter { it.request.reference != reference })

    fun get(reference: String): Item? = all().firstOrNull { it.request.reference == reference }

    /** Newest first. Anything unreadable is dropped rather than trusted — it arrived by radio. */
    fun all(): List<Item> = try {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val req = PayRequest.fromBytes(o.optString("q").toByteArray()) ?: return@mapNotNull null
            if (req.validate() != null) return@mapNotNull null
            Item(
                request = req,
                readAt = o.optLong("t"),
                signature = o.optString("s").takeIf { it.isNotBlank() },
                paidAt = o.optLong("p").takeIf { it > 0 },
                withdrawn = o.optBoolean("w"),
                declined = o.optBoolean("n"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(items: List<Item>) {
        // Open requests are never evicted for space; history is.
        val open = items.filter { !it.paid }.take(MAX_OPEN)
        val done = items.filter { it.paid }.sortedByDescending { it.paidAt }.take(MAX_HISTORY)
        val arr = JSONArray()
        (open + done).sortedByDescending { it.paidAt ?: it.readAt }.forEach { item ->
            arr.put(JSONObject().apply {
                put("q", String(item.request.toBytes(), Charsets.UTF_8))
                put("t", item.readAt)
                item.signature?.let { put("s", it) }
                item.paidAt?.let { put("p", it) }
                if (item.withdrawn) put("w", true)
                if (item.declined) put("n", true)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "items"
        const val MAX_OPEN = 50
        const val MAX_HISTORY = 30
    }
}
