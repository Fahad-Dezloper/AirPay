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
    data class Row(val kind: String, val skr: Int, val counterparty: String, val at: Long,
                   val unlockAt: Long, val state: String,
                   /** Why it was taken back, when it was. Shown rather than hidden. */
                   val reason: String?)

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
                rows += Row(it.optString("kind"), it.optInt("skr"), it.optString("counterparty"),
                    (it.optDouble("created_at") * 1000).toLong(), (it.optDouble("unlock_at") * 1000).toLong(),
                    it.optString("state"), it.text("reason"))
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
            weekPayments = week?.optInt("rewarded_payments") ?: 0, weekAllows = week?.optInt("of") ?: 4,
            monthEarned = month?.optInt("earned") ?: 0, monthCap = month?.optInt("cap") ?: 0,
            poolLeft = o.optInt("pool_left"),
            perPayment = rates?.optInt("per_payment") ?: 0, streak = rates?.optInt("streak") ?: 0,
            streakNeeded = rates?.optInt("streak_needed") ?: 4, lockDays = rates?.optInt("lock_days") ?: 7,
            rows = rows, refusals = refusals,
        )
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
