package com.par9uet.jm.core

import com.par9uet.jm.core.network.AuthFailure
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.core.network.ResponseWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Repository error mapping is what upstream uses to decide "login expired" vs "try again later"
 * and "retry" vs "give up", so the classification below is regression-critical.
 */
class BaseRepositoryErrorMappingTest {

    private val repository = object : BaseRepository() {
        suspend fun <T> embedded(operation: String, block: suspend () -> T): NetWorkResult<T> =
            safeEmbeddedCall(operation, block)
    }

    @Test
    fun serverErrorEnvelopeKeepsServerCodeAndMessage() = runTest {
        // 401 must stay distinguishable from a 500 so login expiry can be detected upstream.
        val unauthorized = repository.safeApiCall {
            ResponseWrapper.Error(errorMsg = "登录已失效", code = 401)
        }
        unauthorized as NetWorkResult.Error
        assertEquals(401, unauthorized.code)
        assertEquals("登录已失效", unauthorized.message)

        val server = repository.safeApiCall<String> {
            ResponseWrapper.Error(errorMsg = "服务器开小差", code = 500)
        }
        server as NetWorkResult.Error
        assertEquals(500, server.code)
    }

    @Test
    fun successEnvelopeWithoutPayloadIsReportedAsEmptyInsteadOfSuccess() = runTest {
        // 点赞这类接口返回 code=200 但没有 data，只能按空响应处理，不能当成成功数据。
        val result = repository.safeApiCall<String> { ResponseWrapper(code = 200, data = null) }
        result as NetWorkResult.Error
        assertEquals("响应数据为空", result.message)
    }

    @Test
    fun transientNetworkFailuresAreClassifiedAsTemporary() = runTest {
        val cases = listOf(
            SocketTimeoutException() to "网络连接超时",
            ConnectException() to "网络连接失败",
            UnknownHostException() to "网络不可用",
            IOException() to "网络请求失败",
        )
        for ((throwable, expectedMessage) in cases) {
            val result = repository.safeApiCall<String> { throw throwable }
            result as NetWorkResult.Error
            assertEquals(expectedMessage, result.message)
            assertEquals(AuthFailure.TemporaryFailure, result.authFailure)
        }
    }

    @Test
    fun cancellationIsNeverSwallowedIntoAnError() = runTest {
        val apiCallFailure = runCatching {
            repository.safeApiCall<String> { throw CancellationException("cancelled") }
        }
        assertTrue(apiCallFailure.exceptionOrNull() is CancellationException)

        val embeddedFailure = runCatching {
            repository.embedded("内置操作") { throw CancellationException("cancelled") }
        }
        assertTrue(embeddedFailure.exceptionOrNull() is CancellationException)
    }

    @Test
    fun sessionRecoveryExceptionKeepsTheStructuredErrorIntact() = runTest {
        val structured = NetWorkResult.Error(
            message = "登录会话已失效，请重新登录",
            authFailure = AuthFailure.InvalidCredentials,
        )
        val result = repository.embedded("内置操作") { throw SessionRecoveryException(structured) }
        // The whole point of SessionRecoveryException: no re-wrap, no lost classification.
        assertSame(structured, result)
    }

    @Test
    fun embeddedFailuresKeepTheOperationPrefix() = runTest {
        val result = repository.embedded("内置 API 获取历史漫画失败") { throw IOException("boom") }
        result as NetWorkResult.Error
        assertTrue(result.message.startsWith("内置 API 获取历史漫画失败"))
        assertTrue(result.message.contains("boom"))
    }
}
