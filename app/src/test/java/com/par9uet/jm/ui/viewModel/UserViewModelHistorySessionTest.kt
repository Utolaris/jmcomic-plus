package com.par9uet.jm.ui.viewModel

import androidx.paging.LoadState
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.core.model.SignInData
import com.par9uet.jm.core.model.User
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.data.models.ActionResult
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicPage
import com.par9uet.jm.data.models.CommentPage
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.download.DownloadWorkScheduler
import com.par9uet.jm.download.atom.DownloadFileRemoval
import com.par9uet.jm.download.coordinator.DownloadExecutionControl
import com.par9uet.jm.download.coordinator.DownloadManager
import com.par9uet.jm.download.molecule.DownloadTaskOperations
import com.par9uet.jm.retrofit.model.LoginResponse
import com.par9uet.jm.session.CandidateSession
import com.par9uet.jm.session.SessionReadiness
import com.par9uet.jm.session.SessionReadinessHolder
import com.par9uet.jm.session.UserManager
import com.par9uet.jm.session.UserRepository
import com.par9uet.jm.storage.ContentPreferences
import com.par9uet.jm.storage.CookieStorage
import com.par9uet.jm.storage.UserStorage
import java.lang.reflect.Proxy
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Cookie
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R08: history comic paging and multi-select must follow the same account+generation
 * lifecycle as history comments. A→B, logout, and refresh failure must clear A's display
 * and selection immediately; A's ids must never submit under B's session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserViewModelHistorySessionTest {
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `account switch rebuilds history pager and clears selection`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.enterHistoryEdit(comicId = 101)
        assertTrue(environment.viewModel.historyEditState.value.editing)
        assertEquals(setOf(101), environment.viewModel.historyEditState.value.selectedComicIds)
        assertEquals(1, environment.viewModel.historyEditState.value.session?.accountId)

        val presenterA = presentHistory(environment.viewModel)
        assertEquals(listOf(101), presenterA.ids())
        val pagesForA = environment.repository.historyPageRequests.size

        environment.loginAs(id = 2, username = "accountB")
        runCurrent()

        val edit = environment.viewModel.historyEditState.value
        assertFalse(edit.editing)
        assertTrue(edit.selectedComicIds.isEmpty())
        assertNull(edit.session)

        // A new Pager must be created for B; collecting it loads B's pages, never A's cache.
        val presenterB = presentHistory(environment.viewModel)
        assertEquals(listOf(201), presenterB.ids())
        assertTrue(environment.repository.historyPageRequests.size > pagesForA)
    }

    @Test
    fun `logout immediately clears history display and selection`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.enterHistoryEdit(comicId = 101)
        val presenterA = presentHistory(environment.viewModel)
        assertEquals(listOf(101), presenterA.ids())

        environment.repository.activeAccountIdForHistory = 0
        environment.viewModel.logout()
        runCurrent()

        val edit = environment.viewModel.historyEditState.value
        assertFalse(edit.editing)
        assertTrue(edit.selectedComicIds.isEmpty())
        assertNull(edit.session)

        // Unauthenticated pager starts empty instead of reusing A's items.
        val presenterOut = presentHistory(environment.viewModel)
        assertTrue(presenterOut.ids().isEmpty())
    }

    @Test
    fun `refresh failure under B never reuses A's cached pages`() = runTest(scheduler) {
        val environment = environment(historyFailuresAfterSwitch = true)
        runCurrent()

        val presenterA = presentHistory(environment.viewModel)
        assertEquals(listOf(101), presenterA.ids())

        environment.loginAs(id = 2, username = "accountB")
        runCurrent()

        val presenterB = presentHistory(environment.viewModel)
        assertTrue(
            "B must not see A's cached history after a failed refresh, got ${presenterB.ids()}",
            presenterB.ids().isEmpty(),
        )
        assertTrue(presenterB.refreshState() is LoadState.Error)
    }

    @Test
    fun `stale A selection is not submitted under B session`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.enterHistoryEdit(comicId = 101)
        environment.loginAs(id = 2, username = "accountB")
        runCurrent()

        // Dialog/selection race: A's comics are still held by the caller, but the
        // selection ownership snapshot no longer matches B's session.
        environment.viewModel.deleteHistoryComics(listOf(historyComic(101)))
        runCurrent()

        assertTrue(
            "deleteHistoryComic must not run for a stale selection, calls=${environment.repository.deletedIds}",
            environment.repository.deletedIds.isEmpty(),
        )
        assertTrue(environment.viewModel.historyEditState.value.selectedComicIds.isEmpty())
    }

    @Test
    fun `selection under the current session can still delete`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.enterHistoryEdit(comicId = 101)
        environment.viewModel.deleteHistoryComics(listOf(historyComic(101)))
        runCurrent()

        assertEquals(listOf(101), environment.repository.deletedIds)
        assertTrue(environment.viewModel.historyEditState.value.selectedComicIds.isEmpty())
    }

    @Test
    fun `logout before delete refuses the batch`() = runTest(scheduler) {
        val environment = environment()
        runCurrent()

        environment.viewModel.enterHistoryEdit(comicId = 101)
        environment.viewModel.logout()
        runCurrent()

        environment.viewModel.deleteHistoryComics(listOf(historyComic(101)))
        runCurrent()

        assertTrue(environment.repository.deletedIds.isEmpty())
    }

    private suspend fun TestScope.presentHistory(
        viewModel: UserViewModel,
    ): HistoryItemPresenter {
        val data = viewModel.historyComicPager.first()
        val presenter = HistoryItemPresenter(coroutineContext)
        val job = backgroundScope.launch(Dispatchers.Unconfined) {
            presenter.collectFrom(data)
        }
        // Drive the test dispatcher until the first refresh settles (success or error).
        repeat(200) {
            val refresh = presenter.refreshState()
            if (refresh is LoadState.NotLoading || refresh is LoadState.Error) return@repeat
            runCurrent()
        }
        presenter.attachJob(job)
        return presenter
    }

    private class HistoryItemPresenter(
        context: CoroutineContext,
    ) : PagingDataPresenter<Comic>(context) {
        private var job: Job? = null

        override suspend fun presentPagingDataEvent(event: PagingDataEvent<Comic>) = Unit

        fun attachJob(collectJob: Job) {
            job = collectJob
        }

        fun refreshState(): LoadState? = loadStateFlow.value?.refresh

        fun ids(): List<Int> = (0 until size).mapNotNull { index -> peek(index)?.id }
    }

    private class FakeUserStorage(initial: User = User.create()) : UserStorage {
        private val state = MutableStateFlow(initial)
        override fun get(): User = state.value
        override fun set(user: User) {
            state.value = user
        }

        override fun remove() {
            state.value = User.create()
        }
    }

    private class FakeCookieStorage(initial: List<Cookie> = emptyList()) : CookieStorage {
        private val _state = MutableStateFlow<List<Cookie>?>(initial)
        override val state: StateFlow<List<Cookie>?> = _state.asStateFlow()
        override fun set(cookieStore: List<Cookie>) {
            _state.value = cookieStore
        }

        override fun get(): List<Cookie> = _state.value ?: emptyList()
        override fun remove() {
            _state.value = emptyList()
        }
    }

    private class HistoryRepository : UserRepository {
        val historyPageRequests = mutableListOf<Int>()
        val deletedIds = mutableListOf<Int>()
        var currentAccountId: Int = 1
        var activeAccountIdForHistory: Int = 1
        var failHistoryAfterAccountSwitch = false

        override suspend fun login(username: String, password: String): NetWorkResult<CandidateSession> {
            val id = currentAccountId
            return NetWorkResult.Success(
                CandidateSession(
                    loginResponse = testLoginResponse(id, username),
                    embeddedCookies = listOf(testAvsCookie("session-$id")),
                )
            )
        }

        override fun activateVerifiedSession(verified: CandidateSession) = Unit
        override fun clearSession() = Unit

        override suspend fun getHistoryComicList(page: Int): NetWorkResult<ComicPage> {
            historyPageRequests += page
            val accountId = activeAccountIdForHistory
            if (accountId <= 0) {
                return NetWorkResult.Success(ComicPage(items = emptyList(), total = null))
            }
            if (failHistoryAfterAccountSwitch && accountId != 1) {
                return NetWorkResult.Error("refresh failed")
            }
            val id = if (accountId == 1) 101 else 201
            return NetWorkResult.Success(
                ComicPage(items = listOf(historyComic(id)), total = null)
            )
        }

        override suspend fun deleteHistoryComic(id: Int): NetWorkResult<Unit> {
            deletedIds += id
            return NetWorkResult.Success(Unit)
        }

        override suspend fun getHistoryCommentList(page: Int, userId: Int): NetWorkResult<CommentPage> =
            NetWorkResult.Success(CommentPage(emptyList(), total = 0))

        override suspend fun getSignData(userId: Int): NetWorkResult<SignInData> =
            NetWorkResult.Error("unused")

        override suspend fun signIn(userId: Int, dailyId: Int): NetWorkResult<ActionResult> =
            NetWorkResult.Error("unused")
    }

    private class FakeContentPreferences : ContentPreferences {
        override val blockedTags: StateFlow<List<String>> = MutableStateFlow(emptyList())
        override val homeExcludedTags: StateFlow<List<String>> = MutableStateFlow(emptyList())
    }

    private class Environment(
        val viewModel: UserViewModel,
        val userManager: UserManager,
        val repository: HistoryRepository,
    ) {
        suspend fun loginAs(id: Int, username: String) {
            repository.currentAccountId = id
            repository.activeAccountIdForHistory = id
            userManager.login(username, "pwd")
        }
    }

    private fun environment(
        historyFailuresAfterSwitch: Boolean = false,
    ): Environment {
        val repository = HistoryRepository().apply {
            failHistoryAfterAccountSwitch = historyFailuresAfterSwitch
        }
        val cookies = FakeCookieStorage(listOf(testAvsCookie("session-1")))
        val readiness = SessionReadinessHolder()
        val userManager = UserManager(
            FakeUserStorage(testUser(1, "accountA")),
            cookies,
            repository,
            readiness,
        )
        readiness.set(SessionReadiness.Authenticated)
        val viewModel = UserViewModel(
            userManager = userManager,
            userRepository = repository,
            toastManager = ToastManager(),
            contentPreferences = FakeContentPreferences(),
            downloadManager = testDownloadManager(CoroutineScope(Dispatchers.Default)),
        )
        return Environment(viewModel, userManager, repository)
    }
}

private fun historyComic(id: Int): Comic = Comic(
    id = id,
    name = "comic-$id",
    authorList = listOf("author"),
    description = "",
    readCount = 0,
    likeCount = 0,
    commentCount = 0,
    tagList = emptyList(),
    roleList = emptyList(),
    workList = emptyList(),
    price = 0,
)

private fun testLoginResponse(id: Int, name: String): LoginResponse = LoginResponse(
    uid = id,
    username = name,
    email = "",
    photo = "",
    coin = "0",
    album_favorites = 0,
    level_name = "M",
    level = 1,
    nextLevelExp = 100,
    exp = 0,
    expPercent = 0.0,
    album_favorites_max = 100,
)

private fun testAvsCookie(value: String): Cookie = Cookie.Builder()
    .name("AVS")
    .value(value)
    .domain("18comic.vip")
    .path("/")
    .build()

private fun testUser(id: Int, name: String, password: String = "pwd"): User = User(
    id = id,
    username = name,
    password = password,
    avatar = "",
    level = 1,
    levelName = "M",
    currentLevelExp = 0,
    nextLevelExp = 100,
    currentCollectCount = 0,
    maxCollectCount = 100,
    jCoin = 0,
)

private fun testDownloadManager(scope: CoroutineScope): DownloadManager {
    val dao = Proxy.newProxyInstance(
        DownloadComicDao::class.java.classLoader,
        arrayOf(DownloadComicDao::class.java),
    ) { _, method, _ -> error("Unexpected DownloadComicDao.${method.name}") } as DownloadComicDao
    return DownloadManager(
        operations = DownloadTaskOperations(dao, DownloadFileRemoval { _, _ -> true }),
        scope = scope,
        toastManager = ToastManager(),
        downloadWorkScheduler = object : DownloadWorkScheduler {
            override fun enqueue(comicIds: Collection<Int>) = Unit
            override suspend fun cancel(comicIds: Collection<Int>) = Unit
        },
        coordinator = object : DownloadExecutionControl {
            override suspend fun <T> withStoppedDownloads(
                comicIds: Collection<Int>,
                block: suspend () -> T,
            ): T = block()
        },
    )
}
