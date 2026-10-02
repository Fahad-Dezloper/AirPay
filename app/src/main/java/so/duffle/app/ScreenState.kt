package so.duffle.app

import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView

/**
 * Where each screen was scrolled to, held above the screens themselves.
 *
 * **Why it exists.** Screens here are switched by `if` branches, so opening one removes the
 * previous from composition — and a scroll state made with `rememberScrollState()` inside it dies
 * with it. Going back rebuilt the list at the top: the person tapped the fortieth row, read the
 * page, pressed back, and was at row one. Google Pay, and every list app that feels settled, puts
 * you back exactly where you were. The state has to outlive the screen, so it lives here.
 *
 * Keyed by screen (`"shop:More"`, `"rewards"`). [keepOnly] drops everything but Home's once Home is
 * showing again, so opening a screen afresh starts at its top — the only time that is expected.
 *
 * Process-wide on purpose: it holds positions, not data, and losing it with the process is right.
 */
internal object ScrollHold {
    private val scrolls = mutableMapOf<String, ScrollState>()
    private val lists = mutableMapOf<String, LazyListState>()

    fun scroll(key: String): ScrollState = scrolls.getOrPut(key) { ScrollState(0) }
    fun list(key: String): LazyListState = lists.getOrPut(key) { LazyListState() }

    /** Forget every screen's position except those named — called when Home is back in front. */
    fun keepOnly(vararg keys: String) {
        scrolls.keys.retainAll(keys.toSet())
        lists.keys.retainAll(keys.toSet())
    }
}

/** A scroll position that survives its screen leaving composition. See [ScrollHold]. */
@Composable
internal fun rememberHeldScroll(key: String): ScrollState = ScrollHold.scroll(key)

/** [rememberHeldScroll] for a lazy list. */
@Composable
internal fun rememberHeldList(key: String): LazyListState = ScrollHold.list(key)

/**
 * The feel of going back: ONE short tick.
 *
 * It was `GESTURE_END`, the system's gesture-commit effect — which on this phone's motor is a
 * multi-pulse pattern, so going back felt like a buzz of several ticks. The predefined
 * `EFFECT_TICK` (via [Haptics.tick], which the keypad already uses) is a single click, which is
 * what a step back should be.
 */
internal fun View.backHaptic() {
    Haptics.tick(context)
}

/**
 * [onBack] with [backHaptic] in front of it, for every back arrow so they all feel alike. A
 * composable that returns the lambda, so it slots into whatever `clickable` the arrow already has
 * and leaves its press animation and semantics alone.
 */
@Composable
internal fun hapticBack(onBack: () -> Unit): () -> Unit {
    val view = LocalView.current
    return { view.backHaptic(); onBack() }
}
