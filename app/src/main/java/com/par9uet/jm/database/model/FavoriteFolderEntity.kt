package com.par9uet.jm.database.model

import androidx.room.Entity

@Entity(
    tableName = "favorite_folders",
    primaryKeys = ["accountId", "folderId"],
)
data class FavoriteFolderEntity(
    val accountId: Int,
    val folderId: Int,
    val name: String,
    val lastSyncedAt: Long = 0L,
)
