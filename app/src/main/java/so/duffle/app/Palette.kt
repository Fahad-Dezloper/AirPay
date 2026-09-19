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
    val Quiet = Color(0xFF999999)
    val Blue = Color(0xFF008FFF)
    val Line = Color(0xFFEBEBEB)
    val Fill = Color(0xFFF4F4F4)
    // Status colours are placeholders — standard ones, picked properly as the screens are built.
    val Success = Color(0xFF34C759)
    val Danger = Color(0xFFFF3B30)
}
