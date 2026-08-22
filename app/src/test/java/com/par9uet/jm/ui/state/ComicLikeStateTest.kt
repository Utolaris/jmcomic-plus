package com.par9uet.jm.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicLikeStateTest {

    @Test
    fun unlikedSuccessMarksLikedAndIncrementsCount() {
        val (isLike, likeCount) = ComicLikeState.applyLikeSuccess(isLike = false, likeCount = 41)

        assertTrue(isLike)
        assertEquals(42, likeCount)
    }

    @Test
    fun likedComicCannotSubmitAnotherLike() {
        assertFalse(ComicLikeState.canSubmitLike(isLike = true))
    }

    @Test
    fun successfulLikeKeepsAnAlreadyLikedComicLiked() {
        val (isLike, likeCount) = ComicLikeState.applyLikeSuccess(isLike = true, likeCount = 41)

        assertTrue(isLike)
        assertEquals(41, likeCount)
    }

    @Test
    fun likeCountNeverBecomesNegative() {
        val (isLike, likeCount) = ComicLikeState.applyLikeSuccess(isLike = true, likeCount = 0)

        assertTrue(isLike)
        assertEquals(0, likeCount)

        val (_, recoveredCount) = ComicLikeState.applyLikeSuccess(
            isLike = false,
            likeCount = -5,
        )
        assertEquals(1, recoveredCount)
    }

    @Test
    fun failedRequestKeepsOriginalState() {
        val afterFailure = ComicLikeState.applyLikeResult(
            isLike = true,
            likeCount = 41,
            succeeded = false,
        )

        assertEquals(true, afterFailure.first)
        assertEquals(41, afterFailure.second)
    }

    @Test
    fun gateBlocksConcurrentLikesForSameComic() {
        val gate = LikeRequestGate()

        assertTrue(gate.tryAcquire(123))
        assertTrue(gate.isInFlight(123))
        // 同一漫画在途期间的重复点击被忽略
        assertFalse(gate.tryAcquire(123))
        // 其它漫画不受影响
        assertTrue(gate.tryAcquire(456))

        gate.release(123)
        assertFalse(gate.isInFlight(123))
        assertTrue(gate.tryAcquire(123))
    }

    @Test
    fun rapidTapsIssueOnlyOneRequestPerRoundTrip() {
        val gate = LikeRequestGate()
        var issuedRequests = 0

        repeat(5) {
            if (gate.tryAcquire(7)) {
                issuedRequests += 1
            }
        }
        assertEquals(1, issuedRequests)

        gate.release(7)
        if (gate.tryAcquire(7)) issuedRequests += 1
        assertEquals(2, issuedRequests)
    }
}
