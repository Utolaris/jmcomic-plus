package com.par9uet.jm.cache.migration

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.par9uet.jm.cache.cachePathExists
import com.par9uet.jm.cache.cachePathHasContent
import com.par9uet.jm.cache.findCacheChildPath
import com.par9uet.jm.cache.findOrCreateCacheDocument
import com.par9uet.jm.cache.getDownloadDir
import com.par9uet.jm.cache.getDownloadTreeUri
import com.par9uet.jm.cache.isDocumentCachePath
import com.par9uet.jm.cache.listComicImageEntries
import com.par9uet.jm.cache.listComicImagePaths
import com.par9uet.jm.cache.openCacheOutputStream
import com.par9uet.jm.cache.setDownloadTreeUri
import com.par9uet.jm.database.AppDatabase
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.download.atom.DownloadContentFiles
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L3/L4 组合的真机行为：真实 Room + 真实 DocumentsContract 读写。迁移的决策分支
 * 已经由 JVM 的 [CacheMigrationCoordinatorTest] 覆盖，这里只验证文件与索引真的搬对了。
 */
@RunWith(AndroidJUnit4::class)
class CacheMigrationDeviceTest {
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

        val outcome = migrate(targetTree.toString())

        assertEquals(CacheMigrationOutcome.Success, outcome)
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
    fun `saved path cleared by a re-download is skipped instead of failing`() = runBlocking {
        setDownloadTreeUri(context, "")
        val removed = File(context.cacheDir, "removed/chapter")
        val removedCover = File(context.cacheDir, "removed/cover.webp")
        val original = DownloadComic(
            id = 4202,
            name = "重新下载中",
            authorList = emptyList(),
            coverPath = removedCover.absolutePath,
            zipPath = removed.absolutePath,
            progress = 0f,
            status = DownloadStatus.PENDING,
            createTime = 1L,
        )
        database.downloadComicDao().insert(original)
        val targetTree = createTreeDirectory("redownload-${UUID.randomUUID()}")

        val outcome = migrate(targetTree.toString())

        assertEquals(CacheMigrationOutcome.Success, outcome)
        val migrated = database.downloadComicDao().getById(original.id)!!
        assertEquals("", migrated.zipPath)
        assertEquals("", migrated.coverPath)
        assertEquals(targetTree.toString(), getDownloadTreeUri(context)?.toString())
    }

    @Test
    fun `unreachable source fails without changing db or active tree`() = runBlocking {
        setDownloadTreeUri(context, "")
        val unreachable = "content://jmcomic.debug.test.missing-documents/document/chapter"
        val original = DownloadComic(
            id = 4207,
            name = "不可读测试",
            authorList = emptyList(),
            coverPath = "",
            zipPath = unreachable,
            progress = 1f,
            status = DownloadStatus.COMPLETE,
            createTime = 1L,
        )
        database.downloadComicDao().insert(original)
        val targetTree = createTreeDirectory("failed-${UUID.randomUUID()}")

        val outcome = migrate(targetTree.toString())

        assertEquals(CacheMigrationOutcome.Failure("无法读取缓存路径，请检查目录授权：$unreachable"), outcome)
        assertEquals(original, database.downloadComicDao().getById(original.id))
        assertEquals(null, getDownloadTreeUri(context))
        assertEquals(null, findCacheChildPath(context, targetRootDocument(targetTree), "JM4207"))
    }

    @Test
    fun `paused chapter without a saved zip path is migrated`() = runBlocking {
        setDownloadTreeUri(context, "")
        val sourceRoot = File(getDownloadDir(context), "JM4206").apply { mkdirs() }
        val partialChapter = File(sourceRoot, "chapter-4206").apply { mkdirs() }
        val partialPage = File(partialChapter, "0.webp").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val original = DownloadComic(
            id = 4206,
            name = "断点续传测试",
            authorList = emptyList(),
            coverPath = "",
            zipPath = "",
            progress = 0.6f,
            status = DownloadStatus.PAUSED,
            createTime = 1L,
        )
        database.downloadComicDao().insert(original)
        val targetTree = createTreeDirectory("partial-${UUID.randomUUID()}")

        val outcome = migrate(targetTree.toString())

        assertEquals(CacheMigrationOutcome.Success, outcome)
        val migrated = database.downloadComicDao().getById(original.id)!!
        assertTrue(isDocumentCachePath(migrated.zipPath))
        assertEquals(listOf("0.webp"), listComicImageEntries(context, migrated.zipPath).map { it.name })
        assertFalse(partialPage.exists())
        assertFalse(partialChapter.exists())
        assertFalse(sourceRoot.exists())
    }

    @Test
    fun `saf cache migrates to default file cache`() = runBlocking {
        val fixture = createSafComic(4203)
        database.downloadComicDao().insert(fixture.comic)

        val outcome = migrate("")

        assertEquals(CacheMigrationOutcome.Success, outcome)
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

        val outcome = migrate(targetTree.toString())

        assertEquals(CacheMigrationOutcome.Success, outcome)
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
        val targetRoot = android.net.Uri.parse(targetRootDocument(targetTree))
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
        val untouchedChapter = requireNotNull(findOrCreateCacheDocument(
            context,
            targetComic,
            "chapter-9999",
            DocumentsContract.Document.MIME_TYPE_DIR,
        ))
        val untouchedPage = requireNotNull(findOrCreateCacheDocument(
            context,
            untouchedChapter,
            "1.webp",
            "image/webp",
        ))
        openCacheOutputStream(context, untouchedPage.toString()).use { it.write(byteArrayOf(7, 7, 7)) }

        val outcome = migrate(targetTree.toString())

        assertEquals(CacheMigrationOutcome.Success, outcome)
        val migrated = database.downloadComicDao().getById(original.id)!!
        assertEquals(listOf("0.webp"), listComicImageEntries(context, migrated.zipPath).map { it.name })
        assertTrue(cachePathExists(context, untouchedPage.toString()))
    }

    private suspend fun migrate(targetTree: String): CacheMigrationOutcome {
        val coordinator = CacheMigrationCoordinator(
            DeviceCacheMigrationOperations(context, database.downloadComicDao(), database),
            object : CacheMigrationDownloadGate {
                override suspend fun <T> withIdleDownloads(block: suspend () -> T): T = block()
            },
        )
        return coordinator.migrate(targetTree, RecordingFeedback)
    }

    private object RecordingFeedback : CacheMigrationFeedback {
        override suspend fun stage(percent: Int, stage: String) = Unit
        override suspend fun progress(percent: Int, stage: String) = Unit
    }

    private fun createTreeDirectory(name: String): android.net.Uri {
        val providerTree = DocumentsContract.buildTreeDocumentUri(PROVIDER_AUTHORITY, "root")
        val providerRoot = DocumentsContract.buildDocumentUriUsingTree(providerTree, "root")
        val child = requireNotNull(findOrCreateCacheDocument(context, providerRoot, name, DocumentsContract.Document.MIME_TYPE_DIR))
        return DocumentsContract.buildTreeDocumentUri(PROVIDER_AUTHORITY, DocumentsContract.getDocumentId(child))
    }

    private fun targetRootDocument(tree: android.net.Uri): String =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)).toString()

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

    private object DeviceLocalChapterFilesForTest {
        fun images(context: Context, task: DownloadComic): List<String> =
            com.par9uet.jm.reader.atom.DeviceLocalChapterFiles(context).images(task.id, task)
    }

    private data class SafFixture(val comic: DownloadComic)

    private companion object {
        const val PROVIDER_AUTHORITY = "jmcomic.debug.test.cache-documents"
    }
}
