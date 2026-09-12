package com.par9uet.jm.ui.pagingSource

import androidx.paging.PagingSource
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.ComicPageList
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.data.models.ComicSearchPage
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.data.models.HomeComicSwiperItem
import com.par9uet.jm.data.models.WeekData
import com.par9uet.jm.repository.ComicRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchComicPagingSourceTest {
    @Test
    fun filtersMultipleExcludedTagsByDetail() = runBlocking {
        val repository = FakeComicRepository()
        val source = SearchComicPagingSource(
            comicRepository = repository,
            filter = SearchComicFilter(
                searchContent = "artist",
                excludedTags = listOf("a", "b")
            )
        )

        val result = source.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 20,
                placeholdersEnabled = false
            )
        )

        val page = result as PagingSource.LoadResult.Page<Int, Comic>
        assertEquals("artist -a -b", repository.lastSearchContent)
        assertEquals(listOf(2), page.data.map { it.id })
    }

    private class FakeComicRepository : ComicRepository {
        var lastSearchContent: String? = null

        /**
         * 仓库现在直接返回领域契约：搜索结果页带 `total`，
         * 详情直接用 `tagList` 参与标签排除（不再经过 wire 的 tags/actors/works）。
         */
        override suspend fun getComicList(
            page: Int,
            order: ComicSearchOrderFilter,
            searchContent: String
        ): NetWorkResult<ComicSearchPage> {
            lastSearchContent = searchContent
            return NetWorkResult.Success(
                ComicSearchPage(
                    items = listOf(
                        comic(id = 1, tags = listOf("category")),
                        comic(id = 2, tags = listOf("category")),
                    ),
                    total = 2,
                    redirectComicId = null,
                )
            )
        }

        override suspend fun getComicDetail(id: Int): NetWorkResult<Comic> {
            return NetWorkResult.Success(
                comic(id = id, tags = if (id == 1) listOf("a") else listOf("c"))
            )
        }

        override suspend fun getComicIdsByTag(tagName: String, maxPages: Int): Set<Int> {
            error("getComicIdsByTag should not be used for search exclusions")
        }

        override suspend fun collectComic(id: Int): NetWorkResult<Unit> = unused()

        override suspend fun unCollectComic(id: Int): NetWorkResult<Unit> = unused()

        override suspend fun getEmbeddedHomeCategory(categoryId: String): NetWorkResult<List<Comic>> =
            unused()

        override suspend fun getNetworkHomePage(): NetWorkResult<List<HomeComicSwiperItem>> = unused()

        override suspend fun getComicPicList(id: Int): NetWorkResult<ComicPageList> = unused()

        override suspend fun downloadImageBytes(comicId: Int, imageIndex: Int): ByteArray? = unused()

        override suspend fun getWeekData(): NetWorkResult<WeekData> = unused()

        override suspend fun getWeekRecommendComicList(
            page: Int,
            categoryId: String,
            typeId: String
        ): NetWorkResult<ComicPage> = unused()

        override suspend fun getCommentList(page: Int, comicId: Int): NetWorkResult<CommentPage> = unused()

        override suspend fun comment(
            content: String,
            comicId: Int,
            commentId: Int?
        ): NetWorkResult<ActionResult> = unused()

        private fun comic(id: Int, tags: List<String>): Comic = Comic(
            id = id,
            name = "comic $id",
            authorList = listOf("author"),
            description = "",
            readCount = 0,
            likeCount = 0,
            commentCount = 0,
            tagList = tags,
            roleList = emptyList(),
            workList = emptyList(),
            price = 0,
        )

        private fun unused(): Nothing {
            throw UnsupportedOperationException("Unused fake repository method")
        }
    }
}
