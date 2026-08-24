package com.par9uet.jm.storage

import com.google.gson.Gson
import com.par9uet.jm.data.models.LocalSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LegacyLocalSettingMigrationTest {
    private val gson = Gson()

    @Test
    fun retiredApiSourceAndImageShuntValuesAreIgnored() {
        listOf("builtin", "mixed", "network").forEach { legacySource ->
            val decoded = gson.fromJson(
                """{"comicApiSource":"$legacySource","shunt":"4","theme":"dark"}""",
                LocalSetting::class.java,
            )

            assertEquals("dark", decoded.theme)
            val migratedJson = gson.toJson(decoded)
            assertFalse(migratedJson.contains("comicApiSource"))
            assertFalse(migratedJson.contains("shunt"))
        }
    }
}
