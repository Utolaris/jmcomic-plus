package com.par9uet.jm.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Distinguishes recoverable Keystore outages from unreadable payloads. */
sealed class DecryptResult {
    data class Success(val value: String) : DecryptResult()
    data object TemporaryUnavailable : DecryptResult()
    data object Corrupted : DecryptResult()
}

class CryptoManager internal constructor(private val keyProvider: () -> SecretKey) {
    constructor() : this(AndroidStorageKey::get)

    companion object {
        private const val ENCRYPTED_PREFIX = "enc:"
        private const val PLAIN_PREFIX = "plain:"
        private const val GCM_IV_SIZE_BYTES = 12
    }

    /** Failure propagates to storage, which keeps the previous value instead of writing plain. */
    fun encrypt(data: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val encrypted = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return ENCRYPTED_PREFIX + Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    fun decrypt(data: String): DecryptResult = when {
        // Read-only migration of explicitly marked legacy fallback data. Never write this form.
        data.startsWith(PLAIN_PREFIX) -> {
            val body = data.removePrefix(PLAIN_PREFIX)
            runCatching {
                DecryptResult.Success(
                    String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8),
                )
            }.getOrElse { DecryptResult.Corrupted }
        }
        data.startsWith(ENCRYPTED_PREFIX) -> decryptWithKeyStore(data.removePrefix(ENCRYPTED_PREFIX), true)
        // Original encrypted format put the IV at the end and had no prefix. A decrypt failure
        // must never reinterpret ciphertext as plaintext (e.g. when the key is unavailable).
        else -> decryptWithKeyStore(data, false)
    }

    private fun decryptWithKeyStore(value: String, ivAtStart: Boolean): DecryptResult {
        val data = try {
            Base64.getDecoder().decode(value)
        } catch (_: Exception) {
            return DecryptResult.Corrupted
        }
        if (data.size <= GCM_IV_SIZE_BYTES) return DecryptResult.Corrupted
        val iv = if (ivAtStart) data.copyOfRange(0, GCM_IV_SIZE_BYTES)
            else data.copyOfRange(data.size - GCM_IV_SIZE_BYTES, data.size)
        val encrypted = if (ivAtStart) data.copyOfRange(GCM_IV_SIZE_BYTES, data.size)
            else data.copyOfRange(0, data.size - GCM_IV_SIZE_BYTES)
        val key = try {
            keyProvider()
        } catch (_: Exception) {
            return DecryptResult.TemporaryUnavailable
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            DecryptResult.Success(String(cipher.doFinal(encrypted), StandardCharsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            // Wrong key or tampered ciphertext — will not heal by retrying the same blob.
            DecryptResult.Corrupted
        } catch (_: Exception) {
            DecryptResult.TemporaryUnavailable
        }
    }
}

private object AndroidStorageKey {
    private const val ALIAS = "app_master_key"
    // Failed initialization is retried; a transient Keystore failure is not cached as null.
    private val store by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    @Synchronized
    fun get(): SecretKey {
        val entry = store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (entry != null) return entry.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build())
        return generator.generateKey()
    }
}
