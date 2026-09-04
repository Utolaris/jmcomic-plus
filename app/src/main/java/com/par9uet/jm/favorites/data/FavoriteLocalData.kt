package com.par9uet.jm.favorites.data

import androidx.paging.PagingSource
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.database.model.FavoriteComicEntity
import com.par9uet.jm.store.FavoriteMetadataPayload
import com.par9uet.jm.store.FavoriteRemoteItem
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
