package com.redraft.pdfcore.selection

import com.redraft.pdfcore.PdfRect

/**
 * One extracted character in ABSOLUTE PDF user space (origin bottom-left, y up, points).
 *
 * Coordinates stay absolute end to end: the CropBox origin is subtracted exactly
 * once, inside `PdfViewportTransform` (02-RESEARCH §2.1), so taps (`screenToPdf`),
 * overlay quads (`pdfToScreen`) and these boxes all live in one space — nothing
 * outside the transform may apply a crop or rotation step (D-02, D-14).
 *
 * @param index PDFium's character index — the stable basis for `lineId` (D-17).
 * @param text one UTF-16 unit as returned by the text page; `""` when the full
 *   string read is shorter than [index].
 * @param rect normalised with per-axis min/max, so height is always >= 0 even
 *   though PDFium reports char boxes y-up (02-RESEARCH §1.3).
 * @param fontSizePt em size in points from `getFontSize`; 0 when PDFium reports
 *   nothing (the reducer then falls back to the box height).
 */
data class CharBox(
    val index: Int,
    val text: String,
    val rect: PdfRect,
    val fontSizePt: Float,
)
