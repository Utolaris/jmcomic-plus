package com.par9uet.jm.database.model

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "favorite_folder_memberships",
    primaryKeys = ["accountId", "folderId", "albumId"],
    indices = [
        Index(value = ["accountId", "folderId", "remoteOrder"]),
        Index(value = ["accountId", "albumId"]),
    ],
)
data class FavoriteFolderMembershipEntity(
    val accountId: Int,
    val folderId: Int,
    val albumId: Int,
    val remoteOrder: Int,
    val lastSyncedAt: Long,
)
