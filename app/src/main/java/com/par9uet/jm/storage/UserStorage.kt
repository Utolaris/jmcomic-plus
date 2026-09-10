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
        when (secureStorage.setStartup(STORAGE_KEY, user)) {
            is StorageWriteResult.Success -> _state.update { user }
            is StorageWriteResult.TemporaryUnavailable -> Unit
        }
    }

    override fun get(): User {
        _state.value?.let { return it }
        val startup = secureStorage.getStartup<User>(STORAGE_KEY, object : TypeToken<User>() {}.type)
        when (startup) {
            is StorageReadResult.Success -> return startup.value.also { _state.value = it }
            is StorageReadResult.TemporaryUnavailable -> return User.create()
            is StorageReadResult.Missing,
            is StorageReadResult.Corrupted,
            -> Unit
        }
        val legacy = secureStorage.get<User>(STORAGE_KEY, object : TypeToken<User>() {}.type)
        return when (legacy) {
            is StorageReadResult.Success -> legacy.value.also {
                _state.value = it
                secureStorage.setStartup(STORAGE_KEY, it)
            }
            is StorageReadResult.TemporaryUnavailable -> User.create()
            is StorageReadResult.Missing,
            is StorageReadResult.Corrupted,
            -> User.create().also { _state.value = it }
        }
    }

    override fun remove() {
        _state.update {
            User.create()
        }
        secureStorage.remove(STORAGE_KEY)
        secureStorage.removeStartup(STORAGE_KEY)
    }
}
