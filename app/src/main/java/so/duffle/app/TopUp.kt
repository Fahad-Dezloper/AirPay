package so.duffle.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * CellUp's recharges, as the server offers them (server/duffleserver/topups.py).
 *
 * The catalogue is Cryptorefills', read and trimmed by our server; prices are in USDC and are
 * decided by the server, never here. An order comes back with the one-off address and exact
 * USDC amount to pay, and the server follows it to the end and pushes the result.
 */
object TopUp {

    data class Country(val code: String, val name: String)

    data class Operator(val id: String, val name: String, val logo: String?, val color: String?, val kinds: List<String>)

    data class Benefit(val type: String, val unit: String?, val amount: Double?)

    data class Plan(
        val id: String,
        val category: String,
        val label: String,
        val usdc: String,
        val faceAmount: String?,
        val faceCurrency: String?,
        val delivery: String,
        val validityDays: Int?,
        val description: String?,
        val benefits: List<Benefit>,
    ) {
        /** What the plan is called on a shelf: the operator's words, minus the parts the shelf already says. */
        val title: String get() = label.removePrefix("Topup Plan - ").removePrefix("Topup Plan ").trim()
        val usdcValue: Double get() = usdc.toDoubleOrNull() ?: 0.0
    }

    /** One shelf of plans, in the order a person looks for them. */
    enum class Shelf(val key: String, val title: String) {
        BUNDLE("mobile_bundle", "Plans"),
        DATA("mobile_data", "Data"),
        CREDIT("mobile_credits", "Top-up"),
        TALK("mobile_talk_time", "Talk time"),
        SMS("mobile_sms", "SMS");

        companion object {
            fun of(category: String) = entries.firstOrNull { it.key == category } ?: CREDIT
        }
    }

    data class Order(
        val id: String,
        val state: String,
        val phone: String,
        val country: String,
        val operator: String,
        val label: String,
        val face: String?,
        val usdc: String,
        val address: String?,
        val payTo: String?,
        val createdAt: Long,
        /** The plan it bought, so "Recharge again" can open that same plan. */
        val planId: String? = null,
        /** Who fills it: "cryptorefills" or "bitrefill". Support needs to know where to look. */
        val supplier: String = "cryptorefills",
    ) {
        val waiting get() = state == "waiting" || state == "partial"
        val delivered get() = state == "delivered"
        val failed get() = state in setOf("expired", "cancelled", "failed", "refunded")
        val finished get() = delivered || failed
        /** USDC in base units (6 decimals), exact: what the order asks for, to the last unit. */
        val usdcUnits: Long? get() = usdc.toBigDecimalOrNull()?.movePointRight(6)?.longValueExact()
    }

    sealed interface Placed {
        data class Ok(val order: Order) : Placed
        data class Refused(val message: String) : Placed
        data object SignIn : Placed
        data object Offline : Placed
    }

    @Volatile private var countriesCache: List<Country>? = null

    suspend fun countries(): List<Country> {
        countriesCache?.let { return it }
        val o = ServerClient.read("/v1/topup/countries") ?: return emptyList()
        val out = o.optJSONArray("countries").objects().mapNotNull {
            val code = it.optString("code").takeIf { c -> c.length == 2 } ?: return@mapNotNull null
            Country(code, it.optString("name").ifBlank { code })
        }
        if (out.isNotEmpty()) countriesCache = out
        return out
    }

    /** Null when the server could not be reached; empty when the country has no networks. */
    suspend fun operators(country: String): List<Operator>? {
        val o = ServerClient.read("/v1/topup/operators?country=$country") ?: return null
        return o.optJSONArray("operators").objects().mapNotNull {
            val id = it.optString("id").ifBlank { return@mapNotNull null }
            Operator(
                id, it.optString("name").ifBlank { id }, it.text("logo"), it.text("color"),
                it.optJSONArray("kinds").objects().map { k -> k.optString("category") },
            )
        }
    }

    suspend fun plans(country: String, operator: String): List<Plan>? {
        val q = java.net.URLEncoder.encode(operator, "UTF-8")
        val o = ServerClient.read("/v1/topup/plans?country=$country&operator=$q") ?: return null
        return o.optJSONArray("plans").objects().mapNotNull { plan(it) }
    }

    internal fun plan(j: JSONObject): Plan? {
        val id = j.text("id") ?: return null
        val usdc = j.text("usdc") ?: return null
        return Plan(
            id = id,
            category = j.optString("category"),
            label = j.optString("label").ifBlank { j.optString("denomination") },
            usdc = usdc,
            faceAmount = j.text("face_amount"),
            faceCurrency = j.text("face_currency"),
            delivery = j.optString("delivery", "phone"),
            validityDays = j.optInt("validity_days", 0).takeIf { it > 0 },
            description = j.text("description"),
            benefits = j.optJSONArray("benefits").objects().map {
                Benefit(it.optString("type"), it.text("unit"),
                    if (it.isNull("amount")) null else it.optDouble("amount").takeIf { d -> !d.isNaN() })
            },
        )
    }

    /** Which network [phone] is on, as one of the [country]'s operator ids; null when unsure. */
    suspend fun detect(phone: String, country: String): String? {
        val o = ServerClient.ask("/v1/topup/detect", JSONObject().put("phone_number", phone).put("country", country),
            timeoutMs = 20_000) ?: return null
        return o.text("operator")
    }

    suspend fun place(phone: String, country: String, operator: String, planId: String, email: String): Placed {
        val (code, o) = ServerClient.askRaw("/v1/topup/orders", JSONObject()
            .put("phone_number", phone).put("country", country).put("operator", operator)
            .put("plan_id", planId).put("email", email), timeoutMs = 30_000)
        return when {
            code == 0 -> Placed.Offline
            code == 401 -> { Session.stale(); Placed.SignIn }
            code in 200..299 && o != null -> order(o)?.let { Placed.Ok(it) } ?: Placed.Refused(FALLBACK)
            else -> Placed.Refused(o?.text("message") ?: FALLBACK)
        }
    }

    suspend fun status(id: String): Order? = ServerClient.read("/v1/topup/orders/$id")?.let { order(it) }

    suspend fun cancel(id: String): Order? = ServerClient.ask("/v1/topup/orders/$id/cancel", JSONObject())?.let { order(it) }

    suspend fun history(): List<Order>? {
        val o = ServerClient.read("/v1/topup/orders") ?: return null
        return o.optJSONArray("orders").objects().mapNotNull { order(it) }
    }

    internal fun order(j: JSONObject): Order? {
        val id = j.text("id") ?: return null
        return Order(
            id = id,
            state = j.optString("state", "waiting"),
            phone = j.optString("phone"),
            country = j.optString("country"),
            operator = j.optString("operator"),
            label = j.optString("label"),
            face = j.text("face"),
            usdc = j.optString("usdc"),
            address = j.text("address")?.takeIf { PayRequest.isBase58Address(it) },
            payTo = j.text("pay_to"),
            createdAt = (j.optDouble("created_at", 0.0) * 1000).toLong(),
            planId = j.text("plan_id"),
            supplier = j.optString("supplier", "cryptorefills"),
        )
    }

    sealed interface Helped {
        /** Saved. [mailed] false: the server has no mail account, so the phone's mail app sends it. */
        data class Sent(val mailed: Boolean) : Helped
        data class Refused(val message: String) : Helped
        /** The server has no mail account: the phone's own mail app sends it instead. */
        data object MailOff : Helped
        data object SignIn : Helped
        data object Offline : Helped
    }

    /** Asks for help with order [id]. The server adds the order's details from its own records. */
    suspend fun support(id: String, email: String, query: String): Helped {
        val (code, o) = ServerClient.askRaw("/v1/topup/orders/$id/support",
            JSONObject().put("email", email).put("query", query), timeoutMs = 30_000)
        return when {
            code == 0 -> Helped.Offline
            code == 401 -> { Session.stale(); Helped.SignIn }
            code in 200..299 -> Helped.Sent(o?.optBoolean("mailed", true) ?: true)
            o?.text("error") == "mail_off" -> Helped.MailOff
            else -> Helped.Refused(o?.text("message") ?: "Could not send right now. Try again in a minute.")
        }
    }

    /** One help request, with its order as it stands now. */
    data class Ticket(val id: String, val createdAt: Long, val email: String, val query: String,
                      val mailed: Boolean, val order: Order?)

    suspend fun tickets(): List<Ticket>? {
        val o = ServerClient.read("/v1/topup/support") ?: return null
        return o.optJSONArray("tickets").objects().mapNotNull {
            val id = it.text("id") ?: return@mapNotNull null
            Ticket(id, (it.optDouble("created_at", 0.0) * 1000).toLong(), it.optString("email"),
                it.optString("query"), it.optBoolean("mailed"), it.optJSONObject("order")?.let { j -> order(j) })
        }
    }

    private const val FALLBACK = "This recharge could not be started. Nothing was charged."

    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
