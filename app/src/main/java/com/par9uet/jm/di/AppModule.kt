package com.par9uet.jm.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import com.par9uet.jm.repository.RemoteSettingRepository
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.image.JmImageHostHealthManager
import com.par9uet.jm.repository.impl.RemoteSettingRepositoryImpl
import com.par9uet.jm.storage.CookieStorage
import com.par9uet.jm.storage.HistorySearchStorage
import com.par9uet.jm.storage.LocalSettingStorage
import com.par9uet.jm.storage.ReadHistoryStorage
import com.par9uet.jm.storage.SecureCookieStorage
import com.par9uet.jm.storage.SecureStorage
import com.par9uet.jm.storage.SecureUserStorage
import com.par9uet.jm.storage.UserStorage
import com.par9uet.jm.startup.PostStartupCoordinator
import com.par9uet.jm.store.ApiEndpointPreference
import com.par9uet.jm.store.AppExperiencePreferences
import com.par9uet.jm.store.AppSecurityEditor
import com.par9uet.jm.store.AppSecurityPreferences
import com.par9uet.jm.store.AppUpdateDownloadManager
import com.par9uet.jm.store.AppearanceEditor
import com.par9uet.jm.store.AppearancePreferences
import com.par9uet.jm.store.CacheNotificationPreferences
import com.par9uet.jm.store.BlockedTagTemplatePreferences
import com.par9uet.jm.store.ContentPreferences
import com.par9uet.jm.store.DohPreferences
import com.par9uet.jm.store.DohPreferencesEditor
import com.par9uet.jm.store.ReaderPreferences
import com.par9uet.jm.store.RecommendationPreferences
import com.par9uet.jm.store.RemoteConfigPreferences
import com.par9uet.jm.store.RemoteConfigManager
import com.par9uet.jm.store.DownloadToastAggregator
import com.par9uet.jm.store.HistorySearchManager
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.LocalSettingSnapshotProvider
import com.par9uet.jm.store.MiscSettingsPreferences
import com.par9uet.jm.store.ReadHistoryManager
import com.par9uet.jm.store.SessionReadinessHolder
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.network.DohManager
import com.par9uet.jm.launcher.LauncherDisguiseApplier
import com.par9uet.jm.launcher.LauncherIdentityApplier
import com.par9uet.jm.utils.log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import org.koin.core.context.GlobalContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Single source of truth for LocalSettingManager's interface aliases; the Koin wiring smoke test
 * binds the same list so tests cannot drift from production wiring.
 */
val LOCAL_SETTING_MANAGER_ALIASES = arrayOf(
    ContentPreferences::class,
    BlockedTagTemplatePreferences::class,
    RecommendationPreferences::class,
    ReaderPreferences::class,
    CacheNotificationPreferences::class,
    AppSecurityPreferences::class,
    AppSecurityEditor::class,
    DohPreferences::class,
    DohPreferencesEditor::class,
    AppearancePreferences::class,
    AppearanceEditor::class,
    ApiEndpointPreference::class,
    MiscSettingsPreferences::class,
    AppExperiencePreferences::class,
    LocalSettingSnapshotProvider::class,
)

val appModule = module {
    single { DohManager(get(), get()) }

    single {
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            log("全局协程捕获到了异常: $throwable")
        })
    }

    single { SecureStorage(get()) }
    single { SecureUserStorage(get()) } bind UserStorage::class
    single { SecureCookieStorage(get()) } bind CookieStorage::class
    single { LocalSettingStorage(get()) }
    single { HistorySearchStorage(get()) }
    single { ReadHistoryStorage(get()) }
    single { LauncherDisguiseApplier(get()) } bind LauncherIdentityApplier::class
    single {
        JmImageHostHealthManager(
            context = get(),
            scope = get(),
            configuredHostFlow = get<RemoteConfigPreferences>().remoteImageHost,
        )
    }
    single { CoverImageHostResolver(get<JmImageHostHealthManager>()) }

    single { RemoteSettingRepositoryImpl(get()) } bind RemoteSettingRepository::class

    single { SessionReadinessHolder() }
    single { UserManager(get(), get(), get(), get(), get()) }
    single { com.par9uet.jm.store.SecureRemoteConfigStore(get()) } bind com.par9uet.jm.store.RemoteConfigStore::class
    single { RemoteConfigManager(get(), get()) } bind RemoteConfigPreferences::class
    // All interface aliases resolve to the same LocalSettingManager singleton.
    single { LocalSettingManager(get<LocalSettingStorage>(), get()) } binds LOCAL_SETTING_MANAGER_ALIASES
    single { HistorySearchManager(get()) }
    single { ReadHistoryManager(get()) }
    single { ToastManager() }
    single { DownloadToastAggregator(get()) }
    single { PostStartupCoordinator(get(), GlobalContext.get()) }
    single { AppUpdateDownloadManager(get(), get(), get(), get()) }
    viewModel { com.par9uet.jm.ui.viewModel.SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }

    single<Gson> { GsonBuilder().setStrictness(Strictness.LENIENT).serializeNulls().create() }
}
