package so.duffle.app

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle

/**
 * The "reader" half of a tap. This phone is paying, so it drives the exchange.
 *
 * One SELECT, one response, done — roughly 100ms. Nothing is sent back over NFC, so the
 * connection is allowed to die the moment the phones separate. Confirmation and signing happen
 * afterwards, with the phones apart, and the payee finds out from the chain.
 *
 * Reader mode is used rather than foreground dispatch because it lets us disable the platform's
 * NDEF handling and its confirmation sound — both of which fight the pairing animation and add
 * latency to a gesture that has to feel instant.
 */
class TapReader(
    private val activity: Activity,
    private val onLog: (String) -> Unit,
    private val onRequest: (PayRequest) -> Unit,
    /** The other phone had no request, only its card: an introduction. */
    private val onCard: (TapCard) -> Unit = {},
) : NfcAdapter.ReaderCallback {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    val available: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true

    fun start() {
        // NFC-A only. Android's card emulation is required to answer on NFC-A, so a phone
        // running Tap is always found there; polling B as well only lengthens each poll cycle,
        // which is felt as having to press the phones together and hold still before anything
        // happens. A shorter cycle does not add distance — that is fixed by the hardware — but
        // it catches the phones the moment they come within range.
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            // The platform's own NDEF handling would grab the tag before we see it.
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val extras = Bundle().apply {
            // Default presence-check interval is aggressive enough to drop a slow exchange.
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500)
        }
        adapter?.enableReaderMode(activity, this, flags, extras)
        onLog("reader armed")
    }

    fun stop() {
        adapter?.disableReaderMode(activity)
        onLog("reader off")
    }

    override fun onTagDiscovered(tag: Tag?) {
        val t = tag ?: return
        when (val out = TapExchange.run(t, Identity.card(activity), onLog)) {
            is TapExchange.Outcome.Got -> when (val p = out.payload) {
                is TapPayload.Request -> {
                    onLog("✓ ${p.request.label} wants ${p.request.amount} ${p.request.token}")
                    onLog("— you can separate the phones now")
                    onRequest(p.request)
                }
                is TapPayload.Card -> {
                    onLog("✓ met ${p.card.label}")
                    onCard(p.card)
                }
            }
            TapExchange.Outcome.NothingToOffer -> onLog("— that phone has no wallet connected yet")
            is TapExchange.Outcome.Failed -> onLog("✗ ${out.why}")
        }
    }

    companion object { private const val TAG = "TapReader" }
}
