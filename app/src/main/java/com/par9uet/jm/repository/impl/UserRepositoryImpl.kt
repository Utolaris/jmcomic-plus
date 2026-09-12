package com.par9uet.jm.repository.impl

import com.par9uet.jm.core.BaseRepository
import com.par9uet.jm.data.comic.mapper.toComicPage
import com.par9uet.jm.data.comic.mapper.toCommentPage
import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.network.AuthenticatedEmbeddedClient
import com.par9uet.jm.network.EmbeddedClientManager
import com.par9uet.jm.session.CandidateSession
import com.par9uet.jm.session.UserRepository
import com.par9uet.jm.core.model.SignInData
import com.par9uet.jm.core.network.AuthFailure
import com.par9uet.jm.retrofit.model.LoginResponse
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.core.network.map
import com.par9uet.jm.retrofit.model.UserHistoryComicListResponse
import com.par9uet.jm.retrofit.model.UserHistoryCommentListResponse
import io.github.jukomu.jmcomic.api.exception.NetworkException
import io.github.jukomu.jmcomic.api.model.ForumQuery
import io.github.jukomu.jmcomic.api.model.JmAlbumMeta
import io.github.jukomu.jmcomic.api.model.JmCategoryMeta
import io.github.jukomu.jmcomic.api.model.JmComment
import io.github.jukomu.jmcomic.api.model.JmDailyCheckInStatus
import io.github.jukomu.jmcomic.api.model.JmUserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class UserRepositoryImpl(
    private val embeddedClientManager: EmbeddedClientManager,
    private val authenticatedEmbeddedClient: AuthenticatedEmbeddedClient,
) : BaseRepository(), UserRepository {
    override suspend fun login(username: String, password: String): NetWorkResult<CandidateSession> =
        authenticateCandidate(username, password)

    override suspend fun verifyLogin(
        username: String,
        password: String,
    ): NetWorkResult<CandidateSession> = authenticateCandidate(username, password)

    private suspend fun authenticateCandidate(
        username: String,
        password: String,
    ): NetWorkResult<CandidateSession> {
        return withContext(Dispatchers.IO) {
            try {
                // Authentication always runs in an isolated client. Only the generation-checked
                // commit in UserManager promotes these cookies to the shared session.
                when (val result = embeddedClientManager.verifyCandidate(username, password)) {
                    is EmbeddedClientManager.EmbeddedLoginResult.Success -> {
                        NetWorkResult.Success(
                            CandidateSession(
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
    override fun activateVerifiedSession(verified: CandidateSession) {
        embeddedClientManager.activateCandidateSession(verified.embeddedCookies)
    }

    override fun clearSession() {
        embeddedClientManager.clearSession()
    }

    private fun EmbeddedClientManager.EmbeddedLoginResult.Failure.classifyAuthFailure(): AuthFailure {
        return when {
            // This is the API JSON code captured before JmApiResponse consumed the body.
            businessCode == 401 -> AuthFailure.InvalidCredentials
            // ResponseException.errorCode is the HTTP status in JMComic-Api-Java 1.1.8.
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

    override suspend fun getHistoryComicList(page: Int): NetWorkResult<ComicPage> {
        return safeEmbeddedCall("内置 API 获取历史漫画失败") {
            requireNotNull(
                authenticatedEmbeddedClient.withClient { client ->
                    val albumMetas = client.getWatchHistory(page)
                    UserHistoryComicListResponse(
                        list = albumMetas.map { it.toHistoryListItem() },
                    )
                }
            )
        }.map { it.toComicPage() }
    }

    override suspend fun deleteHistoryComic(id: Int): NetWorkResult<Unit> {
        return safeEmbeddedCall("删除历史记录失败") {
            authenticatedEmbeddedClient.withClient { client ->
                client.deleteWatchHistory(id.toString())
            }
            Unit
        }
    }

    override suspend fun getHistoryCommentList(
        page: Int,
        userId: Int
    ): NetWorkResult<CommentPage> {
        return safeEmbeddedCall("内置 API 获取评论历史失败") {
            requireNotNull(
                authenticatedEmbeddedClient.withClient { client ->
                    val query = ForumQuery.user(userId.toString())
                        .page(page)
                        .build()
                    val commentList = client.getComments(query)
                    UserHistoryCommentListResponse(
                        list = commentList.list.map { it.toHistoryCommentListItem() },
                        total = commentList.total
                    )
                }
            )
        }.map { it.toCommentPage() }
    }

    override suspend fun getSignData(userId: Int): NetWorkResult<SignInData> {
        return safeEmbeddedCall("内置 API 获取签到数据失败") {
            requireNotNull(
                authenticatedEmbeddedClient.withClient { client ->
                    val status = client.getDailyCheckInStatus(userId.toString())
                    status.toSignInData()
                }
            )
        }
    }

    override suspend fun signIn(userId: Int, dailyId: Int): NetWorkResult<ActionResult> {
        return safeEmbeddedCall("内置 API 签到失败") {
            authenticatedEmbeddedClient.withClient { client ->
                client.doDailyCheckin(userId.toString(), dailyId.toString())
            }
            ActionResult(isSuccess = true, message = "签到成功")
        }
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
            spoiler = spoiler().toString(),
            replys = replys()?.map { it.toHistoryCommentListItem() }
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

    /**
     * 内置 API 的签到状态直接映射成 `core.model.SignInData`。
     * 不再绕一层 wire 的 `SignInDataResponse`（已删除）：此前经它再 `toSignData()`
     * 会让映射规则分两处维护。
     */
    private fun JmDailyCheckInStatus.toSignInData(): SignInData {
        return SignInData(
            dailyId = dailyId,
            threeDaysCoin = threeDaysCoin.toIntOrNull() ?: 0,
            threeDaysExp = threeDaysExp.toIntOrNull() ?: 0,
            sevenDaysCoin = sevenDaysCoin.toIntOrNull() ?: 0,
            sevenDaysExp = sevenDaysExp.toIntOrNull() ?: 0,
            eventName = eventName,
            currentProgress = currentProgress,
            dateMap = record.flatten()
                .map { item ->
                    SignInData.SignInDataDateMapValue(
                        isSign = item.signed ?: false,
                        hasExtraBonus = item.bonus,
                    )
                }
                .foldIndexed(mutableMapOf<Int, SignInData.SignInDataDateMapValue>()) { index, acc, item ->
                    acc[index + 1] = item
                    acc
                },
        )
    }
}
