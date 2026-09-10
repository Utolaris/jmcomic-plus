package com.par9uet.jm.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.GsonBuilder

class SecureStorage(
    context: Context,
    gson: Gson = GsonBuilder().create(),
    private val cryptoManager: CryptoManager = CryptoManager(),
) {
    private val gson = gson.newBuilder().registerTypeAdapter(okhttp3.Cookie::class.java, CookieTypeAdapter()).create()
    val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences("jm-mobile-g-data", Context.MODE_PRIVATE)
    }
    private val startupPreferences: SharedPreferences by lazy {
        context.getSharedPreferences("jm-mobile-startup", Context.MODE_PRIVATE)
    }

    fun <T> set(key: String, t: T) {
        val json = gson.toJson(t)
        writeEncrypted(sharedPreferences, key, json)
    }

    /** Stores small first-frame values separately from history and download metadata. */
    fun <T> setStartup(key: String, t: T) {
        val json = gson.toJson(t)
        setStartupString(key, json)
    }

    fun setStartupString(key: String, json: String) {
        writeEncrypted(startupPreferences, key, json)
    }

    fun <T> get(key: String, type: java.lang.reflect.Type): T? {
        return decode(getString(key), type)
    }

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

    fun getString(key: String): String? = readEncrypted(sharedPreferences, key)

    fun getStartupString(key: String): String? = readEncrypted(startupPreferences, key)

    private fun writeEncrypted(preferences: SharedPreferences, key: String, json: String) {
        // Encrypt before opening the editor. Failure preserves the last durable value and does
        // not invalidate the current in-memory identity during a temporary Keystore outage.
        val encrypted = runCatching { cryptoManager.encrypt(json) }.getOrNull() ?: return
        preferences.edit { putString(key, encrypted) }
    }

    private fun readEncrypted(preferences: SharedPreferences, key: String): String? {
        val stored = preferences.getString(key, null) ?: return null
        val json = cryptoManager.decrypt(stored) ?: return null
        if (stored.startsWith("plain:")) writeEncrypted(preferences, key, json)
        return json
    }

    fun <T> getStartup(key: String, type: java.lang.reflect.Type): T? {
        return decode(getStartupString(key), type)
    }

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
}
