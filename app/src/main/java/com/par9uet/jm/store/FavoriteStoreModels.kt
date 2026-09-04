package com.par9uet.jm.store

const val FAVORITE_SCOPE_ALL = 0
const val FAVORITE_TERM_TAG = "tag"
const val FAVORITE_TERM_AUTHOR = "author"

data class FavoriteRemoteItem(
    val albumId: Int,
    val title: String,
    val authors: List<String> = emptyList(),
    val description: String = "",
    val image: String = "",
    val tags: List<String> = emptyList(),
    val categoryId: String? = null,
    val categoryTitle: String? = null,
    val subCategoryId: String? = null,
    val subCategoryTitle: String? = null,
)

data class FavoriteMetadataPayload(
    val albumId: Int,
    val title: String,
    val description: String,
    val authors: List<String>,
    val tags: List<String>,
    val roles: List<String>,
    val works: List<String>,
)

data class FavoriteSyncDelta(
    val added: Int,
    val removed: Int,
    val changed: Int,
    val unchanged: Int,
    val metadataIds: List<Int>,
)
