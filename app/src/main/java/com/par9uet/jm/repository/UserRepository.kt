package com.par9uet.jm.repository

import com.par9uet.jm.data.models.CollectComicOrderFilter
import com.par9uet.jm.retrofit.model.LoginResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.SignInDataResponse
import com.par9uet.jm.retrofit.model.SignInResponse
import com.par9uet.jm.retrofit.model.UserCollectComicListResponse
import com.par9uet.jm.retrofit.model.UserHistoryComicListResponse
import com.par9uet.jm.retrofit.model.UserHistoryCommentListResponse
import okhttp3.Cookie

/**
 * 一次登录的结果：登录响应 + 登录完成后活动会话的完整 cookie 状态。
 * cookie 由实现方在登录网络调用结束后读取（内置 API 为 JmApiClient.getCookies()，
 * 含 AVS），由 UserManager 在会话锁内、generation 确认后提交到 CookieStorage。
 */
data class LoginSession(
    val loginResponse: LoginResponse,
    val embeddedCookies: List<Cookie> = emptyList(),
)

/**
 * 候选会话验证的结果：凭据被证明有效，并携带候选会话的 cookie 快照，供
 * 会话管理层在 generation 确认后提升为活动会话。提升前不影响任何活动客户端。
 */
data class VerifiedCredentials(
    val loginResponse: LoginResponse,
    /** 内置 API 候选客户端 CookieJar 的完整快照（含 AVS）。 */
    val embeddedCookies: List<Cookie> = emptyList(),
    /** 网络 API 候选登录响应 Set-Cookie 捕获的会话 cookie。 */
    val networkCookies: List<Cookie> = emptyList(),
)

interface UserRepository {
    suspend fun login(username: String, password: String): NetWorkResult<LoginSession>

    /**
     * 验证凭据但不改变活动会话，返回候选认证结果（含 cookie 快照）。
     */
    suspend fun verifyLogin(username: String, password: String): NetWorkResult<VerifiedCredentials> =
        when (val result = login(username, password)) {
            is NetWorkResult.Success -> NetWorkResult.Success(
                VerifiedCredentials(
                    loginResponse = result.data.loginResponse,
                    embeddedCookies = result.data.embeddedCookies,
                )
            )

            is NetWorkResult.Error -> result
        }

    /**
     * 把已验证的候选会话提升为活动会话（持久化 cookie 并同步活动客户端）。
     * 调用方必须确认 session generation 仍然有效。
     */
    fun activateVerifiedSession(verified: VerifiedCredentials) = Unit

    /** Clears client-side session state without performing a network logout request. */
    fun clearSession() = Unit

    suspend fun getCollectComicList(
        page: Int = 1,
        order: CollectComicOrderFilter = CollectComicOrderFilter.COLLECT_TIME,
        folderId: Int = 0
    ): NetWorkResult<UserCollectComicListResponse>

    suspend fun getHistoryComicList(page: Int = 1): NetWorkResult<UserHistoryComicListResponse>
    suspend fun deleteHistoryComic(id: Int): NetWorkResult<Unit>
    suspend fun getHistoryCommentList(
        page: Int = 1,
        userId: Int
    ): NetWorkResult<UserHistoryCommentListResponse>

    suspend fun getSignData(
        userId: Int,
    ): NetWorkResult<SignInDataResponse>

    suspend fun signIn(
        userId: Int,
        dailyId: Int,
    ): NetWorkResult<SignInResponse>
}
