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
            // Only one command crosses the link, so a short timeout is right — if the phones
            // are too far apart we want to fail fast and let the user try again.
            iso.timeout = 2000
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
            onLog("— you can separate the phones now")

            // Everything after this happens with the phones apart.
            onRequest(req)
        } catch (e: Exception) {
            onLog("✗ ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { iso.close() }
        }
    }

    companion object { private const val TAG = "TapReader" }
}
