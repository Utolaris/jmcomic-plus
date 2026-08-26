package com.par9uet.jm.favorites.sync

import com.par9uet.jm.data.models.CollectComicOrderFilter
import com.par9uet.jm.retrofit.model.NetWorkResult
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
    private data class Request(
        val accountId: Int,
        val folderId: Int,
        val force: Boolean,
    )

    @Test
    fun `manual request bypasses the auto window but never overlaps`() = runTest {
        val accountFlow = MutableStateFlow(7)
        var accountId = 7
        val requests = mutableListOf<Request>()
        val gate = CompletableDeferred<Unit>()
        val controller = controller(backgroundScope, accountFlow, { accountId }, requests, operation = { _, _, _, _, _ ->
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
        val accountFlow = MutableStateFlow(7)
        val requests = mutableListOf<Request>()
        val controller = controller(backgroundScope, accountFlow, { 7 }, requests, operation = { _, _, _, _, _ -> success() })

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
        val accountFlow = MutableStateFlow(7)
        val clock = longArrayOf(0L)
        val requests = mutableListOf<Request>()
        val controller = controller(
            applicationScope = backgroundScope,
            accountFlow = accountFlow,
            currentAccountId = { 7 },
            requests = requests,
            operation = { _, _, _, _, _ -> success() },
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
        val accountFlow = MutableStateFlow(7)
        var accountId = 7
        val clock = longArrayOf(0L)
        val requests = mutableListOf<Request>()
        val controller = controller(
            applicationScope = backgroundScope,
            accountFlow = accountFlow,
            currentAccountId = { accountId },
            requests = requests,
            operation = { _, _, _, _, _ -> success() },
            intervalMillis = 100L,
            timeSource = { clock[0] },
        )

        controller.request(FavoriteSyncRequestKind.AUTO, folderId = 0)
        runCurrent()
        clock[0] = 10L
        controller.request(FavoriteSyncRequestKind.AUTO, folderId = 2)
        accountId = 8
        accountFlow.value = 8
        runCurrent()
        clock[0] = 100L
        advanceTimeBy(100L)
        advanceUntilIdle()

        assertEquals(listOf(Request(7, 0, false)), requests)
        assertFalse(controller.state.value.isSyncing)
    }

    private fun controller(
        applicationScope: CoroutineScope,
        accountFlow: MutableStateFlow<Int>,
        currentAccountId: () -> Int,
        requests: MutableList<Request>,
        operation: suspend (
            accountId: Int,
            folderId: Int,
            force: Boolean,
            order: CollectComicOrderFilter,
            onProgress: (FavoriteSyncProgress) -> Unit,
        ) -> NetWorkResult<FavoriteSyncReport>,
        intervalMillis: Long = 100L,
        timeSource: () -> Long = { 0L },
    ): FavoriteSyncController = FavoriteSyncController(
        accountFlow,
        currentAccountId,
        { account: Int, folder: Int, force: Boolean,
          order: CollectComicOrderFilter, progress: (FavoriteSyncProgress) -> Unit ->
            requests += Request(account, folder, force)
            operation(account, folder, force, order, progress)
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
