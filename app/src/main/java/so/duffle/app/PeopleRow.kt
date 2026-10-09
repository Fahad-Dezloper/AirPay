package so.duffle.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * §3 — the people already met, under the wallet.
 *
 * Lifted out of MainActivity.kt so this section can be worked on without touching a 5,600-line
 * file. What the row DRAWS is ui/PORT-2026-10-01.md §4, ported 2026-10-01 from
 * ui/components/sections/People.tsx.
 */

/**
 * What to call somebody, and how much of it anyone actually checked.
 *
 * Four kinds of name reach this app and exactly one of them is evidence. Collapsing them into a
 * single string is how a payments app ends up showing a stranger's chosen words with the same
 * confidence as a fact — so they are kept apart here, and drawn differently.
 *
 * In order of preference:
 *
 *  1. **[Kind.Yours]** — what you named them. Never crossed a radio, never a claim anyone made
 *     about themselves, written by you while they were standing in front of you.
 *  2. **[Kind.Verified]** — a `.skr` or `.sol` this phone resolved from the chain. Nobody can
 *     forge it, but nobody vetted who bought it either: it is unforgeable, not attested. The
 *     badge says this address owns that name, and must never be read as "this is that person".
 *  3. **[Kind.Claimed]** — the name their phone sent us. Useful for remembering who someone
 *     was; worthless for deciding whether to trust them.
 *  4. **[Kind.Anonymous]** — the address, shortened. Honest.
 *
 * [domain] is kept apart from [shown] on purpose: when you have renamed somebody who also owns
 * a domain, a row can show your name for them *and* what the chain said, rather than one
 * silently hiding the other.
 */
internal data class Called(
    val shown: String,
    /** The on-chain name, when there is one. The only field that earns a tick. */
    val domain: String?,
    val kind: Kind,
    /**
     * True when the name came from a *different* wallet vouching for this one ([Vouch]).
     *
     * Still a fact — the signature was checked and the name still came off the chain — but a
     * different one: "this address belongs to whoever owns dezloper.skr" rather than "this
     * address owns dezloper.skr". Worth saying out loud where money moves.
     */
    val borrowed: Boolean = false,
    /**
     * True when [shown] is only the start of the address because the contact came from a scanned
     * QR with no name ([isPlaceholderName]). Drawn with the scan mark after it, so a nameless
     * person reads as "met by scanning" rather than as someone called "Scanned code".
     */
    val scanned: Boolean = false,
) {
    enum class Kind { Yours, Verified, Claimed, Anonymous }

    /** True only when the chain answered. Drives the tick, and nothing else does. */
    val verified: Boolean get() = domain != null
}

/**
 * Resolves a contact's name, asking the chain once per address.
 *
 * The lookup is mainnet and cached in [Names], so it settles quickly on a warm cache and never
 * blocks a row from drawing: it starts from what is known locally and upgrades in place when
 * the chain replies.
 */
@Composable
internal fun rememberCalled(contact: Contact): Called {
    val context = LocalContext.current
    val found by produceState(initialValue = Names.Found(null, false), contact.address) {
        // The link is re-verified on the way out of storage, so a tampered prefs file buys
        // nothing: an unverifiable link reads back as no link at all.
        value = Names.of(contact.address, Vouch.of(context, contact.address))
    }
    val domain = found.text
    return remember(domain, found.borrowed, contact.yours, contact.label, contact.address) {
        val claimed = contact.claimed()
        val yours = contact.yours
        when {
            !yours.isNullOrBlank() -> Called(yours, domain, Called.Kind.Yours, found.borrowed)
            domain != null -> Called(domain, domain, Called.Kind.Verified, found.borrowed)
            // Below yours and a domain on purpose: the placeholder is only what is left when
            // nobody gave a name, so any real name above has already won.
            !claimed.isNullOrBlank() && isPlaceholderName(claimed) ->
                Called(addressStart(contact.address), null, Called.Kind.Anonymous, false, scanned = true)
            !claimed.isNullOrBlank() -> Called(claimed, null, Called.Kind.Claimed, false)
            else -> Called(contact.short, null, Called.Kind.Anonymous, false)
        }
    }
}

/**
 * A person's own colour, for a face with no photo — what Android Contacts, Google Messages and
 * iOS do. Seven hues, each at the lightness where a white letter is 3.3:1 (the letter is large
 * text, so 3:1 is the bar).
 *
 * Chosen AWAY from the state colours — no red, orange, green or brand blue — so a face never
 * reads as paid, refused or act-now. These are **identity** colours, like the wallet brands:
 * deliberately NOT palette tokens, which is why they live here and not in Palette.kt, and why
 * they are private to this file. Nothing outside §3 People may use them.
 */
internal val FaceColours = listOf(
    Color(0xFF7887E2), Color(0xFFA076E7), Color(0xFFC26BD4), Color(0xFFE25CA4),
    Color(0xFF119C97), Color(0xFFB6840F), Color(0xFF798FAF),
)

/**
 * The face colour for a wallet **address** — never the name.
 *
 * Names change: you rename somebody, or a domain resolves a second after the row drew. The
 * address does not, so seeding off it is what makes a person keep one colour across a rename
 * and across a restart. The sandbox reproduces Java's `String.hashCode` so both sides land on
 * the same hue; in Kotlin `hashCode()` already *is* that.
 */
/**
 * `internal`, not private, because transaction rows draw the same face for the same person
 * (ui/PORT-2026-10-01-2.md §1.2) and one person must be one colour everywhere. The constraint
 * these were given still stands and is the reason they are not in `Palette.kt`: they are
 * IDENTITY colours, not palette tokens, and nothing may reuse them for anything else.
 */
internal fun faceColour(address: String) =
    FaceColours[(address.hashCode() and 0x7fffffff) % FaceColours.size]

/**
 * At most 13 characters, spaces not counted; past that the name is cut and "…" added.
 *
 * Two lines of caption in a quarter-width column hold 13 comfortably, and `maxLines = 2` is only
 * the backstop for wide glyphs — the cut is what keeps every column the same height. Spaces are
 * not counted because they cost almost no width and a two-word name is the common case.
 */
private const val NAME_MAX = 13

private fun fit(name: String): String {
    var counted = 0
    for (i in name.indices) {
        if (name[i] == ' ') continue
        counted++
        if (counted > NAME_MAX) return name.take(i).trimEnd() + "…"
    }
    return name
}

/**
 * A name with its provenance visible at a glance.
 *
 * The tick is the whole point: it appears for a chain-resolved name and for nothing else, so a
 * stranger can tell a fact from a claim without reading any help text. A name we were merely
 * told is drawn in the quiet colour for the same reason.
 *
 * [tick] is a parameter rather than `called.verified` because in §3 People a verified **Seeker**
 * wears the tick on its face instead (see [People]), and two ticks for one fact would read as
 * two facts.
 */
@Composable
internal fun NameLine(
    called: Called,
    modifier: Modifier = Modifier,
    centred: Boolean = false,
    tick: Boolean = called.verified,
) {
    Row(
        modifier,
        horizontalArrangement = if (centred) Arrangement.Center else Arrangement.Start,
        // Top, not centre: the name wraps to two lines, and a tick floating beside the middle
        // of a two-line name is attached to neither of them.
        verticalAlignment = if (centred) Alignment.Top else Alignment.CenterVertically,
    ) {
        // The fourth tier is not a name at all, it is an address: mono, like every other
        // address in the app, so the FACE is a fifth thing saying which tier this is. The
        // size is the caption step for all four — it was 12, below the scale.
        val anonymous = called.kind == Called.Kind.Anonymous
        Text(
            fit(called.shown),
            color = if (called.kind == Called.Kind.Claimed || anonymous) D.Quiet else D.Black,
            style = if (anonymous) Type.mono else Type.caption,
            textAlign = if (centred) TextAlign.Center else TextAlign.Start,
            maxLines = if (centred) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (called.scanned) {
            // The same mark as the "Scan any QR" button: no name, only a scan.
            Spacer(Modifier.width(Space.tight))
            HiIcon(Hi.Scan, D.Quiet, 14.dp)
        }
        if (tick) {
            Spacer(Modifier.width(Space.tight))
            // 16 is the icon size beside a caption, so the tick carries the line's own weight.
            HiIcon(Hi.Verified, D.BlueInk, 16.dp)
        }
    }
}

/**
 * The people already met, under the wallet.
 *
 * It replaces both the dark "Send to" pill and the "hold your phone to theirs" panel. The pill
 * hid these behind three initials, and the panel explained a gesture to someone who has already
 * done it — this shows who can be paid and lets them be paid, which is what both were for.
 *
 * Each face names them the best way available: their `.sol` domain if they have one, since that
 * is the only one of the three they chose for themselves, then the phone's label, then the
 * address.
 *
 * Four columns, wrapping to as many rows as the contacts need (see [COLUMNS]).
 */
@Composable
internal fun People(
    list: List<Contact>,
    waiting: (String) -> Int,
    onPick: (Contact) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // The section heading every other Home section uses (§4.2), not the small uppercase
        // label — "People" is a section like "Pay a bill", not a caption over a strip.
        Text("People", color = D.Black, style = Type.heading)
        Spacer(Modifier.height(Space.lead))

        // Four equal columns across the content width with NO gap (§4.3): each person fills
        // their column and is centred in it, so the row is even from both screen edges and the
        // faces line up with nothing else on Home needing to know how wide a face is.
        list.chunked(COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { c ->
                    Person(c, waiting = waiting, onPick = onPick, modifier = Modifier.weight(1f))
                }
                // A short last row still gets quarter-width columns, so two faces sit where
                // the first two of four would rather than spreading across the screen.
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * How many people fit across the content width.
 *
 * The row WRAPS past four rather than stopping at four. PORT-2026-10-01.md §7 left this open
 * — "the sandbox passes up to five; a fifth would wrap to a second row. What happens past four
 * is not decided" — and a cap was the safe reading while it was open. It is decided now, and
 * the sandbox had already answered it: `grid grid-cols-4` with five items wraps. Capping
 * instead dropped the fifth contact off Home entirely, and with §4.1's removal of the "All
 * activity" link there was no other route to them.
 */
private const val COLUMNS = 4

/**
 * One person: the coloured face, its two marks, and the name.
 *
 * The whole column is one press target (§4.3) — corner 14, vertical padding 4, press scale .97
 * — because every part of it points at the same person.
 */
@Composable
private fun Person(
    c: Contact,
    waiting: (String) -> Int,
    onPick: (Contact) -> Unit,
    modifier: Modifier = Modifier,
) {
    val called = rememberCalled(c)
    val interaction = remember { MutableInteractionSource() }
    // A Seeker owner whose name the chain verified wears the tick on the face, and so not
    // beside the name: one fact, one mark.
    val seekerTick = c.seeker && called.verified
    Column(
        modifier.then(PressScale(interaction)).clip(RoundedCornerShape(14.dp))
            .clickable(interaction, indication = null) { onPick(c) }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
            // The initial, because there is no picture of a person to show and a stock
            // silhouette would only pretend there was. It sits on the person's OWN colour
            // (§4.4) with no ring and no border: the old white disc needed a ring to have an
            // edge at all, and the 2dp blue one that marked a Seeker is gone because the tick
            // below already says "verified". The letter is 0.38 of the disc — a fraction of
            // the shape, not a step on the type scale.
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(faceColour(c.address)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    called.shown.take(1).uppercase(),
                    color = D.White,
                    fontSize = (54 * 0.38f).sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Two marks, in two fixed corners, so they can never collide (§4.5).
            //
            // What is outstanding with this person, on this person, at the top end where a
            // count is looked for. The home page used to carry one "12 waiting" row for
            // everybody, which said how much was open and never with whom.
            val open = waiting(c.address)
            if (open > 0) {
                // On ink, not on blue: an 11sp white number on #008FFF is 3.30:1 and fails;
                // on ink it is 12.46:1. The 2dp white ring is what separates it from the
                // coloured face, which a bare ink pill on a mid-tone hue would not.
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .clip(CircleShape).background(D.White).padding(2.dp)
                        .height(19.dp).defaultMinSize(minWidth = 19.dp)
                        .clip(CircleShape).background(D.Black)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (open > 9) "9+" else "$open", color = D.White, style = CountStyle)
                }
            }

            // The tick, on the opposite corner from the count and pushed 2dp outside the face
            // so it reads as a mark ON the person rather than a hole punched in them.
            if (seekerTick) {
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp)
                        .size(22.dp).clip(CircleShape).background(D.White),
                    contentAlignment = Alignment.Center,
                ) {
                    HiIcon(Hi.Verified, D.BlueInk, 22.dp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        // The inline tick only for a verified name on a phone that is not a Seeker; a verified
        // Seeker already wears it on the face.
        NameLine(called, Modifier.fillMaxWidth(), centred = true, tick = called.verified && !seekerTick)
    }
}


/**
 * A person's face: their initial in white on the colour their address hashes to.
 *
 * Seeded on the ADDRESS and never the name, because names change — a rename, or a domain
 * resolving later — and the address does not. So somebody keeps their colour across a rename,
 * and looks the same in §3 and in a transaction row.
 *
 * The letter is 0.38 x the disc, which is the avatar's own composition rather than a step on
 * the type scale (the same ratio `Initial` uses).
 */
@Composable
internal fun FaceDisc(letter: String, address: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).clip(CircleShape)
            .background(address?.let(::faceColour) ?: D.Neutral),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter.take(1).uppercase().ifBlank { "?" }, color = D.White,
            fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.Bold, fontFamily = Ui,
        )
    }
}
