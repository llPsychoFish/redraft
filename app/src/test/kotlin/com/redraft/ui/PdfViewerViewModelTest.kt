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
import com.redraft.pdfcore.PdfPageGeometry
import com.redraft.pdfcore.PdfPoint
import com.redraft.pdfcore.PdfRect
import com.redraft.pdfcore.PdfViewportTransform
import com.redraft.pdfcore.ViewportState
import com.redraft.pdfcore.selection.CharBox
import com.redraft.pdfcore.selection.PageTextLayout
import com.redraft.pdfcore.selection.TextPageLayout
import io.legere.pdfiumandroid.suspend.PdfDocumentKt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        val pageUpdates = mutableListOf<Pair<String, Int>>()
        override val recents = state
        override suspend fun addOrUpdate(file: RecentFile) {
            state.value = state.value.upsert(file)
        }

        override suspend fun updateLastPage(uri: String, pageIndex: Int) {
            pageUpdates += uri to pageIndex
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
        override val document: PdfDocumentKt
            get() = throw UnsupportedOperationException("document not used in view model tests")

        override suspend fun open(source: PdfDocumentSource) {
            log += "open:$name"
        }

        override suspend fun pageSize(index: Int): PdfPageGeometry =
            PdfPageGeometry(pageIndex = index, cropBox = PdfRect(0f, 0f, 612f, 792f), rotationIndex = 0)

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
        extractor: suspend (DocumentHandle, Int) -> PageTextLayout = { _, _ -> PageTextLayout.Empty },
        opener: (String) -> Result<DocumentHandle>,
    ): PdfViewerViewModel = PdfViewerViewModel(
        recentsRepository = recents,
        openDocument = { uri -> opener(uri) },
        resolveDisplayName = { "resolved-name" },
        clock = { 0L },
        ioDispatcher = dispatcher,
        cleanupScope = CoroutineScope(SupervisorJob() + dispatcher),
        computeDispatcher = dispatcher,
        extractPageText = extractor,
    )

    @Test
    fun openRecent_with_a_stored_page_commands_that_page() = runTest(dispatcher) {
        val recents = FakeRecents()
        val vm = viewModel(recents) { Result.success(FakeHandle(10, "doc", mutableListOf())) }

        vm.openRecent(
            RecentFile(uri = "uri", displayName = "doc", lastOpenedEpochMs = 0L, lastPageIndex = 4),
            startPage = 4,
        )
        advanceUntilIdle()

        // The page is applied by a COMMAND; the mirror only follows once the list reports back.
        assertEquals(4, vm.uiState.value.scrollCommand?.pageIndex)
        assertEquals(4, recents.recents.value.single().lastPageIndex)
    }

    @Test
    fun scrolling_persists_the_page_once_per_change() = runTest(dispatcher) {
        val recents = FakeRecents()
        val vm = viewModel(recents) { Result.success(FakeHandle(10, "doc", mutableListOf())) }
        vm.open("uri")
        advanceUntilIdle()

        vm.onScroll(visiblePageIndex = 3, offsetPx = 0)
        vm.onScroll(visiblePageIndex = 3, offsetPx = 120)
        advanceUntilIdle()
        vm.onScroll(visiblePageIndex = 5, offsetPx = 0)
        advanceUntilIdle()

        assertEquals(listOf("uri" to 3, "uri" to 5), recents.pageUpdates)
    }

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
    fun onScrollCommanded_puts_a_command_in_the_state_with_the_requested_page() = runTest(dispatcher) {
        val vm = viewModel(FakeRecents()) { Result.success(FakeHandle(3, "doc", mutableListOf())) }
        vm.open("uri-ok")
        advanceUntilIdle()

        vm.onScrollCommanded(2)

        val command = vm.uiState.value.scrollCommand
        assertNotNull(command)
        assertEquals(2, command?.pageIndex)
        assertEquals(0, command?.offsetPx)
    }

    @Test
    fun onScrollCommanded_clamps_the_page_index_to_the_document() = runTest(dispatcher) {
        val vm = viewModel(FakeRecents()) { Result.success(FakeHandle(3, "doc", mutableListOf())) }
        vm.open("uri-ok")
        advanceUntilIdle()

        vm.onScrollCommanded(99)

        assertEquals(2, vm.uiState.value.scrollCommand?.pageIndex)
    }

    @Test
    fun two_commands_to_the_same_page_are_two_different_commands() = runTest(dispatcher) {
        val vm = viewModel(FakeRecents()) { Result.success(FakeHandle(3, "doc", mutableListOf())) }
        vm.open("uri-ok")
        advanceUntilIdle()

        vm.onScrollCommanded(1)
        val first = vm.uiState.value.scrollCommand
        vm.onScrollCommanded(1)
        val second = vm.uiState.value.scrollCommand

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(1, first?.pageIndex)
        assertEquals(1, second?.pageIndex)
        assertTrue(
            "the token must advance so the second command is not equal to the first",
            (second?.token ?: 0L) != (first?.token ?: 0L),
        )
    }

    @Test
    fun successful_open_commands_the_list_to_page_zero() = runTest(dispatcher) {
        val vm = viewModel(FakeRecents()) { Result.success(FakeHandle(3, "doc", mutableListOf())) }

        vm.open("uri-ok")
        advanceUntilIdle()

        val command = vm.uiState.value.scrollCommand
        assertNotNull(command)
        assertEquals(0, command?.pageIndex)
        assertEquals(0, command?.offsetPx)
    }

    @Test
    fun consuming_a_scroll_command_clears_it_from_the_state() = runTest(dispatcher) {
        val vm = viewModel(FakeRecents()) { Result.success(FakeHandle(3, "doc", mutableListOf())) }
        vm.open("uri-ok")
        advanceUntilIdle()

        vm.onScrollCommanded(2)
        val token = vm.uiState.value.scrollCommand?.token
        assertNotNull(token)

        vm.onScrollCommandConsumed(token!!)

        assertNull(vm.uiState.value.scrollCommand)
    }

    @Test
    fun consuming_a_stale_token_does_not_clear_a_newer_command() = runTest(dispatcher) {
        val vm = viewModel(FakeRecents()) { Result.success(FakeHandle(3, "doc", mutableListOf())) }
        vm.open("uri-ok")
        advanceUntilIdle()

        vm.onScrollCommanded(1)
        val staleToken = vm.uiState.value.scrollCommand?.token
        vm.onScrollCommanded(2)
        val newer = vm.uiState.value.scrollCommand
        assertNotNull(newer)

        vm.onScrollCommandConsumed(staleToken!!)

        assertEquals(newer, vm.uiState.value.scrollCommand)
    }

    @Test
    fun an_unconsumed_command_is_still_in_the_state() = runTest(dispatcher) {
        val vm = viewModel(FakeRecents()) { Result.success(FakeHandle(3, "doc", mutableListOf())) }
        vm.open("uri-ok")
        advanceUntilIdle()

        vm.onScrollCommanded(2)

        val command = vm.uiState.value.scrollCommand
        assertNotNull(command)
        assertEquals(2, command?.pageIndex)
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

    // ─── Extraction lifecycle (D-28 … D-31) ─────────────────────────────────

    /** A one-line layout: the glyph "H" at PDF (100, 700), 10 pt wide. */
    private fun textLayout(pageIndex: Int = 0): TextPageLayout = TextPageLayout(
        pageIndex = pageIndex,
        chars = listOf(
            CharBox(
                index = 0,
                text = "H",
                rect = PdfRect(100f, 700f, 110f, 710f),
                fontSizePt = 10f,
            ),
        ),
    )

    private val viewport = ViewportState(zoom = 1f, panX = 0f, panY = 0f)
    private val transform = PdfViewportTransform(
        pageWidthPt = 612f,
        pageHeightPt = 792f,
        rotationDegrees = 0,
        renderedWidthPx = 612,
        renderedHeightPx = 792,
    )

    /** Screen point that lands on the glyph of [textLayout] (PDF y is flipped). */
    private val tapOnText = PdfPoint(105f, 92f)

    private fun TestScope.openInEditMode(
        vm: PdfViewerViewModel,
    ) {
        vm.open("uri")
        advanceUntilIdle()
        vm.setEditMode(true)
    }

    @Test
    fun a_tap_during_loading_resolves_when_the_extraction_completes() = runTest(dispatcher) {
        val gate = CompletableDeferred<PageTextLayout>()
        val vm = viewModel(
            recents = FakeRecents(),
            opener = { Result.success(FakeHandle(1, "doc", mutableListOf())) },
            extractor = { _, _ -> gate.await() },
        )
        openInEditMode(vm)

        vm.onTap(tapOnText, 0, viewport, transform)
        runCurrent()
        // The extraction is still running: the tap waits, nothing is selected.
        assertNull(vm.selectionState.value)

        gate.complete(PageTextLayout.Ready(textLayout()))
        runCurrent()

        assertNotNull("the tap must resolve once the extraction finished", vm.selectionState.value)
        assertEquals(1, vm.cachedLayoutCount)
    }

    @Test
    fun a_tap_still_pending_after_one_second_is_dropped_without_a_selection_change() = runTest(dispatcher) {
        val gate = CompletableDeferred<PageTextLayout>()
        val received = mutableListOf<ViewerMessage>()
        val vm = viewModel(
            recents = FakeRecents(),
            opener = { Result.success(FakeHandle(1, "doc", mutableListOf())) },
            extractor = { _, _ -> gate.await() },
        )
        backgroundScope.collectMessages(vm, received)
        openInEditMode(vm)

        vm.onTap(tapOnText, 0, viewport, transform)
        runCurrent()
        assertNull(vm.selectionState.value)

        // One second of virtual time passes with the extraction still running.
        advanceTimeBy(EXTRACTION_WAIT_MS)
        runCurrent()

        assertNull("the tap must be dropped, with no selection change", vm.selectionState.value)
        assertTrue("a dropped tap must not produce a message", received.isEmpty())

        // The extraction may still finish later — the tap was already dropped.
        gate.complete(PageTextLayout.Ready(textLayout()))
        runCurrent()
        assertNull(vm.selectionState.value)
    }

    @Test
    fun two_taps_on_one_empty_page_emit_one_message() = runTest(dispatcher) {
        val received = mutableListOf<ViewerMessage>()
        val vm = viewModel(
            recents = FakeRecents(),
            opener = { Result.success(FakeHandle(1, "doc", mutableListOf())) },
            extractor = { _, _ -> PageTextLayout.Empty },
        )
        backgroundScope.collectMessages(vm, received)
        openInEditMode(vm)

        vm.onTap(tapOnText, 0, viewport, transform)
        runCurrent()
        vm.onTap(tapOnText, 0, viewport, transform)
        runCurrent()

        assertEquals(1, received.size)
        assertEquals(ViewerMessage.NoSelectableText, received.single())
    }

    @Test
    fun changing_the_visible_page_allows_a_second_message() = runTest(dispatcher) {
        val received = mutableListOf<ViewerMessage>()
        val vm = viewModel(
            recents = FakeRecents(),
            opener = { Result.success(FakeHandle(3, "doc", mutableListOf())) },
            extractor = { _, _ -> PageTextLayout.Empty },
        )
        backgroundScope.collectMessages(vm, received)
        openInEditMode(vm)

        vm.onTap(tapOnText, 0, viewport, transform)
        runCurrent()
        assertEquals(1, received.size)

        // The reader leaves page 0 for page 1: the rate limit is cleared.
        vm.onVisiblePagesChanged(setOf(1))
        vm.onTap(tapOnText, 0, viewport, transform)
        runCurrent()

        assertEquals(2, received.size)
    }

    @Test
    fun closing_the_document_clears_layouts_jobs_selection_and_edit_mode() = runTest(dispatcher) {
        val vm = viewModel(
            recents = FakeRecents(),
            opener = { Result.success(FakeHandle(1, "doc", mutableListOf())) },
            extractor = { _, _ -> PageTextLayout.Ready(textLayout()) },
        )
        openInEditMode(vm)

        vm.onTap(tapOnText, 0, viewport, transform)
        runCurrent()
        assertNotNull(vm.selectionState.value)
        assertTrue(vm.editMode.value)
        assertEquals(1, vm.cachedLayoutCount)

        vm.closeDocument()
        advanceUntilIdle()

        assertNull(vm.selectionState.value)
        assertEquals(false, vm.editMode.value)
        assertEquals(0, vm.cachedLayoutCount)
        assertEquals(0, vm.activeExtractionCount)
    }

    /** Collects the ViewModel's one-shot messages into [into]. */
    private fun CoroutineScope.collectMessages(
        vm: PdfViewerViewModel,
        into: MutableList<ViewerMessage>,
    ) {
        launch { vm.messages.collect { into += it } }
    }
}
