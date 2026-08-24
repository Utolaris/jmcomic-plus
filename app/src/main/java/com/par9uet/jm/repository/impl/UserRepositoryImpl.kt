package com.par9uet.jm.repository.impl

import com.par9uet.jm.data.models.CollectComicOrderFilter
import com.par9uet.jm.repository.LoginSession
import com.par9uet.jm.repository.UserRepository
import com.par9uet.jm.repository.VerifiedCredentials
import com.par9uet.jm.store.SessionReadinessHolder
import com.par9uet.jm.store.awaitReady
import com.par9uet.jm.utils.logError
import com.par9uet.jm.retrofit.model.AuthFailure
import com.par9uet.jm.retrofit.model.LoginResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.SignInDataResponse
import com.par9uet.jm.retrofit.model.SignInResponse
import com.par9uet.jm.retrofit.model.UserCollectComicListResponse
import com.par9uet.jm.retrofit.model.UserHistoryComicListResponse
import com.par9uet.jm.retrofit.model.UserHistoryCommentListResponse
import com.par9uet.jm.store.FavoriteStore
import com.par9uet.jm.store.FavoriteSyncProgress
import com.par9uet.jm.store.FavoriteSyncReport
import io.github.jukomu.jmcomic.api.exception.NetworkException
import io.github.jukomu.jmcomic.api.model.ForumQuery
import io.github.jukomu.jmcomic.api.model.FavoriteQuery
import io.github.jukomu.jmcomic.api.model.JmAlbumMeta
import io.github.jukomu.jmcomic.api.model.JmCategoryMeta
import io.github.jukomu.jmcomic.api.model.JmComment
import io.github.jukomu.jmcomic.api.model.JmDailyCheckInStatus
import io.github.jukomu.jmcomic.api.model.JmUserInfo
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UserRepositoryImpl(
    private val embeddedClientManager: EmbeddedClientManager,
    private val sessionReadinessHolder: SessionReadinessHolder,
    private val favoriteStore: FavoriteStore,
    private val favoriteSyncService: FavoriteSyncService,
) : UserRepository {
    override suspend fun login(username: String, password: String): NetWorkResult<LoginSession> {
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

    override suspend fun verifyLogin(username: String, password: String): NetWorkResult<VerifiedCredentials> {
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

    /**
     * 把已验证的候选会话提升为活动会话。调用方（UserManager）已确认 generation 有效。
     */
    override fun activateVerifiedSession(verified: VerifiedCredentials) {
        embeddedClientManager.activateCandidateSession(verified.embeddedCookies)
    }

    override fun clearSession() {
        embeddedClientManager.clearSession()
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

    override suspend fun getCollectComicList(
        page: Int,
        order: CollectComicOrderFilter,
        folderId: Int
    ): NetWorkResult<UserCollectComicListResponse> {
        return loadCollectComicList(page, order, folderId)
    }

    private suspend fun loadCollectComicList(
        page: Int,
        @Suppress("UNUSED_PARAMETER") order: CollectComicOrderFilter,
        folderId: Int,
    ): NetWorkResult<UserCollectComicListResponse> {
        awaitAuthenticatedSessionReady()
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

    override suspend fun synchronizeFavorites(
        accountId: Int,
        folderId: Int,
        force: Boolean,
        order: CollectComicOrderFilter,
        onProgress: (FavoriteSyncProgress) -> Unit,
    ): NetWorkResult<FavoriteSyncReport> = favoriteSyncService.synchronize(
        accountId = accountId,
        folderId = folderId,
        force = force,
        order = order,
        onProgress = onProgress,
    )

    override suspend fun getCachedFavoriteFolders(accountId: Int): Map<String, String> =
        favoriteStore.getCachedFolders(accountId)

    override suspend fun cacheFavoriteComic(
        accountId: Int,
        comic: com.par9uet.jm.data.models.Comic,
        folderId: Int,
    ) {
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

    override suspend fun getHistoryComicList(page: Int): NetWorkResult<UserHistoryComicListResponse> {
        awaitAuthenticatedSessionReady()
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

    override suspend fun getSignData(userId: Int): NetWorkResult<SignInDataResponse> {
        awaitAuthenticatedSessionReady()
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

    override suspend fun signIn(userId: Int, dailyId: Int): NetWorkResult<SignInResponse> {
        awaitAuthenticatedSessionReady()
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

    /**
     * 启动阶段的后台会话恢复尚未完成时，认证类请求做有界等待（默认 2 秒），
     * 避免用未恢复/未验证的会话发出注定 401 的请求。公开接口不调用此方法。
     * 若已有持久化会话（恢复是瞬时的），直接放行，不等后台验证。
     */
    private suspend fun awaitAuthenticatedSessionReady() {
        if (embeddedClientManager.hasPersistedSession()) return
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
