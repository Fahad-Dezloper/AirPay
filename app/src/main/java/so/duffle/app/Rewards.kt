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
        return Preview(o.optInt("skr"), o.optString("refused").ifBlank { null }, o.optInt("min_age_days", 7))
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
                   val unlockAt: Long, val state: String)

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
                    it.optString("state"))
            }
        }
        val refusals = mutableListOf<Refusal>()
        o.optJSONArray("refused")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                refusals += Refusal(it.optString("counterparty"), it.optString("refused"),
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

    /** What a reward was for, in words. */
    fun nameOf(kind: String): String = when (kind) {
        "first_payment" -> "First payment"
        "per_payment" -> "Paid in person"
        "streak" -> "Weekly streak"
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
