package so.duffle.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * People you have tapped.
 *
 * This is the retention half of the product. A payment is episodic — you might not send money
 * for a fortnight — but a contact list built by tapping gives the app a reason to be opened in
 * between, and it costs nothing to collect because the first tap already exchanges everything
 * needed to make an entry.
 *
 * Stored locally only. A directory mapping people to wallet addresses is exactly the kind of
 * thing that should never leave the device.
 */
data class Contact(
    val address: String,
    val label: String,
    /** True when they tapped from a verified Seeker. */
    val seeker: Boolean,
    val firstTapMillis: Long,
    var lastTapMillis: Long,
    var taps: Int,
    var totalPaid: Double = 0.0,
) {
    val short: String get() = "${address.take(6)}…${address.takeLast(4)}"
}

class Contacts(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("contacts", Context.MODE_PRIVATE)

    fun all(): List<Contact> = load().sortedByDescending { it.lastTapMillis }

    /**
     * Records a tap. Returns true when this is somebody new — which the reward loop cares
     * about, because a new counterparty grows the network and a repeat one does not, and
     * tapping the same friend five times is the obvious way to farm.
     */
    /**
     * Records a tap.
     *
     * `seeker` is what the **chain** said, never what the tap claimed. A payload can carry any
     * Genesis mint it likes, and a badge earned by claiming it is worth nothing — the same
     * mistake the confirm sheet made before it waited for the mainnet lookup. Callers who have
     * only the claim pass false and let [verify] fill it in when the answer arrives.
     */
    fun record(address: String, label: String, seeker: Boolean, paid: Double = 0.0): Boolean {
        val list = load().toMutableList()
        val now = System.currentTimeMillis()
        val existing = list.indexOfFirst { it.address == address }
        val isNew = existing < 0
        if (isNew) {
            list.add(Contact(address, label, seeker, now, now, 1, paid))
        } else {
            val c = list[existing]
            list[existing] = c.copy(
                // A contact can gain the Seeker badge but never silently lose it to one
                // failed lookup — absence of evidence is not evidence of absence.
                seeker = c.seeker || seeker,
                label = label.ifBlank { c.label },
                lastTapMillis = now,
                taps = c.taps + 1,
                totalPaid = c.totalPaid + paid,
            )
        }
        save(list)
        return isNew
    }

    /**
     * The chain's answer about a contact's Genesis token, once it lands.
     *
     * Only ever grants the badge. A lookup that fails, or a wallet queried while offline, must
     * not strip a badge that was verified on a day the network worked — absence of evidence is
     * not evidence of absence, and Genesis is soulbound, so a true answer stays true.
     */
    fun verify(address: String, held: Boolean) {
        if (!held) return
        val list = load().toMutableList()
        val i = list.indexOfFirst { it.address == address }
        if (i < 0 || list[i].seeker) return
        list[i] = list[i].copy(seeker = true)
        save(list)
    }

    fun count(): Int = load().size

    private fun load(): List<Contact> = try {
        val raw = prefs.getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    Contact(
                        address = o.getString("a"),
                        label = o.optString("l"),
                        seeker = o.optBoolean("s"),
                        firstTapMillis = o.optLong("f"),
                        lastTapMillis = o.optLong("t"),
                        taps = o.optInt("n", 1),
                        totalPaid = o.optDouble("p", 0.0),
                    )
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<Contact>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject().apply {
                    put("a", c.address); put("l", c.label); put("s", c.seeker)
                    put("f", c.firstTapMillis); put("t", c.lastTapMillis)
                    put("n", c.taps); put("p", c.totalPaid)
                }
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object { const val KEY = "list" }
}
