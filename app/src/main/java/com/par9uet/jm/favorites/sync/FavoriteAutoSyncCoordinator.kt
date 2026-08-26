package com.par9uet.jm.favorites.sync

import android.os.SystemClock

/** Global automatic favorite synchronization interval. */
const val FAVORITE_AUTO_SYNC_INTERVAL_MILLIS = 30_000L

enum class FavoriteSyncKind {
    AUTO,
    MANUAL,
    FORCE,
}

/**
 * Whether a sync may start for [kind] outside the automatic coordinator.
 *
 * - AUTO: requires the automatic window and no in-flight sync.
 * - MANUAL / FORCE: bypass the automatic window, but still coalesce while a sync runs.
 */
internal fun shouldStartFavoriteSync(
    kind: FavoriteSyncKind,
    isAutoSyncAllowed: Boolean,
    isSyncing: Boolean,
): Boolean = when (kind) {
    FavoriteSyncKind.AUTO -> isAutoSyncAllowed && !isSyncing
    FavoriteSyncKind.MANUAL, FavoriteSyncKind.FORCE -> !isSyncing
}

sealed class FavoriteAutoRequestResult {
    /** A sync may start immediately; a fresh 30-second window begins now. */
    data class StartNow(val folderId: Int) : FavoriteAutoRequestResult()

    /**
     * The request was coalesced into the current window. A single trailing sync may run after
     * [trailingDelayMs]; [trailingDelayMs] is 0 when only an in-flight sync is blocking.
     */
    data class Coalesced(val trailingDelayMs: Long) : FavoriteAutoRequestResult()
}

/**
 * Leading + trailing coalescing state machine for automatic favorite synchronization.
 *
 * The first eligible automatic request starts a sync immediately and opens a 30-second window.
 * Additional automatic requests inside the window are coalesced (latest requested folder wins)
 * and result in at most ONE trailing sync when the window expires. Requests arriving while a
 * sync is already in flight are also coalesced and never start parallel work.
 *
 * Uses [SystemClock.elapsedRealtime] in production; tests inject a time source.
 */
class FavoriteAutoSyncCoordinator(
    private val intervalMillis: Long = FAVORITE_AUTO_SYNC_INTERVAL_MILLIS,
    private val timeSource: () -> Long = SystemClock::elapsedRealtime,
) {
    private var windowStartMs = -1L
    private var pending = false
    private var pendingFolderId: Int? = null

    fun hasPendingAutoSync(): Boolean = pending

    fun pendingAutoFolderId(): Int? = pendingFolderId

    /**
     * Routes an automatic sync intent. The window is only advanced when a sync is actually
     * accepted (never for requests blocked by an in-flight sync).
     */
    fun request(folderId: Int, isSyncing: Boolean): FavoriteAutoRequestResult {
        val now = timeSource()
        if (isSyncing) {
            // Never start parallel work; retain the request for one trailing sync.
            pending = true
            pendingFolderId = folderId
            return FavoriteAutoRequestResult.Coalesced(remainingWindowMillis(now))
        }
        if (windowOpen(now)) {
            pending = true
            pendingFolderId = folderId
            return FavoriteAutoRequestResult.Coalesced(remainingWindowMillis(now))
        }
        // Window closed (or never opened): start a leading sync and begin a new window.
        windowStartMs = now
        pending = false
        pendingFolderId = null
        return FavoriteAutoRequestResult.StartNow(folderId)
    }

    /**
     * Called by the single scheduled wake-up timer when the window expires. Returns the folder
     * for exactly one trailing sync, or null when nothing is pending.
     */
    fun trailingDue(): Int? = drainIfDue()

    /**
     * Called after a sync finishes. If a pending trailing sync became due while the sync was
     * running (window expired), returns its folder so the caller may start it; otherwise null.
     */
    fun onSyncFinished(): Int? = drainIfDue()

    /** Clears timing, pending state and pending folder (account change / logout). */
    fun reset() {
        windowStartMs = -1L
        pending = false
        pendingFolderId = null
    }

    private fun drainIfDue(): Int? {
        if (!pending) return null
        val now = timeSource()
        if (windowOpen(now)) return null
        pending = false
        val folder = pendingFolderId
        pendingFolderId = null
        // The trailing sync opens a new window.
        windowStartMs = now
        return folder
    }

    private fun windowOpen(now: Long): Boolean =
        windowStartMs >= 0 && now - windowStartMs < intervalMillis

    private fun remainingWindowMillis(now: Long): Long =
        if (windowStartMs < 0) 0L else (windowStartMs + intervalMillis - now).coerceAtLeast(0L)
}
