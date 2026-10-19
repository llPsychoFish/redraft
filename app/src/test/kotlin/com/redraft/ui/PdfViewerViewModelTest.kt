package com.redraft.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.redraft.data.DocumentError
import com.redraft.data.DocumentHandle
import com.redraft.data.RecentFile
import com.redraft.data.RecentsStore
import com.redraft.data.upsert
import com.redraft.pdfcore.PdfDocumentSource
import com.redraft.pdfcore.PdfPageSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PdfViewerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeRecents : RecentsStore {
        private val state = MutableStateFlow<List<RecentFile>>(emptyList())
        val removed = mutableListOf<String>()
        override val recents = state
        override suspend fun addOrUpdate(file: RecentFile) {
            state.value = state.value.upsert(file)
        }

        override suspend fun remove(uri: String) {
            removed += uri
            state.value = state.value.filterNot { it.uri == uri }
        }

        override suspend fun clear() {
            state.value = emptyList()
        }
    }

    private class FakeHandle(
        override var pageCount: Int,
        private val name: String,
        private val log: MutableList<String>,
    ) : DocumentHandle {
        override suspend fun open(source: PdfDocumentSource) {
            log += "open:$name"
        }

        override suspend fun pageSize(index: Int): PdfPageSize = PdfPageSize(612f, 792f, 0)

        override suspend fun renderPage(
            index: Int,
            targetWidthPx: Int,
            targetHeightPx: Int,
        ): Bitmap = throw UnsupportedOperationException("renderPage is not exercised here")

        override suspend fun close() {
            log += "close:$name"
        }
    }

    private fun viewModel(
        recents: RecentsStore,
        log: MutableList<String> = mutableListOf(),
        opener: (String) -> Result<DocumentHandle>,
    ): PdfViewerViewModel = PdfViewerViewModel(
        recentsRepository = recents,
        openDocument = { uri -> opener(uri) },
        resolveDisplayName = { "resolved-name" },
        clock = { 0L },
        ioDispatcher = dispatcher,
        cleanupScope = CoroutineScope(SupervisorJob() + dispatcher),
    )

    @Test
    fun opening_second_document_closes_first_before_opening_second() = runTest(dispatcher) {
        val log = mutableListOf<String>()
        var counter = 0
        val vm = viewModel(FakeRecents(), log) {
            counter += 1
            log += "open:$counter"
            Result.success(FakeHandle(1, counter.toString(), log))
        }

        vm.open("uri-1")
        advanceUntilIdle()
        vm.open("uri-2")
        advanceUntilIdle()

        assertEquals(listOf("open:1", "close:1", "open:2"), log)
    }

    @Test
    fun onCleared_closes_the_open_handle() = runTest(dispatcher) {
        val log = mutableListOf<String>()
        val store = ViewModelStore()
        val factory = viewModelFactory {
            initializer {
                PdfViewerViewModel(
                    recentsRepository = FakeRecents(),
                    openDocument = {
                        log += "open"
                        Result.success(FakeHandle(5, "doc", log))
                    },
                    resolveDisplayName = { "doc" },
                    clock = { 0L },
                    ioDispatcher = dispatcher,
                    cleanupScope = CoroutineScope(SupervisorJob() + dispatcher),
                )
            }
        }
        val vm = ViewModelProvider(store, factory).get(PdfViewerViewModel::class.java)

        vm.open("uri")
        advanceUntilIdle()
        store.clear()
        advanceUntilIdle()

        assertTrue("close:doc" in log)
    }

    @Test
    fun open_failure_sets_error_and_leaves_no_open_handle() = runTest(dispatcher) {
        val vm = viewModel(FakeRecents()) { Result.failure(DocumentError.Unreadable()) }

        vm.open("bad-uri")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.error is DocumentError.Unreadable)
        assertNull(vm.handle.value)
    }

    @Test
    fun failed_recent_open_removes_entry_from_recents() = runTest(dispatcher) {
        val recents = FakeRecents()
        val vm = viewModel(recents) { Result.failure(DocumentError.AccessRevoked()) }

        vm.openRecent(RecentFile("uri-x", "X", 1L))
        advanceUntilIdle()

        assertTrue("uri-x" in recents.removed)
        assertTrue(vm.uiState.value.error is DocumentError.AccessRevoked)
    }

    @Test
    fun successful_open_records_recent_and_updates_state() = runTest(dispatcher) {
        val recents = FakeRecents()
        val vm = viewModel(recents) { Result.success(FakeHandle(3, "doc", mutableListOf())) }

        vm.open("uri-ok")
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals("resolved-name", state.documentName)
        assertEquals(3, state.pageCount)
        assertEquals(false, state.isLoading)
        assertNull(state.error)
        assertEquals(listOf("uri-ok"), recents.recents.value.map { it.uri })
    }
}
