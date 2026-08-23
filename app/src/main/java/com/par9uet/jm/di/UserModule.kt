package com.par9uet.jm.di

import com.par9uet.jm.repository.UserRepository
import com.par9uet.jm.repository.impl.FavoriteSyncService
import com.par9uet.jm.repository.impl.UserRepositoryImpl
import com.par9uet.jm.ui.viewModel.UserViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

val userModule = module {
    single {
        FavoriteSyncService(
            get(), get(), get(), get(), get(), get(), get(), get(), get(),
        )
    }
    single { UserRepositoryImpl(get(), get(), get(), get(), get(), get(), get(), get()) } bind UserRepository::class

    viewModel { UserViewModel(get(), get(), get(), get(), get(), get(), get()) }
}
