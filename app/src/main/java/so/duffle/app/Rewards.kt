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
        /** When this card can no longer be scratched, in milliseconds. Zero when the server does not say. */
        val expiresAt: Long = 0L,
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

    /** One SKR payout to this wallet, from the server's record of it. */
    data class Payout(val id: String, val skr: Int, val state: String, val signature: String?, val at: Long)

    /** What pressing Claim came to. */
    data class Claimed(val ok: Boolean, val skr: Int, val state: String, val refused: String?, val have: Int, val need: Int)

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
        /** Final and unpaid: what Claim would send. */
        val claimable: Int = 0,
        /** Reserved for a payout in flight. */
        val claiming: Int = 0,
        /** Already sent to the wallet. */
        val paid: Int = 0,
        /** Under this, Claim is not offered: a first payout costs real SOL to open the account. */
        val minPayout: Int = 100,
        /** False until the server has a payer configured; the button says "soon" instead of failing. */
        val payoutsEnabled: Boolean = false,
        val payouts: List<Payout> = emptyList(),
    )

    /**
     * Asks the server to send every claimable SKR to this wallet.
     *
     * The server decides everything — which rewards are final, how much, whether the payer is up —
     * and is safe to ask twice: the same claim returns the same payout, never a second one.
     */
    suspend fun claim(address: String): Claimed? {
        if (!enabled || address.isBlank()) return null
        val o = ServerClient.ask("/v1/rewards/claim", JSONObject(), timeoutMs = 20_000) ?: return null
        val p = o.optJSONObject("payout")
        if (o.optBoolean("ok")) Analytics.event("reward_claimed")
        return Claimed(
            ok = o.optBoolean("ok"), skr = p?.optInt("skr") ?: 0, state = p?.optString("state").orEmpty(),
            refused = o.text("refused"), have = o.optInt("have"), need = o.optInt("need"),
        )
    }

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
                    expiresAt = (it.optDouble("expires_at", 0.0) * 1000).toLong(),
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
            claimable = bal?.optInt("claimable") ?: 0, claiming = bal?.optInt("claiming") ?: 0,
            paid = bal?.optInt("paid") ?: 0, minPayout = o.optInt("min_payout", 100),
            payoutsEnabled = o.optBoolean("payouts_enabled", false),
            payouts = buildList {
                o.optJSONArray("payouts")?.let { a ->
                    for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                        add(Payout(it.optString("id"), it.optInt("skr"), it.optString("state"),
                            it.text("signature"), (it.optDouble("created_at") * 1000).toLong()))
                    }
                }
            },
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
    suspend fun reveal(address: String, id: Int): Boolean = revealWhy(address, id) == null

    /**
     * [reveal], but saying why not, because the scratch stage has to tell the two failures apart:
     * a card that ran out ("expired", the server's own word) is gone for good, and one the server
     * never heard about ([REVEAL_OFFLINE]) can simply be scratched again.
     *
     * Null when the server took it. Never null for a practice card (an id below one), which is
     * not sent at all, so the caller decides what a practice scratch looks like.
     */
    suspend fun revealWhy(address: String, id: Int): String? {
        if (!enabled || id <= 0) return REVEAL_OFFLINE
        val o = ServerClient.ask("/v1/rewards/reveal", JSONObject().apply {
            put("address", address)
            put("id", id)
        }) ?: return REVEAL_OFFLINE
        return if (o.optBoolean("ok")) null else o.text("refused") ?: "refused"
    }

    /** [revealWhy]'s answer when there was no answer: no server, no network, or a dead session. */
    const val REVEAL_OFFLINE = "offline"

    /** [revealWhy]'s answer, as the server spells it, for a card left unscratched too long. */
    const val REVEAL_EXPIRED = "expired"

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
        /**
         * When this person was TOLD about the offer (a push went out for it), in millis — **0 when
         * not yet**. The notification page lists an offer only once it has been told, so offers
         * arrive one at a time, hours apart, instead of all at once at sign-up. The Offers page
         * itself still lists everything: that is where someone goes to look.
         */
        val notifiedAt: Long = 0L,
        /**
         * What sort of task it is: pay, swap, shop, people, tap or stake. Null from an older
         * server, and null for a word this build does not know, so nothing downstream has to
         * guess what an unknown kind means.
         */
        val kind: String? = null,
        /**
         * The steps and the rules, in the server's own words and order. **Empty means "not sent"**,
         * and the sheet then writes the ones it always wrote. They are the server's because only
         * it knows what the task is: the phone's hardcoded "Install {app} from the dApp Store" was
         * wrong for an offer that is done inside AirPay.
         */
        val steps: List<String> = emptyList(),
        val rules: List<String> = emptyList(),
        /** What the sheet's button says and does once the offer is claimed. Null: "Open {app}". */
        val cta: Cta? = null,
        /** How far along a counted task is. Null when the offer is not counted. */
        val progress: Progress? = null,
        /** The reward as it should be advertised, "Flat 50 SKR" or "Up to 200 SKR". */
        val rewardText: String? = null,
        /** What the offer is, in a sentence or two, for somebody who has never done the task. */
        val about: String? = null,
        /** Where the task is done, as a short label: "In AirPay", "In the Jupiter app". */
        val where: String? = null,
        /**
         * The steps with their explanations. **When it is not empty it replaces [steps]**, which
         * are titles only. Empty means "not sent".
         */
        val guide: List<Step> = emptyList(),
        /** When and how the reward arrives, as one sentence. */
        val payout: String? = null,
        /** What somebody needs before starting: a balance, an app, a Seeker. */
        val needs: List<String> = emptyList(),
        /**
         * The least a scratch card pays. **Null means a flat reward**: the offer pays [reward] and
         * nothing else. Set, the card pays somewhere between this and [reward], so the sheet must
         * say a range and not promise the top figure.
         */
        val rewardMin: Int? = null,
    ) {
        val available get() = state == "available"
        val waiting get() = state == "open"

        /** Done inside AirPay rather than in somebody else's app. False when [kind] is unknown. */
        val inAirPay get() = kind in IN_AIRPAY
    }

    /**
     * The claimed offer's button. [target] is one of [CTA_TARGETS]: "link" opens the offer's own
     * `link` in the browser, and the other four are places inside AirPay.
     */
    data class Cta(val label: String, val target: String)

    /** One step of the guide: what to do, and under it how. [detail] is null when there is none. */
    data class Step(val title: String, val detail: String? = null)

    /** [done] of [need], with `need` at least 1 and `done` never outside 0..need. */
    data class Progress(val done: Int, val need: Int)

    private val KINDS = setOf("pay", "swap", "shop", "people", "tap", "stake")
    private val IN_AIRPAY = setOf("shop", "people", "tap")
    internal val CTA_TARGETS = setOf("recharge", "shop", "tap", "pay", "link")

    /** `steps` are the short, title-only list. The longer, explained one is `guide`. */
    private const val MAX_STEPS = 3
    private const val MAX_RULES = 6
    /** The guide is the sheet that scrolls, so it has room for the five steps the server may send. */
    private const val MAX_GUIDE = 6
    private const val MAX_NEEDS = 6

    /** A string that is really there: not missing, not JSON null, not a number, not blank. */
    private fun JSONObject.words(key: String): String? =
        (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The guide's steps, in order. A step needs a title: one without it has nothing to show beside
     * its number, so it drops itself and the rest renumber. A bare string is taken as a title with
     * no detail, because that is what an older `steps` array looks like and refusing it would
     * throw away words that are perfectly readable.
     */
    private fun JSONObject.guide(key: String): List<Step> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            when (val e = arr.opt(i)) {
                is JSONObject -> e.words("title")?.let { Step(it, e.words("detail")) }
                is String -> e.trim().takeIf { it.isNotEmpty() }?.let { Step(it) }
                else -> null
            }
        }.take(MAX_GUIDE)
    }

    /**
     * The strings in an array, in order, and nothing else. A missing key, a value that is not an
     * array, and elements that are null, blank or not text all come out as "not there", so one
     * bad element drops itself instead of printing `null` or `{}` as a step.
     */
    private fun JSONObject.strings(key: String, max: Int): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length())
            .mapNotNull { i -> (arr.opt(i) as? String)?.trim()?.takeIf { it.isNotEmpty() } }
            .take(max)
    }

    /**
     * One offer off the wire. Separate from [offers] so it can be tested without a server.
     *
     * Every field added after the first release is optional here, because an older server does
     * not send it: missing, JSON null and the wrong type all land on the same "not sent" value.
     */
    internal fun offerFrom(j: JSONObject): Offer {
        val token = j.optString("token", "SOL")
        // A button needs both halves. A label with a target this build cannot act on would be a
        // blue button that goes nowhere, so the whole thing falls back to "Open {app}".
        val cta = j.optJSONObject("cta")?.let { c ->
            val label = (c.opt("label") as? String)?.trim().orEmpty()
            val target = c.opt("target") as? String
            if (label.isNotEmpty() && target in CTA_TARGETS) Cta(label, target!!) else null
        }
        // Numbers only. `optInt` would read the text "2" as 2 and anything else as 0, and a bar
        // drawn from a guess is worse than no bar.
        val progress = j.optJSONObject("progress")?.let { p ->
            val done = (p.opt("done") as? Number)?.toInt()
            val need = (p.opt("need") as? Number)?.toInt()
            if (done != null && need != null && need > 0) Progress(done.coerceIn(0, need), need)
            else null
        }
        val reward = j.optInt("reward")
        // A range needs a real floor under the top figure. Zero, a negative, text, or a floor at
        // or above the reward is not a range, and "50 to 50 SKR" would be a flat reward dressed up
        // as a scratch card.
        val rewardMin = (j.opt("reward_min") as? Number)?.toInt()?.takeIf { it in 1 until reward }
        return Offer(
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
            notifiedAt = (j.optDouble("notified_at", 0.0) * 1000).toLong(),
            kind = (j.opt("kind") as? String)?.takeIf { it in KINDS },
            steps = j.strings("steps", MAX_STEPS),
            rules = j.strings("rules", MAX_RULES),
            cta = cta,
            progress = progress,
            rewardText = (j.opt("reward_text") as? String)?.trim()?.takeIf { it.isNotEmpty() },
            about = j.words("about"),
            where = j.words("where"),
            guide = j.guide("guide"),
            payout = j.words("payout"),
            needs = j.strings("needs", MAX_NEEDS),
            rewardMin = rewardMin,
        )
    }

    suspend fun offers(address: String): List<Offer>? {
        if (!enabled) return null
        val o = ServerClient.read("/v1/offers?address=$address") ?: return null
        val arr = o.optJSONArray("offers") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::offerFrom) }
    }

    /** Opens an offer. This is also the moment the attribution window starts, so a payment
     *  made before it does not count — the app says so rather than letting it look broken. */
    suspend fun claim(offerId: String, address: String): String? {
        val o = ServerClient.ask("/v1/offers/$offerId/claim", JSONObject().apply {
            put("address", address)
        }) ?: return "no_server"
        if (o.optBoolean("ok")) Analytics.event("offer_claimed")
        return if (o.optBoolean("ok")) null else o.text("refused") ?: "refused"
    }

    /** Why an offer can't be opened, or hasn't paid yet. */
    fun offerWhy(why: String?): String? = when (why) {
        null, "" -> null
        "not_a_seeker" -> "Only a Seeker can claim this"
        "already_claimed" -> "You have already claimed this"
        "not_live" -> "This offer has ended"
        "offer_budget" -> "This offer has run out"
        "before_claim" -> "That payment was before you claimed. Pay again to earn"
        "outside_window" -> "The window closed"
        "wrong_recipient" -> "Waiting for a payment to the app"
        "below_minimum" -> "That payment was under the minimum"
        "wrong_token" -> "Paid in the wrong token"
        "chain_unreachable" -> "Couldn't check the chain. Try again"
        // What [claim] answers when the server gave no answer, or an answer with no reason. They
        // fell through to the line below and were shown as "no server" and "refused".
        "no_server" -> "Couldn't reach the server. Try again"
        "refused" -> "That didn't go through. Try again"
        else -> why.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** What a reward was for, in words. */
    fun nameOf(kind: String): String = when (kind) {
        "first_payment" -> "First payment"
        "per_payment" -> "Paid in person"
        "streak" -> "Weekly streak"
        "offer" -> "Offer completed"
        "signup" -> "Welcome bonus"
        "recharge" -> "Phone recharge"
        "voucher" -> "Voucher"
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
