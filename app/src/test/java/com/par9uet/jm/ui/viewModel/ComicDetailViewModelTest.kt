package com.par9uet.jm.ui.viewModel

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicSearchOrderFilter
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.database.model.FavoriteComicEntity
import com.par9uet.jm.favorites.data.FavoriteLocalMutation
import com.par9uet.jm.favorites.data.FavoriteLocalQuery
import com.par9uet.jm.favorites.data.FavoriteRemoteMutation
import com.par9uet.jm.favorites.data.FavoriteSession
import com.par9uet.jm.favorites.data.FavoriteSessionSnapshot
import com.par9uet.jm.favorites.model.FavoriteSyncUiState
import com.par9uet.jm.favorites.sync.FavoriteSyncRequestKind
import com.par9uet.jm.favorites.sync.FavoriteSyncRequester
import com.par9uet.jm.favorites.usecase.CollectFavorite
import com.par9uet.jm.favorites.usecase.MoveFavorites
import com.par9uet.jm.favorites.usecase.UncollectFavorites
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
import com.par9uet.jm.store.ToastManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComicDetailViewModelTest {
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
    fun `collect success followed by account switch cannot commit stale UI success`() = runTest(scheduler) {
        val environment = environment()
        val messages = collectToasts(environment.toastManager)
        environment.prepare(comic(isCollected = false))
        environment.session.afterNextBound = { environment.session.switchAccount(43) }

        environment.viewModel.collect(COMIC_ID)
        advanceUntilIdle()

        assertEquals(listOf(Triple(42, COMIC_ID, 0)), environment.local.added)
        assertTrue(environment.local.added.none { it.first == 43 })
        assertFalse(environment.viewModel.comicDetailState.value.data!!.isCollect)
        assertFalse("收藏成功" in messages)
        assertEquals("登录状态已变化，请重试", environment.viewModel.collectComicState.value.errorMsg)
    }

    @Test
    fun `uncollect stale action removes only A local data and does not report B success`() = runTest(scheduler) {
        val environment = environment()
        val messages = collectToasts(environment.toastManager)
        environment.prepare(comic(isCollected = true))
        environment.session.afterNextBound = { environment.session.switchAccount(43) }

        environment.viewModel.unCollect(COMIC_ID)
        advanceUntilIdle()

        assertEquals(listOf(42 to COMIC_ID), environment.local.removed)
        assertTrue(environment.local.removed.none { it.first == 43 })
        assertTrue(environment.viewModel.comicDetailState.value.data!!.isCollect)
        assertFalse("取消收藏成功" in messages)
        assertEquals("登录状态已变化，请重试", environment.viewModel.collectComicState.value.errorMsg)
    }

    @Test
    fun `collectWithFolder captures one snapshot and reuses it for collect and move`() = runTest(scheduler) {
        val environment = environment()
        environment.prepare(comic(isCollected = false))
        environment.local.updateFolders(42, mapOf("7" to "Later"))
        environment.viewModel.refreshFolderList()
        runCurrent()

        environment.viewModel.collectWithFolder(COMIC_ID, "7")
        advanceUntilIdle()

        assertEquals(1, environment.session.snapshotCalls)
        assertEquals(2, environment.session.boundAttempts.size)
        assertEquals(environment.session.boundAttempts[0], environment.session.boundAttempts[1])
        assertEquals(listOf(COMIC_ID), environment.remote.collectedIds)
        assertEquals(listOf(COMIC_ID to 7), environment.remote.movedIds)
        assertEquals(listOf(Triple(42, COMIC_ID, 0)), environment.local.added)
        assertEquals(listOf(Triple(42, COMIC_ID, 7)), environment.local.moved)
        assertTrue(environment.viewModel.comicDetailState.value.data!!.isCollect)
    }

    @Test
    fun `account switch between collect and move prevents move and stale UI success`() = runTest(scheduler) {
        val environment = environment()
        val messages = collectToasts(environment.toastManager)
        environment.prepare(comic(isCollected = false))
        environment.session.afterNextBound = { environment.session.switchAccount(43) }

        environment.viewModel.collectWithFolder(COMIC_ID, "7")
        advanceUntilIdle()

        assertEquals(1, environment.session.snapshotCalls)
        assertEquals(2, environment.session.boundAttempts.size)
        assertEquals(environment.session.boundAttempts[0], environment.session.boundAttempts[1])
        assertEquals(listOf(COMIC_ID), environment.remote.collectedIds)
        assertTrue(environment.remote.movedIds.isEmpty())
        assertTrue(environment.local.moved.isEmpty())
        assertFalse(environment.viewModel.comicDetailState.value.data!!.isCollect)
        assertTrue(messages.none { it.startsWith("已收藏") || it == "收藏成功" })
        assertEquals("登录状态已变化，请重试", environment.viewModel.collectComicState.value.errorMsg)
    }

    @Test
    fun `collect exposes the real remote error to ComicDetail`() = runTest(scheduler) {
        val environment = environment()
        environment.prepare(comic(isCollected = false))
        environment.remote.collectResult = NetWorkResult.Error("server collect failed", code = 503)

        environment.viewModel.collect(COMIC_ID)
        advanceUntilIdle()

        assertEquals("server collect failed", environment.viewModel.collectComicState.value.errorMsg)
        assertTrue(environment.local.added.isEmpty())
        assertFalse(environment.viewModel.comicDetailState.value.data!!.isCollect)
    }

    @Test
    fun `folder picker is local first syncs and flatMapLatest switches account source`() = runTest(scheduler) {
        val environment = environment()
        environment.local.updateFolders(42, mapOf("1" to "A cached"))
        environment.local.updateFolders(43, mapOf("2" to "B cached"))

        environment.viewModel.refreshFolderList()
        runCurrent()

        assertEquals(mapOf("1" to "A cached"), environment.viewModel.folderList.value)
        assertEquals(
            listOf(SyncRequest(FavoriteSyncRequestKind.AUTO, 0)),
            environment.sync.requests,
        )

        environment.local.updateFolders(42, mapOf("1" to "A refreshed"))
        runCurrent()
        assertEquals(mapOf("1" to "A refreshed"), environment.viewModel.folderList.value)

        environment.session.switchAccount(43)
        runCurrent()
        assertEquals(mapOf("2" to "B cached"), environment.viewModel.folderList.value)
        assertEquals(listOf(42, 43), environment.local.observedAccounts)

        // A emits after the switch; flatMapLatest cancelled that source, so B stays visible.
        environment.local.updateFolders(42, mapOf("9" to "late A"))
        runCurrent()
        assertEquals(mapOf("2" to "B cached"), environment.viewModel.folderList.value)
    }

    private fun TestEnvironment.prepare(comic: Comic) {
        viewModel.prepareDetail(comic)
        scheduler.runCurrent()
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

    private fun environment(): TestEnvironment {
        val repository = StubComicRepository()
        val toastManager = ToastManager()
        val local = FakeFavoriteLocalData()
        val session = FakeFavoriteSession()
        val remote = FakeFavoriteRemoteMutation()
        val sync = RecordingSyncRequester()
        return TestEnvironment(
            viewModel = ComicDetailViewModel(
                comicRepository = repository,
                toastManager = toastManager,
                favoriteLocalQuery = local,
                favoriteSession = session,
                collectFavorite = CollectFavorite(remote, local, session),
                uncollectFavorites = UncollectFavorites(remote, local, session),
                moveFavorites = MoveFavorites(remote, local, session),
                syncRequester = sync,
            ),
            toastManager = toastManager,
            local = local,
            session = session,
            remote = remote,
            sync = sync,
        )
    }

    private data class TestEnvironment(
        val viewModel: ComicDetailViewModel,
        val toastManager: ToastManager,
        val local: FakeFavoriteLocalData,
        val session: FakeFavoriteSession,
        val remote: FakeFavoriteRemoteMutation,
        val sync: RecordingSyncRequester,
    )

    private data class SyncRequest(val kind: FavoriteSyncRequestKind, val folderId: Int)

    private class RecordingSyncRequester : FavoriteSyncRequester {
        private val mutableState = MutableStateFlow(FavoriteSyncUiState())
        override val state: StateFlow<FavoriteSyncUiState> = mutableState.asStateFlow()
        val requests = mutableListOf<SyncRequest>()

        override fun request(kind: FavoriteSyncRequestKind, folderId: Int) {
            requests += SyncRequest(kind, folderId)
        }
    }

    private class FakeFavoriteSession : FavoriteSession {
        private val account = MutableStateFlow(42)
        private var generation = 0L
        var snapshotCalls = 0
        val boundAttempts = mutableListOf<FavoriteSessionSnapshot>()
        var afterNextBound: (() -> Unit)? = null

        override val accountIdFlow: StateFlow<Int> = account.asStateFlow()
        override fun currentAccountId(): Int = account.value

        override fun snapshot(): FavoriteSessionSnapshot {
            snapshotCalls++
            return FavoriteSessionSnapshot(account.value, generation)
        }

        override fun isCurrent(snapshot: FavoriteSessionSnapshot): Boolean =
            snapshot.accountId == account.value && snapshot.generation == generation

        override suspend fun <T> withCurrentSession(
            snapshot: FavoriteSessionSnapshot,
            block: suspend () -> T,
        ): T? = if (isCurrent(snapshot)) block() else null

        override suspend fun <T> withBoundRemoteSession(
            snapshot: FavoriteSessionSnapshot,
            block: suspend () -> T,
        ): T? {
            boundAttempts += snapshot
            if (!isCurrent(snapshot)) return null
            val result = block()
            afterNextBound?.also {
                afterNextBound = null
                it()
            }
            return result
        }

        fun switchAccount(accountId: Int) {
            generation++
            account.value = accountId
        }
    }

    private class FakeFavoriteRemoteMutation : FavoriteRemoteMutation {
        val collectedIds = mutableListOf<Int>()
        val movedIds = mutableListOf<Pair<Int, Int>>()
        var collectResult: NetWorkResult<Unit> = NetWorkResult.Success(Unit)
        var uncollectResult: NetWorkResult<Unit> = NetWorkResult.Success(Unit)
        var moveResult: NetWorkResult<Unit> = NetWorkResult.Success(Unit)

        override suspend fun collectComic(comicId: Int): NetWorkResult<Unit> {
            collectedIds += comicId
            return collectResult
        }

        override suspend fun uncollectComic(comicId: Int): NetWorkResult<Unit> = uncollectResult
        override suspend fun createFolder(name: String): NetWorkResult<Unit> = NetWorkResult.Success(Unit)
        override suspend fun deleteFolder(folderId: Int): NetWorkResult<Unit> = NetWorkResult.Success(Unit)
        override suspend fun renameFolder(folderId: Int, name: String): NetWorkResult<Unit> =
            NetWorkResult.Success(Unit)

        override suspend fun moveComicToFolder(comicId: Int, folderId: Int): NetWorkResult<Unit> {
            movedIds += comicId to folderId
            return moveResult
        }
    }

    private class FakeFavoriteLocalData : FavoriteLocalQuery, FavoriteLocalMutation {
        private val folderFlows = mutableMapOf<Int, MutableStateFlow<Map<String, String>>>()
        val observedAccounts = mutableListOf<Int>()
        val added = mutableListOf<Triple<Int, Int, Int>>()
        val removed = mutableListOf<Pair<Int, Int>>()
        val moved = mutableListOf<Triple<Int, Int, Int>>()

        fun updateFolders(accountId: Int, folders: Map<String, String>) {
            folderFlows.getOrPut(accountId) { MutableStateFlow(emptyMap()) }.value = folders
        }

        override fun observeFolders(accountId: Int): Flow<Map<String, String>> {
            observedAccounts += accountId
            return folderFlows.getOrPut(accountId) { MutableStateFlow(emptyMap()) }
        }

        override suspend fun getCachedFolders(accountId: Int): Map<String, String> =
            folderFlows.getOrPut(accountId) { MutableStateFlow(emptyMap()) }.value

        override suspend fun addFromComic(accountId: Int, comic: Comic, folderId: Int) {
            added += Triple(accountId, comic.id, folderId)
        }

        override suspend fun remove(accountId: Int, albumIds: Collection<Int>) {
            removed += albumIds.map { accountId to it }
        }

        override suspend fun moveToFolder(accountId: Int, albumId: Int, folderId: Int) {
            moved += Triple(accountId, albumId, folderId)
        }

        override suspend fun cacheFolder(accountId: Int, folderId: Int, name: String) = Unit
        override suspend fun removeFolder(accountId: Int, folderId: Int) = Unit
        override suspend fun renameFolder(accountId: Int, folderId: Int, name: String) = Unit
        override fun observeTagCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>> = flowOf(emptyMap())
        override fun observeAuthorCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>> = flowOf(emptyMap())
        override suspend fun getComics(accountId: Int, albumIds: Collection<Int>): List<Comic> = emptyList()

        override fun pagingSource(
            accountId: Int,
            blockedTagList: List<String>,
            searchText: String,
            selectedTags: Set<String>,
            selectedAuthors: Set<String>,
            folderId: Int,
            tagLogic: TagFilterLogic,
        ): PagingSource<Int, FavoriteComicEntity> = EmptyFavoritePagingSource()
    }

    private class EmptyFavoritePagingSource : PagingSource<Int, FavoriteComicEntity>() {
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, FavoriteComicEntity> =
            LoadResult.Page(emptyList(), prevKey = null, nextKey = null)

        override fun getRefreshKey(state: PagingState<Int, FavoriteComicEntity>): Int? = null
    }

    private class StubComicRepository : ComicRepository {
        override suspend fun getComicDetail(id: Int): NetWorkResult<ComicDetailResponse> =
            NetWorkResult.Error("detail not needed")

        override suspend fun collectComic(id: Int): NetWorkResult<CollectComicResponse> = unused()
        override suspend fun unCollectComic(id: Int): NetWorkResult<CollectComicResponse> = unused()
        override suspend fun getEmbeddedHomeCategory(categoryId: String): NetWorkResult<List<HomeSwiperComicListItemResponse.ListItem>> = unused()
        override suspend fun getNetworkHomePage(): NetWorkResult<List<HomeSwiperComicListItemResponse>> = unused()
        override suspend fun getComicPicList(id: Int): NetWorkResult<ComicPicListResponse> = unused()
        override suspend fun downloadImageBytes(comicId: Int, imageIndex: Int): ByteArray? = null
        override suspend fun getComicList(page: Int, order: ComicSearchOrderFilter, searchContent: String): NetWorkResult<ComicListResponse> = unused()
        override suspend fun getWeekData(): NetWorkResult<WeekResponse> = unused()
        override suspend fun getWeekRecommendComicList(page: Int, categoryId: String, typeId: String): NetWorkResult<WeekRecommendComicResponse> = unused()
        override suspend fun getCommentList(page: Int, comicId: Int): NetWorkResult<CommentListResponse> = unused()
        override suspend fun comment(content: String, comicId: Int, commentId: Int?): NetWorkResult<CommentComicResponse> = unused()
        override suspend fun getComicIdsByTag(tagName: String, maxPages: Int): Set<Int> = emptySet()

        private fun <T> unused(): NetWorkResult<T> = NetWorkResult.Error("unused")
    }

    private companion object {
        const val COMIC_ID = 11

        fun comic(isCollected: Boolean): Comic =
            Comic.create(COMIC_ID, "Comic", listOf("Author")).copy(isCollect = isCollected)
    }
}
