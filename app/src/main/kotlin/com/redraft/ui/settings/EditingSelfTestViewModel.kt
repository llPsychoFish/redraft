package com.redraft.ui.settings

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import com.redraft.data.SettingsRepository
import com.redraft.data.SettingsStore
import com.redraft.pdfcore.PdfAssetLoader
import com.redraft.pdfcore.PdfDocumentHandle
import com.redraft.pdfcore.PdfPageGeometry
import com.redraft.pdfcore.PdfRect
import com.redraft.pdfcore.selection.CharBox
import com.redraft.pdfcore.selection.PageTextLayout
import com.redraft.pdfcore.selection.TextPageLayout
import com.redraft.pdfcore.selection.pageTextLayout
import com.redraft.pdfedit.FontHandle
import com.redraft.pdfedit.LineEditOutcome
import com.redraft.pdfedit.PdfEditModule
import com.redraft.pdfedit.PdfEditingEngine
import com.redraft.pdfedit.EditingHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private const val TAG = "EditingSelfTest"

/** One step of the self-test. */
data class SelfTestStep(
    val name: String,
    var status: Status = Status.PENDING,
    var detail: String? = null,
)

enum class Status { PENDING, RUNNING, OK, FAIL }

/** The full self-test report, suitable for copying to the clipboard. */
data class SelfTestReport(
    val abi: String,
    val androidVersion: Int,
    val steps: List<SelfTestStep>,
) {
    fun toPlainText(): String {
        val sb = StringBuilder()
        sb.append("Redraft Editing Self-Test Report\n")
        sb.append("===============================\n")
        sb.append("ABI: $abi\n")
        sb.append("Android SDK: $androidVersion\n")
        sb.append("\n")
        for (step in steps) {
            sb.append("${step.name}: ")
            sb.append(when (step.status) {
                Status.OK -> "PASS"
                Status.FAIL -> "FAIL"
                Status.RUNNING -> "RUNNING"
                Status.PENDING -> "PENDING"
            })
            step.detail?.let { sb.append(" — $it") }
            sb.append("\n")
        }
        return sb.toString()
    }
}

class EditingSelfTestViewModel(private val context: Context, private val store: SettingsStore) : ViewModel() {

    private val _report = MutableStateFlow<SelfTestReport?>(null)
    val report: StateFlow<SelfTestReport?> = _report.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _copyRequested = MutableStateFlow<Boolean>(false)
    val copyRequested: StateFlow<Boolean> = _copyRequested.asStateFlow()

    private var editingEngine: PdfEditingEngine? = null

    fun runSelfTest() {
        viewModelScope.launch {
            _running.value = true
            try {
                val steps = mutableListOf<SelfTestStep>()

                // Step 1: Native library loads
                val step1 = SelfTestStep("1. Native library pdfedit loads")
                steps += step1.run {
                    status = Status.RUNNING
                    try {
                        val engine = PdfEditModule.engine()
                        editingEngine = engine
                        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: Build.CPU_ABI ?: "unknown"
                        val sdk = Build.VERSION.SDK_INT
                        detail = "ABI=$abi, SDK=$sdk, libraryLoaded=${PdfEditModule.isLibraryLoaded}"
                        status = Status.OK
                    } catch (e: Throwable) {
                        detail = "${e::class.simpleName}: ${e.message ?: e}"
                        status = Status.FAIL
                    }
                    this
                }

                // Step 2: libpdfium symbols resolve
                val step2 = SelfTestStep("2. libpdfium symbols resolve")
                steps += step2.run {
                    status = Status.RUNNING
                    try {
                        val result = editingEngine?.pdfiumTest() ?: "engine missing"
                        detail = if (result.isNotEmpty()) "PDFium responded: $result" else "no response"
                        status = if (result.isNotEmpty()) Status.OK else Status.FAIL
                    } catch (e: Throwable) {
                        detail = "${e::class.simpleName}: ${e.message ?: e}"
                        status = Status.FAIL
                    }
                    this
                }

                // Steps 3-9 need an open editing document on A_single.pdf
                val testPdf = "A_single.pdf"
                var docHandle: PdfDocumentHandle? = null
                var editDocHandle: EditingHandle? = null

                try {
                    // Step 3: Open bundled test PDFs from assets
                    val step3 = SelfTestStep("3. Open test PDFs from assets")
                    steps += step3.run {
                        status = Status.RUNNING
                        try {
                            docHandle = withContext(Dispatchers.IO) {
                                PdfAssetLoader.openAsset(context, testPdf)
                            }
                            val pages = docHandle?.pageCount() ?: 0
                            detail = "$testPdf: $pages page(s)"
                            status = if (pages > 0) Status.OK else Status.FAIL
                        } catch (e: Throwable) {
                            detail = "${e::class.simpleName}: ${e.message ?: e}"
                            status = Status.FAIL
                        }
                        this
                    }

                    // Step 4: Edit a line in place (editLine, no font override)
                    val step4 = SelfTestStep("4. Edit line in place (editLine)")
                    steps += step4.run {
                        status = Status.RUNNING
                        try {
                            editDocHandle = openEditingDoc(testPdf) ?: throw IllegalStateException("could not open editing doc")
                            val lineRect = findFirstLineRect(docHandle!!)
                            if (lineRect == null) {
                                detail = "no line found on page 0"
                                status = Status.FAIL
                            } else {
                                val ptResult = withContext(Dispatchers.IO) {
                                    pageTextLayout(docHandle!!.document, 0, PdfPageGeometry.build(0, null, null, 0))
                                }
                                val layout = if (ptResult is PageTextLayout.Ready) ptResult.layout else throw IllegalStateException("no layout")
                                val expectedText = firstLineText(layout)
                                val engine = editingEngine ?: throw IllegalStateException("no engine")
                                val result = withContext(Dispatchers.IO) {
                                    engine.editLine(
                                        handle = editDocHandle!!,
                                        pageIndex = 0,
                                        left = lineRect.minX,
                                        top = lineRect.minY,
                                        right = lineRect.maxX,
                                        bottom = lineRect.maxY,
                                        expectedText = expectedText,
                                        newText = expectedText,
                                        fontHandle = FontHandle(0),
                                        fontSizePt = 0f,
                                        color = 0,
                                        matrix = null,
                                        forceReplace = false,
                                    )
                                }
                                detail = "editLine -> $result"
                                status = if (result == LineEditOutcome.OK) Status.OK else Status.FAIL
                            }
                        } catch (e: Throwable) {
                            detail = "${e::class.simpleName}: ${e.message ?: e}"
                            status = Status.FAIL
                        }
                        this
                    }

                    // Step 5: Edit with bundled Liberation font (forceReplace = true)
                    val step5 = SelfTestStep("5. Edit line with Liberation font (forceReplace)")
                    steps += step5.run {
                        status = Status.RUNNING
                        try {
                            val engine = editingEngine ?: throw IllegalStateException("no engine")
                            val doc = editDocHandle ?: throw IllegalStateException("no editing doc")
                            val fontHandle = withContext(Dispatchers.IO) {
                                engine.loadBundledFont(doc, "LiberationSans-Regular", false, false)
                            }
                            val lineRect = findFirstLineRect(docHandle!!)
                            if (lineRect == null || fontHandle.ptr == 0L) {
                                detail = if (lineRect == null) "no line found" else "font handle is null"
                                status = Status.FAIL
                            } else {
                                val ptResult = withContext(Dispatchers.IO) {
                                    pageTextLayout(docHandle!!.document, 0, PdfPageGeometry.build(0, null, null, 0))
                                }
                                val layout = if (ptResult is PageTextLayout.Ready) ptResult.layout else throw IllegalStateException("no layout")
                                val expectedText = firstLineText(layout)
                                val result = withContext(Dispatchers.IO) {
                                    engine.editLine(
                                        handle = doc,
                                        pageIndex = 0,
                                        left = lineRect.minX,
                                        top = lineRect.minY,
                                        right = lineRect.maxX,
                                        bottom = lineRect.maxY,
                                        expectedText = expectedText,
                                        newText = expectedText,
                                        fontHandle = fontHandle,
                                        fontSizePt = 0f,
                                        color = 0,
                                        matrix = null,
                                        forceReplace = true,
                                    )
                                }
                                detail = "font=${fontHandle.ptr}, editLine -> $result"
                                status = if (result == LineEditOutcome.OK) Status.OK else Status.FAIL
                            }
                        } catch (e: Throwable) {
                            detail = "${e::class.simpleName}: ${e.message ?: e}"
                            status = Status.FAIL
                        }
                        this
                    }

                    // Step 6: Save a copy to app cache
                    val step6 = SelfTestStep("6. Save copy to app cache")
                    steps += step6.run {
                        status = Status.RUNNING
                        try {
                            val engine = editingEngine ?: throw IllegalStateException("no engine")
                            val doc = editDocHandle ?: throw IllegalStateException("no editing doc")
                            val cacheFile = File(context.cacheDir, "self_test_${System.currentTimeMillis()}.pdf")
                            val saved = withContext(Dispatchers.IO) {
                                engine.saveAsCopy(doc, cacheFile.absolutePath)
                            }
                            detail = if (saved && cacheFile.exists()) "${cacheFile.length()} bytes" else "save failed"
                            status = if (saved && cacheFile.exists()) Status.OK else Status.FAIL
                        } catch (e: Throwable) {
                            detail = "${e::class.simpleName}: ${e.message ?: e}"
                            status = Status.FAIL
                        }
                        this
                    }

                    // Step 7: Reopen saved copy with normal renderer
                    val step7 = SelfTestStep("7. Reopen saved copy with renderer")
                    steps += step7.run {
                        status = Status.RUNNING
                        try {
                            val cacheFile = latestCacheFile() ?: throw IllegalStateException("no saved file found")
                            val reopened = withContext(Dispatchers.IO) {
                                PdfDocumentHandle.open(cacheFile.readBytes())
                            }
                            val pages = reopened.pageCount()
                            reopened.close()
                            detail = "$pages page(s)"
                            status = if (pages > 0) Status.OK else Status.FAIL
                        } catch (e: Throwable) {
                            detail = "${e::class.simpleName}: ${e.message ?: e}"
                            status = Status.FAIL
                        }
                        this
                    }

                    // Step 8: Extract text and compare
                    val step8 = SelfTestStep("8. Extract text and compare")
                    steps += step8.run {
                        status = Status.RUNNING
                        try {
                            val cacheFile = latestCacheFile() ?: throw IllegalStateException("no saved file found")
                            val reopened = withContext(Dispatchers.IO) {
                                PdfDocumentHandle.open(cacheFile.readBytes())
                            }
                            val geometry = reopened.pageSize(0)
                            val ptResult = withContext(Dispatchers.IO) {
                                pageTextLayout(reopened.document, 0, geometry)
                            }
                            val layout = if (ptResult is PageTextLayout.Ready) ptResult.layout else throw IllegalStateException("no layout")
                            val extracted = layout.reducedLines.joinToString(" ") { it.chars.joinToString("") { it.text } }
                            reopened.close()
                            val expected = "KOFORIDUA TECHNICAL UNIVERSITY"
                            val match = extracted.contains(expected)
                            detail = "found='$extracted' expected='$expected' -> ${if (match) "MATCH" else "MISMATCH"}"
                            status = if (match) Status.OK else Status.FAIL
                        } catch (e: Throwable) {
                            detail = "${e::class.simpleName}: ${e.message ?: e}"
                            status = Status.FAIL
                        }
                        this
                    }

                    // Step 9: Render page to bitmap, check not blank
                    val step9 = SelfTestStep("9. Render page to bitmap, not blank")
                    steps += step9.run {
                        status = Status.RUNNING
                        try {
                            val cacheFile = latestCacheFile() ?: throw IllegalStateException("no saved file found")
                            val reopened = withContext(Dispatchers.IO) {
                                PdfDocumentHandle.open(cacheFile.readBytes())
                            }
                            val geometry = reopened.pageSize(0)
                            val width = (geometry.cropBox.width * 2f).roundToInt().coerceAtLeast(1)
                            val height = (geometry.cropBox.height * 2f).roundToInt().coerceAtLeast(1)
                            val bitmap = withContext(Dispatchers.IO) {
                                reopened.renderPage(0, width, height)
                            }
                            val nonBlank = isNotBlank(bitmap)
                            bitmap.recycle()
                            reopened.close()
                            detail = "$width x $height, nonBlank=$nonBlank"
                            status = if (nonBlank) Status.OK else Status.FAIL
                        } catch (e: Throwable) {
                            detail = "${e::class.simpleName}: ${e.message ?: e}"
                            status = Status.FAIL
                        }
                        this
                    }
                } finally {
                    docHandle?.close()
                    editDocHandle?.let { editingEngine?.close(it) }
                }

                val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: Build.CPU_ABI ?: "unknown"
                val sdk = Build.VERSION.SDK_INT
                _report.value = SelfTestReport(abi, sdk, steps)
            } finally {
                _running.value = false
            }
        }
    }

    fun requestCopy() {
        _copyRequested.value = true
    }

    fun copyAcknowledged() {
        _copyRequested.value = false
    }

    private suspend fun openEditingDoc(assetName: String): EditingHandle? {
        val engine = editingEngine ?: return null
        val fd = context.assets.openFd(assetName)
        return try {
            engine.open(
                uri = android.net.Uri.parse("file:///android_asset/$assetName"),
                pfd = fd.parcelFileDescriptor,
            ).getOrNull()
        } finally {
            fd.close()
        }
    }

    private suspend fun findFirstLineRect(docHandle: PdfDocumentHandle): PdfRect? {
        val geometry = docHandle.pageSize(0)
        val result = withContext(Dispatchers.IO) {
            pageTextLayout(docHandle.document, 0, geometry)
        }
        val layout = if (result is PageTextLayout.Ready) result.layout else return null
        return layout.reducedLines.firstOrNull()?.rect
    }

    private suspend fun firstLineText(layout: TextPageLayout): String {
        return layout.reducedLines.firstOrNull()?.let { it.chars.joinToString("") { it.text } }.orEmpty()
    }

    private fun latestCacheFile(): File? {
        return context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("self_test_") && it.name.endsWith(".pdf") }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun isNotBlank(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var nonWhite = 0
        for (i in pixels.indices) {
            if (pixels[i] != -1) { // -1 = 0xFFFFFFFF = white
                nonWhite++
                if (nonWhite > 100) return true
            }
        }
        return false
    }

    companion object {
        fun factory(context: Context): androidx.lifecycle.ViewModelProvider.Factory {
            val app = context.applicationContext
            return viewModelFactory {
                initializer {
                    EditingSelfTestViewModel(app, SettingsRepository(app))
                }
            }
        }
    }
}
