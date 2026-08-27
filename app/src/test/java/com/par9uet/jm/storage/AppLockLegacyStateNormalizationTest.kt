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
    fun `legacy appLockType migration runs through real storage normalization`() {
        // 旧 JSON 只有 appLockType：读取后应迁移出 PATTERN 模式并保持凭据合法。
        val legacyJson = """{"appLockEnabled":true,"appLockPattern":"01246","appLockType":"pattern"}"""
        val saved = gson.fromJson(legacyJson, LocalSetting::class.java)

        // 走 storage 的真实 normalize 路径（与 load() 相同），不再手工模拟模式推导。
        val normalized = normalizePersisted(legacyJson, saved)

        assertEquals(APP_LOCK_UNLOCK_MODE_PATTERN, normalized.appLockUnlockMode)
        org.junit.Assert.assertTrue(normalized.appLockEnabled)
        assertEquals("01246", normalized.appLockPattern)
        assertEquals("", normalized.appLockPassword)
    }

    @Test
    fun `legacy appLockType password value migrates to PASSWORD mode`() {
        val legacyJson = """{"appLockEnabled":true,"appLockPassword":"1234","appLockType":"password"}"""
        val saved = gson.fromJson(legacyJson, LocalSetting::class.java)

        val normalized = normalizePersisted(legacyJson, saved)

        assertEquals(APP_LOCK_UNLOCK_MODE_PASSWORD, normalized.appLockUnlockMode)
        assertEquals("1234", normalized.appLockPassword)
    }
}
