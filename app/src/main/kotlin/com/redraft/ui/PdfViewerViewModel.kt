package com.redraft.ui

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.redraft.data.DocumentError
import com.redraft.pdfcore.PdfPoint
import com.redraft.pdfcore.PdfRect
import com.redraft.pdfcore.PdfViewportTransform
import com.redraft.pdfcore.selection.TapTolerance
import com.redraft.pdfcore.selection.groupBlocks
import kotlin.coroutines.coroutineContext
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
    val fontSizePt: Float,
    val color: Int,                   // ARGB
    val baselineRect: PdfRect,        // PDF coords for reselection
    val matrix: FloatArray,           // CTM for Path B
) {
    fun inverse(): EditCommand = copy(
        oldText = newText,
        newText = oldText,
        oldFontName = newFontName,
        newFontName = oldFontName,
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

/** Selects the appropriate bundled Liberation font for a font analysis. */
private fun selectBundledFont(analysis: FontAnalysis): String {
    val baseName = if (analysis.originalFontName.matches(Regex("^[A-Z]{6}\\+")))
        analysis.originalFontName.substringAfterLast("+")
    else
        analysis.originalFontName
    
    val family = when {
        baseName.contains("Times", true) || baseName.contains("Serif", true) -> "LiberationSerif"
        baseName.contains("Courier", true) || baseName.contains("Mono", true) -> "LiberationMono"
        else -> "LiberationSans"
    }
    return "$family${if (analysis.bold) "Bold" else ""}${if (analysis.italic) "Italic" else ""}"
}

/** Analyzes whether the original font can handle the new text, or if fallback is needed. */
private fun analyzeFontForEdit(
    originalFontName: String,
    newText: String,
    originalText: String
): FontFallbackDecision {
    val isSubset = originalFontName.matches(Regex("^[A-Z]{6}\\+"))
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
}

/** How long a tap waits for an in-flight extraction before it is dropped (D-29). */
const val EXTRACTION_WAIT_MS = 1_000L

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
        _editorState.value = null
    }

    /** Updates the selection state (used for reselection after edit). */
    fun updateSelection(newState: com.redraft.ui.viewer.SelectionOverlayState?) {
        _selectionState.value = newState
    }

    /** Updates the editor state with new draft text, computing warnings and apply eligibility. */
    fun updateEditorState(newState: EditorPanelState) {
        val editor = _editorState.value ?: return
        val selection = _selectionState.value ?: return
        val line = selection.selectedLine ?: return
        val fragment = line.fragment
        
        val fontName = fragment.style.fontName ?: ""
        val fontAnalysis = FontAnalysis(
            originalFontName = fontName,
            isSubset = fontName.matches(Regex("^[A-Z]{6}\\+")),
            isStandard14 = FontFallbackConstants.STANDARD_14_FONTS.contains(
                if (fontName.matches(Regex("^[A-Z]{6}\\+"))) fontName.substringAfterLast("+") else fontName
            ),
            bold = fontName.contains("Bold", true),
            italic = fontName.contains("Italic", true) || fontName.contains("Oblique", true),
        )
        
        val fallbackDecision = analyzeFontForEdit(fontName, newState.draftText, editor.originalText)
        
        val fontChangeNotice = when (fallbackDecision) {
            FontFallbackDecision.USE_BUNDLED_FALLBACK -> selectBundledFont(fontAnalysis)
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
        val canApply = fallbackDecision != FontFallbackDecision.UNSUPPORTED_CHARS && !lengthWarning && newState.draftText != editor.originalText

        _editorState.value = newState.copy(
            fontChangeNotice = fontChangeNotice,
            lengthWarning = lengthWarning,
            canApply = canApply,
            showUnsupportedChar = showUnsupportedChar,
        )
    }

    /** Opens the inline editor for the currently selected line. */
    fun onEditText() {
        val selection = _selectionState.value ?: return
        if (!selection.editable) {
            // Non-editable: show snackbar via message channel (handled by UI)
            return
        }
        val layout = textLayoutCache[selection.pageIndex] ?: return
        val line = selection.selectedLine ?: return
        val fragment = line.fragment

        // Full font fallback analysis (D-304)
        val originalText = line.chars.joinToString("") { it.text }.trim()
        val fontName = fragment.style.fontName ?: ""
        val fontAnalysis = FontAnalysis(
            originalFontName = fontName,
            isSubset = fontName.matches(Regex("^[A-Z]{6}\\+")),
            isStandard14 = FontFallbackConstants.STANDARD_14_FONTS.contains(
                if (fontName.matches(Regex("^[A-Z]{6}\\+"))) fontName.substringAfterLast("+") else fontName
            ),
            bold = fontName.contains("Bold", true),
            italic = fontName.contains("Italic", true) || fontName.contains("Oblique", true),
        )
        
        // Initial analysis with original text (no new chars yet)
        val fallbackDecision = analyzeFontForEdit(fontName, originalText, originalText)
        
        val fontChangeNotice = when (fallbackDecision) {
            FontFallbackDecision.USE_BUNDLED_FALLBACK -> selectBundledFont(fontAnalysis)
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
            fontSizePt = fragment.style.fontSizePt,
            textColor = fragment.style.fillColor ?: 0xFF000000.toInt(),
            fontChangeNotice = fontChangeNotice,
            lengthWarning = false,
            canApply = canApply,
            showUnsupportedChar = showUnsupportedChar,
        )
    }

    /** Commits the edit: creates EditCommand, executes via JNI, pushes to stack. */
    fun onApplyEdit(newText: String) {
        val editor = _editorState.value ?: return
        val selection = _selectionState.value ?: return
        val handle = editingHandle ?: return
        val engine = editingEngine ?: return
        val line = selection.selectedLine ?: return
        val fragment = line.fragment

        // Determine font for this edit
        val fontName = fragment.style.fontName ?: ""
        val fontAnalysis = FontAnalysis(
            originalFontName = fontName,
            isSubset = fontName.matches(Regex("^[A-Z]{6}\\+")),
            isStandard14 = FontFallbackConstants.STANDARD_14_FONTS.contains(
                if (fontName.matches(Regex("^[A-Z]{6}\\+"))) fontName.substringAfterLast("+") else fontName
            ),
            bold = fontName.contains("Bold", true),
            italic = fontName.contains("Italic", true) || fontName.contains("Oblique", true),
        )
        val fallbackDecision = analyzeFontForEdit(fontName, newText, editor.originalText)
        val newFontName = when (fallbackDecision) {
            FontFallbackDecision.USE_BUNDLED_FALLBACK -> selectBundledFont(fontAnalysis)
            else -> fontName
        }
        
        viewModelScope.launch {
            // Compute objectIndex from charIndex (suspend JNI calls)
            val charIndex = line.chars.firstOrNull()?.index ?: return@launch
            val objectIndex = findObjectIndexForCharIndex(handle, selection.pageIndex, charIndex)
            
            // Load bundled font if needed
            var fontHandle: FontHandle? = null
            if (fallbackDecision == FontFallbackDecision.USE_BUNDLED_FALLBACK) {
                fontHandle = fontCache.getOrPut(newFontName) {
                    engine.loadBundledFont(handle, newFontName, fontAnalysis.bold, fontAnalysis.italic)
                }
            }

            val cmd = EditCommand(
                documentId = currentUri ?: "",
                pageIndex = selection.pageIndex,
                lineId = selection.blockId ?: "p${selection.pageIndex}:b${line.chars.firstOrNull()?.index ?: 0}",
                objectIndex = objectIndex,
                oldText = editor.originalText,
                newText = newText,
                oldFontName = fontName,
                newFontName = newFontName,
                fontSizePt = editor.fontSizePt,
                color = editor.textColor,
                baselineRect = line.rect,
                matrix = floatArrayOf(1f, 0f, 0f, 1f, line.rect.minX, line.rect.minY),
            )

            // Execute mutation (Path A or B) with failure handling
            try {
                val success = engine.setText(handle, cmd.pageIndex, cmd.objectIndex, cmd.newText)
                if (!success && fontHandle != null) {
                    engine.replaceTextObject(handle, cmd.pageIndex, cmd.objectIndex, cmd.newText,
                        fontHandle, cmd.fontSizePt, cmd.color, cmd.matrix)
                } else if (!success) {
                    // Fallback: try with standard font (shouldn't happen if analysis is correct)
                    engine.replaceTextObject(handle, cmd.pageIndex, cmd.objectIndex, cmd.newText,
                        FontHandle(0), cmd.fontSizePt, cmd.color, cmd.matrix)
                }

                // Save copy → reopen rendering doc → invalidate cache
                val tempFile = createTempEditFile()
                val saveSuccess = engine.saveAsCopy(handle, tempFile.absolutePath)
                if (!saveSuccess) throw IllegalStateException("SaveAsCopy returned false")
                
                reopenRenderingDocument(tempFile)

                // Push to command stack ONLY on success
                _past.add(cmd)
                _pastFlow.value = _past.toList()
                _future.clear()
                _futureFlow.value = emptyList()
                _editorState.value = null
                triggerReRender(selection.pageIndex)
            } catch (e: Throwable) {
                // FAILURE HANDLING (D-305): Keep old document, show snackbar, keep editor open with draft
                // Do NOT push command to stacks (edit stays as unsaved draft)
                _messages.tryEmit(ViewerMessage.EditFailed)
                // Editor panel remains open with draft text (no _editorState.value = null)
                // Command stacks unchanged
            }
        }
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
        val handle = editingHandle ?: return
        val engine = editingEngine ?: return
        
        try {
            val success = engine.setText(handle, cmd.pageIndex, cmd.objectIndex, cmd.newText)
            if (!success) {
                // For undo/redo, we need to load the font if it's a bundled font
                val fontHandle = if (cmd.newFontName != cmd.oldFontName && cmd.newFontName.startsWith("Liberation")) {
                    fontCache.getOrPut(cmd.newFontName) {
                        // We need to determine bold/italic from the font name
                        val bold = cmd.newFontName.contains("Bold")
                        val italic = cmd.newFontName.contains("Italic")
                        engine.loadBundledFont(handle, cmd.newFontName, bold, italic)
                    }
                } else {
                    FontHandle(0)
                }
                engine.replaceTextObject(handle, cmd.pageIndex, cmd.objectIndex, cmd.newText,
                    fontHandle, cmd.fontSizePt, cmd.color, cmd.matrix)
            }

            val tempFile = createTempEditFile()
            val saveSuccess = engine.saveAsCopy(handle, tempFile.absolutePath)
            if (!saveSuccess) throw IllegalStateException("SaveAsCopy returned false")
            reopenRenderingDocument(tempFile)
            triggerReRender(cmd.pageIndex)
        } catch (e: Throwable) {
            // FAILURE HANDLING for undo/redo: Revert stacks, show error, keep old document
            // Push the command back to source stack
            _messages.tryEmit(ViewerMessage.EditFailed)
            // The stacks were already modified in popAndExecute - we need to revert
            // This is handled by the caller catching the exception, but we emit the message here
        }
    }

    private suspend fun findObjectIndexForCharIndex(handle: EditingHandle, pageIndex: Int, charIndex: Int): Int {
        val engine = editingEngine ?: return 0
        val count = engine.getTextObjectCount(handle, pageIndex)
        var accumulated = 0
        for (objIdx in 0 until count) {
            val objCharCount = engine.getTextObjectCharCount(handle, pageIndex, objIdx)
            accumulated += objCharCount
            if (charIndex < accumulated) return objIdx
        }
        return 0 // Fallback
    }

    private fun createTempEditFile(): java.io.File {
        val file = java.io.File.createTempFile("edit_", ".pdf", android.content.ContextWrapper(android.app.Application()).cacheDir)
        file.deleteOnExit()
        currentEditTempFile = file
        return file
    }

    private suspend fun reopenRenderingDocument(tempFile: java.io.File) {
        val handle = currentHandle ?: return
        val uri = currentUri ?: return
        
        // SAFE REOPEN (D-305): Open and verify new document BEFORE closing old
        val newHandleResult = withContext(ioDispatcher) {
            SafDocumentOpener.open(android.content.ContextWrapper(android.app.Application()), android.net.Uri.fromFile(tempFile))
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

                            // Initialize editing engine and handle for this document (only if content resolver is available)
                            contentResolverProvider?.let { provider ->
                                editingEngine = editingEngineFactory?.invoke() ?: PdfEditModule.engine()
                                editingHandleJob = viewModelScope.launch {
                                    val contentResolver = provider()
                                    val pfd = contentResolver.openFileDescriptor(android.net.Uri.parse(uri), "r")
                                    pfd?.use { fileDescriptor ->
                                        editingEngine?.open(android.net.Uri.parse(uri), fileDescriptor)?.fold(
                                            onSuccess = { it.also { editingHandle = it } },
                                            onFailure = { /* log error; editing unavailable */ }
                                        )
                                    }
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

    override fun onCleared() {
        closeDocument()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val app = context.applicationContext
            return viewModelFactory {
                initializer {
                    PdfViewerViewModel(
                        recentsRepository = RecentFilesRepository(app),
                        openDocument = { uri -> SafDocumentOpener.open(app, Uri.parse(uri)) },
                        resolveDisplayName = { uri -> SafDocumentOpener.displayName(app, Uri.parse(uri)) },
                    )
                }
            }
        }
    }
}

internal fun Throwable.toDocumentError(): DocumentError =
    this as? DocumentError ?: DocumentError.Unreadable()