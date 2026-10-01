package so.duffle.app

import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * SKR earned by paying in person.
 *
 * The rules are **not** here. They live on the server ([duffleserver.rewards]) and are asked
 * over the wire, both before a payment and after it. A second copy on the phone would drift
 * from the first, and the version that drifts is always the one making the promise.
 *
 * Nothing here is ever a promise. A preview says what the rules would pay *now*; the real
 * decision runs again when the payment settles, and the week's pool may have emptied by then.
 * When the server can't be reached, every function returns null and the screens say nothing —
 * silence is the only honest fallback for a reward we can't confirm.
 */
object Rewards {

    /** Off unless this build asked for it and a server is configured to answer. */
    val enabled get() = BuildConfig.REWARDS && ServerClient.enabled

    /** What a payment would earn, and why it wouldn't. */
    data class Preview(val skr: Int, val refused: String?, val minAgeDays: Int) {
        val earns get() = skr > 0 && refused == null
    }

    /** What a payment did earn, once the server saw it settle. */
    data class Earned(val skr: Int, val kinds: List<String>)

    suspend fun preview(payer: String, counterparty: String, token: String, units: Long): Preview? {
        if (!enabled) return null
        val o = ServerClient.ask("/v1/rewards/preview", JSONObject().apply {
            put("payer", payer)
            put("counterparty", counterparty)
            put("token", token)
            put("units", units)
        }) ?: return null
        // stringOrNull, not optString: a preview that refuses nothing sends JSON null here, and
        // reading that as the string "null" made `earns` false — so the review screen could
        // never say "Earns N SKR", only ever the refusals. It looked like a design choice.
        return Preview(o.optInt("skr"), o.text("refused"), o.optInt("min_age_days", 7))
    }

    /**
     * Waits for the reward for one payment. The server only decides once it has seen the
     * payment on-chain, which is seconds after the wallet returns — so this polls rather than
     * asking once, and gives up quietly. A reward that arrives later is still in Rewards; it
     * just misses this screen.
     */
    suspend fun awaitEarned(payer: String, reference: String, tries: Int = 8): Earned? {
        if (!enabled) return null
        repeat(tries) { attempt ->
            delay(if (attempt == 0) 1_200L else 2_500L)
            val o = ServerClient.read("/v1/rewards/$payer") ?: return null
            val recent = o.optJSONArray("recent") ?: return@repeat
            var skr = 0
            val kinds = mutableListOf<String>()
            for (i in 0 until recent.length()) {
                val row = recent.optJSONObject(i) ?: continue
                if (row.optString("reference") == reference) {
                    skr += row.optInt("skr")
                    kinds += row.optString("kind")
                }
            }
            if (skr > 0) return Earned(skr, kinds)
        }
        return null
    }

    // ------------------------------------------------------------------ what one person has

    /** One reward row, or one payment that earned nothing. */
    data class Row(
        /** The server's own id, and the only handle a phone has to say "I looked at this". */
        val id: Int,
        val kind: String, val skr: Int, val counterparty: String, val at: Long,
        val unlockAt: Long, val state: String,
        /** Why it was taken back, when it was. Shown rather than hidden. */
        val reason: String?,
        /**
         * Whether this card has been scratched, as the **server** remembers it.
         *
         * Not kept on the phone: a card that re-covers itself after a reinstall would be lying
         * about which rewards are new. It has no bearing on the money — see [reveal].
         */
        val revealed: Boolean,
        /**
         * What the card holds when it is not SKR.
         *
         * Null on every card the server sends today — `rewards.py` has no concept of a gift
         * card and its `total` is `sum(a.skr)`, an integer. ui/PORT-2026-10-01-3.md §3 asks a
         * card to be able to hold one, and §10 lists the reward record's gift fields as
         * undesigned. So the shape is here and the screen draws it, and the day the server
         * sends `gift` the cards appear with no further work. Nothing fabricates one meanwhile.
         */
        val gift: Gift? = null,
    )

    /**
     * A gift card won on a scratch card: what it buys, and what it is worth.
     *
     * [usd] is separate from [face] on purpose. The face value is in the brand's own currency
     * and is what the card says; the USD worth is what the total card sums. The phone does not
     * convert between them — both have to arrive from the server (§2).
     */
    data class Gift(
        val brand: String,
        /** Bitrefill's icon id, e.g. "amazon-icon". The logo is fetched padded and transparent. */
        val logoId: String?,
        /** Face value in [currency], whole: 250 reads "₹250". */
        val face: Double,
        val currency: String,
        val usd: Double?,
    )

    data class Refusal(val counterparty: String, val why: String, val at: Long)

    data class Summary(
        val locked: Int, val ready: Int, val settled: Int, val total: Int,
        /** Rewarded payments this week, out of what the week allows. */
        val weekPayments: Int, val weekAllows: Int,
        val monthEarned: Int, val monthCap: Int,
        /** SKR left in this week's pool, across everyone. Nobody earns from an empty one. */
        val poolLeft: Int,
        val perPayment: Int, val streak: Int, val streakNeeded: Int, val lockDays: Int,
        val rows: List<Row>, val refusals: List<Refusal>,
        /**
         * Everything earned, as money, when the server can say.
         *
         * §2 wants the total card to read "$16.24" rather than an SKR count, which needs the
         * SKR price and each gift card's worth — and §2 is explicit that the phone does not
         * convert currencies, so neither may be worked out here. The server supplies none of it
         * today (`rewards.py` has no USD anywhere), so this is null and the card falls back to
         * the SKR figure rather than printing a dollar sign in front of a number nobody priced.
         */
        val totalUsd: Double? = null,
    )

    suspend fun summary(address: String): Summary? {
        if (!enabled || address.isBlank()) return null
        val o = ServerClient.read("/v1/rewards/$address") ?: return null
        val bal = o.optJSONObject("balance")
        val week = o.optJSONObject("this_week")
        val month = o.optJSONObject("this_month")
        val rates = o.optJSONObject("rates")
        val rows = mutableListOf<Row>()
        o.optJSONArray("recent")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                rows += Row(it.optInt("id"), it.optString("kind"), it.optInt("skr"), it.optString("counterparty"),
                    (it.optDouble("created_at") * 1000).toLong(), (it.optDouble("unlock_at") * 1000).toLong(),
                    it.optString("state"), it.text("reason"), !it.isNull("revealed_at"),
                    gift = it.optJSONObject("gift")?.let { g ->
                        Gift(
                            brand = g.text("brand") ?: return@let null,
                            logoId = g.text("logo_id"),
                            face = g.optDouble("face", 0.0),
                            currency = g.text("currency") ?: "INR",
                            // Absent is NOT zero: a gift card whose worth the server has not
                            // priced must not drag the total down to nothing.
                            usd = if (g.isNull("usd")) null else g.optDouble("usd"),
                        )
                    })
            }
        }
        val refusals = mutableListOf<Refusal>()
        o.optJSONArray("refused")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                refusals += Refusal(it.optString("counterparty"), it.text("refused") ?: "",
                    (it.optDouble("at") * 1000).toLong())
            }
        }
        return Summary(
            locked = bal?.optInt("locked") ?: 0, ready = bal?.optInt("ready") ?: 0,
            settled = bal?.optInt("settled") ?: 0, total = bal?.optInt("total") ?: 0,
            totalUsd = bal?.let { if (it.isNull("total_usd")) null else it.optDouble("total_usd") },
            weekPayments = week?.optInt("rewarded_payments") ?: 0, weekAllows = week?.optInt("of") ?: 4,
            monthEarned = month?.optInt("earned") ?: 0, monthCap = month?.optInt("cap") ?: 0,
            poolLeft = o.optInt("pool_left"),
            perPayment = rates?.optInt("per_payment") ?: 0, streak = rates?.optInt("streak") ?: 0,
            streakNeeded = rates?.optInt("streak_needed") ?: 4, lockDays = rates?.optInt("lock_days") ?: 7,
            rows = rows, refusals = refusals,
        )
    }

    /**
     * Uncovers one reward card.
     *
     * **This does not decide anything.** The reward was evaluated, credited and counted against
     * the week's pool the moment the payment settled; all this records is that somebody has now
     * looked at it. The card is theatre over a decision already made, which is the whole
     * difference between it and a scratch card that pays out a random amount — a reward that
     * depended on opening a screen would be a lottery, and every defence this layer has against
     * farming rests on each reward being a fixed consequence of a rule.
     *
     * Returns true when the server took it. A refusal leaves the card covered rather than
     * showing a reveal the server does not agree happened.
     */
    suspend fun reveal(address: String, id: Int): Boolean {
        if (!enabled || id <= 0) return false
        val o = ServerClient.ask("/v1/rewards/reveal", JSONObject().apply {
            put("address", address)
            put("id", id)
        }) ?: return false
        return o.optBoolean("ok")
    }

    // ------------------------------------------------------------------------- offers

    /**
     * An offer: pay a particular app, earn SKR. [state] is this wallet's own — "available",
     * "open" (claimed, waiting for the payment), "paid" or "expired".
     */
    data class Offer(
        val id: String, val app: String, val what: String, val funder: String,
        val reward: Int, val symbol: String, val minimum: Long, val decimals: Int,
        val link: String, val windowHours: Int,
        val state: String, val earned: Int, val signature: String?, val refused: String?,
        /**
         * When this wallet claimed it, in millis — **0 when it has not**.
         *
         * `/v1/offers` sends `claimed_at` (`service.offers_for`), null for an offer nobody here
         * has claimed. With [windowHours] it is the whole countdown ui/PORT-2026-10-02.md §6
         * shows on a claimed offer: the deadline is `claimedAt + windowHours`, which is the
         * server's own rule (`offers.settle`) rather than a second one invented on the phone.
         *
         * §13 records the countdown as undecided because the sandbox's `21h 14m left` is a fixed
         * sample and the sample DATA has no claim time. The server does — so the sheet counts
         * down from the real one, and an unclaimed offer, having none, shows no countdown.
         */
        val claimedAt: Long = 0L,
        /**
         * The app's own icon.
         *
         * ui/PORT-2026-10-01-4.md §1.2 redefines an offer as a task in a dApp Store app and
         * says each one needs the app's name AND icon. The name arrives as [app]; the icon does
         * not — `/v1/offers` sends no such key today. Nullable and parsed, so the row shows a
         * real icon the moment the server adds one, and until then falls back to the app's
         * initial on its own colour. Nothing maps a name to a bundled asset: that would be
         * right for the five sample apps and wrong for the sixth, while looking like it worked.
         */
        val icon: String? = null,
    ) {
        val available get() = state == "available"
        val waiting get() = state == "open"
    }

    suspend fun offers(address: String): List<Offer>? {
        if (!enabled) return null
        val o = ServerClient.read("/v1/offers?address=$address") ?: return null
        val arr = o.optJSONArray("offers") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { j ->
                val token = j.optString("token", "SOL")
                Offer(
                    id = j.optString("id"), app = j.optString("app"), what = j.optString("what"),
                    funder = j.optString("funder"), reward = j.optInt("reward"),
                    symbol = if (token == "SOL") "SOL" else token.take(4),
                    minimum = j.optLong("minimum"), decimals = if (token == "SOL") 9 else 6,
                    link = j.optString("link"), windowHours = j.optDouble("window_hours", 48.0).toInt(),
                    state = j.optString("state", "available"), earned = j.optInt("skr_earned"),
                    signature = j.text("signature"), refused = j.text("refused"),
                    // Seconds on the wire, and JSON null for an unclaimed offer — `optDouble`
                    // answers the default for both a missing key and JSONObject.NULL, so an
                    // unclaimed offer lands on 0 rather than on the epoch.
                    claimedAt = (j.optDouble("claimed_at", 0.0) * 1000).toLong(),
                    icon = j.text("icon"),
                )
            }
        }
    }

    /** Opens an offer. This is also the moment the attribution window starts, so a payment
     *  made before it does not count — the app says so rather than letting it look broken. */
    suspend fun claim(offerId: String, address: String): String? {
        val o = ServerClient.ask("/v1/offers/$offerId/claim", JSONObject().apply {
            put("address", address)
        }) ?: return "no_server"
        return if (o.optBoolean("ok")) null else o.text("refused") ?: "refused"
    }

    /** Why an offer can't be opened, or hasn't paid yet. */
    fun offerWhy(why: String?): String? = when (why) {
        null, "" -> null
        "not_a_seeker" -> "Only a Seeker can claim this"
        "already_claimed" -> "You have already claimed this"
        "not_live" -> "This offer has ended"
        "offer_budget" -> "This offer has run out"
        "before_claim" -> "That payment was before you claimed — pay again to earn"
        "outside_window" -> "The window closed"
        "wrong_recipient" -> "Waiting for a payment to the app"
        "below_minimum" -> "That payment was under the minimum"
        "wrong_token" -> "Paid in the wrong token"
        "chain_unreachable" -> "Couldn't check the chain — try again"
        else -> why.replace('_', ' ')
    }

    /** What a reward was for, in words. */
    fun nameOf(kind: String): String = when (kind) {
        "first_payment" -> "First payment"
        "per_payment" -> "Paid in person"
        "streak" -> "Weekly streak"
        "offer" -> "Offer completed"
        else -> kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /**
     * The refusal, in a sentence someone reads before paying. Only the reasons that are about
     * *this* payment are worth a line; "the pool is empty" or "you've hit this week's cap" are
     * about the person, and belong on the rewards screen rather than on top of a payment they
     * are about to make anyway.
     */
    fun sayWhy(p: Preview?): String? = when (p?.refused) {
        null -> null
        "new_counterparty" -> "New wallet · this payment won't earn SKR"
        "below_minimum" -> "Too small to earn SKR"
        "unknown_token" -> "This token doesn't earn SKR"
        "self_payment" -> "Paying yourself doesn't earn SKR"
        "counterparty_cooldown" -> "Already earned with them this month"
        "not_a_seeker" -> null // the badge already says it, and it isn't news to them
        else -> null
    }
}
