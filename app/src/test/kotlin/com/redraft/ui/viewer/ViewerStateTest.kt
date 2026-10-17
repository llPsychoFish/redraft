package com.redraft.ui.viewer

import com.redraft.ui.clampZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerStateTest {

    @Test
    fun `zoom below min is clamped to 1f`() {
        assertEquals(1f, clampZoom(0.2f), 0.0001f)
    }

    @Test
    fun `zoom above max is clamped to 5f`() {
        assertEquals(5f, clampZoom(9f), 0.0001f)
    }

    @Test
    fun `zoom in range is unchanged`() {
        assertEquals(2.5f, clampZoom(2.5f), 0.0001f)
        assertEquals(1f, clampZoom(1f), 0.0001f)
        assertEquals(5f, clampZoom(5f), 0.0001f)
    }

    @Test
    fun `scale bucket at zoom boundaries`() {
        assertEquals(0, scaleBucketForZoom(0.5f))
        assertEquals(Z_00_SCALE_BUCKETS.lastIndex, scaleBucketForZoom(5f))
        assertTrue(scaleBucketForZoom(1f) in 0..Z_00_SCALE_BUCKETS.lastIndex)
    }
}
