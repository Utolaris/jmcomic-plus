package com.par9uet.jm.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteAutoSyncCoordinatorTest {
    private val intervalMillis = FAVORITE_AUTO_SYNC_INTERVAL_MILLIS

    private class FakeClock {
        var nowMillis = 0L
    }

    private fun coordinator(clock: FakeClock) = FavoriteAutoSyncCoordinator(
        intervalMillis = intervalMillis,
        timeSource = { clock.nowMillis },
    )

    private fun startNow(result: FavoriteAutoRequestResult): Int =
        (result as FavoriteAutoRequestResult.StartNow).folderId

    private fun coalescedDelay(result: FavoriteAutoRequestResult): Long =
        (result as FavoriteAutoRequestResult.Coalesced).trailingDelayMs

    @Test
    fun `test 1 - first request starts immediately`() {
        val clock = FakeClock()
        val result = coordinator(clock).request(folderId = 0, isSyncing = false)
        assertTrue(result is FavoriteAutoRequestResult.StartNow)
        assertEquals(0, startNow(result))
    }

    @Test
    fun `test 2 - request inside the window is coalesced`() {
        val clock = FakeClock()
        val coordinator = coordinator(clock)
        assertTrue(coordinator.request(folderId = 0, isSyncing = false) is FavoriteAutoRequestResult.StartNow)
        clock.nowMillis = 5_000L

        val result = coordinator.request(folderId = 1, isSyncing = false)
        assertTrue(result is FavoriteAutoRequestResult.Coalesced)
        assertEquals(25_000L, coalescedDelay(result))
        assertTrue(coordinator.hasPendingAutoSync())
        assertEquals(1, coordinator.pendingAutoFolderId())
    }

    @Test
    fun `test 3 - multiple requests keep exactly one pending`() {
        val clock = FakeClock()
        val coordinator = coordinator(clock)
        assertTrue(coordinator.request(folderId = 0, isSyncing = false) is FavoriteAutoRequestResult.StartNow)
        clock.nowMillis = 5_000L
        coordinator.request(folderId = 1, isSyncing = false)
        clock.nowMillis = 10_000L
        coordinator.request(folderId = 2, isSyncing = false)
        clock.nowMillis = 20_000L
        coordinator.request(folderId = 3, isSyncing = false)

        assertTrue(coordinator.hasPendingAutoSync())
        // Latest requested folder wins.
        assertEquals(3, coordinator.pendingAutoFolderId())
    }

    @Test
    fun `test 4 and 5 - exactly one trailing sync at window end, then pending clears`() {
        val clock = FakeClock()
        val coordinator = coordinator(clock)
        assertTrue(coordinator.request(folderId = 0, isSyncing = false) is FavoriteAutoRequestResult.StartNow)
        clock.nowMillis = 5_000L
        coordinator.request(folderId = 1, isSyncing = false)

        clock.nowMillis = 30_000L
        assertEquals(1, coordinator.trailingDue())
        // Second trailing call has nothing left.
        assertNull(coordinator.trailingDue())
        assertFalse(coordinator.hasPendingAutoSync())
    }

    @Test
    fun `test 6 - request while a sync is in flight is pending, never parallel`() {
        val clock = FakeClock()
        val coordinator = coordinator(clock)
        assertTrue(coordinator.request(folderId = 0, isSyncing = false) is FavoriteAutoRequestResult.StartNow)
        clock.nowMillis = 2_000L

        val result = coordinator.request(folderId = 4, isSyncing = true)
        assertTrue(result is FavoriteAutoRequestResult.Coalesced)
        assertEquals(28_000L, coalescedDelay(result))
        assertEquals(4, coordinator.pendingAutoFolderId())

        // Trailing is not due while the window is open.
        assertNull(coordinator.onSyncFinished())
    }

    @Test
    fun `test 7 - sync finishing after the window expired runs the retained trailing sync once`() {
        val clock = FakeClock()
        val coordinator = coordinator(clock)
        assertTrue(coordinator.request(folderId = 0, isSyncing = false) is FavoriteAutoRequestResult.StartNow)
        clock.nowMillis = 5_000L
        coordinator.request(folderId = 2, isSyncing = true)

        // The running sync lasts beyond the window (finishes at 35 s).
        clock.nowMillis = 35_000L
        assertEquals(2, coordinator.onSyncFinished())
        assertFalse(coordinator.hasPendingAutoSync())
        assertNull(coordinator.onSyncFinished())
    }

    @Test
    fun `test 8 - manual sync bypasses the automatic window`() {
        assertTrue(
            shouldStartFavoriteSync(
                kind = FavoriteSyncKind.MANUAL,
                isAutoSyncAllowed = false,
                isSyncing = false,
            )
        )
    }

    @Test
    fun `test 9 - force refresh bypasses the automatic window`() {
        assertTrue(
            shouldStartFavoriteSync(
                kind = FavoriteSyncKind.FORCE,
                isAutoSyncAllowed = false,
                isSyncing = false,
            )
        )
    }

    @Test
    fun `test 10 - account reset clears timing and pending state`() {
        val clock = FakeClock()
        val coordinator = coordinator(clock)
        coordinator.request(folderId = 0, isSyncing = false)
        clock.nowMillis = 5_000L
        coordinator.request(folderId = 1, isSyncing = false)
        assertTrue(coordinator.hasPendingAutoSync())

        coordinator.reset()
        assertFalse(coordinator.hasPendingAutoSync())
        assertNull(coordinator.pendingAutoFolderId())
        assertNull(coordinator.trailingDue())

        // Next auto request is immediately eligible for the new account.
        val result = coordinator.request(folderId = 9, isSyncing = false)
        assertTrue(result is FavoriteAutoRequestResult.StartNow)
        assertEquals(9, startNow(result))
    }
}
