package com.tapsol.tap

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The "card" half of a tap. This phone is requesting payment, so it sits there emulating a
 * smartcard and waits for the payer's phone to read it.
 *
 * Android starts this service on its own the moment a reader selects our AID, which is why
 * the request being served lives in a companion object rather than being passed in — there is
 * no opportunity to hand it a constructor argument.
 *
 * Constraints that shape everything here: the process can be started and killed at any moment,
 * every response must fit one APDU, and the whole exchange lasts only as long as two people
 * hold their phones together.
 */
class TapHostApduService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val cmd = commandApdu ?: return Apdu.SW_UNKNOWN_CMD
        Log.i(TAG, "← ${cmd.toHex()}")
        events.tryEmit("← ${cmd.toHex().take(48)}")

        return when {
            Apdu.isSelect(cmd) -> {
                val req = pending.value
                if (req == null) {
                    // A reader tapped us while we were not asking for anything.
                    Log.i(TAG, "SELECT but nothing pending")
                    events.tryEmit("SELECT — nothing pending")
                    Apdu.SW_NOT_READY
                } else {
                    val body = req.toBytes()
                    Log.i(TAG, "SELECT ok → serving ${body.size}B request")
                    events.tryEmit("→ request ${body.size}B  ${req.amount} ${req.token}")
                    body + Apdu.SW_OK
                }
            }

            Apdu.isAck(cmd) -> {
                val data = Apdu.dataOf(cmd)
                val ack = PayAck.fromBytes(data)
                if (ack == null) {
                    events.tryEmit("ACK unparseable (${data.size}B)")
                    Apdu.SW_UNKNOWN_CMD
                } else if (ack.nonce != pending.value?.nonce) {
                    // Wrong nonce: either a replay or a different tap. Refuse it.
                    Log.w(TAG, "nonce mismatch — refusing")
                    events.tryEmit("✗ nonce mismatch — refused")
                    Apdu.SW_NOT_READY
                } else {
                    Log.i(TAG, "ACK from ${ack.payer}")
                    events.tryEmit("✓ payer ${ack.payer.take(8)}…${if (ack.genesis != null) "  [Seeker]" else ""}")
                    acks.tryEmit(ack)
                    Apdu.SW_OK
                }
            }

            else -> {
                events.tryEmit("unknown cmd")
                Apdu.SW_UNKNOWN_CMD
            }
        }
    }

    /** Fired when the phones are pulled apart, or the reader moves to another AID. */
    override fun onDeactivated(reason: Int) {
        val why = if (reason == DEACTIVATION_LINK_LOSS) "link lost" else "deselected"
        Log.i(TAG, "deactivated: $why")
        events.tryEmit("— $why")
    }

    companion object {
        private const val TAG = "TapHCE"

        /** The request this phone is currently offering, or null when idle. */
        val pending = MutableStateFlow<PayRequest?>(null)

        /** Acks received from payers. */
        val acks = MutableSharedFlow<PayAck>(extraBufferCapacity = 8)

        /** Raw exchange log, so Phase 1 can be debugged by eye. */
        val events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    }
}
