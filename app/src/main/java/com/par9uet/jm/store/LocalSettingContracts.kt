package com.par9uet.jm.store

import com.par9uet.jm.data.models.APP_LOCK_TYPE_PASSWORD
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow read contracts over [com.par9uet.jm.data.models.LocalSetting]. A feature depends on
 * one of these instead of the persistence DTO, so it cannot observe unrelated configuration
 * such as the app-lock credentials.
 */
interface ContentPreferences {
    val blockedTags: StateFlow<List<String>>

    /** Additional Home-feed exclusions configured in Settings; combined with [blockedTags]. */
    val homeExcludedTags: StateFlow<List<String>>
}

/** Whether the logged-in account's personalized network recommendations feed the Home page. */
interface RecommendationPreferences {
    val preferenceRecommendEnabled: StateFlow<Boolean>
}

interface ReaderPreferences {
    /** scroll | page | tap */
    val readMode: StateFlow<String>

    /** default | side */
    val readTapMode: StateFlow<String>
    val prefetchCount: StateFlow<Int>
    val memoryOptEnabled: StateFlow<Boolean>
    val decodeConcurrency: StateFlow<Int>
}

data class CacheNotificationSetting(
    val show: Boolean,
    val showName: Boolean,
)

interface CacheNotificationPreferences {
    val cacheNotification: StateFlow<CacheNotificationSetting>
}

data class AppLockState(
    val enabled: Boolean = false,
    val password: String = "",
    val passwordLength: Int = 4,
    val pattern: String = "",
    val unlockMode: String = APP_LOCK_TYPE_PASSWORD,
) {
    val hasPassword: Boolean get() = password.isNotEmpty()
    val hasPattern: Boolean get() = pattern.isNotEmpty()
    val hasCredential: Boolean get() = hasPassword || hasPattern
}

interface AppSecurityPreferences {
    val appLock: StateFlow<AppLockState>
}

/**
 * Every mutator is one complete state transition: it rewrites password, pattern, length,
 * unlock mode and enabled together in a single LocalSetting update so no invalid
 * credential/mode combination can ever be observed.
 */
interface AppSecurityEditor {
    fun setPassword(password: String, length: Int)
    fun removePassword()
    fun setPattern(pattern: String)
    fun removePattern()
    fun setAppLockEnabled(enabled: Boolean)
    fun selectUnlockMode(mode: String)
}

data class DohSettingsState(
    val enabled: Boolean = true,
    val autoStart: Boolean = true,
    val serverId: String = "tencent",
    val customServerName: String = "",
    val customServerUrl: String = "",
    val useDeviceCertificates: Boolean = true,
    val preferIpv6: Boolean = false,
)

interface DohPreferences {
    val doh: StateFlow<DohSettingsState>

    /**
     * True while DoH resolution is expected for this session: toggled in settings or
     * auto-started at launch. While inactive, lookups intentionally use system DNS.
     */
    val sessionDohActive: StateFlow<Boolean>
}

interface DohPreferencesEditor {
    fun persistEnabled(enabled: Boolean)
    fun persistAutoStart(enabled: Boolean)
    fun persistServer(serverId: String)
    fun persistCustomServer(name: String, url: String)
    fun persistUseDeviceCertificates(enabled: Boolean)
    fun persistPreferIpv6(enabled: Boolean)

    /** Marks whether DoH resolution is expected for the current app session. */
    fun setDohSessionActive(active: Boolean)
}

data class ColorPaletteState(
    val presetId: String,
    val customPrimary: String?,
    val customSecondary: String?,
    val customTertiary: String?,
    val customError: String?,
) {
    val hasCustomOverride: Boolean
        get() = customPrimary != null || customSecondary != null ||
            customTertiary != null || customError != null
}

interface AppearancePreferences {
    /** auto | light | dark */
    val theme: StateFlow<String>
    val colorPalette: StateFlow<ColorPaletteState>
    val editor: AppearanceEditor
}

/** Base URL every JM API request is rewritten to; the interceptor reads it per call. */
interface ApiEndpointPreference {
    val apiEndpoint: StateFlow<String>
}

/** Narrow read view of server-delivered runtime configuration. */
interface RemoteConfigPreferences {
    /** Preferred image CDN host from the server; blank until the first successful refresh. */
    val remoteImageHost: StateFlow<String>
}
interface AppearanceEditor {
    /** Atomic preset switch: clears custom color overrides in the same transition. */
    fun selectColorPreset(presetId: String)

    /** Atomic custom-color confirm: switches the palette to custom in the same transition. */
    fun applyCustomColors(primary: String?, secondary: String?, tertiary: String?, error: String?)
}
