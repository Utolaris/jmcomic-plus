package com.par9uet.jm.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import coil.decode.DataSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverLoadingScrollTest {
    @Test
    fun `completed painter remains pending until it can be published`() {
        val deferredPainter = ColorPainter(Color.Red)
        val gate = CoverImageDisplayGate()

        gate.deferSuccess(deferredPainter)

        assertSame(deferredPainter, gate.takeDeferredPainter())
    }

    @Test
    fun `network completion is deferred only while scrolling`() {
        assertTrue(shouldDeferCoverResult(true, DataSource.NETWORK))
        assertFalse(shouldDeferCoverResult(false, DataSource.NETWORK))
    }

    @Test
    fun `memory cache completion is immediately drawable during scrolling`() {
        assertFalse(shouldDeferCoverResult(true, DataSource.MEMORY_CACHE))
        assertFalse(shouldDeferCoverResult(true, DataSource.MEMORY))
    }

    @Test
    fun `deferred painter is released after scrolling settles`() {
        val gate = CoverImageDisplayGate()
        val painter = ColorPainter(Color.Red)

        gate.deferSuccess(painter)

        assertSame(painter, gate.takeDeferredPainter())
        assertSame(null, gate.takeDeferredPainter())
    }
}
