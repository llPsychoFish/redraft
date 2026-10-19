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

data class ViewerUiState(
    val documentName: String? = null,
    val pageCount: Int = 0,
    val isLoading: Boolean = false,
    val error: DocumentError? = null,
    val zoom: Float = 1f,
    val visiblePageIndex: Int = 0,
    val scrollOffsetPx: Int = 0,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val pageSizes: List<PdfPageSize> = emptyList(),
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private val _handle = MutableStateFlow<DocumentHandle?>(null)
    val handle: StateFlow<DocumentHandle?> = _handle.asStateFlow()

    private val _documentOpened = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val documentOpened: SharedFlow<Unit> = _documentOpened.asSharedFlow()

    val recents: StateFlow<List<RecentFile>> = recentsRepository.recents
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    fun onPanChanged(panX: Float, panY: Float) {
        _uiState.update { it.copy(panX = panX, panY = panY) }
    }

    fun onScroll(visiblePageIndex: Int, offsetPx: Int) {
        _uiState.update {
            it.copy(
                visiblePageIndex = visiblePageIndex.coerceIn(0, (it.pageCount - 1).coerceAtLeast(0)),
                scrollOffsetPx = offsetPx,
            )
        }
    }

    private fun openInternal(uri: String, knownDisplayName: String?) {
        viewModelScope.launch {
            openMutex.withLock {
                _uiState.update { it.copy(isLoading = true, error = null) }
                withContext(ioDispatcher) {
                    currentHandle?.close()
                }
                currentHandle = null
                _handle.value = null

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
                                panY = 0f,
                                pageSizes = emptyList(),
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
                                panY = 0f,
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

    override fun onCleared() {
        val handle = currentHandle ?: return
        currentHandle = null
        _handle.value = null
        cleanupScope.launch(NonCancellable) { handle.close() }
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
