package com.par9uet.jm.storage

import com.par9uet.jm.data.models.AVAILABLE_APIS
import com.par9uet.jm.data.models.AVAILABLE_THEMES
import com.par9uet.jm.data.models.LocalSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Old installs persist extra fields (apiList/themeList, retired values). The unified DTO must
 * keep reading their JSON without breaking selected api/theme; candidate lists now come from
 * the static catalog instead of storage.
 */
class LocalSettingStorageCompatibilityTest {
    private val gson = com.google.gson.Gson()

    @Test
    fun `legacy json with apiList and themeList keeps selections`() {
        val legacyJson = """
            {
              "api": "https://www.cdnmhwscc.vip",
              "apiList": ["https://old-a.example", "https://old-b.example"],
              "theme": "dark",
              "themeList": ["legacy-theme"]
            }
        """.trimIndent()

        val decoded = gson.fromJson(legacyJson, LocalSetting::class.java)

        assertEquals("https://www.cdnmhwscc.vip", decoded.api)
        assertEquals("dark", decoded.theme)
    }

    @Test
    fun `candidate catalogs come from code not from old json`() {
        assertEquals(listOf("auto", "light", "dark"), AVAILABLE_THEMES)
        assertFalse(AVAILABLE_THEMES.contains("legacy-theme"))
        // Five built-in proxy endpoints remain the static catalog.
        assertTrue(com.par9uet.jm.data.models.AVAILABLE_APIS.isNotEmpty())
    }

    @Test
    fun `round trip without deleted fields keeps Defaults`() {
        val decoded = gson.fromJson("{}", LocalSetting::class.java)
        assertEquals(LocalSetting().api, decoded.api)
        assertNull(decoded.customColorPrimary)
    }

    @Test
    fun `retired values are still ignored`() {
        val decoded = gson.fromJson(
            """{"comicApiSource":"builtin","shunt":"4","theme":"dark"}""",
            LocalSetting::class.java,
        )
        assertEquals("dark", decoded.theme)
        val migratedJson = gson.toJson(decoded)
        assertFalse(migratedJson.contains("comicApiSource"))
        assertFalse(migratedJson.contains("shunt"))
        assertFalse(migratedJson.contains("apiList"))
        assertFalse(migratedJson.contains("themeList"))
    }
}
