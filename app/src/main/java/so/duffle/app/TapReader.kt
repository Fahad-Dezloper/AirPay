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
    /** A phone came near and nothing usable was read. One plain sentence, for the screen. */
    private val onProblem: (String) -> Unit = {},
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
                    onLog("- you can separate the phones now")
                    onRequest(p.request)
                }
                is TapPayload.Card -> {
                    onLog("✓ met ${p.card.label}")
                    onCard(p.card)
                }
            }
            TapExchange.Outcome.NothingToOffer -> {
                onLog("- that phone has no wallet connected yet")
                onProblem("That phone has no wallet connected yet.")
            }
            is TapExchange.Outcome.Failed -> {
                onLog("✗ ${out.why}")
                // A read that failed used to be a log line and nothing else: the sheet went on
                // waiting as though no phone had ever come near.
                onProblem(
                    if (out.why.contains("version", true)) "That phone runs a different AirPay. Update both."
                    else "Couldn't read that phone. Hold steady and try again."
                )
            }
        }
    }

    companion object { private const val TAG = "TapReader" }
}

/**
 * The reader for the second tap of a payment signed with no connection.
 *
 * A reader of its own rather than a mode of [TapReader], so the read path cannot be changed by
 * accident. It does one thing: every phone that comes near is offered [tx], and [onResult] is
 * told how that went. Called on a binder thread, like every reader callback.
 */
class DeliverReader(
    private val activity: Activity,
    private val tx: ByteArray,
    private val reference: String,
    private val onLog: (String) -> Unit,
    private val onResult: (TapExchange.Delivery) -> Unit,
) : NfcAdapter.ReaderCallback {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    fun start() {
        // The same flags as TapReader, for the same reasons.
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        val extras = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500) }
        adapter?.enableReaderMode(activity, this, flags, extras)
        onLog("deliver reader armed")
    }

    fun stop() {
        adapter?.disableReaderMode(activity)
        onLog("deliver reader off")
    }

    override fun onTagDiscovered(tag: Tag?) {
        val t = tag ?: return
        val out = TapExchange.deliver(t, tx, reference, onLog)
        onLog(when (out) {
            TapExchange.Delivery.Done -> "✓ handed over"
            is TapExchange.Delivery.NotThem -> "- ${out.why}"
            is TapExchange.Delivery.Failed -> "✗ ${out.why}"
        })
        onResult(out)
    }
}
