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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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

    suspend fun clearUser() = loginMutex.withLock {
        _userState.update { it.copy(data = User.create()) }
        retrofit.clearCookie()
        userStorage.remove()
        cookieStorage.remove()
    }

    /** Performs a user-requested login without discarding the previous local identity on error. */
    suspend fun login(username: String, password: String): NetWorkResult<LoginResponse> {
        return loginInternal(username, password, clearUserOnError = false)
    }

    /** Verifies the saved credentials after the first screen is interactive. */
    suspend fun verifyStoredLogin() {
        val userData = _userState.value.data
        if (userData != null && userData.username.isNotEmpty() && userData.password.isNotEmpty()) {
            val username = userData.username
            val password = userData.password
            log("检测到已保存了用户登录信息，后台验证登录状态")
            loginInternal(username, password, clearUserOnError = true)
        }
    }

    /** Runs automatic sign-in under the same mutex as manual and saved-login verification. */
    suspend fun autoSignInIfNeeded(enabled: Boolean, toastManager: ToastManager) =
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

    /** Compatibility entry point for callers that used the old auto-login name. */
    suspend fun autoLogin(username: String, password: String) {
        loginInternal(username, password, clearUserOnError = true)
    }

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
