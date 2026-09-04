package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.data.models.AVAILABLE_APIS
import com.par9uet.jm.data.models.AVAILABLE_THEMES
import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.favorites.model.FavoriteSyncUiState
import com.par9uet.jm.favorites.sync.FavoriteSyncRequestKind
import com.par9uet.jm.favorites.sync.FavoriteSyncRequester
import com.par9uet.jm.store.ApiEndpointPreference
import com.par9uet.jm.store.AppSecurityPreferences
import com.par9uet.jm.store.AppearancePreferences
import com.par9uet.jm.store.CacheNotificationPreferences
import com.par9uet.jm.store.CacheNotificationSetting
import com.par9uet.jm.store.ColorPaletteState
import com.par9uet.jm.store.ContentPreferences
import com.par9uet.jm.store.DohPreferences
import com.par9uet.jm.store.DohSettingsState
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.MiscSettingsState
import com.par9uet.jm.store.ReaderPreferences
import com.par9uet.jm.store.RecommendationPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the Settings home screen renders, composed from narrow preference flows. */
data class SettingsUiState(
    val theme: String = AVAILABLE_THEMES.first(),
    val colorPalette: ColorPaletteState = ColorPaletteState("default", null, null, null, null),
    val launcherDisguiseId: String = LauncherDisguise.Default.id,
    val apiEndpoint: String = AVAILABLE_APIS.first(),
    val recommendationEnabled: Boolean = true,
    val clipboardAutoDetectEnabled: Boolean = false,
    val autoSignInEnabled: Boolean = true,
    val homeExcludedTags: List<String> = emptyList(),
    val prefetchCount: Int = 3,
    val readMode: String = "scroll",
    val memoryOptEnabled: Boolean = false,
    val decodeConcurrency: Int = 2,
    val notification: CacheNotificationSetting = CacheNotificationSetting(show = true, showName = true),
    val appLockEnabled: Boolean = false,
    val appLockHasPassword: Boolean = false,
    val appLockHasPattern: Boolean = false,
    val doh: DohSettingsState = DohSettingsState(),
    val gridColumns: GridColumnsSnapshot = GridColumnsSnapshot(),
) {
    /** Human-readable one-line summary of the current app lock state. */
    fun appLockSummaryText(): String {
        if (!appLockEnabled) return "未启用"
        val methods = buildList {
            if (appLockHasPassword) add("密码")
            if (appLockHasPattern) add("图案")
        }
        return if (methods.isEmpty()) "已启用" else "已启用 - " + methods.joinToString("+")
    }
}

data class GridColumnsSnapshot(
    val home: Int = 0,
    val collect: Int = 0,
    val download: Int = 0,
    val history: Int = 0,
    val search: Int = 0,
)

private data class AppearanceSnapshot(
    val theme: String,
    val colorPalette: ColorPaletteState,
    val launcherDisguiseId: String,
    val recommendationEnabled: Boolean,
)

private data class ReaderSnapshot(
    val prefetchCount: Int,
    val readMode: String,
    val memoryOptEnabled: Boolean,
    val decodeConcurrency: Int,
)


private data class CombinedMiscSnapshot(
    val apiEndpoint: String,
    val homeExcludedTags: List<String>,
    val appLock: com.par9uet.jm.store.AppLockState,
    val doh: DohSettingsState,
    val misc: com.par9uet.jm.store.MiscSettingsState,
)
/**
 * Control owner for the Settings home screen only; sub-screens keep their own narrow facades
 * (AppSecurityEditor / DohManager / AppearanceEditor). The UI collects [uiState] and sends
 * actions; catalog validation lives here, compound invariants live in LocalSettingManager.
 */
class SettingsViewModel(
    contentPreferences: ContentPreferences,
    recommendationPreferences: RecommendationPreferences,
    readerPreferences: ReaderPreferences,
    cacheNotificationPreferences: CacheNotificationPreferences,
    appearancePreferences: AppearancePreferences,
    securityPreferences: AppSecurityPreferences,
    dohPreferences: DohPreferences,
    apiEndpointPreference: ApiEndpointPreference,
    miscSettings: com.par9uet.jm.store.MiscSettingsPreferences,
    private val localSettingManager: LocalSettingManager,
    private val favoriteSyncRequester: FavoriteSyncRequester,
) : ViewModel() {

    // Hierarchical combine keeps each combine within its 5-flow typed overload.
    private val appearanceState = combine(
        appearancePreferences.theme,
        appearancePreferences.colorPalette,
        appearancePreferences.launcherDisguiseId,
        recommendationPreferences.preferenceRecommendEnabled,
    ) { theme, palette, disguiseId, recommend ->
        AppearanceSnapshot(theme, palette, disguiseId, recommend)
    }

    private val readerState = combine(
        readerPreferences.prefetchCount,
        readerPreferences.readMode,
        readerPreferences.memoryOptEnabled,
        readerPreferences.decodeConcurrency,
    ) { prefetch, mode, memoryOpt, concurrency -> ReaderSnapshot(prefetch, mode, memoryOpt, concurrency) }

    private val miscState = combine(
        apiEndpointPreference.apiEndpoint,
        contentPreferences.homeExcludedTags,
        securityPreferences.appLock,
        dohPreferences.doh,
        miscSettings.misc,
    ) { api, excludedTags, appLock, doh, misc -> CombinedMiscSnapshot(api, excludedTags, appLock, doh, misc) }

    val uiState: StateFlow<SettingsUiState> = combine(
        appearanceState,
        readerState,
        miscState,
        cacheNotificationPreferences.cacheNotification,
    ) { appearance, reader, misc, notification ->
        SettingsUiState(
            theme = appearance.theme,
            colorPalette = appearance.colorPalette,
            launcherDisguiseId = appearance.launcherDisguiseId,
            recommendationEnabled = appearance.recommendationEnabled,
            apiEndpoint = misc.apiEndpoint,
            homeExcludedTags = misc.homeExcludedTags,
            prefetchCount = reader.prefetchCount,
            readMode = reader.readMode,
            memoryOptEnabled = reader.memoryOptEnabled,
            decodeConcurrency = reader.decodeConcurrency,
            notification = notification,
            appLockEnabled = misc.appLock.enabled,
            appLockHasPassword = misc.appLock.hasPassword,
            appLockHasPattern = misc.appLock.hasPattern,
            doh = misc.doh,
            clipboardAutoDetectEnabled = misc.misc.clipboardAutoDetectEnabled,
            autoSignInEnabled = misc.misc.autoSignInEnabled,
            gridColumns = GridColumnsSnapshot(
                home = misc.misc.gridColumns.home,
                collect = misc.misc.gridColumns.collect,
                download = misc.misc.gridColumns.download,
                history = misc.misc.gridColumns.history,
                search = misc.misc.gridColumns.search,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    val favoriteSyncState: StateFlow<FavoriteSyncUiState> = favoriteSyncRequester.state

    /** Narrow maintenance capability; Settings never touches FavoritesViewModel. */
    fun requestFavoriteForceRefresh() = viewModelScope.launch {
        if (!favoriteSyncRequester.state.value.isSyncing) {
            favoriteSyncRequester.request(FavoriteSyncRequestKind.FORCE)
        }
    }

    // ---- simple settings intents ----

    fun setPreferenceRecommendEnabled(enabled: Boolean) =
        localSettingManager.setPreferenceRecommendEnabled(enabled)

    fun setAutoSignInEnabled(enabled: Boolean) =
        localSettingManager.updateAutoSignInEnabled(enabled)

    fun setClipboardAutoDetectEnabled(enabled: Boolean) =
        localSettingManager.updateClipboardAutoDetectEnabled(enabled)

    fun setMemoryOptEnabled(enabled: Boolean) = localSettingManager.setMemoryOptEnabled(enabled)

    fun selectApi(url: String) {
        require(url in AVAILABLE_API_SET) { "未知 API 节点" }
        localSettingManager.setApiEndpoint(url)
    }

    fun selectTheme(theme: String) {
        require(theme in AVAILABLE_THEME_SET) { "未知主题" }
        localSettingManager.applyTheme(theme)
    }

    fun selectLauncherDisguise(id: String) = localSettingManager.updateLauncherDisguise(id)

    fun setPrefetchCount(count: Int) = localSettingManager.setPrefetchCount(count)

    fun setReadMode(mode: String) = localSettingManager.setReadMode(mode)

    fun setDecodeConcurrency(concurrency: Int) =
        localSettingManager.setDecodeConcurrency(concurrency)

    /** One notification-dialog intent maps to the derived show/showName pair. */
    fun applyNotificationSetting(show: Boolean, showName: Boolean) =
        localSettingManager.applyNotificationSetting(show, showName)

    /** One grid-dialog confirm updates all five page columns atomically. */
    fun applyGridColumns(home: Int, collect: Int, download: Int, history: Int, search: Int) =
        localSettingManager.applyGridColumns(home, collect, download, history, search)

    fun updateHomeExcludedTags(tags: List<String>) =
        localSettingManager.updateHomeExcludedTags(tags)

    companion object {
        private val AVAILABLE_API_SET = AVAILABLE_APIS.toSet()
        private val AVAILABLE_THEME_SET = AVAILABLE_THEMES.toSet()
    }
}
