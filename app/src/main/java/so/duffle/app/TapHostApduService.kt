package so.duffle.app

import android.app.KeyguardManager
import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The "card" half of a tap, answering whoever reads this phone — including when Tap is closed
 * and the phone is locked.
 *
 * It speaks two languages on the same radio:
 *  * **An NDEF tag** (AID D2760000850101). Android on the other phone reads it with no app open
 *    and launches Tap's popup there. See [Ndef].
 *  * **Tap's own AID.** Tap in the foreground, or the popup that just opened, SELECTs it to get
 *    the payload, then sends HELLO with its own card — so this phone learns who tapped it too.
 *
 * What it hands out is a request when the user is standing in Request mode, and otherwise just
 * this phone's card. Nothing here can move money; a payment always needs the owner's wallet.
 *
 * Android starts this service on its own, which is why shared state lives in the companion:
 * there is no constructor to pass it through, and the process can be killed at any moment.
 */
class TapHostApduService : HostApduService() {

    /** Everything this tap has seen, reset when the phones part. */
    private var payload: TapPayload? = null
    private var ndef: Ndef.Session? = null
    private var inNdefApp = false
    private var servedRequest = false

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val cmd = commandApdu ?: return Apdu.SW_UNKNOWN_CMD
        Log.i(TAG, "← ${cmd.toHex().take(64)}")

        if (Apdu.isSelect(cmd) && cmd.size > 4 && cmd[2] == 0x04.toByte()) {
            val aid = Apdu.data(cmd)?.toHex() ?: return Apdu.SW_WRONG_DATA
            return when (aid) {
                Ndef.AID -> selectNdefApp()
                Apdu.AID -> selectTap()
                else -> Apdu.SW_FILE_NOT_FOUND
            }
        }
        if (Apdu.isHello(cmd)) return hello(cmd)
        if (inNdefApp) {
            ndef?.handle(cmd)?.let { out ->
                // The payload is out once a reader has read past the length prefix.
                if (ndef?.payloadRead == true) markServed()
                return out
            }
        }
        events.tryEmit("unknown command")
        return Apdu.SW_UNKNOWN_CMD
    }

    /** Snapshot once per tap, so every read in the session sees the same bytes. */
    private fun currentPayload(): TapPayload? {
        payload?.let { return it }
        val p = pending.value?.let { TapPayload.Request(it) }
            ?: Identity.card(this)?.let { TapPayload.Card(it) }
        payload = p
        return p
    }

    private fun selectNdefApp(): ByteArray {
        val p = currentPayload() ?: run {
            // No wallet connected: there is nothing worth handing out, so look like no tag.
            Log.i(TAG, "NDEF select, but no identity yet")
            return Apdu.SW_FILE_NOT_FOUND
        }
        ndef = Ndef.Session(Ndef.file(p.toBytes()))
        inNdefApp = true
        return Apdu.SW_OK
    }

    private fun selectTap(): ByteArray {
        inNdefApp = false
        val p = currentPayload() ?: run {
            Log.i(TAG, "SELECT but nothing to offer")
            events.tryEmit("SELECT — no wallet connected")
            return Apdu.SW_NOT_READY
        }
        val body = p.toBytes()
        Log.i(TAG, "SELECT ok → serving ${body.size}B ${if (p is TapPayload.Request) "request" else "card"}")
        events.tryEmit(
            if (p is TapPayload.Request) "→ sent ${p.request.amount} ${p.request.token} request (${body.size}B)"
            else "→ sent card (${body.size}B)"
        )
        markServed()
        return body + Apdu.SW_OK
    }

    /** A request leaves this phone once per tap, however many ways it was read. */
    private fun markServed() {
        val p = payload
        if (p !is TapPayload.Request || servedRequest) return
        servedRequest = true
        served.tryEmit(p.request)
        // Buzz here, in the service: the instant the request actually left the phone.
        Haptics.contact(this)
        // One serve, one request. Leaving it pending let the same reference go out on a
        // second tap — observed 21 seconds apart — and two payers would each have paid it.
        pending.value = null
    }

    private fun hello(cmd: ByteArray): ByteArray {
        val peer = Apdu.data(cmd)?.let { TapCard.fromBytes(it) } ?: return Apdu.SW_WRONG_DATA
        Log.i(TAG, "HELLO from ${peer.label} ${peer.address.take(8)}…")
        events.tryEmit("← ${peer.label} tapped this phone")
        met.tryEmit(peer)
        lastHello = peer
        // Recorded on both paths. This used to return before it, so the phone that ASKED for
        // money never filed the payer as a contact — only the payer filed the payee — and the
        // person you were owed by was missing from People entirely.
        Contacts(this).record(peer.address, peer.label, false, brand = peer.brand)
        if (payload is TapPayload.Request) {
            // We asked them for money; they already have the request. Nothing more to show.
            return Apdu.SW_OK
        }
        Haptics.contact(this)
        announce(peer)
        return Apdu.SW_OK
    }

    /**
     * Tell the owner someone tapped them. Always a notification — that is the one thing a
     * background service may always do, and on a locked phone it is exactly what should happen:
     * unlock, tap it, get the popup. On an unlocked phone we also try to open the popup directly.
     * Android does not document that as allowed for this kind of service and may block it; if it
     * does, the notification is already there, and the popup clears it when it opens.
     */
    private fun announce(peer: TapCard) {
        Push.showTapped(this, peer)
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true
        if (locked) return
        runCatching {
            startActivity(TapPopupActivity.intent(this, TapPayload.Card(peer), cameFrom = "hce")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.i(TAG, "popup start refused: ${it.message}") }
    }

    /**
     * Fires when the phones part. Expected immediately after a successful tap — it is the
     * normal end of the exchange, not a failure.
     */
    override fun onDeactivated(reason: Int) {
        val why = if (reason == DEACTIVATION_LINK_LOSS) "phones parted" else "deselected"
        Log.i(TAG, "deactivated: $why")
        events.tryEmit("— $why")
        payload = null
        ndef = null
        inNdefApp = false
        servedRequest = false
        parted.tryEmit(Unit)
    }

    companion object {
        private const val TAG = "TapHCE"

        /** The request this phone is currently offering, or null when idle. */
        val pending = MutableStateFlow<PayRequest?>(null)

        /** Emitted once a request has actually been handed to a reader. */
        val served = MutableSharedFlow<PayRequest>(extraBufferCapacity = 8)

        /** Emitted when a reader introduced itself with HELLO. */
        val met = MutableSharedFlow<TapCard>(extraBufferCapacity = 8)

        /**
         * The last reader to say hello, held rather than emitted.
         *
         * [served] fires on a later APDU in the same tap, and whoever handles it needs to know
         * who took the request — a flow collected somewhere else cannot be relied on to have
         * arrived first. Stale between taps by design: it is only ever read in the moment a
         * request is served, which is always after that tap's HELLO.
         */
        @Volatile var lastHello: TapCard? = null

        /** The link to the reader dropped — the phones have separated. */
        val parted = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

        /** Human-readable exchange log for the on-screen panel. */
        val events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    }
}
