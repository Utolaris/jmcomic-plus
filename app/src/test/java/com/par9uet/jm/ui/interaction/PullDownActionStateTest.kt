package com.par9uet.jm.ui.interaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullDownActionStateTest {
    private fun state() = PullDownActionState(threshold = 72f)

    @Test
    fun `idle becomes pulling and progress stays within range`() {
        val state = state()
        assertEquals(PullDownActionPhase.IDLE, state.phase)
        state.onPull(deltaY = 36f, isAtTop = true)
        assertEquals(PullDownActionPhase.PULLING, state.phase)
        assertEquals(0.5f, state.progress, 0.001f)
        state.onPull(deltaY = 100f, isAtTop = true)
        assertEquals(1f, state.progress, 0.001f)
    }

    @Test
    fun `pulling becomes armed at threshold`() {
        val state = state()
        state.onPull(deltaY = 71f, isAtTop = true)
        assertEquals(PullDownActionPhase.PULLING, state.phase)
        assertTrue(state.onPull(deltaY = 1f, isAtTop = true))
        assertEquals(PullDownActionPhase.ARMED, state.phase)
    }

    @Test
    fun `armed returns to pulling when distance drops`() {
        val state = state()
        state.onPull(deltaY = 80f, isAtTop = true)
        state.onPull(deltaY = -20f, isAtTop = true)
        assertEquals(PullDownActionPhase.PULLING, state.phase)
        assertFalse(state.isArmed)
    }

    @Test
    fun `armed release becomes triggering and claims once`() {
        val state = state()
        state.onPull(deltaY = 80f, isAtTop = true)
        assertTrue(state.release())
        assertEquals(PullDownActionPhase.TRIGGERING, state.phase)
        assertFalse(state.release())
    }

    @Test
    fun `armed haptic transition is emitted once per gesture`() {
        val state = state()
        assertTrue(state.onPull(deltaY = 80f, isAtTop = true))
        state.onPull(deltaY = -20f, isAtTop = true)
        assertFalse(state.onPull(deltaY = 20f, isAtTop = true))
    }

    @Test
    fun `release below threshold cancels to idle`() {
        val state = state()
        state.onPull(deltaY = 50f, isAtTop = true)
        assertFalse(state.release())
        assertEquals(PullDownActionPhase.IDLE, state.phase)
        assertEquals(0f, state.progress, 0.001f)
    }

    @Test
    fun `complete trigger and reset return to idle`() {
        val state = state()
        state.onPull(deltaY = 80f, isAtTop = true)
        state.release()
        state.completeTrigger()
        assertEquals(PullDownActionPhase.IDLE, state.phase)
        state.reset()
        assertEquals(PullDownActionPhase.IDLE, state.phase)
    }
}
