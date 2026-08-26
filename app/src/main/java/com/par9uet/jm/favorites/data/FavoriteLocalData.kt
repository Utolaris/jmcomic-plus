package com.par9uet.jm.favorites.data

import androidx.paging.PagingSource
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.database.model.FavoriteComicEntity
import com.par9uet.jm.store.FavoriteMetadataPayload
import com.par9uet.jm.store.FavoriteRemoteItem
import com.par9uet.jm.store.FavoriteStore
import com.par9uet.jm.store.FavoriteSyncDelta
import kotlinx.coroutines.flow.Flow

/** L4 query capabilities for the Room-backed local Favorites snapshot. */
interface FavoriteLocalQuery {
    fun pagingSource(
        accountId: Int,
        blockedTagList: List<String>,
        searchText: String,
        selectedTags: Set<String>,
        selectedAuthors: Set<String>,
        folderId: Int,
        tagLogic: TagFilterLogic,
    ): PagingSource<Int, FavoriteComicEntity>

    fun observeFolders(accountId: Int): Flow<Map<String, String>>

    fun observeTagCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>>

    fun observeAuthorCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>>

    suspend fun getCachedFolders(accountId: Int): Map<String, String>

    suspend fun getComics(accountId: Int, albumIds: Collection<Int>): List<Comic>
}

/** L4 mutation capabilities for the Room-backed local Favorites snapshot. */
interface FavoriteLocalMutation {
    suspend fun addFromComic(accountId: Int, comic: Comic, folderId: Int = 0)

    suspend fun remove(accountId: Int, albumIds: Collection<Int>)

    suspend fun moveToFolder(accountId: Int, albumId: Int, folderId: Int)

    suspend fun cacheFolder(accountId: Int, folderId: Int, name: String)

    suspend fun removeFolder(accountId: Int, folderId: Int)

    suspend fun renameFolder(accountId: Int, folderId: Int, name: String)
}

/** L4 snapshot operations used by the complete synchronization molecule. */
interface FavoriteLocalSync {
    suspend fun reconcileLightweightSnapshot(
        accountId: Int,
        scopeFolderId: Int,
        remoteItems: List<FavoriteRemoteItem>,
        remoteFolders: Map<Int, String>,
        syncedAt: Long,
    ): FavoriteSyncDelta

    suspend fun replaceAllSnapshot(
        accountId: Int,
        remoteItems: List<FavoriteRemoteItem>,
        remoteFolders: Map<Int, String>,
        metadata: List<FavoriteMetadataPayload>,
        syncedAt: Long,
        forceRefreshedAt: Long,
        folderMemberships: Map<Int, List<Int>> = emptyMap(),
    )

    suspend fun applyMetadata(accountId: Int, payload: FavoriteMetadataPayload, syncedAt: Long)

    suspend fun markSyncSuccess(accountId: Int, scopeFolderId: Int, syncedAt: Long)
}

/**
 * Feature-facing contract around FavoriteStore. The store remains the Room transaction boundary;
 * Favorites callers no longer need to know the store's persistence API.
 */
class RoomFavoriteLocalData(
    private val favoriteStore: FavoriteStore,
) : FavoriteLocalQuery, FavoriteLocalMutation, FavoriteLocalSync {
    override fun pagingSource(
        accountId: Int,
        blockedTagList: List<String>,
        searchText: String,
        selectedTags: Set<String>,
        selectedAuthors: Set<String>,
        folderId: Int,
        tagLogic: TagFilterLogic,
    ): PagingSource<Int, FavoriteComicEntity> = favoriteStore.pagingSource(
        accountId = accountId,
        blockedTagList = blockedTagList,
        searchText = searchText,
        selectedTags = selectedTags,
        selectedAuthors = selectedAuthors,
        folderId = folderId,
        tagLogic = tagLogic,
    )

    override fun observeFolders(accountId: Int): Flow<Map<String, String>> =
        favoriteStore.observeFolders(accountId)

    override fun observeTagCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>> =
        favoriteStore.observeTagCounts(accountId, folderId)

    override fun observeAuthorCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>> =
        favoriteStore.observeAuthorCounts(accountId, folderId)

    override suspend fun getCachedFolders(accountId: Int): Map<String, String> =
        favoriteStore.getCachedFolders(accountId)

    override suspend fun getComics(accountId: Int, albumIds: Collection<Int>): List<Comic> =
        favoriteStore.getComics(accountId, albumIds)

    override suspend fun addFromComic(accountId: Int, comic: Comic, folderId: Int) =
        favoriteStore.addFromComic(accountId, comic, folderId)

    override suspend fun remove(accountId: Int, albumIds: Collection<Int>) =
        favoriteStore.remove(accountId, albumIds.toList())

    override suspend fun moveToFolder(accountId: Int, albumId: Int, folderId: Int) =
        favoriteStore.moveToFolder(accountId, albumId, folderId)

    override suspend fun cacheFolder(accountId: Int, folderId: Int, name: String) =
        favoriteStore.cacheFolder(accountId, folderId, name)

    override suspend fun removeFolder(accountId: Int, folderId: Int) =
        favoriteStore.removeFolder(accountId, folderId)

    override suspend fun renameFolder(accountId: Int, folderId: Int, name: String) =
        favoriteStore.renameFolder(accountId, folderId, name)

    override suspend fun reconcileLightweightSnapshot(
        accountId: Int,
        scopeFolderId: Int,
        remoteItems: List<FavoriteRemoteItem>,
        remoteFolders: Map<Int, String>,
        syncedAt: Long,
    ): FavoriteSyncDelta = favoriteStore.reconcileLightweightSnapshot(
        accountId = accountId,
        scopeFolderId = scopeFolderId,
        remoteItems = remoteItems,
        remoteFolders = remoteFolders,
        syncedAt = syncedAt,
    )

    override suspend fun replaceAllSnapshot(
        accountId: Int,
        remoteItems: List<FavoriteRemoteItem>,
        remoteFolders: Map<Int, String>,
        metadata: List<FavoriteMetadataPayload>,
        syncedAt: Long,
        forceRefreshedAt: Long,
        folderMemberships: Map<Int, List<Int>>,
    ) = favoriteStore.replaceAllSnapshot(
        accountId = accountId,
        remoteItems = remoteItems,
        remoteFolders = remoteFolders,
        metadata = metadata,
        syncedAt = syncedAt,
        forceRefreshedAt = forceRefreshedAt,
        folderMemberships = folderMemberships,
    )

    override suspend fun applyMetadata(accountId: Int, payload: FavoriteMetadataPayload, syncedAt: Long) =
        favoriteStore.applyMetadata(accountId, payload, syncedAt)

    override suspend fun markSyncSuccess(accountId: Int, scopeFolderId: Int, syncedAt: Long) =
        favoriteStore.markSyncSuccess(accountId, scopeFolderId, syncedAt)
}
