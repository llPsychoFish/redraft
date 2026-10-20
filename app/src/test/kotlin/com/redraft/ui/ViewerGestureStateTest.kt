package com.redraft.ui

import com.redraft.data.RecentFile
import com.redraft.data.RecentsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The viewer state that belongs to the pinch gesture.
 *
 * The vertical axis is owned by the list scroll (D-06), so the state has no
 * vertical pan at all, and a `gestureActive` flag tells the screen when the
 * gesture - not the scroll restore - owns the scroll position.
 */
class ViewerGestureStateTest {

    private class FakeRecents : RecentsStore {
        override val recents: Flow<List<RecentFile>> = flowOf(emptyList())
        override suspend fun addOrUpdate(file: RecentFile) = Unit
        override suspend fun remove(uri: String) = Unit
        override suspend fun clear() = Unit
    }

    private fun viewModel(): PdfViewerViewModel = PdfViewerViewModel(
        recentsRepository = FakeRecents(),
        openDocument = { Result.failure(IllegalStateException("not used")) },
        resolveDisplayName = { "doc" },
    )

    @Test
    fun `viewer state has no vertical pan`() {
        val fields = ViewerUiState::class.java.declaredFields.map { it.name }
        assertTrue("panY" !in fields)
    }

    @Test
    fun `horizontal pan is stored`() {
        val vm = viewModel()

        vm.onPanChanged(-120f)

        assertEquals(-120f, vm.uiState.value.panX, 0.0001f)
    }

    @Test
    fun `gesture flag starts inactive and follows the gesture`() {
        val vm = viewModel()
        assertEquals(false, vm.uiState.value.gestureActive)

        vm.onGestureActiveChanged(true)
        assertEquals(true, vm.uiState.value.gestureActive)

        vm.onGestureActiveChanged(false)
        assertEquals(false, vm.uiState.value.gestureActive)
    }
}
