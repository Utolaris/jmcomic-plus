package com.par9uet.jm.audit

import android.content.SharedPreferences
import android.graphics.Bitmap
import com.par9uet.jm.backup.*
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.download.RecordingDownloadDao
import com.par9uet.jm.download.atom.*
import com.par9uet.jm.download.molecule.DeviceDownloadContentOperations
import com.par9uet.jm.launcher.LauncherIdentityApplier
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.storage.*
import com.par9uet.jm.ui.viewModel.BackupRestoreViewModel
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Audit witnesses: assertions describe current defects, not desired regression behavior. */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentAuditReproductionTest {
    private val codec = BackupManager()
    private val launcher = object : LauncherIdentityApplier {
        override fun apply(disguise: LauncherDisguise) = true
    }

    @Test fun encryptedBackupExposesFastCredentialOracle() {
        val backup = codec.parseBackup(codec.createBackup(
            LocalSetting(theme = "dark"), options = BackupContentOptions(),
            protectionType = BACKUP_PROTECTION_BOTH, password = "1234", pattern = "0123",
        )).getOrThrow()
        fun hash(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        val candidates = listOf("9999", "1234", "0123")
        val password = candidates.single { hash(it) == backup.meta.passwordHash }
        val pattern = candidates.single { hash(it) == backup.meta.patternHash }
        assertEquals("dark", codec.extractLocalSetting(codec.unlockBackup(backup, password, pattern).getOrThrow())!!.theme)
    }

    @Test fun nullGroupsEscapeIntoDirectUiCallback() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val backup = codec.parseBackup("""{"meta":{"version":3,"includeComicCache":true},"data":{"comicCache":{"groups":null}}}""").getOrThrow()
            val operations = object : BackupRestoreOperations {
                override suspend fun loadComicCache() = ComicCacheBackup()
                override suspend fun write(uri: String, draft: BackupDraft) = Unit
                override suspend fun read(uri: String) = backup
                override suspend fun restore(backup: BackupFile, includeSettings: Boolean, groups: List<ComicGroupBackup>) = "unused"
            }
            val vm = BackupRestoreViewModel(operations, codec, ToastManager())
            vm.beginRestore()
            vm.readDocument("test")
            runCurrent()
            assertThrows(NullPointerException::class.java) {
                vm.selectRestoreContent(BackupContentOptions(false, true))
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun storageReportsSuccessWithoutAnyConfirmedDiskCommit() {
        var applyCalls = 0
        var commitCalls = 0
        val memory = InMemorySharedPreferences()
        val prefs = object : SharedPreferences by memory {
            override fun edit(): SharedPreferences.Editor {
                val editor = memory.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun apply() { applyCalls++ /* simulate failed disk write */ }
                    override fun commit(): Boolean { commitCalls++; return false }
                }
            }
        }
        val storage = SecureStorage(prefs, prefs, cryptoManager = CryptoManager {
            SecretKeySpec(ByteArray(32) { 7 }, "AES")
        })
        assertEquals(StorageWriteResult.Success, storage.setStartup("localSetting", LocalSetting(appLockEnabled = true)))
        assertEquals(1, applyCalls)
        assertEquals(0, commitCalls)
        assertEquals(StorageReadResult.Missing, storage.getStartupString("localSetting"))
    }

    @Test fun ordinaryPrivacySettingWriteFailureIsIgnored() {
        var durable = LocalSetting(showComicCacheNotificationName = true)
        val persistence = object : LocalSettingPersistence {
            override fun load() = LocalSettingLoadResult.Success(durable)
            override fun persist(localSetting: LocalSetting) = StorageWriteResult.TemporaryUnavailable
        }
        val manager = LocalSettingManager(persistence, launcher)
        manager.applyNotificationSetting(true, false)
        assertFalse(manager.cacheNotification.value.showName)
        assertTrue(LocalSettingManager(persistence, launcher).cacheNotification.value.showName)
    }

    @Test fun restoringSettingsCanReenableOnboardingWithExistingLock() {
        var durable = LocalSetting(onboardingCompleted = true, appLockEnabled = true, appLockPassword = "1234")
        val persistence = object : LocalSettingPersistence {
            override fun load() = LocalSettingLoadResult.Success(durable)
            override fun persist(localSetting: LocalSetting): StorageWriteResult {
                durable = localSetting
                return StorageWriteResult.Success
            }
        }
        val manager = LocalSettingManager(persistence, launcher)
        manager.applyLocalSetting(LocalSetting())
        val restarted = LocalSettingManager(persistence, launcher)
        assertTrue(restarted.appLock.value.enabled)
        assertFalse(restarted.onboardingCompleted.value)
        // App uses this exact predicate to choose onboarding before app-lock UI.
        val showOnboarding = !restarted.onboardingCompleted.value && !restarted.securityLoadBlocked.value
        assertTrue(showOnboarding)
        assertTrue(restarted.disableAndClearAppLock())
        assertFalse(restarted.appLock.value.enabled)
    }

    @Test fun concurrentChapterCompletionLeavesStaleGroupConfig() = runTest {
        val dao = RecordingDownloadDao()
        val tasks = (1..2).map { id -> DownloadComic(
            id = id, name = "chapter$id", authorList = emptyList(), coverPath = "", zipPath = "",
            progress = 1f, status = DownloadStatus.DOWNLOADING, createTime = id.toLong(), groupId = 100,
        ) }
        tasks.forEach { dao.tasks[it.id] = it }
        val rendezvous = CountDownLatch(2)
        val lastConfig = AtomicReference<List<DownloadComic>>()
        val storage = object : DownloadContentStorage {
            override fun chapterPath(task: DownloadComic) = "/chapter-${task.id}"
            override fun pageExists(chapterPath: String, index: Int) = true
            override fun writePage(chapterPath: String, index: Int, bitmap: Bitmap): Long = error("unused")
            override fun writeCover(task: DownloadComic, bitmap: Bitmap): String = error("unused")
            override fun writeConfig(current: DownloadComic, chapters: List<DownloadComic>) {
                rendezvous.countDown()
                check(rendezvous.await(5, TimeUnit.SECONDS)) { "writers did not overlap" }
                lastConfig.set(chapters)
            }
        }
        val repository = Proxy.newProxyInstance(ComicRepository::class.java.classLoader,
            arrayOf(ComicRepository::class.java)) { _, _, _ -> error("unused") } as ComicRepository
        val content = DeviceDownloadContentOperations(dao, repository, DownloadPageDecoder { error("unused") },
            CoverImageHostResolver(knownHosts = listOf("cdn.example")),
            DownloadCoverImages { _, _ -> error("unused") }, storage)
        tasks.map { async { content.complete(it) } }.awaitAll()
        assertEquals(2, dao.tasks.values.count { it.status == DownloadStatus.COMPLETE })
        assertEquals(1, lastConfig.get().count { it.status == DownloadStatus.COMPLETE })
        assertEquals(1, lastConfig.get().count { it.zipPath.isBlank() })
    }
}
