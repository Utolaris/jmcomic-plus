package com.par9uet.jm.store
import com.par9uet.jm.storage.LocalSettingManager

import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.launcher.LauncherIdentityApplier
import com.par9uet.jm.storage.LocalSettingPersistence
import org.junit.Assert.assertEquals
import org.junit.Test

/** A disguise whose alias did not switch must not be stored as the current one. */
class LauncherDisguiseFailureTest {
    private val persisted = mutableListOf<LocalSetting>()

    private fun manager(applier: LauncherIdentityApplier): LocalSettingManager =
        LocalSettingManager(
            object : LocalSettingPersistence {
                override fun load(): LocalSetting? = persisted.lastOrNull()
                override fun persist(localSetting: LocalSetting) { persisted += localSetting }
            },
            applier,
        )

    @Test
    fun `failed switch keeps the stored disguise`() {
        val manager = manager(RefusingLauncherApplier())

        manager.updateLauncherDisguise(LauncherDisguise.Gallery.id)

        assertEquals(LauncherDisguise.Default.id, manager.launcherDisguiseId.value)
        assertEquals(emptyList<LocalSetting>(), persisted)
    }

    @Test
    fun `successful switch stores the disguise`() {
        val manager = manager(NoOpLauncherIdentityApplier())

        manager.updateLauncherDisguise(LauncherDisguise.Gallery.id)

        assertEquals(LauncherDisguise.Gallery.id, manager.launcherDisguiseId.value)
        assertEquals(LauncherDisguise.Gallery.id, persisted.last().launcherDisguise)
    }

    @Test
    fun `failed restore keeps this device's entry`() {
        val manager = manager(RefusingLauncherApplier())

        manager.applyLocalSetting(LocalSetting(launcherDisguise = LauncherDisguise.SystemTools.id))

        assertEquals(LauncherDisguise.Default.id, manager.launcherDisguiseId.value)
        assertEquals(LauncherDisguise.Default.id, persisted.last().launcherDisguise)
    }

    private class RefusingLauncherApplier : LauncherIdentityApplier {
        override fun apply(disguise: LauncherDisguise) = false
    }
}
