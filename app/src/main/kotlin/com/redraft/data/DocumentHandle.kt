package com.redraft.data

import android.graphics.Bitmap
import com.redraft.pdfcore.PdfAssetLoader
import com.redraft.pdfcore.PdfDocumentSource
import com.redraft.pdfcore.PdfPageGeometry
import com.redraft.pdfcore.PdfDocumentHandle as PdfCoreDocumentHandle
import io.legere.pdfiumandroid.suspend.PdfDocumentKt

interface DocumentHandle {
    val pageCount: Int
    suspend fun open(source: PdfDocumentSource)
    suspend fun pageSize(index: Int): PdfPageGeometry
    suspend fun renderPage(index: Int, targetWidthPx: Int, targetHeightPx: Int): Bitmap
    suspend fun close()
    /** The underlying Pdfium document, needed by text extraction. */
    val document: PdfDocumentKt
}

class PdfiumDocumentHandle : DocumentHandle {

    private var delegate: PdfCoreDocumentHandle? = null

    override var pageCount: Int = 0
        private set

    override val document: PdfDocumentKt
        get() = requireDelegate().document

    override suspend fun open(source: PdfDocumentSource) {
        val opened = PdfAssetLoader.open(source)
        delegate = opened
        pageCount = opened.pageCount()
    }

    override suspend fun pageSize(index: Int): PdfPageGeometry =
        requireDelegate().pageSize(index)

    override suspend fun renderPage(
        index: Int,
        targetWidthPx: Int,
        targetHeightPx: Int,
    ): Bitmap = requireDelegate().renderPage(index, targetWidthPx, targetHeightPx)

    override suspend fun close() {
        val open = delegate ?: return
        delegate = null
        pageCount = 0
        open.close()
    }

    private fun requireDelegate(): PdfCoreDocumentHandle =
        checkNotNull(delegate) { "Document handle is not open" }
}
