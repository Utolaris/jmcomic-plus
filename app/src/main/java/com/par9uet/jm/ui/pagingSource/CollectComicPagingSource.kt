package com.par9uet.jm.ui.pagingSource

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.database.model.FavoriteComicEntity
import com.par9uet.jm.utils.log

/**
 * Adapts the Room-backed Favorites PagingSource to the existing Comic UI model.
 * No network call belongs in this class; synchronization writes to Room separately.
 */
class CollectComicPagingSource(
    private val localSource: PagingSource<Int, FavoriteComicEntity>,
) : PagingSource<Int, Comic>() {
    init {
        localSource.registerInvalidatedCallback {
            log("FavoritesPaging", "Room source invalidated")
            invalidate()
        }
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

private fun FavoriteComicEntity.toComic(): Comic = Comic(
    id = albumId,
    name = title,
    authorList = authorList,
    description = description,
    readCount = 0,
    likeCount = 0,
    commentCount = 0,
    tagList = tagList,
    roleList = roleList,
    workList = workList,
    isLike = false,
    isCollect = true,
    relateComicList = emptyList(),
    comicChapterList = emptyList(),
    price = 0,
    isBuy = false,
)
