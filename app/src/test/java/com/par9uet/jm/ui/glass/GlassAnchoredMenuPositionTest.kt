package com.par9uet.jm.ui.glass

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassAnchoredMenuPositionTest {
    private val root = IntSize(400, 800)
    private val menu = IntSize(220, 300)

    @Test
    fun `start alignment clamps to the left edge`() {
        assertEquals(
            8,
            position(Rect(0f, 80f, 60f, 130f), GlassMenuAlignment.START).x,
        )
    }

    @Test
    fun `end alignment clamps to the right edge`() {
        assertEquals(
            172,
            position(Rect(370f, 80f, 400f, 130f), GlassMenuAlignment.END).x,
        )
    }

    @Test
    fun `menu opens below an anchor when space is available`() {
        assertEquals(
            136,
            position(Rect(80f, 80f, 180f, 130f), GlassMenuAlignment.START).y,
        )
    }

    @Test
    fun `menu falls back above an anchor near the bottom`() {
        assertEquals(
            394,
            position(Rect(80f, 700f, 180f, 750f), GlassMenuAlignment.START).y,
        )
    }

    @Test
    fun `oversized bottom placement is clamped inside the root`() {
        val tallMenu = IntSize(220, 760)
        val result = calculateGlassMenuPosition(
            anchorBounds = Rect(80f, 300f, 180f, 350f),
            rootSize = root,
            menuSize = tallMenu,
            marginPx = 8,
            gapPx = 6,
            alignment = GlassMenuAlignment.START,
        )
        assertEquals(32, result.y)
    }

    private fun position(anchor: Rect, alignment: GlassMenuAlignment): IntOffset =
        calculateGlassMenuPosition(
            anchorBounds = anchor,
            rootSize = root,
            menuSize = menu,
            marginPx = 8,
            gapPx = 6,
            alignment = alignment,
        )
}
