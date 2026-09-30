package so.duffle.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
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
 *
 * ### Ported from ui/components/screens/Drawer.tsx (2026-09-30)
 *
 * The containers are gone: the address, the no-wallet message and the rows sit on white, split
 * by space and a `D.Line` divider, because a door is not a settings card. The QR card is a
 * poster — only "Scan to pay me" at 19sp bold stays on the blue, since white on `D.Blue` is
 * 3.30:1 and passes only as large text — so "Works in any Solana wallet" moved under it onto
 * white. The "Set a name" nudge lost its blue wash; the highlight marks the words instead.
 *
 * The arrangement was re-checked against that file afterwards: the page carries its own 12/32
 * top and bottom padding, the gaps above the nudge and above the QR card are 24 and not 16,
 * the empty state takes a row's padding of its own, "Copy" is a 48dp target rather than two
 * loose children of the address row, the address is mono at body size, and the first door in a
 * group carries the same row padding as every other door.
 *
 * [SideSheet]'s geometry is deliberate and untouched: only type, colour and spacing changed.
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
        Column(
            // The page's own edges: the gutter on both sides, 12 above the avatar and 32 below
            // the last line. Inside the scroll, so the bottom is space after the last line
            // rather than a gap the panel can never scroll away.
            Modifier.verticalScroll(rememberScrollState())
                .padding(start = Space.gutter, top = 12.dp, end = Space.gutter, bottom = 32.dp),
        ) {
            // -------------------------------------------------------------- who you are
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A white disc with a ring rather than a grey one: 2dp of blue marks a verified
                // Seeker, a hairline marks everybody else. The initial is 0.38 x the disc — part
                // of the avatar's own composition, not a step on the type scale.
                // `Initial` (Design.kt). The letter is 0.38 x the disc, which is where the
                // 21sp here came from — it was the ratio already, just written as a number,
                // so it read like a scale step somebody had invented.
                Initial(name.take(1).uppercase().ifBlank { "Y" }, 56.dp, seeker = isSeeker)
                Spacer(Modifier.width(Space.inline))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name.ifBlank { "No name yet" },
                            color = if (name.isBlank()) D.Quiet else D.Black,
                            style = Type.heading,
                            maxLines = 1,
                        )
                        if (isSeeker) {
                            Spacer(Modifier.width(Space.tight))
                            // 20dp beside a 19sp line, and in ink: it is a small icon, not a fill.
                            HiIcon(Hi.Verified, D.BlueInk, 20.dp)
                        }
                    }
                    Spacer(Modifier.height(Space.tight))
                    Text(
                        if (isSeeker) "Verified Seeker" else "$network · AirPay",
                        color = D.Quiet, style = Type.caption,
                    )
                }
                Spacer(Modifier.width(Space.inline))
                val close = remember { MutableInteractionSource() }
                Box(
                    // 48dp of hit area, pulled out by 12 so the mark — not the empty half of the
                    // target — is what meets the gutter.
                    Modifier.then(PressScale(close)).offset(x = 12.dp).size(48.dp)
                        .clickable(close, indication = null, onClick = onClose)
                        .semantics { contentDescription = "Close" },
                    contentAlignment = Alignment.Center,
                ) {
                    // The shipped close mark at 24dp, the action size, rather than a
                    // hand-drawn cross: the stroke then follows the text like every other
                    // icon in the app (ui/PORT.md §5).
                    HiIcon(Hi.Close, D.Black, 24.dp)
                }
            }

            // Only when there is something to fix. A banner that is always there is chrome,
            // and chrome is not read.
            if (name.isBlank()) {
                Spacer(Modifier.height(24.dp))
                Nudge("Set a name", "Without one, the people you tap see a wallet address.", onRename)
            }

            // -------------------------------------------------------------- the QR
            if (address == null) {
                Spacer(Modifier.height(Space.row))
                // Two lines on the page, taking a row's worth of space above and below. A grey
                // panel drawn around "there is nothing here" is a container around an absence.
                Column(Modifier.padding(vertical = Space.row)) {
                    Text("No wallet connected", color = D.Black, style = Type.body)
                    Spacer(Modifier.height(Space.tight))
                    Text("Connect one and a code to be paid with appears here.",
                        color = D.Quiet, style = Type.caption)
                }
            } else {
                Spacer(Modifier.height(24.dp))
                val uri = remember(address) { "solana:$address" }
                Column(
                    // Concentric: 20 outside = 12 inside + the 8 of blue between them.
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(D.Blue).padding(8.dp),
                ) {
                    // White, and generously padded: the quiet zone is part of the code, and a
                    // QR sitting straight on a coloured card is one a scanner may refuse.
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(D.White).padding(20.dp),
                    ) {
                        QrCode(uri, Modifier.fillMaxWidth().aspectRatio(1f))
                    }
                    // The only words left on the blue, and 19sp bold is the floor white on
                    // D.Blue clears (3.30:1 passes as large text and nothing smaller).
                    Text(
                        "Scan to pay me", color = D.White, style = Type.heading,
                        modifier = Modifier.padding(
                            start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                    )
                }
                // Off the blue: at 11sp white this was the least legible line in the drawer.
                Spacer(Modifier.height(8.dp))
                Text("Works in any Solana wallet", color = D.Quiet, style = Type.caption)

                Spacer(Modifier.height(Space.row))
                // The address, copyable. Shown in full-ish rather than as an opaque blob: it is
                // the thing somebody will check against what their wallet shows them. Ruled
                // above and below instead of boxed.
                Divider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable { clipboard.setText(AnnotatedString(address)) }
                        .padding(vertical = Space.row),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(walletName ?: "Wallet", color = D.Quiet, style = Type.caption)
                        Spacer(Modifier.height(Space.tight))
                        // An identifier, read one character at a time: the mono face, not the
                        // money face it used to borrow. At body size rather than caption,
                        // because this is the line somebody checks character by character
                        // against what their own wallet shows them.
                        Text("${address.take(10)}…${address.takeLast(6)}", color = D.Black,
                            style = Type.body.copy(fontFamily = Mono), maxLines = 1)
                    }
                    Spacer(Modifier.width(Space.inline))
                    val copy = remember { MutableInteractionSource() }
                    Row(
                        // Its own 48dp-tall target with 12 of padding, pulled out by 12 so the
                        // word — not the padding — is what meets the gutter.
                        Modifier.then(PressScale(copy)).offset(x = 12.dp).heightIn(min = 48.dp)
                            .clickable(copy, indication = null) {
                                clipboard.setText(AnnotatedString(address))
                            }
                            .padding(horizontal = 12.dp)
                            .semantics { contentDescription = "Copy address" },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The word "Copy" is right beside it, so the mark stays decorative.
                        HiIcon(Hi.Copy, D.BlueInk, 16.dp)
                        Spacer(Modifier.width(Space.tight))
                        Text("Copy", color = D.BlueInk, style = Type.caption)
                    }
                }
                Divider()

                Spacer(Modifier.height(Space.row))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.inline)) {
                    SecondaryButton("Share", { onShare(uri) }, Modifier.weight(1f))
                    SecondaryButton("Change wallet", onWallets, Modifier.weight(1f))
                }
            }

            // -------------------------------------------------------------- doors
            Spacer(Modifier.height(Space.section))
            Text("Everything else", color = D.Black, style = Type.heading)
            Spacer(Modifier.height(Space.lead))

            DrawerRow("Activity", "Requests waiting on you") { onGo(Tab.REQUESTS) }
            Divider()
            DrawerRow("Transactions", "Everything paid and received") { onGo(Tab.HISTORY) }
            Divider()
            DrawerRow("Rewards", "SKR earned by tapping") { onGo(Tab.REWARDS) }
            Divider()
            DrawerRow("Offers", "Pay an app, earn SKR") { onGo(Tab.OFFERS) }
            Divider()
            DrawerRow("Refer and earn", "Bring a friend") { onGo(Tab.REFERRALS) }
            Divider()
            DrawerRow("Notifications", "What happened while you were away") { onGo(Tab.ALERTS) }

            Spacer(Modifier.height(Space.section))
            Text("Settings", color = D.Black, style = Type.heading)
            Spacer(Modifier.height(Space.lead))
            DrawerRow("Your name", name.ifBlank { "Not set" }, onOpen = onRename)
            Divider()
            DrawerRow("Codes emailed to", email ?: "Not set", onOpen = onEmail)
            Divider()
            // The diagnostics used to live only behind a long-press on the wordmark. A gesture
            // nobody is told about is a feature nobody has.
            DrawerRow("Diagnostics", "NFC, wallet and server checks", onOpen = onDiagnostics)

            Spacer(Modifier.height(Space.section))
            Text("AirPay $version · $network", color = D.Quiet, style = CaptionTnum,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.tight))
            Text("Payments move real money on Solana mainnet.", color = D.Quiet,
                style = Type.caption)
        }
    }
}

/**
 * The version line's step, with tabular figures.
 *
 * Not an eighth scale step: [Type.caption] with `tnum`, so a version number's digits sit in
 * fixed-width cells rather than shifting as the build changes.
 */
private val CaptionTnum = Type.caption.copy(fontFeatureSettings = "tnum")

/** A hairline between two doors. Decorative — the space is what actually separates them. */
@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(D.Line))

/**
 * One thing worth doing, shown only while it is undone.
 *
 * The pale blue wash behind this was a tint, and tints are gone. `D.Highlight` behind ink text
 * is what replaced them: a marker pen at 10.83:1, which actually marks the words, where the
 * wash only suggested something was there.
 */
@Composable
private fun Nudge(title: String, detail: String, onAct: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.then(PressScale(interaction)).fillMaxWidth().heightIn(min = 48.dp)
            .clickable(interaction, indication = null, onClick = onAct),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            // Pulled back by the highlight's own padding, so the marked words still begin on
            // the same edge as every other line in the drawer.
            Text(
                title, color = D.Black, style = Type.body,
                modifier = Modifier.offset(x = (-4).dp).clip(RoundedCornerShape(4.dp))
                    .background(D.Highlight).padding(horizontal = 4.dp),
            )
            Spacer(Modifier.height(Space.tight))
            Text(detail, color = D.Quiet, style = Type.caption)
        }
        Spacer(Modifier.width(Space.inline))
        Text("Set", color = D.BlueInk, style = Type.body)
    }
}

/**
 * A door.
 *
 * No card and no grey body: the title, what is behind it, and a chevron, separated from the next
 * door by space and a hairline. Every door carries a full row's padding top and bottom, the
 * first one included: the heading's own 12 sits above that rather than instead of it, which is
 * the 12-against-40 ratio that makes a group read as a group without a box round it.
 */
@Composable
private fun DrawerRow(
    title: String, detail: String, onOpen: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // The chevron means "forward", so it points the other way in a right-to-left layout.
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        Modifier.then(PressScale(interaction)).fillMaxWidth()
            .clickable(interaction, indication = null, onClick = onOpen)
            .padding(vertical = Space.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = D.Black, style = Type.body)
            Spacer(Modifier.height(Space.tight))
            Text(detail, color = D.Quiet, style = Type.caption, maxLines = 1)
        }
        Spacer(Modifier.width(Space.inline))
        // Quiet, and unlabelled: it is an affordance, not information, and the row's own title
        // is what a screen reader should read.
        // 16dp, the size of any mark beside a line of text, with the glyph drawn on the
        // 24-unit grid the marks share: 4.8 → 19.2 across, the full height, stroke 3.744.
        // Heavier than markStroke on purpose — two strokes this short have to hold on their
        // own at the end of a row.
        Canvas(Modifier.size(16.dp)) {
            val u = size.width / 24f
            val tip = if (rtl) 4.8f * u else 19.2f * u
            val base = if (rtl) 19.2f * u else 4.8f * u
            drawLine(D.Quiet, Offset(base, 0f), Offset(tip, size.height / 2),
                strokeWidth = 3.744f * u, cap = StrokeCap.Round)
            drawLine(D.Quiet, Offset(tip, size.height / 2), Offset(base, size.height),
                strokeWidth = 3.744f * u, cap = StrokeCap.Round)
        }
    }
}
