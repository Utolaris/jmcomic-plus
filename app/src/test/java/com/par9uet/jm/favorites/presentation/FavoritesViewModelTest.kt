package com.par9uet.jm.favorites.presentation

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.database.model.FavoriteComicEntity
import com.par9uet.jm.favorites.data.FavoriteDownloader
import com.par9uet.jm.favorites.data.FavoriteLocalMutation
import com.par9uet.jm.favorites.data.FavoriteLocalQuery
import com.par9uet.jm.favorites.data.FavoriteRemoteMutation
import com.par9uet.jm.favorites.data.FavoriteSession
import com.par9uet.jm.favorites.data.FavoriteSessionSnapshot
import com.par9uet.jm.favorites.model.FavoriteSyncUiState
import com.par9uet.jm.favorites.model.FavoritesFilter
import com.par9uet.jm.favorites.model.FavoritesIntent
import com.par9uet.jm.favorites.model.FavoritesModal
import com.par9uet.jm.favorites.model.FavoritesSelectionState
import com.par9uet.jm.favorites.sync.FavoriteSyncRequestKind
import com.par9uet.jm.favorites.sync.FavoriteSyncRequester
import com.par9uet.jm.favorites.usecase.CreateFavoriteFolder
import com.par9uet.jm.favorites.usecase.DeleteFavoriteFolder
import com.par9uet.jm.favorites.usecase.DownloadSelectedFavorites
import com.par9uet.jm.favorites.usecase.MoveFavorites
import com.par9uet.jm.favorites.usecase.RenameFavoriteFolder
import com.par9uet.jm.favorites.usecase.UncollectFavorites
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.ContentPreferences
import com.par9uet.jm.store.ToastManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {
    @Test
    fun `sync progress and completion preserve the paging generation and viewport`() = runTest(scheduler) {
        val environment = environment()
        var generations = 0
        backgroundScope.launch {
            environment.viewModel.collectComicPager.collect { generations++ }
        }
        runCurrent()
        val viewport = environment.viewModel.uiState.value.viewport
        environment.sync.publish(FavoriteSyncUiState(isSyncing = true))
        runCurrent()
        environment.sync.publish(FavoriteSyncUiState(isSyncing = true, completed = 20, total = 20))
        runCurrent()
        environment.sync.publish(FavoriteSyncUiState())
        runCurrent()

        assertEquals(1, generations)
        assertEquals(viewport, environment.viewModel.uiState.value.viewport)
    }

    @Test
    fun `sync failure opens dialog and dismissal survives unrelated UI changes`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()
        environment.sync.publish(FavoriteSyncUiState(errorMessage = "网络连接失败"))
        runCurrent()
        assertTrue(environment.viewModel.uiState.value.syncErrorVisible)
        environment.viewModel.onIntent(FavoritesIntent.SyncErrorDismissed)
        environment.viewModel.onIntent(FavoritesIntent.SearchEntered)
        assertFalse(environment.viewModel.uiState.value.syncErrorVisible)
        assertEquals("网络连接失败", environment.viewModel.uiState.value.sync.errorMessage)

        environment.sync.publish(FavoriteSyncUiState(isSyncing = true))
        runCurrent()
        environment.sync.publish(FavoriteSyncUiState(errorMessage = "网络连接失败"))
        runCurrent()
        assertTrue(environment.viewModel.uiState.value.syncErrorVisible)
        environment.sync.publish(FavoriteSyncUiState())
        runCurrent()
        assertFalse(environment.viewModel.uiState.value.syncErrorVisible)
    }

    @Test
    fun `dialog retry preserves force refresh and leaves another modal intact`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()
        environment.viewModel.onIntent(FavoritesIntent.FilterOpened)
        environment.sync.publish(FavoriteSyncUiState(isForceRefresh = true, errorMessage = "失败"))
        runCurrent()
        assertEquals(FavoritesModal.Filter, environment.viewModel.uiState.value.modal)
        environment.viewModel.onIntent(FavoritesIntent.SyncRetried)
        assertEquals(SyncRequest(FavoriteSyncRequestKind.FORCE, 0), environment.sync.requests.single())
        assertFalse(environment.viewModel.uiState.value.syncErrorVisible)
    }

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
    fun `entered while authenticated requests automatic sync`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.onIntent(FavoritesIntent.AccountStateChanged(authenticated = true))
        environment.viewModel.onIntent(FavoritesIntent.Entered)

        assertEquals(
            listOf(SyncRequest(FavoriteSyncRequestKind.AUTO, folderId = 0)),
            environment.sync.requests,
        )
    }

    @Test
    fun `repeated entered events do not request another entry sync`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.onIntent(FavoritesIntent.AccountStateChanged(authenticated = true))
        environment.viewModel.onIntent(FavoritesIntent.Entered)
        environment.viewModel.onIntent(FavoritesIntent.Entered)

        assertEquals(1, environment.sync.requests.size)
        assertEquals(FavoriteSyncRequestKind.AUTO, environment.sync.requests.single().kind)
    }

    @Test
    fun `login while favorites is visible requests automatic sync`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.onIntent(FavoritesIntent.Entered)
        environment.viewModel.onIntent(FavoritesIntent.AccountStateChanged(authenticated = true))

        assertEquals(
            listOf(SyncRequest(FavoriteSyncRequestKind.AUTO, folderId = 0)),
            environment.sync.requests,
        )
    }

    @Test
    fun `folder selection resets selection search viewport and requests that folder`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.onIntent(FavoritesIntent.SearchEntered)
        environment.viewModel.onIntent(FavoritesIntent.SearchChanged("query"))
        environment.viewModel.onIntent(FavoritesIntent.ComicSelectionToggled(comicId = 11))
        val savedGeneration = environment.viewModel.uiState.value.viewport.resetGeneration
        environment.viewModel.onIntent(
            FavoritesIntent.ViewportSaved(
                firstVisibleItemIndex = 8,
                firstVisibleItemScrollOffset = 3,
                resetGeneration = savedGeneration,
            )
        )
        val previousGeneration = environment.viewModel.uiState.value.viewport.resetGeneration

        environment.viewModel.onIntent(FavoritesIntent.FolderSelected(folderId = 7))

        val state = environment.viewModel.uiState.value
        assertEquals(7, state.selectedFolderId)
        assertEquals(FavoritesSelectionState(), state.selection)
        assertFalse(state.searchActive)
        assertEquals("", state.filter.searchText)
        assertEquals(0, state.viewport.firstVisibleItemIndex)
        assertEquals(0, state.viewport.firstVisibleItemScrollOffset)
        assertTrue(state.viewport.resetGeneration > previousGeneration)
        assertEquals(
            listOf(SyncRequest(FavoriteSyncRequestKind.AUTO, folderId = 7)),
            environment.sync.requests,
        )
    }

    @Test
    fun `search changed resets viewport`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        val savedGeneration = environment.viewModel.uiState.value.viewport.resetGeneration
        environment.viewModel.onIntent(
            FavoritesIntent.ViewportSaved(4, 6, savedGeneration)
        )
        val previousGeneration = environment.viewModel.uiState.value.viewport.resetGeneration

        environment.viewModel.onIntent(FavoritesIntent.SearchChanged("query"))

        val state = environment.viewModel.uiState.value
        assertEquals("query", state.filter.searchText)
        assertEquals(0, state.viewport.firstVisibleItemIndex)
        assertEquals(0, state.viewport.firstVisibleItemScrollOffset)
        assertTrue(state.viewport.resetGeneration > previousGeneration)
    }

    @Test
    fun `search exited clears query and resets viewport`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.onIntent(FavoritesIntent.SearchEntered)
        environment.viewModel.onIntent(FavoritesIntent.SearchChanged("query"))
        val savedGeneration = environment.viewModel.uiState.value.viewport.resetGeneration
        environment.viewModel.onIntent(
            FavoritesIntent.ViewportSaved(5, 2, savedGeneration)
        )
        val previousGeneration = environment.viewModel.uiState.value.viewport.resetGeneration

        environment.viewModel.onIntent(FavoritesIntent.SearchExited)

        val state = environment.viewModel.uiState.value
        assertFalse(state.searchActive)
        assertEquals("", state.filter.searchText)
        assertEquals(0, state.viewport.firstVisibleItemIndex)
        assertEquals(0, state.viewport.firstVisibleItemScrollOffset)
        assertTrue(state.viewport.resetGeneration > previousGeneration)
    }

    @Test
    fun `left with active search clears query and resets viewport`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.onIntent(FavoritesIntent.FolderSelected(folderId = 7))
        environment.viewModel.onIntent(
            FavoritesIntent.FilterApplied(
                selectedTags = setOf("tag"),
                selectedAuthors = setOf("author"),
                tagLogic = TagFilterLogic.OR,
            )
        )
        environment.viewModel.onIntent(FavoritesIntent.SearchEntered)
        environment.viewModel.onIntent(FavoritesIntent.SearchChanged("query"))
        environment.viewModel.onIntent(FavoritesIntent.ComicSelectionToggled(comicId = 11))
        environment.viewModel.onIntent(FavoritesIntent.FolderManagementOpened)
        val savedGeneration = environment.viewModel.uiState.value.viewport.resetGeneration
        environment.viewModel.onIntent(FavoritesIntent.ViewportSaved(5, 2, savedGeneration))
        val previousGeneration = environment.viewModel.uiState.value.viewport.resetGeneration

        environment.viewModel.onIntent(FavoritesIntent.Left)

        val state = environment.viewModel.uiState.value
        assertEquals(7, state.selectedFolderId)
        assertEquals(
            FavoritesFilter(
                selectedTags = setOf("tag"),
                selectedAuthors = setOf("author"),
                tagLogic = TagFilterLogic.OR,
            ),
            state.filter,
        )
        assertFalse(state.searchActive)
        assertEquals(FavoritesSelectionState(), state.selection)
        assertEquals(null, state.modal)
        assertEquals("", state.filter.searchText)
        assertEquals(0, state.viewport.firstVisibleItemIndex)
        assertEquals(0, state.viewport.firstVisibleItemScrollOffset)
        assertTrue(state.viewport.resetGeneration > previousGeneration)
    }

    @Test
    fun `left without active search preserves viewport`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        val savedGeneration = environment.viewModel.uiState.value.viewport.resetGeneration
        environment.viewModel.onIntent(FavoritesIntent.ViewportSaved(100, 4, savedGeneration))
        val previousGeneration = environment.viewModel.uiState.value.viewport.resetGeneration

        environment.viewModel.onIntent(FavoritesIntent.Left)

        val state = environment.viewModel.uiState.value
        assertFalse(state.searchActive)
        assertEquals(FavoritesSelectionState(), state.selection)
        assertEquals(null, state.modal)
        assertEquals(100, state.viewport.firstVisibleItemIndex)
        assertEquals(4, state.viewport.firstVisibleItemScrollOffset)
        assertEquals(previousGeneration, state.viewport.resetGeneration)
    }

    @Test
    fun `entering and exiting an empty search preserves viewport`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        val savedGeneration = environment.viewModel.uiState.value.viewport.resetGeneration
        environment.viewModel.onIntent(FavoritesIntent.ViewportSaved(60, 7, savedGeneration))
        val previousGeneration = environment.viewModel.uiState.value.viewport.resetGeneration

        environment.viewModel.onIntent(FavoritesIntent.SearchEntered)
        environment.viewModel.onIntent(FavoritesIntent.SearchExited)

        val state = environment.viewModel.uiState.value
        assertFalse(state.searchActive)
        assertEquals(60, state.viewport.firstVisibleItemIndex)
        assertEquals(7, state.viewport.firstVisibleItemScrollOffset)
        assertEquals(previousGeneration, state.viewport.resetGeneration)
    }

    @Test
    fun `account change resets account specific state`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.onIntent(FavoritesIntent.FolderSelected(folderId = 7))
        environment.viewModel.onIntent(
            FavoritesIntent.FilterApplied(
                selectedTags = setOf("tag"),
                selectedAuthors = setOf("author"),
                tagLogic = TagFilterLogic.NOT,
            )
        )
        environment.viewModel.onIntent(FavoritesIntent.SearchEntered)
        environment.viewModel.onIntent(FavoritesIntent.SearchChanged("query"))
        environment.viewModel.onIntent(FavoritesIntent.ComicSelectionToggled(comicId = 11))
        environment.viewModel.onIntent(FavoritesIntent.FolderManagementOpened)
        val previousGeneration = environment.viewModel.uiState.value.viewport.resetGeneration

        environment.session.switchAccount(8)
        runCurrent()

        val state = environment.viewModel.uiState.value
        assertEquals(0, state.selectedFolderId)
        assertEquals(FavoritesFilter(), state.filter)
        assertEquals(FavoritesSelectionState(), state.selection)
        assertFalse(state.searchActive)
        assertEquals(null, state.modal)
        assertEquals(0, state.viewport.firstVisibleItemIndex)
        assertEquals(0, state.viewport.firstVisibleItemScrollOffset)
        assertTrue(state.viewport.resetGeneration > previousGeneration)
    }

    @Test
    fun `move confirmed uses MoveFavorites without directly requesting sync`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.onIntent(FavoritesIntent.ComicSelectionToggled(comicId = 11))
        environment.viewModel.onIntent(FavoritesIntent.MoveSelected)
        assertEquals(FavoritesModal.Move, environment.viewModel.uiState.value.modal)

        environment.viewModel.onIntent(FavoritesIntent.MoveConfirmed(folderId = 7))
        advanceUntilIdle()

        assertEquals(listOf(11), environment.remote.movedIds)
        assertEquals(listOf(Triple(42, 11, 7)), environment.local.movedFavorites)
        assertTrue(environment.sync.requests.isEmpty())
        assertEquals(FavoritesSelectionState(), environment.viewModel.uiState.value.selection)
        assertEquals(null, environment.viewModel.uiState.value.modal)
    }

    @Test
    fun `folder CRUD returns to management and syncs after local sequencing`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.onIntent(FavoritesIntent.FolderManagementOpened)
        environment.viewModel.onIntent(FavoritesIntent.CreateFolderOpened)
        assertEquals(FavoritesModal.CreateFolder, environment.viewModel.uiState.value.modal)
        environment.viewModel.onIntent(FavoritesIntent.CreateFolderSubmitted(" New "))
        advanceUntilIdle()

        assertEquals(FavoritesModal.FolderManagement, environment.viewModel.uiState.value.modal)
        assertEquals(
            listOf("remote:create:New", "sync:AUTO:0"),
            environment.events,
        )

        environment.events.clear()
        environment.sync.requests.clear()
        environment.viewModel.onIntent(
            FavoritesIntent.RenameFolderOpened(folderId = 7, folderName = "Old")
        )
        assertEquals(
            FavoritesModal.RenameFolder(7, "Old"),
            environment.viewModel.uiState.value.modal,
        )
        environment.viewModel.onIntent(FavoritesIntent.RenameFolderSubmitted(7, " Renamed "))
        advanceUntilIdle()

        assertEquals(FavoritesModal.FolderManagement, environment.viewModel.uiState.value.modal)
        assertEquals(
            listOf("remote:rename:7:Renamed", "local:rename:42:7:Renamed", "sync:AUTO:0"),
            environment.events,
        )

        environment.events.clear()
        environment.sync.requests.clear()
        environment.viewModel.onIntent(FavoritesIntent.FolderSelected(folderId = 7))
        environment.events.clear()
        environment.sync.requests.clear()
        environment.viewModel.onIntent(
            FavoritesIntent.DeleteFolderOpened(folderId = 7, folderName = "Renamed")
        )
        assertEquals(
            FavoritesModal.DeleteFolder(7, "Renamed"),
            environment.viewModel.uiState.value.modal,
        )
        environment.viewModel.onIntent(FavoritesIntent.DeleteFolderConfirmed)
        advanceUntilIdle()

        assertEquals(FavoritesModal.FolderManagement, environment.viewModel.uiState.value.modal)
        assertEquals(0, environment.viewModel.uiState.value.selectedFolderId)
        assertEquals(
            listOf("remote:delete:7", "local:remove-folder:42:7", "sync:AUTO:0"),
            environment.events,
        )
    }

    @Test
    fun `stale session cannot commit a successful move to local favorites`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()
        val remoteResult = CompletableDeferred<NetWorkResult<Unit>>()
        environment.remote.moveHandler = { comicId, folderId ->
            environment.remote.moveStarted.complete(Unit)
            remoteResult.await()
        }

        environment.viewModel.onIntent(FavoritesIntent.ComicSelectionToggled(comicId = 11))
        environment.viewModel.onIntent(FavoritesIntent.MoveSelected)
        environment.viewModel.onIntent(FavoritesIntent.MoveConfirmed(folderId = 7))
        runCurrent()
        assertTrue(environment.remote.moveStarted.isCompleted)

        environment.session.bumpGeneration()
        remoteResult.complete(NetWorkResult.Success(Unit))
        advanceUntilIdle()

        assertTrue(environment.local.movedFavorites.isEmpty())
        assertTrue(environment.sync.requests.isEmpty())
    }

    private data class SyncRequest(
        val kind: FavoriteSyncRequestKind,
        val folderId: Int,
    )

    private data class TestEnvironment(
        val viewModel: FavoritesViewModel,
        val session: FakeFavoriteSession,
        val remote: RecordingRemoteMutation,
        val local: RecordingLocalMutation,
        val sync: RecordingSyncRequester,
        val events: MutableList<String>,
    )

    private fun environment(): TestEnvironment {
        val events = mutableListOf<String>()
        val session = FakeFavoriteSession()
        val remote = RecordingRemoteMutation(events)
        val local = RecordingLocalMutation(events)
        val query = EmptyFavoriteLocalQuery()
        val sync = RecordingSyncRequester(events)
        val viewModel = FavoritesViewModel(
            favoriteSession = session,
            contentPreferences = FakeLocalSettings(),
            localQuery = query,
            toastManager = ToastManager(),
            uncollectFavorites = UncollectFavorites(remote, local, session),
            moveFavorites = MoveFavorites(remote, local, session),
            createFavoriteFolder = CreateFavoriteFolder(remote, session),
            deleteFavoriteFolder = DeleteFavoriteFolder(remote, local, session),
            renameFavoriteFolder = RenameFavoriteFolder(remote, local, session),
            downloadSelectedFavorites = DownloadSelectedFavorites(query, NoOpFavoriteDownloader()),
            syncController = sync,
        )
        return TestEnvironment(viewModel, session, remote, local, sync, events)
    }

    private class FakeLocalSettings : ContentPreferences {
        override val blockedTags: StateFlow<List<String>> =
            MutableStateFlow(emptyList<String>()).asStateFlow()
        override val homeExcludedTags: StateFlow<List<String>> =
            MutableStateFlow(emptyList<String>()).asStateFlow()
    }

    private class FakeFavoriteSession(initialAccountId: Int = 42) : FavoriteSession {
        private val _accountId = MutableStateFlow(initialAccountId)
        private var generation = 0L

        private val _session = MutableStateFlow(FavoriteSessionSnapshot(initialAccountId, 0L))
        override val sessionFlow = _session.asStateFlow()
        override val accountIdFlow: StateFlow<Int> = _accountId.asStateFlow()

        override fun currentAccountId(): Int = _accountId.value

        override fun snapshot(): FavoriteSessionSnapshot =
            FavoriteSessionSnapshot(_accountId.value, generation)

        override fun isCurrent(snapshot: FavoriteSessionSnapshot): Boolean =
            snapshot.accountId == _accountId.value && snapshot.generation == generation

        override suspend fun <T> withCurrentSession(
            snapshot: FavoriteSessionSnapshot,
            block: suspend () -> T,
        ): T? = if (isCurrent(snapshot)) block() else null

        fun switchAccount(accountId: Int) {
            generation++
            _accountId.value = accountId
            _session.value = snapshot()
        }

        fun bumpGeneration() {
            generation++
            _session.value = snapshot()
        }
    }

    private class RecordingSyncRequester(
        private val events: MutableList<String>,
    ) : FavoriteSyncRequester {
        private val _state = MutableStateFlow(FavoriteSyncUiState())
        override val state: StateFlow<FavoriteSyncUiState> = _state.asStateFlow()
        val requests = mutableListOf<SyncRequest>()

        fun publish(state: FavoriteSyncUiState) { _state.value = state }

        override fun request(kind: FavoriteSyncRequestKind, folderId: Int) {
            requests += SyncRequest(kind, folderId)
            events += "sync:$kind:$folderId"
        }
    }

    private class RecordingRemoteMutation(
        private val events: MutableList<String>,
    ) : FavoriteRemoteMutation {
        val movedIds = mutableListOf<Int>()
        val moveStarted = CompletableDeferred<Unit>()
        var moveHandler: suspend (Int, Int) -> NetWorkResult<Unit> = { _, _ ->
            NetWorkResult.Success(Unit)
        }

        override suspend fun collectComic(comicId: Int): NetWorkResult<Unit> =
            NetWorkResult.Success(Unit)

        override suspend fun uncollectComic(comicId: Int): NetWorkResult<Unit> =
            NetWorkResult.Success(Unit)

        override suspend fun createFolder(name: String): NetWorkResult<Unit> {
            events += "remote:create:$name"
            return NetWorkResult.Success(Unit)
        }

        override suspend fun deleteFolder(folderId: Int): NetWorkResult<Unit> {
            events += "remote:delete:$folderId"
            return NetWorkResult.Success(Unit)
        }

        override suspend fun renameFolder(folderId: Int, name: String): NetWorkResult<Unit> {
            events += "remote:rename:$folderId:$name"
            return NetWorkResult.Success(Unit)
        }

        override suspend fun moveComicToFolder(comicId: Int, folderId: Int): NetWorkResult<Unit> {
            movedIds += comicId
            events += "remote:move:$comicId:$folderId"
            return moveHandler(comicId, folderId)
        }
    }

    private class RecordingLocalMutation(
        private val events: MutableList<String>,
    ) : FavoriteLocalMutation {
        val movedFavorites = mutableListOf<Triple<Int, Int, Int>>()

        override suspend fun addFromComic(accountId: Int, comic: Comic, folderId: Int) = Unit

        override suspend fun remove(accountId: Int, albumIds: Collection<Int>) = Unit

        override suspend fun moveToFolder(accountId: Int, albumId: Int, folderId: Int) {
            movedFavorites += Triple(accountId, albumId, folderId)
            events += "local:move:$accountId:$albumId:$folderId"
        }

        override suspend fun cacheFolder(accountId: Int, folderId: Int, name: String) = Unit

        override suspend fun removeFolder(accountId: Int, folderId: Int) {
            events += "local:remove-folder:$accountId:$folderId"
        }

        override suspend fun renameFolder(accountId: Int, folderId: Int, name: String) {
            events += "local:rename:$accountId:$folderId:$name"
        }
    }

    private class NoOpFavoriteDownloader : FavoriteDownloader {
        override fun downloadComics(comics: List<Comic>) = Unit
    }

    private class EmptyFavoriteLocalQuery : FavoriteLocalQuery {
        override fun pagingSource(
            accountId: Int,
            blockedTagList: List<String>,
            searchText: String,
            selectedTags: Set<String>,
            selectedAuthors: Set<String>,
            folderId: Int,
            tagLogic: TagFilterLogic,
        ): PagingSource<Int, FavoriteComicEntity> = EmptyPagingSource()

        override fun observeFolders(accountId: Int): Flow<Map<String, String>> =
            flowOf(emptyMap())

        override fun observeTagCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>> =
            flowOf(emptyMap())

        override fun observeAuthorCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>> =
            flowOf(emptyMap())

        override suspend fun getCachedFolders(accountId: Int): Map<String, String> = emptyMap()

        override suspend fun getComics(accountId: Int, albumIds: Collection<Int>): List<Comic> =
            emptyList()
    }

    private class EmptyPagingSource : PagingSource<Int, FavoriteComicEntity>() {
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, FavoriteComicEntity> =
            LoadResult.Page(data = emptyList(), prevKey = null, nextKey = null)

        override fun getRefreshKey(state: PagingState<Int, FavoriteComicEntity>): Int? = null
    }
}
