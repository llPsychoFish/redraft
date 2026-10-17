package com.redraft.data

data class RecentFile(
    val uri: String,
    val displayName: String,
    val lastOpenedEpochMs: Long,
)

const val RECENTS_CAP = 10

fun List<RecentFile>.upsert(file: RecentFile, cap: Int = RECENTS_CAP): List<RecentFile> =
    (filterNot { it.uri == file.uri } + file)
        .sortedByDescending { it.lastOpenedEpochMs }
        .take(cap)
