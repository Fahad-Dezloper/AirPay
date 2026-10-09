package so.duffle.app

import android.content.Context
import org.json.JSONObject
import org.sol4k.Base58
import org.sol4k.PublicKey
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * One wallet vouching for another, so an identity survives changing wallets.
 *
 * The problem: your name lives on **one** address. A `.skr` belongs to the wallet that holds it,
 * so the moment you receive money into a second wallet you are a stranger again — the payer
 * resolves that address, finds nothing, and sees hex. Buying a domain per wallet is absurd, and
 * simply *claiming* the same name from every wallet would make the tick worthless.
 *
 * The fix is a signature. The named wallet signs a short statement naming the other one:
 *
 * ```
 * AirPay wallet link v1
 * owner=4WZ9hkr9…            ← holds dezloper.skr
 * wallet=8Mj4…               ← the wallet being vouched for
 * expires=2027-03-28
 * ```
 *
 * The payer's phone can then check the whole chain **by itself**, with nothing trusted from the
 * radio except numbers it verifies:
 *
 *  1. rebuild these exact bytes from the owner, wallet and expiry in the payload,
 *  2. verify the ed25519 signature against the *owner's* public key — only the owner's private
 *     key can produce it, and it is held in the Seeker's secure element,
 *  3. resolve the owner's `.skr` from the chain ([Names]) rather than believing a sent name,
 *  4. check the date.
 *
 * **The wallet address is inside the signed bytes**, which is what makes this safe. A statement
 * that merely said "I own dezloper.skr" could be copied by anyone who ever saw it and replayed
 * beside their own address. This one names exactly one wallet and verifies for no other.
 *
 * ### What it proves, and what it does not
 *
 * It proves *this address is controlled by whoever owns that name*. It does not prove who that
 * person is — nobody vetted the buyer of a domain — and it does not stop an owner vouching for
 * an address belonging to somebody else. That last one is a real risk and an unavoidable one:
 * it is delegation, and the thing being staked is the owner's own name.
 *
 * ### Why it expires
 *
 * A signature is forever and wallets are not. A key gets compromised, a wallet gets handed on,
 * and a permanent vouch would keep asserting a link that stopped being true. The date caps how
 * long a stale link can lie; re-signing is one approval.
 */
object Vouch {

    /** How long a fresh link is good for. Long enough to be rare, short enough to matter. */
    const val VALID_DAYS = 180L

    private const val PREFS = "vouch"
    private const val HEADER = "AirPay wallet link v1"

    /**
     * A proven link between two wallets.
     *
     * Only ever built by [check] or by [remember] after a real signature came back, so holding
     * one means the signature verified — there is no way to construct an unverified Link and
     * have the UI mistake it for a real one.
     */
    data class Link(
        /** The wallet that signed — the one with the name on it. */
        val owner: String,
        /** The wallet being vouched for. */
        val wallet: String,
        /** Days since the epoch, so the whole thing stays small enough for one tap. */
        val expiresDay: Long,
        /** base64, no padding. 64 raw bytes of ed25519. */
        val signature: String,
    ) {
        val expiresOn: LocalDate get() = LocalDate.ofEpochDay(expiresDay)
        fun expired(today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Boolean = today > expiresOn
    }

    /**
     * Exactly the bytes that get signed, and exactly the bytes that get verified.
     *
     * Built in one place because the two sides have to agree byte for byte. A stray space here
     * is a link that signs cleanly on one phone and fails on every other, which would look like
     * a network problem and never be found.
     *
     * Kept human-readable because the wallet shows it to the person before they approve: it has
     * to be obvious that this is a statement about wallets and not a transaction.
     */
    fun message(owner: String, wallet: String, expiresDay: Long): ByteArray =
        buildString {
            append(HEADER).append('\n')
            append("owner=").append(owner).append('\n')
            append("wallet=").append(wallet).append('\n')
            append("expires=").append(LocalDate.ofEpochDay(expiresDay))
        }.toByteArray(Charsets.UTF_8)

    /** The expiry a link signed today should carry. */
    fun expiryFor(today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Long =
        today.plusDays(VALID_DAYS).toEpochDay()

    /**
     * Does this signature really say that `owner` vouches for `wallet`?
     *
     * Returns the link only when the signature verifies and the date has not passed. Every
     * failure — malformed base58, wrong signature length, a key that is not on the curve, an
     * expired date — comes back null. **Never throws**: this runs on a payload that arrived
     * over a radio from a phone we do not control, and a crash mid-tap is the one thing the
     * whole validation layer exists to prevent.
     */
    fun check(owner: String, wallet: String, expiresDay: Long, signature: String): Link? = try {
        val raw = java.util.Base64.getDecoder().decode(signature)
        when {
            raw.size != 64 -> null
            owner == wallet -> null                 // a wallet vouching for itself proves nothing
            !PayRequest.isBase58Address(owner) -> null
            !PayRequest.isBase58Address(wallet) -> null
            // The argument order here is pinned by VouchTest against a real sol4k signature.
            // Getting it backwards would make every check fail, or — far worse if the library
            // ever became lenient — succeed on anything.
            !PublicKey(owner).verify(raw, message(owner, wallet, expiresDay)) -> null
            else -> Link(owner, wallet, expiresDay, signature).takeIf { !it.expired() }
        }
    } catch (e: Exception) {
        null
    }

    // -- storage -------------------------------------------------------------------------------

    /**
     * Keeps a link that has just been signed.
     *
     * Verified again before it is written, even though it was made on this phone a moment ago.
     * It costs nothing and it means the only way a bad link reaches disk is a bug in [check]
     * itself, rather than any of the paths that call it.
     */
    fun remember(context: Context, owner: String, wallet: String, expiresDay: Long, signature: String): Link? {
        val link = check(owner, wallet, expiresDay, signature) ?: return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(wallet, JSONObject().apply {
                put("o", owner); put("x", expiresDay); put("s", signature)
            }.toString())
            .apply()
        return link
    }

    /**
     * The link stored for a wallet, re-verified on the way out.
     *
     * Read back through [check] rather than trusted because it was written by us: prefs are a
     * file, files can be edited on a rooted phone, and a link is the thing that puts a tick on
     * a screen. Re-checking makes tampering pointless.
     */
    fun of(context: Context, wallet: String): Link? = try {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(wallet, null)
            ?: return null
        val o = JSONObject(raw)
        check(o.getString("o"), wallet, o.getLong("x"), o.getString("s"))
    } catch (e: Exception) {
        null
    }

    fun forget(context: Context, wallet: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(wallet).apply()
    }

    /** base64 without padding, which is how a signature travels in a tap. */
    fun encode(signature: ByteArray): String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(signature)

    /** For the rare case a wallet hands back a base58 signature instead of raw bytes. */
    fun fromBase58(signature: String): String? = try {
        Base58.decode(signature).takeIf { it.size == 64 }?.let { encode(it) }
    } catch (e: Exception) {
        null
    }
}
