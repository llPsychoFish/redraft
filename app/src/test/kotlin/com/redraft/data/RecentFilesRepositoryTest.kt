package com.redraft.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentFilesRepositoryTest {

    private fun file(uri: String, name: String = uri, ts: Long = 0L) =
        RecentFile(uri = uri, displayName = name, lastOpenedEpochMs = ts)

    @Test
    fun upsert_adds_new_entry() {
        val result = emptyList<RecentFile>().upsert(file("a", ts = 1))

        assertEquals(listOf("a"), result.map { it.uri })
    }

    @Test
    fun upsert_updates_duplicate_uri_in_place() {
        val existing = listOf(file("a", name = "old", ts = 1), file("b", ts = 2))

        val result = existing.upsert(file("a", name = "new", ts = 3))

        assertEquals(2, result.size)
        assertEquals("new", result.first { it.uri == "a" }.displayName)
    }

    @Test
    fun upsert_orders_most_recent_first() {
        val existing = listOf(file("a", ts = 1), file("b", ts = 3))

        val result = existing.upsert(file("c", ts = 2))

        assertEquals(listOf("b", "c", "a"), result.map { it.uri })
    }

    @Test
    fun upsert_never_exceeds_cap() {
        val existing = (1..12).map { file("u$it", ts = it.toLong()) }

        val result = existing.upsert(file("new", ts = 99))

        assertEquals(RECENTS_CAP, result.size)
        assertEquals("new", result.first().uri)
    }

    @Test
    fun upsert_respects_custom_cap() {
        val existing = (1..5).map { file("u$it", ts = it.toLong()) }

        val result = existing.upsert(file("new", ts = 99), cap = 3)

        assertEquals(3, result.size)
        assertEquals(listOf("new", "u5", "u4"), result.map { it.uri })
    }
}
