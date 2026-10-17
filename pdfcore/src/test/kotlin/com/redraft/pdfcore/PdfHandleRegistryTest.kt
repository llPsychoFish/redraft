package com.redraft.pdfcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfHandleRegistryTest {

    @Test
    fun `register and unregister return openCount to zero`() {
        PdfHandleRegistry.register("document")
        assertEquals(1, PdfHandleRegistry.openCount)
        PdfHandleRegistry.unregister("document")
        assertEquals(0, PdfHandleRegistry.openCount)
    }

    @Test
    fun `assertNoLeaks passes when nothing is open`() {
        PdfHandleRegistry.assertNoLeaks()
    }

    @Test
    fun `assertNoLeaks throws after a simulated unclosed handle`() {
        PdfHandleRegistry.register("document")
        try {
            val thrown = assertThrows(IllegalStateException::class.java) {
                PdfHandleRegistry.assertNoLeaks()
            }
            assertEquals("Native handle leak: 1 open", thrown.message)
        } finally {
            PdfHandleRegistry.unregister("document")
        }
    }

    @Test
    fun `double unregister is rejected and openCount stays consistent`() {
        PdfHandleRegistry.register("document")
        PdfHandleRegistry.unregister("document")
        assertThrows(IllegalStateException::class.java) {
            PdfHandleRegistry.unregister("document")
        }
        assertEquals(0, PdfHandleRegistry.openCount)
    }
}
