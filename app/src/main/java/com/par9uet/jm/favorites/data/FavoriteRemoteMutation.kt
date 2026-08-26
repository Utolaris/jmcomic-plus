package com.par9uet.jm.favorites.data

import com.par9uet.jm.data.comic.ComicEmbeddedDataSource
import com.par9uet.jm.retrofit.model.NetWorkResult

/** L4 remote mutation capabilities used by Favorites behavior. */
interface FavoriteRemoteMutation {
    suspend fun collectComic(comicId: Int): NetWorkResult<Unit>

    suspend fun uncollectComic(comicId: Int): NetWorkResult<Unit>

    suspend fun createFolder(name: String): NetWorkResult<Unit>

    suspend fun deleteFolder(folderId: Int): NetWorkResult<Unit>

    suspend fun renameFolder(folderId: Int, name: String): NetWorkResult<Unit>

    suspend fun moveComicToFolder(comicId: Int, folderId: Int): NetWorkResult<Unit>
}

/** The only adapter that knows the embedded API's Favorites mutation methods. */
class EmbeddedFavoriteRemoteMutation(
    private val embeddedDataSource: ComicEmbeddedDataSource,
) : FavoriteRemoteMutation {
    override suspend fun collectComic(comicId: Int): NetWorkResult<Unit> =
        embeddedDataSource.collectComic(comicId).asUnit()

    override suspend fun uncollectComic(comicId: Int): NetWorkResult<Unit> =
        embeddedDataSource.unCollectComic(comicId).asUnit()

    override suspend fun createFolder(name: String): NetWorkResult<Unit> =
        embeddedDataSource.createFavoriteFolder(name)

    override suspend fun deleteFolder(folderId: Int): NetWorkResult<Unit> =
        embeddedDataSource.deleteFavoriteFolder(folderId.toString())

    override suspend fun renameFolder(folderId: Int, name: String): NetWorkResult<Unit> =
        embeddedDataSource.renameFavoriteFolder(folderId.toString(), name)

    override suspend fun moveComicToFolder(comicId: Int, folderId: Int): NetWorkResult<Unit> =
        embeddedDataSource.moveComicToFolder(comicId, folderId.toString())
}

private fun <T> NetWorkResult<T>.asUnit(): NetWorkResult<Unit> = when (this) {
    is NetWorkResult.Error -> this
    is NetWorkResult.Success -> NetWorkResult.Success(Unit)
}
