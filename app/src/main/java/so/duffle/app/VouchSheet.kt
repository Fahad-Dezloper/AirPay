package so.duffle.app

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/**
 * Where a wallet stands on carrying a name.
 *
 * A `.skr` or `.sol` lives on one address, so any other wallet is a stranger to the person being
 * paid. [Vouch] fixes that with a signature from the wallet that owns the name. This is the
 * read side: given the wallets on this phone, which of four situations is this one in?
 *
 * Computed on the phone from what it already stores — the cached name per wallet and the
 * verified links. Nothing here touches the network, so the Wallets screen can show it instantly.
 */
internal sealed interface Standing {
    /** Wallets that show the name — the ones this wallet could also lend it to. */
    data class Owns(val name: String, val lent: List<Lent>) : Standing

    /** Another of the person's wallets vouched for this one. [owner] is null if it was since removed. */
    data class Borrowed(val name: String?, val owner: Linked?, val link: Vouch.Link, val daysLeft: Long) : Standing

    /** Another wallet owns a name and has not vouched for this one yet. */
    data class Offered(val owner: Linked, val name: String) : Standing

    /** None of the person's wallets owns a name. */
    data object Bare : Standing

    /** Shown with the blue tick: the name is on this wallet, or carried to it by a valid link. */
    val verified: Boolean get() = this is Owns || this is Borrowed

    /** A borrowed name that lapses within [SOON_DAYS], which is when it is worth re-signing. */
    val expiringSoon: Boolean get() = this is Borrowed && daysLeft <= SOON_DAYS

    /** The row's title and the line under it — plain words, and the state said, not hinted at. */
    fun row(): Pair<String, String> = when (this) {
        is Owns -> "Your name" to when {
            lent.isEmpty() -> name
            lent.all { it.link != null } -> "$name · lent to your other wallets"
            else -> "$name · lend it to your other wallets"
        }
        is Borrowed -> "Seen as ${name ?: "your name"}" to
            if (daysLeft <= SOON_DAYS) "Lapses in ${days(daysLeft)} · tap to renew"
            else "Vouched until ${dateOf(link.expiresOn)}"
        is Offered -> "Use your name here" to "$name can vouch for this wallet"
        Bare -> "Your name" to "No wallet of yours has a .skr or .sol yet"
    }

    companion object {
        const val SOON_DAYS = 30L
    }
}

/** One other wallet and whether this wallet's name has been lent to it. */
internal data class Lent(val wallet: Linked, val link: Vouch.Link?) {
    fun daysLeft(today: LocalDate = LocalDate.now(ZoneOffset.UTC)): Long? =
        link?.let { ChronoUnit.DAYS.between(today, it.expiresOn) }
}

internal fun standingOf(ctx: Context, w: Linked, all: List<Linked>): Standing {
    val today = LocalDate.now(ZoneOffset.UTC)
    val mine = Identity.domain(ctx, w.address)
    if (mine != null) {
        // A wallet that already has a name of its own has nothing to borrow, so it is not offered one.
        val others = all.filter { it.address != w.address && Identity.domain(ctx, it.address) == null }
        return Standing.Owns(mine, others.map { o ->
            Lent(o, Vouch.of(ctx, o.address)?.takeIf { it.owner == w.address })
        })
    }
    val link = Vouch.of(ctx, w.address)
    if (link != null) {
        return Standing.Borrowed(
            name = Identity.domain(ctx, link.owner),
            owner = all.firstOrNull { it.address == link.owner },
            link = link,
            daysLeft = ChronoUnit.DAYS.between(today, link.expiresOn),
        )
    }
    val named = Identity.named(ctx, all.map { it.address }.filter { it != w.address })
    if (named != null) {
        all.firstOrNull { it.address == named.first }?.let { return Standing.Offered(it, named.second) }
    }
    return Standing.Bare
}

private fun days(n: Long) = if (n <= 0) "a day" else if (n == 1L) "1 day" else "$n days"

private fun dateOf(d: LocalDate): String =
    d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** A wallet's own mark, however the wallet draws it. */
@Composable
internal fun WalletMark(w: Linked, size: androidx.compose.ui.unit.Dp) {
    val tint = w.iconTint
    if (tint == null) {
        Image(painterResource(w.iconRes), null, Modifier.size(size).clip(RoundedCornerShape(size / 4)),
            contentScale = ContentScale.Fit)
    } else HIcon(w.iconRes, tint, size)
}

/**
 * Vouching, as a sheet that says what is true and offers only what can work.
 *
 * What a vouch is, in the words the person will meet it in: **one wallet signs "this other wallet
 * is mine"** and the phone being paid checks that signature against the name's owner. It costs
 * nothing, sends no transaction, and lapses after [Vouch.VALID_DAYS] days.
 *
 * What it cannot do is said too. A signature cannot be taken back from a phone that already
 * saw it, so "stop using it" only makes this phone stop presenting the name; the statement names
 * exactly one wallet and expires on its own, which is what bounds it.
 */
@Composable
internal fun VouchSheet(
    w: Linked,
    standing: Standing,
    /** True while a wallet app is being asked to sign. */
    linking: Boolean,
    /** [owner] signs a statement naming [target]. */
    onVouch: (owner: Linked, target: Linked) -> Unit,
    /** This phone stops presenting the borrowed name from [target]. */
    onForget: (target: Linked) -> Unit,
    onClose: () -> Unit,
) {
    Tray(onClose = onClose) {
        Spacer(Modifier.height(Space.lead))
        when (standing) {
            is Standing.Owns -> {
                NameHeading(standing.name)
                Spacer(Modifier.height(Space.tight))
                Text(
                    "This wallet owns the name, so everyone you tap from it sees it with a tick. " +
                        "Lend it to your other wallets and they carry it too.",
                    color = D.Quiet, style = Type.caption,
                )
                Spacer(Modifier.height(Space.lead))
                if (standing.lent.isEmpty()) {
                    Text("You have no other wallets to lend it to yet.", color = D.Quiet, style = Type.body)
                }
                standing.lent.forEachIndexed { i, l ->
                    val left = l.daysLeft()
                    val soon = left != null && left <= Standing.SOON_DAYS
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = Space.tight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WalletMark(l.wallet, 36.dp)
                        Spacer(Modifier.width(Space.inline))
                        Column(Modifier.weight(1f)) {
                            Text(l.wallet.username ?: l.wallet.name, color = D.Black, style = Type.body, maxLines = 1)
                            Text(
                                when {
                                    l.link == null -> "Not lent yet"
                                    soon -> "Lapses in ${days(left!!)}"
                                    else -> "Lent until ${dateOf(l.link.expiresOn)}"
                                },
                                color = if (soon) D.OrangeInk else D.Quiet, style = Type.caption,
                            )
                        }
                        SmallAction(
                            when { linking -> "Waiting…"; l.link == null -> "Lend"; else -> "Renew" },
                            enabled = !linking,
                        ) { onVouch(w, l.wallet) }
                    }
                    if (i < standing.lent.lastIndex) RowLine()
                }
                Spacer(Modifier.height(Space.lead))
                FinePrint()
            }

            is Standing.Borrowed -> {
                NameHeading(standing.name ?: "Your name")
                Spacer(Modifier.height(Space.tight))
                Text(
                    "${standing.owner?.let { it.username ?: it.name } ?: "Another of your wallets"} " +
                        "vouched for this wallet, so the people you tap from it see your name with a " +
                        "tick. Good until ${dateOf(standing.link.expiresOn)}.",
                    color = D.Quiet, style = Type.caption,
                )
                Spacer(Modifier.height(Space.lead))
                val owner = standing.owner
                if (owner != null) {
                    PrimaryButton(
                        if (linking) "Waiting for ${owner.username ?: owner.name}…" else "Renew for another ${Vouch.VALID_DAYS} days",
                        enabled = !linking,
                        onClick = { onVouch(owner, w) },
                    )
                    Spacer(Modifier.height(Space.inline))
                } else {
                    Text(
                        "The wallet that signed it is no longer on this phone, so it can't be renewed from here.",
                        color = D.Quiet, style = Type.caption,
                    )
                    Spacer(Modifier.height(Space.inline))
                }
                SecondaryButton("Stop using it here", { onForget(w) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(Space.lead))
                FinePrint(forget = true)
            }

            is Standing.Offered -> {
                Text("Use your name here", color = D.Black, style = Type.title)
                Spacer(Modifier.height(Space.tight))
                Text(
                    "Right now ${w.short} is just an address to whoever you tap. ${standing.name} can " +
                        "vouch for it with one approval: nothing is spent, and the people you tap from " +
                        "this wallet see your name with a tick.",
                    color = D.Quiet, style = Type.caption,
                )
                Spacer(Modifier.height(Space.lead))
                PrimaryButton(
                    if (linking) "Waiting for ${standing.name}…" else "Use ${standing.name} here",
                    enabled = !linking,
                    onClick = { onVouch(standing.owner, w) },
                )
                Spacer(Modifier.height(Space.lead))
                FinePrint()
            }

            Standing.Bare -> {
                Text("Your name", color = D.Black, style = Type.title)
                Spacer(Modifier.height(Space.tight))
                Text(
                    "None of your wallets owns a .skr or .sol yet, so there is no name to carry. " +
                        "Every Seeker comes with a .skr, and any wallet that has one can lend it to " +
                        "the rest. Link that wallet here and this will offer to.",
                    color = D.Quiet, style = Type.caption,
                )
                Spacer(Modifier.height(Space.lead))
            }
        }
    }
}

@Composable
private fun NameHeading(name: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = D.Black, style = Type.title, maxLines = 1)
        Spacer(Modifier.width(Space.tight))
        HiIcon(Hi.Verified, D.BlueInk, 24.dp)
    }
}

@Composable
private fun FinePrint(forget: Boolean = false) {
    Text(
        if (forget) {
            "A signature can't be taken back from a phone that already saw it. It names only this " +
                "wallet and lapses on its own date; stopping makes this phone stop showing it."
        } else {
            "Each approval is one signature in the wallet that owns the name. It sends no " +
                "transaction, costs nothing, and lasts ${Vouch.VALID_DAYS} days."
        },
        color = D.Quiet, style = Type.caption,
    )
}

/** A compact action at the end of a row — a full-width button would shout over a list of wallets. */
@Composable
private fun SmallAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (enabled) D.BlueInk else D.Quiet, style = Type.body,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 14.dp),
    )
}

