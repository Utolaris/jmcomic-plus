package com.par9uet.jm.favorites.sync

import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.retrofit.model.NetworkErrorKind
import com.par9uet.jm.favorites.TestFavoriteSession
import com.par9uet.jm.favorites.data.FavoriteSessionSnapshot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertTrue
import com.par9uet.jm.store.FAVORITE_SCOPE_ALL
import com.par9uet.jm.store.FavoriteSyncProgress
import com.par9uet.jm.store.FavoriteSyncReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteSyncControllerTest {
    @Test
    fun `failure kind reaches UI and a successful retry clears it`() = runTest {
        var shouldFail = true
        val controller = controller(backgroundScope, TestFavoriteSession(), mutableListOf(), operation = { _, _, _, _ ->
            if (shouldFail) {
                NetWorkResult.Error("登录已失效，请重新登录后同步", kind = NetworkErrorKind.Authentication)
            } else {
                success()
            }
        })
        controller.request(FavoriteSyncRequestKind.FORCE)
        runCurrent()
        assertEquals(NetworkErrorKind.Authentication, controller.state.value.errorKind)
        assertEquals("登录已失效，请重新登录后同步", controller.state.value.errorMessage)
        assertTrue(controller.state.value.isForceRefresh)
        shouldFail = false
        controller.request(FavoriteSyncRequestKind.MANUAL)
        runCurrent()
        assertEquals(null, controller.state.value.errorKind)
        assertEquals(null, controller.state.value.errorMessage)
    }

    private data class Request(
        val accountId: Int,
        val folderId: Int,
        val force: Boolean,
    )

    @Test
    fun `manual request bypasses the auto window but never overlaps`() = runTest {
        val session = TestFavoriteSession()
        val requests = mutableListOf<Request>()
        val gate = CompletableDeferred<Unit>()
        val controller = controller(backgroundScope, session, requests, operation = { _, _, _, _ ->
            gate.await()
            success()
        })

        controller.request(FavoriteSyncRequestKind.AUTO, folderId = 1)
        runCurrent()
        controller.request(FavoriteSyncRequestKind.MANUAL, folderId = 3)

        assertEquals(listOf(Request(7, 1, false)), requests)
        gate.complete(Unit)
        advanceUntilIdle()
        runCurrent()
        assertFalse(controller.state.value.isSyncing)
    }

    @Test
    fun `force refresh bypasses the auto window and always uses all favorites`() = runTest {
        val session = TestFavoriteSession()
        val requests = mutableListOf<Request>()
        val controller = controller(backgroundScope, session, requests, operation = { _, _, _, _ -> success() })

        controller.request(FavoriteSyncRequestKind.AUTO, folderId = 4)
        runCurrent()
        controller.request(FavoriteSyncRequestKind.FORCE, folderId = 9)
        runCurrent()

        assertEquals(
            listOf(
                Request(7, 4, false),
                Request(7, FAVORITE_SCOPE_ALL, true),
            ),
            requests,
        )
    }

    @Test
    fun `automatic requests keep the latest folder for one trailing sync`() = runTest {
        val session = TestFavoriteSession()
        val clock = longArrayOf(0L)
        val requests = mutableListOf<Request>()
        val controller = controller(
            applicationScope = backgroundScope,
            session = session,
            requests = requests,
            operation = { _, _, _, _ -> success() },
            intervalMillis = 100L,
            timeSource = { clock[0] },
        )

        controller.request(FavoriteSyncRequestKind.AUTO, folderId = 0)
        runCurrent()
        clock[0] = 10L
        controller.request(FavoriteSyncRequestKind.AUTO, folderId = 1)
        clock[0] = 20L
        controller.request(FavoriteSyncRequestKind.AUTO, folderId = 2)
        clock[0] = 100L
        advanceTimeBy(100L)
        advanceUntilIdle()

        assertEquals(
            listOf(
                Request(7, 0, false),
                Request(7, 2, false),
            ),
            requests,
        )
    }

    @Test
    fun `account change cancels pending trailing work and resets visible state`() = runTest {
        val session = TestFavoriteSession()
        val clock = longArrayOf(0L)
        val requests = mutableListOf<Request>()
        val controller = controller(
            applicationScope = backgroundScope,
            session = session,
            requests = requests,
            operation = { _, _, _, _ -> success() },
            intervalMillis = 100L,
            timeSource = { clock[0] },
        )

        controller.request(FavoriteSyncRequestKind.AUTO, folderId = 0)
        runCurrent()
        clock[0] = 10L
        controller.request(FavoriteSyncRequestKind.AUTO, folderId = 2)
        session.switchAccount(8)
        runCurrent()
        clock[0] = 100L
        advanceTimeBy(100L)
        advanceUntilIdle()

        assertEquals(listOf(Request(7, 0, false)), requests)
        assertFalse(controller.state.value.isSyncing)
    }

    @Test
    fun `old uncancellable result cannot clear new sync after A B A switch`() = runTest {
        val session = TestFavoriteSession()
        val oldResult = CompletableDeferred<Unit>()
        val newResult = CompletableDeferred<Unit>()
        var calls = 0
        val controller = controller(backgroundScope, session, mutableListOf(), operation = { _, _, _, progress ->
            calls++
            if (calls == 1) {
                withContext(NonCancellable) {
                    oldResult.await()
                    progress(FavoriteSyncProgress(99, 100, "old"))
                }
            } else {
                newResult.await()
            }
            success()
        })
        controller.request(FavoriteSyncRequestKind.MANUAL, 1)
        runCurrent()
        session.switchAccount(8)
        runCurrent()
        session.switchAccount(7)
        // Request must observe the session even before the account collector runs.
        controller.request(FavoriteSyncRequestKind.FORCE)
        runCurrent()
        oldResult.complete(Unit)
        runCurrent()
        assertEquals(2, calls)
        assertTrue(controller.state.value.isSyncing)
        assertTrue(controller.state.value.isForceRefresh)
        assertEquals(0, controller.state.value.completed)
        newResult.complete(Unit)
        runCurrent()
        assertFalse(controller.state.value.isSyncing)
    }

    @Test
    fun `same account reauthentication invalidates in flight work`() = runTest {
        val session = TestFavoriteSession()
        val requests = mutableListOf<Request>()
        val controller = controller(backgroundScope, session, requests, operation = { _, _, _, _ ->
            kotlinx.coroutines.awaitCancellation()
        })
        controller.request(FavoriteSyncRequestKind.AUTO)
        runCurrent()
        session.switchAccount(7)
        controller.request(FavoriteSyncRequestKind.AUTO)
        runCurrent()
        assertEquals(2, requests.size)
        assertTrue(controller.state.value.isSyncing)
    }

    @Test
    fun `failed and cancelled operations release the sync slot`() = runTest {
        val session = TestFavoriteSession()
        var calls = 0
        val controller = controller(backgroundScope, session, mutableListOf(), operation = { _, _, _, _ ->
            calls++
            when (calls) {
                1 -> throw IllegalStateException("offline")
                2 -> throw CancellationException("cancelled")
                else -> success()
            }
        })
        controller.request(FavoriteSyncRequestKind.MANUAL)
        runCurrent()
        assertEquals("offline", controller.state.value.errorMessage)
        assertFalse(controller.state.value.isSyncing)
        repeat(2) {
            controller.request(FavoriteSyncRequestKind.MANUAL)
            runCurrent()
            assertFalse(controller.state.value.isSyncing)
        }
        assertEquals(3, calls)
    }

    private fun controller(
        applicationScope: CoroutineScope,
        session: TestFavoriteSession,
        requests: MutableList<Request>,
        operation: suspend (
            accountId: Int,
            folderId: Int,
            force: Boolean,
            onProgress: (FavoriteSyncProgress) -> Unit,
        ) -> NetWorkResult<FavoriteSyncReport>,
        intervalMillis: Long = 100L,
        timeSource: () -> Long = { 0L },
    ): FavoriteSyncController = FavoriteSyncController(
        session,
        { snapshot: FavoriteSessionSnapshot, folder: Int, force: Boolean,
          progress: (FavoriteSyncProgress) -> Unit ->
            requests += Request(snapshot.accountId, folder, force)
            operation(snapshot.accountId, folder, force, progress)
        },
        applicationScope,
        FavoriteAutoSyncCoordinator(intervalMillis, timeSource),
    )

    private fun success() = NetWorkResult.Success(
        FavoriteSyncReport(
            added = 0,
            removed = 0,
            changed = 0,
            unchanged = 0,
            metadataFetched = 0,
        )
    )
}
