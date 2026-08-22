package com.par9uet.jm.coil

import com.par9uet.jm.image.JmImageHostHealthStore
import com.par9uet.jm.image.JmImageHostLatency
import com.par9uet.jm.image.JmImageHostPersistence
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverImageHostResolverTest {
    @Test
    fun emptyRemoteHostStillUsesKnownCandidates() {
        val resolver = resolver()

        assertEquals(
            listOf(
                "https://a.example/media/albums/123_3x4.jpg",
                "https://b.example/media/albums/123_3x4.jpg",
            ),
            resolver.coverUrls(comicId = 123, remoteHost = ""),
        )
    }

    @Test
    fun duplicateRemoteHostIsEmittedOnlyOnce() {
        val resolver = resolver()

        assertEquals(
            listOf("a.example", "b.example"),
            resolver.candidateHosts("https://a.example/"),
        )
    }

    @Test
    fun successfulHostIsPromotedForTheNextCover() {
        val resolver = resolver()
        resolver.recordFailure(buildJmCoverUrl("a.example", 1))
        resolver.recordSuccess(buildJmCoverUrl("b.example", 1), elapsedMillis = 20L)

        assertEquals("b.example", resolver.candidateHosts("https://a.example").first())
    }

    @Test
    fun failedHostMovesBehindAvailableHostsDuringCooldown() {
        val clock = MutableClock(10_000L)
        val resolver = resolver(clock)
        resolver.recordFailure(buildJmCoverUrl("a.example", 1))

        assertEquals(
            listOf("b.example", "a.example"),
            resolver.candidateHosts("https://a.example"),
        )
    }

    @Test
    fun failedHostRejoinsNormalOrderAfterCooldownExpires() {
        val clock = MutableClock(10_000L)
        val resolver = resolver(clock)
        resolver.recordFailure(buildJmCoverUrl("a.example", 1))
        clock.nowMillis += 1_001L

        assertEquals(
            listOf("a.example", "b.example"),
            resolver.candidateHosts("https://a.example"),
        )
    }

    @Test
    fun coverUrlUsesOnlyTheAlbumsPathAndExpectedFormat() {
        val url = buildJmCoverUrl("a.example", 123)
        val parsed = url.toHttpUrl()

        assertEquals("https://a.example/media/albums/123_3x4.jpg", url)
        assertEquals("/media/albums/123_3x4.jpg", parsed.encodedPath)
        assertFalse(parsed.encodedPath.startsWith("/media/photos/"))
    }

    @Test
    fun coverFallbackAttemptsExactlyOneCandidateAtATime() {
        val candidates = listOf("A", "B", "C")
        val visited = mutableListOf<String>()
        val attempted = mutableSetOf<String>()
        val active = mutableSetOf<String>()
        while (true) {
            val next = nextCoverCandidateUrl(candidates, attempted) ?: break
            active += next
            assertEquals(1, active.size)
            visited += next
            attempted += next
            active -= next
        }

        assertEquals(candidates, visited)
        assertEquals(null, nextCoverCandidateUrl(candidates, attempted))
    }

    @Test
    fun logicalCacheKeyIsStableAcrossMirrorsAndDifferentAcrossComics() {
        val firstMirror = buildJmCoverUrl("a.example", 123)
        val secondMirror = buildJmCoverUrl("b.example", 123)

        assertNotEquals(firstMirror, secondMirror)
        assertEquals("jm-cover-123", jmCoverCacheKey(123))
        assertEquals(jmCoverCacheKey(123), jmCoverCacheKey(123))
        assertNotEquals(jmCoverCacheKey(123), jmCoverCacheKey(124))
    }

    @Test
    fun coverUsesTheSharedReaderRankingSemantics() {
        val store = JmImageHostHealthStore(
            knownHosts = listOf("a.example", "b.example"),
            clockMillis = { 10_000L },
            cooldownMillis = 1_000L,
            maxHosts = 4,
        )
        store.restore(
            JmImageHostPersistence(
                preferredHost = "b.example",
                latencies = mapOf(
                    "a.example" to JmImageHostLatency(20L, 1L),
                    "b.example" to JmImageHostLatency(80L, 1L),
                ),
            )
        )
        val resolver = CoverImageHostResolver(store, maxCandidates = 4)

        assertEquals(store.orderedHosts("a.example"), resolver.candidateHosts("a.example"))
        resolver.recordFailure(buildJmCoverUrl("b.example", 1))
        assertEquals(listOf("a.example", "b.example"), resolver.candidateHosts("a.example"))
    }

    @Test
    fun nonHttpsRemoteHostIsSkippedAndCandidateCountIsBounded() {
        val resolver = CoverImageHostResolver(
            knownHosts = (1..20).map { "cdn$it.example" },
            maxCandidates = 4,
        )

        val candidates = resolver.coverUrls(123, "http://unsafe.example")

        assertEquals(4, candidates.size)
        assertTrue(candidates.all { it.startsWith("https://") })
    }

    private fun resolver(clock: MutableClock = MutableClock(10_000L)) =
        CoverImageHostResolver(
            knownHosts = listOf("a.example", "b.example"),
            clockMillis = clock::read,
            failureCooldownMillis = 1_000L,
            maxCandidates = 4,
        )

    private class MutableClock(var nowMillis: Long) {
        fun read(): Long = nowMillis
    }
}
