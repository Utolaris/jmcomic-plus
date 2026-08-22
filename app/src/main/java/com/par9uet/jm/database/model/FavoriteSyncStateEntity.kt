package com.par9uet.jm.database.model

import androidx.room.Entity

@Entity(
    tableName = "favorite_sync_state",
    primaryKeys = ["accountId", "scopeFolderId"],
)
data class FavoriteSyncStateEntity(
    val accountId: Int,
    val scopeFolderId: Int,
    val lastSuccessfulSyncAt: Long = 0L,
    val lastForceRefreshAt: Long = 0L,
    val generation: Long = 0L,
)
