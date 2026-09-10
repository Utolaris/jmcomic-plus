package com.par9uet.jm.storage

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class CryptoManagerTest {
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    private val crypto = CryptoManager { key }

    @Test fun encryptedRoundTripAndLegacyCiphertext() {
        val json = "{\"password\":\"secret\"}"
        val encrypted = crypto.encrypt(json)
        assertTrue(encrypted.startsWith("enc:"))
        assertFalse(encrypted.contains("secret"))
        assertEquals(json, crypto.decrypt(encrypted))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val legacy = Base64.getEncoder().encodeToString(cipher.doFinal(json.toByteArray()) + cipher.iv)
        assertEquals(json, crypto.decrypt(legacy))
    }

    @Test fun encryptionFailureDoesNotReturnPlaintextAndNextAttemptCanRecover() {
        var unavailable = true
        val subject = CryptoManager { if (unavailable) error("Keystore temporarily unavailable") else key }
        assertThrows(IllegalStateException::class.java) { subject.encrypt("secret") }
        unavailable = false
        assertEquals("secret", subject.decrypt(subject.encrypt("secret")))
    }

    @Test fun onlyExplicitLegacyPlainIsReadAndBadCiphertextNeverFallsBackToPlain() {
        val encoded = Base64.getEncoder().encodeToString("{\"password\":\"secret\"}".toByteArray())
        assertEquals("{\"password\":\"secret\"}", crypto.decrypt("plain:$encoded"))
        assertNull(crypto.decrypt(encoded))
        assertNull(crypto.decrypt("enc:$encoded"))
        val wrongKey = CryptoManager { SecretKeySpec(ByteArray(32) { 8 }, "AES") }
        assertNull(wrongKey.decrypt(crypto.encrypt("secret")))
    }
}
