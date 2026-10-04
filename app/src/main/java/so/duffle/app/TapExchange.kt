package so.duffle.app

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log

/**
 * The reader's side of one tap: get the other phone's payload, and introduce ourselves.
 *
 * Two commands, ~100ms. SELECT returns their request or their card; HELLO hands them our card so
 * their phone can show a popup about us too. HELLO is best-effort — if the phones have already
 * parted, the popup falls back to relaying the introduction through the server.
 */
object TapExchange {

    sealed interface Outcome {
        data class Got(val payload: TapPayload, val introduced: Boolean) : Outcome
        /** Their Tap answered but has nothing to offer: no wallet connected on that phone. */
        data object NothingToOffer : Outcome
        data class Failed(val why: String) : Outcome
    }

    fun run(tag: Tag, me: TapCard?, log: (String) -> Unit = {}): Outcome {
        log("tag tech ${tag.techList.joinToString { it.substringAfterLast('.') }}")
        val iso = IsoDep.get(tag) ?: return Outcome.Failed("that isn't a phone")
        return try {
            iso.connect()
            // Short on purpose: phones too far apart should fail fast so the user can try again.
            iso.timeout = 2000
            log("connected (maxTransceive=${iso.maxTransceiveLength}B)")

            val resp = iso.transceive(Apdu.select())
            Log.i(TAG, "← ${resp.toHex().take(64)}")
            if (!Apdu.isOk(resp)) {
                val nothing = resp.size >= 2 && resp[resp.size - 2] == 0x69.toByte() && resp[resp.size - 1] == 0x85.toByte()
                return if (nothing) Outcome.NothingToOffer else Outcome.Failed("that phone refused the tap (${resp.toHex()})")
            }
            val payload = TapPayload.parse(Apdu.body(resp))
                ?: return Outcome.Failed("that phone sent something we could not read")

            var introduced = false
            if (me != null && me.address != payload.address) {
                introduced = runCatching { Apdu.isOk(iso.transceive(Apdu.hello(me))) }.getOrDefault(false)
                log(if (introduced) "→ introduced ourselves" else "- couldn't introduce ourselves over NFC")
            }
            Outcome.Got(payload, introduced)
        } catch (e: Exception) {
            Outcome.Failed("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { iso.close() }
        }
    }

    sealed interface Delivery {
        /** Every piece was taken. Their phone has the payment; whether it lands is theirs to say. */
        data object Done : Delivery
        /** A phone answered, and it is not one holding this request out. Nothing was handed over. */
        data class NotThem(val why: String) : Delivery
        /** The radio let go part way, or their phone refused a piece. Worth another tap. */
        data class Failed(val why: String) : Delivery
    }

    /**
     * The second tap of a payment signed with no connection: hand [tx] to the phone that asked.
     *
     * Kept apart from [run] so reading a request is exactly what it was. SELECT comes first for
     * two reasons: the card only takes a hand-over after it, and its answer says which request
     * that phone is holding out, so a payment is never pushed at somebody else's phone.
     */
    fun deliver(tag: Tag, tx: ByteArray, reference: String, log: (String) -> Unit = {}): Delivery {
        val iso = IsoDep.get(tag) ?: return Delivery.NotThem("that isn't a phone")
        return try {
            iso.connect()
            iso.timeout = 2000
            val resp = iso.transceive(Apdu.select())
            if (!Apdu.isOk(resp)) return Delivery.NotThem("select refused (${resp.toHex()})")
            val theirs = (TapPayload.parse(Apdu.body(resp)) as? TapPayload.Request)?.request?.reference
            if (theirs != reference) return Delivery.NotThem("that phone is not showing this request")

            // Five bytes of header go out with every piece, and the piece must fit what this
            // phone's radio can send in one go.
            val chunk = minOf(OfflineTx.MAX_CHUNK, iso.maxTransceiveLength - 5)
            val apdus = OfflineTx.apdus(tx, chunk)
            if (apdus.isEmpty()) return Delivery.Failed("nothing sendable (${tx.size}B in pieces of $chunk)")
            log("handing over ${tx.size}B in ${apdus.size} piece(s) of up to ${chunk}B")
            for ((i, apdu) in apdus.withIndex()) {
                val r = iso.transceive(apdu)
                if (!Apdu.isOk(r)) return Delivery.Failed("piece $i refused (${r.toHex()})")
            }
            Delivery.Done
        } catch (e: Exception) {
            Delivery.Failed("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { iso.close() }
        }
    }

    private const val TAG = "TapExchange"
}
