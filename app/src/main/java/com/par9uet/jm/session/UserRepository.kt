package com.par9uet.jm.session
import com.par9uet.jm.core.model.SignInData
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.retrofit.model.LoginResponse
import okhttp3.Cookie

/** Isolated authenticated session that can be promoted after the caller validates its generation. */
data class CandidateSession(
    val loginResponse: LoginResponse,
    val embeddedCookies: List<Cookie> = emptyList(),
)

/**
 * 会话仓库。
 *
 * 对外方法只返回领域类型（`SignInData` / `ComicPage` / `CommentPage` / `ActionResult`），
 * 登录凭据 `LoginResponse` 只在本包内传递，不进表现层。
 */
interface UserRepository {
    suspend fun login(username: String, password: String): NetWorkResult<CandidateSession>

    /**
     * 验证凭据但不改变活动会话，返回候选认证结果（含 cookie 快照）。
     */
    suspend fun verifyLogin(username: String, password: String): NetWorkResult<CandidateSession> =
        login(username, password)

    /**
     * 把已验证的候选会话提升为活动会话（持久化 cookie 并同步活动客户端）。
     * 调用方必须确认 session generation 仍然有效。
     */
    fun activateVerifiedSession(verified: CandidateSession)

    /** Clears client-side session state without performing a network logout request. */
    fun clearSession()

    suspend fun getHistoryComicList(page: Int = 1): NetWorkResult<ComicPage>
    suspend fun deleteHistoryComic(id: Int): NetWorkResult<Unit>
    suspend fun getHistoryCommentList(
        page: Int = 1,
        userId: Int
    ): NetWorkResult<CommentPage>

    suspend fun getSignData(
        userId: Int,
    ): NetWorkResult<SignInData>

    suspend fun signIn(
        userId: Int,
        dailyId: Int,
    ): NetWorkResult<ActionResult>
}
