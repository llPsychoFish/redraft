package com.redraft.pdfedit

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.redraft.pdfcore.PdfDocumentHandle
import com.redraft.pdfcore.selection.PageTextLayout
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task 7: prove a REAL edit with the native module.
 *
 * Opens the bundled sample through [PdfEditingEngineImpl], replaces one word,
 * saves a copy, reopens that copy with the ordinary renderer and checks the text.
 * This must run on a device or emulator; it is not a fake-engine test.
 */
@RunWith(AndroidJUnit4::class)
class RealTextEditTest {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun edit_one_word_survives_save_and_reopen() = runBlocking {
        val originalBytes = testAssets.open("text_basic.pdf").use { it.readBytes() }
        val source = File(targetContext.cacheDir, "edit-source.pdf").apply { writeBytes(originalBytes) }
        val copy = File(targetContext.cacheDir, "edit-copy.pdf").apply { delete() }

        val (originalLine, originalText) = readFirstLine(originalBytes)
        val editedText = originalText.replace("World", "Raft")
        assertFalse("the fixture must contain the word being replaced", editedText == originalText)

        PdfEditModule.initialize(targetContext)
        val engine = PdfEditModule.engine()
        val handle = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            engine.open(Uri.fromFile(source), pfd).getOrThrow()
        }

        try {
            val objectIndex = engine.findTextObject(
                handle = handle,
                pageIndex = 0,
                left = originalLine.rect.minX,
                bottom = originalLine.rect.minY,
                right = originalLine.rect.maxX,
                top = originalLine.rect.maxY,
                text = originalText,
            )
            assertTrue("exactly one text object must match the line", objectIndex >= 0)

            val set = engine.setText(handle, 0, objectIndex, editedText)
            if (!set) {
                val font = engine.loadBundledFont(handle, "LiberationSans-Regular", false, false)
                val replaced = engine.replaceTextObject(
                    handle, 0, objectIndex, editedText, font,
                    originalLine.fragment.style.fontSizePt, 0xFF000000.toInt(),
                    floatArrayOf(1f, 0f, 0f, 1f, originalLine.rect.minX, originalLine.rect.minY),
                )
                assertTrue("the replacement font path must succeed", replaced)
            }

            assertTrue("saving the copy must succeed", engine.saveAsCopy(handle, copy.absolutePath))
        } finally {
            engine.close(handle)
        }

        assertTrue("the copy must exist and be a PDF", copy.isFile && copy.length() > 0)
        val reopened = PdfDocumentHandle.open(copy.readBytes())
        try {
            val layout = reopened.pageTextLayout(0, reopened.pageSize(0))
            val text = (layout as? PageTextLayout.Ready)
                ?.layout
                ?.chars
                ?.joinToString("") { it.text }
                .orEmpty()
            assertTrue("the new word must be in the saved copy", text.contains("Raft"))
            assertFalse("the old word must be gone", text.contains("World"))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun a_new_character_uses_the_bundled_font() = runBlocking {
        val originalBytes = testAssets.open("text_basic.pdf").use { it.readBytes() }
        val source = File(targetContext.cacheDir, "fallback-source.pdf").apply { writeBytes(originalBytes) }
        val copy = File(targetContext.cacheDir, "fallback-copy.pdf").apply { delete() }

        val (line, originalText) = readFirstLine(originalBytes)
        // 'ö' is not in the original line, so the original subset font cannot be
        // assumed to encode it; the bundled Liberation face is the fallback.
        val editedText = originalText.replace("World", "Wörld")
        assertTrue(editedText.contains('ö'))

        PdfEditModule.initialize(targetContext)
        val engine = PdfEditModule.engine()
        val handle = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            engine.open(Uri.fromFile(source), pfd).getOrThrow()
        }

        try {
            val objectIndex = engine.findTextObject(
                handle, 0, line.rect.minX, line.rect.minY, line.rect.maxX, line.rect.maxY, originalText,
            )
            assertTrue(objectIndex >= 0)

            val font = engine.loadBundledFont(handle, "LiberationSans-Regular", false, false)
            assertTrue(
                "the bundled font must replace the object",
                engine.replaceTextObject(
                    handle, 0, objectIndex, editedText, font,
                    line.fragment.style.fontSizePt, 0xFF000000.toInt(),
                    floatArrayOf(1f, 0f, 0f, 1f, line.rect.minX, line.rect.minY),
                ),
            )
            assertTrue(engine.saveAsCopy(handle, copy.absolutePath))
        } finally {
            engine.close(handle)
        }

        val reopened = PdfDocumentHandle.open(copy.readBytes())
        try {
            val layout = reopened.pageTextLayout(0, reopened.pageSize(0))
            val text = (layout as? PageTextLayout.Ready)
                ?.layout
                ?.chars
                ?.joinToString("") { it.text }
                .orEmpty()
            assertTrue("the fallback edit must survive the save", text.contains("Wörld"))
        } finally {
            reopened.close()
        }
    }

    private suspend fun readFirstLine(
        bytes: ByteArray,
    ): Pair<com.redraft.pdfcore.selection.TextLine, String> {
        val document = PdfDocumentHandle.open(bytes)
        try {
            val layout = document.pageTextLayout(0, document.pageSize(0))
            val ready = layout as? PageTextLayout.Ready
                ?: error("the fixture must have extractable text")
            val line = ready.layout.reducedLines.first { reduced ->
                reduced.chars.any { !it.text.isBlank() }
            }
            val text = line.chars.joinToString("") { it.text }.trim()
            assertEquals("Hello World", text)
            return line to text
        } finally {
            document.close()
        }
    }
}
