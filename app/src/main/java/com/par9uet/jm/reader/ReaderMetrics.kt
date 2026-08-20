package com.par9uet.jm.reader

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

internal data class ReaderMetricsSnapshot(
    val visibleRequests: Long,
    val prefetchRequests: Long,
    val prefetchCacheHits: Long,
    val backgroundRequests: Long,
    val memoryCacheHits: Long,
    val decodedDiskCacheHits: Long,
    val sourceCacheHits: Long,
    val cacheMisses: Long,
    val networkRequests: Long,
    val networkFailures: Long,
    val hedgeStarted: Long,
    val hedgeWinnerPrimary: Long,
    val hedgeWinnerSecondary: Long,
    val hedgeLoserCanceled: Long,
    val prefetchCanceled: Long,
    val decodeCount: Long,
    val totalTimeToFirstByteMillis: Long,
    val totalNetworkMillis: Long,
    val totalDecodeMillis: Long,
    val visibleLatencyP50Millis: Long?,
    val visibleLatencyP95Millis: Long?,
    val visibleLatencyP99Millis: Long?,
    val hostSuccesses: Map<String, Long>,
    val hostFailures: Map<String, Long>,
)

/** Debug-only counters with no per-page log spam in release builds. */
internal class ReaderMetrics(
    private val enabled: Boolean,
) {
    private val visibleRequests = AtomicLong()
    private val prefetchRequests = AtomicLong()
    private val prefetchCacheHits = AtomicLong()
    private val backgroundRequests = AtomicLong()
    private val memoryCacheHits = AtomicLong()
    private val decodedDiskCacheHits = AtomicLong()
    private val sourceCacheHits = AtomicLong()
    private val cacheMisses = AtomicLong()
    private val networkRequests = AtomicLong()
    private val networkFailures = AtomicLong()
    private val hedgeStarted = AtomicLong()
    private val hedgeWinnerPrimary = AtomicLong()
    private val hedgeWinnerSecondary = AtomicLong()
    private val hedgeLoserCanceled = AtomicLong()
    private val prefetchCanceled = AtomicLong()
    private val decodeCount = AtomicLong()
    private val totalTimeToFirstByteMillis = AtomicLong()
    private val totalNetworkMillis = AtomicLong()
    private val totalDecodeMillis = AtomicLong()
    private val hostSuccesses = ConcurrentHashMap<String, AtomicLong>()
    private val hostFailures = ConcurrentHashMap<String, AtomicLong>()
    private val visibleLatencySamples = ArrayDeque<Long>()

    fun request(priority: ReaderRequestPriority) {
        if (!enabled) return
        when (priority) {
            ReaderRequestPriority.VISIBLE -> visibleRequests.incrementAndGet()
            ReaderRequestPriority.PREFETCH -> prefetchRequests.incrementAndGet()
            ReaderRequestPriority.BACKGROUND -> backgroundRequests.incrementAndGet()
        }
    }

    fun memoryHit() { if (enabled) memoryCacheHits.incrementAndGet() }
    fun prefetchCacheHit() { if (enabled) prefetchCacheHits.incrementAndGet() }
    fun decodedDiskHit() { if (enabled) decodedDiskCacheHits.incrementAndGet() }
    fun sourceHit() { if (enabled) sourceCacheHits.incrementAndGet() }
    fun cacheMiss() { if (enabled) cacheMisses.incrementAndGet() }
    fun networkStarted() { if (enabled) networkRequests.incrementAndGet() }
    fun responseHeadersReceived(elapsedMillis: Long) {
        if (enabled) totalTimeToFirstByteMillis.addAndGet(elapsedMillis.coerceAtLeast(0L))
    }
    fun networkFinished(success: Boolean, elapsedMillis: Long) {
        if (!enabled) return
        if (!success) networkFailures.incrementAndGet()
        totalNetworkMillis.addAndGet(elapsedMillis.coerceAtLeast(0L))
    }
    fun hostSuccess(host: String) {
        if (enabled) hostSuccesses.getOrPut(host) { AtomicLong() }.incrementAndGet()
    }
    fun hostFailure(host: String) {
        if (enabled) hostFailures.getOrPut(host) { AtomicLong() }.incrementAndGet()
    }
    fun hedgeStarted() { if (enabled) hedgeStarted.incrementAndGet() }
    fun hedgeWinner(primary: Boolean) {
        if (!enabled) return
        if (primary) hedgeWinnerPrimary.incrementAndGet() else hedgeWinnerSecondary.incrementAndGet()
    }
    fun hedgeLoserCanceled() { if (enabled) hedgeLoserCanceled.incrementAndGet() }
    fun prefetchCanceled() { if (enabled) prefetchCanceled.incrementAndGet() }
    fun decodeFinished(elapsedMillis: Long) {
        if (!enabled) return
        decodeCount.incrementAndGet()
        totalDecodeMillis.addAndGet(elapsedMillis.coerceAtLeast(0L))
    }

    fun requestFinished(priority: ReaderRequestPriority, elapsedMillis: Long) {
        if (!enabled || priority != ReaderRequestPriority.VISIBLE) return
        synchronized(visibleLatencySamples) {
            if (visibleLatencySamples.size >= MAX_LATENCY_SAMPLES) visibleLatencySamples.removeFirst()
            visibleLatencySamples.addLast(elapsedMillis.coerceAtLeast(0L))
        }
    }

    fun snapshot(): ReaderMetricsSnapshot {
        val samples = synchronized(visibleLatencySamples) { visibleLatencySamples.sorted() }
        fun percentile(percent: Int): Long? = samples.takeIf { it.isNotEmpty() }?.let {
            it[((it.size - 1) * percent) / 100]
        }
        return ReaderMetricsSnapshot(
            visibleRequests = visibleRequests.get(),
            prefetchRequests = prefetchRequests.get(),
            prefetchCacheHits = prefetchCacheHits.get(),
            backgroundRequests = backgroundRequests.get(),
            memoryCacheHits = memoryCacheHits.get(),
            decodedDiskCacheHits = decodedDiskCacheHits.get(),
            sourceCacheHits = sourceCacheHits.get(),
            cacheMisses = cacheMisses.get(),
            networkRequests = networkRequests.get(),
            networkFailures = networkFailures.get(),
            hedgeStarted = hedgeStarted.get(),
            hedgeWinnerPrimary = hedgeWinnerPrimary.get(),
            hedgeWinnerSecondary = hedgeWinnerSecondary.get(),
            hedgeLoserCanceled = hedgeLoserCanceled.get(),
            prefetchCanceled = prefetchCanceled.get(),
            decodeCount = decodeCount.get(),
            totalTimeToFirstByteMillis = totalTimeToFirstByteMillis.get(),
            totalNetworkMillis = totalNetworkMillis.get(),
            totalDecodeMillis = totalDecodeMillis.get(),
            visibleLatencyP50Millis = percentile(50),
            visibleLatencyP95Millis = percentile(95),
            visibleLatencyP99Millis = percentile(99),
            hostSuccesses = hostSuccesses.mapValues { it.value.get() },
            hostFailures = hostFailures.mapValues { it.value.get() },
        )
    }

    private companion object {
        private const val MAX_LATENCY_SAMPLES = 128
    }
}
