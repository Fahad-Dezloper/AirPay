package so.duffle.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
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
 * How the side panel arrives and settles: no spring.
 *
 * [Arrive] and [Settle] are under-damped on purpose for things that rise out of the page, where
 * a little overshoot reads as weight. A panel that enters from an edge has nothing past its
 * resting place but the page behind it: overshooting 0 slides the panel out into the screen
 * and then back, exposing a gap on its left. So this is a tween that decelerates into place and
 * stops exactly there.
 */
private val Glide = tween<Float>(280, easing = FastOutSlowInEasing)

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
        offset.animateTo(0f, Glide)
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
                val gone = if (width > 0f) (-offset.value.coerceAtMost(0f) / width).coerceIn(0f, 1f) else 0f
                drawRect(Color.Black.copy(alpha = .28f * scrim.value * (1f - gone)))
            }
            .clickable(remember { MutableInteractionSource() }, indication = null) { leave() },
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            Modifier.fillMaxHeight().fillMaxWidth(.88f)
                .onSizeChanged { width = it.width.toFloat() }
                .graphicsLayer { translationX = if (shown) offset.value.coerceAtMost(0f) else -size.width }
                .draggable(
                    drag, Orientation.Horizontal, enabled = shown && !leaving,
                    onDragStopped = { velocity ->
                        if (-offset.value > width * .25f || velocity < -1_200f) leave()
                        else scope.launch { offset.animateTo(0f, Glide, initialVelocity = velocity) }
                    },
                )
                .background(D.White)
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
 * rest are doors: the wallets, a friend to invite, the things said about the app itself.
 * Diagnostics used to live only behind a long-press on the wordmark — a gesture nobody is told
 * about is a feature nobody has — and it keeps a visible door here, now under App settings.
 *
 * ### Ported from ui/components/screens/Drawer.tsx (2026-09-30, reworked 2026-10-01)
 *
 * The containers are gone: the no-wallet message and the rows sit on white, split by space and
 * a `D.Line` divider, because a door is not a settings card. The "Set a name" nudge lost its
 * blue wash; the highlight marks the words instead. The page carries its own 12/32 top and
 * bottom padding, the gaps above the nudge and above the badge are 24 and not 16, the empty
 * state takes a row's padding of its own, and the first door in a group carries the same row
 * padding as every other door.
 *
 * **ui/PORT-2026-10-01-4.md §3 collapsed four things into one.** The QR card, the address row
 * and the Share / Change-wallet buttons are now a single blue badge (§3.2): the code on white,
 * what it is under it, and — inside the same card — a white strip naming the wallet it belongs
 * to. "Scan to pay me" and "Works in any Solana wallet" are gone with them, and so is the only
 * place small white type sat on `D.Blue`; the one line left on the blue is "Solana wallet" at
 * 19sp bold, which is the floor 3.30:1 clears. The address shortens to first 6 … last 6 rather
 * than the 10 … 6 it showed when it was a row of its own with nothing competing for the width.
 *
 * The doors are three groups now (§3.3), not a tile grid's worth of screens: Activity,
 * Transactions, Rewards, Offers and Notifications are reached from the home page and no longer
 * duplicated here, Diagnostics folds into App settings, and "Connected wallets" is the only way
 * left to change wallet — which is why [onWallets] has to reach the wallets screen and not one
 * wallet's detail sheet. The foot is the version and the network and nothing else (§3.4).
 *
 * [SideSheet]'s geometry is deliberate and untouched: only type, colour and spacing changed.
 */
@Composable
internal fun ProfileDrawer(
    name: String,
    /** Null when no wallet is connected — the badge and the rows that need one say so. */
    address: String?,
    walletName: String?,
    isSeeker: Boolean,
    network: String,
    version: String,
    email: String?,
    onRename: () -> Unit,
    onEmail: () -> Unit,
    /**
     * The **wallets screen** — the list, where one can be picked.
     *
     * "Connected wallets" is the only way to change wallet since the Change-wallet button went
     * (§3.3), so a call site that opens one wallet's detail sheet leaves no way to switch.
     */
    onWallets: () -> Unit,
    /**
     * Unused: the Share button went with §3.2. Kept so the call site still compiles; delete the
     * two together.
     */
    onShare: (String) -> Unit,
    /**
     * Unused by any row: Activity, Transactions, Rewards, Offers and Notifications are gone
     * (§3.3). It survives only as [onRefer]'s fallback, and goes when that is wired.
     */
    onGo: (Tab) -> Unit,
    onDiagnostics: () -> Unit,
    onClose: () -> Unit,
    /**
     * Opens the Refer sheet (§2) over whatever is behind the panel.
     *
     * Defaulted to the old Referrals destination so the panel still reaches something until the
     * call site hands it the sheet — not left as a no-op, which would be a door to nowhere.
     */
    onRefer: () -> Unit = { onGo(Tab.REFERRALS) },
) {
    val context = LocalContext.current
    // The linked list is read from the wallet store rather than taken as a parameter: it is the
    // same SharedPreferences the caller's own list is a snapshot of, the panel is opened fresh
    // each time, and the one thing it genuinely cannot know — which wallet is in use — is
    // already hoisted as `address`. Only the count and the logo come from here.
    val linked = remember { runCatching { Wallets(context).all() }.getOrDefault(emptyList()) }
    val wallet = remember(linked, address) { linked.firstOrNull { it.address == address } }
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

            // -------------------------------------------------------------- the wallet badge
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
                WalletBadge(address, walletName ?: wallet?.name, wallet)
            }

            // -------------------------------------------------------------- doors
            // No heading on the first group: these three are not a category, they are the three
            // things somebody came in here to do.
            Spacer(Modifier.height(24.dp))
            DrawerRow("Connected wallets", linkedLine(linked.size, walletName ?: wallet?.name),
                onOpen = onWallets)
            Divider()
            DrawerRow("Help center", "Questions, and how to reach us")
            Divider()
            // A sheet, not a screen: the row opens it where it stands, over whatever is behind
            // the panel, rather than navigating away from it.
            DrawerRow("Refer and earn", "Bring a friend", onOpen = onRefer)

            Spacer(Modifier.height(Space.section))
            Text("Settings", color = D.Black, style = Type.heading)
            Spacer(Modifier.height(Space.lead))
            DrawerRow("Your name", name.ifBlank { "Not set" }, onOpen = onRename)
            Divider()
            // Diagnostics lost its own row and lives under App settings. There is no App
            // settings screen yet (PORT-2026-10-01-4 §6: the destination is not designed), and
            // diagnostics is the only thing behind this row that exists — so the row opens it
            // rather than nothing, which is also the one destination §3.3 actually names for it.
            DrawerRow("App settings", "Notifications, sounds, diagnostics", onOpen = onDiagnostics)
            Divider()
            DrawerRow("Codes emailed to", email ?: "Not set", onOpen = onEmail)

            Spacer(Modifier.height(Space.section))
            Text("About us", color = D.Black, style = Type.heading)
            Spacer(Modifier.height(Space.lead))
            // No line under any of these four, and no handler: §6 says their destinations are
            // not designed. They are drawn and inert rather than wired somewhere invented —
            // a door that opens on the wrong room is worse than one that has not been cut yet.
            DrawerRow("About AirPay")
            Divider()
            DrawerRow("Community")
            Divider()
            DrawerRow("Fees and charges")
            Divider()
            DrawerRow("Terms and conditions")

            // One line, and the version and the network are both read off something real:
            // `BuildConfig.VERSION_NAME` and `Chain.NETWORK`, which derives the cluster from the
            // endpoint the build actually talks to rather than from a string beside it.
            Spacer(Modifier.height(Space.section))
            Text("AirPay $version · $network", color = D.Quiet, style = CaptionTnum,
                modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * "3 linked · Phantom in use" — and less than that when less is known.
 *
 * Neither half is padded out with a guess: a count of zero means the store has not been read
 * (the caller may be showing a wallet this list has never seen), and an unnamed wallet drops
 * the second clause rather than printing "Wallet in use".
 */
private fun linkedLine(count: Int, inUse: String?): String? = when {
    count > 0 && inUse != null -> "$count linked · $inUse in use"
    count > 0 -> "$count linked"
    inUse != null -> "$inUse in use"
    else -> null
}

/**
 * The QR, what it is, and whose it is — one card.
 *
 * It was four things: a QR poster, a line under it, an address row between two rules, and a pair
 * of buttons. They all said the same thing twice. The badge keeps the parts that carry
 * information and drops the rest (PORT-2026-10-01-4 §3.2).
 *
 * Corners are concentric: 24 outside, 16 inside, the 8 of blue between them. The QR tile is
 * white and generously padded because the quiet zone is part of the code, and a QR sitting
 * straight on a coloured card is one a scanner may refuse. `solana:<address>` is unchanged —
 * the standard form, so it is not an AirPay code that happens to work elsewhere.
 */
@Composable
private fun WalletBadge(address: String, walletName: String?, wallet: Linked?) {
    val clipboard = LocalClipboardManager.current
    val uri = remember(address) { "solana:$address" }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(D.Blue).padding(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(D.White).padding(20.dp),
        ) {
            QrCode(uri, Modifier.fillMaxWidth().aspectRatio(1f))
        }
        // The only words left on the blue, and 19sp bold is the floor white on D.Blue clears
        // (3.30:1 passes as large text and nothing smaller). Its own 12 all round is what
        // separates the tile above from the strip below.
        Text(
            "Solana wallet", color = D.White, style = Type.heading,
            modifier = Modifier.padding(12.dp),
        )
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(D.White).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (wallet != null) {
                WalletMark(wallet)
                Spacer(Modifier.width(Space.inline))
            }
            Column(Modifier.weight(1f)) {
                Text(walletName ?: "Wallet", color = D.Quiet, style = Type.caption, maxLines = 1)
                Spacer(Modifier.height(Space.tight))
                // An identifier, read one character at a time: the mono face, not the money
                // face it used to borrow, and at body size rather than caption because this is
                // the line somebody checks against what their own wallet shows them. Six each
                // end, not the ten it showed as a row of its own — the logo and the Copy pill
                // are on this line now, and ten would truncate before the ellipsis could.
                Text(
                    "${address.take(6)}…${address.takeLast(6)}", color = D.Black,
                    style = Type.body.monospaced(), maxLines = 1,
                )
            }
            Spacer(Modifier.width(Space.inline))
            CopyPill { clipboard.setText(AnnotatedString(address)) }
        }
    }
}

/**
 * The wallet app's own mark, at 36 with a 9 corner.
 *
 * Fit, not crop: these icons are not all square (Backpack is 117x142, Seeker 681x607) and
 * filling a square box with them cut the artwork's edges off. A wallet with a flat mark ships
 * it as a tintable line icon instead, which is the other branch.
 */
@Composable
private fun WalletMark(w: Linked) {
    val shape = RoundedCornerShape(9.dp)
    val tint = w.iconTint
    if (tint == null) {
        Image(
            painterResource(w.iconRes), contentDescription = null,
            modifier = Modifier.size(36.dp).clip(shape),
            contentScale = ContentScale.Fit,
        )
    } else {
        HIcon(w.iconRes, tint, 36.dp)
    }
}

/**
 * `Copy`, as an outlined pill.
 *
 * The outline is what makes it read as a control on white — the strip it sits in is white too,
 * so there is no fill to lean on. 48dp of hit area around a pill that is shorter than that, and
 * the word is right beside the mark, so the mark stays decorative and unlabelled.
 */
@Composable
private fun CopyPill(onCopy: () -> Unit) {
    val copy = remember { MutableInteractionSource() }
    Box(
        Modifier.then(PressScale(copy)).heightIn(min = 48.dp)
            .clickable(copy, indication = null, onClick = onCopy)
            .semantics { contentDescription = "Copy address" },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.border(1.5.dp, D.Line, CircleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HiIcon(Hi.Copy, D.Black, 16.dp)
            Spacer(Modifier.width(8.dp))
            Text("Copy", color = D.Black, style = Type.caption)
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
 *
 * [detail] is optional — the About us rows carry no line — and so is [onOpen]. A row with a null
 * [onOpen] is drawn but takes no press and no tap: PORT-2026-10-01-4 §6 leaves several of these
 * destinations undesigned, and a row that scales under the finger and then does nothing claims
 * to be a door that is not one.
 */
@Composable
private fun DrawerRow(
    title: String, detail: String? = null, onOpen: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .then(if (onOpen == null) Modifier else PressScale(interaction))
            .fillMaxWidth()
            .then(
                if (onOpen == null) Modifier
                else Modifier.clickable(interaction, indication = null, onClick = onOpen),
            )
            .padding(vertical = Space.row),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = D.Black, style = Type.body)
            if (detail != null) {
                Spacer(Modifier.height(Space.tight))
                Text(detail, color = D.Quiet, style = Type.caption, maxLines = 1)
            }
        }
        Spacer(Modifier.width(Space.inline))
        // Quiet, and unlabelled: it is an affordance, not information, and the row's own title
        // is what a screen reader should read.
        // Hugeicons ArrowRight01 at 16dp, in the icon system's own stroke (1.5 at this size):
        // light and small, as the heavy hand-drawn pair it replaced was not. It mirrors in a
        // right-to-left layout by itself ([Hi.ArrowRight] is in the mirrored set).
        HiIcon(Hi.ArrowRight, D.Quiet, 16.dp)
    }
}
