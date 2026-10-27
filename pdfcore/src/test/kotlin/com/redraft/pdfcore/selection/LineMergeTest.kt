package com.redraft.pdfcore.selection

import com.redraft.pdfcore.PdfRect
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table-driven coverage for the D-12 gap rules and glyph-union handling
 * (EDIT_TEXT-02): gap splitting along the baseline direction, the median
 * word-gap rule, and zero-width characters.
 */
class LineMergeTest {

    // ─── Chars/fragment builders ─────────────────────────────────────────────

    /** Horizontal char box: baseline at [minY], width [w] points, height [size]. */
    private fun charBox(
        index: Int,
        text: String,
        x: Float,
        w: Float,
        size: Float = 10f,
        baseline: Float = 700f,
    ): CharBox = CharBox(
        index = index,
        text = text,
        rect = PdfRect(x, baseline, x + w, baseline + size),
        fontSizePt = size,
    )

    /** `text` laid out left-to-right with [gapEm] em gaps between the chars. */
    private fun spaced(
        text: String,
        size: Float = 10f,
        gapEm: Float = 0f,
        x: Float = 100f,
        baseline: Float = 700f,
    ): List<CharBox> {
        val charW = size * 0.5f
        val step = charW + gapEm * size
        var index = 0
        return text.indices.map { i ->
            charBox(index++, text[i].toString(), x + i * step, charW, size, baseline)
        }
    }

    private fun fragment(
        chars: List<CharBox>,
        directionX: Float = 1f,
        directionY: Float = 0f,
    ): TextFragment = TextFragment(
        chars = chars,
        style = TextStyle(
            fontSizePt = chars.first().fontSizePt,
            baselineY = chars.first().rect.minY,
            directionX = directionX,
            directionY = directionY,
        ),
    )

    // ─── D-12 rule 1: the 2 em rule ──────────────────────────────────────────

    @Test
    fun gap_of_2_1_em_splits_the_line() {
        val chars = listOf(
            charBox(0, "A", 100f, 5f),
            charBox(1, "B", 100f + 0.5f * 10f + 21f, 5f),
        )
        val lines = splitFragmentIntoLines(fragment(chars))
        assertEquals(2, lines.size)
        assertEquals("A", lines[0].chars.joinToString("") { it.text })
        assertEquals("B", lines[1].chars.joinToString("") { it.text })
    }

    // ─── D-12 rule 2: median word-gap rule ───────────────────────────────────

    @Test
    fun gap_of_1_9_em_on_justified_text_does_not_split() {
        // Justified line: every word gap stretched evenly to 0.8 em, so the
        // median word gap is 0.8 em and 2.5 × median = 2.0 em — a 1.9 em
        // stretched gap stays inside the distribution and keeps one line.
        val first = spaced("AB", gapEm = 0.8f)
        val stretchedBit = charBox(2, "C", 118f + 19f, 5f) // exactly 1.9 em after B's edge
        val chars = first + stretchedBit
        val lines = splitFragmentIntoLines(fragment(chars))
        assertEquals(1, lines.size)
        assertEquals(3, lines[0].chars.size)
    }

    @Test
    fun gap_of_0_8_em_table_gutter_splits_beyond_median() {
        // Table cells "AB" | "CD": intra-cell word gaps 0.1 em (median 0.1 em),
        // cell gutter 0.8 em > 2.5 × 0.1 em = 0.25 em → the cells split.
        val cellA = spaced("AB", gapEm = 0.1f)
        val gutterX = 100f + 2 * 0.55f * 10f + 8f
        val cellB = listOf(charBox(2, "C", gutterX, 5f), charBox(3, "D", gutterX + 5.5f, 5f))
        val lines = splitFragmentIntoLines(fragment(cellA + cellB))
        assertEquals(2, lines.size)
        assertEquals("AB", lines[0].chars.joinToString("") { it.text })
        assertEquals("CD", lines[1].chars.joinToString("") { it.text })
    }

    // ─── Zero-width characters (D-12) ────────────────────────────────────────

    @Test
    fun trailing_zero_width_space_does_not_widen_the_union() {
        // "A" 100..112 (12 pt wide at size 24 = 0.5 em), then a zero-width
        // trailing space parked ahead of the glyph end.
        val a = charBox(0, "A", 100f, 12f, size = 24f)
        val zwsp = CharBox(
            index = 1,
            text = " ",
            rect = PdfRect(118f, 700f, 118f, 724f),
            fontSizePt = 24f,
        )
        val lines = splitFragmentIntoLines(fragment(listOf(a, zwsp)))
        assertEquals(1, lines.size)
        // The glyph union must stop at A's right edge — the zero-width char
        // contributes to no bound (a plain union would reach 118 pt).
        assertEquals(112f, lines[0].rect.maxX, 0.001f)
        assertEquals(100f, lines[0].rect.minX, 0.001f)
    }

    // ─── Rotated text measures gaps along the baseline (D-12) ────────────────

    @Test
    fun gap_over_2_em_on_a_90_degree_fragment_still_splits() {
        // 90° text travels along +y: char boxes stacked vertically.
        val c1 = charBox(0, "一", 100f, 10f, size = 10f, baseline = 700f)
        // Next char 21 pt (2.1 em) further along the baseline direction.
        val c2 = charBox(1, "二", 100f, 10f, size = 10f, baseline = 700f + 10f + 21f)
        val lines = splitFragmentIntoLines(fragment(listOf(c1, c2), directionX = 0f, directionY = 1f))
        assertEquals(2, lines.size)
        assertEquals(700f, lines[0].rect.minY, 0.001f)
        assertEquals(731f, lines[1].rect.minY, 0.001f)
    }

    // ─── Two Tj runs with equal metrics merge ────────────────────────────────

    @Test
    fun two_matching_runs_on_one_baseline_form_one_line() {
        // Fragmented runs "Hello" + "World" on one baseline: same style, tiny
        // kerning gap → exactly one visual line.
        val hello = spaced("Hello", size = 24f, x = 100f)
        val world = spaced("World", size = 24f, x = 100f + 5.5f * 12f)
            .mapIndexed { i, c -> c.copy(index = 5 + i) }
        val merged = splitFragmentIntoLines(fragment(hello + world))
        assertEquals(1, merged.size)
        val text = merged[0].chars.joinToString("") { it.text }
        assertTrue("runs must concatenate in order, was: $text", text.startsWith("HelloWorld"))
    }

    @Test
    fun median_word_gap_is_computed_over_the_word_gap_window() {
        // Gaps: two tiny kerning gaps (excluded), one 0.05..1.0 em word gap,
        // one 3 em gap (excluded from the sample but rule 1 splits it first).
        val chars = listOf(
            charBox(0, "A", 100f, 5f, size = 10f),
            charBox(1, "B", 105.2f, 5f, size = 10f),   // gap 0.2 pt = 0.02 em — excluded
            charBox(2, "C", 110.4f, 5f, size = 10f),   // gap 0.2 pt — excluded
            charBox(3, "D", 116.4f, 5f, size = 10f),   // gap 1 pt = 0.1 em — eligible
            charBox(4, "E", 153.4f, 5f, size = 10f),   // gap 32 pt = 3.2 em — splits (rule 1)
        )
        val lines = splitFragmentIntoLines(fragment(chars))
        assertEquals(2, lines.size)
        // The split relies on rule 1 alone; the median sample was {0.1 em}.
        assertTrue(
            "median must be the 0.1 em word gap, was ${medianWordGap(chars, fragment(chars).style)}",
            abs(medianWordGap(chars, fragment(chars).style) - 1f) < 1e-3f,
        )
    }
}
