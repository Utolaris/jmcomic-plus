package com.par9uet.jm.reader

import com.par9uet.jm.image.jmImageHostLatencyEwma
import com.par9uet.jm.image.orderJmImageHosts
import com.par9uet.jm.image.selectJmPreferredHost
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAccelerationTest {
    @Test
    fun visibleSourcePolicyLimitsCandidatesAndDisablesHedgeWhenEveryKnownHostIsSlow() {
        val healthy = readerVisibleSourcePolicy(
            orderedUrls = listOf("primary", "secondary", "third"),
            fastestKnownLatencyMillis = 449L,
        )
        val degraded = readerVisibleSourcePolicy(
            orderedUrls = listOf("primary", "secondary", "third"),
            fastestKnownLatencyMillis = 450L,
        )

        assertEquals(listOf("primary", "secondary"), healthy.urls)
        assertEquals(listOf("primary", "secondary"), degraded.urls)
        assertTrue(healthy.hedgeEnabled)
        assertFalse(degraded.hedgeEnabled)
    }

    @Test
    fun visibleLoadDeadlineReturnsReaderFailureInsteadOfLoadingForever() = runBlocking {
        val failure = runCatching {
            withReaderVisibleLoadDeadline(timeoutMillis = 25L) {
                delay(250L)
                "late"
            }
        }.exceptionOrNull()

        assertTrue(failure is ReaderImageException)
        assertEquals("图片加载超时，请重试", failure?.message)
    }

    @Test
    fun hostReplacementPreservesPathAndQueryAndUnknownPathsStayUnchanged() {
        val original = "https://origin.example/media/photos/123/00001.webp?t=abc&v=2"
        val replaced = replaceReaderImageHost(original, "cdn.example")

        assertEquals(
            "https://cdn.example/media/photos/123/00001.webp?t=abc&v=2",
            replaced,
        )
        assertTrue(isReaderImageMirrorPathAllowed("/media/photos/123/00001.webp"))
        assertFalse(isReaderImageMirrorPathAllowed("/api/private/image.webp"))
        assertFalse(isReaderImageMirrorPathAllowed("/user-content/image.webp"))
        assertTrue(isReaderImageMirrorPathAllowed("/media/albums/220980_3x4.jpg"))
    }

    @Test
    fun mirrorHostsShareOneLogicalSourceCacheIdentity() {
        val first = ReaderPageKey(
            comicId = 7,
            pageIndex = 2,
            sourceIdentity = "https://cdn-msp.jmapiproxy1.cc/media/photos/7/00003.webp?t=1",
            scrambleId = 0,
            speed = "1",
        )
        val second = first.copy(
            sourceIdentity = "https://cdn-msp.jmapiproxy2.cc/media/photos/7/00003.webp?t=1",
        )
        val unrelated = first.copy(
            sourceIdentity = "https://example.com/media/photos/7/00003.webp?t=1",
        )
        val unsupportedFirst = first.copy(
            sourceIdentity = "https://cdn-msp.jmapiproxy1.cc/api/image/00003.webp?t=1",
        )
        val unsupportedSecond = unsupportedFirst.copy(
            sourceIdentity = "https://cdn-msp.jmapiproxy2.cc/api/image/00003.webp?t=1",
        )

        assertEquals(readerCacheKey(first, "source"), readerCacheKey(second, "source"))
        assertEquals(first.stableIdentity(), second.stableIdentity())
        assertTrue(readerCacheKey(first, "source") != readerCacheKey(unrelated, "source"))
        assertTrue(
            readerCacheKey(unsupportedFirst, "source") !=
                readerCacheKey(unsupportedSecond, "source"),
        )
    }

    @Test
    fun hostOrderingUsesCooldownThenPreferredThenHistoricalLatency() {
        val ordered = orderJmImageHosts(
            candidates = listOf("origin", "fast", "preferred", "cooldown"),
            originHost = "origin",
            preferredHost = "preferred",
            latencyMillis = mapOf(
                "origin" to 80L,
                "fast" to 20L,
                "preferred" to 90L,
                "cooldown" to 1L,
            ),
            failedAtMillis = mapOf("cooldown" to 950L),
            nowMillis = 1_000L,
            cooldownMillis = 120L,
        )

        assertEquals(listOf("preferred", "fast", "origin", "cooldown"), ordered)
    }

    @Test
    fun preferredHostTracksStableEwmaInsteadOfLastSuccess() {
        val latencies = mutableMapOf<String, Long?>()
        fun sample(host: String, elapsedMillis: Long): String? {
            latencies[host] = jmImageHostLatencyEwma(latencies[host], elapsedMillis)
            return selectJmPreferredHost(
                candidates = listOf("A", "B"),
                latencyMillis = latencies,
                failedAtMillis = emptyMap(),
                nowMillis = 1_000L,
                cooldownMillis = 120_000L,
            )
        }

        assertEquals("A", sample("A", 40L))
        assertEquals("A", sample("A", 45L))
        assertEquals("A", sample("A", 42L))
        assertEquals("A", sample("B", 120L))
        assertEquals("A", sample("B", 20L))
        assertEquals("A", sample("B", 22L))
        assertEquals("B", sample("B", 18L))
    }

    @Test
    fun adaptivePolicyBoundsAggressivePrefetchAndUsesSourceOnlyOnSingleSlot() {
        val lowRam = readerAdaptivePrefetchPolicy(
            configuredDistance = 6,
            memoryClassMb = 256,
            lowRamDevice = true,
            singleNetworkSlot = true,
            jumpDistance = 20,
            directionStreak = 10,
            pageVelocity = 6f,
            networkLatencyMillis = 2_000L,
            turboMode = true,
        )
        val highEnd = readerAdaptivePrefetchPolicy(
            configuredDistance = 3,
            memoryClassMb = 768,
            lowRamDevice = false,
            singleNetworkSlot = false,
            jumpDistance = 8,
            directionStreak = 5,
            pageVelocity = 3f,
            networkLatencyMillis = 600L,
            turboMode = true,
        )

        assertTrue(lowRam.sourceOnly)
        assertTrue(lowRam.distance <= 2)
        assertTrue(highEnd.distance > 3)
        assertTrue(highEnd.distance <= 12)
        assertEquals(2, highEnd.parallelism)
    }

    @Test
    fun adaptivePrefetchSchedulerEnforcesOneOrTwoActiveWorkers() = runBlocking {
        suspend fun maximumActive(parallelism: Int): Int {
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            runReaderPrefetchSchedule((0 until 8).toList(), parallelism) {
                val current = active.incrementAndGet()
                maximum.updateAndGet { previous -> maxOf(previous, current) }
                try {
                    delay(20L)
                } finally {
                    active.decrementAndGet()
                }
            }
            return maximum.get()
        }

        assertEquals(1, maximumActive(parallelism = 1))
        assertEquals(2, maximumActive(parallelism = 2))
    }

    @Test
    fun cancelingPrefetchScheduleStopsItsActiveWorkers() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val canceledWorkers = AtomicInteger()
        val schedule = launch {
            runReaderPrefetchSchedule((0 until 12).toList(), parallelism = 2) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    canceledWorkers.incrementAndGet()
                }
            }
        }

        started.await()
        schedule.cancelAndJoin()
        assertTrue(canceledWorkers.get() in 1..2)
    }

    @Test
    fun stalePrefetchEntryCanBeCanceledImmediatelyAfterConsumerLeaves() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val registry = ReaderInFlightRegistry<String, String>(
            scope = scope,
            cancellationGraceMillis = 10_000L,
        )
        val loaderStarted = CompletableDeferred<Unit>()
        val loaderCanceled = CompletableDeferred<Unit>()
        val consumer = launch {
            registry.request("stale", ReaderRequestPriority.PREFETCH) {
                loaderStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    loaderCanceled.complete(Unit)
                }
            }
        }

        loaderStarted.await()
        consumer.cancelAndJoin()
        assertTrue(registry.cancelPrefetch("stale"))
        withTimeout(1_000L) { loaderCanceled.await() }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        Unit
    }

    @Test
    fun visibleRequestsAreSharedAcrossPageAndSourceRegistries() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val tracker = ReaderVisibleRequestTracker()
            val pageRegistry = ReaderInFlightRegistry<String, String>(
                scope = scope,
                visibleRequestTracker = tracker,
            )
            val sourceRegistry = ReaderInFlightRegistry<String, String>(
                scope = scope,
                visibleRequestTracker = tracker,
            )
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val calls = AtomicInteger()

            val background = async {
                sourceRegistry.request("source", ReaderRequestPriority.PREFETCH) { handle ->
                    calls.incrementAndGet()
                    started.complete(Unit)
                    handle.awaitBackgroundTurn()
                    release.await()
                    "source"
                }
            }
            started.await()
            assertFalse(tracker.hasVisibleRequest)

            val visible = async {
                pageRegistry.request("page", ReaderRequestPriority.VISIBLE) {
                    assertTrue(tracker.hasVisibleRequest)
                    "page"
                }
            }
            assertEquals("page", visible.await())
            release.complete(Unit)
            assertEquals("source", withTimeout(1_000L) { background.await() })
            assertEquals(1, calls.get())
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
