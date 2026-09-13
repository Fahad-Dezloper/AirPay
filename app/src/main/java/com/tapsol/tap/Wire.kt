package com.tapsol.tap

import org.json.JSONObject
import java.security.SecureRandom

/**
 * What actually crosses the 4cm gap.
 *
 * The tap carries IDENTITY and INTENT, never money. The payment itself is an ordinary Solana
 * transaction over the network — which is why nobody can "intercept funds" by sniffing the
 * radio, and why the phrasing matters when explaining this.
 *
 * Kept deliberately small. A contactless exchange lasts only as long as two phones are held
 * together, and every extra byte is another chance for the user to pull away mid-transfer.
 */
data class PayRequest(
    /** Base58 Solana address that wants to be paid. */
    val recipient: String,
    /** Human-readable amount, e.g. "2.50". Empty means "payer decides". */
    val amount: String,
    /** SPL mint, or "SOL". */
    val token: String,
    /** Display name of whoever is asking. */
    val label: String,
    /**
     * One-time value. The payer must echo it back, so a recording of one tap cannot be
     * replayed as a second payment.
     */
    val nonce: String,
    /** Genesis Token mint if the requester is on a verified Seeker, else null. */
    val genesis: String? = null,
) {
    fun toBytes(): ByteArray = JSONObject().apply {
        put("v", WIRE_VERSION)
        put("r", recipient)
        put("a", amount)
        put("t", token)
        put("l", label)
        put("n", nonce)
        genesis?.let { put("g", it) }
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val WIRE_VERSION = 1

        fun fromBytes(b: ByteArray): PayRequest? = try {
            val o = JSONObject(String(b, Charsets.UTF_8))
            // Refuse anything from a newer wire version rather than guessing at its meaning.
            if (o.optInt("v") != WIRE_VERSION) null
            else PayRequest(
                recipient = o.getString("r"),
                amount = o.optString("a"),
                token = o.optString("t", "SOL"),
                label = o.optString("l"),
                nonce = o.getString("n"),
                genesis = if (o.has("g")) o.getString("g") else null,
            )
        } catch (e: Exception) {
            null
        }

        fun newNonce(): String {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            return b.toHex()
        }
    }
}

/** The payer's reply: who is paying, echoing the nonce so the request can be matched. */
data class PayAck(
    val payer: String,
    val nonce: String,
    val genesis: String? = null,
) {
    fun toBytes(): ByteArray = JSONObject().apply {
        put("v", PayRequest.WIRE_VERSION)
        put("p", payer)
        put("n", nonce)
        genesis?.let { put("g", it) }
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(b: ByteArray): PayAck? = try {
            val o = JSONObject(String(b, Charsets.UTF_8))
            if (o.optInt("v") != PayRequest.WIRE_VERSION) null
            else PayAck(o.getString("p"), o.getString("n"),
                if (o.has("g")) o.getString("g") else null)
        } catch (e: Exception) {
            null
        }
    }
}

// ---- APDU plumbing ----------------------------------------------------------

/**
 * ISO 7816-4 is the only language an NFC reader and a card-emulating phone share, so the
 * exchange is shaped like smartcard commands even though both ends are phones.
 */
object Apdu {

    /**
     * Proprietary AID: F0 + "SolanaTap". The F0 prefix is the range reserved for
     * non-registered applications, so this cannot collide with a real payment applet.
     */
    const val AID = "F0536F6C616E61546170"

    /** Reader → card: "connect me to the app that owns this AID". */
    fun select(aid: String = AID): ByteArray {
        val a = aid.hexToBytes()
        return byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, a.size.toByte()) + a + byteArrayOf(0x00)
    }

    /** Reader → card: here is who I am, and the nonce echoed back. */
    fun sendAck(payload: ByteArray): ByteArray =
        byteArrayOf(CLA_TAP, INS_ACK, 0x00, 0x00, payload.size.toByte()) + payload + byteArrayOf(0x00)

    const val CLA_TAP = 0x80.toByte()
    const val INS_ACK = 0x10.toByte()

    val SW_OK = byteArrayOf(0x90.toByte(), 0x00)
    val SW_UNKNOWN_CMD = byteArrayOf(0x6D, 0x00)
    val SW_NOT_READY = byteArrayOf(0x69, 0x85.toByte())

    fun isOk(r: ByteArray) = r.size >= 2 &&
        r[r.size - 2] == 0x90.toByte() && r[r.size - 1] == 0x00.toByte()

    /** Strip the two-byte status word to get the actual data. */
    fun body(r: ByteArray): ByteArray =
        if (r.size <= 2) ByteArray(0) else r.copyOfRange(0, r.size - 2)

    fun isSelect(cmd: ByteArray): Boolean =
        cmd.size >= 2 && cmd[0] == 0x00.toByte() && cmd[1] == 0xA4.toByte()

    fun isAck(cmd: ByteArray): Boolean =
        cmd.size >= 2 && cmd[0] == CLA_TAP && cmd[1] == INS_ACK

    /** Data field of a case-3/4 APDU: skip CLA INS P1 P2 Lc. */
    fun dataOf(cmd: ByteArray): ByteArray {
        if (cmd.size < 5) return ByteArray(0)
        val lc = cmd[4].toInt() and 0xFF
        if (lc == 0 || cmd.size < 5 + lc) return ByteArray(0)
        return cmd.copyOfRange(5, 5 + lc)
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
