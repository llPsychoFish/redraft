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
    suspend fun replaceTextObject(
        handle: EditingHandle,
        pageIndex: Int,
        objectIndex: Int,
        newText: String,
        fontName: String,
        fontSizePt: Float,
        color: Int,
        matrix: FloatArray
    )
    suspend fun saveAsCopy(handle: EditingHandle, destPath: String): Boolean
    suspend fun loadBundledFont(handle: EditingHandle, fontName: String, bold: Boolean, italic: Boolean): FontHandle
    suspend fun getTextObjectCount(handle: EditingHandle, pageIndex: Int): Int
    suspend fun getTextObjectCharCount(handle: EditingHandle, pageIndex: Int, objectIndex: Int): Int
}

class PdfEditingEngineImpl private constructor(private val context: Context) : PdfEditingEngine {
    companion object {
        @Volatile private var INSTANCE: PdfEditingEngineImpl? = null
        
        fun initialize(context: Context) {
            INSTANCE = PdfEditingEngineImpl(context.applicationContext)
        }
        
        fun get(): PdfEditingEngine = INSTANCE ?: throw IllegalStateException("PdfEditingEngine not initialized. Call initialize(context) first.")
        
        init {
            System.loadLibrary("pdfedit")
        }
    }

    private external fun nativeOpen(uri: String, fd: ParcelFileDescriptor, offset: Long, length: Long): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeSetText(handle: Long, pageIndex: Int, objectIndex: Int, newText: String): Boolean
    private external fun nativeReplaceTextObject(handle: Long, pageIndex: Int, objectIndex: Int,
        newText: String, fontName: String, fontSizePt: Float, color: Int, matrix: FloatArray)
    private external fun nativeSaveAsCopy(handle: Long, destPath: String): Boolean
    private external fun nativeLoadBundledFont(handle: Long, fontName: String, bold: Boolean, italic: Boolean, fontData: ByteArray): Long
    private external fun nativeGetTextObjectCount(handle: Long, pageIndex: Int): Int
    private external fun nativeGetTextObjectCharCount(handle: Long, pageIndex: Int, objectIndex: Int): Int

    override suspend fun open(uri: Uri, pfd: ParcelFileDescriptor): Result<EditingHandle> = withContext(Dispatchers.IO) {
        try {
            val handle = nativeOpen(uri.toString(), pfd, pfd.statSize, 0)
            Result.success(EditingHandle(handle))
        } catch (e: Exception) {
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
        newText: String, fontName: String, fontSizePt: Float, color: Int, matrix: FloatArray) =
        withContext(Dispatchers.IO) {
            nativeReplaceTextObject(handle.ptr, pageIndex, objectIndex, newText, fontName, fontSizePt, color, matrix)
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
}