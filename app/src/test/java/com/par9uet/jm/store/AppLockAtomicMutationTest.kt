package com.par9uet.jm.store

import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_BOTH
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_PASSWORD
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_PATTERN
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.storage.LocalSettingPersistence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** One user action == one complete state transition; invalid combinations are unrepresentable. */
class AppLockAtomicMutationTest {
    private lateinit var manager: LocalSettingManager
    private val persisted = mutableListOf<LocalSetting>()

    @Before
    fun setUp() {
        persisted.clear()
        val persistence = object : LocalSettingPersistence {
            override fun load(): LocalSetting? = null
            override fun persist(localSetting: LocalSetting) {
                persisted += localSetting
            }
        }
        manager = LocalSettingManager(persistence, NoOpLauncherIdentityApplier())
    }

    @Test
    fun `setPassword is a single transition with length and unlock mode`() {
        manager.setPassword("1234", length = 6)

        val appLock = manager.appLock.value
        assertEquals("1234", appLock.password)
        assertEquals(6, appLock.passwordLength)
        assertEquals(APP_LOCK_UNLOCK_MODE_PASSWORD, appLock.unlockMode)
        assertFalse(appLock.enabled)
        assertEquals(1, persisted.size)
    }

    @Test
    fun `setPassword while pattern exists upgrades mode to BOTH`() {
        manager.setPattern("01246")

        manager.setPassword("1234", length = 4)

        assertEquals(APP_LOCK_UNLOCK_MODE_BOTH, manager.appLock.value.unlockMode)
    }

    @Test
    fun `removePassword falls back to PATTERN and keeps lock enabled`() {
        manager.setPassword("1234", 4)
        manager.setPattern("01246")
        manager.setAppLockEnabled(true)

        manager.removePassword()

        val appLock = manager.appLock.value
        assertFalse(appLock.hasPassword)
        assertTrue(appLock.hasPattern)
        assertEquals(APP_LOCK_UNLOCK_MODE_PATTERN, appLock.unlockMode)
        assertTrue(appLock.enabled)
    }

    @Test
    fun `removePattern falls back to PASSWORD and keeps lock enabled`() {
        manager.setPassword("1234", 4)
        manager.setPattern("01246")
        manager.setAppLockEnabled(true)
        manager.selectUnlockMode(APP_LOCK_UNLOCK_MODE_BOTH)

        manager.removePattern()

        val appLock = manager.appLock.value
        assertTrue(appLock.hasPassword)
        assertFalse(appLock.hasPattern)
        assertEquals(APP_LOCK_UNLOCK_MODE_PASSWORD, appLock.unlockMode)
        assertTrue(appLock.enabled)
    }

    @Test
    fun `removing final credential disables the lock`() {
        manager.setPassword("1234", 4)
        manager.setAppLockEnabled(true)

        manager.removePassword()

        val appLock = manager.appLock.value
        assertFalse(appLock.hasCredential)
        assertFalse(appLock.enabled)
        // Unlock mode stays a valid single-credential value instead of an undefined state.
        assertEquals(APP_LOCK_UNLOCK_MODE_PASSWORD, appLock.unlockMode)
    }

    @Test
    fun `BOTH can only be selected when both credentials exist`() {
        manager.setPassword("1234", 4)

        manager.selectUnlockMode(APP_LOCK_UNLOCK_MODE_BOTH)

        // Mode change cannot invent a missing credential; BOTH is only stored with both set.
        assertEquals(APP_LOCK_UNLOCK_MODE_PASSWORD, manager.appLock.value.unlockMode)
    }

    @Test
    fun `setAppLockEnabled without credentials keeps lock off`() {
        manager.setAppLockEnabled(true)

        assertFalse(manager.appLock.value.enabled)
    }

    @Test
    fun `disable and clear app lock is one persisted transition`() {
        manager.setPassword("1234", 4)
        manager.setPattern("01246")
        manager.setAppLockEnabled(true)
        val writesBefore = persisted.size

        manager.disableAndClearAppLock()

        val appLock = manager.appLock.value
        assertFalse(appLock.enabled)
        assertFalse(appLock.hasCredential)
        assertEquals(APP_LOCK_UNLOCK_MODE_PASSWORD, appLock.unlockMode)
        assertEquals(writesBefore + 1, persisted.size)
    }
}
