package com.redraft.pdfcore

import android.graphics.Bitmap
import android.graphics.Color
import io.legere.pdfiumandroid.suspend.PdfDocumentKt
import io.legere.pdfiumandroid.suspend.PdfiumCoreKt
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.withContext

data class PdfPageSize(
    val widthPt: Float,
    val heightPt: Float,
    val rotationDegrees: Int,
)

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
        checkOpen()
        return document.getPageCount()
    }

    suspend fun pageSize(index: Int): PdfPageSize {
        checkOpen()
        require(index in 0 until pageCount()) { "Page index $index out of bounds" }
        val page = document.openPage(index)
            ?: error("PDFium could not open page $index")
        return page.use {
            PdfPageSize(
                widthPt = it.getPageWidthPoint().toFloat(),
                heightPt = it.getPageHeightPoint().toFloat(),
                rotationDegrees = it.getPageRotation(),
            )
        }
    }

    suspend fun renderPage(
        index: Int,
        targetWidthPx: Int,
        targetHeightPx: Int,
        background: Int = Color.WHITE,
    ): Bitmap {
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
        if (!closed.compareAndSet(false, true)) return
        withContext(pdfDispatcher) {
            document.close()
        }
        PdfHandleRegistry.unregister(KIND_DOCUMENT)
    }

    companion object {
        private const val KIND_DOCUMENT = "document"

        suspend fun open(bytes: ByteArray): PdfDocumentHandle {
            require(bytes.isNotEmpty()) { "Empty PDF byte array" }
            val core = PdfiumCoreKt(pdfDispatcher)
            val document = core.newDocument(bytes)
            PdfHandleRegistry.register(KIND_DOCUMENT)
            return PdfDocumentHandle(document)
        }
    }
}
