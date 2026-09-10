package com.par9uet.jm.di

import com.par9uet.jm.data.comic.ComicEmbeddedDataSource
import com.par9uet.jm.data.comic.EmbeddedComicDataSource
import com.par9uet.jm.data.comic.NetworkHomeDataSource
import com.par9uet.jm.data.comic.RetrofitNetworkHomeDataSource
import com.par9uet.jm.favorites.usecase.MoveFavorites
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.repository.impl.AuthenticatedEmbeddedClient
import com.par9uet.jm.repository.impl.ComicRepositoryImpl
import com.par9uet.jm.repository.impl.EmbeddedClientManager
import com.par9uet.jm.reader.ReaderImagePipeline
import com.par9uet.jm.ui.viewModel.ComicDetailViewModel
import com.par9uet.jm.ui.viewModel.ComicReadViewModel
import com.par9uet.jm.ui.viewModel.HomeViewModel
import com.par9uet.jm.ui.viewModel.SearchViewModel
import com.par9uet.jm.ui.viewModel.WeekViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

val comicModule = module {
    single { EmbeddedClientManager(get(), get()) }
    single { AuthenticatedEmbeddedClient(get(), get()) }
    single { RetrofitNetworkHomeDataSource(get()) } bind NetworkHomeDataSource::class
    single { EmbeddedComicDataSource(get(), get()) } bind ComicEmbeddedDataSource::class
    single { ComicRepositoryImpl(get(), get()) } bind ComicRepository::class
    single<com.par9uet.jm.reader.atom.LocalChapterFiles> { com.par9uet.jm.reader.atom.DeviceLocalChapterFiles(get()) }
    single { com.par9uet.jm.reader.molecule.LoadLocalChapter(get(), get()) }
    single { ReaderImagePipeline(get(), get(), get(), get()) }

    viewModel { HomeViewModel(get(), get()) }
    viewModel { SearchViewModel(get(), get()) }
    viewModel { WeekViewModel(get(), get()) }
    viewModel { ComicDetailViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { ComicReadViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
