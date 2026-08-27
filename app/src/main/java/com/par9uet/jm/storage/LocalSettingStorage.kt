package com.par9uet.jm.storage

import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PASSWORD
import com.par9uet.jm.data.models.APP_LOCK_TYPE_PATTERN
import com.par9uet.jm.data.models.BlockedTagTemplate
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_BOTH
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_PASSWORD
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_PATTERN
import com.par9uet.jm.data.models.COLOR_PALETTE_PRESET_DEFAULT
import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.utils.flattenBlockedTagTemplates
import com.par9uet.jm.utils.normalizeBlockedTagList
import com.par9uet.jm.utils.normalizeBlockedTagTemplates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Persistence boundary for the single encrypted [LocalSetting] JSON document. New installs
 * read/write the small startup preferences file; the legacy lookup stays as a one-time
 * migration path for existing installations.
 */
class LocalSettingStorage(
    private val secureStorage: SecureStorage
) : LocalSettingPersistence {
    companion object {
        private const val STORAGE_KEY = "localSetting"
        private val GSON_TYPE = object : TypeToken<LocalSetting>() {}.type
    }

    private var _state = MutableStateFlow<LocalSetting?>(null)

    override fun load(): LocalSetting? {
        _state.value?.let { return it }
        val savedJson = secureStorage.getStartupString(STORAGE_KEY)
            ?: secureStorage.getString(STORAGE_KEY)?.also {
                secureStorage.setStartupString(STORAGE_KEY, it)
            }
        if (savedJson == null) return null
        val saved = secureStorage.decode<LocalSetting>(savedJson, GSON_TYPE) ?: return null
        return normalizePersisted(savedJson, saved).also { restored -> _state.update { restored } }
    }

    override fun persist(localSetting: LocalSetting) {
        _state.update { localSetting }
        secureStorage.setStartup(STORAGE_KEY, localSetting)
    }

    fun remove() {
        _state.update { null }
        secureStorage.remove(STORAGE_KEY)
        secureStorage.removeStartup(STORAGE_KEY)
    }

}

/**
 * Normalizes every load: legacy field migrations and enum-safe coercions live here so the
 * manager always starts from a valid LocalSetting. Top-level so tests exercise the real path.
 */
internal fun normalizePersisted(savedJson: String, saved: LocalSetting): LocalSetting {
        // 旧版本字段 appLockType 迁移到 appLockUnlockMode。
        val migratedUnlockMode = when {
            savedJson.hasField("appLockUnlockMode") -> saved.appLockUnlockMode
            else -> parseLegacyAppLockType(savedJson) ?: APP_LOCK_TYPE_PASSWORD
        }
        val legacyBlockedTags = normalizeBlockedTagList(
            runCatching { saved.blockedTagList }.getOrNull() ?: listOf()
        )
        val savedTemplates = normalizeBlockedTagTemplates(
            runCatching { saved.blockedTagTemplateList }.getOrNull() ?: listOf()
        )
        val migratedTemplates = when {
            savedJson.hasField("blockedTagTemplateList") -> savedTemplates
            legacyBlockedTags.isNotEmpty() ->
                listOf(BlockedTagTemplate(name = "默认排除", tagList = legacyBlockedTags))
            else -> listOf()
        }
        val appLock = canonicalizeAppLock(saved, migratedUnlockMode)
        return saved.copy(
            showComicCacheNotification = if (savedJson.hasField("showComicCacheNotification")) {
                saved.showComicCacheNotification
            } else {
                true
            },
            showComicCacheNotificationName = if (savedJson.hasField("showComicCacheNotificationName")) {
                saved.showComicCacheNotificationName
            } else {
                true
            },
            launcherDisguise = if (savedJson.hasField("launcherDisguise")) {
                LauncherDisguise.fromId(saved.launcherDisguise).id
            } else {
                LauncherDisguise.Default.id
            },
            blockedTagList = flattenBlockedTagTemplates(migratedTemplates),
            blockedTagTemplateList = migratedTemplates,
            appLockPassword = appLock.password,
            appLockPasswordLength = appLock.passwordLength,
            appLockPattern = appLock.pattern,
            appLockEnabled = appLock.enabled,
            appLockUnlockMode = appLock.unlockMode,
            colorPalettePreset = if (savedJson.hasField("colorPalettePreset")) {
                saved.colorPalettePreset
            } else {
                COLOR_PALETTE_PRESET_DEFAULT
            },
            customColorPrimary = nullableString(savedJson, "customColorPrimary", saved.customColorPrimary),
            customColorSecondary = nullableString(savedJson, "customColorSecondary", saved.customColorSecondary),
            customColorTertiary = nullableString(savedJson, "customColorTertiary", saved.customColorTertiary),
            customColorError = nullableString(savedJson, "customColorError", saved.customColorError),
            // DoH 默认启用，但仅对从未持久化过选择的用户生效；显式关闭在重启后保留。
            dohEnabled = saved.dohEnabled,
            dohAutoStart = saved.dohAutoStart,
            dohServerId = saved.dohServerId.ifBlank { "tencent" },
            dohCustomServerName = saved.dohCustomServerName,
            dohCustomServerUrl = saved.dohCustomServerUrl,
            dohUseDeviceCertificates = saved.dohUseDeviceCertificates,
            dohPreferIpv6 = saved.dohPreferIpv6,
        )
}
private fun nullableString(json: String, field: String, value: String?): String? =
    if (json.hasField(field)) value else null

private fun String?.hasField(name: String): Boolean = this != null && contains(QUOTE + name + QUOTE)

internal data class CanonicalAppLock(
    val enabled: Boolean,
    val password: String,
    val passwordLength: Int,
    val pattern: String,
    val unlockMode: String,
)

/**
 * Older versions let callers sequence multiple setters, so persisted JSON can hold lock states
 * the atomic mutator can no longer produce (a credential-less enabled lock, a mode without its
 * credential, or BOTH with one credential). Every load is rewritten to the closest valid state.
 */
internal fun canonicalizeAppLock(saved: LocalSetting, migratedUnlockMode: String): CanonicalAppLock {
    val password = saved.appLockPassword.orEmpty()
    val pattern = saved.appLockPattern.orEmpty()
    val unlockMode = when {
        password.isNotEmpty() && pattern.isNotEmpty() -> when (migratedUnlockMode) {
            APP_LOCK_UNLOCK_MODE_PASSWORD, APP_LOCK_UNLOCK_MODE_PATTERN, APP_LOCK_UNLOCK_MODE_BOTH ->
                migratedUnlockMode
            else -> APP_LOCK_UNLOCK_MODE_BOTH
        }
        password.isNotEmpty() -> APP_LOCK_UNLOCK_MODE_PASSWORD
        pattern.isNotEmpty() -> APP_LOCK_UNLOCK_MODE_PATTERN
        else -> APP_LOCK_UNLOCK_MODE_PASSWORD
    }
    return CanonicalAppLock(
        // 启用锁但没有可解锁的凭据是非法状态；恢复为关闭。
        enabled = saved.appLockEnabled && (password.isNotEmpty() || pattern.isNotEmpty()),
        password = password,
        passwordLength = saved.appLockPasswordLength.coerceIn(4, 8),
        pattern = pattern,
        unlockMode = unlockMode,
    )
}

/**
 * 旧版本存储中的 appLockType 字段（已废弃）迁移到 appLockUnlockMode。
 */
private fun parseLegacyAppLockType(json: String?): String? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val obj = JsonParser.parseString(json).asJsonObject
        if (!obj.has("appLockType")) return@runCatching null
        when (obj.get("appLockType").asString) {
            APP_LOCK_TYPE_PATTERN -> APP_LOCK_TYPE_PATTERN
            else -> APP_LOCK_TYPE_PASSWORD
        }
    }.getOrNull()
}

private val QUOTE: String = Char(34).toString()
