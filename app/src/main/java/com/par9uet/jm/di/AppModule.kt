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
import com.par9uet.jm.storage.ApiEndpointPreference
import com.par9uet.jm.storage.AppExperiencePreferences
import com.par9uet.jm.storage.AppSecurityEditor
import com.par9uet.jm.storage.AppSecurityPreferences
import com.par9uet.jm.update.AppUpdateDownloadManager
import com.par9uet.jm.storage.AppearanceEditor
import com.par9uet.jm.storage.AppearancePreferences
import com.par9uet.jm.storage.CacheNotificationPreferences
import com.par9uet.jm.storage.BlockedTagTemplatePreferences
import com.par9uet.jm.storage.ContentPreferences
import com.par9uet.jm.storage.DohPreferences
import com.par9uet.jm.storage.DohPreferencesEditor
import com.par9uet.jm.storage.ReaderPreferences
import com.par9uet.jm.storage.RecommendationPreferences
import com.par9uet.jm.storage.RemoteConfigPreferences
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.network.RemoteConfigManager
import com.par9uet.jm.download.coordinator.DownloadToastAggregator
import com.par9uet.jm.storage.HistorySearchManager
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.storage.LocalSettingSnapshotProvider
import com.par9uet.jm.storage.MiscSettingsPreferences
import com.par9uet.jm.storage.ReadHistoryManager
import com.par9uet.jm.storage.ReaderResumeManager
import com.par9uet.jm.session.SessionReadinessHolder
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.session.UserManager
import com.par9uet.jm.network.DohManager
import com.par9uet.jm.launcher.LauncherDisguiseApplier
import com.par9uet.jm.launcher.LauncherIdentityApplier
import com.par9uet.jm.utils.log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
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

/**
 * Inventory of every app-owned OkHttpClient construction. New clients MUST be added here
 * (and must set [DohManager] as DNS) — previous audits each found one previously-unlisted
 * bypass that still used system DNS. The OkHttpClient inventory test asserts every
 * construction site appears in this table.
 *
 * | Site (file) | DNS | Cookies | Notes |
 * |---|---|---|---|
 * | retrofit/Retrofit.kt | injected DohManager | NO_COOKIES | promote/settings API |
 * | data/comic/EmbeddedComicDataSource.kt | dohManager | default | image fallback |
 * | reader/ReaderImagePipeline.kt | dohManager | default | reader pages |
 * | coil/Config.kt | dohManager | default | cover loader |
 * | update/AppUpdateDownloadManager.kt | dohManager | default | APK download |
 * | di/AppModule.kt GithubReleaseSource | DohManager | default | release metadata |
 * | di/AppModule.kt JmImageHostHealthManager baseHttpClient | DohManager | NO_COOKIES | CDN HEAD probe |
 * | network/DohManager.kt DohResolver bootstrap | bootstrapDns | default | **intentional system-DNS exception** for resolving the DoH server itself |
 * | update/GithubReleaseSource.kt default parameter | bare (tests only) | default | production always injects the AppModule client |
 * | image/JmImageHostHealthManager.kt default parameter | bare (tests only) | default | production always injects the AppModule client |
 *
 * System DNS is therefore only used by the DoH bootstrap path listed above.
 */
internal fun createSharedCookielessDohClient(dns: Dns): OkHttpClient = OkHttpClient.Builder()
    .dns(dns)
    .cookieJar(CookieJar.NO_COOKIES)
    .build()

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
            // Probes must share the app-wide DoH resolver; a bare OkHttpClient would
            // leak CDN hostnames through system DNS on every init/network change.
            baseHttpClient = createSharedCookielessDohClient(get<DohManager>()),
        )
    }
    single { CoverImageHostResolver(get<JmImageHostHealthManager>()) }

    single { RemoteSettingRepositoryImpl(get()) } bind RemoteSettingRepository::class

    single { SessionReadinessHolder() }
    single { UserManager(get(), get(), get(), get()) }
    single { com.par9uet.jm.network.SecureRemoteConfigStore(get()) } bind com.par9uet.jm.network.RemoteConfigStore::class
    single {
        val remoteSettingRepository = get<RemoteSettingRepository>()
        RemoteConfigManager(
            remoteSettingFetch = com.par9uet.jm.network.RemoteSettingFetch {
                // Map on this side of the port so network never imports retrofit.
                when (val result = remoteSettingRepository.getRemoteSetting()) {
                    is NetWorkResult.Success ->
                        NetWorkResult.Success(result.data.toRemoteSetting())
                    is NetWorkResult.Error -> result
                }
            },
            store = get(),
        )
    } bind RemoteConfigPreferences::class
    // All interface aliases resolve to the same LocalSettingManager singleton.
    single { LocalSettingManager(get<LocalSettingStorage>(), get()) } binds LOCAL_SETTING_MANAGER_ALIASES
    single { HistorySearchManager(get()) }
    single { ReadHistoryManager(get()) }
    single { ReaderResumeManager(secureStorage = get()) }
    single { ToastManager() }
    single<com.par9uet.jm.cache.atom.CacheFiles> {
        com.par9uet.jm.cache.atom.DeviceCacheFiles(get<android.content.Context>().cacheDir)
    }
    viewModel {
        val reader = get<com.par9uet.jm.reader.ReaderImagePipeline>()
        val downloads = get<com.par9uet.jm.download.coordinator.DownloadManager>()
        com.par9uet.jm.ui.viewModel.CacheCleanupViewModel(get(), reader::clearDiskCache, downloads::clearDownloadedCache)
    }
    single { DownloadToastAggregator(get()) }
    single { PostStartupCoordinator(get(), GlobalContext.get()) }
    single { AppUpdateDownloadManager(get(), get(), get(), get()) } bind com.par9uet.jm.update.AppUpdateDownloads::class
    single {
        com.par9uet.jm.update.GithubReleaseSource(
            createSharedCookielessDohClient(get<DohManager>()),
        )
    } bind com.par9uet.jm.update.ReleaseSource::class
    single { com.par9uet.jm.update.ApkInstaller(get()) } bind com.par9uet.jm.update.AppUpdateInstaller::class
    viewModel { com.par9uet.jm.ui.viewModel.AppUpdateViewModel(get(), get(), get(), get()) }
    single { com.par9uet.jm.backup.BackupManager() }
    single<com.par9uet.jm.backup.BackupTaskScheduler> {
        val downloadManager = get<com.par9uet.jm.download.coordinator.DownloadManager>()
        object : com.par9uet.jm.backup.BackupTaskScheduler {
            override fun downloadComic(comic: com.par9uet.jm.data.models.Comic) {
                downloadManager.downloadComic(comic)
            }

            override fun downloadChapters(
                parentComic: com.par9uet.jm.data.models.Comic,
                chapters: List<com.par9uet.jm.data.models.ComicChapter>,
            ) {
                downloadManager.downloadChapters(parentComic, chapters)
            }
        }
    }
    single<com.par9uet.jm.backup.BackupRestoreOperations> {
        com.par9uet.jm.backup.DeviceBackupRestoreOperations(get(), get(), get(), get(), get())
    }
    viewModel { com.par9uet.jm.ui.viewModel.BackupRestoreViewModel(get(), get(), get()) }
    viewModel { com.par9uet.jm.ui.viewModel.SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }

    single<Gson> { GsonBuilder().setStrictness(Strictness.LENIENT).serializeNulls().create() }
}
