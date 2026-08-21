package com.par9uet.jm.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.GsonBuilder

class SecureStorage(
    context: Context,
    private val gson: Gson = GsonBuilder().create()
) {
    private val cryptoManager = CryptoManager()
    val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences("jm-mobile-g-data", Context.MODE_PRIVATE)
    }
    private val startupPreferences: SharedPreferences by lazy {
        context.getSharedPreferences("jm-mobile-startup", Context.MODE_PRIVATE)
    }

    fun <T> set(key: String, t: T) {
        val json = gson.toJson(t)
        sharedPreferences.edit {
            putString(key, cryptoManager.encrypt(json))
        }
    }

    /** Stores small first-frame values separately from history/AI/download metadata. */
    fun <T> setStartup(key: String, t: T) {
        val json = gson.toJson(t)
        setStartupString(key, json)
    }

    fun setStartupString(key: String, json: String) {
        startupPreferences.edit {
            putString(key, cryptoManager.encrypt(json))
        }
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

    fun getString(key: String): String? {
        val json = sharedPreferences.getString(key, null)
        return try {
            json?.let {
                cryptoManager.decrypt(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getStartupString(key: String): String? {
        val json = startupPreferences.getString(key, null)
        return try {
            json?.let { cryptoManager.decrypt(it) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
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
