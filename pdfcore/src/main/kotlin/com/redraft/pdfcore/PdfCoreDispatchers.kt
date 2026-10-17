package com.redraft.pdfcore

import android.os.Looper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Single-threaded dispatcher for every PDFium call.
 *
 * PDFium document/page handles are not thread-safe per document. Routing all
 * native entry points through one limited-parallelism dispatcher serializes
 * access without requiring external locks (decision D-11).
 */
val pdfDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

/**
 * Guard enforcing that no PDFium call happens on the main thread (VIEWER-07).
 *
 * Called at the top of every native entry point in [PdfDocumentHandle]. The
 * actual check is delegated to [isMainThread], which is split out so it can be
 * reasoned about independently of the framework.
 */
fun assertNotMainThread() {
    if (isMainThread()) {
        throw IllegalStateException("PDFium call on main thread")
    }
}

private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
