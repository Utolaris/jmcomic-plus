package com.par9uet.jm.favorites.usecase

import android.os.SystemClock
import com.par9uet.jm.favorites.data.FavoriteLocalSync
import com.par9uet.jm.favorites.data.FavoriteRemoteQuery
import com.par9uet.jm.favorites.data.toFavoriteSyncError
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.FAVORITE_SCOPE_ALL
import com.par9uet.jm.store.FavoriteMetadataPayload
import com.par9uet.jm.store.FavoriteRemoteItem
import com.par9uet.jm.store.FavoriteSyncProgress
import com.par9uet.jm.store.FavoriteSyncReport
import com.par9uet.jm.favorites.data.FavoriteSession
import com.par9uet.jm.favorites.data.FavoriteSessionSnapshot
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Coordinates persistent favorite synchronization without owning favorite persistence. */
class SyncFavorites(
    private val remoteQuery: FavoriteRemoteQuery,
    private val localSync: FavoriteLocalSync,
    private val session: FavoriteSession,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    private data class RemoteFavoriteSnapshot(
        val items: List<FavoriteRemoteItem>,
        val folders: Map<Int, String>,
        val folderMemberships: Map<Int, List<Int>> = emptyMap(),
    )

    private data class MetadataFetchResult(
        val payloads: List<FavoriteMetadataPayload>,
        val failures: Int,
    )

    suspend fun synchronize(
        sessionSnapshot: FavoriteSessionSnapshot,
        folderId: Int = FAVORITE_SCOPE_ALL,
        force: Boolean = false,
        onProgress: (FavoriteSyncProgress) -> Unit = {},
    ): NetWorkResult<FavoriteSyncReport> {
        val accountId = sessionSnapshot.accountId
        val scopeFolderId = if (force) FAVORITE_SCOPE_ALL else folderId
        val startedAt = elapsedRealtime()
        log("FavoritesSync", "start account=$accountId folder=$folderId force=$force")
        return try {
            if (!session.isCurrent(sessionSnapshot)) return NetWorkResult.Error("登录账号已变化")
            val snapshot = fetchRemoteFavoriteSnapshot(
                sessionSnapshot = sessionSnapshot,
                folderId = scopeFolderId,
                includeAllFolderMemberships = force,
                onProgress = onProgress,
            )
            val syncedAt = System.currentTimeMillis()
            if (!session.isCurrent(sessionSnapshot)) return NetWorkResult.Error("登录账号已变化")
            if (force) {
                val metadata = fetchFavoriteMetadata(
                    sessionSnapshot = sessionSnapshot,
                    ids = snapshot.items.map { it.albumId },
                    failFast = true,
                    onProgress = onProgress,
                )
                if (!session.isCurrent(sessionSnapshot)) return NetWorkResult.Error("登录账号已变化")
                session.withCurrentSession(sessionSnapshot) {
                    localSync.replaceAllSnapshot(
                        accountId = accountId,
                        remoteItems = snapshot.items,
                        remoteFolders = snapshot.folders,
                        folderMemberships = snapshot.folderMemberships,
                        metadata = metadata.payloads,
                        syncedAt = syncedAt,
                        forceRefreshedAt = syncedAt,
                    )
                } ?: return NetWorkResult.Error("登录账号已变化")
                log(
                    "FavoritesSync",
                    "force remote=${snapshot.items.size} metadata=${metadata.payloads.size} " +
                        "duration=${elapsedRealtime() - startedAt}ms",
                )
                NetWorkResult.Success(
                    FavoriteSyncReport(
                        added = snapshot.items.size,
                        removed = 0,
                        changed = snapshot.items.size,
                        unchanged = 0,
                        metadataFetched = metadata.payloads.size,
                    )
                )
            } else {
                val deltaStartedAt = elapsedRealtime()
                val delta = session.withCurrentSession(sessionSnapshot) {
                    localSync.reconcileLightweightSnapshot(
                        accountId = accountId,
                        scopeFolderId = scopeFolderId,
                        remoteItems = snapshot.items,
                        remoteFolders = snapshot.folders,
                        syncedAt = syncedAt,
                    )
                } ?: return NetWorkResult.Error("登录账号已变化")
                val metadata = fetchFavoriteMetadata(
                    sessionSnapshot = sessionSnapshot,
                    ids = delta.metadataIds,
                    failFast = false,
                    onProgress = onProgress,
                )
                for (payload in metadata.payloads) {
                    if (!session.isCurrent(sessionSnapshot)) return NetWorkResult.Error("登录账号已变化")
                    session.withCurrentSession(sessionSnapshot) {
                        localSync.applyMetadata(accountId, payload, syncedAt)
                    } ?: return NetWorkResult.Error("登录账号已变化")
                }
                if (metadata.failures > 0) {
                    log(
                        "FavoritesSync",
                        "metadata failures=${metadata.failures}; keeping previous complete metadata where available",
                    )
                }
                session.withCurrentSession(sessionSnapshot) {
                    localSync.markSyncSuccess(accountId, scopeFolderId, syncedAt)
                } ?: return NetWorkResult.Error("登录账号已变化")
                log(
                    "FavoritesSync",
                    "remote=${snapshot.items.size} added=${delta.added} " +
                        "removed=${delta.removed} changed=${delta.changed} " +
                        "unchanged=${delta.unchanged} metadataFetch=${metadata.payloads.size} " +
                        "deltaDuration=${elapsedRealtime() - deltaStartedAt}ms " +
                        "duration=${elapsedRealtime() - startedAt}ms",
                )
                NetWorkResult.Success(
                    FavoriteSyncReport(
                        added = delta.added,
                        removed = delta.removed,
                        changed = delta.changed,
                        unchanged = delta.unchanged,
                        metadataFetched = metadata.payloads.size,
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(
                "FavoritesSync",
                "${e.message ?: "同步收藏夹失败"} duration=${elapsedRealtime() - startedAt}ms",
            )
            e.toFavoriteSyncError()
        }
    }

    private suspend fun fetchRemoteFavoriteSnapshot(
        sessionSnapshot: FavoriteSessionSnapshot,
        folderId: Int,
        includeAllFolderMemberships: Boolean,
        onProgress: (FavoriteSyncProgress) -> Unit,
        progressPhase: String = "收藏页面",
    ): RemoteFavoriteSnapshot {
        val startedAt = elapsedRealtime()
        val snapshot = withContext(Dispatchers.IO) {
            val items = mutableListOf<FavoriteRemoteItem>()
            val folders = mutableMapOf<Int, String>()
            var page = 1
            var expectedTotal = 0
            var continuePaging = true
            while (continuePaging) {
                val favoritePage = session.withBoundRemoteSession(sessionSnapshot) {
                    remoteQuery.getFavorites(folderId, page)
                } ?: error("登录账号已变化")
                val pageItems = favoritePage.items
                items += pageItems
                folders += favoritePage.folders
                expectedTotal = favoritePage.totalItems
                onProgress(FavoriteSyncProgress(items.size, expectedTotal, progressPhase))
                continuePaging = when {
                    favoritePage.totalPages > 0 -> page < favoritePage.totalPages
                    pageItems.isEmpty() -> false
                    else -> pageItems.size >= FAVORITE_REMOTE_PAGE_SIZE
                }
                page++
            }
            RemoteFavoriteSnapshot(
                items = items.distinctBy { it.albumId },
                folders = folders.apply { putIfAbsent(FAVORITE_SCOPE_ALL, "全部") },
            )
        }
        log(
            "FavoritesSync",
            "favorite pages folder=$folderId remote=${snapshot.items.size} " +
                "duration=${elapsedRealtime() - startedAt}ms",
        )
        if (!includeAllFolderMemberships) return snapshot

        val folderMemberships = snapshot.folders.keys
            .filter { it > FAVORITE_SCOPE_ALL }
            .associateWith { nestedFolderId ->
                fetchRemoteFavoriteSnapshot(
                    sessionSnapshot = sessionSnapshot,
                    folderId = nestedFolderId,
                    includeAllFolderMemberships = false,
                    onProgress = onProgress,
                    progressPhase = "收藏夹 $nestedFolderId",
                ).items.map { it.albumId }
            }
        return snapshot.copy(folderMemberships = folderMemberships)
    }

    private suspend fun fetchFavoriteMetadata(
        sessionSnapshot: FavoriteSessionSnapshot,
        ids: List<Int>,
        failFast: Boolean,
        onProgress: (FavoriteSyncProgress) -> Unit,
    ): MetadataFetchResult = coroutineScope {
        val distinctIds = ids.distinct()
        if (distinctIds.isEmpty()) return@coroutineScope MetadataFetchResult(emptyList(), 0)
        val startedAt = elapsedRealtime()
        val semaphore = Semaphore(FAVORITE_METADATA_CONCURRENCY)
        var completed = 0
        val results = distinctIds.map { albumId ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        // Album metadata is public and can be fetched concurrently. The account's
                        // local write still requires the captured session under the commit lock.
                        check(session.isCurrent(sessionSnapshot)) { "登录账号已变化" }
                        val metadata = remoteQuery.getMetadata(albumId)
                        check(session.isCurrent(sessionSnapshot)) { "登录账号已变化" }
                        Result.success(metadata)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }.also {
                    synchronized(distinctIds) {
                        completed++
                        onProgress(FavoriteSyncProgress(completed, distinctIds.size, "漫画元数据"))
                    }
                }
            }
        }.awaitAll()
        val failures = results.count { it.isFailure }
        log(
            "FavoritesSync",
            "metadata enrichment requested=${distinctIds.size} success=${results.size - failures} " +
                "failures=$failures duration=${elapsedRealtime() - startedAt}ms",
        )
        if (failFast && failures > 0) {
            throw IllegalStateException(
                "强制刷新收藏夹时有 $failures 部漫画元数据获取失败",
                results.firstNotNullOf { it.exceptionOrNull() },
            )
        }
        MetadataFetchResult(
            payloads = results.mapNotNull { it.getOrNull() },
            failures = failures,
        )
    }

    private companion object {
        const val FAVORITE_REMOTE_PAGE_SIZE = 20
        const val FAVORITE_METADATA_CONCURRENCY = 4
    }
}
