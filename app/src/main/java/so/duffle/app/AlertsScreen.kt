package so.duffle.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.lazy.itemsIndexed
import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.core.view.WindowCompat
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.drawBehind
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * §7 — notifications: the icon says who or what, a badge says what happened.
 *
 * Lifted out of MainActivity.kt so this surface can be worked on without touching a
 * 5,000-line file. Rebuilt for ui/PORT-2026-10-02.md §7.
 */

/** Every row's icon sits in this box, so every title starts at the same x. */
private val ALERT_ICON = 40.dp

/** The badge on the icon's corner: 20 across, pushed 4 outside, a 2 white ring, a 12 mark. */
private val BADGE = 20.dp
private val BADGE_OUT = 4.dp
private val BADGE_RING = 2.dp
private val BADGE_MARK = 12.dp

/** The sandbox's `gap-2` between the things at the row's end: the amount, the time, the dot. */
private val END_GAP = 8.dp

/**
 * The notification panel: what has happened, and what is still waiting on someone.
 *
 * One list rather than a badge per section. A request waiting on you, a decline, a message and
 * a recharge that has not arrived are all "something you would want to know", and splitting
 * them by which part of the app they came from asks the reader to know that first.
 *
 * ### §7: the icon says WHO or WHAT, and a badge says what happened
 *
 * The tinted disc that used to carry a glyph per `Alerts.Kind` is gone. It spent the one place
 * on the row that could have identified the other party on repeating the kind — which the title
 * already says — so a decline from one person and a decline from another drew the same picture.
 * Now the row takes the transaction rows' rule (ui/PORT-2026-10-01-2.md §1.2):
 *
 * | notification | icon (40) | badge |
 * |---|---|---|
 * | somebody paid you | their face, seeded on their address | `D.Success` + tick |
 * | somebody declined | their face | `D.Danger` + cross |
 * | an order arrived | its category's two-colour mark, optically sized (26 in the 40) | `D.Success` + tick |
 * | a new offer | the app's own icon, round | none |
 *
 * **A badge is not drawn unless something is settled.** An order still on its way is the same
 * kind of line as one that arrived, and a tick on it would say it is here. Asked, deferred,
 * withdrawn and messages are states, not outcomes, and carry no badge either — a badge on every
 * row says nothing.
 *
 * **Red, here, is sanctioned** (§10): a declined ask is red wherever it appears, including this
 * badge, and it is a cross rather than a dash. Expired and "nothing was sent" stay grey.
 *
 * Unread marking is unchanged: the title sits on the highlight — a marker pen, at 10.83:1 — and
 * the blue dot stays as a graphic so "new" is never told by colour alone.
 */
@Composable
internal fun AlertsTab(
    list: List<Alerts.Alert>, seenAt: Long,
    /** The phone would swallow anything we posted. Draws [AllowNotifications] above the list. */
    notifOff: Boolean = false,
    onAllow: () -> Unit = {},
    onOpen: (Alerts.Alert) -> Unit,
) {
    if (list.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            if (notifOff) AllowNotifications(onAllow)
            // Nothing to list, so nothing to align a list to: the message sits in the middle of
            // the screen. The bottom padding is the title row above it, so it is the SCREEN's
            // middle and not the middle of what is left under the title.
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = Space.section)
                    .padding(bottom = if (notifOff) 0.dp else 96.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Nothing new", color = D.Black, style = Type.body, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Space.tight))
                Text("Payments, requests, replies, messages, rewards, offers and anything you've bought show up here.",
                    color = D.Quiet, style = Type.caption, textAlign = TextAlign.Center)
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), state = rememberHeldList("alerts")) {
        // In the list rather than pinned above it, so it scrolls away with the rows: what has
        // already happened is listed whether or not the phone is allowed to announce the next.
        if (notifOff) item(key = "allow") {
            AllowNotifications(onAllow)
            Spacer(Modifier.height(Space.tight))
        }
        itemsIndexed(list) { i, a ->
            val fresh = a.at > seenAt
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable { onOpen(a) }.padding(vertical = Space.row),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(ALERT_ICON), contentAlignment = Alignment.Center) {
                    AlertIcon(a)
                    AlertBadge(a)
                }
                Spacer(Modifier.width(Space.inline))
                Column(Modifier.weight(1f)) {
                    // Unread does not wash the row blue (no tints): the title sits on the
                    // highlight, which is what a marker pen is for, at 10.83:1.
                    Text(
                        a.title, color = D.Black, style = Type.body, maxLines = 2,
                        modifier = if (fresh) {
                            Modifier.clip(RoundedCornerShape(4.dp)).background(D.Highlight)
                                .padding(horizontal = 4.dp)
                        } else Modifier,
                    )
                    Spacer(Modifier.height(Space.tight))
                    // Money that arrived is the one line worth colouring. A decline's line
                    // says nothing was paid, and saying it in red would make the sentence
                    // read as an error when it is an ordinary answer — the red on this row
                    // is the badge, which says what happened, and that is enough.
                    Text(
                        a.detail,
                        color = if (a.kind == Alerts.Kind.RECEIVED ||
                            (a.kind == Alerts.Kind.MONEY && a.done)) D.SuccessInk else D.Quiet,
                        style = Type.caption, maxLines = 2,
                    )
                }
                Spacer(Modifier.width(Space.inline))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // A declined ask still says its number, struck through: the line under the
                    // title says nothing was paid, and the amount is what makes that mean
                    // something. Struck rather than dropped, because "0.50 USDC" removed
                    // leaves the reader to remember what was asked for.
                    a.amount?.takeIf { a.kind == Alerts.Kind.DECLINED }?.let {
                        Text(
                            it, color = D.Quiet,
                            style = Type.caption.figure()
                                .copy(textDecoration = TextDecoration.LineThrough),
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(END_GAP))
                    }
                    // Tabular, but not the figure face: "4m ago" is a sentence with a digit in
                    // it, and switching face mid-sentence reads as a rendering bug. Tabular
                    // only so 9m → 10m does not shift the row's end sideways.
                    Text(
                        ago(a.at), color = D.Quiet,
                        style = Type.caption.copy(fontFeatureSettings = "tnum"), maxLines = 1,
                    )
                    if (fresh) {
                        Spacer(Modifier.width(END_GAP))
                        // The dot stays as a graphic, so "new" is not told by colour alone —
                        // the marker behind the title says it too.
                        Box(
                            Modifier.size(8.dp).clip(CircleShape).background(D.Blue)
                                .semantics { contentDescription = "Unread" },
                        )
                    }
                }
            }
            if (i < list.lastIndex) RowLine()
        }
    }
}

/**
 * The one place the app asks to be allowed to notify, and only while it is not.
 *
 * It used to be a banner on Home, under the balance, on every launch. Home is where money is
 * looked at, and a standing complaint there reads as something being wrong with the money. This
 * page is where the question belongs: it is the list of what a notification would have said, so
 * what is being missed is on screen right under the button.
 *
 * It says what is lost in the terms of this app (being paid, being asked, a reward) and has one
 * action. It goes away by itself the moment notifications are allowed; nothing dismisses it,
 * because dismissing it would leave the phone silent with no way back to the question.
 */
@Composable
private fun AllowNotifications(onAllow: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = Space.tight, bottom = Space.lead)
            .clip(shape).border(1.5.dp, D.Line, shape).padding(Space.row),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HiIcon(Hi.Bell, D.Black, 22.dp)
            Spacer(Modifier.width(Space.inline))
            Text("Notifications are off", color = D.Black, style = Type.heading)
        }
        Spacer(Modifier.height(Space.tight))
        Text(
            "You will not hear when someone pays you, asks you for money or when a reward arrives.",
            color = D.Quiet, style = Type.caption,
        )
        Spacer(Modifier.height(Space.row))
        PrimaryButton("Allow notifications", onAllow)
    }
}

/**
 * What the notification is ABOUT, in a 40 box (§7).
 *
 * Three answers, in the order the sandbox asks them (`components/screens/Lists.tsx` → `Alerts`):
 * a person is their face; an app is its own icon; a shop order is the category it came off.
 * Nothing falls through — every `Alerts.Kind` is one of a person, an order, an offer, or (for
 * money in a wallet and rewards, which have nobody behind them) the thing itself.
 */
@Composable
private fun AlertIcon(a: Alerts.Alert) {
    when {
        // A shelf always resolves: `History.spendFor` falls back to OTHER, so an order bought
        // before categories were recorded draws the "More" mark rather than nothing.
        // The brand that was bought, when the order kept its logo. An order from before logos
        // were kept, or one whose logo will not load, draws its shelf's mark as it always has.
        a.kind == Alerts.Kind.ORDER -> {
            val logo = a.iconUrl?.let { rememberLogo(it) }
            if (logo != null) {
                Image(
                    logo, contentDescription = null,
                    modifier = Modifier.size(ALERT_ICON).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                CategoryMark(
                    CatMark.forCategory(spendLabel(History.spendFor(a.category.orEmpty())))
                        ?: CatMark.More,
                    ALERT_ICON, CatMark.ROW_OPTICAL,
                )
            }
        }
        // The app's own icon, round and cropped to fill. `/v1/offers` sends none today, so in
        // practice this falls through to the app's initial on its own colour — the same
        // fallback `OfferFace` documents, and for the same reason: a name-to-asset map would
        // be right for the sample apps and wrong for the next one, while looking like it worked.
        a.kind == Alerts.Kind.OFFER -> {
            val logo = a.iconUrl?.let { rememberLogo(it) }
            if (logo != null) {
                Image(
                    logo, contentDescription = null,
                    modifier = Modifier.size(ALERT_ICON).clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                FaceDisc(a.who.orEmpty(), a.seed, ALERT_ICON)
            }
        }
        // Money in a wallet and a reward have nobody behind them to draw: the transfer came from
        // an address this phone may never have met, and a reward comes from the app. So the
        // icon is the WHAT, the same glyph Home uses for it, on the neutral fill. Not a tinted
        // disc per kind: the badge still says what happened.
        a.kind == Alerts.Kind.MONEY || a.kind == Alerts.Kind.REWARD -> Box(
            Modifier.size(ALERT_ICON).clip(CircleShape).background(D.Fill),
            contentAlignment = Alignment.Center,
        ) {
            HiIcon(
                when {
                    a.kind == Alerts.Kind.REWARD -> Hi.Gift
                    a.topic == "out" -> Hi.ArrowUpRight
                    else -> Hi.Receive
                },
                D.Black, 22.dp,
            )
        }
        // Their face, seeded on the address so one person is one colour everywhere. A null
        // seed is an entry old enough not to have recorded who it was with; `FaceDisc` draws
        // the neutral disc for it rather than guessing a colour.
        else -> FaceDisc(a.who.orEmpty(), a.seed, ALERT_ICON)
    }
}

/**
 * The badge on the icon's bottom end corner: what happened.
 *
 * 20 across, pushed 4 outside the icon, a 2 white ring so it reads against a face of any
 * colour, and a 12 mark at the disc stroke — the weight a mark carrying meaning takes, because
 * every fill here shares one brightness and the SHAPE is what a colour-blind eye reads.
 *
 * Null for anything unsettled, which is most rows.
 */
@Composable
private fun BoxScope.AlertBadge(a: Alerts.Alert) {
    val fill: Color
    val mark: Hi
    when {
        a.done -> { fill = D.Success; mark = Hi.Tick }
        // §10: a declined ask is red wherever it appears, and it is a CROSS, not a dash. The
        // dash means "nothing happened"; somebody actively said no.
        a.kind == Alerts.Kind.DECLINED -> { fill = D.Danger; mark = Hi.Close }
        else -> return
    }
    Box(
        Modifier.align(Alignment.BottomEnd).offset(x = BADGE_OUT, y = BADGE_OUT)
            .size(BADGE).clip(CircleShape).background(D.White).padding(BADGE_RING)
            .clip(CircleShape).background(fill),
        contentAlignment = Alignment.Center,
    ) { HiIcon(mark, D.White, BADGE_MARK, disc = true) }
}

/**
 * A remote logo, loaded once and kept.
 *
 * [LogoCache] is the app's one image cache (PopupUi.kt) and already fronts this exact CDN form;
 * this is the `produceState` wrapper the shop list uses, so a logo that is already in the cache
 * draws on the first frame instead of flashing an empty box.
 */
@Composable
private fun rememberLogo(url: String): androidx.compose.ui.graphics.ImageBitmap? {
    val logo by produceState<androidx.compose.ui.graphics.ImageBitmap?>(LogoCache.get(url), url) {
        if (value == null) value = withContext(Dispatchers.IO) { LogoCache.load(url) }
    }
    return logo
}
