package com.par9uet.jm.store

import com.par9uet.jm.data.models.User
import com.par9uet.jm.repository.UserRepository
import com.par9uet.jm.retrofit.Retrofit
import com.par9uet.jm.retrofit.model.LoginResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.SignInDataResponse
import com.par9uet.jm.storage.CookieStorage
import com.par9uet.jm.storage.UserStorage
import com.par9uet.jm.ui.models.CommonUIState
import com.par9uet.jm.utils.log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UserManager(
    private val userStorage: UserStorage,
    private val cookieStorage: CookieStorage,
    private val userRepository: UserRepository,
    private val retrofit: Retrofit
) {
    private val _userState = MutableStateFlow(CommonUIState<User>())
    val userState = _userState.asStateFlow()

    val isLoginState = _userState.map { (it.data?.id ?: 0) > 0 }
    private val loginMutex = Mutex()

    /**
     * 后台验证/自动签到任务的句柄。mutex 会跨整个网络请求持有，
     * 用户主动登录/退出时先取消后台任务，避免手动操作排在最长 20 秒的后台请求之后。
     */
    @Volatile
    private var backgroundJob: Job? = null

    init {
        // Restoring the local identity is cheap and keeps the first frame consistent with the
        // last session. Network verification is deliberately started after the UI is ready.
        _userState.value = _userState.value.copy(data = runCatching { userStorage.get() }.getOrNull())
    }

    fun updateUser(user: User) {
        _userState.update {
            it.copy(
                data = user
            )
        }
        userStorage.set(user)
    }

    suspend fun clearUser() {
        cancelBackgroundJob()
        loginMutex.withLock {
            // isLoading = false：被取消的后台验证可能已把加载态置真且不会再收尾
            _userState.update { it.copy(data = User.create(), isLoading = false) }
            retrofit.clearCookie()
            userStorage.remove()
            cookieStorage.remove()
        }
    }

    /** Performs a user-requested login without discarding the previous local identity on error. */
    suspend fun login(username: String, password: String): NetWorkResult<LoginResponse> {
        cancelBackgroundJob()
        return loginInternal(username, password, clearUserOnError = false)
    }

    /**
     * Verifies the saved credentials after the first screen is interactive.
     * 只有服务端明确拒绝凭据（401）才注销本地身份；离线、超时等临时错误保留缓存身份，
     * 避免“秒开时暂时没网 → 后台验证失败 → 用户被突然登出”。
     */
    suspend fun verifyStoredLogin() {
        val userData = _userState.value.data
        if (userData != null && userData.username.isNotEmpty() && userData.password.isNotEmpty()) {
            val username = userData.username
            val password = userData.password
            log("检测到已保存了用户登录信息，后台验证登录状态")
            runInBackground {
                loginMutex.withLock {
                    _userState.update {
                        it.copy(isLoading = true, isError = false, errorMsg = "")
                    }
                    when (val result = userRepository.login(username, password)) {
                        is NetWorkResult.Error -> {
                            _userState.update {
                                if (result.isCredentialRejected()) {
                                    it.copy(isError = true, errorMsg = result.message, data = User.create())
                                } else {
                                    it.copy(isError = true, errorMsg = result.message)
                                }
                            }
                        }

                        is NetWorkResult.Success<LoginResponse> -> {
                            updateUser(
                                result.data.toUser(
                                    password = password
                                )
                            )
                        }
                    }
                    _userState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    /** Runs automatic sign-in under the same mutex as manual and saved-login verification. */
    suspend fun autoSignInIfNeeded(enabled: Boolean, toastManager: ToastManager) = runInBackground {
        loginMutex.withLock {
            if (!enabled) return@withLock
            val user = _userState.value.data?.takeIf { it.id > 0 } ?: return@withLock
            val signData = when (val result = userRepository.getSignData(user.id)) {
                is NetWorkResult.Error -> return@withLock
                is NetWorkResult.Success<SignInDataResponse> -> result.data.toSignData()
            }
            val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
            if (signData.dateMap[today]?.isSign == true) return@withLock

            when (val result = userRepository.signIn(user.id, signData.dailyId)) {
                is NetWorkResult.Success -> toastManager.showAsync(result.data.msg)
                is NetWorkResult.Error -> log("自动签到", "签到失败：${result.message}")
            }
        }
    }

    /** Compatibility entry point for callers that used the old auto-login name. */
    suspend fun autoLogin(username: String, password: String) {
        cancelBackgroundJob()
        loginInternal(username, password, clearUserOnError = true)
    }

    /**
     * Runs [block] as the tracked background job and suspends until it finishes.
     * 用户主动操作通过 [cancelBackgroundJob] 取消该任务后，mutex 随网络请求的取消立即释放。
     */
    private suspend fun runInBackground(block: suspend () -> Unit) {
        coroutineScope {
            // LAZY 启动保证 cancel 与启动之间不存在“任务已跑起来但句柄还没登记”的窗口
            val job = launch(start = CoroutineStart.LAZY) { block() }
            backgroundJob = job
            job.start()
            job.join()
        }
    }

    private fun cancelBackgroundJob() {
        backgroundJob?.cancel()
    }

    /** 仅当服务端明确拒绝凭据时返回 true；网络不可用、超时等一律视为临时错误。 */
    private fun NetWorkResult.Error.isCredentialRejected(): Boolean =
        code == 401 || message.contains("账号或密码错误")

    private suspend fun loginInternal(
        username: String,
        password: String,
        clearUserOnError: Boolean,
    ): NetWorkResult<LoginResponse> = loginMutex.withLock {
        _userState.update {
            it.copy(
                isLoading = true,
                isError = false,
                errorMsg = ""
            )
        }
        val result = userRepository.login(username, password)
        when (result) {
            is NetWorkResult.Error -> {
                _userState.update {
                    it.copy(
                        isError = true,
                        errorMsg = result.message,
                        data = if (clearUserOnError) User.create() else it.data
                    )
                }
            }

            is NetWorkResult.Success<LoginResponse> -> {
                updateUser(
                    result.data.toUser(
                        password = password
                    )
                )
            }
        }
        _userState.update { it.copy(isLoading = false) }
        result
    }
}
