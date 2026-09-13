package com.par9uet.jm.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NetWorkResultMapParsingTest {
    @Test
    fun `transform exception becomes Parsing error`() {
        val result = NetWorkResult.Success("abc").map { it.toInt() }

        assertTrue(result is NetWorkResult.Error)
        result as NetWorkResult.Error
        assertEquals(NetworkErrorKind.Parsing, result.kind)
        assertTrue(result.cause is NumberFormatException)
    }

    @Test
    fun `error passes through unchanged`() {
        val origin = NetWorkResult.Error("boom", code = 500, kind = NetworkErrorKind.Server)
        val mapped = origin.map { it }

        assertTrue(mapped === origin)
    }

    @Test
    fun `cancellation is not swallowed as Parsing`() = runTest {
        assertThrows(CancellationException::class.java) {
            NetWorkResult.Success(1).map { throw CancellationException("stop") }
        }
    }
}
