package so.duffle.app

import android.app.PendingIntent
import android.content.Intent
import android.service.quickaccesswallet.GetWalletCardsCallback
import android.service.quickaccesswallet.GetWalletCardsError
import android.service.quickaccesswallet.GetWalletCardsRequest
import android.service.quickaccesswallet.QuickAccessWalletService
import android.service.quickaccesswallet.SelectWalletCardRequest

/**
 * What makes AirPay something the phone can call its wallet app.
 *
 * Double-pressing the power button opens the wallet app, and the system finds out what to open
 * by asking this service of whichever app holds the Wallet role. The answer here is always the
 * tap and pay sheet: the gesture means "I am about to pay", and that sheet is where paying
 * starts.
 *
 * There are no cards to hand the system's own wallet panel. A wallet here is an account in
 * another app, and a picture of it on the lock screen would promise a tap that works without
 * opening AirPay, which it does not. So the card request is answered with "none", and the
 * system falls back to opening the sheet.
 */
class AirPayWalletService : QuickAccessWalletService() {

    private fun sheet(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        TapPopupActivity.armed(this).setAction(ACTION_VIEW_WALLET).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        // No window slide: the sheet rises by itself, after the page behind has dimmed.
        android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
    )

    /** The double-press itself, on Android 16. */
    override fun getGestureTargetActivityPendingIntent(): PendingIntent = sheet()

    /** The lock-screen shortcut and the quick-settings tile, and the gesture on older builds. */
    override fun getTargetActivityPendingIntent(): PendingIntent = sheet()

    override fun onWalletCardsRequested(request: GetWalletCardsRequest, callback: GetWalletCardsCallback) =
        callback.onFailure(GetWalletCardsError(null, null))

    override fun onWalletCardSelected(request: SelectWalletCardRequest) = Unit

    override fun onWalletDismissed() = Unit
}
