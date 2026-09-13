package com.tapsol.tap

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The "card" half of a tap: this phone is requesting payment, so it emulates a smartcard and
 * waits to be read.
 *
 * Its whole job is to answer one SELECT with one payment request. There is no reply leg — the
 * payer's answer arrives on-chain, not over the radio — so the phones may separate the instant
 * this returns. That is what makes the gesture a tap rather than a hold.
 *
 * Android starts this service on its own the moment a reader selects our AID, which is why the
 * request being served lives in a companion object: there is no opportunity to hand it a
 * constructor argument, and the process can be killed and restarted at any time.
 */
class TapHostApduService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val cmd = commandApdu ?: return Apdu.SW_UNKNOWN_CMD
        Log.i(TAG, "← ${cmd.toHex()}")

        if (!Apdu.isSelect(cmd)) {
            events.tryEmit("unknown command")
            return Apdu.SW_UNKNOWN_CMD
        }

        val req = pending.value
        if (req == null) {
            // A reader tapped us while we were not asking for anything. Refusing beats
            // serving a stale request.
            Log.i(TAG, "SELECT but nothing pending")
            events.tryEmit("SELECT — nothing pending")
            return Apdu.SW_NOT_READY
        }

        val body = req.toBytes()
        Log.i(TAG, "SELECT ok → serving ${body.size}B request, ref ${req.reference.take(8)}…")
        events.tryEmit("→ sent ${req.amount} ${req.token} request (${body.size}B)")
        // Handed over. From here the payee watches the chain for `reference`.
        served.tryEmit(req)
        return body + Apdu.SW_OK
    }

    /**
     * Fires when the phones part. Expected immediately after a successful tap — it is the
     * normal end of the exchange, not a failure.
     */
    override fun onDeactivated(reason: Int) {
        val why = if (reason == DEACTIVATION_LINK_LOSS) "phones parted" else "deselected"
        Log.i(TAG, "deactivated: $why")
        events.tryEmit("— $why")
    }

    companion object {
        private const val TAG = "TapHCE"

        /** The request this phone is currently offering, or null when idle. */
        val pending = MutableStateFlow<PayRequest?>(null)

        /** Emitted once a request has actually been handed to a reader. */
        val served = MutableSharedFlow<PayRequest>(extraBufferCapacity = 8)

        /** Human-readable exchange log for the on-screen panel. */
        val events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    }
}
