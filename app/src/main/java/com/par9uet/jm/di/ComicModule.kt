package com.par9uet.jm.di

import com.par9uet.jm.data.comic.ComicEmbeddedDataSource
import com.par9uet.jm.data.comic.ComicNetworkDataSource
import com.par9uet.jm.data.comic.EmbeddedComicDataSource
import com.par9uet.jm.data.comic.NetworkComicDataSource
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.repository.impl.ComicRepositoryImpl
import com.par9uet.jm.repository.impl.EmbeddedClientManager
import com.par9uet.jm.reader.ReaderImagePipeline
import com.par9uet.jm.ui.viewModel.ComicDetailViewModel
import com.par9uet.jm.ui.viewModel.ComicReadViewModel
import com.par9uet.jm.ui.viewModel.ComicViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

val comicModule = module {
    single { EmbeddedClientManager(get()) }
    single { NetworkComicDataSource(get()) } bind ComicNetworkDataSource::class
    single { EmbeddedComicDataSource(get()) } bind ComicEmbeddedDataSource::class
    single { ComicRepositoryImpl(get(), get()) } bind ComicRepository::class
    single { ReaderImagePipeline(get(), get(), get()) }

    viewModel { ComicViewModel(get(), get()) }
    viewModel { ComicDetailViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { ComicReadViewModel(get(), get(), get(), get(), get(), get()) }
}
