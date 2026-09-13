package com.par9uet.jm.storage

import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.launcher.LauncherIdentityApplier
import com.par9uet.jm.data.models.LauncherDisguise
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPersistenceBoundaryTest {
    private val launcher = object : LauncherIdentityApplier {
        override fun apply(disguise: LauncherDisguise) = true
    }

    @Test
    fun `temporary read failure blocks security state instead of unlocking`() {
        val persistence = object : LocalSettingPersistence {
            var unavailable = true
            override fun load(): LocalSettingLoadResult =
                if (unavailable) LocalSettingLoadResult.TemporaryUnavailable
                else LocalSettingLoadResult.Success(
                    LocalSetting(appLockEnabled = true, appLockPassword = "1234", onboardingCompleted = true),
                )

            override fun persist(localSetting: LocalSetting): StorageWriteResult = StorageWriteResult.Success
        }
        val manager = LocalSettingManager(persistence, launcher)
        assertTrue(manager.securityLoadBlocked.value)
        assertFalse(manager.appLock.value.enabled)

        persistence.unavailable = false
        manager.reloadSettings()
        assertFalse(manager.securityLoadBlocked.value)
        assertTrue(manager.appLock.value.enabled)
    }

    @Test
    fun `failed lock write reverts and does not look enabled`() {
        val persistence = object : LocalSettingPersistence {
            var writesFail = false
            private var stored: LocalSetting? = LocalSetting(onboardingCompleted = true)
            override fun load(): LocalSettingLoadResult =
                stored?.let { LocalSettingLoadResult.Success(it) } ?: LocalSettingLoadResult.Missing

            override fun persist(localSetting: LocalSetting): StorageWriteResult {
                if (writesFail) return StorageWriteResult.TemporaryUnavailable
                stored = localSetting
                return StorageWriteResult.Success
            }
        }
        val manager = LocalSettingManager(persistence, launcher)
        assertTrue(manager.setPassword("1234", 4))
        persistence.writesFail = true
        assertFalse(manager.setAppLockEnabled(true))
        assertFalse(manager.appLock.value.enabled)
    }
}
