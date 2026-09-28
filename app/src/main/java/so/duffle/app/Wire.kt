package so.duffle.app

import org.json.JSONObject
import java.security.SecureRandom

/**
 * What crosses the 4cm gap — and it only ever travels ONE way.
 *
 * NFC has no connection that survives separation; the field dies the moment the phones part.
 * So nothing important may depend on the phones staying together. The payee serves a request,
 * the payer reads it in ~100ms, and they separate immediately. Confirmation, biometrics and
 * signing all happen afterwards, at whatever pace the payer likes.
 *
 * The payee then learns about the payment from the CHAIN, not from the radio, by polling for
 * the `reference` key. That is the Solana Pay mechanism: a 32-byte value included as a
 * read-only, non-signer account on the transfer instruction. Validators index transactions by
 * account key, so `getSignaturesForAddress(reference)` finds the payment without knowing its
 * signature in advance.
 *
 * Using the standard rather than inventing one also means any Solana Pay wallet can pay a
 * request we emit, not just this app.
 */
data class PayRequest(
    /** Base58 Solana address to be paid. */
    val recipient: String,
    /** Human-readable amount, e.g. "2.50". Empty means "payer decides". */
    val amount: String,
    /** SPL mint address, or "SOL". */
    val token: String,
    /** Display name of whoever is asking. */
    val label: String,
    /**
     * Solana Pay `reference`: a unique 32-byte key, base58 encoded, attached to the transfer
     * as a read-only non-signer account. Doubles as replay protection — it is single-use and
     * verified on-chain rather than trusted over the radio.
     */
    val reference: String,
    /** Genesis Token mint if the requester is on a verified Seeker, else null. */
    val genesis: String? = null,
    /**
     * The token's decimals — 9 for SOL. Carried so both phones agree on what "0.5" means, but
     * never trusted for a payment: the payer reads the mint from the chain before paying, and
     * `transferChecked` makes the chain itself refuse a transfer built on the wrong decimals.
     */
    val decimals: Int = 9,
    /** What to call the token on screen, e.g. "USDC". Display only. */
    val symbol: String = if (token == "SOL") "SOL" else token.take(4),
    /** The asking phone's maker, as a [Brand] key. Display only. */
    val brand: String? = null,
    /**
     * The wallet vouching for [recipient], when the money is going to a linked wallet rather
     * than to the one that holds the name. See [Vouch].
     */
    val owner: String? = null,
    /** Days since the epoch. Signed over, so it cannot be stretched after the fact. */
    val linkExpiry: Long = 0,
    /** base64, unpadded, 64 bytes of ed25519. */
    val linkSignature: String? = null,
) {
    val isSol get() = token == "SOL"
    /** The SPL mint, or null for SOL. */
    val mint: String? get() = if (isSol) null else token

    /** The amount in the token's smallest unit — lamports for SOL. See [lamports]. */
    fun units(): Long? = baseUnits(amount, decimals)
    /**
     * The amount in lamports, exactly — or null when it is blank or unreadable.
     *
     * Never through a Double. `0.00013 * 1e9` is 129999.99999999999, which truncates to one
     * lamport short — and 1,710 of the 99,999 five-decimal amounts below 1 SOL do the same. The
     * payer would send a lamport less than asked and a verifier doing the arithmetic properly
     * would call it underpaid. The server uses Python's Decimal, so both agree to the lamport.
     */
    fun lamports(): Long? = baseUnits(amount, 9)

    fun toBytes(): ByteArray = JSONObject().apply {
        put("v", WIRE_VERSION)
        put("r", recipient)
        put("a", amount)
        put("t", token)
        put("l", label)
        put("f", reference)
        genesis?.let { put("g", it) }
        if (!isSol) {
            put("d", decimals)
            put("s", symbol)
        }
        brand?.let { put("b", it) }
        if (owner != null && linkSignature != null) {
            put("o", owner); put("x", linkExpiry); put("z", linkSignature)
        }
    }.toString().toByteArray(Charsets.UTF_8)

    /**
     * The link, but only when it verifies against [recipient]. Null otherwise.
     *
     * The single route from a payload to a name with a tick on the confirm sheet. Note it
     * checks the link against the address **being paid**, not against anything else in the
     * payload: the question the payer needs answered is who owns the destination.
     */
    fun link(): Vouch.Link? {
        val o = owner ?: return null
        val sig = linkSignature ?: return null
        return Vouch.check(o, recipient, linkExpiry, sig)
    }

    /**
     * The same request as a `solana:` URL. Emitting the standard form means a plain Solana Pay
     * wallet can settle this without our app on the paying side.
     */
    fun toSolanaPayUrl(): String = buildString {
        append("solana:").append(recipient)
        append("?amount=").append(amount)
        if (token != "SOL") append("&spl-token=").append(token)
        append("&reference=").append(reference)
        append("&label=").append(java.net.URLEncoder.encode(label, "UTF-8"))
    }

    /**
     * Everything arriving over the radio is untrusted. `PublicKey(...)` throws on malformed
     * base58, so an unvalidated payload from a hostile or buggy peer would crash the payer's
     * app at the worst possible moment — mid-tap, in front of someone.
     */
    fun validate(): String? = when {
        !isBase58Address(recipient) -> "The request has an invalid destination address."
        !isBase58Address(reference) -> "The request has an invalid reference."
        genesis != null && !isBase58Address(genesis) -> "The request has an invalid Genesis token."
        !isSol && !isBase58Address(token) -> "The request names an invalid token."
        decimals !in 0..MAX_DECIMALS -> "The request has impossible token decimals."
        symbol.isBlank() || symbol.length > 12 -> "The request has an unreadable token name."
        amount.isNotBlank() && amount.toDoubleOrNull() == null -> "The request has an unreadable amount."
        (amount.toDoubleOrNull() ?: 0.0) < 0 -> "The request asks for a negative amount."
        (amount.toDoubleOrNull() ?: 0.0) > MAX_AMOUNT -> "That amount looks wrong — refusing it."
        // Readable and in range but not expressible in lamports, e.g. "0.0000000001" or "0".
        amount.isNotBlank() && units() == null -> "That amount can't be paid in $symbol."
        label.length > 64 -> "The request has an oversized label."
        Username.looksLikeDomain(label) -> "The request has a name shaped like a domain."
        owner != null && !isBase58Address(owner) -> "The request has an invalid owner."
        (owner == null) != (linkSignature == null) -> "The request carries half a wallet link."
        else -> null
    }

    companion object {
        // 4: requests no longer expire. v3 carried a blockhash so both phones shared a
        // deadline; v4 drops it, because the payer fetches a fresh one only when they press Pay
        // and a request is otherwise good forever. A v3 phone would still be running timers
        // against a hash this side never sends, so it is refused rather than half-understood.
        const val WIRE_VERSION = 4

        /** A sanity ceiling. A tap should never carry a life-changing sum by accident. */
        const val MAX_AMOUNT = 1_000.0

        /** Solana mints allow up to 255 decimals in principle; nothing real uses more than 9. */
        const val MAX_DECIMALS = 12

        /**
         * An amount in a token's smallest unit, exactly — or null when it is blank, unreadable,
         * zero, or finer than the token can express.
         *
         * Never through a Double. `0.00013 * 1e9` is 129999.99999999999, which truncates to one
         * lamport short — and 1,710 of the 99,999 five-decimal amounts below 1 SOL do the same.
         * The server uses Python's Decimal, so both agree to the unit.
         */
        fun baseUnits(amount: String, decimals: Int): Long? = try {
            java.math.BigDecimal(amount.trim())
                .movePointRight(decimals)
                .setScale(0, java.math.RoundingMode.UNNECESSARY)
                .longValueExact()
                .takeIf { it > 0 }
        } catch (e: Exception) {
            null
        }

        private val B58 = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

        /** Shape check only — cheap, and enough to stop PublicKey() from throwing. */
        fun isBase58Address(s: String?): Boolean = s != null && B58.matches(s)

        fun fromBytes(b: ByteArray): PayRequest? = try {
            val o = JSONObject(String(b, Charsets.UTF_8))
            // Refuse a newer wire version rather than guessing at its meaning.
            if (o.optInt("v") != WIRE_VERSION) null
            else PayRequest(
                recipient = o.getString("r"),
                amount = o.optString("a"),
                token = o.optString("t", "SOL"),
                label = o.optString("l"),
                reference = o.getString("f"),
                genesis = if (o.has("g")) o.getString("g") else null,
                decimals = o.optInt("d", 9),
                symbol = o.optString("s").ifBlank { if (o.optString("t", "SOL") == "SOL") "SOL" else o.optString("t").take(4) },
                // An unknown brand is dropped, not refused: it only decides a picture.
                brand = o.optString("b").takeIf { Brand.isKnown(it) },
                owner = if (o.has("o")) o.getString("o") else null,
                linkExpiry = o.optLong("x"),
                linkSignature = if (o.has("z")) o.getString("z") else null,
            )
        } catch (e: Exception) {
            null
        }

        /**
         * A fresh reference per request. Base58 so it is a valid account key on the wire —
         * it never needs a private key, only uniqueness.
         */
        fun newReference(): String {
            val b = ByteArray(32)
            SecureRandom().nextBytes(b)
            return Base58.encode(b)
        }
    }
}

/**
 * Who a phone is, handed over on a tap when it is not asking for money.
 *
 * With both phones idle — nobody has pressed Request — a tap is an introduction, not a bill. Each
 * phone learns the other's address and name, and the person then decides in the popup whether
 * to pay or request. Deliberately small: an address, a display name, and the Genesis mint that
 * earns the "Verified Seeker" badge. Nothing that could move money.
 */
data class TapCard(
    val address: String,
    val label: String,
    val genesis: String? = null,
    /** This phone's maker, as a [Brand] key, so the other side can show its mark. */
    val brand: String? = null,
    /**
     * The wallet that vouches for [address], when this is not the wallet carrying the name.
     *
     * With the three link fields present the other phone can work out who this is without
     * believing anything we say: it rebuilds the signed statement, checks the signature against
     * `owner`, and resolves `owner`'s domain from the chain. See [Vouch].
     */
    val owner: String? = null,
    /** Days since the epoch. Inside the signature, so it cannot be stretched afterwards. */
    val linkExpiry: Long = 0,
    /** base64, unpadded, 64 bytes of ed25519. */
    val linkSignature: String? = null,
) {
    fun toBytes(): ByteArray = JSONObject().apply {
        put("v", PayRequest.WIRE_VERSION)
        put("k", KIND)
        put("r", address)
        put("l", label)
        genesis?.let { put("g", it) }
        brand?.let { put("b", it) }
        // All three or none: a partial link is not checkable and must not travel.
        if (owner != null && linkSignature != null) {
            put("o", owner); put("x", linkExpiry); put("z", linkSignature)
        }
    }.toString().toByteArray(Charsets.UTF_8)

    /**
     * The link, but only if it actually verifies. Null whenever it does not.
     *
     * The only route from a payload to a tick, so there is no way to show one on an unchecked
     * claim: the fields on this object are just numbers until this says otherwise.
     */
    fun link(): Vouch.Link? {
        val o = owner ?: return null
        val sig = linkSignature ?: return null
        return Vouch.check(o, address, linkExpiry, sig)
    }

    /** Untrusted like everything else that arrives by radio. */
    fun validate(): String? = when {
        !PayRequest.isBase58Address(address) -> "That phone sent an invalid address."
        genesis != null && !PayRequest.isBase58Address(genesis) -> "That phone sent an invalid Genesis token."
        label.length > 64 -> "That phone sent an oversized name."
        // A username may not wear a domain's clothes. Names with a dot are resolved from the
        // chain and never travel by radio, so one arriving here is a phone claiming a name it
        // did not prove — which is exactly what the tick is supposed to rule out.
        Username.looksLikeDomain(label) -> "That phone sent a name shaped like a domain."
        owner != null && !PayRequest.isBase58Address(owner) -> "That phone sent an invalid owner."
        // Not checked here: whether the signature is GOOD. A bad link is not a bad card — the
        // tap is still a perfectly good introduction, it just does not carry a name. Refusing
        // the whole payload over it would turn an unverifiable claim into a failed tap.
        (owner == null) != (linkSignature == null) -> "That phone sent half a wallet link."
        else -> null
    }

    companion object {
        const val KIND = "card"

        fun fromBytes(b: ByteArray): TapCard? = try {
            val o = JSONObject(String(b, Charsets.UTF_8))
            if (o.optInt("v") != PayRequest.WIRE_VERSION || o.optString("k") != KIND) null
            else TapCard(
                address = o.getString("r"),
                label = o.optString("l"),
                genesis = if (o.has("g")) o.getString("g") else null,
                brand = o.optString("b").takeIf { Brand.isKnown(it) },
                owner = if (o.has("o")) o.getString("o") else null,
                linkExpiry = o.optLong("x"),
                linkSignature = if (o.has("z")) o.getString("z") else null,
            ).takeIf { it.validate() == null }
        } catch (e: Exception) {
            null
        }
    }
}

/** What a tap can carry: a bill, or an introduction. */
sealed interface TapPayload {
    data class Request(val request: PayRequest) : TapPayload
    data class Card(val card: TapCard) : TapPayload

    val address: String get() = when (this) {
        is Request -> request.recipient
        is Card -> card.address
    }
    val label: String get() = when (this) {
        is Request -> request.label
        is Card -> card.label
    }
    val genesis: String? get() = when (this) {
        is Request -> request.genesis
        is Card -> card.genesis
    }
    val brand: String? get() = when (this) {
        is Request -> request.brand
        is Card -> card.brand
    }

    fun toBytes(): ByteArray = when (this) {
        is Request -> request.toBytes()
        is Card -> card.toBytes()
    }

    companion object {
        /** Null for anything unreadable or invalid — the caller never sees half a payload. */
        fun parse(b: ByteArray): TapPayload? {
            TapCard.fromBytes(b)?.let { return Card(it) }
            val req = PayRequest.fromBytes(b) ?: return null
            return if (req.validate() == null) Request(req) else null
        }
    }
}

/** Minimal base58 encoder — the alphabet Solana uses for account keys. */
object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""
        val bytes = input.copyOf()
        val out = StringBuilder()
        var zeros = 0
        while (zeros < bytes.size && bytes[zeros].toInt() == 0) zeros++
        var start = zeros
        while (start < bytes.size) {
            var carry = 0
            for (i in start until bytes.size) {
                val v = (bytes[i].toInt() and 0xFF) + carry * 256
                bytes[i] = (v / 58).toByte()
                carry = v % 58
            }
            out.append(ALPHABET[carry])
            if (bytes[start].toInt() == 0) start++
        }
        repeat(zeros) { out.append(ALPHABET[0]) }
        return out.reverse().toString()
    }
}

// ---- APDU plumbing ----------------------------------------------------------

/**
 * ISO 7816-4 is the only language a reader and a card-emulating phone share, so the exchange
 * is shaped like smartcard commands even though both ends are phones.
 *
 * One command is enough now. SELECT returns the request and the exchange is over — there is
 * no reply leg, because the reply travels over the chain.
 */
object Apdu {

    /**
     * Proprietary AID: F0 + "Duffle" in ASCII. The F0 prefix is the range reserved for
     * non-registered applications, so it cannot collide with a real payment applet.
     *
     * **The name is AirPay now; these bytes still spell the old one, deliberately.** Both
     * phones in a tap must route on the SAME AID, so changing it is a coordinated reinstall,
     * not an edit — and it buys nothing anybody can see. Left alone until after submission;
     * see pending.txt. Do not "correct" this comment to match the app name.
     */
    const val AID = "F0447566666C65"

    /** Reader → card: "connect me to the app that owns this AID". */
    fun select(aid: String = AID): ByteArray {
        val a = aid.hexToBytes()
        return byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, a.size.toByte()) + a + byteArrayOf(0x00)
    }

    /**
     * Reader → card, after SELECT: "and this is who I am". Proprietary class 0x80, so no real
     * smartcard applet will ever mistake it for one of its own commands.
     *
     * It exists because a tap is otherwise one-way — the reader learns about the card, and the
     * card learns nothing. With both phones idle, both people need a popup about the other.
     */
    fun hello(card: TapCard): ByteArray {
        val data = card.toBytes()
        return if (data.size <= 255) {
            byteArrayOf(0x80.toByte(), INS_HELLO, 0x00, 0x00, data.size.toByte()) + data
        } else {
            // Extended length: Lc as 00 hi lo. A card with a long name can exceed 255 bytes.
            byteArrayOf(0x80.toByte(), INS_HELLO, 0x00, 0x00, 0x00,
                (data.size shr 8).toByte(), data.size.toByte()) + data
        }
    }

    const val INS_HELLO: Byte = 0x10

    fun isHello(cmd: ByteArray) = cmd.size >= 5 && cmd[0] == 0x80.toByte() && cmd[1] == INS_HELLO

    /** The data field of a short or extended-length APDU, or null if the lengths do not add up. */
    fun data(cmd: ByteArray): ByteArray? {
        if (cmd.size < 5) return null
        val short = cmd[4].toInt() and 0xFF
        if (short != 0) return if (cmd.size >= 5 + short) cmd.copyOfRange(5, 5 + short) else null
        if (cmd.size < 7) return null
        val n = ((cmd[5].toInt() and 0xFF) shl 8) or (cmd[6].toInt() and 0xFF)
        return if (n > 0 && cmd.size >= 7 + n) cmd.copyOfRange(7, 7 + n) else null
    }

    val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    val SW_FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
    val SW_WRONG_DATA = byteArrayOf(0x6A, 0x80.toByte())
    val SW_WRONG_OFFSET = byteArrayOf(0x6B, 0x00)
    val SW_UNKNOWN_CMD = byteArrayOf(0x6D, 0x00)
    val SW_NOT_READY = byteArrayOf(0x69, 0x85.toByte())

    fun isOk(r: ByteArray) = r.size >= 2 &&
        r[r.size - 2] == 0x90.toByte() && r[r.size - 1] == 0x00.toByte()

    /** Strip the two-byte status word to get the payload. */
    fun body(r: ByteArray): ByteArray =
        if (r.size <= 2) ByteArray(0) else r.copyOfRange(0, r.size - 2)

    fun isSelect(cmd: ByteArray): Boolean =
        cmd.size >= 2 && cmd[0] == 0x00.toByte() && cmd[1] == 0xA4.toByte()
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
