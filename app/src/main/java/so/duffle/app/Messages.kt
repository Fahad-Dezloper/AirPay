package so.duffle.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The words between two phones, kept next to the money.
 *
 * A payment request carries an amount and nothing else, which is right — a tap should not be a
 * chat. But the thing people actually do before handing money over in person is check they have
 * the right person: "is this you?". Without somewhere to say that, the only way to confirm an
 * address is to read 44 base58 characters off a screen, which nobody does.
 *
 * So: one line of text at a time, filed under the person, shown in the same thread as the
 * requests. Delivered by the server — the radio is gone by the time anyone types.
 *
 * **Held on this phone.** The server keeps a copy only until it has been collected; this store
 * is what survives. Sent messages are written here the moment they leave, so the thread shows
 * them without waiting for a round trip.
 */
class Messages(context: Context) {

    private val prefs = context.getSharedPreferences("messages", Context.MODE_PRIVATE)

    data class Note(
        /** The other phone, whichever direction this went. */
        val peer: String,
        val body: String,
        val at: Long,
        /** True when we wrote it. */
        val mine: Boolean,
        /** The server's row id for a received message; 0 for our own. Used to not store twice. */
        val id: Long = 0,
    )

    /** Records one. Returns false when this exact message is already held. */
    fun add(n: Note): Boolean {
        val all = all()
        if (n.id > 0 && all.any { it.id == n.id }) return false
        save(all + n)
        return true
    }

    fun with(peer: String): List<Note> = all().filter { it.peer == peer }.sortedBy { it.at }

    /**
     * When the newest received message arrived — where to ask the server to carry on from.
     *
     * Only received ones count: our own were never on the server's list for us, and letting one
     * move the cursor would skip past anything that arrived while we were typing.
     */
    fun since(): Double = all().filter { !it.mine }.maxOfOrNull { it.at }?.let { it / 1000.0 } ?: 0.0

    fun all(): List<Note> = try {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val peer = o.optString("w")
            val body = o.optString("b")
            if (!PayRequest.isBase58Address(peer) || body.isBlank()) return@mapNotNull null
            Note(peer = peer, body = body, at = o.optLong("t"), mine = o.optBoolean("m"), id = o.optLong("i"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<Note>) {
        val arr = JSONArray()
        // Oldest dropped first: a thread is read from the bottom.
        list.sortedBy { it.at }.takeLast(MAX).forEach { n ->
            arr.put(JSONObject().apply {
                put("w", n.peer)
                put("b", n.body)
                put("t", n.at)
                if (n.mine) put("m", true)
                if (n.id > 0) put("i", n.id)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        /** What the server accepts, so the phone refuses at the same place rather than later. */
        const val MAX_BODY = 280
        private const val KEY = "notes"
        private const val MAX = 300
    }
}
