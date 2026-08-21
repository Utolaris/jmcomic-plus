package com.par9uet.jm.di

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import com.par9uet.jm.repository.RemoteSettingRepository
import com.par9uet.jm.repository.AiChatRepository
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.repository.impl.RemoteSettingRepositoryImpl
import com.par9uet.jm.storage.AiChatStorage
import com.par9uet.jm.storage.CookieStorage
import com.par9uet.jm.storage.HistorySearchStorage
import com.par9uet.jm.storage.LocalSettingStorage
import com.par9uet.jm.storage.PersonaStorage
import com.par9uet.jm.storage.ReadHistoryStorage
import com.par9uet.jm.storage.SecureStorage
import com.par9uet.jm.storage.UserStorage
import com.par9uet.jm.store.AppUpdateDownloadManager
import com.par9uet.jm.store.DownloadToastAggregator
import com.par9uet.jm.store.HistorySearchManager
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.PostStartupInitializer
import com.par9uet.jm.store.ReadHistoryManager
import com.par9uet.jm.store.RemoteSettingManager
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.ui.viewModel.AiChatViewModel
import com.par9uet.jm.ui.viewModel.PersonaViewModel
import com.par9uet.jm.utils.LauncherDisguiseApplier
import com.par9uet.jm.utils.log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.GlobalContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

val appModule = module {
    single {
        CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            log("全局协程捕获到了异常: $throwable")
        })
    }

    single { SecureStorage(get()) }
    single { UserStorage(get()) }
    single { CookieStorage(get()) }
    single { LocalSettingStorage(get()) }
    single { HistorySearchStorage(get()) }
    single { ReadHistoryStorage(get()) }
    single { AiChatStorage(get()) }
    single { PersonaStorage(get()) }
    single { LauncherDisguiseApplier(get()) }
    single { CoverImageHostResolver() }

    single { RemoteSettingRepositoryImpl(get()) } bind RemoteSettingRepository::class
    single { AiChatRepository(get()) }

    single { UserManager(get(), get(), get(), get()) }
    single { RemoteSettingManager(get(), get()) }
    single { LocalSettingManager(get(), get()) }
    single { HistorySearchManager(get()) }
    single { ReadHistoryManager(get()) }
    single { ToastManager() }
    single { DownloadToastAggregator(get()) }
    single { PostStartupInitializer(get(), GlobalContext.get()) }
    single { AppUpdateDownloadManager(get(), get(), get()) }

    single<Gson> { GsonBuilder().setStrictness(Strictness.LENIENT).serializeNulls().create() }

    viewModel { AiChatViewModel(get(), get(), get()) }
    viewModel { PersonaViewModel(get()) }
}
