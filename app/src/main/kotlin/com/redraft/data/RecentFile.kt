package com.redraft.data

data class RecentFile(
    val uri: String,
    val displayName: String,
    val lastOpenedEpochMs: Long,
    /** Zero-based page the reader was last on, so the viewer can reopen there. */
    val lastPageIndex: Int = 0,
)

const val RECENTS_CAP = 10

fun List<RecentFile>.upsert(file: RecentFile, cap: Int = RECENTS_CAP): List<RecentFile> =
    (filterNot { it.uri == file.uri } + file)
        .sortedByDescending { it.lastOpenedEpochMs }
        .take(cap)
