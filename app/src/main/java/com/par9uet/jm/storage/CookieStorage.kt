package com.par9uet.jm.storage

import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Cookie

/** 活动认证会话 cookie 的持久化读写。[SecureCookieStorage] 提供加密实现，测试可用内存替身。 */
interface CookieStorage {
    val state: StateFlow<List<Cookie>?>
    fun set(cookieStore: List<Cookie>)
    fun get(): List<Cookie>
    fun remove()
}

class SecureCookieStorage(
    private val secureStorage: SecureStorage
) : CookieStorage {
    companion object {
        private const val STORAGE_KEY = "cookie"
    }

    private var _state = MutableStateFlow<List<Cookie>?>(null)
    override val state = _state.asStateFlow()

    override fun set(cookieStore: List<Cookie>) {
        // Only publish in-memory after the durable write succeeds, so memory cannot diverge from disk.
        when (secureStorage.set(STORAGE_KEY, cookieStore)) {
            is StorageWriteResult.Success -> _state.update { cookieStore }
            is StorageWriteResult.TemporaryUnavailable -> Unit
        }
    }

    override fun get(): List<Cookie> {
        _state.value?.let { return it }
        return when (
            val result = secureStorage.get<List<Cookie>>(
                STORAGE_KEY,
                object : TypeToken<List<Cookie>>() {}.type,
            )
        ) {
            is StorageReadResult.Success -> result.value.also { _state.value = it }
            // Permanent failures may cache empty; temporary Keystore outage must retry.
            is StorageReadResult.Missing,
            is StorageReadResult.Corrupted,
            -> emptyList<Cookie>().also { _state.value = it }
            is StorageReadResult.TemporaryUnavailable -> emptyList()
        }
    }

    override fun remove() {
        _state.update {
            listOf()
        }
        secureStorage.remove(STORAGE_KEY)
    }
}
