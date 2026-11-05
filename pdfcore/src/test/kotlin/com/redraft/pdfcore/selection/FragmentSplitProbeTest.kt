package com.redraft.pdfcore.selection

import com.redraft.pdfcore.PdfRect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Minimal probe of the fragment-split mechanism.
 *
 * PDFium reports `FPDFText_GetFontSize == 1.0` for the spaces it synthesises at the
 * boundary between two text-showing operations, and 1.0 for its CR/LF break chars
 * (measured directly: 123 of the 131 spaces on a Word-style page report 1 pt).
 * `fragmentChars` compares `deriveStyle(listOf(prev))` against `deriveStyle(listOf(char))`
 * — one-char samples — so a space's own 1 pt size is taken as its style and compared with
 * the 9 pt size of its neighbours.
 */
class FragmentSplitProbeTest {

    private fun glyph(index: Int, text: String, x: Float, size: Float) = CharBox(
        index = index,
        text = text,
        rect = PdfRect(x, 700f, x + size * 0.5f, 706f),
        fontSizePt = size,
    )

    /** A synthesised space: PDFium gives it a zero-width box and size 1. */
    private fun synthSpace(index: Int, x: Float) = CharBox(
        index = index,
        text = " ",
        rect = PdfRect(x, 700f, x, 700.01f),
        fontSizePt = 1f,
    )

    @Test fun oneVisualLineDrawnAsTwoRunsWithASynthesisedSpaceIsOneFragment() {
        // "music while" — Word emits `BT (music) Tj ( while) Tj ET`, so PDFium
        // synthesises the space between the two runs at size 1.
        val chars = listOf(
            glyph(0, "m", 100f, 9f),
            glyph(1, "u", 104.5f, 9f),
            glyph(2, "s", 109f, 9f),
            glyph(3, "i", 113.5f, 9f),
            glyph(4, "c", 116.5f, 9f),
            synthSpace(5, 121f),
            glyph(6, "w", 124f, 9f),
            glyph(7, "h", 128.5f, 9f),
            glyph(8, "i", 133f, 9f),
            glyph(9, "l", 136f, 9f),
            glyph(10, "e", 139f, 9f),
        )

        val fragments = fragmentChars(chars)
        assertEquals(
            "a synthesised space must not end a fragment (its 1 pt size is not its style)",
            1,
            fragments.size,
        )
        assertEquals(1, TextPageLayout(0, chars).reducedLines.size)
    }
}
