package com.par9uet.jm.ui.viewModel

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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Home 轮播加载状态测试：loading 归当前请求代次所有，取消的陈旧请求不能清掉
 * 新请求的 loading，返回已缓存来源时必须复位 loading 且不发起多余网络请求。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicViewModelHomeLoadingTest {

    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeSettings : AppLocalSettings {
        override val localSettingState = MutableStateFlow(LocalSetting())
    }

    private class FakeComicRepository(
        private val homeResult: suspend () -> NetWorkResult<List<HomeSwiperComicListItemResponse>>,
    ) : ComicRepository {
        var homeCalls = 0

        override suspend fun getHomeSwiperComicList(): NetWorkResult<List<HomeSwiperComicListItemResponse>> {
            homeCalls++
            return homeResult()
        }

        override suspend fun getComicDetail(id: Int): NetWorkResult<ComicDetailResponse> =
            NetWorkResult.Error("stub")

        override suspend fun likeComic(id: Int): NetWorkResult<LikeComicResponse> =
            NetWorkResult.Error("stub")

        override suspend fun collectComic(id: Int): NetWorkResult<CollectComicResponse> =
            NetWorkResult.Error("stub")

        override suspend fun unCollectComic(id: Int): NetWorkResult<CollectComicResponse> =
            NetWorkResult.Error("stub")

        override suspend fun getComicPicList(id: Int, shunt: String): NetWorkResult<ComicPicListResponse> =
            NetWorkResult.Error("stub")

        override suspend fun downloadImageBytes(comicId: Int, imageIndex: Int): ByteArray? = null

        override suspend fun getComicList(
            page: Int,
            order: ComicSearchOrderFilter,
            searchContent: String
        ): NetWorkResult<ComicListResponse> = NetWorkResult.Error("stub")

        override suspend fun getWeekData(): NetWorkResult<WeekResponse> =
            NetWorkResult.Error("stub")

        override suspend fun getWeekRecommendComicList(
            page: Int,
            categoryId: String,
            typeId: String
        ): NetWorkResult<WeekRecommendComicResponse> = NetWorkResult.Error("stub")

        override suspend fun getCommentList(
            page: Int,
            comicId: Int
        ): NetWorkResult<CommentListResponse> = NetWorkResult.Error("stub")

        override suspend fun comment(
            content: String,
            comicId: Int,
            commentId: Int?
        ): NetWorkResult<CommentComicResponse> = NetWorkResult.Error("stub")

        override suspend fun likeComment(commentId: Int): NetWorkResult<CommentComicResponse> =
            NetWorkResult.Error("stub")

        override suspend fun createFavoriteFolder(name: String): NetWorkResult<Unit> =
            NetWorkResult.Error("stub")

        override suspend fun deleteFavoriteFolder(folderId: String): NetWorkResult<Unit> =
            NetWorkResult.Error("stub")

        override suspend fun renameFavoriteFolder(folderId: String, newName: String): NetWorkResult<Unit> =
            NetWorkResult.Error("stub")

        override suspend fun moveComicToFolder(comicId: Int, folderId: String): NetWorkResult<Unit> =
            NetWorkResult.Error("stub")

        override suspend fun getComicIdsByTag(tagName: String, maxPages: Int): Set<Int> = emptySet()
    }

    private fun item(id: Int): HomeSwiperComicListItemResponse =
        HomeSwiperComicListItemResponse(
            id = id.toString(),
            title = "item$id",
            slug = "",
            type = "",
            filter_val = "",
            content = listOf(),
        )

    private fun vm(
        settings: FakeSettings,
        homeResult: suspend () -> NetWorkResult<List<HomeSwiperComicListItemResponse>>,
    ): Pair<ComicViewModel, FakeComicRepository> {
        val repository = FakeComicRepository(homeResult)
        return ComicViewModel(repository, settings) to repository
    }

    @Test
    fun returningToCachedSourceClearsLoadingWithoutExtraNetwork() = runTest(scheduler) {
        val gate = CompletableDeferred<NetWorkResult<List<HomeSwiperComicListItemResponse>>>()
        val settings = FakeSettings()
        val (viewModel, repository) = vm(settings) {
            if (settings.localSettingState.value.comicApiSource == "network") {
                gate.await()
            } else {
                NetWorkResult.Success(listOf(item(1)))
            }
        }

        // A（builtin）加载完成。
        viewModel.getHomeComic()
        advanceUntilIdle()
        assertEquals(1, repository.homeCalls)
        assertFalse(viewModel.homeComicState.value.isLoading)

        // 切到 B（network）：isLoading = true，请求挂在 gate 上。
        settings.localSettingState.update { it.copy(comicApiSource = "network") }
        viewModel.getHomeComic()
        advanceUntilIdle()
        assertTrue(viewModel.homeComicState.value.isLoading)

        // B 完成前切回已缓存的 A：必须立即回到 isLoading = false，且不发起第三次请求
        // （前两次分别是 A 与 B 的正常请求）。
        settings.localSettingState.update { it.copy(comicApiSource = "builtin") }
        viewModel.getHomeComic()
        advanceUntilIdle()
        assertEquals(2, repository.homeCalls)
        assertFalse(viewModel.homeComicState.value.isLoading)
        assertEquals(1, viewModel.homeComicState.value.list.size)

        // B 的物理请求迟到完成：结果被丢弃，不能复活 loading 或覆盖 A 的数据。
        gate.complete(NetWorkResult.Success(listOf(item(2))))
        advanceUntilIdle()
        assertEquals(2, repository.homeCalls)
        assertFalse(viewModel.homeComicState.value.isLoading)
        assertEquals(listOf("1"), viewModel.homeComicState.value.list.map { it.id })
    }

    @Test
    fun nowLoadingOwnerIsLatestRequestGeneration() = runTest(scheduler) {
        val gateB = CompletableDeferred<NetWorkResult<List<HomeSwiperComicListItemResponse>>>()
        val gateC = CompletableDeferred<NetWorkResult<List<HomeSwiperComicListItemResponse>>>()
        val settings = FakeSettings()
        val (viewModel, _) = vm(settings) {
            when (settings.localSettingState.value.comicApiSource) {
                "builtin" -> NetWorkResult.Success(listOf(item(1)))
                "network" -> gateB.await()
                else -> gateC.await()
            }
        }

        viewModel.getHomeComic()
        advanceUntilIdle()
        assertFalse(viewModel.homeComicState.value.isLoading)

        // A -> B -> C 快速切换：B 被取消，C 持有 loading。
        settings.localSettingState.update { it.copy(comicApiSource = "network") }
        viewModel.getHomeComic()
        advanceUntilIdle()
        settings.localSettingState.update { it.copy(comicApiSource = "mixed") }
        viewModel.getHomeComic()
        advanceUntilIdle()
        assertTrue(viewModel.homeComicState.value.isLoading)

        // B 的物理请求迟到完成：不得把 C 的 loading 清掉，不得写入数据。
        gateB.complete(NetWorkResult.Success(listOf(item(2))))
        advanceUntilIdle()
        assertTrue(viewModel.homeComicState.value.isLoading)
        assertEquals(1, viewModel.homeComicState.value.list.size)

        // C 完成：数据更新、loading 结束。
        gateC.complete(NetWorkResult.Success(listOf(item(3))))
        advanceUntilIdle()
        assertFalse(viewModel.homeComicState.value.isLoading)
        assertEquals(listOf("3"), viewModel.homeComicState.value.list.map { it.id })
    }

    @Test
    fun refreshThenSwitchDiscardsLateRefreshResult() = runTest(scheduler) {
        val gateRefresh = CompletableDeferred<NetWorkResult<List<HomeSwiperComicListItemResponse>>>()
        val gateB = CompletableDeferred<NetWorkResult<List<HomeSwiperComicListItemResponse>>>()
        val settings = FakeSettings()
        var inRefresh = false
        val (viewModel, repository) = vm(settings) {
            when {
                settings.localSettingState.value.comicApiSource == "network" -> gateB.await()
                inRefresh -> gateRefresh.await()
                else -> NetWorkResult.Success(listOf(item(1)))
            }
        }

        viewModel.getHomeComic()
        advanceUntilIdle()
        assertFalse(viewModel.homeComicState.value.isLoading)

        // refresh A：刷新请求挂在 gateRefresh 上。
        inRefresh = true
        viewModel.getHomeComic(force = true)
        advanceUntilIdle()
        assertTrue(viewModel.homeComicState.value.isLoading)

        // 刷新完成前切到 B。
        settings.localSettingState.update { it.copy(comicApiSource = "network") }
        viewModel.getHomeComic()
        advanceUntilIdle()
        assertTrue(viewModel.homeComicState.value.isLoading)

        // 迟到的刷新结果被丢弃；B 完成后展示 B 的数据。
        gateRefresh.complete(NetWorkResult.Success(listOf(item(9))))
        advanceUntilIdle()
        assertEquals(listOf("1"), viewModel.homeComicState.value.list.map { it.id })
        assertTrue(viewModel.homeComicState.value.isLoading)

        gateB.complete(NetWorkResult.Success(listOf(item(2))))
        advanceUntilIdle()
        assertFalse(viewModel.homeComicState.value.isLoading)
        assertEquals(listOf("2"), viewModel.homeComicState.value.list.map { it.id })
        assertEquals(3, repository.homeCalls)
    }
}
