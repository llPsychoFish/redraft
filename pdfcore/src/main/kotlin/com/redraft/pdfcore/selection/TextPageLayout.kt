package com.redraft.pdfcore.selection

/**
 * Everything the reducer needs from one page, as plain data.
 *
 * [reducedLines] is derived eagerly at construction from [chars] using the
 * [reduceCharsToLines] pipeline.  The [blockId] field of each line is `null`
 * until [groupBlocks] is applied separately.
 */
data class TextPageLayout(
    val pageIndex: Int,
    val chars: List<CharBox>,
) {
    /**
     * Visual lines derived from [chars], with null [TextLine.blockId].
     * Call [groupBlocks] to assign block IDs for paragraph metadata.
     */
    val reducedLines: List<TextLine> by lazy {
        reduceCharsToLines(chars)
    }
}

/** Result of extracting one page as plain data (D-23). */
sealed interface PageTextLayout {
    data class Ready(val layout: TextPageLayout) : PageTextLayout
    data object Empty : PageTextLayout
    data object Failed : PageTextLayout
}
