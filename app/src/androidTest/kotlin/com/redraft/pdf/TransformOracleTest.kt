package com.redraft.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.redraft.pdfcore.PdfViewportTransform
import com.redraft.pdfcore.ScreenPoint
import com.redraft.pdfcore.ViewportState
import io.legere.pdfiumandroid.suspend.PdfDocumentKt
import io.legere.pdfiumandroid.suspend.PdfiumCoreKt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Oracle test: our [PdfViewportTransform.screenToPdf] must agree with PDFium's own
 * [mapDeviceCoordsToPage] at the viewport centre, for every rotation.
 *
 * For each fixture PDF we:
 * 1. Open page 0 via pdfiumandroid
 * 2. Read page width/height/rotation/cropBox from the binding
 * 3. Build our [PdfViewportTransform] with the same rendered size
 * 4. Compute screenToPdf at the viewport centre (zoom=1, pan=0)
 * 5. Call PDFium's mapDeviceCoordsToPage at the device centre
 * 6. Assert our result matches PDFium's result + crop origin, within 0.5 pt
 *
 * This is an instrumented test — it requires an emulator/device.
 */
@RunWith(AndroidJUnit4::class)
class TransformOracleTest {

    private val TOLERANCE_PT = 0.5f

    @Test
    fun screenToPdf_agrees_with_pdfium_oracle_for_all_rotations() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = listOf(
            "text_basic.pdf" to 0,
            "rotated90.pdf" to 1,
            "rotated270.pdf" to 3,
            "cropbox_offset.pdf" to 0,
        )

        for ((assetName, expectedRotationIndex) in fixtures) {
            val bytes = context.assets.open(assetName).readBytes()
            val core = PdfiumCoreKt(Dispatchers.IO)
            val document = core.newDocument(bytes)
            try {
                val page = document.openPage(0) ?: error("could not open page 0 in $assetName")
                try {
                    val pageWidthPt = page.getPageWidthPoint().toFloat()
                    val pageHeightPt = page.getPageHeightPoint().toFloat()
                    val rotationIndex = page.getPageRotation()
                    val cropBox = page.getPageCropBox()

                    val cropOriginX = minOf(cropBox.left, cropBox.right)
                    val cropOriginY = minOf(cropBox.top, cropBox.bottom)

                    val viewportWidthPx = pageWidthPt.toInt()
                    val quarterTurn = rotationIndex % 2 == 1
                    val renderedWidthPx = if (quarterTurn) {
                        (viewportWidthPx * pageWidthPt / pageHeightPt).roundToInt()
                    } else {
                        viewportWidthPx
                    }
                    val renderedHeightPx = if (quarterTurn) {
                        viewportWidthPx
                    } else {
                        (viewportWidthPx * pageHeightPt / pageWidthPt).roundToInt()
                    }

                    val transform = PdfViewportTransform(
                        pageWidthPt = pageWidthPt,
                        pageHeightPt = pageHeightPt,
                        rotationDegrees = 0,
                        renderedWidthPx = renderedWidthPx,
                        renderedHeightPx = renderedHeightPx,
                        cropOriginX = cropOriginX,
                        cropOriginY = cropOriginY,
                    )

                    val centreScreen = ScreenPoint(renderedWidthPx / 2f, renderedHeightPx / 2f)
                    val ourPdf = transform.screenToPdf(
                        screen = centreScreen,
                        viewport = ViewportState(zoom = 1f, panX = 0f, panY = 0f),
                    )

                    val pdfiumPt = page.mapDeviceCoordsToPage(
                        startX = 0,
                        startY = 0,
                        sizeX = renderedWidthPx,
                        sizeY = renderedHeightPx,
                        rotate = rotationIndex,
                        deviceX = renderedWidthPx / 2,
                        deviceY = renderedHeightPx / 2,
                    )

                    val expectedX = pdfiumPt.x + cropOriginX
                    val expectedY = pdfiumPt.y + cropOriginY

                    assertTrue(
                        "$assetName: X mismatch — ours=${ourPdf.x}, pdfium+crop=$expectedX",
                        abs(ourPdf.x - expectedX) <= TOLERANCE_PT,
                    )
                    assertTrue(
                        "$assetName: Y mismatch — ours=${ourPdf.y}, pdfium+crop=$expectedY",
                        abs(ourPdf.y - expectedY) <= TOLERANCE_PT,
                    )

                    assertTrue(
                        "$assetName: rotation index $rotationIndex != expected $expectedRotationIndex",
                        rotationIndex == expectedRotationIndex,
                    )
                } finally {
                    page.close()
                }
            } finally {
                document.close()
            }
        }
    }
}