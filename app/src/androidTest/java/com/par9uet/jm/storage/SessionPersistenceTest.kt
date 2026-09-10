package com.par9uet.jm.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.par9uet.jm.store.ReadHistoryManager
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SessionPersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun temporaryEncryptionFailurePreservesExistingCiphertextAndNeverWritesPlain() {
        val storage = SecureStorage(context)
        val key = "encryption-failure-test"
        storage.set(key, "previous")
        val ciphertext = storage.sharedPreferences.getString(key, null)
        assertTrue(ciphertext!!.startsWith("enc:"))
        try {
            val unavailable = SecureStorage(context, cryptoManager = CryptoManager { error("Keystore unavailable") })
            unavailable.set(key, "replacement-secret")
            unavailable.set("$key-new", "new-secret")
            assertEquals(ciphertext, storage.sharedPreferences.getString(key, null))
            assertFalse(storage.sharedPreferences.contains("$key-new"))
            assertEquals("previous", storage.get<String>(key, String::class.java))
        } finally {
            storage.remove(key)
            storage.remove("$key-new")
        }
    }

    @Test fun legacyCookiesMigrateToEncryptionAndSurviveStorageReconstruction() {
        val storage = SecureStorage(context)
        val cookies = listOf(Cookie.Builder().name("AVS").value("legacy-test").hostOnlyDomain("api.example").secure().httpOnly().build())
        val plain = "plain:" + Base64.getEncoder().encodeToString(Gson().toJson(cookies).toByteArray())
        storage.sharedPreferences.edit().putString("cookie", plain).commit()
        try {
            assertEquals(cookies, SecureCookieStorage(storage).get())
            assertTrue(storage.sharedPreferences.getString("cookie", "")!!.startsWith("enc:"))
            assertEquals(cookies, SecureCookieStorage(SecureStorage(context)).get())
            SecureCookieStorage(storage).remove()
            assertTrue(SecureCookieStorage(SecureStorage(context)).get().isEmpty())
        } finally { storage.remove("cookie") }
    }

    @Test fun readProgressSurvivesReconstructionAndLateStartupLoad() = runBlocking {
        val historyStorage = ReadHistoryStorage(SecureStorage(context))
        historyStorage.remove()
        try {
            val first = ReadHistoryManager(historyStorage)
            first.saveReadProgress(100, 101, 12, 20)
            val restored = ReadHistoryManager(ReadHistoryStorage(SecureStorage(context)))
            // The restored reader may arrive before PostStartupCoordinator.load().
            restored.markRead(100, 101)
            assertEquals(12, restored.lastReadPageIndex(100, 101))
            restored.saveReadProgress(100, 101, 15, 20)
            restored.load()
            assertEquals(15, restored.lastReadPageIndex(100, 101))
            restored.markRead(100, 102)
            assertEquals(0, restored.lastReadPageIndex(100, 102))
        } finally { historyStorage.remove() }
    }
}
