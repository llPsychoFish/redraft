package com.redraft.ui.viewer

/** Why an edit could not be applied. Mapped to user-facing copy by [EditorPanel]. */
enum class EditorEditFailure {
    NO_ENGINE,
    NO_SELECTION,
    TARGET_NOT_FOUND,
    PIECES_DO_NOT_ADD_UP,
    FONT_FAILED,
    SAVE_FAILED,
    UNSUPPORTED_CHARACTER,
    GENERIC,
}

data class EditorPanelState(
    val originalText: String,
    val draftText: String,
    val fontSizePt: Float,
    val textColor: Int,           // ARGB
    val fontChangeNotice: String? = null,  // e.g., "Liberation Sans"
    val lengthWarning: Boolean = false,
    val canApply: Boolean = true,
    val showUnsupportedChar: Char? = null,
    /** True from the moment Apply is tapped until the edit finishes (success or failure). */
    val applyInProgress: Boolean = false,
    /** Why the last Apply failed; shown inside the panel, never only in a snackbar. */
    val applyError: EditorEditFailure? = null,
    /** Technical detail for the failure (e.g. the library/loader error). */
    val applyErrorDetail: String? = null,
    /** How many PDF text objects the line is made of; 0 until the engine reports it. */
    val lineObjectCount: Int = 0,
) {
    fun withDraftText(newText: String): EditorPanelState =
        copy(draftText = newText, applyError = null)
}
