package com.par9uet.jm.backup

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.par9uet.jm.data.models.LocalSetting
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupProtectionEncryptionTest {
    private val codec = BackupManager()

    private fun unlockedSetting(json: String, password: String? = null, pattern: String? = null): LocalSetting {
        val parsed = codec.parseBackup(json).getOrThrow()
        val unlocked = codec.unlockBackup(parsed, password, pattern).getOrThrow()
        val result = codec.extractLocalSetting(unlocked)
        assertTrue(result is BackupSectionResult.Success)
        return (result as BackupSectionResult.Success).value
    }

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
        assertEquals(BackupSectionResult.Missing, codec.extractLocalSetting(parsed))
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
        assertEquals("https://sensitive.example", unlockedSetting(json, password = "1234").api)
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
        assertEquals("https://plain.example", unlockedSetting(json).api)
    }

    @Test
    fun `new format does not write fast credential digests`() {
        val json = codec.createBackup(
            localSetting = LocalSetting(),
            options = BackupContentOptions(),
            protectionType = BACKUP_PROTECTION_BOTH,
            password = "1234",
            pattern = "0123",
        )
        val meta = JsonParser.parseString(json).asJsonObject.getAsJsonObject("meta")
        assertTrue(meta.get("passwordHash") == null || meta.get("passwordHash").isJsonNull)
        assertTrue(meta.get("patternHash") == null || meta.get("patternHash").isJsonNull)
        assertFalse(json.contains(sha256Hex("1234")))
        assertFalse(json.contains(sha256Hex("0123")))
    }

    @Test
    fun `tampered ciphertext cannot be unlocked`() {
        val json = codec.createBackup(
            localSetting = LocalSetting(api = "https://sensitive.example"),
            options = BackupContentOptions(),
            protectionType = BACKUP_PROTECTION_PASSWORD,
            password = "1234",
        )
        val edited = JsonParser.parseString(json).asJsonObject
        val data = edited.getAsJsonObject("data")
        val cipher = data.get("ciphertext").asString
        // Flip one character in the middle of the base64 blob.
        val mid = cipher.length / 2
        val flipped = cipher.substring(0, mid) +
            (if (cipher[mid] == 'A') 'B' else 'A') +
            cipher.substring(mid + 1)
        data.addProperty("ciphertext", flipped)
        val parsed = codec.parseBackup(edited.toString()).getOrThrow()
        assertThrows(IllegalStateException::class.java) {
            codec.unlockBackup(parsed, password = "1234").getOrThrow()
        }
    }

    @Test
    fun `unknown version is rejected`() {
        val future = """{"meta":{"version":99,"protectionType":"none"},"data":{}}"""
        assertThrows(IllegalStateException::class.java) {
            codec.parseBackup(future).getOrThrow()
        }
    }

    @Test
    fun `structured credential encoding distinguishes separator ambiguity`() {
        // password "a|2:bc" must not derive the same key as password "a" + pattern "bc".
        val left = codec.createBackup(
            localSetting = LocalSetting(api = "https://left.example"),
            options = BackupContentOptions(),
            protectionType = BACKUP_PROTECTION_PASSWORD,
            password = "a|2:bc",
        )
        val right = codec.createBackup(
            localSetting = LocalSetting(api = "https://right.example"),
            options = BackupContentOptions(),
            protectionType = BACKUP_PROTECTION_BOTH,
            password = "a",
            pattern = "bc",
        )
        assertThrows(IllegalStateException::class.java) {
            codec.unlockBackup(codec.parseBackup(left).getOrThrow(), password = "a", pattern = "bc")
                .getOrThrow()
        }
        assertThrows(IllegalStateException::class.java) {
            codec.unlockBackup(codec.parseBackup(right).getOrThrow(), password = "a|2:bc")
                .getOrThrow()
        }
        assertEquals("https://left.example", unlockedSetting(left, password = "a|2:bc").api)
        assertEquals("https://right.example", unlockedSetting(right, password = "a", pattern = "bc").api)
    }

    @Test
    fun `legacy v3 file with hashes still unlocks and does not require new digests`() {
        // Simulate a v3 file written before the digest removal: rebuild meta with version 3
        // and legacy join encoding by temporarily creating with current codec then patching
        // is not enough (derivation changed). Construct via a v3-shaped unprotected shell is
        // covered elsewhere; here we assert verify* without hashes still routes to unlock.
        val json = codec.createBackup(
            localSetting = LocalSetting(api = "https://legacy.example"),
            options = BackupContentOptions(),
            protectionType = BACKUP_PROTECTION_PASSWORD,
            password = "1234",
        )
        val parsed = codec.parseBackup(json).getOrThrow()
        // No hash → any non-empty password is accepted by the lightweight gate.
        assertTrue(codec.verifyPassword(parsed, "9999"))
        assertFalse(codec.verifyPassword(parsed, ""))
        // Real gate still rejects the wrong password.
        assertThrows(IllegalStateException::class.java) {
            codec.unlockBackup(parsed, password = "9999").getOrThrow()
        }
        assertEquals("https://legacy.example", unlockedSetting(json, password = "1234").api)
    }

    @Test
    fun `null groups field is corrupted not empty success`() {
        val backup = codec.parseBackup(
            """{"meta":{"version":4,"includeComicCache":true},"data":{"comicCache":{"groups":null}}}"""
        ).getOrThrow()
        assertEquals(BackupSectionResult.Corrupted, codec.extractComicCache(backup))
    }

    @Test
    fun `null group elements and null chapters are corrupted`() {
        val nullElement = codec.parseBackup(
            """{"meta":{"version":4,"includeComicCache":true},"data":{"comicCache":{"groups":[null]}}}"""
        ).getOrThrow()
        assertEquals(BackupSectionResult.Corrupted, codec.extractComicCache(nullElement))

        val nullChapters = codec.parseBackup(
            """{"meta":{"version":4,"includeComicCache":true},"data":{"comicCache":{"groups":[{"id":1,"name":"n","authors":[],"tags":[],"chapters":null}]}}}"""
        ).getOrThrow()
        assertEquals(BackupSectionResult.Corrupted, codec.extractComicCache(nullChapters))
    }

    @Test
    fun `valid comic cache extracts successfully`() {
        val backup = codec.parseBackup(
            """{"meta":{"version":4,"includeComicCache":true},"data":{"comicCache":{"groups":[{"id":1,"name":"n","authors":["a"],"tags":["t"],"chapters":[{"id":2,"name":"c","sortOrder":1}]}]}}}"""
        ).getOrThrow()
        val result = codec.extractComicCache(backup)
        assertTrue(result is BackupSectionResult.Success)
        assertEquals(1, (result as BackupSectionResult.Success).value.groups.size)
        assertEquals(2, result.value.groups.single().chapters.single().id)
    }

    @Test
    fun `null settings list fields are corrupted`() {
        val backup = codec.parseBackup(
            """{"meta":{"version":4,"includeLocalSetting":true},"data":{"localSetting":{"blockedTagList":null}}}"""
        ).getOrThrow()
        assertEquals(BackupSectionResult.Corrupted, codec.extractLocalSetting(backup))
    }

    @Test
    fun `missing comic cache section is missing not corrupted`() {
        val json = codec.createBackup(
            localSetting = LocalSetting(),
            options = BackupContentOptions(includeLocalSetting = true, includeComicCache = false),
            protectionType = BACKUP_PROTECTION_NONE,
        )
        val parsed = codec.parseBackup(json).getOrThrow()
        assertEquals(BackupSectionResult.Missing, codec.extractComicCache(parsed))
    }

    @Test
    fun `v3 backup with legacy join encoding and fast digests still unlocks`() {
        val plainGson = GsonBuilder().disableHtmlEscaping().create()
        val inner = JsonObject()
        inner.add("localSetting", plainGson.toJsonTree(LocalSetting(api = "https://v3.example")))
        val salt = SecureRandom().generateSeed(16)
        // v1–v3 derivation: plain "|" join, not length-prefixed.
        val material = "1234|0123"
        val spec = PBEKeySpec(material.toCharArray(), salt, 120_000, 256)
        val key = SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
            "AES",
        )
        spec.clearPassword()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plainGson.toJson(inner).toByteArray(Charsets.UTF_8))
        val envelope = JsonObject().apply {
            addProperty(
                "ciphertext",
                Base64.getEncoder().encodeToString(cipher.iv + encrypted),
            )
        }
        val meta = JsonObject().apply {
            addProperty("version", 3)
            addProperty("protectionType", BACKUP_PROTECTION_BOTH)
            addProperty("passwordHash", sha256Hex("1234"))
            addProperty("patternHash", sha256Hex("0123"))
            addProperty("includeLocalSetting", true)
            addProperty("includeComicCache", false)
            addProperty("encryptionSalt", Base64.getEncoder().encodeToString(salt))
        }
        val file = JsonObject().apply {
            add("meta", meta)
            add("data", envelope)
        }
        val parsed = codec.parseBackup(file.toString()).getOrThrow()
        assertTrue(codec.verifyPassword(parsed, "1234"))
        assertTrue(codec.verifyPattern(parsed, "0123"))
        assertFalse(codec.verifyPassword(parsed, "9999"))
        val unlocked = codec.unlockBackup(parsed, password = "1234", pattern = "0123").getOrThrow()
        val setting = codec.extractLocalSetting(unlocked)
        assertTrue(setting is BackupSectionResult.Success)
        assertEquals("https://v3.example", (setting as BackupSectionResult.Success).value.api)
    }

    private fun sha256Hex(value: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
