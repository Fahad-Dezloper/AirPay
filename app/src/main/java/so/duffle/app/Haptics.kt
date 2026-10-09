package so.duffle.app

import android.app.Activity
import android.content.Context
import android.view.HapticFeedbackConstants
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

    /**
     * The phones found each other. Felt on both at once.
     *
     * One solid buzz, chosen by hand on the Seeker out of six ("Touch 4" in the debug feel
     * test). The built-in heavy click this used to be is over in a tenth of a second, which is
     * too little to notice through two phones pressed together. The Seeker's motor has no
     * strength control and no primitives, so length is the only thing there is to turn up.
     */
    fun contact(context: Context) =
        play(context, VibrationEffect.createOneShot(CONTACT_MS, VibrationEffect.DEFAULT_AMPLITUDE))

    private const val CONTACT_MS = 260L

    /** Money moved — sent on the payer, arrived on the payee. Two crisp taps, Apple Pay style. */
    fun success(context: Context) =
        play(context, VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))

    /**
     * Nothing moved: refused, declined or withdrawn. The system's own "rejected" pattern where
     * there is a window to play it on; the Seeker's motor has no amplitude control, so the old
     * stepped waveforms came out as flat buzzes.
     */
    fun failure(context: Context) {
        val view = (context as? Activity)?.window?.decorView
        if (view == null || !view.performHapticFeedback(HapticFeedbackConstants.REJECT)) {
            play(context, VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        }
    }

    /** A choice made — picking a token. Firmer than a key, lighter than the tap. */
    fun select(context: Context) =
        play(context, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))

    /** A keypad digit. Frequent, so faint. */
    fun tick(context: Context) =
        play(context, VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))

    private fun play(context: Context, effect: VibrationEffect) {
        runCatching {
            context.getSystemService(VibratorManager::class.java)
                ?.defaultVibrator
                ?.takeIf { it.hasVibrator() }
                ?.vibrate(effect)
        }
    }
}
