package com.par9uet.jm.backup

import com.google.gson.JsonParser
import com.par9uet.jm.data.models.LocalSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupProtectionEncryptionTest {
    private val codec = BackupManager()

    @Test
    fun `protected backup does not expose plaintext and metadata bypass cannot extract`() {
        val json = codec.createBackup(
            localSetting = LocalSetting(api = "https://sensitive.example"),
            options = BackupContentOptions(),
            protectionType = BACKUP_PROTECTION_BOTH,
            password = "1234",
            pattern = "0123",
        )
        assertFalse(json.contains("https://sensitive.example"))

        val edited = JsonParser.parseString(json).asJsonObject
        edited.getAsJsonObject("meta").addProperty("protectionType", BACKUP_PROTECTION_NONE)
        val parsed = codec.parseBackup(edited.toString()).getOrThrow()
        // Cipher remains even after protectionType is stripped; content stays unreadable.
        assertTrue(codec.isEncrypted(parsed))
        assertNull(codec.extractLocalSetting(parsed))
    }

    @Test
    fun `unlock with correct credentials restores content`() {
        val json = codec.createBackup(
            localSetting = LocalSetting(api = "https://sensitive.example"),
            options = BackupContentOptions(),
            protectionType = BACKUP_PROTECTION_PASSWORD,
            password = "1234",
        )
        val parsed = codec.parseBackup(json).getOrThrow()
        val unlocked = codec.unlockBackup(parsed, password = "1234").getOrThrow()
        assertFalse(codec.isEncrypted(unlocked))
        assertEquals("https://sensitive.example", codec.extractLocalSetting(unlocked)!!.api)
    }

    @Test
    fun `wrong password fails unlock`() {
        val json = codec.createBackup(
            localSetting = LocalSetting(api = "https://sensitive.example"),
            options = BackupContentOptions(),
            protectionType = BACKUP_PROTECTION_PASSWORD,
            password = "1234",
        )
        val parsed = codec.parseBackup(json).getOrThrow()
        assertThrows(IllegalStateException::class.java) {
            codec.unlockBackup(parsed, password = "0000").getOrThrow()
        }
    }

    @Test
    fun `unprotected backup remains plaintext and extractable`() {
        val json = codec.createBackup(
            localSetting = LocalSetting(api = "https://plain.example"),
            options = BackupContentOptions(),
            protectionType = BACKUP_PROTECTION_NONE,
        )
        val parsed = codec.parseBackup(json).getOrThrow()
        assertFalse(codec.isEncrypted(parsed))
        assertEquals("https://plain.example", codec.extractLocalSetting(parsed)!!.api)
    }
}
