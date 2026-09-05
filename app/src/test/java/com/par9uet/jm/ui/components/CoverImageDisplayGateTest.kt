package com.par9uet.jm.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CoverImageDisplayGateTest {
    @Test
    fun deferredPainterIsReleasedOnce() {
        val gate = CoverImageDisplayGate()
        val painter = ColorPainter(Color.Red)

        assertNull(gate.takeDeferredPainter())

        gate.deferSuccess(painter)

        assertSame(painter, gate.takeDeferredPainter())
        assertNull(gate.takeDeferredPainter())
    }
}
