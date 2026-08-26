package com.par9uet.jm.favorites.usecase

import android.os.SystemClock
import com.par9uet.jm.data.models.CollectComicOrderFilter
import com.par9uet.jm.favorites.data.FavoriteLocalSync
import com.par9uet.jm.favorites.data.FavoriteRemoteQuery
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.FAVORITE_SCOPE_ALL
import com.par9uet.jm.store.FavoriteMetadataPayload
import com.par9uet.jm.store.FavoriteRemoteItem
import com.par9uet.jm.store.FavoriteSyncProgress
import com.par9uet.jm.store.FavoriteSyncReport
import com.par9uet.jm.storage.UserStorage
import com.par9uet.jm.utils.log
import com.par9uet.jm.utils.logError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Coordinates persistent favorite synchronization without owning favorite persistence. */
class SyncFavorites(
    private val remoteQuery: FavoriteRemoteQuery,
    private val localSync: FavoriteLocalSync,
    private val userStorage: UserStorage,
    private val applicationScope: CoroutineScope,
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

    private val syncCoordinator by lazy {
        FavoriteSyncCoordinator(
            applicationScope = applicationScope,
            isActiveAccount = ::isActiveFavoriteAccount,
            performSync = ::performFavoriteSync,
        )
    }

    suspend fun synchronize(
        accountId: Int,
        folderId: Int = FAVORITE_SCOPE_ALL,
        force: Boolean = false,
        order: CollectComicOrderFilter = CollectComicOrderFilter.COLLECT_TIME,
        onProgress: (FavoriteSyncProgress) -> Unit = {},
    ): NetWorkResult<FavoriteSyncReport> = syncCoordinator.synchronize(
        accountId = accountId,
        folderId = folderId,
        force = force,
        order = order,
        onProgress = onProgress,
    )

    private suspend fun performFavoriteSync(
        accountId: Int,
        folderId: Int,
        force: Boolean,
        order: CollectComicOrderFilter,
        onProgress: (FavoriteSyncProgress) -> Unit,
    ): NetWorkResult<FavoriteSyncReport> {
        val startedAt = SystemClock.elapsedRealtime()
        log("FavoritesSync", "start account=$accountId folder=$folderId force=$force order=$order")
        return try {
            if (!isActiveFavoriteAccount(accountId)) return NetWorkResult.Error("登录账号已变化")
            val snapshot = fetchRemoteFavoriteSnapshot(
                folderId = folderId,
                includeAllFolderMemberships = force,
                order = order,
                onProgress = onProgress,
            )
            val syncedAt = System.currentTimeMillis()
            if (!isActiveFavoriteAccount(accountId)) return NetWorkResult.Error("登录账号已变化")
            if (force) {
                val metadata = fetchFavoriteMetadata(
                    ids = snapshot.items.map { it.albumId },
                    failFast = true,
                    onProgress = onProgress,
                )
                if (!isActiveFavoriteAccount(accountId)) return NetWorkResult.Error("登录账号已变化")
                localSync.replaceAllSnapshot(
                    accountId = accountId,
                    remoteItems = snapshot.items,
                    remoteFolders = snapshot.folders,
                    folderMemberships = snapshot.folderMemberships,
                    metadata = metadata.payloads,
                    syncedAt = syncedAt,
                    forceRefreshedAt = syncedAt,
                )
                log(
                    "FavoritesSync",
                    "force remote=${snapshot.items.size} metadata=${metadata.payloads.size} " +
                        "duration=${SystemClock.elapsedRealtime() - startedAt}ms",
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
                val deltaStartedAt = SystemClock.elapsedRealtime()
                val delta = localSync.reconcileLightweightSnapshot(
                    accountId = accountId,
                    scopeFolderId = folderId,
                    remoteItems = snapshot.items,
                    remoteFolders = snapshot.folders,
                    syncedAt = syncedAt,
                )
                val metadata = fetchFavoriteMetadata(
                    ids = delta.metadataIds,
                    failFast = false,
                    onProgress = onProgress,
                )
                for (payload in metadata.payloads) {
                    if (!isActiveFavoriteAccount(accountId)) return NetWorkResult.Error("登录账号已变化")
                    localSync.applyMetadata(accountId, payload, syncedAt)
                }
                if (metadata.failures > 0) {
                    log(
                        "FavoritesSync",
                        "metadata failures=${metadata.failures}; keeping previous complete metadata where available",
                    )
                }
                localSync.markSyncSuccess(accountId, folderId, syncedAt)
                log(
                    "FavoritesSync",
                    "remote=${snapshot.items.size} added=${delta.added} " +
                        "removed=${delta.removed} changed=${delta.changed} " +
                        "unchanged=${delta.unchanged} metadataFetch=${metadata.payloads.size} " +
                        "deltaDuration=${SystemClock.elapsedRealtime() - deltaStartedAt}ms " +
                        "duration=${SystemClock.elapsedRealtime() - startedAt}ms",
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
                "${e.message ?: "同步收藏夹失败"} duration=${SystemClock.elapsedRealtime() - startedAt}ms",
            )
            NetWorkResult.Error(e.message ?: "同步收藏夹失败")
        }
    }

    private fun isActiveFavoriteAccount(accountId: Int): Boolean = userStorage.get().id == accountId

    private suspend fun fetchRemoteFavoriteSnapshot(
        folderId: Int,
        includeAllFolderMemberships: Boolean,
        order: CollectComicOrderFilter,
        onProgress: (FavoriteSyncProgress) -> Unit,
        progressPhase: String = "收藏页面",
    ): RemoteFavoriteSnapshot {
        val startedAt = SystemClock.elapsedRealtime()
        val snapshot = withContext(Dispatchers.IO) {
            val items = mutableListOf<FavoriteRemoteItem>()
            val folders = mutableMapOf<Int, String>()
            var page = 1
            var expectedTotal = 0
            var continuePaging = true
            while (continuePaging) {
                val favoritePage = remoteQuery.getFavorites(folderId, page, order)
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
                "duration=${SystemClock.elapsedRealtime() - startedAt}ms",
        )
        if (!includeAllFolderMemberships) return snapshot

        val folderMemberships = snapshot.folders.keys
            .filter { it > FAVORITE_SCOPE_ALL }
            .associateWith { nestedFolderId ->
                fetchRemoteFavoriteSnapshot(
                    folderId = nestedFolderId,
                    includeAllFolderMemberships = false,
                    order = order,
                    onProgress = onProgress,
                    progressPhase = "收藏夹 $nestedFolderId",
                ).items.map { it.albumId }
            }
        return snapshot.copy(folderMemberships = folderMemberships)
    }

    private suspend fun fetchFavoriteMetadata(
        ids: List<Int>,
        failFast: Boolean,
        onProgress: (FavoriteSyncProgress) -> Unit,
    ): MetadataFetchResult = coroutineScope {
        val distinctIds = ids.distinct()
        if (distinctIds.isEmpty()) return@coroutineScope MetadataFetchResult(emptyList(), 0)
        val startedAt = SystemClock.elapsedRealtime()
        val semaphore = Semaphore(FAVORITE_METADATA_CONCURRENCY)
        var completed = 0
        val results = distinctIds.map { albumId ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        Result.success(fetchFavoriteMetadata(albumId))
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
                "failures=$failures duration=${SystemClock.elapsedRealtime() - startedAt}ms",
        )
        if (failFast && failures > 0) {
            throw IllegalStateException("强制刷新收藏夹时有 $failures 部漫画元数据获取失败")
        }
        MetadataFetchResult(
            payloads = results.mapNotNull { it.getOrNull() },
            failures = failures,
        )
    }

    private suspend fun fetchFavoriteMetadata(albumId: Int): FavoriteMetadataPayload =
        remoteQuery.getMetadata(albumId)

    private companion object {
        const val FAVORITE_REMOTE_PAGE_SIZE = 20
        const val FAVORITE_METADATA_CONCURRENCY = 4
    }
}

/**
 * Application-scoped synchronization gate. The actual sync operation stays injectable so the
 * deduplication, account guard, cancellation, and per-account serialization can be tested without
 * constructing Room or a live API client.
 */
internal class FavoriteSyncCoordinator(
    private val applicationScope: CoroutineScope,
    private val isActiveAccount: (Int) -> Boolean,
    private val performSync: suspend (
        accountId: Int,
        folderId: Int,
        force: Boolean,
        order: CollectComicOrderFilter,
        onProgress: (FavoriteSyncProgress) -> Unit,
    ) -> NetWorkResult<FavoriteSyncReport>,
) {
    private data class FavoriteSyncKey(
        val accountId: Int,
        val folderId: Int,
        val force: Boolean,
        val order: CollectComicOrderFilter,
    )

    private val syncMutex = Mutex()
    private val accountLocks = mutableMapOf<Int, Mutex>()
    private val activeSyncs =
        mutableMapOf<FavoriteSyncKey, kotlinx.coroutines.Deferred<NetWorkResult<FavoriteSyncReport>>>()

    suspend fun synchronize(
        accountId: Int,
        folderId: Int,
        force: Boolean,
        order: CollectComicOrderFilter,
        onProgress: (FavoriteSyncProgress) -> Unit,
    ): NetWorkResult<FavoriteSyncReport> {
        if (accountId <= 0) return NetWorkResult.Error("未登录")
        if (!isActiveAccount(accountId)) return NetWorkResult.Error("登录账号已变化")
        val scopeFolderId = if (force) FAVORITE_SCOPE_ALL else folderId
        val key = FavoriteSyncKey(accountId, scopeFolderId, force, order)
        val deferred = syncMutex.withLock {
            val accountLock = accountLocks.getOrPut(accountId) { Mutex() }
            activeSyncs[key]?.takeIf { it.isActive }
                ?: applicationScope.async(start = CoroutineStart.LAZY) {
                    accountLock.withLock {
                        performSync(accountId, scopeFolderId, force, order, onProgress)
                    }
                }.also { created ->
                    activeSyncs[key] = created
                    created.invokeOnCompletion {
                        applicationScope.launch {
                            syncMutex.withLock {
                                if (activeSyncs[key] === created) activeSyncs.remove(key)
                            }
                        }
                    }
                    created.start()
                }
        }
        val result = deferred.await()
        return if (isActiveAccount(accountId)) result else NetWorkResult.Error("登录账号已变化")
    }
}
