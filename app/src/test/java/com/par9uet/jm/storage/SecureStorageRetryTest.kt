package com.par9uet.jm.storage

import android.content.SharedPreferences
import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Minimal in-memory [SharedPreferences] so storage behavior is unit-testable without Robolectric. */
internal class InMemorySharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        (values[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            pending[key.orEmpty()] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            pending[key.orEmpty()] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            pending[key.orEmpty()] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            pending[key.orEmpty()] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            pending[key.orEmpty()] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            pending[key.orEmpty()] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            pending[key.orEmpty()] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            if (clear) values.clear()
            pending.forEach { (k, v) ->
                if (v == null) values.remove(k) else values[k] = v
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit
}

class SecureStorageRetryTest {
    private val key = SecretKeySpec(ByteArray(32) { 3 }, "AES")
    private val prefs = InMemorySharedPreferences()
    private val startupPrefs = InMemorySharedPreferences()

    private fun storage(
        keyProvider: () -> SecretKey = { key },
    ) = SecureStorage(
        sharedPreferences = prefs,
        startupPreferences = startupPrefs,
        cryptoManager = CryptoManager(keyProvider),
    )

    @Test
    fun temporaryDecryptFailureDoesNotCacheAndNextReadSucceeds() {
        var unavailable = false
        val crypto = CryptoManager { if (unavailable) error("keystore down") else key }
        val durable = SecureStorage(prefs, startupPrefs, cryptoManager = crypto)
        // Seed durable ciphertext while the key is available.
        assertTrue(durable.set("cookie", listOf("avs")) is StorageWriteResult.Success)

        unavailable = true
        val first = durable.get<List<String>>(
            "cookie",
            object : com.google.gson.reflect.TypeToken<List<String>>() {}.type,
        )
        assertTrue(first is StorageReadResult.TemporaryUnavailable)

        unavailable = false
        val second = durable.get<List<String>>(
            "cookie",
            object : com.google.gson.reflect.TypeToken<List<String>>() {}.type,
        )
        assertTrue(second is StorageReadResult.Success)
        assertEquals(listOf("avs"), (second as StorageReadResult.Success).value)
    }

    @Test
    fun writeFailureKeepsPreviousDurableValueAcrossRestart() {
        val first = storage()
        assertTrue(first.set("user", "alice") is StorageWriteResult.Success)

        var unavailable = true
        val failing = SecureStorage(
            prefs,
            startupPrefs,
            cryptoManager = CryptoManager { if (unavailable) error("keystore down") else key },
        )
        assertTrue(failing.set("user", "bob") is StorageWriteResult.TemporaryUnavailable)

        unavailable = false
        val restarted = storage()
        val result = restarted.get<String>(
            "user",
            object : com.google.gson.reflect.TypeToken<String>() {}.type,
        )
        assertTrue(result is StorageReadResult.Success)
        assertEquals("alice", (result as StorageReadResult.Success).value)
    }

    @Test
    fun missingKeyIsMissingNotTemporary() {
        val result = storage().get<String>(
            "absent",
            object : com.google.gson.reflect.TypeToken<String>() {}.type,
        )
        assertTrue(result is StorageReadResult.Missing)
    }

    @Test
    fun legacyPlainStringIsStillReadableAndMigrated() {
        val json = "\"legacy\""
        prefs.edit().putString(
            "plainKey",
            "plain:" + Base64.getEncoder().encodeToString(json.toByteArray()),
        ).commit()
        val storage = storage()
        val result = storage.getString("plainKey")
        assertTrue(result is StorageReadResult.Success)
        assertEquals(json, (result as StorageReadResult.Success).value)
        // Migration rewrote the durable entry to enc: form.
        assertTrue(prefs.getString("plainKey", null)!!.startsWith("enc:"))
    }

    @Test
    fun corruptedCiphertextIsCorruptedNotMissing() {
        prefs.edit().putString("badKey", "enc:not-valid-base64!!!").commit()
        assertTrue(storage().getString("badKey") is StorageReadResult.Corrupted)
    }

    @Test
    fun commitFailureIsTemporaryUnavailableNotSuccess() {
        var commitSucceeds = true
        val failingPrefs = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        // Must return the wrapper so the overridden commit() is the one called.
                        return this
                    }

                    override fun commit(): Boolean {
                        // Simulate a disk write that cannot be confirmed.
                        return if (commitSucceeds) editor.commit() else false
                    }
                }
            }
        }
        val durable = SecureStorage(
            failingPrefs,
            failingPrefs,
            cryptoManager = CryptoManager { key },
        )
        commitSucceeds = false
        assertTrue(durable.setStartup("localSetting", "secret") is StorageWriteResult.TemporaryUnavailable)
        assertTrue(durable.getStartupString("localSetting") is StorageReadResult.Missing)

        commitSucceeds = true
        assertTrue(durable.setStartup("localSetting", "secret") is StorageWriteResult.Success)
        val result = durable.getStartupString("localSetting")
        assertTrue(result is StorageReadResult.Success)
        assertEquals("\"secret\"", (result as StorageReadResult.Success).value)
    }

    @Test
    fun encryptionFailureKeepsPreviousDurableValue() {
        val first = storage()
        assertTrue(first.set("user", "alice") is StorageWriteResult.Success)

        val failing = SecureStorage(
            prefs,
            startupPrefs,
            cryptoManager = CryptoManager { error("keystore down") },
        )
        assertTrue(failing.set("user", "bob") is StorageWriteResult.TemporaryUnavailable)

        val restarted = storage()
        val result = restarted.get<String>(
            "user",
            object : com.google.gson.reflect.TypeToken<String>() {}.type,
        )
        assertEquals("alice", (result as StorageReadResult.Success).value)
    }
}
