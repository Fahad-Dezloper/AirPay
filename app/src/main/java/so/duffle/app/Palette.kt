package so.duffle.app

import androidx.compose.ui.graphics.Color

// One palette for the app and the tap popup, so the popup reads as part of Tap rather than a
// system dialog that happened to appear.
internal val Ink = Color(0xFF0B0D10)
internal val Panel = Color(0xFF14181E)
internal val Line = Color(0xFF262E38)
internal val Fg = Color(0xFFE8EDF3)
internal val Dim = Color(0xFF8A96A4)
internal val Accent = Color(0xFF14F195)
internal val Warn = Color(0xFFE5534B)

// The light palette, decided 2026-09-19 (DUFFLE_DESIGN.md §3). The tap popup uses it now; the main
// screen still uses the dark tokens above until it is redesigned.
internal object D {
    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF343433)
    // Was #999999: 2.85:1 on white and 2.59:1 on Fill, too faint for the addresses, timestamps
    // and "Nothing was sent" lines it carries. #6E6E6E is 5.1:1 on white, 4.64:1 on Fill and
    // 4.6:1 on a 12% tint — WCAG AA for small text on every ground it sits on.
    val Quiet = Color(0xFF6E6E6E)
    val Blue = Color(0xFF008FFF)
    val Line = Color(0xFFEBEBEB)
    val Fill = Color(0xFFF4F4F4)
    // Both were raised so a white icon holds on them. #34C759 carried white at 2.22:1, which is
    // not a contrast ratio so much as a guess, and the disc's icon is what a colour-blind
    // person reads — so it has to be there and it has to be visible.
    val Success = Color(0xFF00A344)
    val Danger = Color(0xFFFF4B45)

    // Act now, and nothing else. The approval window and its countdown — the one place where
    // doing nothing loses something.
    val Orange = Color(0xFFE46A00)
    val OrangeInk = Color(0xFFB35400)

    // Nothing happened. The nothing-sent disc, which must not look like a failure, because no
    // money moved and none was going to.
    val Neutral = Color(0xFF8D8D8D)

    // A marker pen, and only ever behind ink text (10.83:1). Alone on white it is invisible
    // at 1.15:1 — never a dot, a line, a ring or a border.
    val Highlight = Color(0xFFD7FF3A)

    // The one dark surface: the head of a token card, and the price pill on it. NOT a card
    // colour for anything else — every other surface in this app is white. White on it is
    // 18.7:1. It was a raw 0xFF0E1013 written out at both call sites, which is the thing §1
    // rule 5 forbids: a hex in a composable is a colour dark mode can never reach.
    val Night = Color(0xFF0E1013)

    // Blue, Success and Danger are FILL colours: buttons, the pay bar, discs, drawn marks, tints.
    // As text they fail AA (3.3, 2.22 and 3.55:1 on white). Each has an ink — the same hue
    // darkened until it reads — for text and small icons. Every ink clears 4.5:1 on white, on
    // Fill, and on its own 12% tint. Where a colour arrives as a parameter, use `.ink`.
    val BlueInk = Color(0xFF0066CC)
    val SuccessInk = Color(0xFF1F7A35)
    val DangerInk = Color(0xFFD70015)
}

/** The text-safe shade of a fill colour. Anything without one is already legible as itself. */
internal val Color.ink: Color
    get() = when (this) {
        D.Blue -> D.BlueInk
        D.Success -> D.SuccessInk
        D.Danger -> D.DangerInk
        D.Orange -> D.OrangeInk
        else -> this
    }
