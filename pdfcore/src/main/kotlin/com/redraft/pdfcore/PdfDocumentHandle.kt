package com.redraft.pdfcore

import android.graphics.Bitmap
import android.graphics.Color
import io.legere.pdfiumandroid.suspend.PdfDocumentKt
import io.legere.pdfiumandroid.suspend.PdfiumCoreKt
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.withContext

/**
 * Single-owner handle over an open PDFium document (decision D-12).
 *
 * All PDFium work runs on [pdfDispatcher] via the pdfiumandroid suspend API —
 * no function here may call PDFium off that dispatcher, and no non-suspend
 * function may touch PDFium at all. Page handles are short-lived and closed
 * within each call (`use { }`). [close] is idempotent.
 */
class PdfDocumentHandle private constructor(
    private val document: PdfDocumentKt,
) {

    private val closed = AtomicBoolean(false)

    private fun checkOpen() {
        check(!closed.get()) { "Document already closed" }
    }

    suspend fun pageCount(): Int {
        assertNotMainThread()
        checkOpen()
        return document.getPageCount()
    }

    suspend fun pageSize(index: Int): PdfPageGeometry {
        assertNotMainThread()
        checkOpen()
        require(index in 0 until pageCount()) { "Page index $index out of bounds" }
        val page = document.openPage(index)
            ?: error("PDFium could not open page $index")
        return page.use {
            // Page boxes arrive as four positional numbers whose field names are
            // assigned positionally, not semantically (02-RESEARCH §1.3).
            // PdfPageGeometry.build normalises them, honours the (-1,-1,-1,-1)
            // sentinel, intersects crop with media, and coerces the rotation
            // index so -1 can never become 270° (02-RESEARCH §2.3).
            val crop = it.getPageCropBox()
            val media = it.getPageMediaBox()
            PdfPageGeometry.build(
                pageIndex = index,
                cropValues = floatArrayOf(crop.left, crop.top, crop.right, crop.bottom),
                mediaValues = floatArrayOf(media.left, media.top, media.right, media.bottom),
                rawRotation = it.getPageRotation(),
            )
        }
    }

    suspend fun renderPage(
        index: Int,
        targetWidthPx: Int,
        targetHeightPx: Int,
        background: Int = Color.WHITE,
    ): Bitmap {
        assertNotMainThread()
        checkOpen()
        require(targetWidthPx > 0 && targetHeightPx > 0) {
            "Target size must be positive: ${targetWidthPx}x$targetHeightPx"
        }
        require(index in 0 until pageCount()) { "Page index $index out of bounds" }
        val page = document.openPage(index)
            ?: error("PDFium could not open page $index")
        return page.use {
            val bitmap = Bitmap.createBitmap(targetWidthPx, targetHeightPx, Bitmap.Config.ARGB_8888)
            it.renderPageBitmap(
                bitmap = bitmap,
                startX = 0,
                startY = 0,
                drawSizeX = targetWidthPx,
                drawSizeY = targetHeightPx,
                renderAnnot = false,
                textMask = false,
                canvasColor = background,
                pageBackgroundColor = background,
            )
            bitmap
        }
    }

    suspend fun close() {
        assertNotMainThread()
        if (!closed.compareAndSet(false, true)) return
        withContext(pdfDispatcher) {
            document.close()
        }
        PdfHandleRegistry.unregister(KIND_DOCUMENT)
    }

    companion object {
        private const val KIND_DOCUMENT = "document"

        suspend fun open(bytes: ByteArray): PdfDocumentHandle {
            assertNotMainThread()
            require(bytes.isNotEmpty()) { "Empty PDF byte array" }
            val core = PdfiumCoreKt(pdfDispatcher)
            val document = core.newDocument(bytes)
            PdfHandleRegistry.register(KIND_DOCUMENT)
            return PdfDocumentHandle(document)
        }
    }
}
