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
        _state.update {
            cookieStore
        }
        secureStorage.set(STORAGE_KEY, this.state.value)
    }

    override fun get(): List<Cookie> {
        if (_state.value == null) {
            _state.update {
                secureStorage.get(STORAGE_KEY, object : TypeToken<List<Cookie>>() {}.type)
                    ?: listOf()
            }
        }
        return _state.value ?: listOf()
    }

    override fun remove() {
        _state.update {
            listOf()
        }
        secureStorage.remove(STORAGE_KEY)
    }
}
