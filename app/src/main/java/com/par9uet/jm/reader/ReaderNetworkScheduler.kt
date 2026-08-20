package com.par9uet.jm.reader

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal class ReaderNetworkPermit(
    private val releaseAction: () -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) releaseAction()
    }
}

/**
 * Bounds active HTTP calls. Background permits are independent from foreground permits, and a
 * final priority check after acquiring the global slot prevents queued speculative work from
 * jumping ahead of a newly visible page.
 */
internal class ReaderNetworkScheduler(
    totalConcurrency: Int,
    initialBackgroundConcurrency: Int,
) {
    private val totalLimiter = ReaderDynamicLimiter(totalConcurrency.coerceAtLeast(1))
    private val backgroundLimiter = if (totalConcurrency > 1) {
        ReaderDynamicLimiter(
            initialBackgroundConcurrency.coerceIn(1, totalConcurrency - 1),
        )
    } else {
        null
    }

    fun updateBackgroundLimit(limit: Int) {
        val background = backgroundLimiter ?: return
        background.updateLimit(limit.coerceIn(1, totalLimiter.limit - 1))
    }

    suspend fun acquire(
        isVisible: () -> Boolean,
        hasVisibleRequest: () -> Boolean,
    ): ReaderNetworkPermit {
        while (true) {
            if (isVisible()) return acquireForegroundPermit()
            if (hasVisibleRequest()) {
                delay(PRIORITY_RETRY_DELAY_MILLIS)
                continue
            }

            val background = backgroundLimiter
            var backgroundHeld = false
            var totalHeld = false
            try {
                if (background != null) {
                    if (!background.tryAcquire()) {
                        delay(PRIORITY_RETRY_DELAY_MILLIS)
                        continue
                    }
                    backgroundHeld = true
                }

                if (isVisible()) {
                    if (backgroundHeld) {
                        background?.release()
                        backgroundHeld = false
                    }
                    return acquireForegroundPermit()
                }
                if (hasVisibleRequest()) continue

                totalLimiter.acquire()
                totalHeld = true
                if (isVisible()) {
                    if (backgroundHeld) {
                        background?.release()
                        backgroundHeld = false
                    }
                    return ReaderNetworkPermit { totalLimiter.release() }
                }
                if (hasVisibleRequest()) continue

                val releaseBackground = backgroundHeld
                backgroundHeld = false
                totalHeld = false
                return ReaderNetworkPermit {
                    totalLimiter.release()
                    if (releaseBackground) background?.release()
                }
            } catch (error: CancellationException) {
                throw error
            } finally {
                if (totalHeld) totalLimiter.release()
                if (backgroundHeld) backgroundLimiter?.release()
            }
        }
    }

    private suspend fun acquireForegroundPermit(): ReaderNetworkPermit {
        totalLimiter.acquire()
        return ReaderNetworkPermit { totalLimiter.release() }
    }

    private companion object {
        private const val PRIORITY_RETRY_DELAY_MILLIS = 12L
    }
}
