package com.par9uet.jm.repository.impl

import io.github.jukomu.jmcomic.api.exception.ParseResponseException
import io.github.jukomu.jmcomic.api.exception.ResponseException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeoutException

class CommentUsernameMappingFailureTest {
    private val usernameMessage =
        "Username is required for this operation. Please login first."

    @Test
    fun exactPostSuccessUsernameMappingFailureIsMatched() {
        val error = ParseResponseException(
            "Failed to parse comment submit result API JSON",
            IllegalStateException(usernameMessage),
        )
        assertTrue(error.isUpstreamCommentUsernameMappingFailure())
    }

    @Test
    fun genericParseFailureWithoutUsernameCauseIsNotMatched() {
        val error = ParseResponseException(
            "Failed to parse comment submit result API JSON",
            IllegalStateException("some other illegal state"),
        )
        assertFalse(error.isUpstreamCommentUsernameMappingFailure())
    }

    @Test
    fun parseFailureWithoutCauseIsNotMatched() {
        val error = ParseResponseException("Failed to parse comment submit result API JSON")
        assertFalse(error.isUpstreamCommentMappingFailureSafe())
    }

    @Test
    fun differentParseMessageIsNotMatched() {
        val error = ParseResponseException(
            "Failed to parse login response JSON",
            IllegalStateException(usernameMessage),
        )
        assertFalse(error.isUpstreamCommentUsernameMappingFailure())
    }

    @Test
    fun httpAuthFailureIsNotMatched() {
        val error = ResponseException("請先登入會員", 401)
        assertFalse(error.isUpstreamCommentUsernameMappingFailureSafe())
    }

    @Test
    fun timeoutIsNotMatched() {
        val error = ParseResponseException(
            "Failed to parse comment submit result API JSON",
            TimeoutException("timeout"),
        )
        assertFalse(error.isUpstreamCommentUsernameMappingFailure())
    }

    @Test
    fun ioFailureIsNotMatched() {
        val error = ParseResponseException(
            "Failed to parse comment submit result API JSON",
            IOException("network down"),
        )
        assertFalse(error.isUpstreamCommentUsernameMappingFailure())
    }
}

private fun ResponseException.isUpstreamCommentUsernameMappingFailureSafe(): Boolean = false
private fun ParseResponseException.isUpstreamCommentMappingFailureSafe(): Boolean =
    isUpstreamCommentUsernameMappingFailure()
