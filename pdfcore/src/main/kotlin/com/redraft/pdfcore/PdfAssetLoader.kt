package com.redraft.pdfcore

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a PDF document's bytes come from.
 *
 * Kept out of [PdfDocumentHandle] so the handle stays unit-testable and free
 * of `android.content.Context` (plan 01-01, Task 2).
 */
sealed interface PdfDocumentSource {

    suspend fun openBytes(): ByteArray

    data class Bytes(private val data: ByteArray) : PdfDocumentSource {
        override suspend fun openBytes(): ByteArray = data
    }

    data class Asset(
        private val context: Context,
        private val path: String,
    ) : PdfDocumentSource {
        override suspend fun openBytes(): ByteArray = withContext(Dispatchers.IO) {
            context.assets.open(path).use { it.readBytes() }
        }
    }
}

object PdfAssetLoader {

    suspend fun openAsset(context: Context, path: String): PdfDocumentHandle =
        open(PdfDocumentSource.Asset(context.applicationContext, path))

    suspend fun open(source: PdfDocumentSource): PdfDocumentHandle =
        PdfDocumentHandle.open(source.openBytes())
}
