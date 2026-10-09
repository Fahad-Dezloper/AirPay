package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gift-card header's ground, pinned to the pixels it is chosen from.
 *
 * Why this is a test and not a look: the bug it replaces — Vodafone's white logo washed out to
 * nothing on a near-white header — was invisible in code, passed lint, and could only be seen on
 * the phone, one brand at a time. [groundFor] is pixels in and a choice out, so the whole
 * decision can be stated as arithmetic here: what a white logo gets, what a dark one gets, where
 * the crossover is, and the three ways of accidentally giving every brand the same answer.
 *
 * Resource-free and Compose-free at the call site, so it runs as a plain JVM test with no
 * Robolectric. The ground *colours* are not asserted — [groundFor] returns the choice, and the
 * mapping to `D.Night` / `D.White` is one `when` in the composable.
 */
class VoucherGroundTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun grey(v: Int, a: Int = 0xFF) = argb(a, v, v, v)

    /** Fully transparent, which is what `c_pad` with no `b_rgb:` pads a logo out with. */
    private val clear = 0

    /**
     * A padded logo the shape the CDN returns one: a small mark inside a mostly empty frame.
     * The mark is spread on a period coprime with [groundFor]'s own stride, so no sampling step
     * can land entirely between the marks and measure the padding alone.
     */
    private fun padded(mark: Int, size: Int = 1_008, every: Int = 24) =
        IntArray(size) { if (it % every == 0) mark else clear }

    @Test fun `a white logo is put on the dark ground`() {
        assertEquals(Ground.Dark, groundFor(padded(grey(0xFF))))
    }

    /**
     * The actual bug. `b_rgb:F8F8F8` padded every logo onto this grey and the header was painted
     * to match, so a logo at the same luminance sat at 1.06:1 — Vodafone's, and it read as an
     * empty card. A logo this light must now come out on the dark ground.
     */
    @Test fun `the near-white logo that caused the bug is put on the dark ground`() {
        assertEquals(Ground.Dark, groundFor(padded(grey(0xF8))))
        assertEquals(Ground.Dark, groundFor(padded(argb(0xFF, 0xFF, 0xFA, 0xF2))))
    }

    @Test fun `a dark logo keeps the light ground`() {
        assertEquals(Ground.Light, groundFor(padded(grey(0x00))))
        assertEquals(Ground.Light, groundFor(padded(grey(0x1A))))
        assertEquals(Ground.Light, groundFor(padded(argb(0xFF, 0x34, 0x34, 0x33))))
    }

    /**
     * Colour, not just grey: a saturated mark is weighted the way the eye weights it, so a red
     * that is numerically mid-bright is still a dark logo, and a yellow is a light one.
     */
    @Test fun `the choice follows luminance rather than raw brightness`() {
        assertEquals(Ground.Light, groundFor(padded(argb(0xFF, 0xE6, 0x00, 0x00))))
        assertEquals(Ground.Dark, groundFor(padded(argb(0xFF, 0xFF, 0xE0, 0x00))))
    }

    /**
     * The in-between case, stated as a property rather than a threshold: the choice flips once
     * across the grey ramp, in that direction, and the flip is somewhere in the middle greys.
     * Ranges rather than the exact byte (it computes to #797979) because the bound either side
     * is what the design claims — dark logos light, light logos dark — and one byte of float
     * drift is not a regression.
     */
    @Test fun `the grey ramp flips exactly once, from light to dark`() {
        val ramp = (0..255).map { groundFor(padded(grey(it)), step = 1) }
        val flips = (1..255).filter { ramp[it] != ramp[it - 1] }
        assertEquals(1, flips.size)
        assertEquals(Ground.Light, ramp.first())
        assertEquals(Ground.Dark, ramp.last())
        assertTrue("flip at #${flips[0].toString(16)}", flips[0] in 112..128)
    }

    /**
     * Rule 1. The frame is mostly empty; if the empty part counted, every logo would average to
     * the padding's own colour and every card would get the same ground — which is the bug, not
     * a fix. A white mark in a 97% transparent frame is still a white logo.
     */
    @Test fun `transparent padding is not counted`() {
        val mostlyEmpty = IntArray(1_000) { if (it < 30) grey(0xFF) else clear }
        assertEquals(Ground.Dark, groundFor(mostlyEmpty, step = 1))
    }

    /**
     * Rule 2. Alpha is a weight, not a flag. A faint white ghost around an opaque dark mark must
     * not outvote the mark: counted as pixels it is 4 white to 1 black and the header would go
     * dark under a dark logo; weighted by alpha it is 32 parts white to 255 parts black.
     */
    @Test fun `partial alpha counts for as much as it is painted`() {
        val ghosted = IntArray(1_000) {
            when {
                it == 0 -> grey(0x00)
                it in 1..4 -> grey(0xFF, a = 0x08)
                else -> clear
            }
        }
        assertEquals(Ground.Light, groundFor(ghosted, step = 1))
    }

    /**
     * Rule 3. An image with no transparent pixel anywhere carries its own background — a logo
     * with a white plate baked in. Its average is dominated by that plate, but padding it onto
     * the dark ground would frame it in a visible rectangle, so it takes the light ground.
     */
    @Test fun `an opaque image brings its own ground`() {
        val plated = IntArray(1_000) { if (it % 24 == 0) grey(0x11) else grey(0xFF) }
        assertEquals(Ground.Light, groundFor(plated, step = 1))
    }

    /** Nothing to measure is not a reason to paint a dark panel. */
    @Test fun `an empty or wholly transparent logo keeps the light ground`() {
        assertEquals(Ground.Light, groundFor(IntArray(0)))
        assertEquals(Ground.Light, groundFor(IntArray(1_000) { clear }))
    }

    /**
     * Sampling is an optimisation, so it must not be a decision. Every Nth pixel — and a step of
     * 0 or less, which would otherwise not terminate — gives the answer the whole walk gives.
     */
    @Test fun `the sampling step does not change the answer`() {
        for (v in listOf(0x00, 0x1A, 0x60, 0x79, 0x90, 0xF8, 0xFF)) {
            val px = padded(grey(v))
            val whole = groundFor(px, step = 1)
            for (step in listOf(0, 1, 7, 23, 97)) {
                assertEquals("grey #${v.toString(16)} at step $step", whole, groundFor(px, step))
            }
        }
    }
}
