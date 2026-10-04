package so.duffle.app

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy

/**
 * A picture of the screen the tap sheet is about to cover, so the sheet can show it blurred.
 *
 * The sheet is its own window, and the Seeker cannot blur one window behind another (its
 * display driver does not offer it: `mBlurEnabled=false`). So the screen underneath is copied
 * just before the sheet opens, small, and the sheet blurs that copy itself.
 *
 * Small on purpose. It is about to be blurred, so a quarter of the resolution is enough, and it
 * keeps the copy quick and the memory trivial.
 */
object Backdrop {
    private var shot: Bitmap? = null
    private var at = 0L

    /** The copy taken a moment ago, or null when there is none or it is too old to be this screen. */
    fun recent(): Bitmap? = shot?.takeIf { System.currentTimeMillis() - at < 3_000 }

    /**
     * The copy the tap sheet is drawing right now, or null when it has none. The request sheet
     * that can follow it draws the same one, so the page behind does not snap back into focus
     * between the two.
     */
    @Volatile var showing: Bitmap? = null

    /** Copies [activity]'s window, then runs [then] either way. A failed copy only means no blur. */
    fun capture(activity: Activity, then: () -> Unit) {
        val view = activity.window.decorView
        if (view.width <= 0 || view.height <= 0) return then()
        val bitmap = runCatching {
            Bitmap.createBitmap(view.width / 4, view.height / 4, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return then()
        runCatching {
            PixelCopy.request(activity.window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) { shot = bitmap; at = System.currentTimeMillis() }
                then()
            }, Handler(Looper.getMainLooper()))
        }.onFailure { then() }
    }
}
