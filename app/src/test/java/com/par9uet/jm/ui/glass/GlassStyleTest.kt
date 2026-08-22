package com.par9uet.jm.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassStyleTest {
    @Test
    fun defaultStyleKeepsTheInitialNagramLikeGeometryCentralized() {
        val style = GlassStyle.Default

        assertEquals(56f, style.barHeight.value, 0f)
        assertEquals(8f, style.outerMargin.value, 0f)
        assertEquals(250f, style.maxBarWidth.value, 0f)
        assertEquals(18f, style.blurRadius.value, 0f)
        assertEquals(0.76f, style.tintAlpha, 0f)
        assertEquals(0.09f, style.selectedIndicatorAlpha, 0f)
    }

    @Test
    fun nativeBackdropBlurRequiresAndroid12() {
        assertFalse(GlassCapabilities.usesNativeBackdropBlur(30))
        assertTrue(GlassCapabilities.usesNativeBackdropBlur(31))
        assertTrue(GlassCapabilities.usesNativeBackdropBlur(35))
    }
}
