package so.duffle.app

import org.json.JSONObject

/**
 * Buying real things: phone credit, a Hotstar subscription, a PhonePe balance that settles the
 * electricity bill.
 *
 * The catalogue and the invoice come from Duffle's server, which holds the Bitrefill key —
 * in the APK it would be readable by anyone who decompiled it. What comes back is an ordinary
 * Solana address and an amount in base units, so **paying for a recharge is the payment path
 * this app already has**, pointed somewhere other than a person.
 *
 * **This is the one thing Duffle does on mainnet.** Bitrefill settles in real USDC; a devnet
 * transfer to that address would be a payment into nothing and a subscription that never
 * arrives. Every tap stays on devnet. The screen has to say which is which, every time.
 */
object Shop {

    /** Real USDC, on the cluster where it is real. */
    const val USDC_MAINNET = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    const val USDC_DECIMALS = 6

    val enabled get() = ServerClient.enabled

    data class Product(
        val id: String,
        val name: String,
        val currency: String,
        /** What the buyer must supply: "phone_number", "account", or "none". */
        val recipient: String,
        /** How it arrives: "online", "phone", or empty for a direct top-up. */
        val redemption: List<String>,
        /** The denominations on offer, in the product's own currency. */
        val values: List<String>,
    ) {
        val needsPhone get() = recipient == "phone_number"
    }

    data class Section(val title: String, val products: List<Product>)

    /** What an invoice asks for: somewhere to pay, and exactly how much. */
    data class Invoice(
        val id: String,
        val address: String,
        /** Base units of [currency] — 11960000 is 11.96 USDC. */
        val amount: Long,
        val currency: String,
        val productId: String,
        val value: String,
        /** When this app stops trusting the price. See [QUOTE_LIFE_MS]. */
        val quotedAt: Long = System.currentTimeMillis(),
    ) {
        val shown: String get() = "%.2f %s".format(amount / 1_000_000.0, currency)
        val expired get() = System.currentTimeMillis() - quotedAt > QUOTE_LIFE_MS
    }

    /**
     * How long a quote is trusted.
     *
     * Bitrefill publishes no expiry, but a price in USDC for goods priced in rupees is a quote
     * against a moving rate — the same shape as a blockhash, and the same failure if it is used
     * late. Two minutes is generous for reading a screen and short enough that nobody pays an
     * old number.
     */
    const val QUOTE_LIFE_MS = 120_000L

    suspend fun sections(): List<Section>? {
        if (!enabled) return null
        val o = ServerClient.read("/v1/shop") ?: return null
        if (!o.optBoolean("enabled", false)) return emptyList()
        val arr = o.optJSONArray("sections") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { s ->
                val ps = s.optJSONArray("products") ?: return@let null
                Section(
                    title = s.optString("title"),
                    products = (0 until ps.length()).mapNotNull { j -> ps.optJSONObject(j)?.let(::product) },
                )
            }
        }
    }

    private fun product(j: JSONObject) = Product(
        id = j.optString("id"),
        name = j.optString("name"),
        currency = j.optString("currency", "INR"),
        recipient = j.optString("recipient", "none"),
        redemption = j.optJSONArray("redemption").let { a ->
            (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
        },
        values = j.optJSONArray("values").let { a ->
            (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
        },
    )

    /** Asks for a price. Nothing is bought and nothing is owed until the transfer is signed. */
    suspend fun invoice(productId: String, value: String): Invoice? {
        val o = ServerClient.ask("/v1/shop/invoice", JSONObject().apply {
            put("product_id", productId)
            put("value", value)
        }) ?: return null
        val address = o.optString("address").takeIf { PayRequest.isBase58Address(it) } ?: return null
        val amount = o.optLong("amount", 0L).takeIf { it > 0 } ?: return null
        return Invoice(
            id = o.optString("invoice_id"), address = address, amount = amount,
            currency = o.optString("currency", "USDC"),
            productId = productId, value = value,
        )
    }

    /** What became of it. Delivery follows the payment by seconds, so the caller keeps asking. */
    suspend fun order(invoiceId: String): String? {
        val o = ServerClient.ask("/v1/shop/order", JSONObject().apply {
            put("invoice_id", invoiceId)
        }) ?: return null
        return o.optString("status").ifBlank { null }
    }
}
