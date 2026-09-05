package com.par9uet.jm.ui.screens.readScreen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderInteractionTest {
    @Test
    fun `pinch owns gesture and cannot dispatch a page turn`() {
        val session = ReaderGestureSession()
        session.observe(pointerCount = 1, consumed = false)
        session.observe(pointerCount = 2, consumed = false)

        assertTrue(session.ownsTransform)
        assertFalse(session.canDispatchTap(distance = 0f, maximumDistance = 10f))
    }

    @Test
    fun `zoomed pan stays on page and double tap center restores navigation state`() {
        val state = ReaderZoomState()
        val size = IntSize(300, 600)
        state.updateViewportSize(size.width, size.height)
        state.applyTransform(zoomChange = 2f, panChange = Offset(20f, 30f))

        assertTrue(state.isZoomed)
        assertTrue(resetZoomFromCenterDoubleTap(state, Offset(150f, 300f), size))
        assertFalse(state.isZoomed)
        assertEquals(Offset.Zero, state.offset)
    }
}
