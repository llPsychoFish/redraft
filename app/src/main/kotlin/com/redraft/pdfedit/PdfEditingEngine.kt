package com.redraft.pdfedit

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface PdfEditingEngine {
    suspend fun open(uri: Uri, pfd: ParcelFileDescriptor): Result<EditingHandle>
    suspend fun close(handle: EditingHandle)
    suspend fun setText(handle: EditingHandle, pageIndex: Int, objectIndex: Int, newText: String): Boolean
    /** Returns false when PDFium could not replace the object; the page is unchanged. */
    suspend fun replaceTextObject(
        handle: EditingHandle,
        pageIndex: Int,
        objectIndex: Int,
        newText: String,
        fontHandle: FontHandle,
        fontSizePt: Float,
        color: Int,
        matrix: FloatArray
    ): Boolean
    suspend fun saveAsCopy(handle: EditingHandle, destPath: String): Boolean
    suspend fun loadBundledFont(handle: EditingHandle, fontName: String, bold: Boolean, italic: Boolean): FontHandle
    suspend fun getTextObjectCount(handle: EditingHandle, pageIndex: Int): Int
    suspend fun getTextObjectCharCount(handle: EditingHandle, pageIndex: Int, objectIndex: Int): Int

    /**
     * Index of the single TEXT object on [pageIndex] whose bounds overlap the
     * selected line's rectangle in PDF coordinates and whose text equals [text].
     *
     * Returns -1 when no object, or more than one, matches. The caller must treat
     * that as "cannot edit safely" and leave the page untouched.
     */
    suspend fun findTextObject(
        handle: EditingHandle,
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        text: String,
    ): Int
}

class PdfEditingEngineImpl private constructor(private val context: Context) : PdfEditingEngine {
    companion object {
        @Volatile private var INSTANCE: PdfEditingEngineImpl? = null

        fun initialize(context: Context) {
            INSTANCE = PdfEditingEngineImpl(context.applicationContext)
        }

        fun get(): PdfEditingEngine =
            INSTANCE ?: throw IllegalStateException(
                "PdfEditingEngine not initialized. Call PdfEditModule.engine() first.",
            )
    }

    private external fun nativeOpen(uri: String, fd: ParcelFileDescriptor, offset: Long, length: Long): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeSetText(handle: Long, pageIndex: Int, objectIndex: Int, newText: String): Boolean
    private external fun nativeReplaceTextObject(handle: Long, pageIndex: Int, objectIndex: Int,
        newText: String, fontHandle: Long, fontSizePt: Float, color: Int, matrix: FloatArray): Boolean
    private external fun nativeSaveAsCopy(handle: Long, destPath: String): Boolean
    private external fun nativeLoadBundledFont(handle: Long, fontName: String, bold: Boolean, italic: Boolean, fontData: ByteArray): Long
    private external fun nativeGetTextObjectCount(handle: Long, pageIndex: Int): Int
    private external fun nativeGetTextObjectCharCount(handle: Long, pageIndex: Int, objectIndex: Int): Int
    private external fun nativeFindTextObject(
        handle: Long,
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        text: String,
    ): Int

    override suspend fun open(uri: Uri, pfd: ParcelFileDescriptor): Result<EditingHandle> = withContext(Dispatchers.IO) {
        try {
            // Argument order is (offset, length). The offset is always 0: the
            // descriptor points at the start of the PDF. Passing the size as the
            // offset left the editing document unopened, so Apply silently did
            // nothing on every document.
            val length = pfd.statSize
            if (length <= 0L) {
                Result.failure(IllegalStateException("The document size is unknown"))
            } else {
                val handle = nativeOpen(uri.toString(), pfd, 0L, length)
                if (handle == 0L) {
                    Result.failure(IllegalStateException("PDFium could not open the document for editing"))
                } else {
                    Result.success(EditingHandle(handle))
                }
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun close(handle: EditingHandle) = withContext(Dispatchers.IO) {
        nativeClose(handle.ptr)
    }

    override suspend fun setText(handle: EditingHandle, pageIndex: Int, objectIndex: Int, newText: String): Boolean =
        withContext(Dispatchers.IO) {
            nativeSetText(handle.ptr, pageIndex, objectIndex, newText)
        }

    override suspend fun replaceTextObject(handle: EditingHandle, pageIndex: Int, objectIndex: Int,
        newText: String, fontHandle: FontHandle, fontSizePt: Float, color: Int, matrix: FloatArray): Boolean =
        withContext(Dispatchers.IO) {
            nativeReplaceTextObject(handle.ptr, pageIndex, objectIndex, newText, fontHandle.ptr, fontSizePt, color, matrix)
        }

    override suspend fun saveAsCopy(handle: EditingHandle, destPath: String): Boolean =
        withContext(Dispatchers.IO) {
            nativeSaveAsCopy(handle.ptr, destPath)
        }

    override suspend fun loadBundledFont(handle: EditingHandle, fontName: String, bold: Boolean, italic: Boolean): FontHandle =
        withContext(Dispatchers.IO) {
            val assetName = "fonts/liberation/${fontName}.ttf"
            val fontBytes = context.assets.open(assetName).readBytes()
            val fontPtr = nativeLoadBundledFont(handle.ptr, fontName, bold, italic, fontBytes)
            FontHandle(fontPtr)
        }

    override suspend fun getTextObjectCount(handle: EditingHandle, pageIndex: Int): Int =
        withContext(Dispatchers.IO) {
            nativeGetTextObjectCount(handle.ptr, pageIndex)
        }

    override suspend fun getTextObjectCharCount(handle: EditingHandle, pageIndex: Int, objectIndex: Int): Int =
        withContext(Dispatchers.IO) {
            nativeGetTextObjectCharCount(handle.ptr, pageIndex, objectIndex)
        }

    override suspend fun findTextObject(
        handle: EditingHandle,
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        text: String,
    ): Int = withContext(Dispatchers.IO) {
        nativeFindTextObject(handle.ptr, pageIndex, left, top, right, bottom, text)
    }
}
