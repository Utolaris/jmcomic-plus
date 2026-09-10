package com.par9uet.jm.ui.pagingSource

import androidx.paging.PagingSource
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.repository.CandidateSession
import com.par9uet.jm.repository.UserRepository
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.SignInDataResponse
import com.par9uet.jm.retrofit.model.SignInResponse
import com.par9uet.jm.retrofit.model.UserHistoryComicListResponse
import com.par9uet.jm.retrofit.model.UserHistoryCommentListResponse
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
        override suspend fun getHistoryComicList(page: Int): NetWorkResult<UserHistoryComicListResponse> {
            requests += page
            return NetWorkResult.Success(UserHistoryComicListResponse(
                list = List(sizes.getValue(page)) { index ->
                    UserHistoryComicListResponse.ListItem(
                        id = (page * 100 + index).toString(), author = "author", description = null,
                        name = "comic", image = "", category = UserHistoryComicListResponse.ListItem.Category("1", "blocked"),
                        category_sub = UserHistoryComicListResponse.ListItem.Category(null, null),
                    )
                },
            ))
        }
        override suspend fun login(username: String, password: String): NetWorkResult<CandidateSession> = error("unused")
        override fun activateVerifiedSession(verified: CandidateSession) = Unit
        override fun clearSession() = Unit
        override suspend fun deleteHistoryComic(id: Int): NetWorkResult<Unit> = error("unused")
        override suspend fun getHistoryCommentList(page: Int, userId: Int): NetWorkResult<UserHistoryCommentListResponse> = error("unused")
        override suspend fun getSignData(userId: Int): NetWorkResult<SignInDataResponse> = error("unused")
        override suspend fun signIn(userId: Int, dailyId: Int): NetWorkResult<SignInResponse> = error("unused")
    }
}
