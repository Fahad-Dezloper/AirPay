package com.tapsol.tap

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The payee's outstanding payments, written down.
 *
 * **A list, not a single slot.** Waiting is the one part of a tap that outlives the gesture —
 * the phones parted seconds ago and a payer may take half a minute in their wallet — and there
 * is no reason the payee should stand idle through it. Someone collecting from a queue of
 * people taps the next phone immediately; the first payment is still in flight, and both must
 * be tracked. Holding one reference at a time made the second person wait on the first.
 *
 * On disk, because the wait also has to survive the app being backgrounded out: the SOL moves
 * regardless, and a payee who is never told is worse than one who is told late. Each entry
 * gets one more look at the chain per launch until it is old enough to call abandoned.
 */
class Watch(context: Context) {

    private val prefs = context.getSharedPreferences("watch", Context.MODE_PRIVATE)

    data class Armed(
        val reference: String,
        val amount: String,
        val startedAt: Long,
        /** Chain state at serve time — see Chain.blockhashAlive for why it is kept. */
        val blockhash: String?,
        /** Who tapped, for the on-screen list. */
        val label: String = "",
    )

    fun add(a: Armed) {
        val kept = all().filter { it.reference != a.reference }
        save((kept + a).takeLast(MAX_OUTSTANDING))
    }

    fun remove(reference: String) {
        save(all().filter { it.reference != reference })
    }

    /** Corrupt or unreadable entries are dropped rather than trusted. */
    fun all(): List<Armed> = try {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ref = o.optString("r")
            if (!PayRequest.isBase58Address(ref)) return@mapNotNull null
            Armed(
                reference = ref,
                amount = o.optString("a"),
                startedAt = o.optLong("t", System.currentTimeMillis()),
                blockhash = o.optString("b").takeIf { it.isNotBlank() },
                label = o.optString("l"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun save(list: List<Armed>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().apply {
                put("r", a.reference)
                put("a", a.amount)
                put("t", a.startedAt)
                a.blockhash?.let { put("b", it) }
                put("l", a.label)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        /** After this, a payment nobody made is abandoned rather than carried forever. */
        const val ABANDON_MS = 24 * 60 * 60 * 1000L

        /** A ceiling, because every outstanding payment costs a poll against the chain. */
        const val MAX_OUTSTANDING = 5

        private const val KEY = "outstanding"
    }
}
