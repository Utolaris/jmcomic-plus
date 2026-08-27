package com.par9uet.jm.di

import com.par9uet.jm.favorites.data.EmbeddedFavoriteRemoteMutation
import com.par9uet.jm.favorites.data.EmbeddedFavoriteRemoteQuery
import com.par9uet.jm.favorites.data.DownloadManagerFavoriteDownloader
import com.par9uet.jm.favorites.data.FavoriteDownloader
import com.par9uet.jm.favorites.data.FavoriteLocalMutation
import com.par9uet.jm.favorites.data.FavoriteLocalQuery
import com.par9uet.jm.favorites.data.FavoriteLocalSync
import com.par9uet.jm.favorites.data.FavoriteRemoteMutation
import com.par9uet.jm.favorites.data.FavoriteRemoteQuery
import com.par9uet.jm.favorites.data.FavoriteSession
import com.par9uet.jm.favorites.data.RoomFavoriteLocalData
import com.par9uet.jm.favorites.data.UserManagerFavoriteSession
import com.par9uet.jm.favorites.presentation.FavoritesViewModel
import com.par9uet.jm.favorites.sync.FavoriteSyncController
import com.par9uet.jm.favorites.sync.FavoriteSyncRequester
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
    single<FavoriteSession> { UserManagerFavoriteSession(get()) }
    single<FavoriteDownloader> { DownloadManagerFavoriteDownloader(get()) }
    single { EmbeddedFavoriteRemoteMutation(get()) } bind FavoriteRemoteMutation::class
    single { EmbeddedFavoriteRemoteQuery(get()) } bind FavoriteRemoteQuery::class

    single { UncollectFavorites(get(), get(), get()) }
    single { MoveFavorites(get(), get(), get()) }
    single { CreateFavoriteFolder(get(), get()) }
    single { DeleteFavoriteFolder(get(), get(), get()) }
    single { RenameFavoriteFolder(get(), get(), get()) }
    single { DownloadSelectedFavorites(get(), get()) }
    single { SyncFavorites(get(), get(), get(), get()) }
    single { FavoriteSyncController(get(), get(), get()) }
    single<FavoriteSyncRequester> { get<FavoriteSyncController>() }

    viewModel {
        FavoritesViewModel(
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
        )
    }
}
