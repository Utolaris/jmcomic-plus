package com.par9uet.jm.storage

import com.google.gson.reflect.TypeToken
import com.par9uet.jm.data.models.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 已登录身份的持久化读写。[SecureUserStorage] 提供加密实现，测试可用内存替身。 */
interface UserStorage {
    fun get(): User
    fun set(user: User)
    fun remove()
}

class SecureUserStorage(
    private val secureStorage: SecureStorage
) : UserStorage {
    companion object {
        private const val STORAGE_KEY = "user"
    }

    private var _state = MutableStateFlow<User?>(null)
    val state = _state.asStateFlow()

    override fun set(user: User) {
        _state.update {
            user
        }
        secureStorage.setStartup(STORAGE_KEY, user)
    }

    override fun get(): User {
        if (_state.value == null) {
            _state.update {
                secureStorage.getStartup<User>(STORAGE_KEY, object : TypeToken<User>() {}.type)
                    ?: secureStorage.get<User>(STORAGE_KEY, object : TypeToken<User>() {}.type)
                    ?.also { secureStorage.setStartup(STORAGE_KEY, it) }
                    ?: User.create()
            }
        }
        return _state.value ?: User.create()
    }

    override fun remove() {
        _state.update {
            User.create()
        }
        secureStorage.remove(STORAGE_KEY)
        secureStorage.removeStartup(STORAGE_KEY)
    }
}
