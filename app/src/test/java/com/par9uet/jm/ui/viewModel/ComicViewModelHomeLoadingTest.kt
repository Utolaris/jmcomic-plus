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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 首页分类 lazy 加载测试：
 * - 推荐开启时 /promote 的真实分类保持独立，只交换前两个符合约定的 section
 * - 推荐关闭时启动只请求“最新上架”
 * - 其它分类点击才请求，再次点击复用缓存
 * - force refresh 只刷新当前分类
 * - 推荐拓扑变化时，迟到的旧请求结果不得写入新状态
 * - 失败后可重试
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

    private class FakeSettings(initial: LocalSetting = LocalSetting()) : AppLocalSettings {
        override val localSettingState = MutableStateFlow(initial)
    }

    private class FakeComicRepository(
        private val embeddedHandler: suspend (String) -> NetWorkResult<List<HomeSwiperComicListItemResponse.ListItem>> =
            { NetWorkResult.Error("stub") },
        private val networkHandler: suspend () -> NetWorkResult<List<HomeSwiperComicListItemResponse>> =
            { NetWorkResult.Error("stub") },
    ) : ComicRepository {
        val embeddedCalls = mutableListOf<String>()
        var networkPageCalls = 0

        override suspend fun getEmbeddedHomeCategory(
            categoryId: String
        ): NetWorkResult<List<HomeSwiperComicListItemResponse.ListItem>> {
            embeddedCalls.add(categoryId)
            return embeddedHandler(categoryId)
        }

        override suspend fun getNetworkHomePage(): NetWorkResult<List<HomeSwiperComicListItemResponse>> {
            networkPageCalls++
            return networkHandler()
        }

        override suspend fun getComicDetail(id: Int): NetWorkResult<ComicDetailResponse> =
            NetWorkResult.Error("stub")

        override suspend fun collectComic(id: Int): NetWorkResult<CollectComicResponse> =
            NetWorkResult.Error("stub")

        override suspend fun unCollectComic(id: Int): NetWorkResult<CollectComicResponse> =
            NetWorkResult.Error("stub")

        override suspend fun getComicPicList(id: Int): NetWorkResult<ComicPicListResponse> =
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

        override suspend fun moveComicToFolder(comicId: Int, folderId: String): NetWorkResult<Unit> =
            NetWorkResult.Error("stub")

        override suspend fun getComicIdsByTag(tagName: String, maxPages: Int): Set<Int> = emptySet()
    }

    private fun item(id: Int): HomeSwiperComicListItemResponse.ListItem =
        HomeSwiperComicListItemResponse.ListItem(
            id = id.toString(),
            author = "",
            description = null,
            name = "comic$id",
            image = "",
            category = HomeSwiperComicListItemResponse.ListItem.Category(null, null),
            category_sub = HomeSwiperComicListItemResponse.ListItem.Category(null, null),
            is_favorite = false,
            update_at = 0,
        )

    private fun page(id: String, title: String, items: List<HomeSwiperComicListItemResponse.ListItem>) =
        HomeSwiperComicListItemResponse(
            id = id,
            title = title,
            slug = id,
            type = "preference",
            filter_val = "",
            content = items,
        )

    private fun embeddedOk(categoryId: String): NetWorkResult<List<HomeSwiperComicListItemResponse.ListItem>> =
        NetWorkResult.Success(listOf(item(categoryId.hashCode())))

    @Test
    fun recommendOffStartupRequestsOnlyLatest() = runTest(scheduler) {
        val repo = FakeComicRepository(embeddedHandler = { embeddedOk(it) })
        val settings = FakeSettings()
        val vm = ComicViewModel(repo, settings)

        vm.refreshHome()
        advanceUntilIdle()

        assertEquals(listOf(ComicViewModel.CATEGORY_LATEST), repo.embeddedCalls)
        assertEquals(0, repo.networkPageCalls)
        assertEquals(ComicViewModel.CATEGORY_LATEST, vm.homeState.value.selectedCategoryId)
        assertEquals(1, vm.homeState.value.states[ComicViewModel.CATEGORY_LATEST]?.content?.size)
        assertEquals(ComicViewModel.EMBEDDED_CATEGORIES, vm.homeState.value.categories)
    }

    @Test
    fun recommendOnPreservesPromoteSectionsAndSwapsRecommendationToFirst() = runTest(scheduler) {
        val repo = FakeComicRepository(
            embeddedHandler = { embeddedOk(it) },
            networkHandler = {
                NetWorkResult.Success(
                    listOf(
                        page("serial", "连载漫画", listOf(item(1))),
                        page("rec", "C108推荐本本", listOf(item(2))),
                        page("other", "其它栏目", listOf(item(3))),
                    )
                )
            },
        )
        val settings = FakeSettings(LocalSetting().copy(preferenceRecommendEnabled = true))
        val vm = ComicViewModel(repo, settings)

        vm.refreshHome()
        advanceUntilIdle()

        assertEquals(1, repo.networkPageCalls)
        assertTrue(repo.embeddedCalls.isEmpty())
        assertEquals("net_rec", vm.homeState.value.selectedCategoryId)
        assertEquals(
            listOf("C108推荐本本", "连载漫画", "其它栏目", "最新上架"),
            vm.homeState.value.categories.take(4).map { it.title },
        )
        assertEquals(listOf("2"), vm.homeState.value.states["net_rec"]?.content?.map { it.id })
        assertEquals(listOf("1"), vm.homeState.value.states["net_serial"]?.content?.map { it.id })
        assertEquals(listOf("3"), vm.homeState.value.states["net_other"]?.content?.map { it.id })
        assertNull(vm.homeState.value.states[ComicViewModel.CATEGORY_LATEST])

        vm.selectHomeCategory("net_serial")
        advanceUntilIdle()
        assertEquals(1, repo.networkPageCalls)
        assertEquals(listOf("1"), vm.homeState.value.states["net_serial"]?.content?.map { it.id })
    }

    @Test
    fun recommendationPrefixIsDynamicAndDoesNotHardcodeC108() = runTest(scheduler) {
        val repo = FakeComicRepository(
            networkHandler = {
                NetWorkResult.Success(
                    listOf(
                        page("serial", "连载漫画", listOf(item(1))),
                        page("rec", "C109推荐本本", listOf(item(2))),
                        page("other", "其它栏目", listOf(item(3))),
                    )
                )
            },
        )
        val vm = ComicViewModel(
            repo,
            FakeSettings(LocalSetting().copy(preferenceRecommendEnabled = true)),
        )

        vm.refreshHome()
        advanceUntilIdle()

        assertEquals(
            listOf("C109推荐本本", "连载漫画", "其它栏目"),
            vm.homeState.value.categories.take(3).map { it.title },
        )
        assertEquals(listOf("2"), vm.homeState.value.states["net_rec"]?.content?.map { it.id })
    }

    @Test
    fun unrelatedSecondPromoteSectionKeepsServerOrder() = runTest(scheduler) {
        val repo = FakeComicRepository(
            networkHandler = {
                NetWorkResult.Success(
                    listOf(
                        page("first", "连载漫画", listOf(item(1))),
                        page("second", "其它栏目 A", listOf(item(2))),
                        page("third", "其它栏目 B", listOf(item(3))),
                    )
                )
            },
        )
        val vm = ComicViewModel(
            repo,
            FakeSettings(LocalSetting().copy(preferenceRecommendEnabled = true)),
        )

        vm.refreshHome()
        advanceUntilIdle()

        assertEquals(
            listOf("连载漫画", "其它栏目 A", "其它栏目 B"),
            vm.homeState.value.categories.take(3).map { it.title },
        )
        assertEquals(listOf("1"), vm.homeState.value.states["net_first"]?.content?.map { it.id })
    }

    @Test
    fun clickingCategoryRequestsItOnceThenUsesCache() = runTest(scheduler) {
        val repo = FakeComicRepository(embeddedHandler = { embeddedOk(it) })
        val settings = FakeSettings()
        val vm = ComicViewModel(repo, settings)

        vm.refreshHome()
        advanceUntilIdle()

        vm.selectHomeCategory("builtin_week_hot")
        advanceUntilIdle()
        assertEquals(listOf(ComicViewModel.CATEGORY_LATEST, "builtin_week_hot"), repo.embeddedCalls)
        assertEquals("builtin_week_hot", vm.homeState.value.selectedCategoryId)

        // 再次点击同一分类：无新请求。
        vm.selectHomeCategory("builtin_week_hot")
        advanceUntilIdle()
        assertEquals(2, repo.embeddedCalls.size)

        // 切走再切回：命中缓存，无新请求。
        vm.selectHomeCategory(ComicViewModel.CATEGORY_LATEST)
        advanceUntilIdle()
        vm.selectHomeCategory("builtin_week_hot")
        advanceUntilIdle()
        assertEquals(2, repo.embeddedCalls.size)
        assertEquals(1, vm.homeState.value.states["builtin_week_hot"]?.content?.size)
    }

    @Test
    fun forceRefreshOnlyRefreshesCurrentCategory() = runTest(scheduler) {
        val repo = FakeComicRepository(embeddedHandler = { embeddedOk(it) })
        val settings = FakeSettings()
        val vm = ComicViewModel(repo, settings)

        vm.refreshHome()
        advanceUntilIdle()
        vm.selectHomeCategory("builtin_week_hot")
        advanceUntilIdle()

        vm.refreshSelectedHomeCategory()
        advanceUntilIdle()

        assertEquals(
            listOf(ComicViewModel.CATEGORY_LATEST, "builtin_week_hot", "builtin_week_hot"),
            repo.embeddedCalls,
        )
        // 其它分类缓存未被破坏。
        assertEquals(1, vm.homeState.value.states[ComicViewModel.CATEGORY_LATEST]?.content?.size)
    }

    @Test
    fun staleResultAfterRecommendationTopologyChangeIsDiscarded() = runTest(scheduler) {
        val gate = CompletableDeferred<NetWorkResult<List<HomeSwiperComicListItemResponse.ListItem>>>()
        val repo = FakeComicRepository(
            embeddedHandler = {
                if (it == "builtin_week_hot") gate.await() else embeddedOk(it)
            },
            networkHandler = { NetWorkResult.Success(listOf(page("home", "首页", listOf(item(3))))) },
        )
        val settings = FakeSettings()
        val vm = ComicViewModel(repo, settings)

        vm.refreshHome()
        advanceUntilIdle()
        vm.selectHomeCategory("builtin_week_hot")
        advanceUntilIdle()
        assertTrue(vm.homeState.value.states["builtin_week_hot"]?.isLoading == true)

        // 用户开启网络推荐：旧请求 token 作废，首页切换到推荐 + Embedded 分类拓扑。
        settings.localSettingState.update { it.copy(preferenceRecommendEnabled = true) }
        vm.refreshHome()
        advanceUntilIdle()
        assertEquals("net_home", vm.homeState.value.selectedCategoryId)

        // 迟到的旧请求完成：不得写入任何状态。
        gate.complete(NetWorkResult.Success(listOf(item(9))))
        advanceUntilIdle()

        assertNull(vm.homeState.value.states["builtin_week_hot"])
        // 网络推荐正常展开。
        assertTrue(vm.homeState.value.categories.isNotEmpty())
        assertTrue(vm.homeState.value.states.values.any { it.content.isNotEmpty() })
    }

    @Test
    fun failedCategoryCanRetry() = runTest(scheduler) {
        var fail = true
        val repo = FakeComicRepository(
            embeddedHandler = {
                if (it == "builtin_doujin" && fail) {
                    NetWorkResult.Error("boom")
                } else {
                    embeddedOk(it)
                }
            },
        )
        val settings = FakeSettings()
        val vm = ComicViewModel(repo, settings)

        vm.refreshHome()
        advanceUntilIdle()
        vm.selectHomeCategory("builtin_doujin")
        advanceUntilIdle()
        assertTrue(vm.homeState.value.states["builtin_doujin"]?.isError == true)
        assertEquals("boom", vm.homeState.value.states["builtin_doujin"]?.errorMsg)
        assertFalse(vm.homeState.value.states["builtin_doujin"]?.isLoading == true)

        // 重试成功。
        fail = false
        vm.refreshSelectedHomeCategory()
        advanceUntilIdle()
        assertFalse(vm.homeState.value.states["builtin_doujin"]?.isError == true)
        assertEquals(1, vm.homeState.value.states["builtin_doujin"]?.content?.size)
    }

    @Test
    fun networkRecommendationAddsTabsWithoutReplacingEmbeddedCategories() = runTest(scheduler) {
        val repo = FakeComicRepository(
            embeddedHandler = { embeddedOk(it) },
            networkHandler = {
                NetWorkResult.Success(
                    listOf(
                        page("serial", "连载漫画", listOf(item(1))),
                        page("rec", "C108推荐本本", listOf(item(2))),
                    )
                )
            },
        )
        val settings = FakeSettings()
        val vm = ComicViewModel(repo, settings)

        // 默认只加载 Embedded 最新上架。
        vm.refreshHome()
        advanceUntilIdle()
        assertEquals(1, repo.embeddedCalls.size)

        // 开启网络推荐：/promote 展开为 tab，同时保留 Embedded 分类入口。
        settings.localSettingState.update { it.copy(preferenceRecommendEnabled = true) }
        vm.refreshHome()
        advanceUntilIdle()
        assertEquals(1, repo.networkPageCalls)
        assertEquals(
            listOf("C108推荐本本", "连载漫画"),
            vm.homeState.value.categories.take(2).map { it.title },
        )
        assertTrue(vm.homeState.value.categories.any { it.id == ComicViewModel.CATEGORY_LATEST })
        assertEquals("net_rec", vm.homeState.value.selectedCategoryId)

        // 关闭网络推荐：回到 Embedded，并复用已加载的最新上架缓存。
        settings.localSettingState.update { it.copy(preferenceRecommendEnabled = false) }
        vm.refreshHome()
        advanceUntilIdle()
        assertEquals(1, repo.embeddedCalls.size)
        assertEquals(ComicViewModel.CATEGORY_LATEST, vm.homeState.value.selectedCategoryId)
        assertTrue(vm.homeState.value.categories.none { it.id == "net_rec" })
    }

    @Test
    fun togglePreferenceWhileCategoryLoadingDoesNotBlockLaterLoad() = runTest(scheduler) {
        val gate = CompletableDeferred<NetWorkResult<List<HomeSwiperComicListItemResponse.ListItem>>>()
        var firstLatest = true
        val repo = FakeComicRepository(
            embeddedHandler = {
                if (it == ComicViewModel.CATEGORY_LATEST && firstLatest) {
                    firstLatest = false
                    gate.await()
                } else {
                    embeddedOk(it)
                }
            },
            networkHandler = {
                NetWorkResult.Success(
                    listOf(
                        page("serial", "连载漫画", listOf(item(4))),
                        page("rec", "C109推荐本本", listOf(item(5))),
                    )
                )
            },
        )
        val settings = FakeSettings()
        val vm = ComicViewModel(repo, settings)

        vm.refreshHome()
        advanceUntilIdle()
        assertTrue(vm.homeState.value.states[ComicViewModel.CATEGORY_LATEST]?.isLoading == true)

        // 最新上架加载中切换推荐开关：默认分类切到真实 /promote 推荐 section。
        settings.localSettingState.update { it.copy(preferenceRecommendEnabled = true) }
        vm.refreshHome()
        advanceUntilIdle()
        assertEquals("net_rec", vm.homeState.value.selectedCategoryId)

        // 迟到的“最新上架”结果被丢弃。
        gate.complete(NetWorkResult.Success(listOf(item(9))))
        advanceUntilIdle()

        // 之后点击“最新上架”必须发起新请求，不能被孤立 loading 吞掉。
        val callsBefore = repo.embeddedCalls.size
        vm.selectHomeCategory(ComicViewModel.CATEGORY_LATEST)
        advanceUntilIdle()
        assertEquals(callsBefore + 1, repo.embeddedCalls.size)
        assertFalse(vm.homeState.value.states[ComicViewModel.CATEGORY_LATEST]?.isLoading == true)
        assertEquals(1, vm.homeState.value.states[ComicViewModel.CATEGORY_LATEST]?.content?.size)
    }

    @Test
    fun rapidAToBToAKeepsAWithAValidRequest() = runTest(scheduler) {
        val aGate = CompletableDeferred<NetWorkResult<List<HomeSwiperComicListItemResponse.ListItem>>>()
        val repo = FakeComicRepository(
            embeddedHandler = { categoryId ->
                if (categoryId == "builtin_week_hot") aGate.await() else embeddedOk(categoryId)
            },
        )
        val vm = ComicViewModel(repo, FakeSettings())
        vm.refreshHome()
        advanceUntilIdle()

        vm.selectHomeCategory("builtin_week_hot")
        advanceUntilIdle()
        vm.selectHomeCategory("builtin_month_hot")
        advanceUntilIdle()
        vm.selectHomeCategory("builtin_week_hot")
        advanceUntilIdle()

        assertEquals("builtin_week_hot", vm.homeState.value.selectedCategoryId)
        assertTrue(vm.homeState.value.states["builtin_week_hot"]?.isLoading == true)
        assertEquals(1, repo.embeddedCalls.count { it == "builtin_week_hot" })

        aGate.complete(NetWorkResult.Success(listOf(item(99))))
        advanceUntilIdle()
        assertFalse(vm.homeState.value.states["builtin_week_hot"]?.isLoading == true)
        assertEquals(listOf("99"), vm.homeState.value.states["builtin_week_hot"]?.content?.map { it.id })
    }

    @Test
    fun refreshingCachedCategoryKeepsItsContentVisible() = runTest(scheduler) {
        val refreshGate = CompletableDeferred<NetWorkResult<List<HomeSwiperComicListItemResponse.ListItem>>>()
        var latestCalls = 0
        val repo = FakeComicRepository(
            embeddedHandler = { categoryId ->
                if (categoryId == ComicViewModel.CATEGORY_LATEST && ++latestCalls == 2) {
                    refreshGate.await()
                } else {
                    NetWorkResult.Success(listOf(item(1)))
                }
            },
        )
        val vm = ComicViewModel(repo, FakeSettings())
        vm.refreshHome()
        advanceUntilIdle()

        vm.refreshSelectedHomeCategory()
        advanceUntilIdle()

        val refreshing = vm.homeState.value.states[ComicViewModel.CATEGORY_LATEST]
        assertTrue(refreshing?.isLoading == true)
        assertEquals(listOf("1"), refreshing?.content?.map { it.id })

        refreshGate.complete(NetWorkResult.Success(listOf(item(2))))
        advanceUntilIdle()
        assertEquals(
            listOf("2"),
            vm.homeState.value.states[ComicViewModel.CATEGORY_LATEST]?.content?.map { it.id },
        )
    }

    @Test
    fun searchOrderChangeUpdatesFilterAndClearsPendingComicId() = runTest(scheduler) {
        val vm = ComicViewModel(FakeComicRepository(), FakeSettings())

        assertEquals(ComicSearchOrderFilter.NEWEST, vm.searchComicFilterState.value.order)

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_COLLECT_COUNT)
        assertEquals(ComicSearchOrderFilter.MOST_COLLECT_COUNT, vm.searchComicFilterState.value.order)
        assertNull(vm.searchComicIdState.value)

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_PIC_COUNT)
        assertEquals(ComicSearchOrderFilter.MOST_PIC_COUNT, vm.searchComicFilterState.value.order)
        assertNull(vm.searchComicIdState.value)

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_LIKE_COUNT)
        assertEquals(ComicSearchOrderFilter.MOST_LIKE_COUNT, vm.searchComicFilterState.value.order)
        assertNull(vm.searchComicIdState.value)

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.NEWEST)
        assertEquals(ComicSearchOrderFilter.NEWEST, vm.searchComicFilterState.value.order)
    }

    @Test
    fun searchOrderChangeKeepsSearchCriteria() = runTest(scheduler) {
        val vm = ComicViewModel(FakeComicRepository(), FakeSettings())
        vm.changeSearchComicContent("neko", listOf("tag1", "tag2"))

        vm.changeSearchComicOrderFilter(ComicSearchOrderFilter.MOST_PIC_COUNT)

        val filter = vm.searchComicFilterState.value
        assertEquals("neko", filter.searchContent)
        assertEquals(listOf("tag1", "tag2"), filter.excludedTags)
        assertEquals(ComicSearchOrderFilter.MOST_PIC_COUNT, filter.order)
    }
}
