package com.par9uet.jm.favorites.data

import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.favorites.data.FavoriteMetadataPayload
import com.par9uet.jm.favorites.data.FavoriteRemoteItem
import com.par9uet.jm.favorites.data.FavoriteSyncDelta

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
