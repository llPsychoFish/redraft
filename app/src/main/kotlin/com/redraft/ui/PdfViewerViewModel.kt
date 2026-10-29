package com.redraft.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.redraft.data.DocumentError
import com.redraft.pdfcore.PdfPoint
import com.redraft.pdfcore.PdfViewportTransform
import com.redraft.pdfcore.selection.hitTest
import com.redraft.pdfcore.selection.pageTextLayout
import com.redraft.data.DocumentHandle
import com.redraft.data.RecentFile
import com.redraft.data.RecentFilesRepository
import com.redraft.data.RecentsStore
import com.redraft.data.SafDocumentOpener
import com.redraft.pdfcore.ViewportState
import com.redraft.pdfcore.PdfPageGeometry
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

    // The page that owns the current selection: its layout survives the
    // viewport eviction (D-28) and the overlay keeps working (D-24).
    private var selectionPageIndex: Int? = null

    // Rate limiting (D-31): one message per page per visit.  The marker is
    // cleared when the visible page changes.
    private var lastMessagePageIndex: Int? = null
    private var lastVisiblePageIndex: Int? = null

    /** Dropped taps awaiting extraction completion (B6, B8). */
    private val droppedTaps = mutableMapOf<Int, PdfPoint>()

    /** Flag: set when edit mode is turned on, so onVisiblePagesChanged can
     *  start extraction for initially-visible pages. */
    private var editModeJustStarted = false

    /** Number of cached page layouts — exposed for the lifecycle unit tests. */
    internal val cachedLayoutCount: Int get() = textLayoutCache.size

    /** Number of in-flight extraction jobs — exposed for the lifecycle unit tests. */
    internal val activeExtractionCount: Int get() = extractionJobs.size

    /** The last known extraction state of [pageIndex] — exposed for tests. */
    internal fun pageTextStateOf(pageIndex: Int): PageTextState? = pageTextStates[pageIndex]

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
     */
    fun onTap(
        tapScreen: PdfPoint,
        pageIndex: Int,
        viewport: ViewportState,
        transform: PdfViewportTransform,
    ) {
        if (!_editMode.value) {
            // Log.d(TAG, "Tap ignored: in read mode")
            return
        }

        viewModelScope.launch {
            val handle = currentHandle ?: return@launch

            // Log.d(TAG, "Starting extraction for page $pageIndex")
            when (val state = awaitPageText(pageIndex, handle)) {
                is PageTextState.Ready -> {
                    // A miss on a page that HAS text merely clears the
                    // selection — no message (D-30).
                    val result = withContext(computeDispatcher) {
                        com.redraft.pdfcore.selection.hitTest(tapScreen, state.layout, viewport, transform)
                    }
                    _selectionState.value = result?.let {
                        selectionPageIndex = pageIndex
                        com.redraft.ui.viewer.SelectionOverlayState(
                            selectedLine = it.line,
                            blockId = it.line.blockId,
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
        val job = viewModelScope.launch(ioDispatcher) {
            val result = try {
                extractPageText(handle, pageIndex)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                com.redraft.pdfcore.selection.PageTextLayout.Failed
            }
            when (result) {
                is com.redraft.pdfcore.selection.PageTextLayout.Ready -> {
                    textLayoutCache[pageIndex] = result.layout
                    pageTextStates[pageIndex] = PageTextState.Ready(result.layout)
                    enforceLayoutCacheLimit()
                }

                com.redraft.pdfcore.selection.PageTextLayout.Empty ->
                    pageTextStates[pageIndex] = PageTextState.Empty

                com.redraft.pdfcore.selection.PageTextLayout.Failed ->
                    pageTextStates[pageIndex] = PageTextState.Failed
            }
            extractionJobs.remove(pageIndex)
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
        val visiblePage = visible.minOrNull() ?: uiState.value.visiblePageIndex
        if (visiblePage != lastVisiblePageIndex) {
            lastVisiblePageIndex = visiblePage
            lastMessagePageIndex = null
            // Log.d(TAG, "onVisiblePagesChanged: visiblePage=$visiblePage")
        }

        // Cancel the extraction of every page that left the screen.
        val gone = extractionJobs.keys - visible
        for (page in gone) {
            // Log.d(TAG, "Cancelling extraction for page $page")
            extractionJobs.remove(page)?.cancel()
            pageTextStates.remove(page)
        }

        // Evict the layouts of the pages that left the screen; the selected
        // page keeps its layout.
        val keep = selectionPageIndex
        val evict = textLayoutCache.keys - visible - setOfNotNull(keep)
        for (page in evict) {
            textLayoutCache.remove(page)
            pageTextStates.remove(page)
        }

        // When edit mode is on, start extraction for pages that are visible.
        val alreadyKnown = textLayoutCache.keys + extractionJobs.keys + pageTextStates.keys
        val toExtract = visible - alreadyKnown
        for (page in toExtract) {
            startExtraction(page, currentHandle!!)
        }

        // If edit mode just started, also start extraction for all currently
        // visible pages (they may not have triggered onVisiblePagesChanged
        // with the new edit-mode flag yet).
        if (editModeJustStarted) {
            editModeJustStarted = false
            for (page in visible - alreadyKnown) {
                startExtraction(page, currentHandle!!)
            }
        }

        enforceLayoutCacheLimit()
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
            droppedTaps.clear()
            editModeJustStarted = false
        } else {
            editModeJustStarted = true
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
        // Clear selection state and the whole extraction lifecycle
        // (D-21, D-28): layouts, in-flight jobs and per-page states are
        // dropped, Edit mode is left, and the message rate limit is reset.
        _selectionState.value = null
        selectionPageIndex = null
        textLayoutCache.clear()
        pageTextStates.clear()
        extractionJobs.values.forEach { it.cancel() }
        extractionJobs.clear()
        lastMessagePageIndex = null
        lastVisiblePageIndex = null
        _editMode.value = false
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