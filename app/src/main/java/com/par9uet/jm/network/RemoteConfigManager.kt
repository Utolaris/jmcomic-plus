package com.par9uet.jm.network
import com.google.gson.reflect.TypeToken
import com.par9uet.jm.core.model.RemoteSetting
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.retrofit.model.RemoteSettingResponse
import com.par9uet.jm.storage.RemoteConfigPreferences
import com.par9uet.jm.storage.SecureStorage
import com.par9uet.jm.storage.StorageReadResult
import com.par9uet.jm.utils.log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persistence seam so RemoteConfigManager is testable without Android SecureStorage. */
interface RemoteConfigStore {
    fun <T> get(key: String, type: java.lang.reflect.Type): T?
    fun <T> set(key: String, value: T)
}

/** Narrow port for fetching the server setting response; the composition root delegates it. */
fun interface RemoteSettingFetch {
    suspend fun fetch(): NetWorkResult<RemoteSettingResponse>
}

class SecureRemoteConfigStore(
    private val secureStorage: SecureStorage,
) : RemoteConfigStore {
    override fun <T> get(key: String, type: java.lang.reflect.Type): T? =
        when (val result = secureStorage.get<T>(key, type)) {
            is StorageReadResult.Success -> result.value
            is StorageReadResult.Missing,
            is StorageReadResult.Corrupted,
            is StorageReadResult.TemporaryUnavailable,
            -> null
        }

    override fun <T> set(key: String, value: T) {
        secureStorage.set(key, value)
    }
}

/**
 * Server-delivered runtime configuration (currently the image CDN host). This is not a user
 * setting; read-only consumers depend on [RemoteConfigPreferences], control paths (startup)
 * may use the concrete manager for refresh().
 */
class RemoteConfigManager(
    private val remoteSettingFetch: RemoteSettingFetch,
    private val store: RemoteConfigStore,
) : RemoteConfigPreferences {
    companion object {
        private const val STORAGE_KEY = "remoteSetting"
    }

    private val refreshMutex = Mutex()
    private val _remoteImageHost = MutableStateFlow(loadCachedConfig().imgHost)
    override val remoteImageHost = _remoteImageHost.asStateFlow()

    /** Refreshes the remote value without ever being part of the first-screen dependency graph. */
    suspend fun refresh() = refreshMutex.withLock {
        when (val data = remoteSettingFetch.fetch()) {
            is NetWorkResult.Error -> {
                log("获取远程应用设置失败，继续使用本地缓存：${data.message}")
            }

            is NetWorkResult.Success<RemoteSettingResponse> -> {
                val setting = data.data.toRemoteSetting()
                if (setting.imgHost.isNotBlank()) {
                    _remoteImageHost.value = setting.imgHost
                    store.set(STORAGE_KEY, setting)
                }
                log("获取远程应用设置成功")
            }
        }
    }

    private fun loadCachedConfig(): RemoteSetting {
        return runCatching {
            store.get<RemoteSetting>(
                STORAGE_KEY,
                object : TypeToken<RemoteSetting>() {}.type,
            )
        }.getOrNull()?.takeIf { it.imgHost.isNotBlank() } ?: RemoteSetting()
    }
}
