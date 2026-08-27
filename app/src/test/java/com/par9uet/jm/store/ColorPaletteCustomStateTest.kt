package com.par9uet.jm.store

import com.par9uet.jm.data.models.COLOR_PALETTE_PRESET_CUSTOM
import com.par9uet.jm.data.models.COLOR_PALETTE_PRESET_DEFAULT
import com.par9uet.jm.data.models.COLOR_PALETTE_PRESET_OCEAN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * CUSTOM is not a selectable preset: entering it only happens through applyCustomColors, and
 * preset selection must never destroy saved custom colors.
 */
class ColorPaletteCustomStateTest {
    private lateinit var manager: LocalSettingManager

    @Before
    fun setUp() {
        val persistence = object : com.par9uet.jm.storage.LocalSettingPersistence {
            var stored: com.par9uet.jm.data.models.LocalSetting? = null
            override fun load(): com.par9uet.jm.data.models.LocalSetting? = stored
            override fun persist(localSetting: com.par9uet.jm.data.models.LocalSetting) {
                stored = localSetting
            }
        }
        manager = LocalSettingManager(persistence, NoOpLauncherIdentityApplier())
        // Persistent writes keep the manager in a known clean state per test.
        manager.selectColorPreset(COLOR_PALETTE_PRESET_DEFAULT)
    }

    @Test
    fun `applyCustomColors switches preset to CUSTOM and keeps values`() {
        manager.applyCustomColors("#FF111111", "#FF222222", "#FF333333", "#FF444444")

        val palette = manager.colorPalette.value
        assertEquals(COLOR_PALETTE_PRESET_CUSTOM, palette.presetId)
        assertEquals("#FF111111", palette.customPrimary)
        assertEquals("#FF444444", palette.customError)
    }

    @Test
    fun `selectColorPreset CUSTOM is a no-op and keeps custom colors`() {
        manager.applyCustomColors("#FF111111", null, null, null)

        // Even if a caller routes the custom card through the preset path, values survive.
        manager.selectColorPreset(COLOR_PALETTE_PRESET_CUSTOM)

        val palette = manager.colorPalette.value
        assertEquals(COLOR_PALETTE_PRESET_CUSTOM, palette.presetId)
        assertEquals("#FF111111", palette.customPrimary)
    }

    @Test
    fun `CUSTOM to real preset clears custom values`() {
        manager.applyCustomColors("#FF111111", "#FF222222", null, null)

        manager.selectColorPreset(COLOR_PALETTE_PRESET_OCEAN)

        val palette = manager.colorPalette.value
        assertEquals(COLOR_PALETTE_PRESET_OCEAN, palette.presetId)
        assertNull(palette.customPrimary)
        assertNull(palette.customSecondary)
    }

    @Test
    fun `preset CUSTOM then applyCustomColors is one transition`() {
        manager.selectColorPreset(COLOR_PALETTE_PRESET_DEFAULT)

        manager.applyCustomColors("#FFAAAAAA", null, null, null)

        assertEquals(COLOR_PALETTE_PRESET_CUSTOM, manager.colorPalette.value.presetId)
    }
}
