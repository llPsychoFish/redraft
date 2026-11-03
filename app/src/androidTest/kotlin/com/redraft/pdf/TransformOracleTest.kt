package com.redraft.pdf

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.redraft.pdfcore.PdfPageGeometry
import com.redraft.pdfcore.PdfRect
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
 * [mapDeviceCoordsToPage] for every rotation, at more than one point.
 *
 * For each fixture PDF we:
 * 1. Open page 0 via pdfiumandroid
 * 2. Read the page's UNROTATED CropBox and rotation index from the binding
 * 3. Build the production geometry (`PdfPageGeometry` + `slotBaseSizePx`) and
 *    feed the transform the page's real rotation — a `0` here compared two
 *    different mappings and never reached the 90/270 arms of `pdfToRotated`
 * 4. Compare our `screenToPdf` with PDFium's `mapDeviceCoordsToPage` at the
 *    viewport centre AND at a quarter point, where a rotation error cannot
 *    cancel out
 * 5. Assert agreement within 0.5 pt
 *
 * This is an instrumented test — it requires an emulator/device.
 */
@RunWith(AndroidJUnit4::class)
class TransformOracleTest {

    private val tolerancePt = 0.5f

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
                    val rotationIndex = page.getPageRotation()
                    val cropBox = page.getPageCropBox()

                    assertTrue(
                        "$assetName: rotation index $rotationIndex != expected $expectedRotationIndex",
                        rotationIndex == expectedRotationIndex,
                    )

                    // The same geometry rule the viewer uses: the UNROTATED CropBox
                    // plus the coerced index. The transform is the only rotation
                    // authority, so its rendered size is the unrotated base size.
                    val geometry = PdfPageGeometry(
                        pageIndex = 0,
                        cropBox = PdfRect(
                            minX = minOf(cropBox.left, cropBox.right),
                            minY = minOf(cropBox.top, cropBox.bottom),
                            maxX = maxOf(cropBox.left, cropBox.right),
                            maxY = maxOf(cropBox.top, cropBox.bottom),
                        ),
                        rotationIndex = rotationIndex.coerceIn(0, 3),
                    )
                    val base = geometry.slotBaseSizePx(geometry.displayWidthPt.roundToInt())
                    val transform = PdfViewportTransform(
                        pageWidthPt = geometry.widthPt,
                        pageHeightPt = geometry.heightPt,
                        rotationDegrees = geometry.rotationDegrees,
                        renderedWidthPx = base.widthPx,
                        renderedHeightPx = base.heightPx,
                        cropOriginX = geometry.cropBox.minX,
                        cropOriginY = geometry.cropBox.minY,
                    )

                    val display = transform.renderedSize(zoom = 1f)
                    // Integer device points: the oracle takes ints, so a probe that
                    // needs rounding would blur the comparison by up to 0.5 px.
                    val probes = listOf(
                        Triple("centre", display.widthPx / 2, display.heightPx / 2),
                        Triple("quarter", display.widthPx / 4, display.heightPx * 3 / 4),
                    )

                    for ((label, deviceX, deviceY) in probes) {
                        val ourPdf = transform.screenToPdf(
                            screen = ScreenPoint(deviceX.toFloat(), deviceY.toFloat()),
                            viewport = ViewportState(zoom = 1f, panX = 0f, panY = 0f),
                        )

                        val pdfiumPt = page.mapDeviceCoordsToPage(
                            startX = 0,
                            startY = 0,
                            sizeX = display.widthPx,
                            sizeY = display.heightPx,
                            rotate = rotationIndex,
                            deviceX = deviceX,
                            deviceY = deviceY,
                        )

                        val expectedX = pdfiumPt.x + geometry.cropBox.minX
                        val expectedY = pdfiumPt.y + geometry.cropBox.minY

                        assertTrue(
                            "$assetName ($label): X mismatch — ours=${ourPdf.x}, pdfium+crop=$expectedX",
                            abs(ourPdf.x - expectedX) <= tolerancePt,
                        )
                        assertTrue(
                            "$assetName ($label): Y mismatch — ours=${ourPdf.y}, pdfium+crop=$expectedY",
                            abs(ourPdf.y - expectedY) <= tolerancePt,
                        )
                    }
                } finally {
                    page.close()
                }
            } finally {
                document.close()
            }
        }
    }
}
