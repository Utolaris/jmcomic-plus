package com.par9uet.jm.favorites.data

import com.par9uet.jm.data.models.CollectComicOrderFilter
import com.par9uet.jm.repository.impl.AuthenticatedEmbeddedClient
import com.par9uet.jm.store.FavoriteMetadataPayload
import com.par9uet.jm.store.FavoriteRemoteItem
import io.github.jukomu.jmcomic.api.model.FavoriteQuery
import io.github.jukomu.jmcomic.api.model.JmAlbum
import io.github.jukomu.jmcomic.api.model.JmAlbumMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FavoriteRemotePage(
    val items: List<FavoriteRemoteItem>,
    val folders: Map<Int, String>,
    val totalItems: Int,
    val totalPages: Int,
)

/** L4 remote query capability for the persistent Favorites snapshot. */
interface FavoriteRemoteQuery {
    suspend fun getFavorites(
        folderId: Int,
        page: Int,
        order: CollectComicOrderFilter,
    ): FavoriteRemotePage

    suspend fun getMetadata(albumId: Int): FavoriteMetadataPayload
}

/** Adapts the embedded JM API and its response models to feature-facing sync data. */
class EmbeddedFavoriteRemoteQuery(
    private val authenticatedEmbeddedClient: AuthenticatedEmbeddedClient,
) : FavoriteRemoteQuery {
    override suspend fun getFavorites(
        folderId: Int,
        page: Int,
        @Suppress("UNUSED_PARAMETER") order: CollectComicOrderFilter,
    ): FavoriteRemotePage = withContext(Dispatchers.IO) {
        requireNotNull(
            authenticatedEmbeddedClient.withClient { client ->
                client.getFavorites(
                    FavoriteQuery.Builder().folderId(folderId).page(page).build()
                )
            }
        ).let { favoritePage ->
            FavoriteRemotePage(
                items = favoritePage.content().orEmpty().mapNotNull { it.toFavoriteRemoteItem() },
                folders = favoritePage.folderList().orEmpty().mapNotNull { (id, name) ->
                    id.toIntOrNull()?.let { it to name }
                }.toMap(),
                totalItems = favoritePage.totalItems(),
                totalPages = favoritePage.totalPages(),
            )
        }
    }

    override suspend fun getMetadata(albumId: Int): FavoriteMetadataPayload = withContext(Dispatchers.IO) {
        requireNotNull(
            authenticatedEmbeddedClient.withClient { client ->
                client.getAlbum(albumId.toString())
            }
        ).toFavoriteMetadataPayload()
    }
}

private fun JmAlbumMeta.toFavoriteRemoteItem(): FavoriteRemoteItem? {
    val albumId = id().toIntOrNull() ?: return null
    return FavoriteRemoteItem(
        albumId = albumId,
        title = title().orEmpty(),
        authors = authors().orEmpty(),
        description = description().orEmpty(),
        image = image().orEmpty(),
        tags = tags().orEmpty(),
        categoryId = category()?.id(),
        categoryTitle = category()?.title(),
        subCategoryId = subCategory()?.id(),
        subCategoryTitle = subCategory()?.title(),
    )
}

private fun JmAlbum.toFavoriteMetadataPayload() = FavoriteMetadataPayload(
    albumId = id().toIntOrNull() ?: 0,
    title = title().orEmpty(),
    description = description().orEmpty(),
    authors = authors().orEmpty(),
    tags = tags().orEmpty(),
    roles = actors().orEmpty(),
    works = works().orEmpty(),
)
