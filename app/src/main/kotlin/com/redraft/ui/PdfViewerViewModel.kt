package com.redraft.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.redraft.data.DocumentError
import com.redraft.data.DocumentHandle
import com.redraft.data.RecentFile
import com.redraft.data.RecentFilesRepository
import com.redraft.data.RecentsStore
import com.redraft.data.SafDocumentOpener
import com.redraft.pdfcore.PdfPageSize
import com.redraft.ui.viewer.PageBitmapCache
import com.redraft.ui.overview.ThumbnailCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val pageSizes: List<PdfPageSize> = emptyList(),
    // True while a pinch gesture owns the scroll position. The scroll-restore
    // effect must not fight the focal anchoring while it is set (D-06, D-07).
    val gestureActive: Boolean = false,
    // A one-shot command to move the list. ONLY this may move the list - the
    // visiblePageIndex / scrollOffsetPx pair is a MIRROR of the list's own
    // position and must never drive it (during a fling the mirror lags a frame
    // behind, and restoring from it cancels the fling).
    val scrollCommand: ScrollCommand? = null,
)

const val MIN_ZOOM = 1f
const val MAX_ZOOM = 5f

fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

class PdfViewerViewModel(
    private val recentsRepository: RecentsStore,
    private val openDocument: suspend (String) -> Result<DocumentHandle>,
    private val resolveDisplayName: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    // Cache ownership (A-1) — single instance per ViewModel
    val pageBitmapCache: PageBitmapCache = PageBitmapCache(),
    val thumbnailCache: ThumbnailCache = ThumbnailCache(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private val _handle = MutableStateFlow<DocumentHandle?>(null)
    val handle: StateFlow<DocumentHandle?> = _handle.asStateFlow()

    private val _documentOpened = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val documentOpened: SharedFlow<Unit> = _documentOpened.asSharedFlow()

    val recents: StateFlow<List<RecentFile>> = recentsRepository.recents
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Cache size (A-3) — null = not yet measured
    private val _cacheSizeBytes = MutableStateFlow<Long?>(null)
    val cacheSizeBytes: StateFlow<Long?> = _cacheSizeBytes

    private val openMutex = Mutex()
    private var currentHandle: DocumentHandle? = null

    fun open(uri: String) {
        openInternal(uri = uri, knownDisplayName = null)
    }

    fun openRecent(file: RecentFile) {
        openInternal(uri = file.uri, knownDisplayName = file.displayName)
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun removeRecent(file: RecentFile) {
        viewModelScope.launch { recentsRepository.remove(file.uri) }
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
        _uiState.update {
            it.copy(
                visiblePageIndex = visiblePageIndex.coerceIn(0, (it.pageCount - 1).coerceAtLeast(0)),
                scrollOffsetPx = offsetPx,
            )
        }
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
        _handle.value = null
        // Recycle both caches when document is closed
        pageBitmapCache.recycleAll()
        thumbnailCache.recycleAll()
        cleanupScope.launch(NonCancellable) { handle.close() }
    }

    private fun openInternal(uri: String, knownDisplayName: String?) {
        viewModelScope.launch {
            openMutex.withLock {
                _uiState.update { it.copy(isLoading = true, error = null) }
                closeDocument()
                withContext(ioDispatcher) {
                    openDocument(uri).fold(
                        onSuccess = { opened ->
                            currentHandle = opened
                            _handle.value = opened
                            val name = knownDisplayName ?: resolveDisplayName(uri)
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
                                    // A new document starts at its first page - by a
                                    // COMMAND, not by the mirror: the mirror never
                                    // moves the list.
                                    scrollCommand = ScrollCommand(
                                        pageIndex = 0,
                                        offsetPx = 0,
                                        token = (it.scrollCommand?.token ?: 0L) + 1L,
                                    ),
                                )
                            }
                            recentsRepository.addOrUpdate(
                                RecentFile(uri = uri, displayName = name, lastOpenedEpochMs = clock()),
                            )
                            _documentOpened.tryEmit(Unit)
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