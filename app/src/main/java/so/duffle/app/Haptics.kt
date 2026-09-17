package so.duffle.app

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * What the tap feels like.
 *
 * The exchange is over in ~100ms, faster than anyone can read a screen, and phones held
 * back-to-back are not being looked at anyway. Without a pulse at the instant of contact
 * people keep holding, or pull away early and try again. A buzz on BOTH phones at the same
 * moment is the "paired" feeling — and it tells them they can separate.
 *
 * Every call is best-effort: no vibrator, or haptics switched off, must never break a payment.
 */
object Haptics {

    /** The phones found each other. Felt on both at once. */
    fun contact(context: Context) =
        play(context, VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))

    /** Money moved — sent on the payer, arrived on the payee. */
    fun success(context: Context) =
        play(context, VibrationEffect.createWaveform(longArrayOf(0, 35, 70, 55), intArrayOf(0, 180, 0, 255), -1))

    /** Nothing moved: expired, refused, or declined. Deliberately unlike success. */
    fun failure(context: Context) =
        play(context, VibrationEffect.createWaveform(longArrayOf(0, 90, 60, 90), intArrayOf(0, 120, 0, 120), -1))

    private fun play(context: Context, effect: VibrationEffect) {
        runCatching {
            context.getSystemService(VibratorManager::class.java)
                ?.defaultVibrator
                ?.takeIf { it.hasVibrator() }
                ?.vibrate(effect)
        }
    }
}
