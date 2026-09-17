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
                log(if (introduced) "→ introduced ourselves" else "— couldn't introduce ourselves over NFC")
            }
            Outcome.Got(payload, introduced)
        } catch (e: Exception) {
            Outcome.Failed("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { iso.close() }
        }
    }

    private const val TAG = "TapExchange"
}
