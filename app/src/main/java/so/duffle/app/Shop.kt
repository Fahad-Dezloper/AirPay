package so.duffle.app

import java.math.BigDecimal
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

        /**
         * The words §3.2 of ui/PORT-2026-10-02.md puts on a service's page — **every field of
         * it nullable, and today every field of it null**. See [Guide].
         */
        val guide: Guide = Guide(),
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
         * The same logo at its own shape, on **transparent** padding — what §3.1's tile and
         * §3.2's page logo draw.
         *
         * Two differences from [logoUrl], and both are load-bearing for §3. It asks for no
         * `w_`, so a wordmark comes back as a wordmark instead of being scaled down to fit a
         * square — §3.2 wants it "48 tall at its natural width". And it asks for no `b_rgb:`,
         * so the ground is the screen's to choose rather than the CDN's to bake in, which is
         * what lets a logo drawn for a dark ground be put on one (`groundFor` in Vouchers.kt
         * measures which). The same URL form `Vouchers.headerArt` already uses, for the same
         * two reasons.
         */
        val artUrl: String? get() = image?.takeIf { it.isNotBlank() }?.let {
            "https://www.bitrefill.com/content/cn/h_168/v1/$it.png"
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
     * §3.2's disclosure, as data: what this is, what must be read before buying, where it
     * works, how to claim it, and what an amount means.
     *
     * **Everything here is null or empty today, and that is the honest state of it.** §3.4 of
     * ui/PORT-2026-10-02.md is explicit: these words were written by hand from Bitrefill's long
     * descriptions and terms, Bitrefill's API does not supply them in this form, and "they need
     * a home on AirPay's server". The server has not grown one — `duffleserver/shop.py`'s
     * `_trim` sends exactly nine fields (id, name, currency, categories, image, recipient,
     * redemption, values, packages) and not one of them is any of these. §13 lists the wording
     * as an open point and notes that the "cannot change or refund it" sentence has legal
     * weight and has not been reviewed.
     *
     * So this is a **shape, not a copy of the sandbox's table**. Nothing is hardcoded per
     * service: every field is read off the product payload and every field is nullable, so a
     * page draws only the rows the server actually answered and leaves the rest out — which is
     * what §3.2 asks for anyway ("Leave a row out when the data has no value for it"). The
     * moment the server sends these keys the page fills in with no further change here.
     *
     * The key names are the ones the server would have to use.
     *
     * @param kind "Gift card" or "Subscription code" — §3.2's highlight badge.
     * @param line one line of what the service is for. §3.1's second row, and §3.2's.
     * @param where where a code can be spent.
     * @param valid how long it lasts.
     * @param uses whether it is one use or a balance.
     * @param know §3.2's "Read before you buy" bullets.
     * @param steps §3.2's numbered "How to claim it".
     * @param amounts one sentence saying what a price point means. §3.3 puts it over Pay.
     * @param brandHex the brand's own colour as `#RRGGBB`, for §3.1's tile and §3.2's logo box.
     *   Absent, the ground is measured from the logo's own pixels instead (`groundFor`).
     */
    data class Guide(
        val kind: String? = null,
        val line: String? = null,
        val where: String? = null,
        val valid: String? = null,
        val uses: String? = null,
        val know: List<String> = emptyList(),
        val steps: List<String> = emptyList(),
        val amounts: String? = null,
        val brandHex: String? = null,
    )

    /**
     * One thing you can buy from a product, and what it costs.
     *
     * [amount] is in the product's own currency — 219 really is the ₹219 a Jio 30GB pack costs.
     * It is for **choosing**: the price anybody actually pays comes back on the invoice, in
     * USDC, and is the only number this app treats as authoritative.
     *
     * @param usdPrice what this package costs in USDC, as a decimal string — §3.3's second line
     *   inside a price box, and the figure on its `Pay 5.49 USDC` button. **Null today.**
     *   `shop.py` deliberately withholds Bitrefill's own price field ("`price` is Bitrefill's
     *   internal unit and is NOT money anybody recognises… deliberately not sent to the app"),
     *   so there is no USDC figure on the wire for a denomination that has not been invoiced
     *   yet. Null makes §3.3's cost line render nothing and its button read a bare `Pay`; the
     *   real price still arrives on the invoice, which is the only number this app has ever
     *   treated as authoritative. See [Invoice].
     */
    data class Package(
        val value: String,
        val amount: Int,
        val id: String? = null,
        val usdPrice: String? = null,
    ) {
        /**
         * What to print as the price.
         *
         * `value` wins whenever it is a number, because it is the clean face value — KFC's
         * ₹1000 card has `value: "1000"` and `amount: 999.92`, a rounding artefact of their
         * pricing that was being shown to people as the price of the card. Bigbasket's ₹5000
         * reads 4999.59 the same way.
         *
         * `amount` is for the products where `value` is a sentence rather than a number: a Jio
         * bundle's value is "2GB/Day 5G Plan - 84 Days" and its price lives nowhere else.
         */
        val price: String get() = value.toDoubleOrNull()
            ?.let { if (it == Math.floor(it)) it.toLong().toString() else "%.2f".format(it) }
            ?: amount.toString()
    }

    data class Section(val title: String, val products: List<Product>)

    // -- §3.1: the list of services ------------------------------------------------------------

    /**
     * One buyable card inside a [Service] — a Bitrefill product, with the tab label §3.2 puts
     * on it.
     *
     * @param label what §3.2's pill reads. Null when the service sells one card, in which case
     *   §3.2 draws no tabs at all. For a brand that sells several, the sandbox's labels
     *   ("Flights & hotels", "Holidays", "Rail", "Bus", "Hotel card") are hand-written words
     *   and are not in the catalogue, so this falls back to the product's own server `name`
     *   with the brand and the country suffix trimmed off the ends of it. Derived from what the
     *   server sent, never invented.
     */
    data class Card(val product: Product, val label: String?)

    /**
     * One brand on §3.1's list, which is not always one product.
     *
     * @param id the brand key — a product id, or [BRANDS]' key for a grouped brand.
     * @param cards one or more, in §3.2's tab order. The first is the one a page opens on.
     */
    data class Service(val id: String, val name: String, val cards: List<Card>) {
        /** The card a page opens on, and the one §3.1's row draws. */
        val lead get() = cards.first().product
        /** §3.2 draws tabs only for a brand with several cards. */
        val tabbed get() = cards.size > 1
    }

    /**
     * §3.1's order, by Bitrefill's own product ids: "Hotstar, PhonePe, MakeMyTrip, Google Play,
     * The Man Company, FirstCry, Himalaya, Wakefit, Wildcraft, IKEA, Nykaa Men, Tinder Plus &
     * Gold, Airlines… then the rest of the catalogue."
     *
     * **This is a curation decision and it lives here rather than pretending to have come off
     * the wire.** The server sends no ordering — `_trim`'s nine fields do not include one — so
     * the catalogue's own order is whatever Bitrefill's API happened to return. The ids are the
     * server's; only the sequence is ours, and it is the sequence §3.1 names.
     *
     * Anything not listed keeps the order the server gave it, after these.
     */
    private val LEAD = listOf(
        "hotstar-india",
        "phonepe-india",
        "makemytrip",
        "google-play-india",
        "the-man-company-india",
        "first-cry-egift-voucher-india",
        "himalaya-india",
        "wakefit-india",
        "wildcraft-india",
        "ikea-india",
        "nykaa-men-india",
        "tinder-plus-and-gold-india",
        "airlinegift-india",
    )

    /**
     * The brands §3.1 collapses into **one** row, and the order of their tabs.
     *
     * "MakeMyTrip is one row although Bitrefill sells five MakeMyTrip products" — and those
     * five are five separate ids on the wire, with five separate validities, warnings and price
     * lists, which is exactly why §3.2 gives them tabs instead of five list rows.
     *
     * Listed by id rather than guessed from a shared prefix on purpose: the prefix before the
     * first `-` groups `first-cry-egift-voucher-india` under "first", and a shop that silently
     * merged two unrelated brands into one row would be the worse failure. A brand not listed
     * here is one product and one row.
     */
    private val BRANDS: Map<String, Pair<String, List<String>>> = mapOf(
        "makemytrip" to ("MakeMyTrip" to listOf(
            "makemytrip-voucher-india",
            "makemytrip-holiday-india",
            "makemytrip-rail-india",
            "makemytrip-bus-india",
            "makemytrip-hotel-card-india",
        )),
    )

    /** The catalogue's " India" / " India " suffix, which says nothing a per-country shop does not. */
    private fun trimCountry(name: String) = name.removeSuffix(" India").trim()

    /**
     * [products] as §3.1's list: grouped by brand, in §3.1's order.
     *
     * Order and grouping only. Nothing is added, nothing is dropped — a product the server sent
     * is a row (or a tab) here, and a product no [LEAD] entry names keeps its place behind the
     * ones that do.
     */
    fun services(products: List<Product>): List<Service> {
        val byId = products.associateBy { it.id }
        val grouped = mutableSetOf<String>()
        val out = mutableListOf<Service>()

        // The grouped brands first, wherever their members appear, so a member can never also
        // surface as a row of its own below.
        for ((key, brand) in BRANDS) {
            val (name, ids) = brand
            val cards = ids.mapNotNull { byId[it] }.map { p ->
                // The brand off the front, the country off the back: "MakeMyTrip Rail India"
                // → "Rail". Whatever is left when that trim empties the name is the product's
                // own name, which is still better than a blank pill.
                val label = trimCountry(p.name).removePrefix(name).trim()
                    .ifBlank { trimCountry(p.name) }
                Card(p, label)
            }
            if (cards.isEmpty()) continue
            grouped += ids
            out += Service(key, name, cards)
        }

        // Everything else: one product, one service, no tabs.
        for (p in products) {
            if (p.id in grouped) continue
            out += Service(p.id, trimCountry(p.name), listOf(Card(p, null)))
        }

        // `indexOf` is -1 for anything unlisted, so a listed brand sorts by its place in [LEAD]
        // and everything else sorts after it by the order it arrived in. Keyed on the index
        // this loop carries rather than `out.indexOf`, which would compare whole Services.
        return out.withIndex()
            .sortedBy { (i, s) -> LEAD.indexOf(s.id).takeIf { it >= 0 } ?: (LEAD.size + i) }
            .map { it.value }
    }

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
        /**
         * What it cost, written once and stored — `Orders.Order.paid` keeps this exact string
         * and the receipt renders it at [Type.heading].
         *
         * It was `"%.2f %s".format(amount / 1_000_000.0, currency)`: three faults in one line,
         * invisible until §4 put the figure at heading size. It **rounded half-up**, so a price
         * the buyer was charged could be written a hundredth lower than it was; it went through
         * `Double`, which cannot hold a decimal cent exactly; and `format` without a locale
         * writes the JVM default's separators, so the one figure the receipt exists for was the
         * only figure in the app not written the way the phone writes numbers. [Money] cuts
         * rather than rounds, stays in [BigDecimal], and follows the phone.
         */
        val shown: String get() = Money.token(
            BigDecimal.valueOf(amount).movePointLeft(USDC_DECIMALS), currency,
        )
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
        image = j.text("image"),
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
                            Package(v, o.optInt("amount", 0), o.text("id"), o.text("usd_price"))
                        }
                }
            }
        },
        guide = guide(j),
    )

    /** §3.2's words, read leniently: a key the server has not grown yet simply stays null. */
    private fun guide(j: JSONObject) = Guide(
        kind = j.text("kind"),
        line = j.text("line"),
        where = j.text("where_it_works"),
        valid = j.text("valid_for"),
        uses = j.text("how_used"),
        know = j.strings("know"),
        steps = j.strings("steps"),
        amounts = j.text("amounts"),
        brandHex = j.text("brand_colour"),
    )

    /** An array of non-blank strings, or nothing. `null` entries are dropped, not stringified. */
    private fun JSONObject.strings(name: String): List<String> {
        val a = optJSONArray(name) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            if (a.isNull(i)) null else a.optString(i).takeIf { it.isNotBlank() }
        }
    }

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
            about = o.text("about"),
            terms = o.text("terms"),
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
            code = any.text("code"),
            pin = any.text("pin"),
            link = any.text("link") ?: any.text("url"),
            instructions = any.text("instructions"),
            expires = any.text("expiration_date"),
            // Anything Bitrefill sends that we have no name for. Never dropped.
            // Anything Bitrefill sends that we have no name for. Never dropped — Subway's
            // barcode_format arrived this way and is worth keeping.
            //
            // But a field that merely repeats one already shown is noise: the same card put
            // "PIN Code: 298093" in an `other` field with the PIN already above it, so the
            // sheet said it twice.
            raw = any.keys().asSequence()
                .filterNot { it in setOf("code", "pin", "link", "url", "instructions", "expiration_date") }
                .mapNotNull { k ->
                    any.opt(k)?.toString()?.takeIf { it.isNotBlank() && it != "{}" }?.let { "$k: $it" }
                }
                .filterNot { line ->
                    listOf("code", "pin").any { key ->
                        any.text(key)?.let { line.contains(it) } == true
                    }
                }
                .joinToString("\n").takeIf { it.isNotBlank() },
        ).takeIf { !it.empty }
        else -> any.toString().takeIf { it.isNotBlank() }?.let { Redemption(raw = it) }
    }
}
