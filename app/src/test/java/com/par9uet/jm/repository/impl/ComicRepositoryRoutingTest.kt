package com.par9uet.jm.repository.impl

import com.par9uet.jm.data.comic.ComicEmbeddedDataSource
import com.par9uet.jm.data.comic.ComicNetworkDataSource
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.retrofit.model.CollectComicResponse
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.ComicListResponse
import com.par9uet.jm.retrofit.model.ComicPicListResponse
import com.par9uet.jm.retrofit.model.CommentComicResponse
import com.par9uet.jm.retrofit.model.CommentListResponse
import com.par9uet.jm.retrofit.model.HomeSwiperComicListItemResponse
import com.par9uet.jm.retrofit.model.LikeComicResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.WeekRecommendComicResponse
import com.par9uet.jm.retrofit.model.WeekResponse
import com.par9uet.jm.store.AppLocalSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun <T> routingStub(): NetWorkResult<T> = NetWorkResult.Error("stub")

class ComicRepositoryRoutingTest {
    private class FakeSettings(source: String) : AppLocalSettings {
        override val localSettingState = MutableStateFlow(
            LocalSetting().copy(comicApiSource = source)
        )
    }

    private class NetworkFake : ComicNetworkDataSource {
        var detailCalls = 0
        var imageCalls = 0
        var homeCalls = 0
        var commentLikeCalls = 0

        override suspend fun getComicDetail(id: Int): NetWorkResult<ComicDetailResponse> {
            detailCalls++
            return NetWorkResult.Error("network")
        }

        override suspend fun likeComic(id: Int): NetWorkResult<LikeComicResponse> = routingStub()
        override suspend fun collectComic(id: Int): NetWorkResult<CollectComicResponse> = routingStub()
        override suspend fun unCollectComic(id: Int): NetWorkResult<CollectComicResponse> = routingStub()

        override suspend fun getHomePage(): NetWorkResult<List<HomeSwiperComicListItemResponse>> {
            homeCalls++
            return NetWorkResult.Error("network")
        }

        override suspend fun getComicPicList(id: Int, shunt: String): NetWorkResult<ComicPicListResponse> {
            imageCalls++
            return NetWorkResult.Error("network")
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

        override suspend fun getCommentList(page: Int, comicId: Int): NetWorkResult<CommentListResponse> = routingStub()

        override suspend fun comment(
            content: String,
            comicId: Int,
            commentId: Int?,
        ): NetWorkResult<CommentComicResponse> = routingStub()

        override suspend fun likeComment(commentId: Int): NetWorkResult<CommentComicResponse> {
            commentLikeCalls++
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

        override suspend fun likeComic(id: Int): NetWorkResult<LikeComicResponse> = routingStub()
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

        override suspend fun getCommentList(page: Int, comicId: Int): NetWorkResult<CommentListResponse> = routingStub()

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
    fun mixedModeUsesEmbeddedMetadataAndNetworkImageList() = runTest {
        val network = NetworkFake()
        val embedded = EmbeddedFake()
        val repository = repository("mixed", network, embedded)

        repository.getComicDetail(1)
        repository.getComicPicList(1, "1")
        repository.getEmbeddedHomeCategory("builtin_latest")
        repository.getNetworkHomePage()
        repository.likeComment(1)

        assertEquals(1, embedded.detailCalls)
        assertEquals(1, embedded.homeCalls)
        assertEquals(0, embedded.imageCalls)
        assertEquals(1, network.imageCalls)
        assertEquals(1, network.homeCalls)
        assertEquals(1, network.commentLikeCalls)
    }

    @Test
    fun builtinModeUsesEmbeddedImageList() = runTest {
        val network = NetworkFake()
        val embedded = EmbeddedFake()
        val repository = repository("builtin", network, embedded)

        repository.getComicPicList(1, "1")

        assertEquals(1, embedded.imageCalls)
        assertEquals(0, network.imageCalls)
    }

    @Test
    fun networkModeDoesNotExposeEmbeddedOnlyCategory() = runTest {
        val network = NetworkFake()
        val embedded = EmbeddedFake()
        val repository = repository("network", network, embedded)

        val result = repository.getEmbeddedHomeCategory("builtin_latest")
        repository.getComicDetail(1)

        assertTrue(result is NetWorkResult.Error)
        assertEquals(0, embedded.homeCalls)
        assertEquals(0, embedded.detailCalls)
        assertEquals(1, network.detailCalls)
    }

    private fun repository(
        source: String,
        network: NetworkFake,
        embedded: EmbeddedFake,
    ): ComicRepository = ComicRepositoryImpl(network, embedded, FakeSettings(source))

}
