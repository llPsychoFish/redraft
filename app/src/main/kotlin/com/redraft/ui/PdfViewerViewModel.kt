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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ViewerUiState(
    val documentName: String? = null,
    val pageCount: Int = 0,
    val isLoading: Boolean = false,
    val error: DocumentError? = null,
)

class PdfViewerViewModel(
    private val recentsRepository: RecentsStore,
    private val openDocument: suspend (String) -> Result<DocumentHandle>,
    private val resolveDisplayName: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cleanupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
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

    private fun openInternal(uri: String, knownDisplayName: String?) {
        viewModelScope.launch {
            openMutex.withLock {
                _uiState.update { it.copy(isLoading = true, error = null) }
                currentHandle?.close()
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
