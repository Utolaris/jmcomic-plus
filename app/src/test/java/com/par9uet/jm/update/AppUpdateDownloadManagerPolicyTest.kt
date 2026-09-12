package com.par9uet.jm.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppUpdateDownloadManagerPolicyTest {
    @Test
    fun `remote asset name is reduced to a safe basename`() {
        assertEquals("app.apk", safeUpdateFileName("../../app.apk"))
        assertEquals("app.apk", safeUpdateFileName("dir/app.apk"))
        assertEquals("app.apk", safeUpdateFileName("dir\\app.apk"))
        assertEquals("update.apk", safeUpdateFileName(""))
        assertEquals("update.apk", safeUpdateFileName("   "))
    }

    @Test
    fun `path traversal cannot escape the updates directory`() {
        val sanitized = safeUpdateFileName("../..\\evil.apk")
        assertFalse(sanitized.contains('/'))
        assertFalse(sanitized.contains('\\'))
        assertFalse(sanitized.contains(".."))
    }
}
