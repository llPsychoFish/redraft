package com.redraft.ui.viewer

/** Why an edit could not be applied. Mapped to user-facing copy by [EditorPanel]. */
enum class EditorEditFailure {
    NO_ENGINE,
    NO_SELECTION,
    TARGET_NOT_FOUND,
    PIECES_DO_NOT_ADD_UP,
    FONT_FAILED,
    SAVE_FAILED,
    /** The saved copy could not be re-rendered in time; the panel stays open. */
    RERENDER_FAILED,
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
    /** User-selected font size for formatting (defaults to the line's own size). */
    val selectedFontSizePt: Float = 0f,   // 0 = use the line's own fontSizePt
    /** User-selected bold for formatting. */
    val selectedBold: Boolean = false,
    /** User-selected italic for formatting. */
    val selectedItalic: Boolean = false,
    /** User-selected text colour for formatting (defaults to the line's own colour). */
    val selectedColor: Int = 0,           // 0 = use the line's own textColor
    /** Diagnostic: native result code from the failing edit step. */
    val applyErrorCode: Int? = null,
    /** Diagnostic: "in-place" or "replacement" path that was attempted. */
    val applyErrorPath: String? = null,
    /** Diagnostic: the editor's draft text at failure time. */
    val applyErrorEditorText: String? = null,
    /** Diagnostic: the text found in the PDF objects at failure time. */
    val applyErrorPdfText: String? = null,
    /** Diagnostic: the font name used or attempted. */
    val applyErrorFontName: String? = null,
    /** Diagnostic: the font size used or attempted. */
    val applyErrorFontSize: Float? = null,
    /** Diagnostic: exception stack trace if any. */
    val applyErrorStackTrace: String? = null,
    /** Diagnostic: device ABI. */
    val applyErrorAbi: String? = null,
) {
    fun withDraftText(newText: String): EditorPanelState =
        copy(draftText = newText, applyError = null)

    /** Effective font size: the user's selection, or the line's own when unset. */
    val effectiveFontSizePt: Float get() = if (selectedFontSizePt > 0f) selectedFontSizePt else fontSizePt
    /** Effective colour: the user's selection, or the line's own when unset. */
    val effectiveColor: Int get() = if (selectedColor != 0) selectedColor else textColor
}
