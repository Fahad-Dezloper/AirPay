package com.tapsol.tap

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log

/**
 * The "reader" half of a tap. This phone is paying, so it drives the exchange.
 *
 * Reader mode is used rather than foreground dispatch because it lets us disable the platform's
 * NDEF handling and its confirmation sound — both of which fight the pairing animation and add
 * latency to a gesture that has to feel instant.
 */
class TapReader(
    private val activity: Activity,
    private val onLog: (String) -> Unit,
    private val onRequest: (PayRequest, respond: (PayAck) -> Boolean) -> Unit,
) : NfcAdapter.ReaderCallback {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    val available: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true

    fun start() {
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
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
        val iso = IsoDep.get(tag ?: return)
        if (iso == null) {
            onLog("✗ tag is not IsoDep — not a phone")
            return
        }
        try {
            iso.connect()
            // A tap that needs holding for more than ~2s feels broken, but allow headroom
            // for the transaction-building round trip.
            iso.timeout = 5000
            onLog("connected (maxTransceive=${iso.maxTransceiveLength}B)")

            val sel = Apdu.select()
            Log.i(TAG, "→ ${sel.toHex()}")
            onLog("→ SELECT ${Apdu.AID}")
            val resp = iso.transceive(sel)
            Log.i(TAG, "← ${resp.toHex()}")

            if (!Apdu.isOk(resp)) {
                onLog("✗ SELECT refused: ${resp.toHex()}")
                return
            }
            val body = Apdu.body(resp)
            onLog("← request ${body.size}B")

            val req = PayRequest.fromBytes(body)
            if (req == null) {
                onLog("✗ could not parse request")
                return
            }
            onLog("✓ ${req.label} wants ${req.amount} ${req.token}")

            // Hand it up. The callback decides whether to pay and returns the ack to send.
            onRequest(req) { ack ->
                try {
                    val cmd = Apdu.sendAck(ack.toBytes())
                    onLog("→ ACK ${ack.payer.take(8)}…")
                    val r = iso.transceive(cmd)
                    Log.i(TAG, "← ${r.toHex()}")
                    val ok = Apdu.isOk(r)
                    onLog(if (ok) "✓ accepted" else "✗ refused: ${r.toHex()}")
                    ok
                } catch (e: Exception) {
                    onLog("✗ ack failed: ${e.message}")
                    false
                }
            }
        } catch (e: Exception) {
            onLog("✗ ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { iso.close() }
        }
    }

    companion object { private const val TAG = "TapReader" }
}
