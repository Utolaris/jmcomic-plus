package com.par9uet.jm.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteAutoSyncGateTest {
    private val intervalMillis = FAVORITE_AUTO_SYNC_INTERVAL_MILLIS

    private class FakeClock {
        var nowMillis = 0L
    }

    private fun gate(clock: FakeClock) = FavoriteAutoSyncGate(
        intervalMillis = intervalMillis,
        timeSource = { clock.nowMillis },
    )

    @Test
    fun `first automatic sync is allowed`() {
        val clock = FakeClock()
        assertTrue(gate(clock).tryAcquire())
    }

    @Test
    fun `automatic sync inside the window is rejected`() {
        val clock = FakeClock()
        val gate = gate(clock)
        assertTrue(gate.tryAcquire())
        clock.nowMillis = 5_000L
        assertFalse(gate.tryAcquire())
    }

    @Test
    fun `automatic sync just before 30 seconds is still rejected`() {
        val clock = FakeClock()
        val gate = gate(clock)
        assertTrue(gate.tryAcquire())
        clock.nowMillis = intervalMillis - 100L
        assertFalse(gate.tryAcquire())
    }

    @Test
    fun `automatic sync exactly at 30 seconds is allowed`() {
        val clock = FakeClock()
        val gate = gate(clock)
        assertTrue(gate.tryAcquire())
        clock.nowMillis = intervalMillis
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun `account reset clears the window immediately`() {
        val clock = FakeClock()
        val gate = gate(clock)
        assertTrue(gate.tryAcquire())
        clock.nowMillis = 2_000L
        assertFalse(gate.tryAcquire())
        gate.reset()
        assertTrue(gate.tryAcquire())
    }

    @Test
    fun `manual sync bypasses the automatic gate`() {
        assertTrue(
            shouldStartFavoriteSync(
                kind = FavoriteSyncKind.MANUAL,
                isAutoSyncAllowed = false,
                isSyncing = false,
            )
        )
    }

    @Test
    fun `force refresh bypasses the automatic gate`() {
        assertTrue(
            shouldStartFavoriteSync(
                kind = FavoriteSyncKind.FORCE,
                isAutoSyncAllowed = false,
                isSyncing = false,
            )
        )
    }

    @Test
    fun `automatic sync requires the gate and no in-flight sync`() {
        assertFalse(
            shouldStartFavoriteSync(
                kind = FavoriteSyncKind.AUTO,
                isAutoSyncAllowed = false,
                isSyncing = false,
            )
        )
        assertFalse(
            shouldStartFavoriteSync(
                kind = FavoriteSyncKind.AUTO,
                isAutoSyncAllowed = true,
                isSyncing = true,
            )
        )
        assertTrue(
            shouldStartFavoriteSync(
                kind = FavoriteSyncKind.AUTO,
                isAutoSyncAllowed = true,
                isSyncing = false,
            )
        )
    }

    @Test
    fun `in-flight sync coalesces every kind`() {
        assertFalse(shouldStartFavoriteSync(FavoriteSyncKind.AUTO, true, true))
        assertFalse(shouldStartFavoriteSync(FavoriteSyncKind.MANUAL, false, true))
        assertFalse(shouldStartFavoriteSync(FavoriteSyncKind.FORCE, false, true))
    }
}
