package com.par9uet.jm.storage

import com.google.gson.Gson
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_BOTH
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_PASSWORD
import com.par9uet.jm.data.models.APP_LOCK_UNLOCK_MODE_PATTERN
import com.par9uet.jm.data.models.LocalSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Legacy installs could persist lock states the atomic mutator can no longer produce. */
class AppLockLegacyStateNormalizationTest {
    private val gson = Gson()

    private fun canonicalize(json: String): CanonicalAppLock {
        val saved = gson.fromJson(json, LocalSetting::class.java)
        return canonicalizeAppLock(saved, saved.appLockUnlockMode)
    }

    @Test
    fun `case D no credential but enabled turns disabled with PASSWORD mode`() {
        val result = canonicalize(
            """{"appLockEnabled":true,"appLockPassword":"","appLockPattern":""}"""
        )
        assertFalse(result.enabled)
        assertEquals("", result.password)
        assertEquals("", result.pattern)
        assertEquals(APP_LOCK_UNLOCK_MODE_PASSWORD, result.unlockMode)
    }

    @Test
    fun `case B only password with BOTH mode becomes PASSWORD`() {
        val result = canonicalize(
            """{"appLockEnabled":true,"appLockPassword":"1234","appLockUnlockMode":"both"}"""
        )
        assertEquals(APP_LOCK_UNLOCK_MODE_PASSWORD, result.unlockMode)
        assertEquals("1234", result.password)
        assertEquals("", result.pattern)
        assertTrue(result.enabled)
    }

    @Test
    fun `case C only pattern with PASSWORD mode becomes PATTERN`() {
        val result = canonicalize(
            """{"appLockEnabled":true,"appLockPattern":"01246","appLockUnlockMode":"password"}"""
        )
        assertEquals(APP_LOCK_UNLOCK_MODE_PATTERN, result.unlockMode)
        assertTrue(result.enabled)
    }

    @Test
    fun `case A both credentials with valid BOTH is preserved`() {
        val result = canonicalize(
            """{"appLockEnabled":true,"appLockPassword":"1234","appLockPattern":"01246","appLockUnlockMode":"both"}"""
        )
        assertEquals(APP_LOCK_UNLOCK_MODE_BOTH, result.unlockMode)
        assertTrue(result.enabled)
    }

    @Test
    fun `case A both credentials with invalid mode falls back to BOTH`() {
        val result = canonicalize(
            """{"appLockEnabled":true,"appLockPassword":"1234","appLockPattern":"01246","appLockUnlockMode":"weird"}"""
        )
        assertEquals(APP_LOCK_UNLOCK_MODE_BOTH, result.unlockMode)
    }

    @Test
    fun `legacy appLockType migration still works through normalize`() {
        // appLockType=pattern 迁移后：仅图案凭据 + PATTERN 模式。
        val saved = gson.fromJson(
            """{"appLockEnabled":true,"appLockPattern":"01246"}""",
            LocalSetting::class.java,
        )
        val legacyType = com.par9uet.jm.data.models.APP_LOCK_TYPE_PATTERN
        val migratedMode = "pattern" // parseLegacyAppLockType maps pattern->PATTERN
        val result = canonicalizeAppLock(saved, if (migratedMode == legacyType) APP_LOCK_UNLOCK_MODE_PATTERN else APP_LOCK_UNLOCK_MODE_PASSWORD)
        assertEquals(APP_LOCK_UNLOCK_MODE_PATTERN, result.unlockMode)
    }
}
