package com.par9uet.jm.store

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/** Global automatic favorite synchronization interval. */
const val FAVORITE_AUTO_SYNC_INTERVAL_MILLIS = 30_000L

enum class FavoriteSyncKind {
    AUTO,
    MANUAL,
    FORCE,
}

/**
 * Whether a sync may start for [kind].
 *
 * - AUTO: requires the 30-second gate and no in-flight sync.
 * - MANUAL / FORCE: bypass the automatic gate, but still coalesce while a sync runs.
 */
internal fun shouldStartFavoriteSync(
    kind: FavoriteSyncKind,
    isAutoSyncAllowed: Boolean,
    isSyncing: Boolean,
): Boolean = when (kind) {
    FavoriteSyncKind.AUTO -> isAutoSyncAllowed && !isSyncing
    FavoriteSyncKind.MANUAL, FavoriteSyncKind.FORCE -> !isSyncing
}

/**
 * Process-lifetime rate limit for automatic favorite syncs.
 * Uses [SystemClock.elapsedRealtime] so it never depends on wall clock changes.
 */
class FavoriteAutoSyncGate(
    private val intervalMillis: Long = FAVORITE_AUTO_SYNC_INTERVAL_MILLIS,
    private val timeSource: () -> Long = SystemClock::elapsedRealtime,
) {
    private val lastAllowedAt = AtomicLong(-1L)

    /** Claims the automatic sync window, or returns false when inside it. */
    fun tryAcquire(): Boolean {
        val now = timeSource()
        val last = lastAllowedAt.get()
        if (last >= 0L && now - last < intervalMillis) return false
        return lastAllowedAt.compareAndSet(last, now)
    }

    /** Clears the window, e.g. when the logged-in account changes. */
    fun reset() {
        lastAllowedAt.set(-1L)
    }
}
