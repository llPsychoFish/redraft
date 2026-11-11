package com.redraft.ui

import com.redraft.pdfcore.PdfRect
import com.redraft.pdfcore.selection.CharBox
import com.redraft.pdfcore.selection.TextFragment
import com.redraft.pdfcore.selection.TextLine
import com.redraft.pdfcore.selection.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class EditableLineTextTest {

    private fun line(vararg chars: CharBox): TextLine {
        val list = chars.toList()
        val fragment = TextFragment(list, TextStyle(fontSizePt = 10f, baselineY = 700f))
        return TextLine(list, PdfRect(100f, 700f, 200f, 710f), fragment)
    }

    @Test
    fun infers_a_missing_word_space_from_the_gap() {
        val text = editableLineText(
            line(
                CharBox(0, "A", PdfRect(100f, 700f, 110f, 710f), 10f),
                CharBox(1, "B", PdfRect(140f, 700f, 150f, 710f), 10f),
            ),
        )
        assertEquals("A B", text)
    }

    @Test
    fun does_not_double_a_space_that_extraction_already_produced() {
        val text = editableLineText(
            line(
                CharBox(0, "A", PdfRect(100f, 700f, 110f, 710f), 10f),
                CharBox(1, " ", PdfRect(110f, 700f, 115f, 710f), 10f),
                CharBox(2, "B", PdfRect(145f, 700f, 155f, 710f), 10f),
            ),
        )
        assertEquals("A B", text)
    }

    @Test
    fun keeps_letters_with_no_gap_together() {
        val text = editableLineText(
            line(
                CharBox(0, "A", PdfRect(100f, 700f, 110f, 710f), 10f),
                CharBox(1, "B", PdfRect(111f, 700f, 120f, 710f), 10f),
            ),
        )
        assertEquals("AB", text)
    }

    @Test
    fun bundled_font_notice_uses_a_readable_name() {
        assertEquals("Liberation Sans", bundledFontDisplayName("LiberationSans-Regular"))
        assertEquals(
            "Liberation Sans Bold Italic",
            bundledFontDisplayName("LiberationSans-BoldItalic"),
        )
    }
}
