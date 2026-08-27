package com.par9uet.jm.store

import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.data.models.LocalSetting
import org.junit.Assert.assertEquals
import org.junit.Test

/** The narrow appearance flow must mirror the persisted launcher disguise selection. */
class LauncherDisguiseProjectionTest {
    private fun newManager(): LocalSettingManager {
        val persistence = object : com.par9uet.jm.storage.LocalSettingPersistence {
            var stored: LocalSetting? = null
            override fun load(): LocalSetting? = stored
            override fun persist(localSetting: LocalSetting) { stored = localSetting }
        }
        return LocalSettingManager(persistence, NoOpLauncherIdentityApplier())
    }

    @Test
    fun `non-default disguise selection projects into narrow flow`() {
        val manager = newManager()
        val appearance: AppearancePreferences = manager

        manager.updateLauncherDisguise(LauncherDisguise.Gallery.id)
        assertEquals(LauncherDisguise.Gallery.id, appearance.launcherDisguiseId.value)

        // 未知 id 在域层被归一为 Default。
        manager.updateLauncherDisguise("bogus")
        assertEquals(LauncherDisguise.Default.id, appearance.launcherDisguiseId.value)
    }
}
