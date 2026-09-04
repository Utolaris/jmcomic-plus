package com.par9uet.jm.store

import com.par9uet.jm.data.models.User
import com.par9uet.jm.repository.CandidateSession
import com.par9uet.jm.repository.UserRepository
import com.par9uet.jm.retrofit.ActiveSessionCookieStore
import com.par9uet.jm.retrofit.model.AuthFailure
import com.par9uet.jm.retrofit.model.LoginResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.SignInDataResponse
import com.par9uet.jm.retrofit.model.SignInResponse
import com.par9uet.jm.retrofit.model.UserHistoryComicListResponse
import com.par9uet.jm.retrofit.model.UserHistoryCommentListResponse
import com.par9uet.jm.storage.CookieStorage
import com.par9uet.jm.storage.UserStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * UserManager 会话状态机测试：验证 generation + 身份校验边界下，
 * 陈旧验证结果不能覆盖更新的登录/登出，临时失败保留身份，InvalidCredentials 才清除。
 */
class UserManagerSessionTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun restoringSessionNeverFallsThroughToAuthenticatedWorkOnTimeout() = runTest {
        val readiness = SessionReadinessHolder().apply {
            set(SessionReadiness.Restoring)
        }
        val gate = AuthenticatedSessionGate(readiness)
        var requestCalls = 0
        val request = launch {
            gate.run { requestCalls++ }
        }

        advanceTimeBy(5_000)
        assertFalse(request.isCompleted)
        assertEquals(0, requestCalls)

        readiness.set(SessionReadiness.Authenticated)
        request.join()
        assertEquals(1, requestCalls)
    }

    @Test
    fun canceledAuthenticatedWaitNeverExecutesTheRequest() = runTest {
        val readiness = SessionReadinessHolder().apply {
            set(SessionReadiness.Restoring)
        }
        val gate = AuthenticatedSessionGate(readiness)
        var requestCalls = 0
        val request = launch {
            gate.run { requestCalls++ }
        }

        request.cancelAndJoin()
        readiness.set(SessionReadiness.Authenticated)

        assertTrue(request.isCancelled)
        assertEquals(0, requestCalls)
    }

    @Test
    fun unauthenticatedSessionRejectsRequestWithoutCallingIt() {
        val readiness = SessionReadinessHolder().apply {
            set(SessionReadiness.Unauthenticated)
        }
        val gate = AuthenticatedSessionGate(readiness)
        var requestCalls = 0

        assertThrows(AuthenticatedSessionRequiredException::class.java) {
            runBlocking { gate.run { requestCalls++ } }
        }
        assertEquals(0, requestCalls)
    }

    @Test
    fun authenticatedGatePropagatesCancellation() {
        val readiness = SessionReadinessHolder().apply {
            set(SessionReadiness.Authenticated)
        }
        val gate = AuthenticatedSessionGate(readiness)

        assertThrows(CancellationException::class.java) {
            runBlocking {
                gate.run<Unit> { throw CancellationException("stop") }
            }
        }
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
        private val writes = mutableListOf<List<Cookie>>()

        override fun set(cookieStore: List<Cookie>) {
            writes.add(cookieStore)
            _state.value = cookieStore
        }

        override fun get(): List<Cookie> = _state.value ?: emptyList()
        override fun remove() {
            writes.add(emptyList())
            _state.value = emptyList()
        }

        fun writesCount(): Int = writes.size
    }

    private class FakeSessionClearer : ActiveSessionCookieStore {
        var clearCount = 0
        override fun clearCookie() {
            clearCount++
        }
    }

    /**
     * 可编排的网络替身。verifyLogin 模拟真实的不可取消阻塞网络调用：
     * gate 完成前即使外部 job 被取消，调用仍会完成（返回后由 ensureActive 中止提交）。
     */
    private class GateUserRepository(
        private val cookieStorage: CookieStorage,
    ) : UserRepository {
        val verifyStarted = CompletableDeferred<Unit>()
        private val verifyGate = CompletableDeferred<NetWorkResult<CandidateSession>>()
        val activated = mutableListOf<CandidateSession>()
        var loginHandler: (suspend (String, String) -> NetWorkResult<CandidateSession>)? = null

        fun completeVerify(result: NetWorkResult<CandidateSession>) {
            verifyGate.complete(result)
        }

        override suspend fun verifyLogin(
            username: String,
            password: String
        ): NetWorkResult<CandidateSession> {
            verifyStarted.complete(Unit)
            return withContext(Dispatchers.Default + NonCancellable) {
                verifyGate.await()
            }
        }

        override suspend fun login(
            username: String,
            password: String
        ): NetWorkResult<CandidateSession> {
            return checkNotNull(loginHandler).invoke(username, password)
        }

        override fun activateVerifiedSession(verified: CandidateSession) {
            activated += verified
            if (verified.embeddedCookies.isNotEmpty()) {
                cookieStorage.set(verified.embeddedCookies)
            }
        }

        override fun clearSession() = Unit

        override suspend fun getHistoryComicList(
            page: Int
        ): NetWorkResult<UserHistoryComicListResponse> = NetWorkResult.Error("stub")

        override suspend fun deleteHistoryComic(id: Int): NetWorkResult<Unit> =
            NetWorkResult.Error("stub")

        override suspend fun getHistoryCommentList(
            page: Int,
            userId: Int
        ): NetWorkResult<UserHistoryCommentListResponse> = NetWorkResult.Error("stub")

        override suspend fun getSignData(userId: Int): NetWorkResult<SignInDataResponse> =
            NetWorkResult.Error("stub")

        override suspend fun signIn(userId: Int, dailyId: Int): NetWorkResult<SignInResponse> =
            NetWorkResult.Error("stub")
    }

    private fun user(id: Int, name: String, password: String = "pwd"): User = User(
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

    private fun loginResponse(id: Int, name: String): LoginResponse = LoginResponse(
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

    private fun avsCookie(value: String = "session-1"): Cookie = Cookie.Builder()
        .name("AVS")
        .value(value)
        .domain("18comic.vip")
        .path("/")
        .build()

    private fun manager(
        userStorage: UserStorage,
        cookieStorage: CookieStorage,
        repository: UserRepository,
        clearer: ActiveSessionCookieStore,
        readiness: SessionReadinessHolder = SessionReadinessHolder(),
    ) = UserManager(userStorage, cookieStorage, repository, clearer, readiness)

    @Test
    fun staleVerifierCannotOverwriteNewerManualLogin() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage()
        val repository = GateUserRepository(cookieStorage)
        repository.loginHandler = { _, _ ->
            NetWorkResult.Success(
                CandidateSession(
                    loginResponse = loginResponse(2, "accountB"),
                    embeddedCookies = listOf(avsCookie("session-B")),
                )
            )
        }
        val manager = manager(userStorage, cookieStorage, repository, FakeSessionClearer())

        val verifier = launch { manager.verifyStoredLogin() }
        repository.verifyStarted.await()

        // 验证 A 的网络请求仍在进行时，用户手动登录 B 并完成。
        val loginResult = manager.login("accountB", "pwdB")
        assertTrue(loginResult is NetWorkResult.Success)

        // A 的候选验证随后返回。
        repository.completeVerify(
            NetWorkResult.Success(
                CandidateSession(
                    loginResponse = loginResponse(1, "accountA"),
                    embeddedCookies = listOf(avsCookie("session-A")),
                )
            )
        )
        verifier.join()

        // 活动身份仍然是 B；存储 cookie 是 B 的会话；A 的候选从未被激活。
        assertEquals(2, manager.userState.value.data?.id)
        assertEquals("session-B", cookieStorage.get().single().value)
        assertTrue(repository.activated.none {
            it.embeddedCookies.any { c -> c.value == "session-A" }
        })
        assertTrue(repository.activated.any {
            it.embeddedCookies.any { c -> c.value == "session-B" }
        })
    }

    @Test
    fun staleVerifierCannotRestoreAfterLogout() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage(listOf(avsCookie("session-A")))
        val repository = GateUserRepository(cookieStorage)
        val manager = manager(userStorage, cookieStorage, repository, FakeSessionClearer())

        val verifier = launch { manager.verifyStoredLogin() }
        repository.verifyStarted.await()

        // 验证进行中用户登出。
        manager.clearUser()
        assertEquals(0, manager.userState.value.data?.id)

        repository.completeVerify(
            NetWorkResult.Success(
                CandidateSession(
                    loginResponse = loginResponse(1, "accountA"),
                    embeddedCookies = listOf(avsCookie("session-A")),
                )
            )
        )
        verifier.join()

        // 登出保持有效：身份为空、cookie 存储被清空、没有发生激活。
        assertEquals(0, manager.userState.value.data?.id)
        assertTrue(cookieStorage.get().isEmpty())
        assertEquals(User.create(), userStorage.get())
        assertTrue(repository.activated.isEmpty())
    }

    @Test
    fun transientVerifyFailureRetainsCachedIdentity() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage(listOf(avsCookie("session-A")))
        val repository = GateUserRepository(cookieStorage)
        val readiness = SessionReadinessHolder()
        val manager = manager(userStorage, cookieStorage, repository, FakeSessionClearer(), readiness)

        val verifier = launch { manager.verifyStoredLogin() }
        repository.verifyStarted.await()
        repository.completeVerify(
            NetWorkResult.Error("网络连接超时", authFailure = AuthFailure.TemporaryFailure)
        )
        verifier.join()

        assertEquals(1, manager.userState.value.data?.id)
        assertEquals("session-A", cookieStorage.get().single().value)
        assertTrue(repository.activated.isEmpty())
        assertEquals(SessionReadiness.Authenticated, readiness.state.value)
        assertEquals(false, manager.userState.value.isLoading)
    }

    @Test
    fun invalidCredentialsClearsPersistentIdentity() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage(listOf(avsCookie("session-A")))
        val repository = GateUserRepository(cookieStorage)
        val clearer = FakeSessionClearer()
        val readiness = SessionReadinessHolder()
        val manager = manager(userStorage, cookieStorage, repository, clearer, readiness)

        val verifier = launch { manager.verifyStoredLogin() }
        repository.verifyStarted.await()
        repository.completeVerify(
            NetWorkResult.Error(
                "账号或密码错误",
                code = 401,
                authFailure = AuthFailure.InvalidCredentials
            )
        )
        verifier.join()

        assertEquals(0, manager.userState.value.data?.id)
        assertEquals(User.create(), userStorage.get())
        assertTrue(cookieStorage.get().isEmpty())
        assertEquals(1, clearer.clearCount)
        assertEquals(SessionReadiness.Unauthenticated, readiness.state.value)
    }

    @Test
    fun successfulCandidateVerificationPromotesFullSessionWithAVS() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage()
        val repository = GateUserRepository(cookieStorage)
        val readiness = SessionReadinessHolder()
        val manager = manager(userStorage, cookieStorage, repository, FakeSessionClearer(), readiness)

        val verifier = launch { manager.verifyStoredLogin() }
        repository.verifyStarted.await()
        repository.completeVerify(
            NetWorkResult.Success(
                CandidateSession(
                    loginResponse = loginResponse(1, "accountA"),
                    embeddedCookies = listOf(avsCookie("session-A")),
                )
            )
        )
        verifier.join()

        assertEquals(1, manager.userState.value.data?.id)
        // 完整 embedded cookie（含 AVS）被持久化为活动会话。
        val persisted = cookieStorage.get()
        assertEquals(1, persisted.size)
        assertEquals("AVS", persisted.single().name)
        assertEquals("session-A", persisted.single().value)
        assertEquals(SessionReadiness.Authenticated, readiness.state.value)
        assertEquals(1, repository.activated.size)
    }

    @Test
    fun promotedSessionIsAvailableToAuthenticatedFeatures() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage()
        val repository = GateUserRepository(cookieStorage)
        val manager = manager(userStorage, cookieStorage, repository, FakeSessionClearer())

        val verifier = launch { manager.verifyStoredLogin() }
        repository.verifyStarted.await()
        repository.completeVerify(
            NetWorkResult.Success(
                CandidateSession(
                    loginResponse = loginResponse(1, "accountA"),
                    embeddedCookies = listOf(avsCookie("session-A")),
                )
            )
        )
        verifier.join()

        val activeCookie = cookieStorage.get().single()
        assertEquals("AVS", activeCookie.name)
        assertEquals("session-A", activeCookie.value)
    }

    @Test
    fun savedActiveSessionSurvivesProcessStyleReconstruction() = runBlocking {
        val savedUser = user(1, "accountA")
        val savedCookies = listOf(avsCookie("session-A"))
        val userStorage = FakeUserStorage(savedUser)
        val cookieStorage = FakeCookieStorage(savedCookies)
        val repository = GateUserRepository(cookieStorage)
        val readiness = SessionReadinessHolder()

        // 模拟进程重启：用同一持久化存储重建 UserManager。
        val manager = manager(userStorage, cookieStorage, repository, FakeSessionClearer(), readiness)

        assertEquals(1, manager.userState.value.data?.id)
        assertEquals("accountA", manager.userState.value.data?.username)
        assertEquals("session-A", cookieStorage.get().single().value)
        // 持久化 cookie 可被 EmbeddedClientManager 同步恢复，认证功能可立即使用。
        assertEquals(SessionReadiness.Authenticated, readiness.state.value)
        assertEquals(SessionReadiness.Authenticated, manager.authState.value)
    }

    @Test
    fun cachedIdentityWithoutRestoredSessionIsRestoringNotLoggedOut() {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage()
        val repository = GateUserRepository(cookieStorage)
        val readiness = SessionReadinessHolder()

        val manager = manager(userStorage, cookieStorage, repository, FakeSessionClearer(), readiness)

        assertEquals(1, manager.userState.value.data?.id)
        assertEquals(SessionReadiness.Restoring, manager.authState.value)
        assertFalse(manager.authState.value == SessionReadiness.Unauthenticated)
    }

    @Test
    fun noCachedIdentityIsUnauthenticatedAfterConstruction() {
        val userStorage = FakeUserStorage()
        val cookieStorage = FakeCookieStorage()
        val repository = GateUserRepository(cookieStorage)
        val manager = manager(userStorage, cookieStorage, repository, FakeSessionClearer())

        assertEquals(SessionReadiness.Unauthenticated, manager.authState.value)
    }

    @Test
    fun boundRemoteWorkRunsOnlyWhileSnapshotStaysCurrent() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage()
        val repository = GateUserRepository(cookieStorage)
        val manager = manager(userStorage, cookieStorage, repository, FakeSessionClearer())
        val snapshot = manager.currentSessionSnapshot()
        var remoteCalls = 0

        val committed = manager.withBoundRemoteSession(
            accountId = snapshot.accountId,
            generation = snapshot.generation,
        ) {
            remoteCalls++
            "result-A"
        }

        assertEquals("result-A", committed)
        assertEquals(1, remoteCalls)
    }

    @Test
    fun staleSnapshotNeverObtainsBoundRemoteCapability() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage()
        val repository = GateUserRepository(cookieStorage)
        val manager = manager(userStorage, cookieStorage, repository, FakeSessionClearer())
        val staleGeneration = manager.currentSessionSnapshot().generation - 1L
        var remoteCalls = 0

        val committed = manager.withBoundRemoteSession(
            accountId = 1,
            generation = staleGeneration,
        ) {
            remoteCalls++
            "result"
        }

        assertNull(committed)
        assertEquals(0, remoteCalls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sessionTransitionCannotDeadlockBoundRemoteLocalCommit() = runTest {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage()
        val repository = GateUserRepository(cookieStorage)
        val manager = manager(userStorage, cookieStorage, repository, FakeSessionClearer())
        val snapshotA = manager.currentSessionSnapshot()
        val blockStarted = CompletableDeferred<Unit>()
        val allowLocalCommit = CompletableDeferred<Unit>()

        val boundJob = async {
            manager.withBoundRemoteSession(
                accountId = snapshotA.accountId,
                generation = snapshotA.generation,
            ) {
                blockStarted.complete(Unit)
                allowLocalCommit.await()
                manager.withCurrentSession(
                    accountId = snapshotA.accountId,
                    generation = snapshotA.generation,
                ) { "committed-A" }
            }
        }

        blockStarted.await()
        val logoutJob = async { manager.clearUser() }
        // Let logout reach its first contested lock before the bound work requests loginMutex.
        runCurrent()
        allowLocalCommit.complete(Unit)

        withTimeout(1_000) {
            assertEquals("committed-A", boundJob.await())
            logoutJob.await()
        }
        assertEquals(0, manager.userState.value.data?.id)
    }

    @Test
    fun manualLoginPromotesCandidateOnlyAfterItsNetworkResultReturns() = runTest {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage(listOf(avsCookie("session-A")))
        val repository = GateUserRepository(cookieStorage)
        val candidateStarted = CompletableDeferred<Unit>()
        val candidateResult = CompletableDeferred<NetWorkResult<CandidateSession>>()
        repository.loginHandler = { _, _ ->
            candidateStarted.complete(Unit)
            candidateResult.await()
        }
        val manager = manager(userStorage, cookieStorage, repository, FakeSessionClearer())

        val loginJob = async { manager.login("accountB", "pwdB") }
        candidateStarted.await()

        // The candidate network request is in flight. No shared-session promotion or identity
        // write may happen before UserManager enters its generation-checked commit.
        assertTrue(repository.activated.isEmpty())
        assertEquals(1, manager.userState.value.data?.id)
        assertEquals("session-A", cookieStorage.get().single().value)

        candidateResult.complete(
            NetWorkResult.Success(
                CandidateSession(
                    loginResponse = loginResponse(2, "accountB"),
                    embeddedCookies = listOf(avsCookie("session-B")),
                )
            )
        )

        assertTrue(loginJob.await() is NetWorkResult.Success)
        assertEquals(2, manager.userState.value.data?.id)
        assertEquals("session-B", cookieStorage.get().single().value)
        assertEquals(1, repository.activated.size)
    }

    @Test
    fun nonCancellableBoundRemoteFinishesBeforeManualLoginCandidateStarts() = runBlocking {
        val userStorage = FakeUserStorage(user(1, "accountA"))
        val cookieStorage = FakeCookieStorage(listOf(avsCookie("session-A")))
        val repository = GateUserRepository(cookieStorage)
        val candidateStarted = CompletableDeferred<Unit>()
        repository.loginHandler = { _, _ ->
            candidateStarted.complete(Unit)
            NetWorkResult.Success(
                CandidateSession(
                    loginResponse = loginResponse(2, "accountB"),
                    embeddedCookies = listOf(avsCookie("session-B")),
                )
            )
        }
        val manager = manager(userStorage, cookieStorage, repository, FakeSessionClearer())
        val snapshotA = manager.currentSessionSnapshot()
        val remoteStarted = CountDownLatch(1)
        val releaseRemote = CountDownLatch(1)

        val remoteJob = async(Dispatchers.IO) {
            manager.withBoundRemoteSession(snapshotA.accountId, snapshotA.generation) {
                remoteStarted.countDown()
                // Deliberately blocking Java primitive: coroutine cancellation cannot release it.
                check(releaseRemote.await(2, TimeUnit.SECONDS))
                "remote-A"
            }
        }
        assertTrue(remoteStarted.await(1, TimeUnit.SECONDS))

        val loginJob = async(Dispatchers.Default) { manager.login("accountB", "pwdB") }
        assertNull(withTimeoutOrNull(150) { candidateStarted.await() })

        releaseRemote.countDown()
        assertEquals("remote-A", withTimeout(2_000) { remoteJob.await() })
        withTimeout(2_000) { candidateStarted.await() }
        assertTrue(withTimeout(2_000) { loginJob.await() } is NetWorkResult.Success)
        assertEquals(2, manager.userState.value.data?.id)
    }
}
