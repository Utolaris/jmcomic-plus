package com.par9uet.jm.di

import com.par9uet.jm.coil.createAsyncImageLoader
import com.par9uet.jm.network.DohManager
import org.koin.dsl.module

val coilModule = module {
    single { createAsyncImageLoader(get(), get()) }
}
