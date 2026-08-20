package com.par9uet.jm.reader

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAccelerationTest {
    @Test
    fun hostReplacementPreservesPathAndQueryAndUnknownPathsStayUnchanged() {
        val original = "https://origin.example/media/photos/123/00001.webp?t=abc&v=2"
        val replaced = replaceReaderImageHost(original, "cdn.example")

        assertEquals(
            "https://cdn.example/media/photos/123/00001.webp?t=abc&v=2",
            replaced,
        )
        assertTrue(
            isReaderImageMirrorAllowed(
                host = "cdn.example",
                path = "/media/photos/123/00001.webp",
                allowlistedHosts = listOf("cdn.example"),
            ),
        )
        assertFalse(
            isReaderImageMirrorAllowed(
                host = "unknown.example",
                path = "/media/photos/123/00001.webp",
                allowlistedHosts = listOf("cdn.example"),
            ),
        )
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

        assertEquals(readerCacheKey(first, "source"), readerCacheKey(second, "source"))
        assertEquals(first.stableIdentity(), second.stableIdentity())
        assertTrue(readerCacheKey(first, "source") != readerCacheKey(unrelated, "source"))
    }

    @Test
    fun fastPrimaryDoesNotStartSecondary() = runBlocking {
        var secondaryCalls = 0
        val winner = delayedHedge(
            delayMillis = 100L,
            primaryAttempt = { Result.success("primary") },
            secondaryAttempt = {
                secondaryCalls++
                Result.success("secondary")
            },
        )

        assertEquals("primary", winner)
        assertEquals(0, secondaryCalls)
    }

    @Test
    fun delayedHedgeReturnsSecondaryAndCancelsPrimary() = runBlocking {
        val primaryCancelled = CompletableDeferred<Unit>()
        val winner = delayedHedge(
            delayMillis = 10L,
            primaryAttempt = {
                try {
                    awaitCancellation()
                } finally {
                    primaryCancelled.complete(Unit)
                }
            },
            secondaryAttempt = { Result.success("secondary") },
        )

        assertEquals("secondary", winner)
        withTimeout(1_000L) { primaryCancelled.await() }
    }

    @Test
    fun failedPrimaryFallsThroughToTheNextCandidateWithoutAThirdRace() = runBlocking {
        var secondaryCalls = 0
        val winner = delayedHedge(
            delayMillis = 100L,
            primaryAttempt = { Result.failure(IllegalStateException("bad CDN")) },
            secondaryAttempt = {
                secondaryCalls++
                Result.success("alternate")
            },
        )

        assertEquals("alternate", winner)
        assertEquals(1, secondaryCalls)
    }

    @Test
    fun hostOrderingUsesCooldownThenPreferredThenHistoricalLatency() {
        val ordered = orderReaderImageHosts(
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
    fun nextChapterWarmupTriggersOnlyInsideTheForwardWindow() {
        assertFalse(shouldWarmNextChapter(currentPageIndex = 4, pageCount = 10, distance = 2))
        assertTrue(shouldWarmNextChapter(currentPageIndex = 7, pageCount = 10, distance = 2))
        assertTrue(shouldWarmNextChapter(currentPageIndex = 0, pageCount = 1, distance = 0))
        assertFalse(shouldWarmNextChapter(currentPageIndex = 0, pageCount = 0, distance = 2))
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
