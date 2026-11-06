package com.redraft.ui.viewer

data class EditorPanelState(
    val originalText: String,
    val draftText: String,
    val fontSizePt: Float,
    val textColor: Int,           // ARGB
    val fontChangeNotice: String? = null,  // e.g., "Liberation Sans"
    val lengthWarning: Boolean = false,
    val canApply: Boolean = true,
    val showUnsupportedChar: Char? = null,
) {
    fun withDraftText(newText: String): EditorPanelState = copy(draftText = newText)
}