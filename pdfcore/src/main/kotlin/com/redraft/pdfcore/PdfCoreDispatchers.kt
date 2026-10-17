package com.redraft.pdfcore

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
