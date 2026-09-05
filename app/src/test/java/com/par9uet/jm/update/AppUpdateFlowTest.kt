package com.par9uet.jm.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateFlowTest {
    @Test
    fun `version comparison handles release suffixes and missing components`() {
        assertTrue(compareVersion("1.4.1", "1.4.0") > 0)
        assertEquals(0, compareVersion("v1.4", "1.4.0-debug"))
        assertFalse(compareVersion("1.3.9", "1.4.0") >= 0)
    }

    @Test
    fun `release parser selects the compatible apk asset`() {
        val release = parseGithubRelease(
            """{"tag_name":"v1.4.1","name":"JMcomic Plus v1.4.1","body":"changes","assets":[{"name":"jm-mobile_v1.4.1.apk","browser_download_url":"https://example.com/app.apk"}]}"""
        )

        assertEquals("1.4.1", release.version)
        assertEquals("https://example.com/app.apk", release.downloadUrl)
    }
}
