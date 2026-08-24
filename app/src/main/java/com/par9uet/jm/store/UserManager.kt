package com.par9uet.jm.store

import com.par9uet.jm.data.models.User
import com.par9uet.jm.repository.LoginSession
import com.par9uet.jm.repository.UserRepository
import com.par9uet.jm.repository.VerifiedCredentials
import com.par9uet.jm.retrofit.ActiveSessionCookieStore
import com.par9uet.jm.retrofit.model.AuthFailure
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.SignInDataResponse
import com.par9uet.jm.storage.CookieStorage
import com.par9uet.jm.storage.UserStorage
import com.par9uet.jm.ui.models.CommonUIState
import com.par9uet.jm.utils.log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * 用户会话状态机。
 *
 * 会话正确性边界：sessionGeneration。任何“把结果应用到活动会话”的提交都必须在
 * loginMutex 内做 generation + 身份双重校验；网络请求本身（登录/验证）始终在锁外执行，
 * 因此手动登录/登出不会被 10-20 秒的阻塞网络调用拖住。
 *
 * Cookie 提交统一走 [UserRepository.activateVerifiedSession]：只有 generation 仍然有效时
 * 才把候选/登录会话的完整 cookie（内置 API 含 AVS）持久化并同步到活动客户端。
 */
class UserManager(
    private val userStorage: UserStorage,
    private val cookieStorage: CookieStorage,
    private val userRepository: UserRepository,
    private val retrofit: ActiveSessionCookieStore,
    private val sessionReadinessHolder: SessionReadinessHolder,
) {
    private val _userState = MutableStateFlow(CommonUIState<User>())
    val userState = _userState.asStateFlow()

    /** Authoritative UI authentication state; Compose callers must not supply a fake false initial value. */
    val authState = sessionReadinessHolder.state
    private val loginMutex = Mutex()
    private val sessionGeneration = AtomicLong(0L)

    /**
     * Background work is cancellable when possible, but generation checks remain the correctness
     * boundary because embedded JMComic calls are synchronous Java/OkHttp operations.
     */
    @Volatile
    private var backgroundJob: Job? = null

    init {
        // Restoring the local identity is cheap and keeps the first frame consistent with the
        // last session. Network verification is deliberately started after the UI is ready.
        _userState.value = _userState.value.copy(data = runCatching { userStorage.get() }.getOrNull())
        sessionReadinessHolder.set(readinessForCachedUser(_userState.value.data))
    }

    /** Compatibility entry point for callers that replace the active identity directly. */
    fun updateUser(user: User) {
        sessionGeneration.incrementAndGet()
        _userState.update { it.copy(data = user) }
        userStorage.set(user)
        sessionReadinessHolder.set(readinessForCachedUser(user))
    }

    suspend fun clearUser() {
        cancelBackgroundJob()
        loginMutex.withLock {
            sessionGeneration.incrementAndGet()
            clearIdentityWhileLocked()
            sessionReadinessHolder.set(SessionReadiness.Unauthenticated)
        }
    }

    /** Performs a user-requested login without discarding the previous local identity on error. */
    suspend fun login(username: String, password: String): NetWorkResult<LoginSession> {
        cancelBackgroundJob()
        val generation = beginManualLogin()
        val result = userRepository.login(username, password)
        return commitLoginResult(
            generation = generation,
            password = password,
            result = result,
            clearUserOnError = false,
        )
    }

    /**
     * Verifies the saved credentials after the first screen is interactive.
     * 只有认证分类明确为 InvalidCredentials 才注销本地身份；离线、超时等临时错误保留缓存身份，
     * 避免“秒开时暂时没网 → 后台验证失败 → 用户被突然登出”。
     *
     * 网络验证在 loginMutex 外运行；提交（cookie 持久化、用户写入、读就绪状态）在锁内
     * 做 generation + 身份校验，陈旧候选结果（验证 A 期间手动登录 B / 登出）一律丢弃。
     */
    suspend fun verifyStoredLogin() {
        val snapshot = loginMutex.withLock {
            if (_userState.value.isLoading) return@withLock null
            val user = _userState.value.data?.takeIf {
                it.username.isNotEmpty() && it.password.isNotEmpty()
            } ?: return@withLock null
            SessionSnapshot(sessionGeneration.get(), user)
        } ?: return

        log("检测到已保存了用户登录信息，后台验证登录状态")
        runInBackground {
            if (!isCurrentSession(snapshot)) return@runInBackground
            loginMutex.withLock {
                if (isCurrentSession(snapshot)) {
                    _userState.update {
                        it.copy(isLoading = true, isError = false, errorMsg = "")
                    }
                }
            }

            // This request intentionally runs outside loginMutex. The repository uses an
            // isolated cookie jar/client so it cannot mutate a newer active session.
            val result = userRepository.verifyLogin(snapshot.user.username, snapshot.user.password)
            coroutineContext.ensureActive()

            loginMutex.withLock {
                if (!isCurrentSession(snapshot)) return@withLock
                when (result) {
                    is NetWorkResult.Error -> {
                        if (result.authFailure == AuthFailure.InvalidCredentials) {
                            clearIdentityWhileLocked(result.message)
                            sessionReadinessHolder.set(SessionReadiness.Unauthenticated)
                        } else {
                            // 临时失败保留缓存身份与已持久化的会话；仍按“已认证”对待，
                            // 避免收藏等请求在验证失败后一直空等。
                            sessionReadinessHolder.set(SessionReadiness.Authenticated)
                            _userState.update {
                                it.copy(isError = true, errorMsg = result.message, isLoading = false)
                            }
                        }
                    }

                    is NetWorkResult.Success<VerifiedCredentials> -> {
                        persistUserWhileLocked(
                            result.data.loginResponse.toUser(
                                password = snapshot.user.password
                            )
                        )
                        userRepository.activateVerifiedSession(result.data)
                        sessionReadinessHolder.set(SessionReadiness.Authenticated)
                        _userState.update { it.copy(isLoading = false) }
                    }
                }
            }
        }
    }

    /** Runs automatic sign-in with the same generation guard as saved-login verification. */
    suspend fun autoSignInIfNeeded(enabled: Boolean, toastManager: ToastManager) = runInBackground {
        if (!enabled) return@runInBackground
        val snapshot = loginMutex.withLock {
            if (_userState.value.isLoading) return@withLock null
            _userState.value.data
                ?.takeIf { it.id > 0 }
                ?.let { SessionSnapshot(sessionGeneration.get(), it) }
        } ?: return@runInBackground
        if (!isCurrentSession(snapshot)) return@runInBackground

        val signData = when (val result = userRepository.getSignData(snapshot.user.id)) {
            is NetWorkResult.Error -> return@runInBackground
            is NetWorkResult.Success<SignInDataResponse> -> result.data.toSignData()
        }
        coroutineContext.ensureActive()
        if (!isCurrentSession(snapshot)) return@runInBackground
        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
        if (signData.dateMap[today]?.isSign == true) return@runInBackground

        when (val result = userRepository.signIn(snapshot.user.id, signData.dailyId)) {
            is NetWorkResult.Success -> {
                coroutineContext.ensureActive()
                if (isCurrentSession(snapshot)) toastManager.showAsync(result.data.msg)
            }

            is NetWorkResult.Error -> log("自动签到", "签到失败：" + result.message)
        }
    }

    /** Compatibility entry point for callers that used the old auto-login name. */
    suspend fun autoLogin(username: String, password: String) {
        cancelBackgroundJob()
        val generation = beginManualLogin()
        val result = userRepository.login(username, password)
        commitLoginResult(
            generation = generation,
            password = password,
            result = result,
            clearUserOnError = true,
        )
    }

    /**
     * Runs [block] as the tracked background job and suspends until it finishes. The caller that
     * cancels this job does not join it, so a blocking embedded call cannot delay manual actions.
     */
    private suspend fun runInBackground(block: suspend () -> Unit) {
        coroutineScope {
            // LAZY 启动保证 cancel 与启动之间不存在“任务已跑起来但句柄还没登记”的窗口
            val job = launch(start = CoroutineStart.LAZY) { block() }
            backgroundJob = job
            job.invokeOnCompletion {
                if (backgroundJob === job) backgroundJob = null
            }
            job.start()
            job.join()
        }
    }

    private fun cancelBackgroundJob() {
        backgroundJob?.cancel()
    }

    private suspend fun beginManualLogin(): Long = loginMutex.withLock {
        val generation = sessionGeneration.incrementAndGet()
        _userState.update {
            it.copy(
                isLoading = true,
                isError = false,
                errorMsg = ""
            )
        }
        generation
    }

    private suspend fun commitLoginResult(
        generation: Long,
        password: String,
        result: NetWorkResult<LoginSession>,
        clearUserOnError: Boolean,
    ): NetWorkResult<LoginSession> {
        coroutineContext.ensureActive()
        return loginMutex.withLock {
            if (sessionGeneration.get() != generation) return@withLock result
            when (result) {
                is NetWorkResult.Error -> {
                    if (clearUserOnError && result.authFailure == AuthFailure.InvalidCredentials) {
                        clearIdentityWhileLocked(result.message)
                        sessionReadinessHolder.set(SessionReadiness.Unauthenticated)
                    } else {
                        _userState.update {
                            it.copy(
                                isError = true,
                                errorMsg = result.message,
                            )
                        }
                    }
                }

                is NetWorkResult.Success<LoginSession> -> {
                    persistUserWhileLocked(
                        result.data.loginResponse.toUser(
                            password = password
                        )
                    )
                    // 提交完整会话（内置 API 含 AVS；网络 API 登录响应已由活动 CookieJar
                    // 自行持久化，此处为空操作）。generation 校验保证陈旧的登录/验证结果
                    // 无法覆盖更新的会话。
                    userRepository.activateVerifiedSession(
                        VerifiedCredentials(
                            loginResponse = result.data.loginResponse,
                            embeddedCookies = result.data.embeddedCookies,
                        )
                    )
                    sessionReadinessHolder.set(SessionReadiness.Authenticated)
                }
            }
            if (result !is NetWorkResult.Error || result.authFailure != AuthFailure.InvalidCredentials || !clearUserOnError) {
                _userState.update { it.copy(isLoading = false) }
            }
            result
        }
    }

    private fun persistUserWhileLocked(user: User) {
        _userState.update {
            it.copy(
                data = user,
                isError = false,
                errorMsg = ""
            )
        }
        userStorage.set(user)
    }

    private fun clearIdentityWhileLocked(errorMsg: String? = null) {
        _userState.update {
            it.copy(
                data = User.create(),
                isLoading = false,
                isError = errorMsg != null,
                errorMsg = errorMsg.orEmpty(),
            )
        }
        retrofit.clearCookie()
        userRepository.clearSession()
        userStorage.remove()
        cookieStorage.remove()
    }

    private fun readinessForCachedUser(user: User?): SessionReadiness {
        val hasIdentity = user != null &&
            user.id > 0 &&
            user.username.isNotEmpty() &&
            user.password.isNotEmpty()
        if (!hasIdentity) return SessionReadiness.Unauthenticated
        val hasEmbeddedAuthCookie = cookieStorage.get().any {
            it.name.equals("AVS", ignoreCase = true)
        }
        return if (hasEmbeddedAuthCookie) {
            SessionReadiness.Authenticated
        } else {
            SessionReadiness.Restoring
        }
    }

    private fun isCurrentSession(snapshot: SessionSnapshot): Boolean {
        val currentUser = _userState.value.data ?: return false
        return sessionGeneration.get() == snapshot.generation &&
            currentUser.id == snapshot.user.id &&
            currentUser.username == snapshot.user.username
    }

    private data class SessionSnapshot(
        val generation: Long,
        val user: User,
    )
}
