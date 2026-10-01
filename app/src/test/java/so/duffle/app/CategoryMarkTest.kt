package so.duffle.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The §1.5 optical sizing of the five category marks, pinned to the brief's own table.
 *
 * Why this is a test and not a look: the five vectors were traced at one scale, so their ink
 * boxes differ by nearly 2× in each axis. The fix scales each one so `sqrt(w × h)` hits a
 * target, and the only observable consequence is the dp the ink ends up at — which no one can
 * check by eye on a phone to better than "about right". ui/PORT-2026-10-01-2.md §1.5 publishes
 * the resulting drawn size for all ten mark/target combinations; those ten numbers are the
 * change, so they are asserted here.
 *
 * **Tolerance: 0.5dp.** The brief's table is printed in whole dp (`recharge 21 × 32`), so a
 * computed value agrees with it exactly when it rounds to the printed integer — i.e. when it is
 * within half a dp. Anything looser would let a real arithmetic error through (the largest
 * honest deviation in the table is movie's 32.43 against a printed 32); anything tighter would
 * be asserting digits the brief never states. The test therefore checks BOTH the half-dp
 * distance and that the value rounds to the published integer, and separately checks the exact
 * invariant the rule is actually made of — `sqrt(w × h) == optical` — to 0.001, since that one
 * is arithmetic and not a rounded table.
 *
 * Resource-free on purpose: [CatMark.res] is the only member that touches `R`, and nothing here
 * calls it, so this runs as a plain JVM test with no Robolectric.
 */
class CategoryMarkTest {

    /** ui/PORT-2026-10-01-2.md §1.5, "Resulting drawn size (w × h, dp)", both rows of the table. */
    private val expected: List<Triple<CatMark, Float, Pair<Int, Int>>> = listOf(
        // Transaction row — 40dp box, optical 26.
        Triple(CatMark.Recharge, CatMark.ROW_OPTICAL, 21 to 32),
        Triple(CatMark.Movie, CatMark.ROW_OPTICAL, 32 to 21),
        Triple(CatMark.Meal, CatMark.ROW_OPTICAL, 24 to 28),
        Triple(CatMark.Bag, CatMark.ROW_OPTICAL, 26 to 26),
        Triple(CatMark.More, CatMark.ROW_OPTICAL, 26 to 26),
        // Category picker — 48dp box, optical 31.
        Triple(CatMark.Recharge, CatMark.PICKER_OPTICAL, 25 to 39),
        Triple(CatMark.Movie, CatMark.PICKER_OPTICAL, 39 to 25),
        Triple(CatMark.Meal, CatMark.PICKER_OPTICAL, 29 to 33),
        Triple(CatMark.Bag, CatMark.PICKER_OPTICAL, 31 to 31),
        Triple(CatMark.More, CatMark.PICKER_OPTICAL, 31 to 31),
    )

    @Test
    fun `drawn sizes match the brief's table`() {
        expected.forEach { (mark, optical, want) ->
            val (w, h) = mark.drawnSize(optical)
            val at = "$mark at optical $optical"
            assertEquals("$at width", want.first.toFloat(), w, 0.5f)
            assertEquals("$at height", want.second.toFloat(), h, 0.5f)
            assertEquals("$at width rounds to ${want.first}", want.first, w.roundToInt())
            assertEquals("$at height rounds to ${want.second}", want.second, h.roundToInt())
        }
    }

    /**
     * The rule itself: every mark's ink covers the SAME optical area at one target. This is what
     * "look the same size" means here, and it is the §4 item 4 acceptance check in arithmetic
     * form — the phone must not come out smaller than the ticket.
     */
    @Test
    fun `every mark hits the same optical area`() {
        listOf(CatMark.ROW_OPTICAL, CatMark.PICKER_OPTICAL).forEach { optical ->
            CatMark.entries.forEach { mark ->
                val (w, h) = mark.drawnSize(optical)
                assertEquals("$mark optical at target $optical", optical, sqrt(w * h), 0.001f)
            }
        }
    }

    /**
     * Guards the reason the change was needed: the raw ink boxes are far apart, so a no-op
     * implementation (one scale for all five) would still pass a sloppier equal-area assertion.
     * The tallest/widest spread must be at least 1.5× before scaling.
     */
    @Test
    fun `the raw ink boxes really do differ`() {
        val geo = CatMark.entries.map { sqrt(it.ink.first * it.ink.second) }
        val spread = geo.max() / geo.min()
        assertTrue("ink geometric means should span ~1.22x: $geo", spread > 1.15f)
        // And the axes themselves disagree by nearly 2x, which is why equal box fails.
        val widths = CatMark.entries.map { it.ink.first }
        assertTrue("ink widths should span ~1.9x", widths.max() / widths.min() > 1.8f)
    }

    /** The two call sites' boxes, which §1.5 says must be left alone by the scaling. */
    @Test
    fun `the boxes are the ones the brief specifies`() {
        assertEquals(40f, CatMark.RowBox.value, 0f)
        assertEquals(48f, CatMark.PickerBox.value, 0f)
        assertEquals(26f, CatMark.ROW_OPTICAL, 0f)
        assertEquals(31f, CatMark.PICKER_OPTICAL, 0f)
    }

    /**
     * §1.2's category → mark mapping, and §1.4's "no category yet" case, which must stay null so
     * the row draws the dashed "+" instead of a mark. Travel and Bills have no vector yet (§5),
     * so they are null too rather than silently falling back to More.
     */
    @Test
    fun `categories map to marks`() {
        assertEquals(CatMark.Recharge, CatMark.forCategory("Phone"))
        assertEquals(CatMark.Movie, CatMark.forCategory("Movies"))
        assertEquals(CatMark.Meal, CatMark.forCategory("Food"))
        assertEquals(CatMark.Bag, CatMark.forCategory("Shopping"))
        assertEquals(CatMark.More, CatMark.forCategory("Other"))
        assertNull(CatMark.forCategory(null))
        assertNull(CatMark.forCategory("Travel"))
        assertNull(CatMark.forCategory("Bills"))
        assertNull(CatMark.forCategory("food"))
    }

    /**
     * The grid the painter is drawn at, which is what [CategoryMark] puts on screen: 24 units of
     * grid at `unitFor` dp each. It overhangs the box in four of the ten cases, and must — the
     * clamping version of this (`size` instead of `requiredSize`) is the bug this pins.
     */
    @Test
    fun `the scaled grid overhangs the box where it has to`() {
        val rowGrid = CatMark.entries.associateWith { 24f * it.unitFor(CatMark.ROW_OPTICAL) }
        val pickerGrid = CatMark.entries.associateWith { 24f * it.unitFor(CatMark.PICKER_OPTICAL) }
        // Recharge is the extreme: a tall thin mark needs the most grid to reach the target area.
        assertEquals(45.86f, rowGrid.getValue(CatMark.Recharge), 0.05f)
        assertEquals(54.68f, pickerGrid.getValue(CatMark.Recharge), 0.05f)
        // No mark's INK leaves its box, which is why the overhang can be left unclipped.
        expected.forEach { (mark, optical, _) ->
            val box = if (optical == CatMark.ROW_OPTICAL) 40f else 48f
            val (w, h) = mark.drawnSize(optical)
            assertTrue("$mark ink fits its $box box", abs(w) < box && abs(h) < box)
        }
    }
}
