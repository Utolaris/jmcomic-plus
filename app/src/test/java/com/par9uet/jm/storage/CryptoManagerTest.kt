package com.par9uet.jm.storage

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoManagerTest {
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")
    private val crypto = CryptoManager { key }

    @Test fun encryptedRoundTripAndLegacyCiphertext() {
        val json = "{\"password\":\"secret\"}"
        val encrypted = crypto.encrypt(json)
        assertTrue(encrypted.startsWith("enc:"))
        assertFalse(encrypted.contains("secret"))
        assertEquals(json, (crypto.decrypt(encrypted) as DecryptResult.Success).value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val legacy = Base64.getEncoder().encodeToString(cipher.doFinal(json.toByteArray()) + cipher.iv)
        assertEquals(json, (crypto.decrypt(legacy) as DecryptResult.Success).value)
    }

    @Test fun encryptionFailureDoesNotReturnPlaintextAndNextAttemptCanRecover() {
        var unavailable = true
        val subject = CryptoManager { if (unavailable) error("Keystore temporarily unavailable") else key }
        try {
            subject.encrypt("secret")
            throw AssertionError("expected encrypt to fail while keystore is unavailable")
        } catch (_: IllegalStateException) {
            // expected
        }
        unavailable = false
        assertEquals("secret", (subject.decrypt(subject.encrypt("secret")) as DecryptResult.Success).value)
    }

    @Test fun decryptDistinguishesTemporaryKeystoreFailureFromCorruption() {
        val encrypted = crypto.encrypt("secret")
        val unavailable = CryptoManager { error("keystore down") }
        assertTrue(unavailable.decrypt(encrypted) is DecryptResult.TemporaryUnavailable)
        val wrongKey = CryptoManager { SecretKeySpec(ByteArray(32) { 8 }, "AES") }
        assertTrue(wrongKey.decrypt(encrypted) is DecryptResult.Corrupted)
        assertTrue(crypto.decrypt("enc:not-base64!!!") is DecryptResult.Corrupted)
    }

    @Test fun onlyExplicitLegacyPlainIsReadAndBadCiphertextNeverFallsBackToPlain() {
        val encoded = Base64.getEncoder().encodeToString("{\"password\":\"secret\"}".toByteArray())
        assertEquals(
            "{\"password\":\"secret\"}",
            (crypto.decrypt("plain:$encoded") as DecryptResult.Success).value,
        )
        assertTrue(crypto.decrypt(encoded) is DecryptResult.Corrupted)
        assertTrue(crypto.decrypt("enc:$encoded") is DecryptResult.Corrupted)
        val wrongKey = CryptoManager { SecretKeySpec(ByteArray(32) { 8 }, "AES") }
        assertTrue(wrongKey.decrypt(crypto.encrypt("secret")) is DecryptResult.Corrupted)
    }
}
