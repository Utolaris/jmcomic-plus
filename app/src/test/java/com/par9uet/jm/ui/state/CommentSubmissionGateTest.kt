package com.par9uet.jm.ui.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentSubmissionGateTest {
    @Test
    fun oneSubmissionRunsAtATime() {
        val gate = CommentSubmissionGate()

        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())

        gate.release()
        assertTrue(gate.tryAcquire())
    }
}
