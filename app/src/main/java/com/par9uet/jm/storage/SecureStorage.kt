package com.par9uet.jm.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/** Distinguishes “no value” from “value exists but cannot be read right now”. */
sealed class StorageReadResult<out T> {
    data class Success<T>(val value: T) : StorageReadResult<T>()
    data object Missing : StorageReadResult<Nothing>()
    data object TemporaryUnavailable : StorageReadResult<Nothing>()
    data object Corrupted : StorageReadResult<Nothing>()
}

sealed class StorageWriteResult {
    data object Success : StorageWriteResult()
    data object TemporaryUnavailable : StorageWriteResult()
}

class SecureStorage(
    private val sharedPreferences: SharedPreferences,
    private val startupPreferences: SharedPreferences,
    gson: Gson = GsonBuilder().create(),
    private val cryptoManager: CryptoManager = CryptoManager(),
) {
    constructor(
        context: Context,
        gson: Gson = GsonBuilder().create(),
        cryptoManager: CryptoManager = CryptoManager(),
    ) : this(
        sharedPreferences = context.getSharedPreferences(DATA_PREFERENCES_NAME, Context.MODE_PRIVATE),
        startupPreferences = context.getSharedPreferences(STARTUP_PREFERENCES_NAME, Context.MODE_PRIVATE),
        gson = gson,
        cryptoManager = cryptoManager,
    )

    private val gson = gson.newBuilder().registerTypeAdapter(okhttp3.Cookie::class.java, CookieTypeAdapter()).create()

    fun <T> set(key: String, t: T): StorageWriteResult {
        val json = gson.toJson(t)
        return writeEncrypted(sharedPreferences, key, json)
    }

    /** Stores small first-frame values separately from history and download metadata. */
    fun <T> setStartup(key: String, t: T): StorageWriteResult {
        val json = gson.toJson(t)
        return setStartupString(key, json)
    }

    fun setStartupString(key: String, json: String): StorageWriteResult =
        writeEncrypted(startupPreferences, key, json)

    fun <T> get(key: String, type: java.lang.reflect.Type): StorageReadResult<T> =
        decodeResult(getString(key), type)

    /**
     * Decodes an already decrypted JSON value. This avoids reading and decrypting the same
     * SharedPreferences entry twice when a caller also needs to inspect the raw JSON.
     */
    fun <T> decode(json: String?, type: java.lang.reflect.Type): T? {
        return try {
            json?.let { gson.fromJson(it, type) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun <T> decodeResult(json: StorageReadResult<String>, type: java.lang.reflect.Type): StorageReadResult<T> {
        return when (json) {
            is StorageReadResult.Missing -> StorageReadResult.Missing
            is StorageReadResult.TemporaryUnavailable -> StorageReadResult.TemporaryUnavailable
            is StorageReadResult.Corrupted -> StorageReadResult.Corrupted
            is StorageReadResult.Success -> {
                try {
                    val value = gson.fromJson<T>(json.value, type)
                    if (value == null) StorageReadResult.Corrupted
                    else StorageReadResult.Success(value)
                } catch (_: Exception) {
                    StorageReadResult.Corrupted
                }
            }
        }
    }

    fun getString(key: String): StorageReadResult<String> = readEncrypted(sharedPreferences, key)

    fun getStartupString(key: String): StorageReadResult<String> = readEncrypted(startupPreferences, key)

    private fun writeEncrypted(preferences: SharedPreferences, key: String, json: String): StorageWriteResult {
        // Encrypt before opening the editor. Failure preserves the last durable value and does
        // not invalidate the current in-memory identity during a temporary Keystore outage.
        val encrypted = try {
            cryptoManager.encrypt(json)
        } catch (_: Exception) {
            return StorageWriteResult.TemporaryUnavailable
        }
        preferences.edit { putString(key, encrypted) }
        return StorageWriteResult.Success
    }

    private fun readEncrypted(preferences: SharedPreferences, key: String): StorageReadResult<String> {
        val stored = preferences.getString(key, null) ?: return StorageReadResult.Missing
        return when (val decrypted = cryptoManager.decrypt(stored)) {
            is DecryptResult.Success -> {
                if (stored.startsWith("plain:")) writeEncrypted(preferences, key, decrypted.value)
                StorageReadResult.Success(decrypted.value)
            }
            is DecryptResult.TemporaryUnavailable -> StorageReadResult.TemporaryUnavailable
            is DecryptResult.Corrupted -> StorageReadResult.Corrupted
        }
    }

    fun <T> getStartup(key: String, type: java.lang.reflect.Type): StorageReadResult<T> =
        decodeResult(getStartupString(key), type)

    fun remove(key: String) {
        sharedPreferences.edit {
            remove(key)
        }
    }

    fun removeStartup(key: String) {
        startupPreferences.edit {
            remove(key)
        }
    }

    companion object {
        /** Names of the files that hold encoded values, for callers that inspect stored ciphertext. */
        const val DATA_PREFERENCES_NAME = "jm-mobile-g-data"
        const val STARTUP_PREFERENCES_NAME = "jm-mobile-startup"
    }
}
