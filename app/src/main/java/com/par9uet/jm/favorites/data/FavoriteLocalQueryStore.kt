package com.par9uet.jm.favorites.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.database.dao.FavoriteComicDao
import com.par9uet.jm.database.dao.FavoriteFolderDao
import com.par9uet.jm.database.dao.FavoriteMetadataTermDao
import com.par9uet.jm.database.model.FavoriteComicEntity
import com.par9uet.jm.favorites.model.FavoriteLocalQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Read-side implementation of the local Favorites snapshot. */
internal class FavoriteLocalQueryStore(
    private val comicDao: FavoriteComicDao,
    private val folderDao: FavoriteFolderDao,
    private val termDao: FavoriteMetadataTermDao,
) : FavoriteLocalQuery {
    override fun pagingSource(
        accountId: Int,
        blockedTagList: List<String>,
        searchText: String,
        selectedTags: Set<String>,
        selectedAuthors: Set<String>,
        folderId: Int,
        tagLogic: TagFilterLogic,
    ): PagingSource<Int, Comic> = FavoriteComicPagingSource(
        comicDao.pagingSource(
            buildFavoritePagingQuery(
                accountId = accountId,
                blockedTagList = blockedTagList,
                searchText = searchText,
                selectedTags = selectedTags,
                selectedAuthors = selectedAuthors,
                folderId = folderId,
                tagLogic = tagLogic,
            )
        )
    )

    override fun observeFolders(accountId: Int): Flow<Map<String, String>> =
        folderDao.observeAll(accountId).map { folders ->
            linkedMapOf<String, String>().apply {
                put("0", folders.firstOrNull { it.folderId == 0 }?.name ?: "全部")
                folders.filter { it.folderId != 0 }
                    .forEach { put(it.folderId.toString(), it.name) }
            }
        }

    override fun observeTagCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>> =
        termDao.observeCounts(accountId, folderId, FAVORITE_TERM_TAG).map { counts ->
            counts.associate { it.value to it.count }
        }

    override fun observeAuthorCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>> =
        termDao.observeCounts(accountId, folderId, FAVORITE_TERM_AUTHOR).map { counts ->
            counts.associate { it.value to it.count }
        }

    override suspend fun getCachedFolders(accountId: Int): Map<String, String> =
        folderDao.getAll(accountId).associate { it.folderId.toString() to it.name }
            .toMutableMap()
            .apply { putIfAbsent("0", "全部") }

    override suspend fun getComics(accountId: Int, albumIds: Collection<Int>): List<Comic> {
        val requestedIds = albumIds.distinct()
        if (accountId <= 0 || requestedIds.isEmpty()) return emptyList()
        val comicsById = comicDao.getByIds(accountId, requestedIds).associateBy { it.albumId }
        return requestedIds.mapNotNull { comicsById[it]?.toComic() }
    }
}

/** Maps Room favorite rows to [Comic] inside L4 so presentation never sees entities. */
private class FavoriteComicPagingSource(
    private val localSource: PagingSource<Int, FavoriteComicEntity>,
) : PagingSource<Int, Comic>() {
    init {
        localSource.registerInvalidatedCallback { invalidate() }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Comic> =
        when (val result = localSource.load(params)) {
            is LoadResult.Error -> LoadResult.Error(result.throwable)
            is LoadResult.Invalid -> LoadResult.Invalid()
            is LoadResult.Page -> LoadResult.Page(
                data = result.data.map { it.toComic() },
                prevKey = result.prevKey,
                nextKey = result.nextKey,
                itemsBefore = result.itemsBefore,
                itemsAfter = result.itemsAfter,
            )
        }

    override fun getRefreshKey(state: PagingState<Int, Comic>): Int? = null
}
