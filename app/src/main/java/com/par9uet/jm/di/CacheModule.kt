package com.par9uet.jm.di

import com.par9uet.jm.cache.migration.CacheMigrationCoordinator
import com.par9uet.jm.cache.migration.CacheMigrationDownloadGate
import com.par9uet.jm.cache.migration.CacheMigrationOperations
import com.par9uet.jm.cache.migration.CacheMigrationScheduler
import com.par9uet.jm.cache.migration.DeviceCacheMigrationOperations
import com.par9uet.jm.download.coordinator.DownloadComicCoordinator
import com.par9uet.jm.ui.viewModel.CachePathViewModel
import com.par9uet.jm.worker.CacheMigrationWorker
import com.par9uet.jm.worker.WorkManagerCacheMigrationScheduler
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.worker
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * 缓存领域的组合根。迁移协调器通过窄回调拿到"等下载空闲"的能力，
 * 这样 `cache/migration` 不需要认识下载协调器的具体类型。
 */
val cacheModule = module {
    single<CacheMigrationOperations> { DeviceCacheMigrationOperations(androidContext(), get(), get()) }
    single<CacheMigrationDownloadGate> {
        val downloads = get<DownloadComicCoordinator>()
        object : CacheMigrationDownloadGate {
            override suspend fun <T> withIdleDownloads(block: suspend () -> T): T =
                downloads.withIdleDownloads(block)
        }
    }
    single { CacheMigrationCoordinator(get(), get()) }
    single<CacheMigrationScheduler> { WorkManagerCacheMigrationScheduler(androidContext()) }
    worker { CacheMigrationWorker(get(), get(), get()) }
    viewModel { CachePathViewModel(androidApplication(), get()) }
}
