package com.par9uet.jm.store

import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.launcher.LauncherIdentityApplier
import com.par9uet.jm.storage.LocalSettingPersistence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockLifecycleTest {
    @Test
    fun `lock is enabled only after a credential exists`() {
        val manager = manager()

        manager.setAppLockEnabled(true)
        assertFalse(manager.appLock.value.enabled)

        manager.setPassword("1234", 4)
        manager.setAppLockEnabled(true)
        assertTrue(manager.appLock.value.enabled)
    }

    @Test
    fun `disabling lock clears credentials for the next cold start`() {
        val persisted = mutableListOf<LocalSetting>()
        val manager = manager(persisted)
        manager.setPassword("1234", 4)
        manager.setPattern("0123")
        manager.setAppLockEnabled(true)

        manager.disableAndClearAppLock()

        assertFalse(manager.appLock.value.enabled)
        assertFalse(manager.appLock.value.hasCredential)
        assertTrue(persisted.last().appLockPassword.isBlank())
        assertTrue(persisted.last().appLockPattern.isBlank())
    }

    private fun manager(persisted: MutableList<LocalSetting> = mutableListOf()): LocalSettingManager {
        val persistence = object : LocalSettingPersistence {
            override fun load(): LocalSetting? = persisted.lastOrNull()
            override fun persist(localSetting: LocalSetting) { persisted += localSetting }
        }
        return LocalSettingManager(persistence, object : LauncherIdentityApplier {
            override fun apply(disguise: LauncherDisguise) = Unit
        })
    }
}
