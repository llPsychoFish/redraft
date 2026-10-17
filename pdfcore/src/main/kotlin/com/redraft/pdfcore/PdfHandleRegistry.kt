package com.redraft.pdfcore

import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug counter of open native PDFium handles (decision D-13).
 *
 * Thread-safe and framework-free so it can be asserted from JVM unit tests
 * and from debug build checks without touching the Android framework.
 */
object PdfHandleRegistry {

    private val openHandles = AtomicInteger(0)

    val openCount: Int
        get() = openHandles.get()

    fun register(kind: String) {
        openHandles.incrementAndGet()
    }

    fun unregister(kind: String) {
        while (true) {
            val current = openHandles.get()
            check(current > 0) {
                "PdfHandleRegistry underflow: unregistered '$kind' with no matching register"
            }
            if (openHandles.compareAndSet(current, current - 1)) return
        }
    }

    fun assertNoLeaks() {
        val open = openHandles.get()
        if (open != 0) {
            throw IllegalStateException("Native handle leak: $open open")
        }
    }
}
