package com.tapsol.tap

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
) {
    fun toBytes(): ByteArray = JSONObject().apply {
        put("v", WIRE_VERSION)
        put("r", recipient)
        put("a", amount)
        put("t", token)
        put("l", label)
        put("f", reference)
        genesis?.let { put("g", it) }
    }.toString().toByteArray(Charsets.UTF_8)

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
        amount.isNotBlank() && amount.toDoubleOrNull() == null -> "The request has an unreadable amount."
        (amount.toDoubleOrNull() ?: 0.0) < 0 -> "The request asks for a negative amount."
        (amount.toDoubleOrNull() ?: 0.0) > MAX_AMOUNT -> "That amount looks wrong — refusing it."
        label.length > 64 -> "The request has an oversized label."
        else -> null
    }

    companion object {
        const val WIRE_VERSION = 2

        /** A sanity ceiling. A tap should never carry a life-changing sum by accident. */
        const val MAX_AMOUNT = 1_000.0

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
     * Proprietary AID: F0 + "SolanaTap". The F0 prefix is the range reserved for
     * non-registered applications, so it cannot collide with a real payment applet.
     */
    const val AID = "F0536F6C616E61546170"

    /** Reader → card: "connect me to the app that owns this AID". */
    fun select(aid: String = AID): ByteArray {
        val a = aid.hexToBytes()
        return byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, a.size.toByte()) + a + byteArrayOf(0x00)
    }

    val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
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
