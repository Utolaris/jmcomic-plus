package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.data.models.AVAILABLE_APIS
import com.par9uet.jm.data.models.AVAILABLE_THEMES
import com.par9uet.jm.favorites.sync.FavoriteSyncRequestKind
import com.par9uet.jm.favorites.sync.FavoriteSyncRequester
import com.par9uet.jm.store.AppLockState
import com.par9uet.jm.store.CacheNotificationSetting
import com.par9uet.jm.store.LocalSettingManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Control owner for the Settings screen. The UI sends intents and renders state; every
 * business invariant (atomic compound writes, catalog validation) lives here or below in
 * LocalSettingManager.
 */
class SettingsViewModel(
    private val localSettingManager: LocalSettingManager,
    private val favoriteSyncRequester: FavoriteSyncRequester,
) : ViewModel() {
    val appLock: StateFlow<AppLockState> = localSettingManager.appLock
    val cacheNotification = localSettingManager.cacheNotification

    val favoriteSyncState: StateFlow<com.par9uet.jm.favorites.model.FavoriteSyncUiState> =
        favoriteSyncRequester.state
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), favoriteSyncRequester.state.value)

    fun requestFavoriteForceRefresh() = viewModelScope.launch {
        // Narrow maintenance capability; Settings never touches FavoritesViewModel.
        if (!favoriteSyncRequester.state.value.isSyncing) {
            favoriteSyncRequester.request(FavoriteSyncRequestKind.FORCE)
        }
    }

    fun setPreferenceRecommendEnabled(enabled: Boolean) =
        localSettingManager.setPreferenceRecommendEnabled(enabled)

    fun setAutoSignInEnabled(enabled: Boolean) =
        localSettingManager.updateAutoSignInEnabled(enabled)

    fun setClipboardAutoDetectEnabled(enabled: Boolean) =
        localSettingManager.updateClipboardAutoDetectEnabled(enabled)

    fun setMemoryOptEnabled(enabled: Boolean) = localSettingManager.setMemoryOptEnabled(enabled)

    fun selectApi(url: String) {
        require(url in AVAILABLE_APIS_SET) { "未知 API 节点" }
        localSettingManager.setApiEndpoint(url)
    }

    fun selectTheme(theme: String) {

        require(theme in AVAILABLE_THEMES_SET) { "未知主题" }
        localSettingManager.applyTheme(theme)
    }

    fun selectLauncherDisguise(id: String) = localSettingManager.updateLauncherDisguise(id)

    fun setPrefetchCount(count: Int) = localSettingManager.setPrefetchCount(count)

    fun setReadMode(mode: String) = localSettingManager.setReadMode(mode)

    fun setDecodeConcurrency(concurrency: Int) =
        localSettingManager.setDecodeConcurrency(concurrency)

    /** One intent from the notification dialog maps to the derived show/showName pair. */
    fun applyNotificationSetting(show: Boolean, showName: Boolean) =
        localSettingManager.applyNotificationSetting(show, showName)

    /** One confirm on the grid dialog updates all five page columns atomically. */
    fun applyGridColumns(home: Int, collect: Int, download: Int, history: Int, search: Int) =
        localSettingManager.applyGridColumns(home, collect, download, history, search)

    fun updateHomeExcludedTags(tags: List<String>) =
        localSettingManager.updateHomeExcludedTags(tags)

    companion object {
        private val AVAILABLE_APIS_SET = AVAILABLE_APIS.toSet()
        private val AVAILABLE_THEMES_SET = AVAILABLE_THEMES.toSet()
    }
}
