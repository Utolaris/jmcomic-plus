package com.par9uet.jm.favorites.usecase

import com.par9uet.jm.favorites.data.FavoriteLocalMutation
import com.par9uet.jm.favorites.data.FavoriteLocalQuery
import com.par9uet.jm.favorites.data.FavoriteRemoteMutation
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.DownloadManager

data class FavoritesBatchResult(
    val succeeded: Int,
    val failed: Int,
)

class UncollectFavorites(
    private val remoteMutation: FavoriteRemoteMutation,
    private val localMutation: FavoriteLocalMutation,
) {
    suspend operator fun invoke(accountId: Int, comicIds: Collection<Int>): FavoritesBatchResult {
        var succeeded = 0
        var failed = 0
        comicIds.distinct().forEach { comicId ->
            when (remoteMutation.uncollectComic(comicId)) {
                is NetWorkResult.Error -> failed++
                is NetWorkResult.Success -> {
                    localMutation.remove(accountId, listOf(comicId))
                    succeeded++
                }
            }
        }
        return FavoritesBatchResult(succeeded = succeeded, failed = failed)
    }
}

class MoveFavorites(
    private val remoteMutation: FavoriteRemoteMutation,
    private val localMutation: FavoriteLocalMutation,
) {
    suspend operator fun invoke(
        accountId: Int,
        comicIds: Collection<Int>,
        folderId: Int,
    ): FavoritesBatchResult {
        var succeeded = 0
        var failed = 0
        comicIds.distinct().forEach { comicId ->
            when (remoteMutation.moveComicToFolder(comicId, folderId)) {
                is NetWorkResult.Error -> failed++
                is NetWorkResult.Success -> {
                    localMutation.moveToFolder(accountId, comicId, folderId)
                    succeeded++
                }
            }
        }
        return FavoritesBatchResult(succeeded = succeeded, failed = failed)
    }
}

class CreateFavoriteFolder(
    private val remoteMutation: FavoriteRemoteMutation,
) {
    suspend operator fun invoke(name: String): NetWorkResult<Unit> =
        remoteMutation.createFolder(name)
}

class DeleteFavoriteFolder(
    private val remoteMutation: FavoriteRemoteMutation,
    private val localMutation: FavoriteLocalMutation,
) {
    suspend operator fun invoke(accountId: Int, folderId: Int): NetWorkResult<Unit> =
        when (val result = remoteMutation.deleteFolder(folderId)) {
            is NetWorkResult.Error -> result
            is NetWorkResult.Success -> {
                localMutation.removeFolder(accountId, folderId)
                result
            }
        }
}

class RenameFavoriteFolder(
    private val remoteMutation: FavoriteRemoteMutation,
    private val localMutation: FavoriteLocalMutation,
) {
    suspend operator fun invoke(
        accountId: Int,
        folderId: Int,
        name: String,
    ): NetWorkResult<Unit> = when (val result = remoteMutation.renameFolder(folderId, name)) {
        is NetWorkResult.Error -> result
        is NetWorkResult.Success -> {
            localMutation.renameFolder(accountId, folderId, name)
            result
        }
    }
}

class DownloadSelectedFavorites(
    private val localQuery: FavoriteLocalQuery,
    private val downloadManager: DownloadManager,
) {
    suspend operator fun invoke(accountId: Int, comicIds: Collection<Int>) {
        downloadManager.downloadComics(localQuery.getComics(accountId, comicIds))
    }
}
