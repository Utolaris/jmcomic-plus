package com.par9uet.jm.ui.viewModel

import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.ComicPageList
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.data.models.ComicSearchPage
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.data.models.HomeComicSwiperItem
import com.par9uet.jm.data.models.WeekData
import com.par9uet.jm.download.DownloadWorkScheduler
import com.par9uet.jm.download.RecordingDownloadDao
import com.par9uet.jm.download.atom.DownloadFiles
import com.par9uet.jm.download.coordinator.DownloadManager
import com.par9uet.jm.download.molecule.DownloadTaskOperations
import com.par9uet.jm.download.testDownloadCoordinator
import com.par9uet.jm.favorites.TestFavoriteSession
import com.par9uet.jm.favorites.data.FavoriteLocalMutation
import com.par9uet.jm.favorites.data.FavoriteRemoteMutation
import com.par9uet.jm.favorites.usecase.CollectFavorite
import com.par9uet.jm.favorites.usecase.UncollectFavorites
import com.par9uet.jm.reader.ReaderImagePipeline
import com.par9uet.jm.reader.molecule.LoadLocalChapter
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.storage.ReadHistoryManager
import com.par9uet.jm.storage.ReaderPreferences
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ComicReadViewModel 对 DownloadManager 的 L2 委托：
 * Screen 不再直连 DownloadManager，只调 VM 的 downloadComic / downloadChapters。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComicReadViewModelDownloadTest {

    private val scheduler = TestCoroutineScheduler()
    private var downloadScope: CoroutineScope? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        downloadScope?.cancel()
        downloadScope = null
        Dispatchers.resetMain()
    }

    @Test
    fun `downloadComic delegates to DownloadManager and enqueues a task`() = runTest(scheduler) {
        val env = environment()
        val comic = Comic.create(id = 11, name = "整本", authorList = emptyList())

        env.viewModel.downloadComic(comic)
        env.awaitDownloadWork()

        assertEquals(listOf(listOf(11)), env.enqueuedBatches)
        assertTrue(11 in env.dao.tasks)
    }

    @Test
    fun `downloadChapters delegates selected chapters to DownloadManager`() = runTest(scheduler) {
        val env = environment()
        val comic = Comic.create(id = 20, name = "分章", authorList = emptyList())
        val chapters = listOf(
            ComicChapter(id = 21, name = "第1话"),
            ComicChapter(id = 22, name = "第2话"),
        )

        env.viewModel.downloadChapters(comic, chapters)
        env.awaitDownloadWork()

        assertEquals(listOf(listOf(21, 22)), env.enqueuedBatches)
        assertTrue(21 in env.dao.tasks)
        assertTrue(22 in env.dao.tasks)
    }

    @Test
    fun `downloadChapters with empty chapter list is a no-op at DownloadManager`() = runTest(scheduler) {
        val env = environment()
        val comic = Comic.create(id = 30, name = "空章节", authorList = emptyList())

        env.viewModel.downloadChapters(comic, emptyList())
        env.awaitDownloadWork()

        assertTrue(env.enqueuedBatches.isEmpty())
        assertTrue(env.dao.tasks.isEmpty())
    }

    private data class TestEnvironment(
        val viewModel: ComicReadViewModel,
        val dao: RecordingDownloadDao,
        val enqueuedBatches: List<List<Int>>,
        val downloadJob: CompletableJob,
    ) {
        /** DownloadManager fires on Dispatchers.IO; join its children like DownloadManagerTest. */
        suspend fun awaitDownloadWork() {
            downloadJob.children.toList().joinAll()
            yield()
            downloadJob.children.toList().joinAll()
        }
    }

    private fun environment(): TestEnvironment {
        val dao = RecordingDownloadDao()
        val enqueuedBatches = mutableListOf<List<Int>>()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        downloadScope = scope
        val downloadManager = DownloadManager(
            DownloadTaskOperations(dao, DownloadFiles()),
            scope,
            ToastManager(),
            object : DownloadWorkScheduler {
                override suspend fun cancel(comicIds: Collection<Int>) {}
                override fun enqueue(comicIds: Collection<Int>) {
                    enqueuedBatches += comicIds.toList()
                }
            },
            testDownloadCoordinator(dao),
        )
        val session = TestFavoriteSession()
        val remote = UnusedFavoriteRemoteMutation()
        val local = UnusedFavoriteLocalMutation()
        return TestEnvironment(
            viewModel = ComicReadViewModel(
                comicRepository = StubComicRepository(),
                readerImagePipeline = uninitializedInstance(ReaderImagePipeline::class.java),
                readerPreferences = StubReaderPreferences(),
                loadLocalChapter = LoadLocalChapter(
                    downloads = RecordingDownloadDao(),
                    files = { _, _ -> emptyList() },
                ),
                toastManager = ToastManager(),
                readHistoryManager = uninitializedInstance(ReadHistoryManager::class.java),
                favoriteSession = session,
                collectFavorite = CollectFavorite(remote, local, session),
                uncollectFavorites = UncollectFavorites(remote, local, session),
                downloadManager = downloadManager,
            ),
            dao = dao,
            enqueuedBatches = enqueuedBatches,
            downloadJob = job,
        )
    }

    /**
     * Instantiates Android-bound collaborators without constructing their Context graph.
     * Download delegation never touches these instances — do not read fields off them.
     * Prefer stubs when these types grow a non-Android constructor.
     */
    private fun <T> uninitializedInstance(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        @Suppress("UNCHECKED_CAST")
        return allocate.invoke(theUnsafe, type) as T
    }

    private class StubReaderPreferences : ReaderPreferences {
        override val readMode: StateFlow<String> = MutableStateFlow("page").asStateFlow()
        override val readTapMode: StateFlow<String> = MutableStateFlow("default").asStateFlow()
        override val prefetchCount: StateFlow<Int> = MutableStateFlow(2).asStateFlow()
        override val memoryOptEnabled: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
        override val decodeConcurrency: StateFlow<Int> = MutableStateFlow(2).asStateFlow()
    }

    private class UnusedFavoriteRemoteMutation : FavoriteRemoteMutation {
        override suspend fun collectComic(comicId: Int): NetWorkResult<Unit> = unused()
        override suspend fun uncollectComic(comicId: Int): NetWorkResult<Unit> = unused()
        override suspend fun createFolder(name: String): NetWorkResult<Unit> = unused()
        override suspend fun deleteFolder(folderId: Int): NetWorkResult<Unit> = unused()
        override suspend fun renameFolder(folderId: Int, name: String): NetWorkResult<Unit> = unused()
        override suspend fun moveComicToFolder(comicId: Int, folderId: Int): NetWorkResult<Unit> = unused()
        private fun <T> unused(): NetWorkResult<T> = NetWorkResult.Error("unused")
    }

    private class UnusedFavoriteLocalMutation : FavoriteLocalMutation {
        override suspend fun addFromComic(accountId: Int, comic: Comic, folderId: Int) = Unit
        override suspend fun remove(accountId: Int, albumIds: Collection<Int>) = Unit
        override suspend fun moveToFolder(accountId: Int, albumId: Int, folderId: Int) = Unit
        override suspend fun cacheFolder(accountId: Int, folderId: Int, name: String) = Unit
        override suspend fun removeFolder(accountId: Int, folderId: Int) = Unit
        override suspend fun renameFolder(accountId: Int, folderId: Int, name: String) = Unit
    }

    private class StubComicRepository : ComicRepository {
        override suspend fun getComicDetail(id: Int): NetWorkResult<Comic> = unused()
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
