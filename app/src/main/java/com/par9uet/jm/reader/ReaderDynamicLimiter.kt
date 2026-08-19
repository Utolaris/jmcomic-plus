package com.par9uet.jm.reader

import kotlinx.coroutines.delay

/** A small dynamically resized permit pool; setting changes affect the next acquisition. */
internal class ReaderDynamicLimiter(initialLimit: Int) {
    @Volatile
    var limit: Int = initialLimit.coerceAtLeast(0)
        private set

    @Volatile
    private var inUse = 0

    @Synchronized
    fun updateLimit(newLimit: Int) {
        limit = newLimit.coerceAtLeast(0)
    }

    @Synchronized
    fun tryAcquire(): Boolean {
        if (inUse >= limit) return false
        inUse++
        return true
    }

    suspend fun acquire() {
        while (!tryAcquire()) delay(RETRY_DELAY_MILLIS)
    }

    @Synchronized
    fun release() {
        check(inUse > 0) { "Reader permit released without an acquisition" }
        inUse--
    }

    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        return try {
            block()
        } finally {
            release()
        }
    }

    private companion object {
        private const val RETRY_DELAY_MILLIS = 24L
    }
}
