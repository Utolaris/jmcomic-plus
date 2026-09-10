package com.par9uet.jm.cache

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.download.atom.DownloadContentFiles
import com.par9uet.jm.download.atom.DownloadFiles
import com.par9uet.jm.download.export.exportComicToPdf
import com.par9uet.jm.download.export.getCachedComicInfo
import com.par9uet.jm.reader.atom.DeviceLocalChapterFiles
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentCacheStorageTest {
    @Test fun customDirectorySupportsDownloadReadExportAndDelete() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val granted = java.util.concurrent.CountDownLatch(1)
        base.sendOrderedBroadcast(
            android.content.Intent().setComponent(android.content.ComponentName("jmcomic.debug.test", TestCacheGrantReceiver::class.java.name)),
            null, object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: android.content.Intent) { granted.countDown() }
            }, null, 0, null, null,
        )
        check(granted.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Test URI permission grant timed out" }
        val token = UUID.randomUUID().toString()
        val context = object : ContextWrapper(base) {
            override fun getCacheDir() = File(base.cacheDir, token).also { it.mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$token-$name", mode)
        }
        val tree = DocumentsContract.buildTreeDocumentUri("jmcomic.debug.test.cache-documents", "root")
        val treeRoot = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        val testRoot = requireNotNull(findOrCreateCacheDocument(context, treeRoot, token, DocumentsContract.Document.MIME_TYPE_DIR))
        val testTree = DocumentsContract.buildTreeDocumentUri(tree.authority, DocumentsContract.getDocumentId(testRoot))
        setDownloadTreeUri(context, testTree.toString())
        val bitmap = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        try {
            val files = DownloadContentFiles(context)
            val comic = DownloadComic(998877, "test", emptyList(), coverPath = "", zipPath = "",
                progress = 1f, status = DownloadStatus.COMPLETE, createTime = 0L)
            val chapter = files.chapterPath(comic)
            assertTrue(isDocumentCachePath(chapter))
            files.writePage(chapter, 10, bitmap)
            files.writePage(chapter, 2, bitmap)
            assertTrue(files.pageExists(chapter, 2))
            assertFalse(files.pageExists(chapter, 1))
            val saved = comic.copy(zipPath = chapter, coverPath = files.writeCover(comic, bitmap))
            files.writeConfig(saved, listOf(saved))
            assertTrue(cachePathHasContent(context, saved.coverPath))
            assertEquals(CachePathContent.HAS_CONTENT, cachePathContentStatus(context, saved.coverPath))
            assertEquals(listOf("2.webp", "10.webp"), listComicImageEntries(context, chapter).map { it.name })
            assertEquals(listComicImagePaths(context, chapter), DeviceLocalChapterFiles(context).images(comic.id, saved))
            assertEquals(2, getCachedComicInfo(context, saved).imageCount)
            val pdf = exportComicToPdf(context, saved, testTree)
            assertTrue(cachePathLength(context, pdf) > 0L)
            openCacheInputStream(context, pdf)!!.use { val header = ByteArray(4); assertEquals(4, it.read(header)); assertEquals("%PDF", String(header)) }
            DownloadFiles(context).delete(chapter, saved.coverPath)
            assertFalse(cachePathExists(context, chapter))
            assertFalse(cachePathExists(context, saved.coverPath))
            setDownloadTreeUri(context, "")
            assertFalse(isDocumentCachePath(files.chapterPath(comic)))
        } finally {
            bitmap.recycle()
            deleteCachePath(context, testRoot.toString())
            context.cacheDir.deleteRecursively()
            base.deleteSharedPreferences("$token-download_storage")
        }
    }
}
