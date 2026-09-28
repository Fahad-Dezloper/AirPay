package so.duffle.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What has been bought, and whether it has arrived.
 *
 * A recharge is paid in one second and delivered in ten, and until now that gap existed only
 * inside a coroutine that died with the screen. Close the app after paying and there was
 * nothing anywhere — no record that money had left, no way to ask again. **This is the only
 * thing in the app that spends real money**, so it is the last place that should forget.
 *
 * Kept on the phone rather than asked for: Bitrefill knows the invoice, but the invoice id is
 * the only handle we have on it, and that handle lived in memory too.
 */
class Orders(context: Context) {

    private val prefs = context.getSharedPreferences("orders", Context.MODE_PRIVATE)

    data class Order(
        val invoiceId: String,
        /** What it was — "Jio India", "Netflix India". Shown, so it is stored, not derived. */
        val name: String,
        /**
         * The shelf it came off: "Phone", "TV & streaming", "Bills & wallets".
         *
         * Stored rather than looked up, because the catalogue is the server's and it changes.
         * A history that says "Phone" for a recharge bought last month has to keep saying it
         * after the product is gone. Blank for anything bought before this was recorded.
         */
        val category: String = "",
        /** The denomination in the product's own currency, e.g. "50". */
        val value: String,
        /**
         * The number it was credited to, for a recharge. Null for everything else.
         *
         * Kept so the recharge screen can say what you last bought for **this** number, which is
         * the only honest version of a "recommended plan" available here: no third party can be
         * told what plan a number is on, so the nearest true statement is what you chose before.
         */
        val recipient: String? = null,
        /**
         * The code, PIN or link this purchase produced.
         *
         * The whole point of buying a gift card, and the app used to throw it away — see
         * [Shop.order]. Kept here because it has to survive the screen that was open when it
         * arrived: delivery lands seconds after payment and the buyer is often already gone.
         */
        val code: String? = null,
        /** What it cost, already formatted: "0.56 USDC". */
        val paid: String,
        val signature: String,
        /** Bitrefill's word: "pending", "delivered", "failed" — or "paid" before it answers. */
        val status: String,
        val at: Long,
    ) {
        val delivered get() = status == "delivered"
        val failed get() = status == "failed" || status == "expired"
        /** Still owed to us. The only state worth putting in front of someone. */
        val waiting get() = !delivered && !failed
    }

    fun add(o: Order) = save(listOf(o) + all().filter { it.invoiceId != o.invoiceId })

    /** Writes down what a purchase produced. Never overwritten once set. */
    fun setCode(invoiceId: String, code: String) {
        // Never overwritten. A later poll returning something emptier must not erase the code
        // somebody has already been shown and may be relying on.
        save(all().map {
            if (it.invoiceId == invoiceId && it.code.isNullOrBlank()) it.copy(code = code) else it
        })
    }

    fun setStatus(invoiceId: String, status: String) {
        save(all().map { if (it.invoiceId == invoiceId) it.copy(status = status) else it })
    }

    /** Anything paid for and not yet delivered, so the app can ask again on the next launch. */
    fun waiting(): List<Order> = all().filter { it.waiting }

    fun all(): List<Order> = try {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("i").ifBlank { return@mapNotNull null }
            Order(
                invoiceId = id, name = o.optString("n"), value = o.optString("v"),
                category = o.optString("g"),
                paid = o.optString("c"), signature = o.optString("s"),
                status = o.optString("t", "paid").ifBlank { "paid" }, at = o.optLong("a"),
                recipient = o.optString("r").takeIf { it.isNotBlank() },
                code = o.optString("k").takeIf { it.isNotBlank() },
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun save(list: List<Order>) {
        val arr = JSONArray()
        list.sortedByDescending { it.at }.take(MAX).forEach { o ->
            arr.put(JSONObject().apply {
                put("i", o.invoiceId)
                put("n", o.name)
                put("v", o.value)
                put("g", o.category)
                put("c", o.paid)
                put("s", o.signature)
                put("t", o.status)
                put("a", o.at)
                o.recipient?.let { put("r", it) }
                o.code?.let { put("k", it) }
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "orders"
        const val MAX = 50
    }
}
