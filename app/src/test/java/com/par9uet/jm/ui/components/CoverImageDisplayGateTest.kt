package com.par9uet.jm.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverImageDisplayGateTest {
    @Test
    fun deferredSuccessIsReleasedOnce() {
        val gate = CoverImageDisplayGate()

        assertFalse(gate.consumeDeferredSuccess())

        gate.deferSuccess()

        assertTrue(gate.consumeDeferredSuccess())
        assertFalse(gate.consumeDeferredSuccess())
    }
}
