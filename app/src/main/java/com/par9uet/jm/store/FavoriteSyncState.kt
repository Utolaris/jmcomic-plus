package com.par9uet.jm.store

data class FavoriteSyncProgress(
    val completed: Int = 0,
    val total: Int = 0,
    val phase: String = "",
)

data class FavoriteSyncReport(
    val added: Int,
    val removed: Int,
    val changed: Int,
    val unchanged: Int,
    val metadataFetched: Int,
)
