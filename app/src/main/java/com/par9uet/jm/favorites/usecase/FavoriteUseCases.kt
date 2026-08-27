package com.par9uet.jm.favorites.usecase

import com.par9uet.jm.favorites.data.FavoriteLocalMutation
import com.par9uet.jm.favorites.data.FavoriteLocalQuery
import com.par9uet.jm.favorites.data.FavoriteRemoteMutation
import com.par9uet.jm.favorites.data.FavoriteSession
import com.par9uet.jm.favorites.data.FavoriteSessionSnapshot
import com.par9uet.jm.favorites.data.FavoriteDownloader
import com.par9uet.jm.retrofit.model.NetWorkResult

data class FavoritesBatchResult(
    val succeeded: Int,
    val failed: Int,
)

class UncollectFavorites(
    private val remoteMutation: FavoriteRemoteMutation,
    private val localMutation: FavoriteLocalMutation,
    private val session: FavoriteSession,
) {
    suspend operator fun invoke(
        sessionSnapshot: FavoriteSessionSnapshot,
        comicIds: Collection<Int>,
    ): FavoritesBatchResult {
        val distinctIds = comicIds.distinct()
        var succeeded = 0
        var failed = 0
        for (index in distinctIds.indices) {
            if (!session.isCurrent(sessionSnapshot)) {
                failed += distinctIds.size - index
                break
            }
            val comicId = distinctIds[index]
            when (remoteMutation.uncollectComic(comicId)) {
                is NetWorkResult.Error -> failed++
                is NetWorkResult.Success -> {
                    val committed = session.withCurrentSession(sessionSnapshot) {
                        localMutation.remove(sessionSnapshot.accountId, listOf(comicId))
                    }
                    if (committed == null) {
                        failed += distinctIds.size - index
                        break
                    }
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
    private val session: FavoriteSession,
) {
    suspend operator fun invoke(
        sessionSnapshot: FavoriteSessionSnapshot,
        comicIds: Collection<Int>,
        folderId: Int,
    ): FavoritesBatchResult {
        val distinctIds = comicIds.distinct()
        var succeeded = 0
        var failed = 0
        for (index in distinctIds.indices) {
            if (!session.isCurrent(sessionSnapshot)) {
                failed += distinctIds.size - index
                break
            }
            val comicId = distinctIds[index]
            when (remoteMutation.moveComicToFolder(comicId, folderId)) {
                is NetWorkResult.Error -> failed++
                is NetWorkResult.Success -> {
                    val committed = session.withCurrentSession(sessionSnapshot) {
                        localMutation.moveToFolder(sessionSnapshot.accountId, comicId, folderId)
                    }
                    if (committed == null) {
                        failed += distinctIds.size - index
                        break
                    }
                    succeeded++
                }
            }
        }
        return FavoritesBatchResult(succeeded = succeeded, failed = failed)
    }
}

class CreateFavoriteFolder(
    private val remoteMutation: FavoriteRemoteMutation,
    private val session: FavoriteSession,
) {
    suspend operator fun invoke(
        sessionSnapshot: FavoriteSessionSnapshot,
        name: String,
    ): NetWorkResult<Unit> {
        if (!session.isCurrent(sessionSnapshot)) return staleSessionError()
        val result = remoteMutation.createFolder(name)
        if (result is NetWorkResult.Success) {
            val committed = session.withCurrentSession(sessionSnapshot) {}
            if (committed == null) return staleSessionError()
        }
        return result
    }
}

class DeleteFavoriteFolder(
    private val remoteMutation: FavoriteRemoteMutation,
    private val localMutation: FavoriteLocalMutation,
    private val session: FavoriteSession,
) {
    suspend operator fun invoke(
        sessionSnapshot: FavoriteSessionSnapshot,
        folderId: Int,
    ): NetWorkResult<Unit> {
        if (!session.isCurrent(sessionSnapshot)) return staleSessionError()
        return when (val result = remoteMutation.deleteFolder(folderId)) {
            is NetWorkResult.Error -> result
            is NetWorkResult.Success -> {
                val committed = session.withCurrentSession(sessionSnapshot) {
                    localMutation.removeFolder(sessionSnapshot.accountId, folderId)
                }
                if (committed == null) return staleSessionError()
                result
            }
        }
    }
}

class RenameFavoriteFolder(
    private val remoteMutation: FavoriteRemoteMutation,
    private val localMutation: FavoriteLocalMutation,
    private val session: FavoriteSession,
) {
    suspend operator fun invoke(
        sessionSnapshot: FavoriteSessionSnapshot,
        folderId: Int,
        name: String,
    ): NetWorkResult<Unit> {
        if (!session.isCurrent(sessionSnapshot)) return staleSessionError()
        return when (val result = remoteMutation.renameFolder(folderId, name)) {
            is NetWorkResult.Error -> result
            is NetWorkResult.Success -> {
                val committed = session.withCurrentSession(sessionSnapshot) {
                    localMutation.renameFolder(sessionSnapshot.accountId, folderId, name)
                }
                if (committed == null) return staleSessionError()
                result
            }
        }
    }
}

class DownloadSelectedFavorites(
    private val localQuery: FavoriteLocalQuery,
    private val downloader: FavoriteDownloader,
) {
    suspend operator fun invoke(accountId: Int, comicIds: Collection<Int>) {
        downloader.downloadComics(localQuery.getComics(accountId, comicIds))
    }
}

private fun staleSessionError(): NetWorkResult.Error =
    NetWorkResult.Error("登录状态已变化，请重试")
