package com.par9uet.jm.image

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class ImageHostFailureClassifierTest {

    @Test
    fun http404IsResourceFailure() {
        assertEquals(
            ImageHostFailureKind.RESOURCE_FAILURE,
            classifyImageHostFailure(RuntimeException("HTTP 404"), httpCodeHint = 404),
        )
    }

    @Test
    fun http410IsResourceFailure() {
        assertEquals(
            ImageHostFailureKind.RESOURCE_FAILURE,
            classifyImageHostFailure(RuntimeException("HTTP 410"), httpCodeHint = 410),
        )
    }

    @Test
    fun otherHttp4xxIsResourceFailure() {
        assertEquals(
            ImageHostFailureKind.RESOURCE_FAILURE,
            classifyImageHostFailure(null, httpCodeHint = 403),
        )
    }

    @Test
    fun decodeFailureIsResourceFailure() {
        assertEquals(
            ImageHostFailureKind.RESOURCE_FAILURE,
            classifyImageHostFailure(IllegalStateException("BitmapFactory returned null")),
        )
        assertEquals(
            ImageHostFailureKind.RESOURCE_FAILURE,
            classifyImageHostFailure(RuntimeException("Failed to decode image")),
        )
    }

    @Test
    fun timeoutIsHostFailure() {
        assertEquals(
            ImageHostFailureKind.HOST_FAILURE,
            classifyImageHostFailure(SocketTimeoutException("timeout")),
        )
    }

    @Test
    fun connectFailureIsHostFailure() {
        assertEquals(
            ImageHostFailureKind.HOST_FAILURE,
            classifyImageHostFailure(ConnectException("connection refused")),
        )
        assertEquals(
            ImageHostFailureKind.HOST_FAILURE,
            classifyImageHostFailure(UnknownHostException("cdn.example")),
        )
        assertEquals(
            ImageHostFailureKind.HOST_FAILURE,
            classifyImageHostFailure(NoRouteToHostException("no route")),
        )
        assertEquals(
            ImageHostFailureKind.HOST_FAILURE,
            classifyImageHostFailure(SocketException("connection reset")),
        )
    }

    @Test
    fun truncatedSingleResourceIsConservative() {
        assertEquals(
            ImageHostFailureKind.RESOURCE_FAILURE,
            classifyImageHostFailure(EOFException("stream closed")),
        )
    }

    @Test
    fun sslFailureIsHostFailure() {
        assertEquals(
            ImageHostFailureKind.HOST_FAILURE,
            classifyImageHostFailure(SSLHandshakeException("handshake failed")),
        )
        assertEquals(
            ImageHostFailureKind.HOST_FAILURE,
            classifyImageHostFailure(SSLException("tls error")),
        )
    }

    @Test
    fun http5xxIsHostFailure() {
        assertEquals(
            ImageHostFailureKind.HOST_FAILURE,
            classifyImageHostFailure(null, httpCodeHint = 500),
        )
        assertEquals(
            ImageHostFailureKind.HOST_FAILURE,
            classifyImageHostFailure(null, httpCodeHint = 502),
        )
    }

    @Test
    fun http429IsTemporaryHostFailure() {
        assertEquals(
            ImageHostFailureKind.HOST_FAILURE,
            classifyImageHostFailure(null, httpCodeHint = 429),
        )
    }

    @Test
    fun cancellationIsNeverAPenalty() {
        assertEquals(
            ImageHostFailureKind.CANCELLED,
            classifyImageHostFailure(CancellationException("navigated away")),
        )
        // 包装在其它异常里的取消也要识别
        assertEquals(
            ImageHostFailureKind.CANCELLED,
            classifyImageHostFailure(RuntimeException(CancellationException("job cancelled"))),
        )
        assertEquals(
            ImageHostFailureKind.CANCELLED,
            classifyImageHostFailure(CancellationException("cancelled"), httpCodeHint = 500),
        )
    }

    @Test
    fun wrappedIoExceptionIsFoundThroughCauseChain() {
        val wrapped = IllegalStateException("load failed", SocketTimeoutException("read timed out"))

        assertEquals(ImageHostFailureKind.HOST_FAILURE, classifyImageHostFailure(wrapped))
    }

    @Test
    fun unknownErrorIsConservative() {
        assertEquals(
            ImageHostFailureKind.UNKNOWN,
            classifyImageHostFailure(IllegalArgumentException("weird")),
        )
        assertEquals(ImageHostFailureKind.UNKNOWN, classifyImageHostFailure(null))
        assertEquals(
            ImageHostFailureKind.UNKNOWN,
            classifyImageHostFailure(IOException("unspecified I/O failure")),
        )
    }

    @Test
    fun causeChainCycleDoesNotLoopForever() {
        val a = RuntimeException("a")
        val b = IOException("plain-io", a)
        a.initCause(b)

        assertEquals(ImageHostFailureKind.UNKNOWN, classifyImageHostFailure(a))
    }
}
