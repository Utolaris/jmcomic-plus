package com.par9uet.jm.reader

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPipelineTest {
    @Test
    fun duplicateRequestsCollapseIntoOneLoadAndVisiblePromotesIt() {
        runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = ReaderInFlightRegistry<String, Int>(scope, cancellationGraceMillis = 10L)
        val calls = AtomicInteger()
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()

        val prefetch = async {
            registry.request("page", ReaderRequestPriority.PREFETCH) {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                42
            }
        }
        started.await()
        val visible = async(start = CoroutineStart.UNDISPATCHED) {
            registry.request("page", ReaderRequestPriority.VISIBLE) { error("duplicate loader") }
        }

        assertEquals(ReaderRequestPriority.VISIBLE, registry.priority("page"))
        release.complete(Unit)
        assertEquals(42, prefetch.await())
        assertEquals(42, visible.await())
        assertEquals(1, calls.get())
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun visibleRequestIsNotStarvedByBackgroundTask() {
        runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = ReaderInFlightRegistry<String, String>(scope)
        val promoted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()

        val prefetch = async {
            registry.request("page", ReaderRequestPriority.PREFETCH) { handle ->
                handle.awaitVisible()
                promoted.complete(Unit)
                release.await()
                "ready"
            }
        }
        delay(20L)
        val visible = async(start = CoroutineStart.UNDISPATCHED) {
            registry.request("page", ReaderRequestPriority.VISIBLE) { error("duplicate loader") }
        }

        withTimeout(1_000L) { promoted.await() }
        assertTrue(registry.isVisible("page"))
        release.complete(Unit)
        assertEquals("ready", visible.await())
        assertEquals("ready", prefetch.await())
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun everyVisibleConsumerReleasesTheGlobalVisibleGate() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val registry = ReaderInFlightRegistry<String, Int>(scope)
            val prefetch = async {
                registry.request("page-a", ReaderRequestPriority.PREFETCH) { handle ->
                    handle.awaitVisible()
                    1
                }
            }
            delay(20L)
            val firstVisible = async {
                registry.request("page-a", ReaderRequestPriority.VISIBLE) { error("duplicate loader") }
            }
            val secondVisible = async {
                registry.request("page-a", ReaderRequestPriority.VISIBLE) { error("duplicate loader") }
            }
            assertEquals(1, firstVisible.await())
            assertEquals(1, secondVisible.await())
            assertEquals(1, prefetch.await())

            withTimeout(1_000L) {
                assertEquals(2, registry.request("page-b", ReaderRequestPriority.PREFETCH) { handle ->
                    handle.awaitBackgroundTurn()
                    2
                })
            }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun differentDecodeProfilesDoNotShareInFlightWork() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val registry = ReaderInFlightRegistry<ReaderInFlightKey, Int>(scope)
            val calls = AtomicInteger()
            val highKey = ReaderInFlightKey(
                ReaderPageKey(1, 0, "page", 0, "1"),
                ReaderDecodeProfile.HIGH.cacheToken,
            )
            val lowKey = highKey.copy(profileToken = ReaderDecodeProfile.LOW.cacheToken)
            val high = async {
                registry.request(highKey, ReaderRequestPriority.VISIBLE) {
                    calls.incrementAndGet()
                    10
                }
            }
            val low = async {
                registry.request(lowKey, ReaderRequestPriority.BACKGROUND) {
                    calls.incrementAndGet()
                    20
                }
            }
            assertEquals(10, high.await())
            assertEquals(20, low.await())
            assertEquals(2, calls.get())
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun prefetchCancellationDoesNotCancelBackgroundDownloadWork() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val registry = ReaderInFlightRegistry<String, Int>(scope)
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            val download = launch {
                registry.request("download", ReaderRequestPriority.BACKGROUND) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            started.await()
            assertFalse(registry.cancelPrefetch("download"))
            download.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun cancelledPrefetchCanRetryLater() {
        runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = ReaderInFlightRegistry<String, Int>(scope, cancellationGraceMillis = 10L)
        val calls = AtomicInteger()
        val prefetch = launch {
            registry.request("page", ReaderRequestPriority.PREFETCH) {
                calls.incrementAndGet()
                awaitCancellation()
            }
        }
        delay(20L)
        assertTrue(registry.cancelPrefetch("page"))
        prefetch.join()

        assertEquals(7, registry.request("page", ReaderRequestPriority.VISIBLE) {
            calls.incrementAndGet()
            7
        })
        assertEquals(2, calls.get())
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun failedPrefetchDoesNotPoisonTheNextVisibleLoad() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val registry = ReaderInFlightRegistry<String, Int>(scope)
            val calls = AtomicInteger()
            val first = runCatching {
                registry.request("page", ReaderRequestPriority.PREFETCH) {
                    calls.incrementAndGet()
                    error("transient")
                }
            }
            assertTrue(first.isFailure)
            assertEquals(8, registry.request("page", ReaderRequestPriority.VISIBLE) {
                calls.incrementAndGet()
                8
            })
            assertEquals(2, calls.get())
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun lruEvictsLeastRecentlyUsedEntry() {
        val cache = ReaderLruCache<String, String>(3L) { 1L }
        cache.put("a", "A")
        cache.put("b", "B")
        cache.put("c", "C")
        assertEquals("A", cache["a"])
        cache.put("d", "D")

        assertEquals(null, cache["b"])
        assertEquals("A", cache["a"])
        assertEquals("D", cache["d"])
    }

    @Test
    fun directionChangeUpdatesPrefetchOrder() {
        assertEquals(
            listOf(6, 7, 4, 3),
            readerPrefetchPlan(5, 10, 4, direction = 1, includeOpposite = true),
        )
        assertEquals(
            listOf(4, 3, 6, 7),
            readerPrefetchPlan(5, 10, 4, direction = -1, includeOpposite = true),
        )
        assertEquals(listOf(6, 7, 8, 9), readerPrefetchPlan(5, 10, 4, 1, false))
        assertEquals(
            listOf(13, 14, 9, 8),
            readerPrefetchPlan(
                currentPageIndex = 11,
                pageCount = 20,
                distance = 4,
                direction = 1,
                includeOpposite = true,
                visibleStart = 10,
                visibleEnd = 12,
            ),
        )
        assertEquals(
            listOf(9, 8, 13, 14),
            readerPrefetchPlan(
                currentPageIndex = 11,
                pageCount = 20,
                distance = 4,
                direction = -1,
                includeOpposite = true,
                visibleStart = 10,
                visibleEnd = 12,
            ),
        )
    }

    @Test
    fun jumpAndStableDirectionExpandOnlyWithinBound() {
        assertEquals(3, readerPrefetchDistance(3, jumpDistance = 0, directionStreak = 1))
        assertEquals(6, readerPrefetchDistance(3, jumpDistance = 4, directionStreak = 1))
        assertEquals(4, readerPrefetchDistance(3, jumpDistance = 0, directionStreak = 3))
        assertEquals(12, readerPrefetchDistance(12, jumpDistance = 50, directionStreak = 20))
    }

    @Test
    fun cacheKeyIsStableAndIncludesScrambleIdentity() {
        val first = ReaderPageKey(123, 4, "https://example/page.webp?t=1", 456, "0")
        val same = ReaderPageKey(123, 4, "https://example/page.webp?t=1", 456, "0")
        val changed = first.copy(scrambleId = 457)

        assertEquals(first.stableIdentity(), same.stableIdentity())
        assertEquals(readerCacheKey(first, "source"), readerCacheKey(same, "source"))
        assertNotEquals(readerCacheKey(first, "source"), readerCacheKey(changed, "source"))
        assertNotEquals(readerCacheKey(first, "decoded", ReaderDecodeProfile.HIGH), readerCacheKey(first, "decoded", ReaderDecodeProfile.LOW))
    }

    @Test
    fun dynamicLimiterAppliesAChangedLimitToWaitingWork() {
        runBlocking {
            val limiter = ReaderDynamicLimiter(1)
            limiter.acquire()
            val acquired = kotlinx.coroutines.CompletableDeferred<Unit>()
            val waiter = launch {
                limiter.withPermit {
                    acquired.complete(Unit)
                }
            }
            delay(40L)
            assertFalse(acquired.isCompleted)
            limiter.updateLimit(2)
            withTimeout(1_000L) { acquired.await() }
            limiter.release()
            waiter.join()
        }
    }

    @Test
    fun scrambleRangesPreserveHeightAndRemainder() {
        val ranges = scrambledSourceRanges(height = 10, segments = 3)
        assertEquals(listOf(ReaderSourceRange(6, 10), ReaderSourceRange(3, 6), ReaderSourceRange(0, 3)), ranges)
        assertEquals(10, ranges.sumOf(ReaderSourceRange::height))
        assertFalse(sourceRangesAreSequential(ranges, 10))
        assertTrue(sourceRangesAreSequential(ordinarySourceRanges(100, 100), 100))
    }

    @Test
    fun decodeBudgetKeepsAspectRatioAndDimensionsBounded() {
        val size = readerDecodedPageSize(2_000, 8_000, maxPixels = 4_000_000, maxWidth = 900)
        assertTrue(size.first <= 900)
        assertTrue(size.first.toLong() * size.second <= 4_000_000L)
        assertEquals(0.25f, size.first.toFloat() / size.second.toFloat(), 0.01f)
        assertEquals(4, readerRegionSampleSize(4_000, 8_000, maxPixels = 1_000_000))
        assertEquals(
            1,
            readerRegionSampleSize(
                3_000,
                10_000,
                maxPixels = ReaderDecodeProfile.DOWNLOAD.maxPixels,
                maxWidth = ReaderDecodeProfile.DOWNLOAD.maxWidth,
            ),
        )
        assertEquals(
            2,
            readerRegionSampleSize(
                3_000,
                10_000,
                maxPixels = ReaderDecodeProfile.HIGH.maxPixels,
                maxWidth = ReaderDecodeProfile.HIGH.maxWidth,
            ),
        )
    }
}
