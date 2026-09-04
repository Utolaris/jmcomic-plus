package com.par9uet.jm.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import com.par9uet.jm.retrofit.model.NetWorkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * BaseRepository 异常边界测试：CancellationException 必须原样抛出（不能被转换成普通
 * 网络失败），JVM 致命错误（LinkageError 等）不得被请求包装吞掉。
 */
class CancellationPropagationTest {

    private class Subject : BaseRepository() {
        suspend fun capture(block: suspend () -> String): NetWorkResult<String> =
            safeEmbeddedCall("测试失败", block)
    }

    private val subject = Subject()

    @Test
    fun cancellationExceptionPropagatesFromEmbeddedCall() = runBlocking {
        try {
            subject.capture { throw CancellationException("cancelled") }
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    @Test
    fun ordinaryExceptionBecomesNetworkFailure() = runBlocking {
        val result = subject.capture { throw IllegalStateException("boom") }
        assertTrue(result is NetWorkResult.Error)
        assertEquals("测试失败：boom", (result as NetWorkResult.Error).message)
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
