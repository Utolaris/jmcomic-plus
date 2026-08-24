package com.par9uet.jm.repository.impl

import com.par9uet.jm.data.comic.ComicEmbeddedDataSource
import com.par9uet.jm.data.comic.NetworkHomeDataSource
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.retrofit.model.CollectComicResponse
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.ComicListResponse
import com.par9uet.jm.retrofit.model.ComicPicListResponse
import com.par9uet.jm.retrofit.model.CommentComicResponse
import com.par9uet.jm.retrofit.model.CommentListResponse
import com.par9uet.jm.retrofit.model.HomeSwiperComicListItemResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.WeekRecommendComicResponse
import com.par9uet.jm.retrofit.model.WeekResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private fun <T> routingStub(): NetWorkResult<T> = NetWorkResult.Error("stub")

class ComicRepositoryRoutingTest {
    private class NetworkFake : NetworkHomeDataSource {
        var homeCalls = 0

        override suspend fun getHomePage(): NetWorkResult<List<HomeSwiperComicListItemResponse>> {
            homeCalls++
            return NetWorkResult.Error("network")
        }
    }

    private class EmbeddedFake : ComicEmbeddedDataSource {
        var detailCalls = 0
        var imageCalls = 0
        var homeCalls = 0

        override suspend fun getComicDetail(id: Int): NetWorkResult<ComicDetailResponse> {
            detailCalls++
            return NetWorkResult.Error("embedded")
        }

        override suspend fun collectComic(id: Int): NetWorkResult<CollectComicResponse> = routingStub()
        override suspend fun unCollectComic(id: Int): NetWorkResult<CollectComicResponse> = routingStub()

        override suspend fun getHomeCategory(
            categoryId: String,
        ): NetWorkResult<List<HomeSwiperComicListItemResponse.ListItem>> {
            homeCalls++
            return NetWorkResult.Error("embedded")
        }

        override suspend fun getComicPicList(id: Int): NetWorkResult<ComicPicListResponse> {
            imageCalls++
            return NetWorkResult.Error("embedded")
        }

        override suspend fun getComicList(
            page: Int,
            order: ComicSearchOrderFilter,
            searchContent: String,
        ): NetWorkResult<ComicListResponse> = routingStub()

        override suspend fun getWeekData(): NetWorkResult<WeekResponse> = routingStub()

        override suspend fun getWeekRecommendComicList(
            page: Int,
            categoryId: String,
            typeId: String,
        ): NetWorkResult<WeekRecommendComicResponse> = routingStub()

        override suspend fun getCommentList(
            page: Int,
            comicId: Int,
        ): NetWorkResult<CommentListResponse> = routingStub()

        override suspend fun comment(
            content: String,
            comicId: Int,
            commentId: Int?,
        ): NetWorkResult<CommentComicResponse> = routingStub()

        override suspend fun createFavoriteFolder(name: String): NetWorkResult<Unit> = routingStub()
        override suspend fun deleteFavoriteFolder(folderId: String): NetWorkResult<Unit> = routingStub()
        override suspend fun renameFavoriteFolder(folderId: String, newName: String): NetWorkResult<Unit> = routingStub()
        override suspend fun moveComicToFolder(comicId: Int, folderId: String): NetWorkResult<Unit> = routingStub()
        override suspend fun getComicIdsByTag(tagName: String, maxPages: Int): Set<Int> = emptySet()
        override suspend fun downloadImageBytes(comicId: Int, imageIndex: Int): ByteArray? = null
    }

    @Test
    fun normalBusinessAndReaderMetadataAlwaysUseEmbedded() = runTest {
        val network = NetworkFake()
        val embedded = EmbeddedFake()
        val repository = ComicRepositoryImpl(network, embedded)

        repository.getComicDetail(1)
        repository.getComicPicList(1)
        repository.getEmbeddedHomeCategory("builtin_latest")

        assertEquals(1, embedded.detailCalls)
        assertEquals(1, embedded.imageCalls)
        assertEquals(1, embedded.homeCalls)
        assertEquals(0, network.homeCalls)
    }

    @Test
    fun networkDataSourceIsUsedOnlyForExplicitHomeRecommendation() = runTest {
        val network = NetworkFake()
        val embedded = EmbeddedFake()
        val repository = ComicRepositoryImpl(network, embedded)

        repository.getNetworkHomePage()

        assertEquals(1, network.homeCalls)
        assertEquals(0, embedded.detailCalls)
        assertEquals(0, embedded.imageCalls)
        assertEquals(0, embedded.homeCalls)
    }
}
