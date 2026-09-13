package com.par9uet.jm.storage
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_BOTH
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_PASSWORD
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_PATTERN
import com.par9uet.jm.data.models.BlockedTagTemplate
import com.par9uet.jm.data.models.COLOR_PALETTE_PRESET_CUSTOM
import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.storage.LocalSettingPersistence
import com.par9uet.jm.contentfilter.flattenBlockedTagTemplates
import com.par9uet.jm.launcher.LauncherIdentityApplier
import com.par9uet.jm.utils.log
import com.par9uet.jm.contentfilter.normalizeBlockedTagList
import com.par9uet.jm.contentfilter.normalizeBlockedTagTemplates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single writer for [LocalSetting]. The DTO stays the one persistence model (one encrypted
 * JSON document, existing migrations and backup compatibility); features consume the narrow
 * projections below instead of observing the whole persistence object.
 */
class LocalSettingManager(
    private val persistence: LocalSettingPersistence,
    private val launcherDisguiseApplier: LauncherIdentityApplier,
) : ContentPreferences,
    BlockedTagTemplatePreferences,
    RecommendationPreferences,
    ReaderPreferences,
    AppExperiencePreferences,
    LocalSettingSnapshotProvider,
    CacheNotificationPreferences,
    MiscSettingsPreferences,
    AppSecurityPreferences,
    AppSecurityEditor,
    DohPreferences,
    DohPreferencesEditor,
    AppearancePreferences,
    AppearanceEditor,
    ApiEndpointPreference {
    private val _localSettingState = MutableStateFlow(LocalSetting())
    private val _securityLoadBlocked = MutableStateFlow(false)
    val securityLoadBlocked = _securityLoadBlocked.asStateFlow()
    private val updateListeners = mutableListOf<(LocalSetting) -> Unit>()

    override val blockedTags = _projectingState { it.blockedTagList }
    override val blockedTagTemplates = _projectingState { it.blockedTagTemplateList }
    override val homeExcludedTags = _projectingState { it.homeExcludedTags }
    override val preferenceRecommendEnabled = _projectingState { it.preferenceRecommendEnabled }
    override val readMode = _projectingState { it.readMode }
    override val readTapMode = _projectingState { it.readTapMode }
    override val prefetchCount = _projectingState { it.prefetchCount }
    override val memoryOptEnabled = _projectingState { it.readMemoryOptEnabled }
    override val decodeConcurrency = _projectingState { it.readDecodeConcurrency }
    override val onboardingCompleted = _projectingState { it.onboardingCompleted }
    override val nsfwWarningDismissed = _projectingState { it.nsfwWarningDismissed }
    override val cacheNotification = _projectingState(::toCacheNotificationSetting)
    override val misc = _projectingState(::toMiscSettingsState)
    override val appLock = _projectingState(::toAppLockState)
    override val doh = _projectingState(::toDohSettingsState)

    override val theme = _projectingState { it.theme }
    override val launcherDisguiseId = _projectingState { it.launcherDisguise }
    override val colorPalette = _projectingState(::toColorPaletteState)
    override val editor: AppearanceEditor get() = this
    override val apiEndpoint = _projectingState { it.api }

    private fun <T> _projectingState(selector: (LocalSetting) -> T): MutableStateFlow<T> {
        val flow = MutableStateFlow(selector(_localSettingState.value))
        updateListeners += { flow.value = selector(it) }
        return flow
    }

    init {
        ensureLoaded()
    }

    /** Re-reads after a temporary Keystore/storage outage. Safe to call from UI retry. */
    fun reloadSettings() {
        ensureLoaded()
    }

    // ---- Simple single-field editors (each maps to exactly one persistence field; compound
    // business actions live on the atomic mutators so UI never sequences multiple writes). ----

    fun updateOnboardingCompleted(completed: Boolean) =
        updateSetting { it.copy(onboardingCompleted = completed) }

    fun updateClipboardAutoDetectEnabled(enabled: Boolean) =
        updateSetting { it.copy(clipboardAutoDetectEnabled = enabled) }

    fun updateAutoSignInEnabled(enabled: Boolean) =
        updateSetting { it.copy(autoSignInEnabled = enabled) }
    fun setPreferenceRecommendEnabled(enabled: Boolean) =
        updateSetting { it.copy(preferenceRecommendEnabled = enabled) }
    fun setApiEndpoint(url: String) = updateSetting { it.copy(api = url) }

    fun updateLauncherDisguise(launcherDisguise: String) {
        val disguise = LauncherDisguise.fromId(launcherDisguise)
        // Switch the alias first: the stored value has to describe the entry that is actually
        // installed, so a failed switch keeps both the current entry and the current setting.
        if (!launcherDisguiseApplier.apply(disguise)) {
            log("桌面图标入口未切换，保留当前入口：${disguise.id}")
            return
        }
        updateSetting { it.copy(launcherDisguise = disguise.id) }
    }

    fun dismissNsfwWarning() =
        updateSetting { it.copy(nsfwWarningDismissed = true) }

    fun applyTheme(theme: String) =
        updateSetting { it.copy(theme = theme) }

    fun setPrefetchCount(count: Int) =
        updateSetting { it.copy(prefetchCount = count.coerceIn(0, 6)) }

    fun setReadMode(mode: String) =
        updateSetting { it.copy(readMode = mode) }

    fun setDecodeConcurrency(concurrency: Int) =
        updateSetting { it.copy(readDecodeConcurrency = concurrency.coerceIn(1, 4)) }

    fun setMemoryOptEnabled(enabled: Boolean) =
        updateSetting { it.copy(readMemoryOptEnabled = enabled) }

    // ---- Content preferences (blocked tags / templates / home exclusions) ----

    fun replaceBlockedTags(tags: List<String>) =
        updateSetting { it.copy(blockedTagList = normalizeBlockedTagList(tags)) }

    fun saveBlockedTagTemplate(index: Int?, name: String, tags: List<String>) {
        val normalizedTags = normalizeBlockedTagList(tags)
        if (normalizedTags.isEmpty()) return
        val template = BlockedTagTemplate(
            name = name.trim().ifBlank { "排除模板" },
            tagList = normalizedTags
        )
        updateSetting { setting ->
            val mutable = setting.blockedTagTemplateList.toMutableList()
            if (index != null && index in mutable.indices) {
                mutable[index] = template
            } else {
                mutable += template
            }
            setting.withBlockedTagTemplates(mutable)
        }
    }

    fun removeBlockedTagTemplate(index: Int) =
        updateSetting { setting ->
            if (index !in setting.blockedTagTemplateList.indices) {
                setting
            } else {
                setting.withBlockedTagTemplates(
                    setting.blockedTagTemplateList.filterIndexed { i, _ -> i != index }
                )
            }
        }

    fun replaceBlockedTagTemplates(templates: List<BlockedTagTemplate>) =
        updateSetting { it.withBlockedTagTemplates(templates) }

    fun updateHomeExcludedTags(tags: List<String>) =
        updateSetting { it.copy(homeExcludedTags = tags) }

    // ---- Appearance / palette: compound transitions ----

    /**
     * Selecting a real preset clears any custom color overrides in the same transition. CUSTOM is
     * not a selectable preset: entering it only happens through [applyCustomColors], so this call
     * keeps existing custom values instead of destroying them.
     */
    override fun selectColorPreset(presetId: String) = updateSetting { current ->
        if (presetId == COLOR_PALETTE_PRESET_CUSTOM || presetId == current.colorPalettePreset) {
            current
        } else {
            current.copy(
                colorPalettePreset = presetId,
                customColorPrimary = null,
                customColorSecondary = null,
                customColorTertiary = null,
                customColorError = null,
            )
        }
    }

    /** Confirming a custom color switches the palette to custom in the same transition. */
    override fun applyCustomColors(primary: String?, secondary: String?, tertiary: String?, error: String?) =
        updateSetting {
            val hasAnyCustomColor = primary != null || secondary != null ||
                tertiary != null || error != null
            it.copy(
                colorPalettePreset = if (hasAnyCustomColor) COLOR_PALETTE_PRESET_CUSTOM
                else it.colorPalettePreset,
                customColorPrimary = primary,
                customColorSecondary = secondary,
                customColorTertiary = tertiary,
                customColorError = error,
            )
        }

    /** One confirm on the grid dialog updates all five page columns together. */
    fun applyGridColumns(home: Int, collect: Int, download: Int, history: Int, search: Int) =
        updateSetting {
            it.copy(
                homeGridColumns = home.coerceIn(0, 6),
                collectGridColumns = collect.coerceIn(0, 6),
                downloadGridColumns = download.coerceIn(0, 6),
                historyGridColumns = history.coerceIn(0, 6),
                searchGridColumns = search.coerceIn(0, 6),
            )
        }

    /** One selection from the notification dialog derives show/showName together. */
    fun applyNotificationSetting(show: Boolean, showName: Boolean) = updateSetting {
        // Showing the comic name requires the notification itself to be enabled.
        it.copy(
            showComicCacheNotification = show,
            showComicCacheNotificationName = show && showName,
        )
    }

    /**
     * Applies a backup's [LocalSetting]. Backups strip app-lock credentials/secret fields, so
     * this device keeps its own app lock; identity-change side effects run after the write.
     */
    fun applyLocalSetting(setting: LocalSetting) {
        val previousLauncherDisguise = _localSettingState.value.launcherDisguise
        val requestedLauncherDisguise = LauncherDisguise.fromId(setting.launcherDisguise)
        // Never store a disguise whose alias did not switch, otherwise the next launch would
        // reassert a launcher entry this device never managed to install.
        val launcherDisguise = if (requestedLauncherDisguise.id == previousLauncherDisguise ||
            launcherDisguiseApplier.apply(requestedLauncherDisguise)
        ) {
            requestedLauncherDisguise.id
        } else {
            log("桌面图标入口未切换，保留当前入口：${requestedLauncherDisguise.id}")
            previousLauncherDisguise
        }
        updateSetting { current ->
            setting.copy(
                launcherDisguise = launcherDisguise,
                appLockEnabled = current.appLockEnabled,
                appLockPassword = current.appLockPassword,
                appLockPasswordLength = current.appLockPasswordLength,
                appLockPattern = current.appLockPattern,
                appLockUnlockMode = current.appLockUnlockMode,
            )
        }
    }

    /** PackageManager IPC only affects icons and must stay off the first-screen path. */
    fun applyLauncherDisguiseIfNeeded() {
        launcherDisguiseApplier.apply(LauncherDisguise.fromId(_localSettingState.value.launcherDisguise))
    }

    fun currentAutoSignInEnabled(): Boolean = _localSettingState.value.autoSignInEnabled

    override fun currentLocalSettingSnapshot(): LocalSetting = _localSettingState.value

    // ---- AppSecurityEditor: one transition per user action ----

    override fun setPassword(password: String, length: Int) = updateSecuritySetting {
        it.copy(
            appLockPassword = password,
            appLockPasswordLength = length.coerceIn(4, 8),
            appLockUnlockMode = if (it.appLockPattern.isNotEmpty()) {
                APP_LOCK_UNLOCK_MODE_BOTH
            } else {
                APP_LOCK_UNLOCK_MODE_PASSWORD
            },
        )
    }

    override fun removePassword() = updateSecuritySetting {
        it.copy(
            appLockPassword = "",
            appLockUnlockMode = if (it.appLockPattern.isNotEmpty()) {
                APP_LOCK_UNLOCK_MODE_PATTERN
            } else {
                APP_LOCK_UNLOCK_MODE_PASSWORD
            },
            appLockEnabled = if (it.appLockPattern.isNotEmpty()) it.appLockEnabled else false,
        )
    }

    override fun setPattern(pattern: String) = updateSecuritySetting {
        it.copy(
            appLockPattern = pattern,
            appLockUnlockMode = if (it.appLockPassword.isNotEmpty()) {
                APP_LOCK_UNLOCK_MODE_BOTH
            } else {
                APP_LOCK_UNLOCK_MODE_PATTERN
            },
        )
    }

    override fun removePattern() = updateSecuritySetting {
        it.copy(
            appLockPattern = "",
            appLockUnlockMode = if (it.appLockPassword.isNotEmpty()) {
                APP_LOCK_UNLOCK_MODE_PASSWORD
            } else {
                APP_LOCK_UNLOCK_MODE_PATTERN
            },
            appLockEnabled = if (it.appLockPassword.isNotEmpty()) it.appLockEnabled else false,
        )
    }

    override fun setAppLockEnabled(enabled: Boolean) = updateSecuritySetting {
        // Without at least one credential there is nothing to unlock with; keep the lock off.
        it.copy(
            appLockEnabled = enabled &&
                (it.appLockPassword.isNotEmpty() || it.appLockPattern.isNotEmpty()),
        )
    }

    override fun disableAndClearAppLock() = updateSecuritySetting {
        it.copy(
            appLockEnabled = false,
            appLockPassword = "",
            appLockPattern = "",
            appLockUnlockMode = APP_LOCK_UNLOCK_MODE_PASSWORD,
        )
    }

    override fun selectUnlockMode(mode: String) = updateSecuritySetting {
        // BOTH requires both credentials; a mode without its credential falls back to the
        // one that exists, keeping unlock always possible.
        val validMode = when (mode) {
            APP_LOCK_UNLOCK_MODE_BOTH ->
                if (it.appLockPassword.isNotEmpty() && it.appLockPattern.isNotEmpty()) {
                    APP_LOCK_UNLOCK_MODE_BOTH
                } else if (it.appLockPattern.isNotEmpty()) {
                    APP_LOCK_UNLOCK_MODE_PATTERN
                } else {
                    APP_LOCK_UNLOCK_MODE_PASSWORD
                }
            APP_LOCK_UNLOCK_MODE_PATTERN ->
                if (it.appLockPattern.isNotEmpty()) {
                    APP_LOCK_UNLOCK_MODE_PATTERN
                } else {
                    APP_LOCK_UNLOCK_MODE_PASSWORD
                }
            else ->
                if (it.appLockPassword.isNotEmpty()) {
                    APP_LOCK_UNLOCK_MODE_PASSWORD
                } else {
                    APP_LOCK_UNLOCK_MODE_PATTERN
                }
        }
        it.copy(appLockUnlockMode = validMode)
    }

    // ---- DohPreferencesEditor ----

    override fun persistEnabled(enabled: Boolean) =
        updateSetting { it.copy(dohEnabled = enabled) }

    override fun persistAutoStart(enabled: Boolean) =
        updateSetting { it.copy(dohAutoStart = enabled) }

    override fun persistServer(serverId: String) =
        updateSetting { it.copy(dohServerId = serverId) }

    override fun persistCustomServer(name: String, url: String) = updateSetting {
        it.copy(
            dohServerId = "custom",
            dohCustomServerName = name.trim(),
            dohCustomServerUrl = url.trim(),
        )
    }

    override fun persistUseDeviceCertificates(enabled: Boolean) =
        updateSetting { it.copy(dohUseDeviceCertificates = enabled) }

    override fun persistPreferIpv6(enabled: Boolean) =
        updateSetting { it.copy(dohPreferIpv6 = enabled) }


    private fun publish(setting: LocalSetting) {
        _localSettingState.value = setting
        updateListeners.forEach { listener -> listener(setting) }
    }

    private fun updateSetting(update: (LocalSetting) -> LocalSetting) {
        val next = update(_localSettingState.value)
        publish(next)
        persistence.persist(next)
    }

    /**
     * Security transitions must not look successful when the write failed. Revert to the last
     * persisted snapshot so the UI never shows an enabled lock that restarts disabled.
     */
    private fun updateSecuritySetting(update: (LocalSetting) -> LocalSetting): Boolean {
        val previous = _localSettingState.value
        val next = update(previous)
        publish(next)
        return when (persistence.persist(next)) {
            StorageWriteResult.Success -> true
            StorageWriteResult.TemporaryUnavailable -> {
                publish(previous)
                false
            }
        }
    }

    private fun ensureLoaded() {
        try {
            when (val restored = persistence.load()) {
                is LocalSettingLoadResult.Success -> {
                    _securityLoadBlocked.value = false
                    publish(restored.value)
                }
                is LocalSettingLoadResult.Missing -> {
                    _securityLoadBlocked.value = false
                }
                is LocalSettingLoadResult.TemporaryUnavailable -> {
                    // Fail closed: never treat an unreadable Keystore as “no lock configured”.
                    _securityLoadBlocked.value = true
                    log("本地设置暂时不可读，安全状态未知")
                }
            }
        } catch (error: Throwable) {
            _securityLoadBlocked.value = true
            log("加载本地设置失败：${error.message}")
        }
    }

    private fun toCacheNotificationSetting(setting: LocalSetting) = CacheNotificationSetting(
        show = setting.showComicCacheNotification,
        showName = setting.showComicCacheNotificationName,
    )
    private fun toMiscSettingsState(setting: LocalSetting) = MiscSettingsState(
        clipboardAutoDetectEnabled = setting.clipboardAutoDetectEnabled,
        autoSignInEnabled = setting.autoSignInEnabled,
        gridColumns = GridColumnsSetting(
            home = setting.homeGridColumns,
            collect = setting.collectGridColumns,
            download = setting.downloadGridColumns,
            history = setting.historyGridColumns,
            search = setting.searchGridColumns,
        ),
    )

    private fun toAppLockState(setting: LocalSetting) = AppLockState(
        enabled = setting.appLockEnabled,
        password = setting.appLockPassword,
        passwordLength = setting.appLockPasswordLength,
        pattern = setting.appLockPattern,
        unlockMode = setting.appLockUnlockMode,
    )

    private fun toDohSettingsState(setting: LocalSetting) = DohSettingsState(
        enabled = setting.dohEnabled,
        autoStart = setting.dohAutoStart,
        serverId = setting.dohServerId,
        customServerName = setting.dohCustomServerName,
        customServerUrl = setting.dohCustomServerUrl,
        useDeviceCertificates = setting.dohUseDeviceCertificates,
        preferIpv6 = setting.dohPreferIpv6,
    )

    private fun toColorPaletteState(setting: LocalSetting) = ColorPaletteState(
        presetId = setting.colorPalettePreset,
        customPrimary = setting.customColorPrimary,
        customSecondary = setting.customColorSecondary,
        customTertiary = setting.customColorTertiary,
        customError = setting.customColorError,
    )

    private fun LocalSetting.withBlockedTagTemplates(templates: List<BlockedTagTemplate>): LocalSetting {
        val normalizedTemplates = normalizeBlockedTagTemplates(templates)
        return copy(
            blockedTagTemplateList = normalizedTemplates,
            blockedTagList = flattenBlockedTagTemplates(normalizedTemplates)
        )
    }
}
