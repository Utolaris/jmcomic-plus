package com.par9uet.jm.store

import com.par9uet.jm.data.models.APP_LOCK_TYPE_PASSWORD
import com.par9uet.jm.data.models.BlockedTagTemplate
import com.par9uet.jm.data.models.LocalSetting
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow read views over [com.par9uet.jm.data.models.LocalSetting]. Consumers collect the relevant
 * projection instead of the whole persistence object, avoiding unrelated state updates.
 */
interface ContentPreferences {
    val blockedTags: StateFlow<List<String>>

    /** Additional Home-feed exclusions configured in Settings; combined with [blockedTags]. */
    val homeExcludedTags: StateFlow<List<String>>
}

interface BlockedTagTemplatePreferences {
    val blockedTagTemplates: StateFlow<List<BlockedTagTemplate>>
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

interface AppExperiencePreferences {
    val onboardingCompleted: StateFlow<Boolean>
    val nsfwWarningDismissed: StateFlow<Boolean>
}

/** Explicit persistence boundary used by backup export; UI features should use narrow flows. */
interface LocalSettingSnapshotProvider {
    fun currentLocalSettingSnapshot(): LocalSetting
}

data class CacheNotificationSetting(
    val show: Boolean,
    val showName: Boolean,
)

interface CacheNotificationPreferences {
    val cacheNotification: StateFlow<CacheNotificationSetting>
}

data class GridColumnsSetting(
    val home: Int = 0,
    val collect: Int = 0,
    val download: Int = 0,
    val history: Int = 0,
    val search: Int = 0,
)

/** Small single-purpose toggles shown on the Settings home screen. */
data class MiscSettingsState(
    val clipboardAutoDetectEnabled: Boolean = false,
    val autoSignInEnabled: Boolean = true,
    val gridColumns: GridColumnsSetting = GridColumnsSetting(),
)

interface MiscSettingsPreferences {
    val misc: StateFlow<MiscSettingsState>
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
 * Each mutator expresses one complete valid state transition, so callers do not need to compose
 * multiple writes or expose an invalid intermediate credential/mode combination.
 */
interface AppSecurityEditor {
    fun setPassword(password: String, length: Int)
    fun removePassword()
    fun setPattern(pattern: String)
    fun removePattern()
    fun setAppLockEnabled(enabled: Boolean)
    fun disableAndClearAppLock()
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
}

interface DohPreferencesEditor {
    fun persistEnabled(enabled: Boolean)
    fun persistAutoStart(enabled: Boolean)
    fun persistServer(serverId: String)
    fun persistCustomServer(name: String, url: String)
    fun persistUseDeviceCertificates(enabled: Boolean)
    fun persistPreferIpv6(enabled: Boolean)
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
    /** Currently selected launcher alias id ([LauncherDisguise]). */
    val launcherDisguiseId: StateFlow<String>
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
