package com.par9uet.jm.audit

import com.par9uet.jm.backup.*
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.storage.*
import com.par9uet.jm.launcher.LauncherIdentityApplier
import com.par9uet.jm.download.RecordingDownloadDao
import com.par9uet.jm.download.atom.*
import com.par9uet.jm.download.molecule.DeviceDownloadContentOperations
import com.par9uet.jm.download.coordinator.*
import com.par9uet.jm.database.model.*
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.repository.ComicRepository
import com.google.gson.JsonParser
import android.graphics.Bitmap
import javax.crypto.spec.SecretKeySpec
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AuditReproductionTest {
    private val key = SecretKeySpec(ByteArray(32) { 3 }, "AES")
    private val launcher = object : LauncherIdentityApplier {
        override fun apply(disguise: com.par9uet.jm.data.models.LauncherDisguise) = true
    }
    @Test fun protectedBackupIsPlaintextAndMetadataBypassesVerification() {
        val codec = BackupManager()
        val json = codec.createBackup(LocalSetting(api = "https://sensitive.example"), options = BackupContentOptions(), protectionType = BACKUP_PROTECTION_BOTH, password = "1234", pattern = "0123")
        assertTrue(json.contains("https://sensitive.example"))
        val edited = JsonParser.parseString(json).asJsonObject
        edited.getAsJsonObject("meta").addProperty("protectionType", "none")
        val parsed = codec.parseBackup(edited.toString()).getOrThrow()
        assertFalse(codec.needsPassword(parsed))
        assertFalse(codec.needsPattern(parsed))
        assertEquals("https://sensitive.example", codec.extractLocalSetting(parsed)!!.api)
    }
    @Test fun temporaryReadFailureDisablesLockAndDoesNotRetryInManager() {
        val data = InMemorySharedPreferences()
        val startup = InMemorySharedPreferences()
        var unavailable = false
        val secure = SecureStorage(data, startup, cryptoManager = CryptoManager { if (unavailable) error("temporary outage") else key })
        secure.setStartup("localSetting", LocalSetting(appLockEnabled = true, appLockPassword = "1234", onboardingCompleted = true))
        unavailable = true
        val manager = LocalSettingManager(LocalSettingStorage(secure), launcher)
        assertFalse(manager.appLock.value.enabled)
        unavailable = false
        assertFalse(manager.appLock.value.enabled)
        assertTrue(LocalSettingManager(LocalSettingStorage(secure), launcher).appLock.value.enabled)
    }
    @Test fun failedLockWriteLooksEnabledButRestartsDisabled() {
        val data = InMemorySharedPreferences()
        val startup = InMemorySharedPreferences()
        var unavailable = false
        val secure = SecureStorage(data, startup, cryptoManager = CryptoManager { if (unavailable) error("temporary outage") else key })
        secure.setStartup("localSetting", LocalSetting(onboardingCompleted = true))
        val manager = LocalSettingManager(LocalSettingStorage(secure), launcher)
        unavailable = true
        manager.setPassword("1234", 4)
        manager.setAppLockEnabled(true)
        assertTrue(manager.appLock.value.enabled)
        unavailable = false
        assertFalse(LocalSettingManager(LocalSettingStorage(secure), launcher).appLock.value.enabled)
    }
    @Test fun malformedCacheSectionThrowsOutsideParsingResult() {
        val codec = BackupManager()
        val backup = codec.parseBackup("""{"meta":{"version":3,"includeComicCache":true},"data":{"comicCache":[]}}""").getOrThrow()
        assertThrows(ClassCastException::class.java) { codec.extractComicCache(backup) }
    }
    @Test fun configFailureLeavesCompleteAndRetrySkipsWritingConfig() = runTest {
        val dao = RecordingDownloadDao()
        val task = DownloadComic(id=1, name="test", authorList=emptyList(), coverPath="", zipPath="", progress=1f, status=DownloadStatus.DOWNLOADING, createTime=1, groupId=1)
        dao.tasks[1] = task
        val repository = Proxy.newProxyInstance(ComicRepository::class.java.classLoader, arrayOf(ComicRepository::class.java)) { _, _, _ -> error("unused") } as ComicRepository
        var configWrites = 0
        val storage = object : DownloadContentStorage {
            override fun chapterPath(task: DownloadComic) = "/chapter"
            override fun pageExists(chapterPath: String, index: Int) = true
            override fun writePage(chapterPath: String, index: Int, bitmap: Bitmap): Long = error("unused")
            override fun writeCover(task: DownloadComic, bitmap: Bitmap): String = error("unused")
            override fun writeConfig(current: DownloadComic, chapters: List<DownloadComic>) { configWrites++; error("disk full") }
        }
        val content = DeviceDownloadContentOperations(dao, repository, DownloadPageDecoder { error("unused") }, CoverImageHostResolver(knownHosts=listOf("cdn.example")), DownloadCoverImages { _, _ -> error("unused") }, storage)
        try { content.complete(task); fail("must throw") } catch (_: IllegalStateException) { }
        assertEquals(DownloadStatus.COMPLETE, dao.tasks[1]!!.status)
        val preferences = object : RemoteConfigPreferences { override val remoteImageHost = MutableStateFlow("cdn.example") }
        val feedback = object : DownloadFeedback {
            override fun start(groupId: Int) = Unit
            override fun stop(groupId: Int) = Unit
            override fun showProgress(downloadTask: DownloadComic, progress: Float) = Unit
            override fun cancel(groupId: Int) = Unit
            override fun report(batchId: String, batchTotal: Int, comicId: Int, success: Boolean) = Unit
        }
        assertEquals(DownloadOutcome.SUCCESS, DownloadComicCoordinator(dao, preferences, content, feedback).download(1, "", 1, 1))
        assertEquals(1, configWrites)
    }
    @Test fun completionHandlerCannotUnblockRunningBlockingRead() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val handlerCalled = AtomicBoolean(false)
        val job = launch(Dispatchers.IO) {
            currentCoroutineContext().job.invokeOnCompletion { handlerCalled.set(true) }
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            job.cancel()
            delay(100)
            assertFalse(handlerCalled.get())
            assertFalse(job.isCompleted)
        } finally { release.countDown(); job.join() }
        assertTrue(handlerCalled.get())
    }

    @Test fun malformedWireItemEscapesRepositoryAndPagingErrorContract() = runTest {
        val category = com.par9uet.jm.retrofit.model.ComicListResponse.ContentListItem.Category(null, null)
        val item = com.par9uet.jm.retrofit.model.ComicListResponse.ContentListItem("", "", null, "broken", "", category, category, false, 0)
        val response = com.par9uet.jm.core.network.NetWorkResult.Success(com.par9uet.jm.retrofit.model.ComicListResponse("", "1", null, listOf(item)))
        val source = Proxy.newProxyInstance(com.par9uet.jm.data.comic.ComicEmbeddedDataSource::class.java.classLoader, arrayOf(com.par9uet.jm.data.comic.ComicEmbeddedDataSource::class.java)) { _, _, _ -> response } as com.par9uet.jm.data.comic.ComicEmbeddedDataSource
        val network = object : com.par9uet.jm.data.comic.NetworkHomeDataSource {
            override suspend fun getHomePage(): com.par9uet.jm.core.network.NetWorkResult<List<com.par9uet.jm.retrofit.model.HomeSwiperComicListItemResponse>> = error("unused")
        }
        val repository = com.par9uet.jm.repository.impl.ComicRepositoryImpl(network, source)
        val paging = com.par9uet.jm.ui.pagingSource.SearchComicPagingSource(repository, com.par9uet.jm.ui.pagingSource.SearchComicFilter())
        try {
            paging.load(androidx.paging.PagingSource.LoadParams.Refresh(null, 20, false))
            fail("expected NumberFormatException instead of LoadResult.Error")
        } catch (_: NumberFormatException) { }
    }
}
