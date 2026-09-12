package com.par9uet.jm.ui.pagingSource

import androidx.paging.PagingSource
import com.par9uet.jm.core.model.SignInData
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.session.CandidateSession
import com.par9uet.jm.session.UserRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryComicPagingSourceTest {
    @Test
    fun fullPagesContinueUntilThirdShortPage() = runTest {
        val repository = HistoryRepository(mapOf(1 to 20, 2 to 20, 3 to 5))
        val source = HistoryComicPagingSource(repository)
        var key: Int? = 1
        val ids = mutableListOf<Int>()
        while (key != null) {
            val page = source.page(key)
            ids += page.data.map { it.id }
            key = page.nextKey
        }
        assertEquals(listOf(1, 2, 3), repository.requests)
        assertEquals(45, ids.distinct().size)
    }

    @Test
    fun emptyOrShortFirstPageEndsImmediately() = runTest {
        for (size in listOf(0, 5, 19)) {
            val repository = HistoryRepository(mapOf(1 to size))
            val page = HistoryComicPagingSource(repository).page(1)
            assertEquals(size, page.data.size)
            assertNull(page.prevKey)
            assertNull(page.nextKey)
            assertEquals(listOf(1), repository.requests)
        }
    }

    @Test
    fun emptyPageAfterFullPageStopsPagination() = runTest {
        val repository = HistoryRepository(mapOf(1 to 20, 2 to 0))
        val source = HistoryComicPagingSource(repository)
        assertEquals(2, source.page(1).nextKey)
        assertNull(source.page(2).nextKey)
        assertEquals(listOf(1, 2), repository.requests)
    }

    @Test
    fun entirelyFilteredFullPageStillLoadsNextPage() = runTest {
        val repository = HistoryRepository(mapOf(1 to 20, 2 to 5))
        val source = HistoryComicPagingSource(repository, listOf("blocked"))
        val first = source.page(1)
        assertEquals(0, first.data.size)
        assertEquals(2, first.nextKey)
        assertNull(source.page(2).nextKey)
    }

    @Test
    fun largerPagingLoadSizeDoesNotChangeServerPageSize() = runTest {
        val source = HistoryComicPagingSource(HistoryRepository(mapOf(1 to 20)))
        val page = source.load(PagingSource.LoadParams.Refresh(1, 60, false)) as PagingSource.LoadResult.Page
        assertEquals(2, page.nextKey)
    }

    private suspend fun HistoryComicPagingSource.page(key: Int): PagingSource.LoadResult.Page<Int, Comic> =
        load(PagingSource.LoadParams.Append(key, 20, false)) as PagingSource.LoadResult.Page<Int, Comic>

    private class HistoryRepository(private val sizes: Map<Int, Int>) : UserRepository {
        val requests = mutableListOf<Int>()

        /**
         * watch_list 语境：仓库返回的已经是领域 [Comic]，
         * `total` 为 null 表示服务端不提供总数（末页改由本页条数判断）。
         * 标签直接落在 `tagList` 上——`filterBlockedTags` 读的就是这个字段。
         */
        override suspend fun getHistoryComicList(page: Int): NetWorkResult<ComicPage> {
            requests += page
            return NetWorkResult.Success(
                ComicPage(
                    items = List(sizes.getValue(page)) { index ->
                        historyComic(id = page * 100 + index)
                    },
                    total = null,
                )
            )
        }

        override suspend fun login(username: String, password: String): NetWorkResult<CandidateSession> = error("unused")
        override fun activateVerifiedSession(verified: CandidateSession) = Unit
        override fun clearSession() = Unit
        override suspend fun deleteHistoryComic(id: Int): NetWorkResult<Unit> = error("unused")
        override suspend fun getHistoryCommentList(page: Int, userId: Int): NetWorkResult<CommentPage> = error("unused")
        override suspend fun getSignData(userId: Int): NetWorkResult<SignInData> = error("unused")
        override suspend fun signIn(userId: Int, dailyId: Int): NetWorkResult<ActionResult> = error("unused")

        private fun historyComic(id: Int): Comic = Comic(
            id = id,
            name = "comic",
            authorList = listOf("author"),
            description = "",
            readCount = 0,
            likeCount = 0,
            commentCount = 0,
            tagList = listOf("blocked"),
            roleList = listOf(),
            workList = listOf(),
            price = 0,
        )
    }
}
