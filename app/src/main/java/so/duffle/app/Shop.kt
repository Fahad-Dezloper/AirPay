package so.duffle.app

import org.json.JSONObject

/**
 * Buying real things: phone credit, a Hotstar subscription, a PhonePe balance that settles the
 * electricity bill.
 *
 * The catalogue and the invoice come from AirPay's server, which holds the Bitrefill key —
 * in the APK it would be readable by anyone who decompiled it. What comes back is an ordinary
 * Solana address and an amount in base units, so **paying for a recharge is the payment path
 * this app already has**, pointed somewhere other than a person.
 *
 * Bitrefill settles in real USDC — and so does everything else here now, because the whole app
 * moved to mainnet and the shop is no longer the exception it was built as. The warning on the
 * screen stays: money leaving for goods is worth saying out loud even when the tap beside it is
 * equally real.
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
        /** Bitrefill's own categories: "refill", "bundles", "voip", "esim"… */
        val categories: List<String> = emptyList(),
        /** Bitrefill's image id, e.g. "hotstar-svg". See [logoUrl]. */
        val image: String? = null,

        /** The denominations on offer, in the product's own currency. */
        val values: List<String>,
        /** The same denominations with what each costs. See [Package]. */
        val packages: List<Package> = emptyList(),
    ) {
        val needsPhone get() = recipient == "phone_number"

        /**
         * The brand's own logo, from Bitrefill's image CDN.
         *
         * Padded onto a light square rather than cropped, because these are logos of wildly
         * different shapes — a wordmark next to a roundel — and cropping them to a common box
         * cuts the wordmarks in half.
         */
        val logoUrl: String? get() = image?.takeIf { it.isNotBlank() }?.let {
            "https://www.bitrefill.com/content/cn/b_rgb:F8F8F8,c_pad,h_256,w_256/v1/$it.png"
        }

        /**
         * True when this tops up a mobile number on its own network.
         *
         * Not the same as [needsPhone]. Viber credit and an eSIM both want a phone number and
         * neither is a recharge — Viber buys calling credit inside an app, an eSIM is a second
         * line. Listing them beside Jio and Airtel under "Network" invites somebody to pick the
         * wrong one and pay real money for it.
         */
        val isMobileRecharge get() = needsPhone &&
            categories.any { it == "refill" || it == "bundles" || it == "data" } &&
            categories.none { it == "voip" || it == "esim" }

        /**
         * True when this product sells **plans** rather than amounts of credit.
         *
         * The difference decides the whole screen. A top-up product's value IS the money —
         * "50" means fifty rupees — so it draws as a row of amounts. A bundle product's value
         * is a description — "2GB/Day 5G Plan - 84 Days" — and its price lives in
         * [Package.amount]. Drawing the second like the first is how the shop ended up
         * offering "₹30GB – 30 Day Data Pack".
         */
        val sellsPlans get() = packages.any { it.value.toIntOrNull() == null && it.amount > 0 }
    }

    /**
     * One thing you can buy from a product, and what it costs.
     *
     * [amount] is in the product's own currency — 219 really is the ₹219 a Jio 30GB pack costs.
     * It is for **choosing**: the price anybody actually pays comes back on the invoice, in
     * USDC, and is the only number this app treats as authoritative.
     */
    data class Package(val value: String, val amount: Int, val id: String? = null)

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
        image = j.optString("image").takeIf { it.isNotBlank() },
        categories = j.optJSONArray("categories").let { a ->
            (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
        },
        redemption = j.optJSONArray("redemption").let { a ->
            (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
        },
        values = j.optJSONArray("values").let { a ->
            (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
        },
        packages = j.optJSONArray("packages").let { a ->
            (0 until (a?.length() ?: 0)).mapNotNull { i ->
                a!!.optJSONObject(i)?.let { o ->
                    o.optString("value").takeIf { it.isNotBlank() }
                        ?.let { v ->
                            Package(v, o.optInt("amount", 0), o.optString("id").takeIf { i -> i.isNotBlank() })
                        }
                }
            }
        },
    )

    /** Asks for a price. Nothing is bought and nothing is owed until the transfer is signed. */
    /**
     * @param phone the number to credit, in full international form. Required by anything whose
     *   `recipient` is `phone_number` — Bitrefill rejects the invoice without it, and there is
     *   nowhere else in the flow to ask.
     * @param refund the buyer's own wallet. Where the money goes if the order cannot be filled:
     *   Bitrefill's docs list a refund address as required for crypto payments, and the API
     *   accepts an invoice without one — which is the worst combination, because the gap only
     *   surfaces when a refund is actually owed.
     * @param packageId the exact package, when it is known. The docs make this and `value`
     *   mutually exclusive, and it is the unambiguous half: a bundle's value is a sentence.
     */
    suspend fun invoice(
        productId: String,
        value: String,
        phone: String? = null,
        refund: String? = null,
        packageId: String? = null,
        email: String? = null,
    ): Invoice? {
        val o = ServerClient.ask("/v1/shop/invoice", JSONObject().apply {
            put("product_id", productId)
            put("value", value)
            packageId?.takeIf { it.isNotBlank() }?.let { put("package_id", it) }
            phone?.takeIf { it.isNotBlank() }?.let { put("phone_number", it) }
            refund?.takeIf { it.isNotBlank() }?.let { put("refund_address", it) }
            email?.takeIf { it.isNotBlank() }?.let { put("email", it) }
        }) ?: return null
        val address = o.optString("address").takeIf { PayRequest.isBase58Address(it) } ?: return null
        val amount = o.optLong("amount", 0L).takeIf { it > 0 } ?: return null
        return Invoice(
            id = o.optString("invoice_id"), address = address, amount = amount,
            currency = o.optString("currency", "USDC"),
            productId = productId, value = value,
        )
    }

    /** What a number's network turned out to be. */
    sealed interface Operator {
        /** Bitrefill named the network, and this is what it sells for it. */
        data class Found(val product: Product) : Operator
        /** Asked, and it does not recognise the number. */
        data object Unknown : Operator
        /** Could not ask. Different from [Unknown], because one is an answer and one is not. */
        data object Unreachable : Operator
    }

    /**
     * Which network a number is on.
     *
     * Answered by the shop that has to deliver the recharge, which is the only source worth
     * trusting: India has had full mobile number portability since 2011, so a prefix says who
     * **issued** a number rather than who runs it now. A prefix table would be confidently
     * wrong for a very large number of people, and a recharge sent to the wrong network is
     * real money that does not come back.
     */
    suspend fun operatorFor(phone: String): Operator {
        // Longer than the default: this stands in front of Bitrefill rather than our own
        // database, and a cold lookup is about three seconds before the network is counted.
        val o = ServerClient.ask("/v1/shop/operator", JSONObject().apply {
            put("phone_number", phone)
        }, timeoutMs = 20_000) ?: return Operator.Unreachable
        if (!o.optBoolean("operator_found", false)) return Operator.Unknown
        val j = o.optJSONObject("product") ?: return Operator.Unknown
        return Operator.Found(product(j))
    }

    /**
     * The full description and terms for one product.
     *
     * Fetched when somebody opens a product rather than shipped with the catalogue: across a
     * hundred and fifty-eight products this text runs to about 286 KB, which is not worth
     * sending to draw a grid of tiles. Cutting it down to fit was the other option and it ended
     * sentences mid-word — losing exactly the half of a voucher's terms somebody needed.
     *
     * Already plain text on arrival. Eighty-nine of the catalogue's products write their copy
     * as HTML for a web page, and the server strips it.
     */
    data class Details(val about: String?, val terms: String?) {
        val empty get() = about == null && terms == null
    }

    private val detailCache = mutableMapOf<String, Details>()

    suspend fun details(productId: String): Details? {
        synchronized(detailCache) { detailCache[productId] }?.let { return it }
        val o = ServerClient.ask("/v1/shop/product", JSONObject().apply {
            put("product_id", productId)
        }, timeoutMs = 15_000) ?: return null
        val d = Details(
            about = o.optString("about").takeIf { it.isNotBlank() },
            terms = o.optString("terms").takeIf { it.isNotBlank() },
        )
        synchronized(detailCache) { detailCache[productId] = d }
        return d
    }

    /**
     * What became of an order, **including the thing that was bought**.
     *
     * A gift card is a code. The screen says "Code delivered to you" and for a long time that
     * was not true of this app: the server fetched Bitrefill's `redemption_info` and this
     * function read the status out of the reply and dropped the rest on the floor. Somebody
     * could pay real money for a Hotstar subscription and the app would tell them it had been
     * delivered without ever saying what.
     *
     * The shape of that field is Bitrefill's and varies by product, so it is read leniently:
     * whatever looks like a code, a PIN or a link is pulled out, and anything unrecognised is
     * kept verbatim rather than discarded. Showing an odd-looking string beats swallowing the
     * one thing the purchase was for.
     */
    data class Delivery(val status: String, val redemption: Redemption?) {
        /** See [Orders.Order.delivered] — the invoice says "all_delivered", the order "delivered". */
        val delivered get() = status in setOf("all_delivered", "complete", "delivered")
        val failed get() = status in setOf("denied", "payment_error", "failed", "expired")
    }

    suspend fun order(invoiceId: String): Delivery? {
        val o = ServerClient.ask("/v1/shop/order", JSONObject().apply {
            put("invoice_id", invoiceId)
        }) ?: return null
        val status = o.optString("status").ifBlank { null } ?: return null
        return Delivery(status, redemption(o.optJSONArray("orders")))
    }

    /**
     * What a delivered gift card actually gives you.
     *
     * The real shape, read off a delivered PhonePe card rather than guessed:
     *
     * ```
     * {"code": "9092609239069813", "pin": "171129",
     *  "expiration_date": "2027-09-28T00:00:00.000Z", "extra_fields": {}}
     * ```
     *
     * Kept in parts rather than flattened. The first version joined everything with newlines,
     * which stored the right characters and threw away which was the card number and which was
     * the PIN — leaving a sixteen-digit run and a six-digit run with nothing to say which was
     * which, on a row that shows one line.
     *
     * [raw] holds whatever could not be named, because a field we have not seen is still worth
     * showing: the alternative is silently dropping the thing somebody paid for.
     */
    data class Redemption(
        val code: String? = null,
        val pin: String? = null,
        val link: String? = null,
        val instructions: String? = null,
        val expires: String? = null,
        val raw: String? = null,
    ) {
        val empty get() = code == null && pin == null && link == null &&
            instructions == null && raw == null

        /** For storage: everything, losslessly, in one string. */
        fun toJson(): String = JSONObject().apply {
            code?.let { put("code", it) }
            pin?.let { put("pin", it) }
            link?.let { put("link", it) }
            instructions?.let { put("instructions", it) }
            expires?.let { put("expires", it) }
            raw?.let { put("raw", it) }
        }.toString()

        companion object {
            /** Reads back what [toJson] wrote — or treats a bare string as the code itself. */
            fun of(stored: String?): Redemption? {
                val s = stored?.takeIf { it.isNotBlank() } ?: return null
                return try {
                    val o = JSONObject(s)
                    Redemption(
                        code = o.optString("code").takeIf { it.isNotBlank() },
                        pin = o.optString("pin").takeIf { it.isNotBlank() },
                        link = o.optString("link").takeIf { it.isNotBlank() },
                        instructions = o.optString("instructions").takeIf { it.isNotBlank() },
                        expires = o.optString("expires").takeIf { it.isNotBlank() },
                        raw = o.optString("raw").takeIf { it.isNotBlank() },
                    ).takeIf { !it.empty }
                } catch (e: Exception) {
                    // Not JSON: either a product that returns a bare string, or a code stored
                    // by the first version of this, which joined the parts with newlines in
                    // the order Bitrefill lists them — code, then pin. That is our own format
                    // rather than a guess about somebody else's, so it can be split back
                    // instead of being left as one unreadable run of digits.
                    val lines = s.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (lines.size == 2) Redemption(code = lines[0], pin = lines[1])
                    else Redemption(code = s)
                }
            }
        }
    }

    /** The redemption of whichever order in the invoice has one. */
    private fun redemption(orders: org.json.JSONArray?): Redemption? {
        if (orders == null) return null
        for (i in 0 until orders.length()) {
            val r = orders.optJSONObject(i)?.opt("redemption") ?: continue
            parseRedemption(r)?.let { return it }
        }
        return null
    }

    private fun parseRedemption(any: Any?): Redemption? = when (any) {
        null, JSONObject.NULL -> null
        is String -> any.takeIf { it.isNotBlank() }?.let { Redemption(code = it) }
        is org.json.JSONArray ->
            (0 until any.length()).firstNotNullOfOrNull { parseRedemption(any.opt(it)) }
        is JSONObject -> Redemption(
            code = any.optString("code").takeIf { it.isNotBlank() },
            pin = any.optString("pin").takeIf { it.isNotBlank() },
            link = any.optString("link").takeIf { it.isNotBlank() }
                ?: any.optString("url").takeIf { it.isNotBlank() },
            instructions = any.optString("instructions").takeIf { it.isNotBlank() },
            expires = any.optString("expiration_date").takeIf { it.isNotBlank() },
            // Anything Bitrefill sends that we have no name for. Never dropped.
            raw = any.keys().asSequence()
                .filterNot { it in setOf("code", "pin", "link", "url", "instructions", "expiration_date") }
                .mapNotNull { k ->
                    any.opt(k)?.toString()?.takeIf { it.isNotBlank() && it != "{}" }?.let { "$k: $it" }
                }
                .joinToString("\n").takeIf { it.isNotBlank() },
        ).takeIf { !it.empty }
        else -> any.toString().takeIf { it.isNotBlank() }?.let { Redemption(raw = it) }
    }
}
