package so.duffle.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A sheet that comes in from the left.
 *
 * [Tray] could not be reused: everything about it — the grab handle at the top, the drag axis,
 * the corners it rounds, the direction it leaves in — encodes "this came up from the bottom".
 * Bending it into a side panel would have left a horizontal sheet with a horizontal handle
 * being dragged vertically. Same scrim, same springs, same corner radius; different axis.
 *
 * Dragging left closes it, and dragging right is resisted rather than refused, so the panel
 * feels attached to the edge it came from.
 */
@Composable
internal fun SideSheet(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scope = rememberCoroutineScope()
    val scrim = remember { Animatable(0f) }
    val offset = remember { Animatable(0f) }
    var width by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Off-screen until it has been measured, so it never flashes in place — same order as
        // Tray, for the same reason.
        val w = snapshotFlow { width }.first { it > 0f }
        offset.snapTo(-w)
        shown = true
        launch { scrim.animateTo(1f, tween(220)) }
        offset.animateTo(0f, Arrive)
    }

    fun leave() {
        if (leaving) return
        leaving = true
        scope.launch { scrim.animateTo(0f, tween(150)) }
        scope.launch {
            offset.animateTo(-width.coerceAtLeast(1f), tween(200))
            onClose()
        }
    }

    BackHandler { leave() }

    val drag = rememberDraggableState { delta ->
        scope.launch {
            val next = offset.value + delta
            offset.snapTo(if (next > 0f) (offset.value + delta * .25f).coerceAtMost(40f) else next)
        }
    }

    Box(
        Modifier.fillMaxSize()
            .drawBehind {
                // Fades with the drag as well as with the animation, so pulling the panel
                // halfway off shows half a scrim rather than a full one that snaps at the end.
                val gone = if (width > 0f) (-offset.value / width).coerceIn(0f, 1f) else 0f
                drawRect(Color.Black.copy(alpha = .28f * scrim.value * (1f - gone)))
            }
            .clickable(remember { MutableInteractionSource() }, indication = null) { leave() },
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            Modifier.fillMaxHeight().fillMaxWidth(.88f)
                .onSizeChanged { width = it.width.toFloat() }
                .graphicsLayer { translationX = if (shown) offset.value else -size.width }
                .draggable(
                    drag, Orientation.Horizontal, enabled = shown && !leaving,
                    onDragStopped = { velocity ->
                        if (-offset.value > width * .25f || velocity < -1_200f) leave()
                        else scope.launch { offset.animateTo(0f, Settle, initialVelocity = velocity) }
                    },
                )
                .background(D.White, RoundedCornerShape(topEnd = Tray.OUTER, bottomEnd = Tray.OUTER))
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .statusBarsPadding()
                .navigationBarsPadding(),
            content = content,
        )
    }
}

/**
 * You, as the rest of the world sees you: your name, the wallet that pays, and the code
 * somebody points a camera at.
 *
 * ### Why the QR is the centre of it
 *
 * Everything else in this app moves money by touching phones, which needs both people present
 * and both holding a Seeker-ish device. The QR is the fallback that works with **any** Solana
 * wallet, from across a room, and until now the app could read one but never show one — so the
 * only way to be paid was to be tapped. It encodes `solana:<address>`, the standard form, so
 * it is not an AirPay code that happens to work elsewhere; it is the ordinary one.
 *
 * ### The rows are doors, not settings
 *
 * The screen this follows lists settings. Almost nothing here is a setting — it is a payments
 * app with two things you can actually change (your name, and where codes get emailed). The
 * rest of the list is the app's own screens, which otherwise hang off a tile grid halfway down
 * the home page. Long-pressing the wordmark for diagnostics also finally gets a visible door:
 * a gesture nobody is told about is a feature nobody has.
 */
@Composable
internal fun ProfileDrawer(
    name: String,
    /** Null when no wallet is connected — the QR and the rows that need one say so. */
    address: String?,
    walletName: String?,
    isSeeker: Boolean,
    network: String,
    version: String,
    email: String?,
    onRename: () -> Unit,
    onEmail: () -> Unit,
    onWallets: () -> Unit,
    onShare: (String) -> Unit,
    onGo: (Tab) -> Unit,
    onDiagnostics: () -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    SideSheet(onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(14.dp))

            // -------------------------------------------------------------- who you are
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(54.dp).clip(CircleShape).background(D.Fill),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name.take(1).uppercase().ifBlank { "Y" }, color = D.Quiet, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, fontFamily = Display)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name.ifBlank { "No name yet" },
                            color = if (name.isBlank()) D.Quiet else D.Black,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = Display,
                            maxLines = 1,
                        )
                        if (isSeeker) {
                            Spacer(Modifier.width(6.dp))
                            HIcon(R.drawable.hi_verified, D.BlueInk, 16.dp)
                        }
                    }
                    Spacer(Modifier.height(1.dp))
                    Text(
                        if (isSeeker) "Verified Seeker" else "$network · AirPay",
                        color = D.Quiet, fontSize = 12.sp,
                    )
                }
                val close = remember { MutableInteractionSource() }
                Box(
                    Modifier.then(PressScale(close)).size(32.dp).background(D.Fill, CircleShape)
                        .clickable(close, indication = null, onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(12.dp)) {
                        val w = size.minDimension * .14f
                        drawLine(D.Black, Offset.Zero, Offset(size.width, size.height),
                            strokeWidth = w, cap = StrokeCap.Round)
                        drawLine(D.Black, Offset(size.width, 0f), Offset(0f, size.height),
                            strokeWidth = w, cap = StrokeCap.Round)
                    }
                }
            }

            // Only when there is something to fix. A banner that is always there is chrome,
            // and chrome is not read.
            if (name.isBlank()) {
                Spacer(Modifier.height(14.dp))
                Nudge("Set a name", "Without one, the people you tap see a wallet address.", onRename)
            }

            Spacer(Modifier.height(18.dp))

            // -------------------------------------------------------------- the QR
            if (address == null) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(D.Fill).padding(20.dp)) {
                    Text("No wallet connected", color = D.Black, fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Connect one and a code to be paid with appears here.",
                        color = D.Quiet, fontSize = 13.sp)
                }
            } else {
                val uri = remember(address) { "solana:$address" }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(D.Blue).padding(5.dp),
                ) {
                    // White, and generously padded: the quiet zone is part of the code, and a
                    // QR sitting straight on a coloured card is one a scanner may refuse.
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                            .background(D.White).padding(18.dp),
                    ) {
                        QrCode(uri, Modifier.fillMaxWidth().aspectRatio(1f))
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Scan to pay me", color = D.White, fontSize = 13.sp,
                                fontWeight = FontWeight.Bold)
                            Text("Works in any Solana wallet", color = D.White.copy(alpha = .8f),
                                fontSize = 11.sp)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                // The address, copyable. Shown in full-ish rather than as an opaque blob: it is
                // the thing somebody will check against what their wallet shows them.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(D.Fill)
                        .clickable { clipboard.setText(AnnotatedString(address)) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(walletName ?: "Wallet", color = D.Quiet, fontSize = 11.sp)
                        Text("${address.take(10)}…${address.takeLast(6)}", color = D.Black,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = Figures)
                    }
                    Text("Copy", color = D.BlueInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Share", { onShare(uri) }, Modifier.weight(1f))
                    SecondaryButton("Change wallet", onWallets, Modifier.weight(1f))
                }
            }

            // -------------------------------------------------------------- doors
            Spacer(Modifier.height(24.dp))
            Text("Everything else", color = D.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))

            DrawerRow("Activity", "Requests waiting on you") { onGo(Tab.REQUESTS) }
            DrawerRow("Transactions", "Everything paid and received") { onGo(Tab.HISTORY) }
            DrawerRow("Rewards", "SKR earned by tapping") { onGo(Tab.REWARDS) }
            DrawerRow("Offers", "Pay an app, earn SKR") { onGo(Tab.OFFERS) }
            DrawerRow("Refer and earn", "Bring a friend") { onGo(Tab.REFERRALS) }
            DrawerRow("Notifications", "What happened while you were away") { onGo(Tab.ALERTS) }

            Spacer(Modifier.height(18.dp))
            Text("Settings", color = D.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            DrawerRow("Your name", name.ifBlank { "Not set" }, onRename)
            DrawerRow("Codes emailed to", email ?: "Not set", onEmail)
            // The diagnostics used to live only behind a long-press on the wordmark. A gesture
            // nobody is told about is a feature nobody has.
            DrawerRow("Diagnostics", "NFC, wallet and server checks", onDiagnostics)

            Spacer(Modifier.height(22.dp))
            Text("AirPay $version · $network", color = D.Quiet, fontSize = 11.5.sp,
                modifier = Modifier.fillMaxWidth(), style = Figures)
            Spacer(Modifier.height(4.dp))
            Text("Payments move real money on Solana mainnet.", color = D.Quiet, fontSize = 11.sp)
            Spacer(Modifier.height(26.dp))
        }
    }
}

/** One thing worth doing, shown only while it is undone. */
@Composable
private fun Nudge(title: String, detail: String, onAct: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.then(PressScale(interaction)).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(D.Blue.copy(alpha = .10f))
            .clickable(interaction, indication = null, onClick = onAct)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = D.Black, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = D.Quiet, fontSize = 11.5.sp)
        }
        Text("Set", color = D.BlueInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DrawerRow(title: String, detail: String, onOpen: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.then(PressScale(interaction)).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(interaction, indication = null, onClick = onOpen)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = D.Black, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(1.dp))
            Text(detail, color = D.Quiet, fontSize = 12.sp, maxLines = 1)
        }
        Canvas(Modifier.size(8.dp, 13.dp)) {
            drawLine(D.Quiet, Offset(0f, 0f), Offset(size.width, size.height / 2),
                strokeWidth = size.width * .26f, cap = StrokeCap.Round)
            drawLine(D.Quiet, Offset(size.width, size.height / 2), Offset(0f, size.height),
                strokeWidth = size.width * .26f, cap = StrokeCap.Round)
        }
    }
}
