package com.par9uet.jm.database.model

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "favorite_metadata",
    primaryKeys = ["accountId", "albumId"],
    indices = [Index(value = ["accountId", "metadataComplete"])],
)
data class FavoriteMetadataEntity(
    val accountId: Int,
    val albumId: Int,
    val tags: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val works: List<String> = emptyList(),
    val metadataComplete: Boolean = false,
    val metadataUpdatedAt: Long = 0L,
)
