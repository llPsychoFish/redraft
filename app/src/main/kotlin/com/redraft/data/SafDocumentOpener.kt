package com.redraft.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.redraft.pdfcore.PdfDocumentSource
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class DocumentError(message: String) : Exception(message) {
    class PasswordProtected : DocumentError("This PDF is password-protected")
    class AccessRevoked : DocumentError("Access to this file was revoked")
    class Unreadable : DocumentError("Couldn't open this file")
}

object SafDocumentOpener {

    private const val TAG = "SafDocumentOpener"

    const val FALLBACK_NAME = "document.pdf"

    val openPdfMimeTypes = arrayOf("application/pdf")

    fun persistPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Provider withheld persistable permission for $uri", e)
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        return uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: FALLBACK_NAME
    }

    suspend fun open(context: Context, uri: Uri): Result<DocumentHandle> = withContext(Dispatchers.IO) {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(DocumentError.Unreadable())
        } catch (e: SecurityException) {
            return@withContext Result.failure(DocumentError.AccessRevoked())
        } catch (e: IOException) {
            return@withContext Result.failure(DocumentError.Unreadable())
        }

        try {
            val handle = PdfiumDocumentHandle()
            handle.open(PdfDocumentSource.Bytes(bytes))
            Result.success(handle)
        } catch (e: Exception) {
            if (looksEncrypted(bytes)) {
                Result.failure(DocumentError.PasswordProtected())
            } else {
                Result.failure(DocumentError.Unreadable())
            }
        }
    }

    private fun looksEncrypted(bytes: ByteArray): Boolean =
        String(bytes, Charsets.ISO_8859_1).contains("/Encrypt")
}
