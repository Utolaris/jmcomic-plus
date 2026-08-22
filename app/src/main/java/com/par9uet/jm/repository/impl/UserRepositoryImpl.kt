package com.par9uet.jm.repository.impl

import android.os.SystemClock
import com.par9uet.jm.data.models.CollectComicOrderFilter
import com.par9uet.jm.data.models.COMIC_API_SOURCE_BUILTIN
import com.par9uet.jm.data.models.COMIC_API_SOURCE_MIXED
import com.par9uet.jm.repository.BaseRepository
import com.par9uet.jm.repository.LoginSession
import com.par9uet.jm.repository.UserRepository
import com.par9uet.jm.repository.VerifiedCredentials
import com.par9uet.jm.store.SessionReadinessHolder
import com.par9uet.jm.store.awaitReady
import com.par9uet.jm.utils.logError
import com.par9uet.jm.retrofit.Retrofit
import com.par9uet.jm.retrofit.CapturingCookieJar
import com.par9uet.jm.retrofit.model.AuthFailure
import com.par9uet.jm.retrofit.model.LoginResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.SignInDataResponse
import com.par9uet.jm.retrofit.model.SignInResponse
import com.par9uet.jm.retrofit.model.UserCollectComicListResponse
import com.par9uet.jm.retrofit.model.UserHistoryComicListResponse
import com.par9uet.jm.retrofit.model.UserHistoryCommentListResponse
import com.par9uet.jm.retrofit.service.ComicService
import com.par9uet.jm.retrofit.service.UserService
import com.par9uet.jm.store.FavoriteMetadataPayload
import com.par9uet.jm.store.FavoriteRemoteItem
import com.par9uet.jm.store.FavoriteStore
import com.par9uet.jm.store.FavoriteSyncProgress
import com.par9uet.jm.store.FavoriteSyncReport
import com.par9uet.jm.store.FAVORITE_SCOPE_ALL
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.utils.log
import com.par9uet.jm.storage.UserStorage
import io.github.jukomu.jmcomic.api.exception.NetworkException
import io.github.jukomu.jmcomic.api.model.ForumQuery
import io.github.jukomu.jmcomic.api.model.FavoriteQuery
import io.github.jukomu.jmcomic.api.model.JmAlbum
import io.github.jukomu.jmcomic.api.model.JmAlbumMeta
import io.github.jukomu.jmcomic.api.model.JmCategoryMeta
import io.github.jukomu.jmcomic.api.model.JmComment
import io.github.jukomu.jmcomic.api.model.JmCommentList
import io.github.jukomu.jmcomic.api.model.JmDailyCheckInStatus
import io.github.jukomu.jmcomic.api.model.JmUserInfo
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UserRepositoryImpl(
    private val service: UserService,
    private val localSettingManager: LocalSettingManager,
    private val embeddedClientManager: EmbeddedClientManager,
    private val retrofit: Retrofit,
    private val sessionReadinessHolder: SessionReadinessHolder,
    private val userStorage: UserStorage,
    private val comicService: ComicService,
    private val favoriteStore: FavoriteStore,
    private val applicationScope: CoroutineScope,
) : BaseRepository(), UserRepository {
    private data class FavoriteSyncKey(
        val accountId: Int,
        val folderId: Int,
        val force: Boolean,
        val order: CollectComicOrderFilter,
    )
    private data class RemoteFavoriteSnapshot(
        val items: List<FavoriteRemoteItem>,
        val folders: Map<Int, String>,
        val folderMemberships: Map<Int, List<Int>> = emptyMap(),
    )

    private val favoriteSyncMutex = Mutex()
    private val favoriteAccountLocks = mutableMapOf<Int, Mutex>()
    private val activeFavoriteSyncs = mutableMapOf<FavoriteSyncKey, kotlinx.coroutines.Deferred<NetWorkResult<FavoriteSyncReport>>>()

    override suspend fun login(username: String, password: String): NetWorkResult<LoginSession> {
        if (useEmbeddedApi()) {
            return withContext(Dispatchers.IO) {
                try {
                    when (val result = embeddedClientManager.loginActive(username, password)) {
                        is EmbeddedClientManager.EmbeddedLoginResult.Success -> {
                            NetWorkResult.Success(
                                LoginSession(
                                    loginResponse = result.userInfo.toLoginResponse(),
                                    embeddedCookies = result.sessionCookies,
                                )
                            )
                        }

                        is EmbeddedClientManager.EmbeddedLoginResult.Failure -> {
                            val exception = result.exception
                            NetWorkResult.Error(
                                message = "内置API登录失败：" + (exception.message ?: "未知错误"),
                                code = result.businessCode ?: exception.errorCode,
                                authFailure = result.classifyAuthFailure()
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    NetWorkResult.Error(
                        message = "内置API登录失败：" + (e.message ?: "未知错误"),
                        authFailure = e.classifyAuthFailure()
                    )
                }
            }
        }
        return safeApiCall { service.login(username, password) }
            .asAuthResult()
            .mapLogin { LoginSession(loginResponse = it) }
    }

    override suspend fun verifyLogin(username: String, password: String): NetWorkResult<VerifiedCredentials> {
        if (useEmbeddedApi()) {
            return withContext(Dispatchers.IO) {
                try {
                    when (val result = embeddedClientManager.verifyCandidate(username, password)) {
                        is EmbeddedClientManager.EmbeddedLoginResult.Success -> {
                            NetWorkResult.Success(
                                VerifiedCredentials(
                                    loginResponse = result.userInfo.toLoginResponse(),
                                    embeddedCookies = result.sessionCookies,
                                )
                            )
                        }

                        is EmbeddedClientManager.EmbeddedLoginResult.Failure -> {
                            val exception = result.exception
                            NetWorkResult.Error(
                                message = "内置API登录失败：" + (exception.message ?: "未知错误"),
                                code = result.businessCode ?: exception.errorCode,
                                authFailure = result.classifyAuthFailure()
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    NetWorkResult.Error(
                        message = "内置API登录失败：" + (e.message ?: "未知错误"),
                        authFailure = e.classifyAuthFailure()
                    )
                }
            }
        }
        val captured = CapturingCookieJar()
        val loginService = retrofit.createCapturingService(UserService::class.java, captured)
        return safeApiCall { loginService.login(username, password) }
            .asAuthResult()
            .mapLogin {
                VerifiedCredentials(
                    loginResponse = it,
                    networkCookies = captured.capturedCookies,
                )
            }
    }

    /**
     * 把已验证的候选会话提升为活动会话。调用方（UserManager）已确认 generation 有效。
     */
    override fun activateVerifiedSession(verified: VerifiedCredentials) {
        if (useEmbeddedApi()) {
            embeddedClientManager.activateCandidateSession(verified.embeddedCookies)
        } else {
            retrofit.promoteCapturedCookies(verified.networkCookies)
        }
    }

    override fun clearSession() {
        embeddedClientManager.clearSession()
    }

    private fun <T> NetWorkResult<LoginResponse>.mapLogin(transform: (LoginResponse) -> T): NetWorkResult<T> {
        return when (this) {
            is NetWorkResult.Success -> NetWorkResult.Success(transform(data))
            is NetWorkResult.Error -> this
        }
    }

    private fun EmbeddedClientManager.EmbeddedLoginResult.Failure.classifyAuthFailure(): AuthFailure {
        return when {
            // This is the API JSON code captured before JmApiResponse consumed the body.
            businessCode == 401 -> AuthFailure.InvalidCredentials
            // ResponseException.errorCode is the HTTP status in JMComic-Api-Java 1.1.6.
            exception.errorCode == 401 -> AuthFailure.InvalidCredentials
            exception.errorCode in 500..599 -> AuthFailure.TemporaryFailure
            exception.cause is NetworkException || exception.cause is IOException -> AuthFailure.TemporaryFailure
            else -> AuthFailure.Unknown
        }
    }

    private fun Exception.classifyAuthFailure(): AuthFailure {
        return when {
            this is NetworkException || cause is NetworkException -> AuthFailure.TemporaryFailure
            this is SocketTimeoutException || this is ConnectException || this is UnknownHostException -> AuthFailure.TemporaryFailure
            this is IOException || cause is IOException -> AuthFailure.TemporaryFailure
            cause is SocketTimeoutException || cause is ConnectException || cause is UnknownHostException -> AuthFailure.TemporaryFailure
            else -> AuthFailure.Unknown
        }
    }

    private fun NetWorkResult<LoginResponse>.asAuthResult(): NetWorkResult<LoginResponse> {
        if (this !is NetWorkResult.Error) return this
        return copy(
            authFailure = authFailure ?: when {
                code == 401 -> AuthFailure.InvalidCredentials
                code in 500..599 -> AuthFailure.TemporaryFailure
                code == -1 && message in setOf("网络连接超时", "网络连接失败", "网络不可用") -> AuthFailure.TemporaryFailure
                else -> AuthFailure.Unknown
            }
        )
    }

    override suspend fun getCollectComicList(
        page: Int,
        order: CollectComicOrderFilter,
        folderId: Int
    ): NetWorkResult<UserCollectComicListResponse> {
        return loadCollectComicList(page, order, folderId)
    }

    private suspend fun loadCollectComicList(
        page: Int,
        order: CollectComicOrderFilter,
        folderId: Int,
    ): NetWorkResult<UserCollectComicListResponse> {
        awaitAuthenticatedSessionReady()
        if (useEmbeddedApi()) {
            return withContext(Dispatchers.IO) {
                try {
                    val client = embeddedClientManager.getClient()
                    val query = FavoriteQuery.Builder()
                        .folderId(folderId)
                        .page(page)
                        .build()
                    val favPage = client.getFavorites(query)
                    val metas = favPage.content().orEmpty()
                    NetWorkResult.Success(
                        UserCollectComicListResponse(
                            count = favPage.totalItems(),
                            folder_list = favPage.folderList(),
                            list = metas.map { it.toListItem() },
                            total = favPage.totalItems()
                        )
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    NetWorkResult.Error("内置API获取收藏列表失败：${e.message ?: "未知错误"}")
                }
            }
        }
        return safeApiCall {
            service.getCollectComicList(page, order.value, folderId)
        }
    }

    override suspend fun synchronizeFavorites(
        accountId: Int,
        folderId: Int,
        force: Boolean,
        order: CollectComicOrderFilter,
        onProgress: (FavoriteSyncProgress) -> Unit,
    ): NetWorkResult<FavoriteSyncReport> {
        if (accountId <= 0) return NetWorkResult.Error("未登录")
        if (!isActiveFavoriteAccount(accountId)) return NetWorkResult.Error("登录账号已变化")
        val scopeFolderId = if (force) 0 else folderId
        val key = FavoriteSyncKey(accountId, scopeFolderId, force, order)
        val deferred = favoriteSyncMutex.withLock {
            val accountLock = favoriteAccountLocks.getOrPut(accountId) { Mutex() }
            activeFavoriteSyncs[key]?.takeIf { it.isActive }
                ?: applicationScope.async(start = CoroutineStart.LAZY) {
                    accountLock.withLock {
                        performFavoriteSync(
                            accountId = accountId,
                            folderId = scopeFolderId,
                            force = force,
                            order = order,
                            onProgress = onProgress,
                        )
                    }
                }.also { created ->
                    activeFavoriteSyncs[key] = created
                    created.invokeOnCompletion {
                        applicationScope.launch {
                            favoriteSyncMutex.withLock {
                                if (activeFavoriteSyncs[key] === created) {
                                    activeFavoriteSyncs.remove(key)
                                }
                            }
                        }
                    }
                    created.start()
                }
        }
        return deferred.await()
    }

    override suspend fun getCachedFavoriteFolders(accountId: Int): Map<String, String> =
        favoriteStore.getCachedFolders(accountId)

    override suspend fun cacheFavoriteComic(accountId: Int, comic: com.par9uet.jm.data.models.Comic, folderId: Int) {
        if (accountId > 0) favoriteStore.addFromComic(accountId, comic, folderId)
    }

    override suspend fun removeCachedFavoriteComic(accountId: Int, albumId: Int) {
        if (accountId > 0) favoriteStore.remove(accountId, listOf(albumId))
    }

    override suspend fun moveCachedFavoriteComic(accountId: Int, albumId: Int, folderId: Int) {
        if (accountId > 0) favoriteStore.moveToFolder(accountId, albumId, folderId)
    }

    override suspend fun cacheFavoriteFolder(accountId: Int, folderId: Int, name: String) {
        favoriteStore.cacheFolder(accountId, folderId, name)
    }

    override suspend fun removeCachedFavoriteFolder(accountId: Int, folderId: Int) {
        favoriteStore.removeFolder(accountId, folderId)
    }

    override suspend fun renameCachedFavoriteFolder(accountId: Int, folderId: Int, name: String) {
        favoriteStore.renameFolder(accountId, folderId, name)
    }

    private suspend fun performFavoriteSync(
        accountId: Int,
        folderId: Int,
        force: Boolean,
        order: CollectComicOrderFilter,
        onProgress: (FavoriteSyncProgress) -> Unit,
    ): NetWorkResult<FavoriteSyncReport> {
        val startedAt = SystemClock.elapsedRealtime()
        log(
            "FavoritesSync",
            "start account=$accountId folder=$folderId force=$force order=$order",
        )
        return try {
            awaitAuthenticatedSessionReady()
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
                favoriteStore.replaceAllSnapshot(
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
                val delta = favoriteStore.reconcileLightweightSnapshot(
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
                    favoriteStore.applyMetadata(accountId, payload, syncedAt)
                }
                if (metadata.failures > 0) {
                    log(
                        "FavoritesSync",
                        "metadata failures=${metadata.failures}; keeping previous complete metadata where available",
                    )
                }
                favoriteStore.markSyncSuccess(accountId, folderId, syncedAt)
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
                if (useEmbeddedApi()) {
                    val favoritePage = embeddedClientManager.getClient().getFavorites(
                        FavoriteQuery.Builder().folderId(folderId).page(page).build()
                    )
                    val pageItems = favoritePage.content().orEmpty().mapNotNull { it.toFavoriteRemoteItem() }
                    items += pageItems
                    favoritePage.folderList().orEmpty().forEach { (id, name) ->
                        id.toIntOrNull()?.let { folders[it] = name }
                    }
                    expectedTotal = favoritePage.totalItems()
                    onProgress(FavoriteSyncProgress(items.size, expectedTotal, progressPhase))
                    continuePaging = when {
                        favoritePage.totalPages() > 0 -> page < favoritePage.totalPages()
                        pageItems.isEmpty() -> false
                        else -> pageItems.size >= FAVORITE_REMOTE_PAGE_SIZE
                    }
                } else {
                    val response = safeApiCall {
                        service.getCollectComicList(page, order.value, folderId)
                    }
                    val pageData = when (response) {
                        is NetWorkResult.Error -> throw IllegalStateException(response.message)
                        is NetWorkResult.Success -> response.data
                    }
                    val pageItems = pageData.list.mapNotNull { it.toFavoriteRemoteItem() }
                    items += pageItems
                    pageData.folder_list.orEmpty().forEach { (id, name) ->
                        id.toIntOrNull()?.let { folders[it] = name }
                    }
                    expectedTotal = pageData.total
                    onProgress(FavoriteSyncProgress(items.size, expectedTotal, progressPhase))
                    continuePaging = pageItems.isNotEmpty() &&
                        (pageItems.size >= FAVORITE_REMOTE_PAGE_SIZE || expectedTotal > items.size)
                }
                page++
            }
            RemoteFavoriteSnapshot(
                items = items.distinctBy { it.albumId },
                folders = folders.apply { putIfAbsent(0, "全部") },
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

    private data class MetadataFetchResult(
        val payloads: List<FavoriteMetadataPayload>,
        val failures: Int,
    )

    private suspend fun fetchFavoriteMetadata(
        ids: List<Int>,
        failFast: Boolean,
        onProgress: (FavoriteSyncProgress) -> Unit,
    ): MetadataFetchResult {
        val startedAt = SystemClock.elapsedRealtime()
        return coroutineScope {
            if (ids.isEmpty()) return@coroutineScope MetadataFetchResult(emptyList(), 0)
            val semaphore = Semaphore(FAVORITE_METADATA_CONCURRENCY)
            var completed = 0
            val results = ids.distinct().map { albumId ->
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
                        synchronized(ids) {
                            completed++
                            onProgress(FavoriteSyncProgress(completed, ids.size, "漫画元数据"))
                        }
                    }
                }
            }.awaitAll()
            val failures = results.count { it.isFailure }
            log(
                "FavoritesSync",
                "metadata enrichment requested=${ids.distinct().size} success=${results.size - failures} " +
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
    }

    private suspend fun fetchFavoriteMetadata(albumId: Int): FavoriteMetadataPayload {
        if (useEmbeddedApi()) {
            return embeddedClientManager.getClient().getAlbum(albumId.toString()).toFavoriteMetadataPayload()
        }
        return when (val result = safeApiCall { comicService.getComicDetail(albumId) }) {
            is NetWorkResult.Error -> throw IllegalStateException(result.message)
            is NetWorkResult.Success -> result.data.let {
                FavoriteMetadataPayload(
                    albumId = it.id,
                    title = it.name,
                    description = it.description,
                    authors = it.author,
                    tags = it.tags,
                    roles = it.actors,
                    works = it.works,
                )
            }
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

    private fun UserCollectComicListResponse.ListItem.toFavoriteRemoteItem(): FavoriteRemoteItem? {
        val albumId = id.toIntOrNull() ?: return null
        return FavoriteRemoteItem(
            albumId = albumId,
            title = name,
            authors = authors?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                ?: listOf(author).filter { it.isNotBlank() },
            description = description.orEmpty(),
            image = image,
            tags = tags.orEmpty(),
            categoryId = category.id,
            categoryTitle = category.title,
            subCategoryId = category_sub.id,
            subCategoryTitle = category_sub.title,
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

    private companion object {
        const val FAVORITE_REMOTE_PAGE_SIZE = 20
        const val FAVORITE_METADATA_CONCURRENCY = 4
    }

    override suspend fun getHistoryComicList(page: Int): NetWorkResult<UserHistoryComicListResponse> {
        awaitAuthenticatedSessionReady()
        if (useEmbeddedApi()) {
            return withContext(Dispatchers.IO) {
                try {
                    NetWorkResult.Success(withEmbeddedClient { client ->
                        val albumMetas = client.getWatchHistory(page)
                        UserHistoryComicListResponse(
                            list = albumMetas.map { it.toHistoryListItem() },
                            total = albumMetas.size
                        )
                    })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    NetWorkResult.Error("内置API获取历史漫画失败：${e.message ?: "未知错误"}")
                }
            }
        }
        return safeApiCall {
            service.getHistoryComicList(page)
        }
    }

    override suspend fun deleteHistoryComic(id: Int): NetWorkResult<Unit> {
        awaitAuthenticatedSessionReady()
        return withContext(Dispatchers.IO) {
            try {
                withEmbeddedClient { client ->
                    client.deleteWatchHistory(id.toString())
                }
                NetWorkResult.Success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logError("UserRepositoryImpl", "删除历史记录 id=$id 失败: ${e.message}")
                NetWorkResult.Error("删除历史记录失败：${e.message ?: "未知错误"}")
            }
        }
    }

    override suspend fun getHistoryCommentList(
        page: Int,
        userId: Int
    ): NetWorkResult<UserHistoryCommentListResponse> {
        awaitAuthenticatedSessionReady()
        if (useEmbeddedApi()) {
            return withContext(Dispatchers.IO) {
                try {
                    NetWorkResult.Success(withEmbeddedClient { client ->
                        val query = ForumQuery.user(userId.toString())
                            .page(page)
                            .build()
                        val commentList = client.getComments(query)
                        UserHistoryCommentListResponse(
                            list = commentList.list.map { it.toHistoryCommentListItem() },
                            total = commentList.total
                        )
                    })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    NetWorkResult.Error("内置API获取评论历史失败：${e.message ?: "未知错误"}")
                }
            }
        }
        return safeApiCall {
            service.getCommentList(page, userId)
        }
    }

    override suspend fun getSignData(userId: Int): NetWorkResult<SignInDataResponse> {
        awaitAuthenticatedSessionReady()
        if (useEmbeddedApi()) {
            return withContext(Dispatchers.IO) {
                try {
                    NetWorkResult.Success(withEmbeddedClient { client ->
                        val status = client.getDailyCheckInStatus(userId.toString())
                        status.toSignInDataResponse()
                    })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    NetWorkResult.Error("内置API获取签到数据失败：${e.message ?: "未知错误"}")
                }
            }
        }
        return safeApiCall {
            service.getSignInData(userId)
        }
    }

    override suspend fun signIn(userId: Int, dailyId: Int): NetWorkResult<SignInResponse> {
        awaitAuthenticatedSessionReady()
        if (useEmbeddedApi()) {
            return withContext(Dispatchers.IO) {
                try {
                    withEmbeddedClient { client ->
                        client.doDailyCheckin(userId.toString(), dailyId.toString())
                    }
                    NetWorkResult.Success(SignInResponse(msg = "签到成功"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    NetWorkResult.Error("内置API签到失败：${e.message ?: "未知错误"}")
                }
            }
        }
        return safeApiCall {
            service.signIn(userId, dailyId)
        }
    }

    private fun useEmbeddedApi(): Boolean {
        val source = localSettingManager.localSettingState.value.comicApiSource
        return source == COMIC_API_SOURCE_BUILTIN || source == COMIC_API_SOURCE_MIXED
    }

    /**
     * 启动阶段的后台会话恢复尚未完成时，认证类请求做有界等待（默认 2 秒），
     * 避免用未恢复/未验证的会话发出注定 401 的请求。公开接口不调用此方法。
     * 若已有持久化会话（恢复是瞬时的），直接放行，不等后台验证。
     */
    private suspend fun awaitAuthenticatedSessionReady() {
        val hasInstantSession = if (useEmbeddedApi()) {
            embeddedClientManager.hasPersistedSession()
        } else {
            retrofit.hasPersistedSession()
        }
        if (hasInstantSession) return
        sessionReadinessHolder.awaitReady()
    }

    private fun <T> withEmbeddedClient(block: (JmApiClient) -> T): T {
        return block(embeddedClientManager.getClient())
    }

    private fun JmComment.toHistoryCommentListItem(): UserHistoryCommentListResponse.ListItem {
        return UserHistoryCommentListResponse.ListItem(
            AID = aid(),
            BID = bid(),
            CID = commentId(),
            UID = userId(),
            username = username(),
            nickname = nickname(),
            likes = likes().toString(),
            gender = gender(),
            update_at = updateAt(),
            addtime = postDate(),
            parent_CID = parentCommentId(),
            name = name(),
            content = content(),
            photo = photo() ?: "",
            spoiler = spoiler(),
            replys = replys()?.map { it.toHistoryCommentListItem() }
        )
    }

    private fun JmAlbumMeta.toListItem(
    ): UserCollectComicListResponse.ListItem {
        val resolvedAuthors = authors().orEmpty()
        return UserCollectComicListResponse.ListItem(
            id = id().orEmpty(),
            author = resolvedAuthors.firstOrNull().orEmpty(),
            description = description(),
            name = title().orEmpty(),
            image = image().orEmpty(),
            category = category().toCollectCategory(),
            category_sub = subCategory().toCollectCategory(),
            tags = tags().orEmpty().takeIf { it.isNotEmpty() },
            authors = resolvedAuthors.takeIf { it.isNotEmpty() },
        )
    }

    private fun JmAlbumMeta.toHistoryListItem(): UserHistoryComicListResponse.ListItem {
        return UserHistoryComicListResponse.ListItem(
            id = id().orEmpty(),
            author = authors().orEmpty().firstOrNull().orEmpty(),
            description = description(),
            name = title().orEmpty(),
            image = image().orEmpty(),
            category = category().toHistoryCategory(),
            category_sub = subCategory().toHistoryCategory()
        )
    }

    private fun JmCategoryMeta?.toHistoryCategory(): UserHistoryComicListResponse.ListItem.Category {
        return UserHistoryComicListResponse.ListItem.Category(
            id = this?.id(),
            title = this?.title()
        )
    }

    private fun JmCategoryMeta?.toCollectCategory(): UserCollectComicListResponse.ListItem.Category {
        return UserCollectComicListResponse.ListItem.Category(
            id = this?.id(),
            title = this?.title()
        )
    }

    private fun JmUserInfo.toLoginResponse(): LoginResponse {
        return LoginResponse(
            uid = uid.toIntOrNull() ?: 0,
            username = username,
            email = email,
            photo = avatarUrl,
            coin = coin.toString(),
            album_favorites = albumFavorites,
            level_name = levelName,
            level = level,
            nextLevelExp = nextLevelExp.toInt(),
            exp = currentExp.toInt(),
            expPercent = expPercent,
            album_favorites_max = maxAlbumFavorites,
        )
    }

    private fun JmDailyCheckInStatus.toSignInDataResponse(): SignInDataResponse {
        return SignInDataResponse(
            daily_id = dailyId,
            three_days_coin = threeDaysCoin,
            three_days_exp = threeDaysExp,
            seven_days_coin = sevenDaysCoin,
            seven_days_exp = sevenDaysExp,
            event_name = eventName,
            background_pc = backgroundPc,
            background_phone = backgroundPhone,
            currentProgress = currentProgress,
            record = record.map { week ->
                week.map { item ->
                    SignInDataResponse.RecordItem(
                        date = item.date,
                        signed = item.signed ?: false,
                        bonus = item.bonus,
                    )
                }
            }
        )
    }
}
