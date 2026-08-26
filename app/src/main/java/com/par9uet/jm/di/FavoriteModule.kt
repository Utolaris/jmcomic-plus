package com.par9uet.jm.di

import com.par9uet.jm.favorites.data.EmbeddedFavoriteRemoteMutation
import com.par9uet.jm.favorites.data.EmbeddedFavoriteRemoteQuery
import com.par9uet.jm.favorites.data.FavoriteLocalMutation
import com.par9uet.jm.favorites.data.FavoriteLocalQuery
import com.par9uet.jm.favorites.data.FavoriteLocalSync
import com.par9uet.jm.favorites.data.FavoriteRemoteMutation
import com.par9uet.jm.favorites.data.FavoriteRemoteQuery
import com.par9uet.jm.favorites.data.RoomFavoriteLocalData
import com.par9uet.jm.favorites.presentation.FavoritesViewModel
import com.par9uet.jm.favorites.sync.FavoriteSyncController
import com.par9uet.jm.favorites.usecase.CreateFavoriteFolder
import com.par9uet.jm.favorites.usecase.DeleteFavoriteFolder
import com.par9uet.jm.favorites.usecase.DownloadSelectedFavorites
import com.par9uet.jm.favorites.usecase.MoveFavorites
import com.par9uet.jm.favorites.usecase.RenameFavoriteFolder
import com.par9uet.jm.favorites.usecase.SyncFavorites
import com.par9uet.jm.favorites.usecase.UncollectFavorites
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

val favoriteModule = module {
    single { RoomFavoriteLocalData(get()) }
    single<FavoriteLocalQuery> { get<RoomFavoriteLocalData>() }
    single<FavoriteLocalMutation> { get<RoomFavoriteLocalData>() }
    single<FavoriteLocalSync> { get<RoomFavoriteLocalData>() }
    single { EmbeddedFavoriteRemoteMutation(get()) } bind FavoriteRemoteMutation::class
    single { EmbeddedFavoriteRemoteQuery(get()) } bind FavoriteRemoteQuery::class

    single { UncollectFavorites(get(), get()) }
    single { MoveFavorites(get(), get()) }
    single { CreateFavoriteFolder(get()) }
    single { DeleteFavoriteFolder(get(), get()) }
    single { RenameFavoriteFolder(get(), get()) }
    single { DownloadSelectedFavorites(get(), get()) }
    single { SyncFavorites(get(), get(), get(), get()) }
    single { FavoriteSyncController(get(), get(), get()) }

    viewModel {
        FavoritesViewModel(
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
        )
    }
}
