package com.redraft.ui.overview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The size a page thumbnail is rendered at.
 *
 * The thumbnail's width is fixed by the grid cell; its height follows the
 * page's aspect ratio. A quarter-turn (90/270) page is displayed with its
 * width and height swapped, so the thumbnail follows the DISPLAYED aspect
 * ratio, not the page's stored one.
 *
 * The math is pure and Android-free so it can be unit-tested without a device.
 */
class ThumbnailLayoutTest {

    @Test
    fun `a portrait page 612x792 at 200 px wide is 200x259`() {
        val size = thumbnailSize(612f, 792f, rotationDegrees = 0, targetWidthPx = 200)
        assertEquals(200 to 259, size)
    }

    @Test
    fun `a landscape page 792x612 at 200 px wide is 200x155`() {
        val size = thumbnailSize(792f, 612f, rotationDegrees = 0, targetWidthPx = 200)
        assertEquals(200 to 155, size)
    }

    @Test
    fun `a quarter-turn swaps the displayed width and height so 612x792 rotated 90 is 200x155`() {
        // The rule: a quarter turn swaps the displayed width and the height.
        // A 612x792 page displayed 792 wide is 612 tall -> 200x155, the same
        // shape as the landscape page above.
        val size = thumbnailSize(612f, 792f, rotationDegrees = 90, targetWidthPx = 200)
        assertEquals(200 to 155, size)
    }

    @Test
    fun `a 270 degree turn swaps the dimensions the same way as 90`() {
        val ninety = thumbnailSize(612f, 792f, rotationDegrees = 90, targetWidthPx = 200)
        val twoSeventy = thumbnailSize(612f, 792f, rotationDegrees = 270, targetWidthPx = 200)
        assertEquals(ninety, twoSeventy)
    }

    @Test
    fun `an unrotated page keeps its own aspect ratio`() {
        val size = thumbnailSize(612f, 792f, rotationDegrees = 0, targetWidthPx = 200)
        assertEquals(200 to 259, size)
    }

    @Test
    fun `an unknown page size has no thumbnail size`() {
        assertNull(thumbnailSize(0f, 0f, rotationDegrees = 0, targetWidthPx = 200))
    }

    @Test
    fun `a target width of zero has no thumbnail size`() {
        assertNull(thumbnailSize(612f, 792f, rotationDegrees = 0, targetWidthPx = 0))
    }

    @Test
    fun `a negative page dimension has no thumbnail size`() {
        assertNull(thumbnailSize(-1f, 792f, rotationDegrees = 0, targetWidthPx = 200))
    }

    @Test
    fun `the thumbnail bucket is outside the zoom ladder`() {
        // The zoom ladder uses buckets 0..7; the thumbnails get their own.
        assertEquals(8, THUMBNAIL_BUCKET)
    }
}
