package com.par9uet.jm.ui.pagingSource

import androidx.paging.PagingSource
import com.par9uet.jm.data.models.CollectComicOrderFilter
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.repository.LoginSession
import com.par9uet.jm.repository.UserRepository
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.SignInDataResponse
import com.par9uet.jm.retrofit.model.SignInResponse
import com.par9uet.jm.retrofit.model.UserCollectComicListResponse
import com.par9uet.jm.retrofit.model.UserHistoryComicListResponse
import com.par9uet.jm.retrofit.model.UserHistoryCommentListResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectComicPagingSourceTest {

    @Test
    fun initialPageUsesOneLightweightRequestAndPropagatesFolders() = runBlocking {
        val repository = FakeUserRepository()
        var folders: Map<String, String>? = null
        val source = CollectComicPagingSource(
            userRepository = repository,
            order = CollectComicOrderFilter.COLLECT_TIME,
            onFolderListLoaded = { folders = it },
        )

        val result = source.load(refreshParams()) as PagingSource.LoadResult.Page<Int, Comic>

        assertEquals(listOf(1), result.data.map { it.id })
        assertEquals(1, repository.lightweightCalls)
        assertEquals(0, repository.fullMetadataCalls)
        assertEquals(mapOf("0" to "全部", "7" to "稍后看"), folders)
    }

    @Test
    fun metadataDependentFilterUsesExplicitFullMetadataPath() = runBlocking {
        val repository = FakeUserRepository()
        val source = CollectComicPagingSource(
            userRepository = repository,
            order = CollectComicOrderFilter.COLLECT_TIME,
            selectedTags = setOf("tag"),
        )

        source.load(refreshParams())

        assertEquals(0, repository.lightweightCalls)
        assertEquals(1, repository.fullMetadataCalls)
    }

    @Test
    fun onlyMetadataDependentInputsRequestFullData() {
        assertTrue(
            requiresFullCollectMetadata(
                blockedTagList = emptyList(),
                searchText = "tag",
                selectedTags = emptySet(),
                selectedAuthors = emptySet(),
            )
        )
        assertTrue(
            requiresFullCollectMetadata(
                blockedTagList = listOf("blocked"),
                searchText = "",
                selectedTags = emptySet(),
                selectedAuthors = emptySet(),
            )
        )
    }

    private fun refreshParams(): PagingSource.LoadParams<Int> =
        PagingSource.LoadParams.Refresh(
            key = null,
            loadSize = 20,
            placeholdersEnabled = false,
        )

    private class FakeUserRepository : UserRepository {
        var lightweightCalls = 0
        var fullMetadataCalls = 0

        private val response = NetWorkResult.Success(
            UserCollectComicListResponse(
                count = 1,
                folder_list = mapOf("0" to "全部", "7" to "稍后看"),
                list = listOf(
                    UserCollectComicListResponse.ListItem(
                        id = "1",
                        author = "作者",
                        description = "",
                        name = "漫画",
                        image = "",
                        category = UserCollectComicListResponse.ListItem.Category(null, null),
                        category_sub = UserCollectComicListResponse.ListItem.Category(null, null),
                    )
                ),
                total = 1,
            )
        )

        override suspend fun getCollectComicList(
            page: Int,
            order: CollectComicOrderFilter,
            folderId: Int,
        ): NetWorkResult<UserCollectComicListResponse> {
            lightweightCalls++
            return response
        }

        override suspend fun getCollectComicListWithFullTags(
            page: Int,
            order: CollectComicOrderFilter,
            folderId: Int,
        ): NetWorkResult<UserCollectComicListResponse> {
            fullMetadataCalls++
            return response
        }

        override suspend fun login(username: String, password: String): NetWorkResult<LoginSession> = unused()

        override suspend fun getHistoryComicList(page: Int): NetWorkResult<UserHistoryComicListResponse> = unused()

        override suspend fun deleteHistoryComic(id: Int): NetWorkResult<Unit> = unused()

        override suspend fun getHistoryCommentList(
            page: Int,
            userId: Int,
        ): NetWorkResult<UserHistoryCommentListResponse> = unused()

        override suspend fun getSignData(userId: Int): NetWorkResult<SignInDataResponse> = unused()

        override suspend fun signIn(userId: Int, dailyId: Int): NetWorkResult<SignInResponse> = unused()

        private fun unused(): Nothing = error("Unused fake repository method")
    }
}
