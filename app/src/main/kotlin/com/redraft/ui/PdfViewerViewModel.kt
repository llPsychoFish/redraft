package com.redraft.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.util.Log
import com.redraft.data.DocumentError
import com.redraft.pdfcore.PdfPoint
import com.redraft.pdfcore.PdfRect
import com.redraft.pdfcore.PdfViewportTransform
import com.redraft.pdfcore.selection.TapTolerance
import com.redraft.pdfcore.selection.groupBlocks
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import com.redraft.pdfcore.selection.hitTest
import com.redraft.pdfcore.selection.pageTextLayout
import com.redraft.data.DocumentHandle
import com.redraft.data.RecentFile
import com.redraft.data.RecentFilesRepository
import com.redraft.data.RecentsStore
import com.redraft.data.SafDocumentOpener
import com.redraft.pdfcore.ViewportState
import com.redraft.pdfcore.PdfPageGeometry
import com.redraft.pdfedit.EditingHandle
import com.redraft.pdfedit.FontHandle
import com.redraft.pdfedit.PdfEditModule
import com.redraft.ui.viewer.EditorPanelState
import com.redraft.ui.viewer.PageBitmapCache
import com.redraft.ui.viewer.PageKey
import com.redraft.ui.viewer.renderedZoomForBucket
import com.redraft.ui.viewer.scaleBucketForZoom
import com.redraft.ui.overview.ThumbnailCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.LinkedHashMap
import java.util.regex.Pattern

/**
 * A one-shot request to move the list to a page.
 *
 * The token makes every command distinct, so two commands to the SAME page
 * in a row are two commands and not one.
 */
data class ScrollCommand(
    val pageIndex: Int,
    val offsetPx: Int = 0,
    val token: Long = 0L,
)

/** One committed text edit. Stores full before/after state for undo/redo. */
data class EditCommand(
    val documentId: String,           // URI or content hash
    val pageIndex: Int,
    val lineId: String,               // blockId from selection (p{page}:b{firstChar})
    val objectIndex: Int,             // PDFium text object index in editing document
    val oldText: String,
    val newText: String,
    val oldFontName: String,
    val newFontName: String,
    /** Size override for this edit; `0` keeps the original object's size. */
    val fontSizePt: Float,
    /** ARGB colour override; alpha `0` keeps the original object's colour. */
    val color: Int,
    val baselineRect: PdfRect,        // PDF coords for reselection
    /** Matrix override; `null` keeps the original object's matrix (its origin). */
    val matrix: FloatArray?,
    /** The line's original size, restored by undo. */
    val originalFontSizePt: Float = 0f,
    /** The line's original ARGB colour, restored by undo. */
    val originalColor: Int = 0,
) {
    fun inverse(): EditCommand = copy(
        oldText = newText,
        newText = oldText,
        oldFontName = newFontName,
        newFontName = oldFontName,
        // Undo restores the look the line had before the edit.
        fontSizePt = originalFontSizePt,
        color = originalColor,
        matrix = null,
    )
}

/** Font fallback decision for an edit. */
enum class FontFallbackDecision {
    KEEP_ORIGINAL,        // Original font can encode the new text
    USE_BUNDLED_FALLBACK, // Subset font or unsupported chars → use Liberation font
    UNSUPPORTED_CHARS     // New text contains chars outside Liberation Latin coverage
}

/** Analysis of the original font for fallback decision. */
data class FontAnalysis(
    val originalFontName: String,
    val isSubset: Boolean,
    val isStandard14: Boolean,
    val bold: Boolean,
    val italic: Boolean,
)

/** Constants for font fallback logic (D-304). */
private object FontFallbackConstants {
    // Standard 14 PostScript fonts
    val STANDARD_14_FONTS = setOf(
        "Times-Roman", "Times-Bold", "Times-Italic", "Times-BoldItalic",
        "Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique",
        "Courier", "Courier-Bold", "Courier-Oblique", "Courier-BoldOblique",
        "Symbol", "ZapfDingbats"
    )
    
    // WinAnsi encoding range (0x20-0xFF)
    val WIN_ANSI_CHARS: Set<Char> = (0x20..0xFF).map { it.toChar() }.toSet()
    
    // Liberation fonts cover Latin Extended-A (U+0020..U+024F)
    val LIBERATION_LATIN_CHARS: Set<Char> = (0x0020..0x024F).map { it.toChar() }.toSet()
}

/**
 * True for a PDF subset-font name such as `ABCDEF+TimesNewRomanPSMT`.
 *
 * The six-letter tag is a PREFIX; a whole-string match would never be true for a
 * real font name (the old bug), so every real font looked non-subset.
 */
private fun isSubsetFontName(fontName: String): Boolean =
    Regex("^[A-Z]{6}\\+").containsMatchIn(fontName)

/**
 * Builds the font facts used by the fallback decision.
 *
 * `fontName` is empty when extraction cannot supply one (the pinned binding exposes
 * no per-fragment font name). An empty name is deliberately treated as "font
 * unknown": unchanged text keeps the original object, and any NEW character falls
 * through to the bundled Liberation font. That is the documented fallback.
 */
private fun fontAnalysisOf(fontName: String): FontAnalysis = FontAnalysis(
    originalFontName = fontName,
    isSubset = isSubsetFontName(fontName),
    isStandard14 = FontFallbackConstants.STANDARD_14_FONTS.contains(fontName.substringAfterLast("+")),
    bold = fontName.contains("Bold", true),
    italic = fontName.contains("Italic", true) || fontName.contains("Oblique", true),
)

/** Human-readable name for a bundled font asset: "LiberationSans-Bold" → "Liberation Sans Bold". */
internal fun bundledFontDisplayName(assetStem: String): String =
    assetStem
        .replace("LiberationSans", "Liberation Sans")
        .replace("LiberationSerif", "Liberation Serif")
        .replace("LiberationMono", "Liberation Mono")
        .replace("-BoldItalic", " Bold Italic")
        .replace("-Bold", " Bold")
        .replace("-Italic", " Italic")
        .replace("-Regular", "")

/**
 * The line's text as the editor should show it.
 *
 * PDFium does not always emit a space character for a word gap — this PDF's
 * heading has none between "KOFORIDUA" and "TECHNICAL" — so infer one from the
 * glyph geometry: a gap wider than 0.2 em between two glyphs is a word gap.
 */
internal fun editableLineText(line: com.redraft.pdfcore.selection.TextLine): String {
    val builder = StringBuilder()
    var previous: com.redraft.pdfcore.selection.CharBox? = null
    for (char in line.chars) {
        val prev = previous
        if (prev != null && prev.text.isNotBlank() && char.text.isNotBlank()) {
            val size = maxOf(prev.fontSizePt, char.fontSizePt).takeIf { it > 0f } ?: 10f
            val gap = char.rect.minX - prev.rect.maxX
            if (gap > 0.2f * size && !builder.endsWith(" ")) builder.append(' ')
        }
        builder.append(char.text)
        previous = char
    }
    return builder.toString().trim()
}

/** Selects the appropriate bundled Liberation font for a font analysis. */
private fun selectBundledFont(analysis: FontAnalysis): String {
    val baseName = if (isSubsetFontName(analysis.originalFontName))
        analysis.originalFontName.substringAfterLast("+")
    else
        analysis.originalFontName
    
    val family = when {
        baseName.contains("Times", true) || baseName.contains("Serif", true) -> "LiberationSerif"
        baseName.contains("Courier", true) || baseName.contains("Mono", true) -> "LiberationMono"
        else -> "LiberationSans"
    }
    // Asset stems are LiberationSans-Regular/-Bold/-Italic/-BoldItalic; a bare
    // "LiberationSans" never matched a bundled file.
    return when {
        analysis.bold && analysis.italic -> "$family-BoldItalic"
        analysis.bold -> "$family-Bold"
        analysis.italic -> "$family-Italic"
        else -> "$family-Regular"
    }
}

/** Analyzes whether the original font can handle the new text, or if fallback is needed. */
private fun analyzeFontForEdit(
    originalFontName: String,
    newText: String,
    originalText: String
): FontFallbackDecision {
    val isSubset = isSubsetFontName(originalFontName)
    val baseName = if (isSubset) originalFontName.substringAfterLast("+") else originalFontName
    val isStandard14 = FontFallbackConstants.STANDARD_14_FONTS.contains(baseName)
    
    // Characters being added (in new but not in original)
    val newChars = newText.toSet() - originalText.toSet()
    
    // Deletion or reordering only — no new characters to encode
    if (newChars.isEmpty()) return FontFallbackDecision.KEEP_ORIGINAL
    
    // Non-subset standard 14 font with all new chars in WinAnsi
    if (!isSubset && isStandard14 && newChars.all { it in FontFallbackConstants.WIN_ANSI_CHARS }) {
        return FontFallbackDecision.KEEP_ORIGINAL
    }
    
    // Check for unsupported characters (outside Liberation Latin coverage)
    val unsupportedChar = newChars.firstOrNull { it !in FontFallbackConstants.LIBERATION_LATIN_CHARS }
    if (unsupportedChar != null) {
        return FontFallbackDecision.UNSUPPORTED_CHARS
    }
    
    // Subset font or standard font with chars outside WinAnsi but within Liberation coverage
    return FontFallbackDecision.USE_BUNDLED_FALLBACK
}

data class ViewerUiState(
    val documentName: String? = null,
    val pageCount: Int = 0,
    val isLoading: Boolean = false,
    val error: DocumentError? = null,
    val zoom: Float = 1f,
    val visiblePageIndex: Int = 0,
    val scrollOffsetPx: Int = 0,
    val panX: Float = 0f,
    val pageSizes: List<PdfPageGeometry> = emptyList(),
    // True while a pinch gesture owns the scroll position. The scroll-restore
    // effect must not fight the focal anchoring while it is set (D-06, D-07).
    val gestureActive: Boolean = false,
    // True until the first recent-files emission arrives from DataStore.
    // HomeScreen uses this to suppress the empty-state flicker on cold start.
    val isLoadingRecents: Boolean = true,
    // A one-shot command to move the list. ONLY this may move the list - the
    // visiblePageIndex / scrollOffsetPx pair is a MIRROR of the list's own
    // position and must never drive it (during a fling the mirror lags a frame
    // behind, and restoring from it cancels the fling).
    val scrollCommand: ScrollCommand? = null,
)

const val MIN_ZOOM = 1f
const val MAX_ZOOM = 5f

private const val TAG = "PdfViewerViewModel"
private const val TAG_EDIT = "RedraftEdit"

/**
 * Per-page text extraction state (D-28 … D-31).
 *
 * The state is *settled* when it is [Ready], [Empty] or [Failed].  Only a
 * settled state may produce a message; [Loading] never does (D-30).
 */
sealed interface PageTextState {
    /** The page's text was extracted: [layout] holds the plain-data lines. */
    data class Ready(val layout: com.redraft.pdfcore.selection.TextPageLayout) : PageTextState

    /** Extraction is in flight — a tap waits for it, up to one second (D-29). */
    data object Loading : PageTextState

    /** The page has no extractable text at all (D-30). */
    data object Empty : PageTextState

    /** The page's text could not be read (D-31). */
    data object Failed : PageTextState
}

/**
 * A one-shot, user-facing viewer message.  The ViewModel emits the *kind* of
 * message; the UI resolves it to a string resource, so the copy stays in
 * `strings.xml` (D-30, D-31).
 */
enum class ViewerMessage {
    /** "No selectable text on this page" (D-30). */
    NoSelectableText,

    /** "Couldn't read this page's text" (D-31). */
    CouldNotReadText,

    /** "Edit failed: <reason>" — shown when save/reopen fails after edit. */
    EditFailed,

    /** "Select a line first" — Edit text tapped with nothing selected. */
    NothingSelected,

    /** "This text cannot be edited" — the selected line is vertical or RTL. */
    LineNotEditable,

    /** "This page's text is still loading" — extraction is missing or failed. */
    PageTextNotReady,

    /** "Editing is not available on this device" — the native module is missing. */
    EditingUnavailable,

    /** "Cannot edit this line safely" — no single text object matches the line. */
    EditTargetAmbiguous,
}

/** How long a tap waits for an in-flight extraction before it is dropped (D-29). */
const val EXTRACTION_WAIT_MS = 1_000L

/**
 * How long Apply waits for the edited page to be re-rendered from the saved
 * copy before it gives up and shows an error inside the panel. The panel must
 * not close on a page that still shows the old pixels.
 */
const val RERENDER_TIMEOUT_MS = 3_000L

/**
 * Renders the edited page after the saved copy is reopened. Injectable so the
 * "editor stays open until the new pixels exist" contract is unit-testable
 * without PDFium. Returns true only when a real bitmap came back.
 */
fun interface EditedPageRenderer {
    suspend fun render(pageIndex: Int, widthPx: Int, heightPx: Int): Boolean
}

/** Roughly how many extracted page layouts are kept (D-28). */
const val MAX_CACHED_TEXT_LAYOUTS = 4

fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

class PdfViewerViewModel(
    private val recentsRepository: RecentsStore,
    private val openDocument: suspend (String) -> Result<DocumentHandle>,
    private val resolveDisplayName: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Where the (pure, CPU-bound) hit test runs. */
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    /**
     * Extracts one page's text.  Injectable so the extraction lifecycle
     * (timeout, cancellation, rate limiting) is unit-testable without the
     * native PDFium library; the default is the real pdfcore extraction.
     */
    private val extractPageText: suspend (DocumentHandle, Int) -> com.redraft.pdfcore.selection.PageTextLayout =
        { handle, index ->
            val geometry = handle.pageSize(index)
            com.redraft.pdfcore.selection.pageTextLayout(handle.document, index, geometry)
        },
    /**
     * Factory for the PDF editing engine. Null in tests to avoid native library loading.
     */
    private val editingEngineFactory: (() -> com.redraft.pdfedit.PdfEditingEngine)? = null,
    /**
     * Content resolver provider for opening file descriptors. In tests, can provide a mock.
     */
    private val contentResolverProvider: (() -> android.content.ContentResolver)? = null,
    /** Application context used for the edit cache and reopening the saved copy. Null in tests. */
    private val appContext: Context? = null,
    /**
     * How Apply re-renders the edited page. Null uses the live document handle
     * and [pageBitmapCache]; tests inject a fake so the wait is deterministic.
     */
    private val editedPageRenderer: EditedPageRenderer? = null,
    /**
     * Creates the temp file the edited copy is saved to. Null uses the app
     * cache directory; unit tests inject one because they have no Context.
     */
    private val editTempFileFactory: (() -> java.io.File)? = null,
    // Cache ownership (A-1) — single instance per ViewModel
    val pageBitmapCache: PageBitmapCache = PageBitmapCache(),
    val thumbnailCache: ThumbnailCache = ThumbnailCache(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private val _handle = MutableStateFlow<DocumentHandle?>(null)
    val handle: StateFlow<DocumentHandle?> = _handle.asStateFlow()

    private val _documentOpened = Channel<Unit>(capacity = Channel.BUFFERED)
    val documentOpened: Flow<Unit> = _documentOpened.receiveAsFlow()

    val recents: StateFlow<List<RecentFile>> = recentsRepository.recents
        .onEach { _uiState.update { it.copy(isLoadingRecents = false) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Cache size (A-3) — null = not yet measured
    private val _cacheSizeBytes = MutableStateFlow<Long?>(null)
    val cacheSizeBytes: StateFlow<Long?> = _cacheSizeBytes

    // Selection / hit-testing state (Phase 2, D-06, D-15):
    // - _selectionState = null → no line selected
    // - _selectionState = SelectionOverlayState(selectedLine=X) → line X selected
    private val _selectionState = MutableStateFlow<com.redraft.ui.viewer.SelectionOverlayState?>(null)
    val selectionState: StateFlow<com.redraft.ui.viewer.SelectionOverlayState?> = _selectionState.asStateFlow()

    // Edit mode (D-18): off by default on every open, toggled by the pencil
    // action in the viewer top bar.  Leaving Edit mode clears the selection
    // (D-20), and closing the document leaves Edit mode (D-21).
    private val _editMode = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = _editMode.asStateFlow()

    // One-shot messages (D-30, D-31).  The UI resolves them to string
    // resources and shows them in the snackbar host.
    private val _messages = MutableSharedFlow<ViewerMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<ViewerMessage> = _messages.asSharedFlow()

    // Extraction lifecycle (D-28 … D-31):
    // - textLayoutCache: LRU of extracted page layouts (~4 entries).
    // - pageTextStates: the last known state per page.
    // - extractionJobs: the in-flight extraction per page — a second tap
    //   awaits the existing job instead of starting a second extraction.
    private val textLayoutCache = LinkedHashMap<Int, com.redraft.pdfcore.selection.TextPageLayout>(16, 0.75f, true)
    private val pageTextStates = mutableMapOf<Int, PageTextState>()
    private val extractionJobs = mutableMapOf<Int, Job>()

    // Block identity per page (D-15 / D-17): firstCharIndex -> blockId, produced by
    // groupBlocks once per extraction. The reducer's own lines carry a null blockId,
    // so this map is what gives the selection overlay its paragraph metadata.
    private val blockIdsByPage = mutableMapOf<Int, Map<Int, String>>()
    private val _blockIdsByPage = MutableStateFlow<Map<Int, Map<Int, String>>>(emptyMap())
    val blockIdsByPageFlow: StateFlow<Map<Int, Map<Int, String>>> = _blockIdsByPage

    // The pages the viewport last reported. Turning Edit mode on starts extraction
    // for these; Read mode extracts nothing (D-28's lazy-on-first-tap contract).
    private var visiblePages: Set<Int> = emptySet()

    // The page that owns the current selection: its layout survives the
    // viewport eviction (D-28) and the overlay keeps working (D-24).
    private var selectionPageIndex: Int? = null

    // Rate limiting (D-31): one message per page per visit.  The marker is
    // cleared when the visible page changes.
    private var lastMessagePageIndex: Int? = null
    private var lastVisiblePageIndex: Int? = null

    // Editor panel state (D-301): draft text, caret, showing, target line ref
    private val _editorState = MutableStateFlow<EditorPanelState?>(null)
    val editorState: StateFlow<EditorPanelState?> = _editorState.asStateFlow()

    // Command stack (D-302): per-commit granularity, survives rotation
    private val _past = mutableListOf<EditCommand>()
    private val _future = mutableListOf<EditCommand>()
    private val _pastFlow = MutableStateFlow<List<EditCommand>>(emptyList())
    private val _futureFlow = MutableStateFlow<List<EditCommand>>(emptyList())
    val pastCommands: StateFlow<List<EditCommand>> = _pastFlow
    val futureCommands: StateFlow<List<EditCommand>> = _futureFlow

    // Editing engine + handle (D-303)
    private var editingEngine: com.redraft.pdfedit.PdfEditingEngine? = null
    private var editingHandle: EditingHandle? = null
    private var editingHandleJob: Job? = null
    /** Why the editing session could not open; shown in the panel for diagnosis. */
    private var editingUnavailableReason: String? = null
    // The "editing unavailable" snackbar is shown once per open document, not on every tap.
    private var editingUnavailableNotified = false

    // Zoom/pan saved when the editor opens, restored when it closes (Task 4a).
    private var savedZoom: Float = 1f
    private var savedPanX: Float = 0f

    // Render scope for cancelling in-flight renders on document swap
    private var renderJob = SupervisorJob()
    private val renderScope = CoroutineScope(renderJob + ioDispatcher)

    // Font cache for bundled fonts (loaded once per editing document)
    private val fontCache = mutableMapOf<String, FontHandle>()

    // Temp file for edit→save→reopen workflow
    private var currentEditTempFile: java.io.File? = null

    /** Number of cached page layouts — exposed for the lifecycle unit tests. */
    internal val cachedLayoutCount: Int get() = textLayoutCache.size

    /** Number of in-flight extraction jobs — exposed for the lifecycle unit tests. */
    internal val activeExtractionCount: Int get() = extractionJobs.size

    /** The last known extraction state of [pageIndex] — exposed for tests. */
    internal fun pageTextStateOf(pageIndex: Int): PageTextState? = pageTextStates[pageIndex]

    /** Get the cached text layout for a page — exposed for reselection observation. */
    internal fun getTextLayout(pageIndex: Int): com.redraft.pdfcore.selection.TextPageLayout? = textLayoutCache[pageIndex]

    /**
     * Handles a screen-space tap on [pageIndex]. If a text line is under the tap,
     * sets [selectionState] to that line. Otherwise clears the selection.
     *
     * Called from [PdfViewerScreen] on every tap that is NOT a pinch. The tap
     * handler is inside [PdfPageItem]'s pointer input block — the page is
     * unambiguously identified by [pageIndex].
     *
     * In READ mode the tap is ignored: it toggles the viewer chrome instead
     * (D-19).  In EDIT mode the page's text is extracted lazily on first tap
     * (D-28); a tap that arrives while the extraction is still running waits
     * for it for at most [EXTRACTION_WAIT_MS] and is dropped silently when
     * that timeout expires (D-29).
     *
     * @param tapScreen screen-space tap point (y-down pixels).
     * @param pageIndex the page that was tapped.
     * @param viewport current zoom and pan state.
     * @param transform screen↔PDF coordinate transform (D-02, D-14).
     * @param tolerance how far from a line a tap still counts as on it, in SCREEN
     *   pixels — the caller converts dp (`8.dp.toPx()`), because a ViewModel has no
     *   display density and this module must stay density-free.
     */
    fun onTap(
        tapScreen: PdfPoint,
        pageIndex: Int,
        viewport: ViewportState,
        transform: PdfViewportTransform,
        tolerance: TapTolerance,
    ) {
        if (!_editMode.value) {
            return
        }

        viewModelScope.launch {
            val handle = currentHandle ?: return@launch

            when (val state = awaitPageText(pageIndex, handle)) {
                is PageTextState.Ready -> {
                    // Leaving Edit mode while the tap waited for the extraction
                    // must not write a selection: setEditMode already wiped the
                    // caches, and Read-mode taps select nothing (D-20, WR-02).
                    if (!_editMode.value) return@launch

                    // A miss on a page that HAS text merely clears the
                    // selection — no message (D-30).
                    val result = withContext(computeDispatcher) {
                        hitTest(tapScreen, state.layout, viewport, transform, tolerance)
                    }
                    _selectionState.value = result?.let {
                        selectionPageIndex = pageIndex
                        com.redraft.ui.viewer.SelectionOverlayState(
                            selectedLine = it.line,
                            // Block identity is metadata for Phase 3 (D-15): the
                            // reducer never groups, so the line's own blockId is
                            // null and the id comes from the post-extraction
                            // grouping keyed by the line's first character.
                            blockId = it.line.blockId
                                ?: blockIdsByPage[pageIndex]?.get(it.line.firstCharIndex),
                            editable = it.line.fragment.editable,
                            pageIndex = pageIndex,
                        )
                    }
                    if (result == null) selectionPageIndex = null
                }

                PageTextState.Empty -> emitMessage(ViewerMessage.NoSelectableText, pageIndex)

                PageTextState.Failed -> emitMessage(ViewerMessage.CouldNotReadText, pageIndex)

                // Still loading after the timeout: the tap is dropped — no
                // selection change and no message (D-29).
                PageTextState.Loading -> Unit
            }
        }
    }

    /**
     * The page's text, waiting at most [EXTRACTION_WAIT_MS] for an in-flight
     * extraction (D-28, D-29).
     *
     * A second tap on the same page awaits the EXISTING job — it never starts
     * a second extraction.
     */
    private suspend fun awaitPageText(
        pageIndex: Int,
        handle: DocumentHandle,
    ): PageTextState {
        textLayoutCache[pageIndex]?.let { return PageTextState.Ready(it) }

        val job = extractionJobs[pageIndex] ?: startExtraction(pageIndex, handle)

        val finished = withTimeoutOrNull(EXTRACTION_WAIT_MS) {
            job.join()
            true
        } ?: false

        // Timed out: the tap is dropped silently (D-29).
        if (!finished) return PageTextState.Loading

        return pageTextStates[pageIndex] ?: PageTextState.Loading
    }

    /**
     * Starts the extraction for [pageIndex] and registers the in-flight job,
     * so a second tap awaits it instead of extracting twice (D-28).
     */
    private fun startExtraction(pageIndex: Int, handle: DocumentHandle): Job {
        pageTextStates[pageIndex] = PageTextState.Loading
        val job = viewModelScope.launch {
            try {
                val result = try {
                    // Only the EXTRACTION leaves the Main thread. Every map access
                    // below stays on Main: the LRU is access-ordered, so a read
                    // structurally mutates it and cannot race the visibility
                    // sweep that shares it (02-REVIEW CR-04).
                    withContext(ioDispatcher) {
                        extractPageText(handle, pageIndex)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    com.redraft.pdfcore.selection.PageTextLayout.Failed
                }
                when (result) {
                    is com.redraft.pdfcore.selection.PageTextLayout.Ready -> {
                        val layout = result.layout
                        val blockIds = withContext(computeDispatcher) {
                            groupBlocks(layout.reducedLines, pageIndex)
                                .flatMap { block ->
                                    block.lines.map { line -> line.firstCharIndex to block.blockId }
                                }
                                .toMap()
                        }
                        textLayoutCache[pageIndex] = layout
                        blockIdsByPage[pageIndex] = blockIds
                        _blockIdsByPage.value = blockIdsByPage.toMap()
                        pageTextStates[pageIndex] = PageTextState.Ready(layout)
                        enforceLayoutCacheLimit()
                    }

                    com.redraft.pdfcore.selection.PageTextLayout.Empty ->
                        pageTextStates[pageIndex] = PageTextState.Empty

                    com.redraft.pdfcore.selection.PageTextLayout.Failed ->
                        pageTextStates[pageIndex] = PageTextState.Failed
                }
            } finally {
                // Register and unregister on the SAME side (Main). Removing from
                // the IO side could run before the registration below and leave a
                // finished Job in the map for good, so every later tap re-awaited
                // a dead Job and the page stayed Loading (02-REVIEW WR-07).
                val self = coroutineContext[Job]
                if (self != null && extractionJobs[pageIndex] === self) {
                    extractionJobs.remove(pageIndex)
                }
                // A Job that ends without a settled state must not strand the
                // page in Loading — a future tap would await nothing and drop.
                if (pageTextStates[pageIndex] == PageTextState.Loading) {
                    pageTextStates.remove(pageIndex)
                }
            }
        }
        extractionJobs[pageIndex] = job
        return job
    }

    /**
     * Keeps the layout cache at roughly [MAX_CACHED_TEXT_LAYOUTS] entries,
     * plus the page that owns the current selection (D-28).
     */
    private fun enforceLayoutCacheLimit() {
        val limit = MAX_CACHED_TEXT_LAYOUTS + if (selectionPageIndex != null) 1 else 0
        val protectedPage = selectionPageIndex
        while (textLayoutCache.size > limit) {
            val oldest = textLayoutCache.keys.firstOrNull { it != protectedPage } ?: break
            textLayoutCache.remove(oldest)
            blockIdsByPage.remove(oldest)
            _blockIdsByPage.value = blockIdsByPage.toMap()
        }
    }

    /**
     * Emits a message, at most once per page per visit (D-31).
     *
     * D-32 (the optional "This page may be a scan" hint) is deliberately
     * NOT implemented: deciding that a page looks like a scan requires
     * enumerating the page's objects to find an embedded image, and the
     * pinned pdfiumandroid 3.0.4-alpha01 binding exposes no page-object API
     * at all (no `FPDFPage_CountObjects` / `FPDFPage_GetObject` — see
     * 02-RESEARCH §1.2 and §6.4).  The approximate alternative — rendering a
     * thumbnail and measuring non-background coverage — is non-deterministic
     * and false-positives on coloured backgrounds, so the hint stays omitted
     * and the plain no-text message is shown instead.
     */
    private fun emitMessage(message: ViewerMessage, pageIndex: Int) {
        if (lastMessagePageIndex == pageIndex) return
        lastMessagePageIndex = pageIndex
        _messages.tryEmit(message)
    }

    /**
     * The pages currently on screen (D-28).
     *
     * A page that leaves the viewport has its extraction job cancelled and its
     * cached layout evicted — except the page that owns the current selection,
     * whose layout must survive so the overlay keeps working (D-24).
     *
     * The visible page changing also clears the message rate-limit marker, so
     * the next visit to a page may show its message again (D-31).
     */
    fun onVisiblePagesChanged(visible: Set<Int>) {
        visiblePages = visible
        val visiblePage = visible.minOrNull() ?: uiState.value.visiblePageIndex
        if (visiblePage != lastVisiblePageIndex) {
            lastVisiblePageIndex = visiblePage
            lastMessagePageIndex = null
        }

        // Cancel the extraction of every page that left the screen.
        val gone = extractionJobs.keys - visible
        for (page in gone) {
            extractionJobs.remove(page)?.cancel()
            pageTextStates.remove(page)
        }

        // Evict the layouts of the pages that left the screen; the selected
        // page keeps its layout.
        val keep = selectionPageIndex
        val evict = textLayoutCache.keys - visible - setOfNotNull(keep)
        for (page in evict) {
            textLayoutCache.remove(page)
            blockIdsByPage.remove(page)
            pageTextStates.remove(page)
        }
        _blockIdsByPage.value = blockIdsByPage.toMap()
    }

    /** Turns Edit mode on or off (D-18).  Leaving it clears the selection (D-20). */
    fun setEditMode(enabled: Boolean) {
        if (_editMode.value == enabled) return
        _editMode.value = enabled
        if (!enabled) {
            clearSelection()
            // Cancel all in-flight extraction jobs when leaving edit mode.
            extractionJobs.values.forEach { it.cancel() }
            extractionJobs.clear()
            pageTextStates.clear()
            textLayoutCache.clear()
            blockIdsByPage.clear()
            _blockIdsByPage.value = emptyMap()
        } else {
            // Turning Edit mode on warms the pages already on screen; no
            // extraction starts in Read mode at all (CR-03).
            currentHandle?.let { handle ->
                val alreadyKnown = textLayoutCache.keys + extractionJobs.keys + pageTextStates.keys
                for (page in visiblePages - alreadyKnown) {
                    startExtraction(page, handle)
                }
            }
        }
    }

    /** Toggles Edit mode (D-18). */
    fun toggleEditMode() {
        setEditMode(!_editMode.value)
    }

    /** Clears the current text selection (e.g. on single tap outside a line). */
    fun clearSelection() {
        _selectionState.value = null
        selectionPageIndex = null
    }

    /** Dismisses the inline editor without committing changes. */
    fun dismissEditor() {
        endEditSession()
        _editorState.value = null
    }

    /** Saves the current zoom/pan so they can be restored when the editor closes (Task 4a). */
    internal fun startEditSession() {
        savedZoom = _uiState.value.zoom
        savedPanX = _uiState.value.panX
    }

    /** Restores the zoom/pan that was active when the editor opened (Task 4a). */
    internal fun endEditSession() {
        _uiState.update { it.copy(zoom = savedZoom, panX = savedPanX) }
    }

    /**
     * Auto-zoom: fit [lineRect] horizontally to 90% of the viewport and center panX on it
     * (Task 4a). Called when the editor opens so the selected line is visible above the keyboard.
     */
    internal fun zoomToLine(lineRect: PdfRect, viewportWidthPx: Int) {
        val pageIndex = _selectionState.value?.pageIndex ?: return
        val pageSize = _uiState.value.pageSizes.firstOrNull { it.pageIndex == pageIndex } ?: return
        val lineWidth = lineRect.width
        if (lineWidth <= 0f) return

        // At zoom z, screen width of line = lineWidth * z * baseWidthPx / widthPt
        // We want screen width = 0.9 * viewportWidthPx
        // z = 0.9 * viewportWidthPx * widthPt / (lineWidth * baseWidthPx)
        val baseSize = pageSize.slotBaseSizePx(viewportWidthPx)
        val targetZoom = 0.9f * viewportWidthPx * pageSize.widthPt / (lineWidth * baseSize.widthPx.toFloat())
        val clampedZoom = clampZoom(targetZoom)

        // Center panX on the line's center point
        val scaleX = clampedZoom * baseSize.widthPx.toFloat() / pageSize.widthPt
        val lineCenter = (lineRect.minX + lineRect.maxX) / 2f
        val targetPanX = viewportWidthPx.toFloat() / 2f - lineCenter * scaleX

        _uiState.update { it.copy(zoom = clampedZoom, panX = targetPanX) }
    }

    /** Updates the selection state (used for reselection after edit). */
    fun updateSelection(newState: com.redraft.ui.viewer.SelectionOverlayState?) {
        _selectionState.value = newState
    }

    /**
     * Test hook: attaches an already-open editing session. The production path
     * creates the engine and opens the document through the ContentResolver,
     * which a JVM unit test cannot construct.
     */
    internal fun attachEditingSessionForTest(
        engine: com.redraft.pdfedit.PdfEditingEngine,
        handle: EditingHandle,
    ) {
        editingEngine = engine
        editingHandle = handle
    }

    /** Updates the editor state with new draft text, computing warnings and apply eligibility. */
    fun updateEditorState(newState: EditorPanelState) {
        val editor = _editorState.value ?: return
        val selection = _selectionState.value ?: return
        val line = selection.selectedLine ?: return
        val fragment = line.fragment
        
        val fontName = fragment.style.fontName ?: ""
        val fontAnalysis = fontAnalysisOf(fontName)
        
        val fallbackDecision = analyzeFontForEdit(fontName, newState.draftText, editor.originalText)
        
        val fontChangeNotice = when (fallbackDecision) {
            FontFallbackDecision.USE_BUNDLED_FALLBACK ->
                bundledFontDisplayName(selectBundledFont(fontAnalysis))
            else -> null
        }
        val showUnsupportedChar = when (fallbackDecision) {
            FontFallbackDecision.UNSUPPORTED_CHARS -> {
                val newChars = newState.draftText.toSet() - editor.originalText.toSet()
                newChars.firstOrNull { it !in FontFallbackConstants.LIBERATION_LATIN_CHARS }
            }
            else -> null
        }
        val lengthWarning = newState.draftText.length > editor.originalText.length * 1.2
        // The 1.2x length check is a NOTICE only: it must not disable Apply.
        // An empty draft cannot match a text object; allowing Apply would only
        // produce "Cannot edit this line safely".
        val formattingChanged =
            newState.effectiveFontSizePt != editor.fontSizePt ||
                newState.selectedBold != editor.selectedBold ||
                newState.selectedItalic != editor.selectedItalic ||
                newState.effectiveColor != editor.textColor
        val canApply = fallbackDecision != FontFallbackDecision.UNSUPPORTED_CHARS &&
            newState.draftText.isNotBlank() &&
            (newState.draftText != editor.originalText || formattingChanged)

        _editorState.value = newState.copy(
            fontChangeNotice = fontChangeNotice,
            lengthWarning = lengthWarning,
            canApply = canApply,
            showUnsupportedChar = showUnsupportedChar,
        )
    }

    /** Opens the inline editor for the currently selected line. */
    fun onEditText() {
        val selection = _selectionState.value
        if (selection == null) {
            Log.d(TAG_EDIT, "onEditText: no line selected")
            _messages.tryEmit(ViewerMessage.NothingSelected)
            return
        }
        if (!selection.editable) {
            Log.d(TAG_EDIT, "onEditText: selected line is not editable")
            _messages.tryEmit(ViewerMessage.LineNotEditable)
            return
        }
        if (selection.selectedLine == null) {
            Log.d(TAG_EDIT, "onEditText: selection has no line")
            _messages.tryEmit(ViewerMessage.NothingSelected)
            return
        }
        if (textLayoutCache[selection.pageIndex] == null) {
            val handle = currentHandle
            if (handle == null) {
                Log.d(TAG_EDIT, "onEditText: page text not ready (no document)")
                _messages.tryEmit(ViewerMessage.PageTextNotReady)
                return
            }
            // The layout was evicted or never extracted: re-extract, then continue
            // instead of silently giving up (the old behaviour).
            Log.d(TAG_EDIT, "onEditText: layout missing for page ${selection.pageIndex}, re-extracting")
            viewModelScope.launch {
                if (awaitPageText(selection.pageIndex, handle) is PageTextState.Ready) {
                    openEditorForSelection()
                } else {
                    Log.d(TAG_EDIT, "onEditText: extraction did not produce a layout")
                    _messages.tryEmit(ViewerMessage.PageTextNotReady)
                }
            }
            return
        }
        viewModelScope.launch { openEditorForSelection() }
    }

    /** Builds the editor state from the current selection (its layout must be cached). */
    private suspend fun openEditorForSelection() {
        // Save zoom/pan so they can be restored when the editor closes (Task 4a).
        startEditSession()
        // The editing session opens in parallel with the document; wait for it
        // instead of reporting "unavailable" just because it is still starting.
        runCatching { editingHandleJob?.join() }
        val selection = _selectionState.value ?: return
        val line = selection.selectedLine ?: return
        val fragment = line.fragment

        // Full font fallback analysis (D-304)
        // The text objects are the source of truth. PDFium may store no space
        // character between words, and the character boxes then have to guess.
        // On some devices PDFium's FPDFTextObj_GetText can truncate glyphs, so
        // compare the native result with the character-box inference and use
        // whichever is more complete (more non-whitespace characters).
        val boxText = editableLineText(line)
        val handle = editingHandle
        val engine = editingEngine
        var objects = 0
        val nativeText = if (handle != null && engine != null) {
            objects = runCatching {
                engine.lineObjectCount(
                    handle, selection.pageIndex,
                    line.rect.minX, line.rect.minY, line.rect.maxX, line.rect.maxY,
                )
            }.getOrDefault(0)
            runCatching {
                engine.lineText(
                    handle, selection.pageIndex,
                    line.rect.minX, line.rect.minY, line.rect.maxX, line.rect.maxY,
                )
            }.getOrDefault("")
        } else {
            ""
        }
        // Pick the text with more non-whitespace characters: native text objects
        // are authoritative, but character-box inference fills in when PDFium
        // truncates glyphs on some devices.
        val nativeCompact = nativeText.replace(Regex("\\s"), "")
        val boxCompact = boxText.replace(Regex("\\s"), "")
        val originalText = if (nativeCompact.length >= boxCompact.length) nativeText else boxText
        Log.d(
            TAG_EDIT,
            "editor text='$originalText' native='$nativeText' boxes='$boxText' objects=$objects",
        )
        // The size PDFium reports for the real text object. The character boxes
        // carry a different number (a 12 pt heading showed as 25 pt), and the
        // replacement object reuses the native size, so the editor must show it.
        val nativeFontSizePt = if (handle != null && engine != null) {
            runCatching {
                engine.lineFontSize(
                    handle, selection.pageIndex,
                    line.rect.minX, line.rect.minY, line.rect.maxX, line.rect.maxY,
                )
            }.getOrDefault(0f)
        } else {
            0f
        }
        val editorFontSizePt =
            if (nativeFontSizePt > 0f) nativeFontSizePt else fragment.style.fontSizePt
        Log.d(
            TAG_EDIT,
            "editor size native=${nativeFontSizePt}pt boxes=${fragment.style.fontSizePt}pt used=${editorFontSizePt}pt",
        )
        val fontName = fragment.style.fontName ?: ""
        val fontAnalysis = fontAnalysisOf(fontName)
        
        // Initial analysis with original text (no new chars yet)
        val fallbackDecision = analyzeFontForEdit(fontName, originalText, originalText)
        val fontChangeNotice = when (fallbackDecision) {
            FontFallbackDecision.USE_BUNDLED_FALLBACK ->
                bundledFontDisplayName(selectBundledFont(fontAnalysis))
            else -> null
        }
        val showUnsupportedChar = when (fallbackDecision) {
            FontFallbackDecision.UNSUPPORTED_CHARS -> {
                // Find first unsupported char in original text (shouldn't happen normally)
                val unsupported = originalText.toSet() - FontFallbackConstants.LIBERATION_LATIN_CHARS
                unsupported.firstOrNull()
            }
            else -> null
        }
        val canApply = fallbackDecision != FontFallbackDecision.UNSUPPORTED_CHARS

        _editorState.value = EditorPanelState(
            originalText = originalText,
            draftText = originalText,
            fontSizePt = editorFontSizePt,
            textColor = fragment.style.fillColor ?: 0xFF000000.toInt(),
            lineObjectCount = objects,
            fontChangeNotice = fontChangeNotice,
            lengthWarning = false,
            canApply = canApply,
            showUnsupportedChar = showUnsupportedChar,
        )
    }

    /** Commits the edit: creates EditCommand, executes via JNI, pushes to stack. */
    fun onApplyEdit(newText: String) {
        val editor = _editorState.value ?: return
        if (editor.applyInProgress) return
        val selection = _selectionState.value
        if (selection == null) {
            failEdit(com.redraft.ui.viewer.EditorEditFailure.NO_SELECTION, "no line selected")
            return
        }
        val handle = editingHandle
        val engine = editingEngine
        if (handle == null || engine == null) {
            notifyEditingUnavailable("apply")
            failEdit(
                com.redraft.ui.viewer.EditorEditFailure.NO_ENGINE,
                editingUnavailableReason,
                editorText = newText,
            )
            return
        }
        val line = selection.selectedLine
        if (line == null) {
            failEdit(com.redraft.ui.viewer.EditorEditFailure.NO_SELECTION)
            return
        }
        val fragment = line.fragment
        val rect = line.rect

        // Determine the font for this edit.
        val fontName = fragment.style.fontName ?: ""
        val fontAnalysis = fontAnalysisOf(fontName)
        val fallbackDecision = analyzeFontForEdit(fontName, newText, editor.originalText)
        if (fallbackDecision == FontFallbackDecision.UNSUPPORTED_CHARS) {
            failEdit(
                com.redraft.ui.viewer.EditorEditFailure.UNSUPPORTED_CHARACTER,
                "new text contains characters outside Liberation Latin coverage",
                editorText = newText,
                fontName = fontName,
            )
            return
        }
        val newFontName = when (fallbackDecision) {
            FontFallbackDecision.USE_BUNDLED_FALLBACK -> selectBundledFont(fontAnalysis)
            else -> fontName
        }

        // Formatting changes (size/bold/italic/colour) force a replacement object.
        val sizeChanged = editor.effectiveFontSizePt != editor.fontSizePt
        val colorChanged = editor.effectiveColor != editor.textColor
        val formattingChanged =
            sizeChanged || editor.selectedBold || editor.selectedItalic || colorChanged

        // Overrides are opt-in: 0 size and alpha-0 colour mean "keep what the
        // original text object has". Nothing here moves the object, so the
        // matrix is always null (the native code reuses the original origin).
        val sizeOverride = if (sizeChanged) editor.effectiveFontSizePt else 0f
        val colorOverride = if (colorChanged) editor.effectiveColor else 0

        // Every outcome is shown inside the panel; the editor never fails silently.
        _editorState.value = editor.copy(applyInProgress = true, applyError = null)

        viewModelScope.launch {
            try {
                val fontHandle = when {
                    fallbackDecision == FontFallbackDecision.USE_BUNDLED_FALLBACK -> {
                        fontCache.getOrPut(newFontName) {
                            engine.loadBundledFont(handle, newFontName, fontAnalysis.bold, fontAnalysis.italic)
                        }
                    }
                    formattingChanged -> {
                        // User changed bold/italic: load the appropriate bundled font.
                        val bundleFontName = when {
                            editor.selectedBold && editor.selectedItalic -> "LiberationSans-BoldItalic"
                            editor.selectedBold -> "LiberationSans-Bold"
                            editor.selectedItalic -> "LiberationSans-Italic"
                            else -> "LiberationSans-Regular"
                        }
                        fontCache.getOrPut(bundleFontName) {
                            engine.loadBundledFont(handle, bundleFontName, editor.selectedBold, editor.selectedItalic)
                        }
                    }
                    else -> FontHandle(0)
                }

                val count = engine.lineObjectCount(
                    handle, selection.pageIndex, rect.minX, rect.minY, rect.maxX, rect.maxY,
                )
                Log.d(
                    TAG_EDIT,
                    "apply: objects=$count line='${editor.originalText}' new='$newText' font='$newFontName'",
                )
                Log.d(
                    TAG_EDIT,
                    "apply: path=${if (formattingChanged) "replacement" else "in-place"} " +
                        "size=${if (sizeOverride > 0f) "${sizeOverride}pt (user)" else "original"} " +
                        "colour=${if (colorOverride != 0) "0x${Integer.toHexString(colorOverride)} (user)" else "original"} " +
                        "matrix=original",
                )
                if (count == 0) {
                    failEdit(
                        com.redraft.ui.viewer.EditorEditFailure.TARGET_NOT_FOUND,
                        "no text objects on the line",
                        path = if (formattingChanged) "replacement" else "in-place",
                        editorText = newText,
                        fontName = newFontName,
                        fontSize = if (sizeOverride > 0f) sizeOverride else null,
                    )
                    return@launch
                }

                val cmd = EditCommand(
                    documentId = currentUri ?: "",
                    pageIndex = selection.pageIndex,
                    lineId = selection.blockId ?: "p${selection.pageIndex}:b${line.chars.firstOrNull()?.index ?: 0}",
                    objectIndex = -1, // the line is edited as a whole, not by one object
                    oldText = editor.originalText,
                    newText = newText,
                    oldFontName = fontName,
                    newFontName = newFontName,
                    fontSizePt = sizeOverride,
                    color = colorOverride,
                    baselineRect = rect,
                    matrix = null,
                    originalFontSizePt = editor.fontSizePt,
                    originalColor = editor.textColor,
                )

                val outcome = engine.editLine(
                    handle = handle,
                    pageIndex = selection.pageIndex,
                    left = rect.minX,
                    top = rect.minY,
                    right = rect.maxX,
                    bottom = rect.maxY,
                    expectedText = editor.originalText,
                    newText = newText,
                    fontHandle = fontHandle,
                    fontSizePt = sizeOverride,
                    color = colorOverride,
                    matrix = cmd.matrix,
                    forceReplace = formattingChanged,
                )
                Log.d(TAG_EDIT, "apply: editLine -> $outcome")
                val path = if (formattingChanged) "replacement" else "in-place"
                when (outcome) {
                    com.redraft.pdfedit.LineEditOutcome.OK -> Unit
                    com.redraft.pdfedit.LineEditOutcome.NO_OBJECTS -> {
                        failEdit(
                            com.redraft.ui.viewer.EditorEditFailure.TARGET_NOT_FOUND,
                            "no text objects on the line",
                            code = outcome.ordinal,
                            path = path,
                            editorText = newText,
                            fontName = newFontName,
                            fontSize = if (sizeOverride > 0f) sizeOverride else null,
                        )
                        return@launch
                    }
                    com.redraft.pdfedit.LineEditOutcome.TEXT_MISMATCH -> {
                        failEdit(
                            com.redraft.ui.viewer.EditorEditFailure.PIECES_DO_NOT_ADD_UP,
                            "the pieces do not add up to the line text",
                            code = outcome.ordinal,
                            path = path,
                            editorText = newText,
                            fontName = newFontName,
                            fontSize = if (sizeOverride > 0f) sizeOverride else null,
                        )
                        return@launch
                    }
                    com.redraft.pdfedit.LineEditOutcome.SET_TEXT_FAILED -> {
                        failEdit(
                            com.redraft.ui.viewer.EditorEditFailure.FONT_FAILED,
                            "neither SetText nor the replacement worked",
                            code = outcome.ordinal,
                            path = path,
                            editorText = newText,
                            fontName = newFontName,
                            fontSize = if (sizeOverride > 0f) sizeOverride else null,
                        )
                        return@launch
                    }
                    com.redraft.pdfedit.LineEditOutcome.GENERATE_FAILED -> {
                        failEdit(
                            com.redraft.ui.viewer.EditorEditFailure.GENERIC,
                            "the page content could not be regenerated",
                            code = outcome.ordinal,
                            path = path,
                            editorText = newText,
                            fontName = newFontName,
                            fontSize = if (sizeOverride > 0f) sizeOverride else null,
                        )
                        return@launch
                    }
                }

                val tempFile = createTempEditFile()
                if (!engine.saveAsCopy(handle, tempFile.absolutePath)) {
                    failEdit(
                        com.redraft.ui.viewer.EditorEditFailure.SAVE_FAILED,
                        "saveAsCopy returned false",
                        path = if (formattingChanged) "replacement" else "in-place",
                        editorText = newText,
                        fontName = newFontName,
                        fontSize = if (sizeOverride > 0f) sizeOverride else null,
                    )
                    return@launch
                }
                Log.d(TAG_EDIT, "apply: saved ${tempFile.length()}B to ${tempFile.name}")

                val pageIndex = selection.pageIndex
                // The size of the bitmap that was on screen, read BEFORE the
                // cache is invalidated, so the verification render matches it.
                val bucket = scaleBucketForZoom(_uiState.value.zoom)
                val dimensions = renderedDimensionsFor(pageIndex, bucket)

                val reopenError = try {
                    reopenRenderingDocument(tempFile)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    e
                }
                Log.d(
                    TAG_EDIT,
                    "apply: reopen -> ${if (reopenError == null) "ok pages=${_uiState.value.pageCount}" else "failed: ${reopenError.message}"}",
                )
                if (reopenError != null) {
                    failEdit(
                        com.redraft.ui.viewer.EditorEditFailure.SAVE_FAILED,
                        reopenError.message ?: reopenError::class.simpleName,
                        path = if (formattingChanged) "replacement" else "in-place",
                        editorText = newText,
                        fontName = newFontName,
                        fontSize = if (sizeOverride > 0f) sizeOverride else null,
                        stackTrace = Log.getStackTraceString(reopenError),
                    )
                    return@launch
                }

                triggerReRender(pageIndex)

                // Keep the Apply spinner until the page shows the new pixels.
                // Closing earlier is what made a correct edit look like it had
                // vanished: the panel disappeared while the old bitmap was
                // still on screen.
                val reRendered = awaitReRenderedPage(pageIndex, bucket, dimensions)
                Log.d(TAG_EDIT, "apply: re-render -> $reRendered")
                if (!reRendered) {
                    failEdit(
                        com.redraft.ui.viewer.EditorEditFailure.RERENDER_FAILED,
                        "page did not re-render within timeout",
                        path = if (formattingChanged) "replacement" else "in-place",
                        editorText = newText,
                        fontName = newFontName,
                        fontSize = if (sizeOverride > 0f) sizeOverride else null,
                    )
                    return@launch
                }

                _past.add(cmd)
                _pastFlow.value = _past.toList()
                _future.clear()
                _futureFlow.value = emptyList()
                endEditSession()
                _editorState.value = null
            } catch (e: Throwable) {
                Log.w(TAG_EDIT, "apply failed", e)
                failEdit(
                    com.redraft.ui.viewer.EditorEditFailure.GENERIC,
                    e.message ?: e::class.simpleName,
                    path = if (formattingChanged) "replacement" else "in-place",
                    editorText = newText,
                    fontName = newFontName,
                    fontSize = if (sizeOverride > 0f) sizeOverride else null,
                    stackTrace = Log.getStackTraceString(e),
                )
            }
        }
    }

    /** Shows an Apply failure inside the panel and keeps the draft. */
    private fun failEdit(
        reason: com.redraft.ui.viewer.EditorEditFailure,
        detail: String? = null,
        code: Int? = null,
        path: String? = null,
        editorText: String? = null,
        pdfText: String? = null,
        fontName: String? = null,
        fontSize: Float? = null,
        stackTrace: String? = null,
    ) {
        val state = _editorState.value ?: return
        val abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: Build.CPU_ABI ?: "unknown"
        _editorState.value = state.copy(
            applyInProgress = false,
            applyError = reason,
            applyErrorDetail = detail,
            applyErrorCode = code,
            applyErrorPath = path,
            applyErrorEditorText = editorText ?: state.draftText,
            applyErrorPdfText = pdfText,
            applyErrorFontName = fontName,
            applyErrorFontSize = fontSize,
            applyErrorStackTrace = stackTrace,
            applyErrorAbi = abi,
        )
        Log.d(TAG_EDIT, "apply failed: $reason ($detail)")
    }

    fun onUndo() { popAndExecute(_past, _future, inverse = true) }
    fun onRedo() { popAndExecute(_future, _past, inverse = false) }

    private fun popAndExecute(source: MutableList<EditCommand>, target: MutableList<EditCommand>, inverse: Boolean) {
        val cmd = source.lastOrNull() ?: return
        val toExecute = if (inverse) cmd.inverse() else cmd
        // Save state for potential rollback
        val sourceSnapshot = source.toList()
        val targetSnapshot = target.toList()
        source.removeAt(source.lastIndex)
        target.add(cmd)
        _pastFlow.value = _past.toList()
        _futureFlow.value = _future.toList()
        viewModelScope.launch {
            try {
                executeCommand(toExecute)
            } catch (e: Throwable) {
                // Rollback stacks on failure
                source.clear()
                source.addAll(sourceSnapshot)
                target.clear()
                target.addAll(targetSnapshot)
                _pastFlow.value = _past.toList()
                _futureFlow.value = _future.toList()
            }
        }
    }

    private suspend fun executeCommand(cmd: EditCommand) {
        // Undo/redo uses the same whole-line path, so it also works for lines
        // that were stored as several text objects.
        val handle = editingHandle ?: throw IllegalStateException("no editing handle")
        val engine = editingEngine ?: throw IllegalStateException("no editing engine")
        val rect = cmd.baselineRect
        val fontHandle = if (cmd.newFontName.startsWith("Liberation")) {
            fontCache.getOrPut(cmd.newFontName) {
                engine.loadBundledFont(
                    handle,
                    cmd.newFontName,
                    cmd.newFontName.contains("Bold"),
                    cmd.newFontName.contains("Italic"),
                )
            }
        } else {
            FontHandle(0)
        }

        val outcome = engine.editLine(
            handle, cmd.pageIndex,
            rect.minX, rect.minY, rect.maxX, rect.maxY,
            cmd.oldText, cmd.newText, fontHandle, cmd.fontSizePt, cmd.color, cmd.matrix,
            forceReplace = cmd.newFontName.startsWith("Liberation"),
        )
        Log.d(TAG_EDIT, "undo/redo: editLine -> $outcome")
        if (outcome != com.redraft.pdfedit.LineEditOutcome.OK) {
            throw IllegalStateException("line edit failed: $outcome")
        }

        val tempFile = createTempEditFile()
        if (!engine.saveAsCopy(handle, tempFile.absolutePath)) {
            throw IllegalStateException("SaveAsCopy returned false")
        }
        reopenRenderingDocument(tempFile)
        triggerReRender(cmd.pageIndex)
    }

    private fun createTempEditFile(): java.io.File {
        val injected = editTempFileFactory
        if (injected != null) {
            val file = injected()
            currentEditTempFile = file
            return file
        }
        val cacheDir = appContext?.cacheDir
            ?: throw IllegalStateException("No application context for the edit cache")
        val file = java.io.File.createTempFile("edit_", ".pdf", cacheDir)
        currentEditTempFile = file
        return file
    }

    private suspend fun reopenRenderingDocument(tempFile: java.io.File) {
        val handle = currentHandle ?: return
        val uri = currentUri ?: return

        // SAFE REOPEN (D-305): Open and verify new document BEFORE closing old
        // Without an application context there is no SAF opener: this is the
        // unit-test configuration, where the rendering swap is exercised by the
        // injected page renderer instead.
        val context = appContext ?: return
        val newHandleResult = withContext(ioDispatcher) {
            SafDocumentOpener.open(context, android.net.Uri.fromFile(tempFile))
        }
        
        val newHandle = newHandleResult.fold(
            onSuccess = { it },
            onFailure = { error ->
                // Reopen failed - keep showing old document, show error via snackbar
                _messages.tryEmit(ViewerMessage.EditFailed)
                throw error // Will be caught by caller
            }
        )
        
        // Verify new document can render
        val canRender = withContext(ioDispatcher) {
            try {
                newHandle.pageCount > 0 && newHandle.pageSize(0) != null
            } catch (_: Throwable) {
                false
            }
        }
        
        if (!canRender) {
            _messages.tryEmit(ViewerMessage.EditFailed)
            cleanupScope.launch(NonCancellable) { newHandle.close() }
            throw IllegalStateException("New document verification failed")
        }
        
        // Cancel in-flight renders for OLD document
        renderJob.cancel()
        renderJob = SupervisorJob()
        
        // NOW close old document and swap
        cleanupScope.launch(NonCancellable) { handle.close() }
        
        currentHandle = newHandle
        _handle.value = newHandle
        currentUri = tempFile.toURI().toString()
        
        // Update page metadata for new document
        val pageSizes = withContext(ioDispatcher) {
            (0 until newHandle.pageCount).map { index ->
                newHandle.pageSize(index)
            }
        }
        _uiState.update { it.copy(pageCount = newHandle.pageCount, pageSizes = pageSizes) }
        
        // Rotation oracle for new document will be recreated by PdfViewerScreen on next render
    }

    private fun triggerReRender(pageIndex: Int) {
        pageBitmapCache.invalidatePage(pageIndex)
        thumbnailCache.invalidate(pageIndex)
        textLayoutCache.remove(pageIndex)
        blockIdsByPage.remove(pageIndex)
        _blockIdsByPage.value = blockIdsByPage.toMap()
        // Kick off render for visible pages (existing mechanism handles this)
        // The cross-fade will be triggered when PageBitmapCache detects invalidated page
        // Reselection will be triggered via LaunchedEffect in PdfViewerScreen observing textLayoutCache
    }

    /**
     * The pixel size the edited page was last drawn at, so the verification
     * render replaces it with a bitmap of the same size. Falls back to 2 px per
     * PDF point when nothing is cached (the page was never on screen).
     */
    private fun renderedDimensionsFor(pageIndex: Int, bucket: Int): Pair<Int, Int> {
        val cached = pageBitmapCache.acquire(PageKey(pageIndex, bucket))
            ?: pageBitmapCache.acquireAny(pageIndex)
        if (cached != null && cached.width > 0 && cached.height > 0) {
            return cached.width to cached.height
        }
        val geometry = _uiState.value.pageSizes.firstOrNull { it.pageIndex == pageIndex }
        val zoom = renderedZoomForBucket(bucket)
        val quarterTurn = geometry?.rotationDegrees == 90 || geometry?.rotationDegrees == 270
        val widthPt = if (quarterTurn) geometry?.heightPt ?: 792f else geometry?.widthPt ?: 612f
        val heightPt = if (quarterTurn) geometry?.widthPt ?: 612f else geometry?.heightPt ?: 792f
        return ((widthPt * zoom * 2f).roundToInt().coerceAtLeast(1)) to
            ((heightPt * zoom * 2f).roundToInt().coerceAtLeast(1))
    }

    /**
     * Renders [pageIndex] from the reopened document and waits at most
     * [RERENDER_TIMEOUT_MS] for the bitmap. The result goes into the shared
     * cache, so the page item paints the new pixels on its next composition.
     */
    private suspend fun awaitReRenderedPage(
        pageIndex: Int,
        bucket: Int,
        dimensions: Pair<Int, Int>,
    ): Boolean = withTimeoutOrNull(RERENDER_TIMEOUT_MS) {
        val injected = editedPageRenderer
        if (injected != null) {
            return@withTimeoutOrNull injected.render(pageIndex, dimensions.first, dimensions.second)
        }
        val handle = _handle.value ?: return@withTimeoutOrNull false
        val bitmap = runCatching {
            withContext(ioDispatcher) {
                handle.renderPage(pageIndex, dimensions.first, dimensions.second)
            }
        }.getOrNull() ?: return@withTimeoutOrNull false
        if (bitmap.width <= 0 || bitmap.height <= 0) return@withTimeoutOrNull false
        pageBitmapCache.put(PageKey(pageIndex, bucket), bitmap)
        true
    } ?: false

    private val openMutex = Mutex()
    private var currentHandle: DocumentHandle? = null
    private var currentUri: String? = null
    private var lastPersistedPage: Int? = null

    fun open(uri: String) {
        openInternal(uri = uri, knownDisplayName = null)
    }

    /**
     * Opens a recent document. [startPage] is the page to land on — Home passes the stored
     * [RecentFile.lastPageIndex] only while "Reopen at the last page" is enabled.
     */
    fun openRecent(file: RecentFile, startPage: Int = 0) {
        openInternal(uri = file.uri, knownDisplayName = file.displayName, startPage = startPage)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun removeRecent(file: RecentFile) {
        viewModelScope.launch { recentsRepository.remove(file.uri) }
    }

    /** Empties the recent-files list only — the documents themselves are untouched. */
    fun clearRecents() {
        viewModelScope.launch { recentsRepository.clear() }
    }

    fun onZoomChanged(zoom: Float) {
        val clamped = clampZoom(zoom)
        _uiState.update { it.copy(zoom = clamped) }
    }

    fun onPanChanged(panX: Float) {
        _uiState.update { it.copy(panX = panX) }
    }

    fun onGestureActiveChanged(active: Boolean) {
        _uiState.update { it.copy(gestureActive = active) }
    }

    fun onScroll(visiblePageIndex: Int, offsetPx: Int) {
        var landedPage = 0
        _uiState.update {
            landedPage = visiblePageIndex.coerceIn(0, (it.pageCount - 1).coerceAtLeast(0))
            it.copy(
                visiblePageIndex = landedPage,
                scrollOffsetPx = offsetPx,
            )
        }
        rememberPage(landedPage)
    }

    // The page is persisted only when it actually changes: the mirror emits on every pixel of a
    // scroll, and a DataStore write per frame would be absurd.
    private fun rememberPage(pageIndex: Int) {
        val uri = currentUri ?: return
        if (pageIndex == lastPersistedPage) return
        lastPersistedPage = pageIndex
        viewModelScope.launch { recentsRepository.updateLastPage(uri, pageIndex) }
    }

    /**
     * Command the list to a page. The token increments on every command so
     * that repeating the same command still fires.
     */
    fun onScrollCommanded(pageIndex: Int, offsetPx: Int = 0) {
        _uiState.update { state ->
            state.copy(
                scrollCommand = ScrollCommand(
                    pageIndex = pageIndex.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)),
                    offsetPx = offsetPx,
                    token = (state.scrollCommand?.token ?: 0L) + 1L,
                ),
            )
        }
    }

    /**
     * Marks a scroll command as applied, so it is never applied twice.
     *
     * The command must be one-shot in EFFECT, not only in the data: the
     * viewer's effect re-runs on every re-composition, so a command that is
     * never cleared would be re-applied whenever the viewer is rebuilt - a
     * rotation, or a return from the page overview - overriding the saveable
     * list state and dropping the reader back to the commanded page.
     *
     * The token is checked so that a command which has already been replaced
     * by a newer one is not cleared by mistake.
     */
    fun onScrollCommandConsumed(token: Long) {
        _uiState.update { state ->
            if (state.scrollCommand?.token == token) state.copy(scrollCommand = null) else state
        }
    }

    // Cache management (A-3)
    fun refreshCacheSize() {
        viewModelScope.launch {
            val total = pageBitmapCache.sizeBytes + thumbnailCache.sizeBytes
            _cacheSizeBytes.value = total
        }
    }

    fun clearCaches() {
        pageBitmapCache.recycleAll()
        thumbnailCache.recycleAll()
        _cacheSizeBytes.value = 0L
    }

    // Document lifecycle (A-2) — single place for cache recycle + handle close
    fun closeDocument() {
        val handle = currentHandle ?: return
        currentHandle = null
        currentUri = null
        lastPersistedPage = null
        _handle.value = null
        // Recycle both caches when document is closed
        pageBitmapCache.recycleAll()
        thumbnailCache.recycleAll()
        // Cancel in-flight renders
        renderJob.cancel()
        renderJob = SupervisorJob()
        // Clear selection state and the whole extraction lifecycle
        // (D-21, D-28): layouts, in-flight jobs and per-page states are
        // dropped, Edit mode is left, and the message rate limit is reset.
        _selectionState.value = null
        selectionPageIndex = null
        textLayoutCache.clear()
        pageTextStates.clear()
        blockIdsByPage.clear()
        _blockIdsByPage.value = emptyMap()
        visiblePages = emptySet()
        extractionJobs.values.forEach { it.cancel() }
        extractionJobs.clear()
        lastMessagePageIndex = null
        lastVisiblePageIndex = null
        _editMode.value = false
        // Close editing document
        editingHandle?.let { handle ->
            viewModelScope.launch { editingEngine?.close(handle) }
        }
        editingHandleJob?.cancel()
        editingHandle = null
        editingEngine = null
        fontCache.clear()
        currentEditTempFile?.delete()
        currentEditTempFile = null
        cleanupScope.launch(NonCancellable) { handle.close() }
    }

    private fun openInternal(uri: String, knownDisplayName: String?, startPage: Int = 0) {
        viewModelScope.launch {
            openMutex.withLock {
                _uiState.update { it.copy(isLoading = true, error = null) }
                closeDocument()
                withContext(ioDispatcher) {
                    openDocument(uri).fold(
                        onSuccess = { opened ->
                            currentHandle = opened
                            _handle.value = opened
                            currentUri = uri
                            val name = knownDisplayName ?: resolveDisplayName(uri)
                            val targetPage = startPage.coerceIn(0, (opened.pageCount - 1).coerceAtLeast(0))
                            lastPersistedPage = targetPage
                            _uiState.update {
                                it.copy(
                                    documentName = name,
                                    pageCount = opened.pageCount,
                                    isLoading = false,
                                    error = null,
                                    zoom = 1f,
                                    visiblePageIndex = 0,
                                    scrollOffsetPx = 0,
                                    panX = 0f,
                                    pageSizes = emptyList(),
                                    // A new document starts at its first page - or at the stored
                                    // page when "Reopen at the last page" is on - by a COMMAND,
                                    // not by the mirror: the mirror never moves the list.
                                    scrollCommand = ScrollCommand(
                                        pageIndex = targetPage,
                                        offsetPx = 0,
                                        token = (it.scrollCommand?.token ?: 0L) + 1L,
                                    ),
                                )
                            }
                            recentsRepository.addOrUpdate(
                                RecentFile(
                                    uri = uri,
                                    displayName = name,
                                    lastOpenedEpochMs = clock(),
                                    lastPageIndex = targetPage,
                                ),
                            )
                            _documentOpened.trySend(Unit)

                            // Editing is optional: a missing native library or an
                            // unreadable descriptor must never stop the viewer opening.
                            editingUnavailableNotified = false
                            contentResolverProvider?.let { provider ->
                                editingHandleJob = viewModelScope.launch {
                                    withContext(ioDispatcher) { openEditingSession(uri, provider) }
                                }
                            }
                        },
                        onFailure = { throwable ->
                            _uiState.update {
                                it.copy(
                                    documentName = null,
                                    pageCount = 0,
                                    isLoading = false,
                                    error = throwable.toDocumentError(),
                                    zoom = 1f,
                                    visiblePageIndex = 0,
                                    scrollOffsetPx = 0,
                                    panX = 0f,
                                    pageSizes = emptyList(),
                                )
                            }
                            if (knownDisplayName != null) {
                                recentsRepository.remove(uri)
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * Creates the editing engine and opens the editing document, off the main thread.
     *
     * Every failure — `UnsatisfiedLinkError` from a missing `libpdfedit.so`, a null
     * descriptor, a corrupt document — leaves the viewer fully usable and only
     * disables editing. Nothing here may crash the open path.
     */
    private suspend fun openEditingSession(uri: String, provider: () -> android.content.ContentResolver) {
        try {
            val engine = editingEngineFactory?.invoke() ?: PdfEditModule.engine()
            val parsed = Uri.parse(uri)
            val pfd = provider().openFileDescriptor(parsed, "r")
            if (pfd == null) {
                Log.w(TAG_EDIT, "openEditingSession: no file descriptor for $uri")
                return
            }
            pfd.use { descriptor ->
                engine.open(parsed, descriptor).fold(
                    onSuccess = { opened ->
                        editingEngine = engine
                        editingHandle = opened
                        editingUnavailableReason = null
                        Log.d(TAG_EDIT, "openEditingSession: editing document open")
                    },
                    onFailure = { error ->
                        editingUnavailableReason = error.message ?: error::class.simpleName
                        Log.w(TAG_EDIT, "openEditingSession: could not open the editing document", error)
                    },
                )
            }
        } catch (error: Throwable) {
            // Includes UnsatisfiedLinkError: the native editing module is absent.
            Log.w(TAG_EDIT, "openEditingSession: editing unavailable", error)
            editingUnavailableReason = error.message ?: error::class.simpleName ?: "unknown error"
            editingEngine = null
            editingHandle = null
        }
    }

    /** Emits the "editing unavailable" notice once per open document. */
    private fun notifyEditingUnavailable(reason: String) {
        Log.d(TAG_EDIT, "editing unavailable: $reason")
        if (editingUnavailableNotified) return
        editingUnavailableNotified = true
        _messages.tryEmit(ViewerMessage.EditingUnavailable)
    }

    override fun onCleared() {
        closeDocument()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            // Store the context only. Loading libpdfedit happens on the first engine
            // creation (off the main thread) so a device without the module still opens.
            PdfEditModule.initialize(app)
            return viewModelFactory {
                initializer {
                    PdfViewerViewModel(
                        recentsRepository = RecentFilesRepository(app),
                        openDocument = { uri -> SafDocumentOpener.open(app, Uri.parse(uri)) },
                        resolveDisplayName = { uri -> SafDocumentOpener.displayName(app, Uri.parse(uri)) },
                        editingEngineFactory = { PdfEditModule.engine() },
                        contentResolverProvider = { app.contentResolver },
                        appContext = app,
                    )
                }
            }
        }
    }
}

internal fun Throwable.toDocumentError(): DocumentError =
    this as? DocumentError ?: DocumentError.Unreadable()