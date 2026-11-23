package com.redraft.pdfedit

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of [PdfEditingEngine.editLine]; mirrors TextMutation::LineEditResult. */
enum class LineEditOutcome {
    OK,
    NO_OBJECTS,
    TEXT_MISMATCH,
    SET_TEXT_FAILED,
    GENERATE_FAILED,
    ;

    companion object {
        fun fromCode(code: Int): LineEditOutcome = when (code) {
            0 -> OK
            2 -> TEXT_MISMATCH
            3 -> SET_TEXT_FAILED
            4 -> GENERATE_FAILED
            else -> NO_OBJECTS
        }
    }
}

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
        /** Point size override; `0` keeps the original object's size. */
        fontSizePt: Float,
        /** ARGB override; alpha `0` keeps the original object's fill colour. */
        color: Int,
        /** Matrix override; `null` keeps the original object's matrix (its origin). */
        matrix: FloatArray?,
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

    /** The line's text built from the real text objects, with spaces inferred from the gaps. */
    suspend fun lineText(
        handle: EditingHandle,
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): String

    /** How many text objects the line is made of (used for logging and diagnostics). */
    suspend fun lineObjectCount(
        handle: EditingHandle,
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Int

    /**
     * The font size PDFium reports for the line's first text object
     * (`FPDFTextObj_GetFontSize`), or `0` when the line has no text object.
     *
     * This is the size the editor shows and a replacement reuses. The
     * character boxes carry a different number that is unreliable on some
     * documents (a 12 pt heading showed as 25 pt).
     */
    suspend fun lineFontSize(
        handle: EditingHandle,
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Float

    /** Trivial PDFium call proving symbols resolve; used by the self-test screen. */
    suspend fun pdfiumTest(): String

    /**
     * Edits the whole visual line, merging every text object on it into one.
     * Refuses (without changing the page) when the pieces do not add up to
     * [expectedText] once whitespace is ignored.
     */
    suspend fun editLine(
        handle: EditingHandle,
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        expectedText: String,
        newText: String,
        fontHandle: FontHandle,
        /** Point size override; `0` keeps the original object's size. */
        fontSizePt: Float,
        /** ARGB override; alpha `0` keeps the original object's fill colour. */
        color: Int,
        /** Matrix override; `null` keeps the original object's matrix (its origin). */
        matrix: FloatArray?,
        forceReplace: Boolean = false,
    ): LineEditOutcome
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
        newText: String, fontHandle: Long, fontSizePt: Float, color: Int, matrix: FloatArray?): Boolean
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
    private external fun nativeLineText(
        handle: Long, pageIndex: Int, left: Float, top: Float, right: Float, bottom: Float,
    ): String
    private external fun nativeLineObjectCount(
        handle: Long, pageIndex: Int, left: Float, top: Float, right: Float, bottom: Float,
    ): Int
    private external fun nativeLineFontSize(
        handle: Long, pageIndex: Int, left: Float, top: Float, right: Float, bottom: Float,
    ): Float
    private external fun nativePdfiumTest(): String
    private external fun nativeEditLine(
        handle: Long, pageIndex: Int, left: Float, top: Float, right: Float, bottom: Float,
        expectedText: String, newText: String, fontHandle: Long, fontSizePt: Float,
        color: Int, matrix: FloatArray?, forceReplace: Boolean,
    ): Int

    override suspend fun open(uri: Uri, pfd: ParcelFileDescriptor): Result<EditingHandle> = withContext(Dispatchers.IO) {
        try {
            // Argument order is (offset, length). The offset is always 0: the
            // descriptor points at the start of the PDF. Passing the size as the
            // offset left the editing document unopened, so Apply silently did
            // nothing on every document.
            // A provider may report statSize == -1; nativeOpen then asks fstat.
            val handle = nativeOpen(uri.toString(), pfd, 0L, pfd.statSize)
            if (handle == 0L) {
                Result.failure(IllegalStateException("PDFium could not open the document for editing"))
            } else {
                Result.success(EditingHandle(handle))
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
        newText: String, fontHandle: FontHandle, fontSizePt: Float, color: Int, matrix: FloatArray?): Boolean =
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

    override suspend fun lineText(
        handle: EditingHandle,
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): String = withContext(Dispatchers.IO) {
        nativeLineText(handle.ptr, pageIndex, left, top, right, bottom)
    }

    override suspend fun lineObjectCount(
        handle: EditingHandle,
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Int = withContext(Dispatchers.IO) {
        nativeLineObjectCount(handle.ptr, pageIndex, left, top, right, bottom)
    }

    override suspend fun lineFontSize(
        handle: EditingHandle,
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Float = withContext(Dispatchers.IO) {
        nativeLineFontSize(handle.ptr, pageIndex, left, top, right, bottom)
    }

    /** Trivial PDFium call proving symbols resolve; used by the self-test screen. */
    override suspend fun pdfiumTest(): String = withContext(Dispatchers.IO) {
        nativePdfiumTest()
    }

    override suspend fun editLine(
        handle: EditingHandle,
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        expectedText: String,
        newText: String,
        fontHandle: FontHandle,
        fontSizePt: Float,
        color: Int,
        matrix: FloatArray?,
        forceReplace: Boolean,
    ): LineEditOutcome = withContext(Dispatchers.IO) {
        LineEditOutcome.fromCode(
            nativeEditLine(
                handle.ptr, pageIndex, left, top, right, bottom,
                expectedText, newText, fontHandle.ptr, fontSizePt, color, matrix, forceReplace,
            ),
        )
    }
}
