package com.par9uet.jm.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * BaseRepository 异常边界测试：CancellationException 必须原样抛出（不能被转换成普通
 * 网络失败），JVM 致命错误（LinkageError 等）不得被 runCatchingCancellable 吞掉。
 */
class CancellationPropagationTest {

    private class Subject : BaseRepository() {
        suspend fun capture(block: suspend () -> String): Result<String> =
            runCatchingCancellable(block)
    }

    private val subject = Subject()

    @Test
    fun cancellationExceptionPropagatesFromRunCatchingCancellable() = runBlocking {
        try {
            subject.capture { throw CancellationException("cancelled") }
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    @Test
    fun ordinaryExceptionBecomesResultFailure() = runBlocking {
        val result = subject.capture { throw IllegalStateException("boom") }
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun fatalJvmErrorIsNotConvertedToResultFailure() = runBlocking {
        try {
            subject.capture { throw LinkageError("linkage") }
            fail("fatal JVM errors must not be swallowed")
        } catch (e: LinkageError) {
            assertEquals("linkage", e.message)
        }
    }

    @Test
    fun safeApiCallPropagatesCancellation() = runBlocking {
        try {
            subject.safeApiCall<String> { throw CancellationException("cancelled") }
            fail("CancellationException must propagate through safeApiCall")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }
}
