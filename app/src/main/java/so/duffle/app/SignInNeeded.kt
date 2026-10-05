package so.duffle.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Asking the wallet to prove it is the wallet — the sheet that opens BEFORE a screen that needs
 * a session, and the same content inline if the session lapses while one is open.
 *
 * What it has to get across: that it is free and cannot move funds, and how long it lasts — the
 * two lines on the marker. No icons — the wallet's own mark in the chip is
 * the only picture, because it says WHICH wallet is being asked. (It is a message signature, not
 * a transaction, and the second line says so in the words people worry about: free, can't move
 * funds.)
 *
 * It never opens the wallet by itself: the button does. And it says plainly when an attempt did
 * not go through, instead of going quiet.
 */
@Composable
internal fun AuthorizeBody(
    what: String,
    wallet: Linked?,
    signing: Boolean,
    failed: Boolean,
    onAuthorize: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Authorization signature", color = D.Black, style = Type.title, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.inline))
        Text(
            "To $what, you must authorize.",
            color = D.Quiet, style = Type.body, textAlign = TextAlign.Center,
        )

        if (wallet != null) {
            Spacer(Modifier.height(Space.lead))
            Row(
                Modifier.clip(CircleShape).border(1.dp, D.Line, CircleShape)
                    .padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WalletMark(wallet, 24.dp)
                Spacer(Modifier.width(8.dp))
                // The name gives way to the address: a long one pushed the address out of the chip.
                Text(
                    wallet.username ?: wallet.name, color = D.Black, style = Type.body, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(wallet.short, color = D.Quiet, style = Type.mono)
            }
        }

        Spacer(Modifier.height(Space.section))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.lead)) {
            Point("Free, and can't move your funds", "No fee, and AirPay never gets your keys.")
            Point("You're sorted for 30 days", "You won't be asked again until then.")
        }

        Spacer(Modifier.height(Space.section))
        if (failed && !signing) {
            Text(
                "That didn't go through. Try again when you're ready.",
                color = D.DangerInk, style = Type.caption, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Space.inline))
        }
        PrimaryButton(
            if (signing) "Waiting for your wallet…" else if (failed) "Try again" else "Authorize now",
            enabled = !signing,
            onClick = onAuthorize,
        )
    }
}

@Composable
private fun Point(title: String, detail: String) {
    Column(Modifier.fillMaxWidth()) {
        // The two things worth knowing sit on the marker, like an unread notification's title.
        // The line under each explains, and does not.
        Text(
            title, color = D.Black, style = Type.body,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(D.Highlight)
                .padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(Space.tight))
        Text(detail, color = D.Quiet, style = Type.caption)
    }
}

/** The sheet itself. Closes when the session arrives — the host runs what was waiting. */
@Composable
internal fun AuthorizeSheet(what: String, wallet: Linked?, onClose: () -> Unit) {
    Tray(onClose = onClose) {
        Spacer(Modifier.height(Space.section))
        AuthorizeBody(
            what = what, wallet = wallet,
            signing = Session.signingIn, failed = Session.failed,
            onAuthorize = { Session.authorize?.invoke() },
        )
        Spacer(Modifier.height(Space.lead))
    }
}

/**
 * The same prompt inline, for a server-backed screen already open whose session lapses.
 * The way IN is the sheet; this is only the safety net.
 */
@Composable
internal fun SignInNeeded(what: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = Space.section)) {
        AuthorizeBody(
            what = what, wallet = null,
            signing = Session.signingIn, failed = Session.failed,
            onAuthorize = { Session.authorize?.invoke() },
        )
    }
}
