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
import com.par9uet.jm.store.AppUpdateDownloadManager
import com.par9uet.jm.store.RemoteConfigManager
import com.par9uet.jm.store.DownloadToastAggregator
import com.par9uet.jm.store.HistorySearchManager
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.PostStartupInitializer
import com.par9uet.jm.store.ReadHistoryManager
import com.par9uet.jm.store.SessionReadinessHolder
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.network.DohManager
import com.par9uet.jm.utils.LauncherDisguiseApplier
import com.par9uet.jm.utils.log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import org.koin.core.context.GlobalContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

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
    single { LauncherDisguiseApplier(get()) }
    single {
        JmImageHostHealthManager(
            context = get(),
            scope = get(),
            configuredHostFlow = get<RemoteConfigManager>().remoteImageHost,
        )
    }
    single { CoverImageHostResolver(get<JmImageHostHealthManager>()) }

    single { RemoteSettingRepositoryImpl(get()) } bind RemoteSettingRepository::class

    single { SessionReadinessHolder() }
    single { UserManager(get(), get(), get(), get(), get()) }
    single { RemoteConfigManager(get(), get()) }
    single { LocalSettingManager(get<LocalSettingStorage>(), get()) }
    single { HistorySearchManager(get()) }
    single { ReadHistoryManager(get()) }
    single { ToastManager() }
    single { DownloadToastAggregator(get()) }
    single { PostStartupInitializer(get(), GlobalContext.get()) }
    single { AppUpdateDownloadManager(get(), get(), get(), get()) }
    viewModel { com.par9uet.jm.ui.viewModel.SettingsViewModel(get(), get()) }

    single<Gson> { GsonBuilder().setStrictness(Strictness.LENIENT).serializeNulls().create() }
}
