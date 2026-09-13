package com.par9uet.jm.storage

import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.launcher.LauncherIdentityApplier
import com.par9uet.jm.store.NoOpLauncherIdentityApplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Write-confirmed publish and restore-time identity preservation:
 * a failed persist must not look saved, and a restored file must not reopen onboarding
 * around an existing app lock.
 */
class LocalSettingManagerWriteConfirmTest {

    private class ControllablePersistence(
        initial: LocalSetting? = null,
    ) : LocalSettingPersistence {
        var durable: LocalSetting? = initial
        var failNextWrites = false
        var writeCount = 0

        override fun load(): LocalSettingLoadResult =
            durable?.let { LocalSettingLoadResult.Success(it) } ?: LocalSettingLoadResult.Missing

        override fun persist(localSetting: LocalSetting): StorageWriteResult {
            if (failNextWrites) return StorageWriteResult.TemporaryUnavailable
            durable = localSetting
            writeCount++
            return StorageWriteResult.Success
        }
    }

    private class RecordingApplier : LauncherIdentityApplier {
        val applied = mutableListOf<String>()
        var accept = true
        override fun apply(disguise: LauncherDisguise): Boolean {
            applied += disguise.id
            return accept
        }
    }

    private fun manager(
        persistence: LocalSettingPersistence,
        applier: LauncherIdentityApplier = NoOpLauncherIdentityApplier(),
    ) = LocalSettingManager(persistence, applier)

    @Test
    fun `privacy setting write failure does not publish the new value`() {
        val persistence = ControllablePersistence(LocalSetting(showComicCacheNotificationName = true))
        val m = manager(persistence)

        persistence.failNextWrites = true
        val ok = m.applyNotificationSetting(show = true, showName = false)

        assertFalse(ok)
        assertTrue(m.cacheNotification.value.showName)
        // Reconstructing the manager still sees the last successful durable value.
        assertTrue(manager(persistence).cacheNotification.value.showName)
    }

    @Test
    fun `clipboard toggle write failure keeps previous durable state`() {
        val persistence = ControllablePersistence(LocalSetting(clipboardAutoDetectEnabled = false))
        val m = manager(persistence)

        persistence.failNextWrites = true
        assertFalse(m.updateClipboardAutoDetectEnabled(true))
        assertFalse(m.misc.value.clipboardAutoDetectEnabled)

        persistence.failNextWrites = false
        assertTrue(m.updateClipboardAutoDetectEnabled(true))
        assertTrue(manager(persistence).misc.value.clipboardAutoDetectEnabled)
    }

    @Test
    fun `security lock enable failure does not report success`() {
        val persistence = ControllablePersistence(
            LocalSetting(appLockEnabled = false, appLockPassword = "1234"),
        )
        val m = manager(persistence)

        persistence.failNextWrites = true
        assertFalse(m.setAppLockEnabled(true))
        assertFalse(m.appLock.value.enabled)
        assertFalse(manager(persistence).appLock.value.enabled)
    }

    @Test
    fun `restore preserves onboarding when lock is already enabled`() {
        val persistence = ControllablePersistence(
            LocalSetting(
                onboardingCompleted = false,
                appLockEnabled = true,
                appLockPassword = "1234",
            ),
        )
        val m = manager(persistence)

        // Backup omits onboardingCompleted (Gson default false) and has no lock credentials.
        m.applyLocalSetting(LocalSetting(theme = "dark", onboardingCompleted = false))

        assertTrue(m.onboardingCompleted.value)
        assertTrue(m.appLock.value.enabled)
        assertEquals("1234", m.appLock.value.password)
        assertEquals("dark", m.theme.value)
        assertTrue(manager(persistence).onboardingCompleted.value)
    }

    @Test
    fun `restore preserves onboarding when already completed`() {
        val persistence = ControllablePersistence(LocalSetting(onboardingCompleted = true))
        val m = manager(persistence)

        m.applyLocalSetting(LocalSetting(onboardingCompleted = false, theme = "light"))

        assertTrue(m.onboardingCompleted.value)
        assertEquals("light", m.theme.value)
    }

    @Test
    fun `restore keeps backup onboarding on a fresh device without lock`() {
        val persistence = ControllablePersistence(LocalSetting(onboardingCompleted = false))
        val m = manager(persistence)

        m.applyLocalSetting(LocalSetting(onboardingCompleted = true, theme = "dark"))

        assertTrue(m.onboardingCompleted.value)
    }

    @Test
    fun `failed restore write rolls launcher alias back`() {
        val persistence = ControllablePersistence(LocalSetting(launcherDisguise = LauncherDisguise.Default.id))
        val applier = RecordingApplier()
        val m = manager(persistence, applier)

        persistence.failNextWrites = true
        m.applyLocalSetting(LocalSetting(launcherDisguise = LauncherDisguise.Gallery.id))

        // Alias switched once for the request, then restored to Default after persist failed.
        assertEquals(listOf(LauncherDisguise.Gallery.id, LauncherDisguise.Default.id), applier.applied)
        assertEquals(LauncherDisguise.Default.id, m.launcherDisguiseId.value)
        assertEquals(LauncherDisguise.Default.id, persistence.durable?.launcherDisguise)
    }

    @Test
    fun `failed launcher disguise write rolls alias back`() {
        val persistence = ControllablePersistence(LocalSetting(launcherDisguise = LauncherDisguise.Default.id))
        val applier = RecordingApplier()
        val m = manager(persistence, applier)

        persistence.failNextWrites = true
        m.updateLauncherDisguise(LauncherDisguise.Gallery.id)

        assertEquals(listOf(LauncherDisguise.Gallery.id, LauncherDisguise.Default.id), applier.applied)
        assertEquals(LauncherDisguise.Default.id, m.launcherDisguiseId.value)
    }
}
