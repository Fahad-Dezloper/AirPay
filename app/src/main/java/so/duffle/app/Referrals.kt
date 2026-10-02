package so.duffle.app

import org.json.JSONObject

/**
 * Bringing somebody to AirPay, and what that is worth.
 *
 * The rules are the server's, like every other reward — see [Rewards]. The one worth knowing
 * here is when the money moves: **the inviter is paid on the invitee's first rewarded payment,
 * not when they join.** A wallet is free, so "somebody signed up with my code" costs an
 * attacker nothing and is worth exactly nothing until a real payment the rules already agreed
 * to reward has happened.
 *
 * That is also why this screen never says "assured". It says what the rule pays and what has
 * to happen first.
 */
object Referrals {

    /** Off unless rewards are on: a referral is a reward, and pays out of the same pool. */
    val enabled get() = Rewards.enabled

    data class Person(val address: String, val at: Long, val paid: Boolean) {
        val short get() = "${address.take(4)}…${address.takeLast(4)}"
    }

    /**
     * @param code this wallet's own code — stable, and the same on every phone it connects.
     * @param reward what the inviter earns, once.
     * @param theirReward what the person being invited earns on their first tap. The half
     *   worth putting in the message: it is their reason to accept, not ours.
     * @param invited how many claimed the code; [paid] how many went on to earn. Kept apart
     *   because "invited 6, 2 have paid" is the honest version of a referral count.
     */
    data class Summary(
        val code: String, val reward: Int, val theirReward: Int,
        val invited: Int, val paid: Int, val earned: Int,
        val people: List<Person>,
    )

    suspend fun summary(address: String): Summary? {
        if (!enabled || address.isBlank()) return null
        val o = ServerClient.read("/v1/referrals/$address") ?: return null
        val code = o.text("code") ?: return null
        val people = mutableListOf<Person>()
        o.optJSONArray("people")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                people += Person(it.optString("invitee"), (it.optDouble("at") * 1000).toLong(),
                    it.optBoolean("paid"))
            }
        }
        return Summary(
            code = code,
            reward = o.optInt("reward"), theirReward = o.optInt("their_reward"),
            invited = o.optInt("invited"), paid = o.optInt("paid"), earned = o.optInt("earned"),
            people = people,
        )
    }

    /** Records that this wallet was invited by whoever owns [code]. Null when it worked. */
    suspend fun claim(code: String, address: String): String? {
        val o = ServerClient.ask("/v1/referrals/claim", JSONObject().apply {
            put("code", code.trim().uppercase())
            put("address", address)
        }) ?: return "no_server"
        return if (o.optBoolean("ok")) null else o.text("refused") ?: "refused"
    }

    /** Every refusal the server can give for a claim, said plainly. */
    fun why(refused: String?): String? = when (refused) {
        null, "" -> null
        "unknown_code" -> "No one has that code"
        "self_referral" -> "That's your own code"
        "already_referred" -> "You've already used a code"
        "referral_too_late" -> "You've already earned, a code only counts before your first payment"
        "no_server" -> "Couldn't reach the server"
        else -> refused.replace('_', ' ')
    }

    /**
     * The message that goes out. No link: duffle.so does not resolve, and a share whose one
     * link is dead is worse than one with none — so the code is the thing to carry, and it is
     * spelled out rather than buried at the end of a sentence.
     *
     * **[me] is very often empty.** `Identity.label` returns "" for anybody who has never set a
     * name, which is the default, and the old invite text interpolated it regardless — so the
     * message this app has been sending reads " is paying people by tapping phones", with a
     * leading space where the name should be. Naming yourself is optional here, so the sentence
     * has to work without one rather than assume one.
     */
    fun message(me: String, code: String, theirReward: Int): String {
        val opener = me.trim().takeIf { it.isNotBlank() }
            ?.let { "$it is paying people by tapping phones with AirPay" }
            ?: "Pay anyone by tapping phones with AirPay"
        return "$opener, no address to type.\n\n" +
            "Use my code $code when you join and your first tap earns you $theirReward SKR."
    }
}
