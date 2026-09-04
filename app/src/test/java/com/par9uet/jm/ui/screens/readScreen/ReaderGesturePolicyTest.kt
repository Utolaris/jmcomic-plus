package com.par9uet.jm.ui.screens.readScreen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderGesturePolicyTest {
    @Test
    fun `free pan starts at the same speed horizontally vertically and diagonally`() {
        fun firstAppliedPan(delta: Offset): Offset {
            val tracker = ReaderFreePanTracker(touchSlop = 10f)
            return requireNotNull(tracker.add(delta))
        }

        val horizontal = firstAppliedPan(Offset(14f, 0f))
        val vertical = firstAppliedPan(Offset(0f, 14f))
        val diagonalComponent = 14f / kotlin.math.sqrt(2f)
        val diagonal = firstAppliedPan(Offset(diagonalComponent, diagonalComponent))

        assertEquals(4f, horizontal.getDistance(), 0.001f)
        assertEquals(horizontal.getDistance(), vertical.getDistance(), 0.001f)
        assertEquals(horizontal.getDistance(), diagonal.getDistance(), 0.001f)
    }

    @Test
    fun `free pan preserves every two dimensional delta after activation`() {
        val tracker = ReaderFreePanTracker(touchSlop = 10f)
        requireNotNull(tracker.add(Offset(12f, 0f)))

        val applied = tracker.add(Offset(x = -3f, y = 7f))

        assertEquals(Offset(x = -3f, y = 7f), applied)
    }

    @Test
    fun `single pointer at original size stays available to reader navigation`() {
        val session = ReaderGestureSession(startedZoomed = false)

        session.observe(1)

        assertFalse(session.ownsTransform)
        assertFalse(session.sawMultiplePointers)
    }

    @Test
    fun `second pointer owns the complete gesture until every pointer is released`() {
        val session = ReaderGestureSession(startedZoomed = false)

        session.observe(2)
        session.observe(1)
        session.observe(0)

        assertTrue(session.ownsTransform)
        assertTrue(session.sawMultiplePointers)
    }

    @Test
    fun `zoomed single pointer only owns gesture after pan is claimed`() {
        val session = ReaderGestureSession(startedZoomed = true)

        session.observe(1)
        assertFalse(session.ownsTransform)

        session.claimSinglePointerPan()
        assertTrue(session.ownsTransform)
    }

    @Test
    fun `tap is canceled after a second pointer appears`() {
        val session = ReaderGestureSession()

        session.observe(pointerCount = 1, consumed = false)
        session.observe(pointerCount = 2, consumed = false)
        session.observe(pointerCount = 1, consumed = false)

        assertFalse(session.canDispatchTap(distance = 0f, maximumDistance = 10f))
    }

    @Test
    fun `short unconsumed single pointer gesture remains a tap`() {
        val session = ReaderGestureSession()

        session.observe(pointerCount = 1, consumed = false)

        assertTrue(session.canDispatchTap(distance = 9f, maximumDistance = 10f))
        assertFalse(session.canDispatchTap(distance = 10f, maximumDistance = 10f))
    }

    @Test
    fun `consumed reader gesture is not dispatched as tap`() {
        val session = ReaderGestureSession()

        session.observe(pointerCount = 1, consumed = true)

        assertFalse(session.canDispatchTap(distance = 0f, maximumDistance = 10f))
    }

    @Test
    fun `only middle third in both axes is reader center`() {
        val size = IntSize(width = 300, height = 600)

        assertTrue(isReaderCenter(Offset(150f, 300f), size))
        assertTrue(isReaderCenter(Offset(100f, 200f), size))
        assertTrue(isReaderCenter(Offset(200f, 400f), size))
        assertFalse(isReaderCenter(Offset(99f, 300f), size))
        assertFalse(isReaderCenter(Offset(150f, 401f), size))
        assertFalse(isReaderCenter(Offset(150f, 300f), IntSize.Zero))
    }

    @Test
    fun `zooming back to original size snaps scale and offset to exact defaults`() {
        val state = ReaderZoomState()
        state.updateViewportSize(width = 300, height = 600)
        state.applyTransform(zoomChange = 2f, panChange = Offset(40f, 60f))

        state.applyTransform(zoomChange = 0.5f, panChange = Offset.Zero)

        assertEquals(1f, state.scale, 0f)
        assertEquals(Offset.Zero, state.offset)
        assertFalse(state.isZoomed)
    }

    @Test
    fun `center double tap resets zoom while edge double tap does nothing`() {
        val state = ReaderZoomState()
        val size = IntSize(width = 300, height = 600)
        state.updateViewportSize(size.width, size.height)
        state.applyTransform(zoomChange = 2f, panChange = Offset.Zero)

        assertFalse(resetZoomFromCenterDoubleTap(state, Offset(20f, 300f), size))
        assertTrue(state.isZoomed)

        assertTrue(resetZoomFromCenterDoubleTap(state, Offset(150f, 300f), size))
        assertFalse(state.isZoomed)
        assertEquals(1f, state.scale, 0f)
        assertEquals(Offset.Zero, state.offset)
    }

    @Test
    fun `center double tap at original size does not change zoom`() {
        val state = ReaderZoomState()

        assertFalse(
            resetZoomFromCenterDoubleTap(
                zoomState = state,
                position = Offset(150f, 300f),
                size = IntSize(width = 300, height = 600)
            )
        )
        assertEquals(1f, state.scale, 0f)
    }
}
