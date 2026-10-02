package so.duffle.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation

/**
 * Phase 1 is being debugged across two phones, only one of which is reachable over adb.
 * Rather than guess at the other one, the app reports its own capability on screen.
 *
 * Each line answers a question that would otherwise need a shell.
 */
data class Diagnostics(
    val nfcPresent: Boolean,
    val nfcEnabled: Boolean,
    val hceFeature: Boolean,
    val serviceRegistered: Boolean,
    val aidRegistered: Boolean,
    val isDefaultForAid: Boolean,
    val model: String,
) {
    /** The single blocking problem, if there is one. */
    val blocker: String? get() = when {
        !nfcPresent -> "This phone has no NFC at all."
        !nfcEnabled -> "NFC is switched off. Turn it on in Settings."
        !hceFeature -> "This phone cannot emulate a card (no HCE). It can PAY, but cannot be paid."
        !serviceRegistered -> "Card service is not registered with Android."
        !aidRegistered -> "Our AID is not registered, Android is not routing taps to us."
        else -> null
    }

    fun lines(): List<Pair<String, Boolean>> = listOf(
        "NFC hardware" to nfcPresent,
        "NFC switched on" to nfcEnabled,
        "Card emulation (HCE)" to hceFeature,
        "Card service registered" to serviceRegistered,
        "AID $AID_SHORT routed" to aidRegistered,
    )

    companion object {
        private const val AID_SHORT = "F0…546170"

        fun read(ctx: Context): Diagnostics {
            val pm = ctx.packageManager
            val adapter = NfcAdapter.getDefaultAdapter(ctx)
            val hce = pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)

            val component = ComponentName(ctx, TapHostApduService::class.java)
            var registered = false
            var aidOk = false
            var isDefault = false

            if (hce && adapter != null) {
                try {
                    val ce = CardEmulation.getInstance(adapter)
                    // GOTCHA: getAidsForService() returns null for AIDs declared STATICALLY in
                    // apduservice.xml — it only reports dynamically registered ones. Using it
                    // as the registration check reported "not registered" while dumpsys showed
                    // the AID correctly routed. isDefaultServiceForAid is the honest test.
                    isDefault = ce.isDefaultServiceForAid(component, Apdu.AID)
                    aidOk = isDefault
                    registered = isDefault ||
                        ce.getAidsForService(component, CardEmulation.CATEGORY_OTHER) != null
                } catch (e: Exception) {
                    // Leaves the flags false, which is the honest answer.
                }
            }

            return Diagnostics(
                nfcPresent = adapter != null,
                nfcEnabled = adapter?.isEnabled == true,
                hceFeature = hce,
                serviceRegistered = registered,
                aidRegistered = aidOk,
                isDefaultForAid = isDefault,
                model = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            )
        }
    }
}
