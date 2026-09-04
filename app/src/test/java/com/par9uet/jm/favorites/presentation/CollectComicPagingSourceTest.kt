package com.par9uet.jm.favorites.presentation

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.par9uet.jm.database.model.FavoriteComicEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectComicPagingSourceTest {

    @Test
    fun roomRowsAreMappedToComicWithoutNetworkAccess() = runBlocking {
        val source = CollectComicPagingSource(
            FakeLocalSource(
                listOf(
                    FavoriteComicEntity(
                        accountId = 7,
                        albumId = 1,
                        title = "本地漫画",
                        authorList = listOf("作者"),
                        description = "描述",
                        image = "cover",
                        tagList = listOf("标签"),
                        roleList = listOf("角色"),
                        workList = listOf("作品"),
                    )
                )
            )
        )

        val result = source.load(refreshParams()) as PagingSource.LoadResult.Page<Int, com.par9uet.jm.data.models.Comic>

        assertEquals(listOf(1), result.data.map { it.id })
        assertEquals(listOf("作者"), result.data.single().authorList)
        assertEquals(listOf("标签"), result.data.single().tagList)
        assertEquals(listOf("角色"), result.data.single().roleList)
        assertTrue(result.data.single().isCollect)
    }

    @Test
    fun invalidatingRoomSourceInvalidatesUiAdapter() {
        val localSource = FakeLocalSource(emptyList())
        val source = CollectComicPagingSource(localSource)

        assertTrue(!source.invalid)
        localSource.invalidate()
        assertTrue(source.invalid)
    }

    private fun refreshParams(): PagingSource.LoadParams<Int> =
        PagingSource.LoadParams.Refresh(
            key = null,
            loadSize = 20,
            placeholdersEnabled = false,
        )

    private class FakeLocalSource(
        private val rows: List<FavoriteComicEntity>,
    ) : PagingSource<Int, FavoriteComicEntity>() {
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, FavoriteComicEntity> =
            LoadResult.Page(rows, prevKey = null, nextKey = null)

        override fun getRefreshKey(state: PagingState<Int, FavoriteComicEntity>): Int? = null
    }
}
