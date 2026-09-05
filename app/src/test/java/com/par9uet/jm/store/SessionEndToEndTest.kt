package com.par9uet.jm.store

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEndToEndTest {
    @Test
    fun `restoration gates authenticated work until the session is ready`() = runTest {
        val readiness = SessionReadinessHolder().apply { set(SessionReadiness.Restoring) }
        val gate = AuthenticatedSessionGate(readiness)
        var calls = 0
        val request = launch { gate.run { calls++ } }

        assertEquals(0, calls)
        readiness.set(SessionReadiness.Authenticated)
        request.join()
        assertEquals(1, calls)
    }

    @Test
    fun `logout readiness rejects stale work and cancellation propagates`() = runTest {
        val readiness = SessionReadinessHolder().apply { set(SessionReadiness.Unauthenticated) }
        val gate = AuthenticatedSessionGate(readiness)

        assertFalse(readiness.state.value == SessionReadiness.Authenticated)
        try {
            gate.run<Unit> { error("must not execute") }
        } catch (error: AuthenticatedSessionRequiredException) {
            assertTrue(error.message!!.contains("登录"))
        }

        readiness.set(SessionReadiness.Authenticated)
        try {
            gate.run<Unit> { throw CancellationException("switch account") }
        } catch (error: CancellationException) {
            assertEquals("switch account", error.message)
        }
    }
}
