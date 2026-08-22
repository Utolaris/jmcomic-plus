package com.par9uet.jm.database.model

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "favorite_comics",
    primaryKeys = ["accountId", "albumId"],
    indices = [
        Index(value = ["accountId", "lastFavoriteOrder"]),
        Index(value = ["accountId", "metadataComplete"]),
    ],
)
data class FavoriteComicEntity(
    val accountId: Int,
    val albumId: Int,
    val title: String,
    val authorList: List<String> = emptyList(),
    val description: String = "",
    val image: String = "",
    val tagList: List<String> = emptyList(),
    val roleList: List<String> = emptyList(),
    val workList: List<String> = emptyList(),
    val categoryId: String? = null,
    val categoryTitle: String? = null,
    val subCategoryId: String? = null,
    val subCategoryTitle: String? = null,
    val metadataComplete: Boolean = false,
    val metadataUpdatedAt: Long = 0L,
    val lastFavoriteOrder: Int = 0,
    val lastFavoriteSyncAt: Long = 0L,
)
