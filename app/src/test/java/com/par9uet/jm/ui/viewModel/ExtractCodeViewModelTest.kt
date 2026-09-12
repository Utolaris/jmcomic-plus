package com.par9uet.jm.ui.viewModel

import com.par9uet.jm.core.ToastManager
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
class ExtractCodeViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `extract digits and load detail successfully`() = runTest(scheduler) {
        val comic = Comic.create(id = 408882, name = "Test Comic", authorList = listOf("Author"))
        val repository = FakeComicRepository().apply {
            detailResult = { NetWorkResult.Success(comic) }
        }
        val toastManager = ToastManager()
        val messages = collectToasts(toastManager)
        val viewModel = ExtractCodeViewModel(repository, toastManager)

        viewModel.extractAndFetch("加里奥在40岁的时候一拳撩到了8个闯入家中的恐怖分子获得了882万的悬赏金")
        advanceUntilIdle()

        // 40 + 8 + 882 → "408882"
        assertEquals("408882", viewModel.uiState.value.extractedCode)
        assertEquals(comic, viewModel.uiState.value.previewComic)
        assertFalse(viewModel.uiState.value.loading)
        assertEquals(listOf(408882), repository.requestedIds)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `no digits shows toast and does not fetch`() = runTest(scheduler) {
        val repository = FakeComicRepository()
        val toastManager = ToastManager()
        val messages = collectToasts(toastManager)
        val viewModel = ExtractCodeViewModel(repository, toastManager)

        viewModel.extractAndFetch("没有数字的文案")
        advanceUntilIdle()

        assertEquals(listOf("未检测到数字，无法提取编码"), messages)
        assertNull(viewModel.uiState.value.extractedCode)
        assertNull(viewModel.uiState.value.previewComic)
        assertFalse(viewModel.uiState.value.loading)
        assertTrue(repository.requestedIds.isEmpty())
    }

    @Test
    fun `detail failure toasts and clears extracted code`() = runTest(scheduler) {
        val repository = FakeComicRepository().apply {
            detailResult = { NetWorkResult.Error("server exploded") }
        }
        val toastManager = ToastManager()
        val messages = collectToasts(toastManager)
        val viewModel = ExtractCodeViewModel(repository, toastManager)

        viewModel.extractAndFetch("编码 12345")
        advanceUntilIdle()

        assertEquals(listOf("获取漫画详情失败：server exploded"), messages)
        assertNull(viewModel.uiState.value.extractedCode)
        assertNull(viewModel.uiState.value.previewComic)
        assertFalse(viewModel.uiState.value.loading)
        assertEquals(listOf(12345), repository.requestedIds)
    }

    @Test
    fun `unexpected exception toasts and clears extracted code`() = runTest(scheduler) {
        val repository = FakeComicRepository().apply {
            detailResult = { throw IllegalStateException("boom") }
        }
        val toastManager = ToastManager()
        val messages = collectToasts(toastManager)
        val viewModel = ExtractCodeViewModel(repository, toastManager)

        viewModel.extractAndFetch("编码 999")
        advanceUntilIdle()

        assertEquals(listOf("获取漫画详情异常"), messages)
        assertNull(viewModel.uiState.value.extractedCode)
        assertNull(viewModel.uiState.value.previewComic)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `cancellation is not swallowed as fetch exception`() = runTest(scheduler) {
        val repository = FakeComicRepository().apply {
            detailResult = { throw CancellationException("cancelled") }
        }
        val toastManager = ToastManager()
        val messages = collectToasts(toastManager)
        val viewModel = ExtractCodeViewModel(repository, toastManager)

        viewModel.extractAndFetch("编码 777")
        advanceUntilIdle()

        // 取消不是失败：不得落入「获取漫画详情异常」分支。
        assertTrue(messages.none { it.contains("获取漫画详情") })
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `dismissPreview clears preview and extracted code`() = runTest(scheduler) {
        val comic = Comic.create(id = 1, name = "A", authorList = emptyList())
        val repository = FakeComicRepository().apply {
            detailResult = { NetWorkResult.Success(comic) }
        }
        val viewModel = ExtractCodeViewModel(repository, ToastManager())

        viewModel.extractAndFetch("1")
        advanceUntilIdle()
        assertEquals(comic, viewModel.uiState.value.previewComic)

        viewModel.dismissPreview()

        assertNull(viewModel.uiState.value.previewComic)
        assertNull(viewModel.uiState.value.extractedCode)
    }

    private fun kotlinx.coroutines.test.TestScope.collectToasts(
        toastManager: ToastManager,
    ): MutableList<String> {
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            toastManager.message.collect { messages += it }
        }
        return messages
    }

    private class FakeComicRepository : ComicRepository {
        val requestedIds = mutableListOf<Int>()
        var detailResult: suspend (Int) -> NetWorkResult<Comic> = {
            NetWorkResult.Error("detail not configured")
        }

        override suspend fun getComicDetail(id: Int): NetWorkResult<Comic> {
            requestedIds += id
            return detailResult(id)
        }

        override suspend fun collectComic(id: Int): NetWorkResult<Unit> = unused()
        override suspend fun unCollectComic(id: Int): NetWorkResult<Unit> = unused()
        override suspend fun getEmbeddedHomeCategory(categoryId: String): NetWorkResult<List<Comic>> = unused()
        override suspend fun getNetworkHomePage(): NetWorkResult<List<HomeComicSwiperItem>> = unused()
        override suspend fun getComicPicList(id: Int): NetWorkResult<ComicPageList> = unused()
        override suspend fun downloadImageBytes(comicId: Int, imageIndex: Int): ByteArray? = null
        override suspend fun getComicList(
            page: Int,
            order: ComicSearchOrderFilter,
            searchContent: String,
        ): NetWorkResult<ComicSearchPage> = unused()

        override suspend fun getWeekData(): NetWorkResult<WeekData> = unused()
        override suspend fun getWeekRecommendComicList(
            page: Int,
            categoryId: String,
            typeId: String,
        ): NetWorkResult<ComicPage> = unused()

        override suspend fun getCommentList(page: Int, comicId: Int): NetWorkResult<CommentPage> = unused()
        override suspend fun comment(
            content: String,
            comicId: Int,
            commentId: Int?,
        ): NetWorkResult<ActionResult> = unused()

        override suspend fun getComicIdsByTag(tagName: String, maxPages: Int): Set<Int> = emptySet()

        private fun <T> unused(): NetWorkResult<T> = NetWorkResult.Error("unused")
    }
}
