package com.par9uet.jm.ui.interaction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullDownActionStateTest {
    private fun state() = PullDownActionState(threshold = 72f)

    @Test
    fun `pull away from top never arms`() {
        val state = state()
        state.onPull(deltaY = 100f, isAtTop = false)
        assertFalse(state.isArmed)
        assertFalse(state.release())
    }

    @Test
    fun `pull below threshold does not arm`() {
        val state = state()
        state.onPull(deltaY = 71f, isAtTop = true)
        assertFalse(state.isArmed)
        assertFalse(state.release())
    }

    @Test
    fun `pull above threshold arms and triggers on release`() {
        val state = state()
        state.onPull(deltaY = 80f, isAtTop = true)
        assertTrue(state.isArmed)
        assertTrue(state.release())
    }

    @Test
    fun `one gesture triggers only once`() {
        val state = state()
        state.onPull(deltaY = 80f, isAtTop = true)
        assertTrue(state.release())
        state.onPull(deltaY = 100f, isAtTop = true)
        assertFalse(state.release())
    }

    @Test
    fun `reset permits the next independent gesture`() {
        val state = state()
        state.onPull(deltaY = 80f, isAtTop = true)
        assertTrue(state.release())
        state.reset()
        state.onPull(deltaY = 80f, isAtTop = true)
        assertTrue(state.release())
    }
}
