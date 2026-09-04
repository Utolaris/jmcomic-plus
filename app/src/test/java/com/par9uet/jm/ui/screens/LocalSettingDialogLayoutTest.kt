package com.par9uet.jm.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSettingDialogLayoutTest {
    @Test
    fun `settings dialogs match card width with equal side gaps`() {
        assertEquals(328.dp, settingsDialogWidth(screenWidth = 360.dp))
        assertEquals(768.dp, settingsDialogWidth(screenWidth = 800.dp))
    }
}
