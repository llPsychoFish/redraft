package com.redraft.pdf

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.redraft.pdfcore.PdfPageGeometry
import com.redraft.pdfcore.PdfRect
import com.redraft.pdfcore.selection.PageTextLayout
import com.redraft.pdfcore.selection.pageTextLayout
import io.legere.pdfiumandroid.suspend.PdfiumCoreKt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProbeTest {
    @Test
    fun probe() {
        runBlocking(Dispatchers.IO) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val testAssets = instrumentation.context.assets
            val names = listOf(
                "two_columns.pdf",
                "table.pdf",
                "list_bulleted.pdf",
                "list_lettered.pdf",
                "justified.pdf",
                "fragmented_runs.pdf",
                "scan_only.pdf",
            )
            for (name in names) {
                val bytes = testAssets.open(name).readBytes()
                val core = PdfiumCoreKt(Dispatchers.IO)
                val document = core.newDocument(bytes)
                try {
                    val geometry = PdfPageGeometry(0, PdfRect(0f, 0f, 612f, 792f), 0)
                    val layout = pageTextLayout(document, 0, geometry)
                    Log.i("PROBE", "=== $name -> ${layout::class.simpleName}")
                    if (layout is PageTextLayout.Ready) {
                        val chars = layout.layout.chars
                        Log.i("PROBE", "  chars=${chars.size}")
                        chars.take(40).forEach { c ->
                            Log.i(
                                "PROBE",
                                "  CHAR idx=${c.index} text=[${c.text.replace(" ", "_")}] " +
                                    "rect=(${c.rect.minX}, ${c.rect.minY})-(${c.rect.maxX}, ${c.rect.maxY}) " +
                                    "size=${c.fontSizePt}",
                            )
                        }
                        layout.layout.reducedLines.forEachIndexed { i, line ->
                            val text = line.chars.joinToString("") { it.text }
                            Log.i(
                                "PROBE",
                                "  LINE $i text=[${text.replace(" ", "_")}] " +
                                    "rect=(${line.rect.minX}, ${line.rect.minY})-(${line.rect.maxX}, ${line.rect.maxY})",
                            )
                        }
                    }
                } finally {
                    document.close()
                }
            }
        }
    }
}
