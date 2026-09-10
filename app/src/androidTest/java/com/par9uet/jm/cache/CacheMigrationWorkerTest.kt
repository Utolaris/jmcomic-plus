package com.par9uet.jm.cache

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.content.Intent
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.impl.utils.taskexecutor.SerialExecutor
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.par9uet.jm.database.AppDatabase
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.download.atom.DownloadContentFiles
import com.par9uet.jm.download.coordinator.DownloadComicCoordinator
import com.par9uet.jm.download.coordinator.DownloadFeedback
import com.par9uet.jm.download.molecule.DownloadContentOperations
import com.par9uet.jm.store.RemoteConfigPreferences
import com.par9uet.jm.worker.CACHE_MIGRATION_TARGET_URI
import com.par9uet.jm.worker.CacheMigrationWorker
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CacheMigrationWorkerTest {
    private lateinit var base: Context
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var token: String

    @Before
    fun setUp() {
        base = InstrumentationRegistry.getInstrumentation().targetContext
        token = "migration-${UUID.randomUUID()}"
        context = object : ContextWrapper(base) {
            override fun getCacheDir() = File(base.cacheDir, token).also { it.mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("$token-$name", mode)
        }
        database = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        grantProviderAccess()
    }

    @After
    fun tearDown() {
        database.close()
        context.cacheDir.deleteRecursively()
        base.deleteSharedPreferences("$token-download_storage")
    }

    @Test
    fun `file cache migrates to saf only after all data is copied`() = runBlocking {
        setDownloadTreeUri(context, "")
        val sourceRoot = File(getDownloadDir(context), "JM4201").apply { mkdirs() }
        val sourceChapter = File(sourceRoot, "chapter-4201").apply { mkdirs() }
        val sourceCover = File(sourceRoot, "cover.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val sourcePage = File(sourceChapter, "0.webp").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val original = DownloadComic(
            id = 4201,
            name = "迁移测试",
            authorList = emptyList(),
            coverPath = sourceCover.absolutePath,
            zipPath = sourceChapter.absolutePath,
            progress = 1f,
            status = DownloadStatus.COMPLETE,
            createTime = 1L,
            groupId = 4201,
            groupName = "迁移测试",
        )
        database.downloadComicDao().insert(original)
        val targetTree = createTreeDirectory("target-${UUID.randomUUID()}")

        val result = createWorker(targetTree.toString()).doWork()

        if (result !is androidx.work.ListenableWorker.Result.Success) {
            throw AssertionError("migration failed: ${result.outputData.keyValueMap}")
        }
        val migrated = database.downloadComicDao().getById(original.id)!!
        assertTrue(isDocumentCachePath(migrated.coverPath))
        assertTrue(isDocumentCachePath(migrated.zipPath))
        assertEquals(targetTree.toString(), getDownloadTreeUri(context)?.toString())
        assertTrue(cachePathHasContent(context, migrated.coverPath))
        assertTrue(listComicImagePaths(context, migrated.zipPath).isNotEmpty())
        assertTrue(DeviceLocalChapterFilesForTest.images(context, migrated).isNotEmpty())
        assertFalse(sourceCover.exists())
        assertFalse(sourcePage.exists())
    }

    @Test
    fun `unreadable persisted source fails without changing db or active tree`() = runBlocking {
        setDownloadTreeUri(context, "")
        val missing = File(context.cacheDir, "missing/chapter")
        val original = DownloadComic(
            id = 4202,
            name = "不可读测试",
            authorList = emptyList(),
            coverPath = "",
            zipPath = missing.absolutePath,
            progress = 1f,
            status = DownloadStatus.COMPLETE,
            createTime = 1L,
        )
        database.downloadComicDao().insert(original)
        val targetTree = createTreeDirectory("failed-${UUID.randomUUID()}")

        val result = createWorker(targetTree.toString()).doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Failure)
        assertEquals(original, database.downloadComicDao().getById(original.id))
        assertEquals(null, getDownloadTreeUri(context))
        assertFalse(cachePathExists(context, targetTree.toString() + "/JM4202"))
        assertFalse(missing.exists())
    }

    @Test
    fun `saf cache migrates to default file cache`() = runBlocking {
        val fixture = createSafComic(4203)
        database.downloadComicDao().insert(fixture.comic)

        val result = createWorker("").doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        val migrated = database.downloadComicDao().getById(fixture.comic.id)!!
        assertFalse(isDocumentCachePath(migrated.coverPath))
        assertFalse(isDocumentCachePath(migrated.zipPath))
        assertEquals(null, getDownloadTreeUri(context))
        assertTrue(File(migrated.coverPath).isFile)
        assertTrue(File(migrated.coverPath).length() > 0L)
        assertEquals(listOf("0.webp"), File(migrated.zipPath).list()?.toList())
        assertTrue(DeviceLocalChapterFilesForTest.images(context, migrated).isNotEmpty())
        assertFalse(cachePathExists(context, fixture.comic.coverPath))
        assertFalse(cachePathExists(context, fixture.comic.zipPath))
    }

    @Test
    fun `saf cache migrates between distinct saf trees`() = runBlocking {
        val fixture = createSafComic(4204)
        database.downloadComicDao().insert(fixture.comic)
        val targetTree = createTreeDirectory("target-${UUID.randomUUID()}")

        val result = createWorker(targetTree.toString()).doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        val migrated = database.downloadComicDao().getById(fixture.comic.id)!!
        assertTrue(isDocumentCachePath(migrated.coverPath))
        assertTrue(isDocumentCachePath(migrated.zipPath))
        assertEquals(targetTree.toString(), getDownloadTreeUri(context)?.toString())
        assertTrue(cachePathHasContent(context, migrated.coverPath))
        assertEquals(listOf("0.webp"), listComicImageEntries(context, migrated.zipPath).map { it.name })
        assertTrue(DeviceLocalChapterFilesForTest.images(context, migrated).isNotEmpty())
        assertFalse(cachePathExists(context, fixture.comic.coverPath))
        assertFalse(cachePathExists(context, fixture.comic.zipPath))
    }

    @Test
    fun `retry removes stale target pages before copying`() = runBlocking {
        setDownloadTreeUri(context, "")
        val sourceRoot = File(getDownloadDir(context), "JM4205").apply { mkdirs() }
        val sourceChapter = File(sourceRoot, "chapter-4205").apply { mkdirs() }
        val sourceCover = File(sourceRoot, "cover.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        File(sourceChapter, "0.webp").writeBytes(byteArrayOf(4, 5, 6))
        val original = DownloadComic(
            id = 4205,
            name = "残留清理测试",
            authorList = emptyList(),
            coverPath = sourceCover.absolutePath,
            zipPath = sourceChapter.absolutePath,
            progress = 1f,
            status = DownloadStatus.COMPLETE,
            createTime = 1L,
        )
        database.downloadComicDao().insert(original)
        val targetTree = createTreeDirectory("target-${UUID.randomUUID()}")
        val targetRoot = DocumentsContract.buildDocumentUriUsingTree(
            targetTree,
            DocumentsContract.getTreeDocumentId(targetTree),
        )
        val targetComic = requireNotNull(findOrCreateCacheDocument(
            context,
            targetRoot,
            "JM4205",
            DocumentsContract.Document.MIME_TYPE_DIR,
        ))
        val targetChapter = requireNotNull(findOrCreateCacheDocument(
            context,
            targetComic,
            "chapter-4205",
            DocumentsContract.Document.MIME_TYPE_DIR,
        ))
        val stalePage = requireNotNull(findOrCreateCacheDocument(
            context,
            targetChapter,
            "999.webp",
            "image/webp",
        ))
        openCacheOutputStream(context, stalePage.toString()).use { it.write(byteArrayOf(9, 9, 9)) }

        val result = createWorker(targetTree.toString()).doWork()

        assertTrue(result is androidx.work.ListenableWorker.Result.Success)
        val migrated = database.downloadComicDao().getById(original.id)!!
        assertEquals(listOf("0.webp"), listComicImageEntries(context, migrated.zipPath).map { it.name })
    }

    private fun createWorker(targetTree: String): CacheMigrationWorker {
        val params = WorkerParameters(
            UUID.randomUUID(),
            Data.Builder().putString(CACHE_MIGRATION_TARGET_URI, targetTree).build(),
            emptyList(),
            WorkerParameters.RuntimeExtras(),
            0,
            0,
            Executor { it.run() },
            Dispatchers.Default,
            ImmediateTaskExecutor,
            object : WorkerFactory() {
                override fun createWorker(context: Context, workerClassName: String, workerParameters: WorkerParameters) = null
            },
            ProgressUpdater { _, _, _ -> completedFuture() },
            ForegroundUpdater { _, _, _ -> completedFuture() },
        )
        val coordinator = DownloadComicCoordinator(
            database.downloadComicDao(),
            object : RemoteConfigPreferences {
                override val remoteImageHost = MutableStateFlow("")
            },
            object : DownloadContentOperations {
                override suspend fun downloadCover(downloadTask: DownloadComic, coverOwnerId: Int, remoteHost: String) = error("unexpected")
                override suspend fun downloadPages(downloadTask: DownloadComic, onProgress: suspend (Float) -> Unit) = error("unexpected")
                override suspend fun complete(downloadTask: DownloadComic) = error("unexpected")
            },
            object : DownloadFeedback {
                override fun start(groupId: Int) = Unit
                override fun stop(groupId: Int) = Unit
                override fun showProgress(downloadTask: DownloadComic, progress: Float) = Unit
                override fun cancel(groupId: Int) = Unit
                override fun report(batchId: String, batchTotal: Int, comicId: Int, success: Boolean) = Unit
            },
        )
        return CacheMigrationWorker(context, params, database.downloadComicDao(), coordinator, database)
    }

    private fun createTreeDirectory(name: String): android.net.Uri {
        val providerTree = DocumentsContract.buildTreeDocumentUri(PROVIDER_AUTHORITY, "root")
        val providerRoot = DocumentsContract.buildDocumentUriUsingTree(providerTree, "root")
        val child = requireNotNull(findOrCreateCacheDocument(context, providerRoot, name, DocumentsContract.Document.MIME_TYPE_DIR))
        return DocumentsContract.buildTreeDocumentUri(PROVIDER_AUTHORITY, DocumentsContract.getDocumentId(child))
    }

    private fun createSafComic(id: Int): SafFixture {
        val sourceTree = createTreeDirectory("source-${UUID.randomUUID()}")
        setDownloadTreeUri(context, sourceTree.toString())
        val comic = DownloadComic(
            id = id,
            name = "SAF迁移测试",
            authorList = emptyList(),
            coverPath = "",
            zipPath = "",
            progress = 1f,
            status = DownloadStatus.COMPLETE,
            createTime = 1L,
        )
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        return try {
            val files = DownloadContentFiles(context)
            val chapterPath = files.chapterPath(comic)
            files.writePage(chapterPath, 0, bitmap)
            val coverPath = files.writeCover(comic, bitmap)
            SafFixture(comic.copy(coverPath = coverPath, zipPath = chapterPath))
        } finally {
            bitmap.recycle()
        }
    }

    private fun grantProviderAccess() {
        val done = java.util.concurrent.CountDownLatch(1)
        base.sendOrderedBroadcast(
            Intent().setComponent(
                android.content.ComponentName(
                    "jmcomic.debug.test",
                    TestCacheGrantReceiver::class.java.name,
                ),
            ),
            null,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = done.countDown()
            },
            null,
            0,
            null,
            null,
        )
        check(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    private object ImmediateTaskExecutor : TaskExecutor {
        private val executor = Executor { it.run() }
        private val serial = object : SerialExecutor {
            override fun execute(command: Runnable) = command.run()
            override fun hasPendingTasks() = false
        }

        override fun getMainThreadExecutor() = executor
        override fun getSerialTaskExecutor() = serial
    }

    private fun completedFuture(): com.google.common.util.concurrent.ListenableFuture<Void> =
        SettableFuture.create<Void>().apply { set(null) }

    private object DeviceLocalChapterFilesForTest {
        fun images(context: Context, task: DownloadComic): List<String> =
            com.par9uet.jm.reader.atom.DeviceLocalChapterFiles(context).images(task.id, task)
    }

    private data class SafFixture(val comic: DownloadComic)

    private companion object {
        const val PROVIDER_AUTHORITY = "jmcomic.debug.test.cache-documents"
    }
}
