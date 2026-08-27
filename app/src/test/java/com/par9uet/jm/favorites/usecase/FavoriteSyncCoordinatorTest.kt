package com.par9uet.jm.favorites.usecase

import com.par9uet.jm.store.FavoriteSyncProgress
import com.par9uet.jm.store.FavoriteSyncReport
import com.par9uet.jm.store.FAVORITE_SCOPE_ALL
import com.par9uet.jm.retrofit.model.NetWorkResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteSyncCoordinatorTest {
    private data class Request(
        val accountId: Int,
        val folderId: Int,
        val force: Boolean,
        val onProgressWired: Boolean = true,
    )

    @Test
    fun identicalRequestsShareOneActiveSync() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val coordinator = coordinator(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) { _, _, _, _ ->
            calls++
            gate.await()
            success()
        }

        val first = async { coordinator.synchronize(7, 3, false) {} }
        val second = async { coordinator.synchronize(7, 3, false) {} }
        advanceUntilIdle()

        assertEquals(1, calls)
        gate.complete(Unit)
        assertTrue(first.await() is NetWorkResult.Success)
        assertTrue(second.await() is NetWorkResult.Success)
    }

    @Test
    fun accountChangeTurnsAnInFlightResultIntoAnError() = runTest {
        var activeAccount = 7
        val gate = CompletableDeferred<Unit>()
        val coordinator = coordinator(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            account = { activeAccount },
        ) { _, _, _, _ ->
            gate.await()
            success()
        }

        val request = async {
            coordinator.synchronize(7, 0, false) {}
        }
        advanceUntilIdle()
        activeAccount = 8
        gate.complete(Unit)

        val result = request.await()
        assertEquals("登录账号已变化", (result as NetWorkResult.Error).message)
    }

    @Test
    fun cancellationPropagatesToTheCaller() = runTest {
        val gate = CompletableDeferred<Unit>()
        val coordinator = coordinator(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) { _, _, _, _ ->
            gate.await()
            success()
        }
        val request = async {
            coordinator.synchronize(7, 0, false) {}
        }
        advanceUntilIdle()

        request.cancel()
        try {
            request.await()
            fail("取消的同步请求应抛出 CancellationException")
        } catch (_: CancellationException) {
            // Expected.
        }
        gate.cancel()
    }

    @Test
    fun forceAndLightweightRequestsKeepTheirDistinctScopeSemantics() = runTest {
        val requests = mutableListOf<Request>()
        val coordinator = coordinator(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)) { accountId, folderId, force, _ ->
            requests += Request(accountId, folderId, force)
            success()
        }

        coordinator.synchronize(7, 4, false) {}
        coordinator.synchronize(7, 4, true) {}
        advanceUntilIdle()

        assertEquals(
            listOf(
                Request(7, 4, false),
                Request(7, FAVORITE_SCOPE_ALL, true),
            ),
            requests,
        )
    }

    private fun coordinator(
        scope: CoroutineScope,
        account: () -> Int = { 7 },
        operation: suspend (
            accountId: Int,
            folderId: Int,
            force: Boolean,
            onProgress: (FavoriteSyncProgress) -> Unit,
        ) -> NetWorkResult<FavoriteSyncReport>,
    ) = FavoriteSyncCoordinator(
        applicationScope = scope,
        isActiveAccount = { accountId -> accountId == account() },
        performSync = operation,
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
