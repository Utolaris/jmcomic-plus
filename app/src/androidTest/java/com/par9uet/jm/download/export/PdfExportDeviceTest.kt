package com.par9uet.jm.download.export

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.par9uet.jm.cache.findOrCreateCacheDocument
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.download.atom.DownloadContentFiles
import java.io.File
import java.net.URLDecoder
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PDF export runs through a real [android.graphics.pdf.PdfDocument] and a real
 * `ContentResolver`, so it can only be trusted when it runs on a device. Writing to a real
 * SAF tree also catches display-name problems that never appear on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class PdfExportDeviceTest {
    private lateinit var base: Context
    private lateinit var context: Context
    private lateinit var token: String
    private lateinit var files: DownloadContentFiles
    private val bitmaps = mutableListOf<Bitmap>()

    @Before
    fun setUp() {
        base = InstrumentationRegistry.getInstrumentation().targetContext
        token = "pdf-export-${UUID.randomUUID()}"
        context = object : ContextWrapper(base) {
            override fun getCacheDir() = File(base.cacheDir, token).apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("$token-$name", mode)
        }
        files = DownloadContentFiles(context)
    }

    @After
    fun tearDown() {
        bitmaps.forEach { it.recycle() }
        bitmaps.clear()
        context.cacheDir.deleteRecursively()
    }

    private fun page(): Bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).also { bitmaps += it }

    private fun comicWithPages(id: Int, name: String, pageCount: Int): DownloadComic {
        val task = DownloadComic(
            id = id,
            name = name,
            authorList = emptyList(),
            coverPath = "",
            zipPath = "",
            progress = 1f,
            status = DownloadStatus.COMPLETE,
            createTime = 1L,
            groupId = id,
            groupName = name,
        )
        val chapter = files.chapterPath(task)
        repeat(pageCount) { index -> files.writePage(chapter, index, page()) }
        return task.copy(zipPath = chapter)
    }

    private fun tree(name: String): Uri {
        val providerTree = DocumentsContract.buildTreeDocumentUri(PROVIDER_AUTHORITY, "root")
        val providerRoot = DocumentsContract.buildDocumentUriUsingTree(providerTree, "root")
        val child = requireNotNull(
            findOrCreateCacheDocument(context, providerRoot, name, DocumentsContract.Document.MIME_TYPE_DIR)
        )
        return DocumentsContract.buildTreeDocumentUri(PROVIDER_AUTHORITY, DocumentsContract.getDocumentId(child))
    }

    private fun readBytes(uri: String): ByteArray =
        requireNotNull(context.contentResolver.openInputStream(Uri.parse(uri))).use { it.readBytes() }

    @Test
    fun exportedFileIsAReadablePdfWithTheExpectedHeader() {
        val comic = comicWithPages(7001, "导出测试", 2)

        val uri = exportComicToPdf(context, comic, tree("export-${UUID.randomUUID()}"))

        val bytes = readBytes(uri)
        assertTrue("导出结果必须是 PDF：${String(bytes.take(8).toByteArray())}", bytes.size > 500)
        assertEquals("%PDF-", String(bytes.copyOfRange(0, 5), Charsets.US_ASCII))
        assertTrue(uri.endsWith(".pdf"))
    }

    @Test
    fun cachedComicInfoCountsTheRealFilesOnDisk() {
        val comic = comicWithPages(7002, "统计测试", 3)

        val info = getCachedComicInfo(context, comic)

        assertEquals(3, info.imageCount)
        assertTrue(info.totalBytes > 0L)
        assertEquals(comic.zipPath, info.imageDir?.absolutePath)
    }

    @Test
    fun exportingAComicWithoutCachedPagesFailsLoudly() {
        val empty = DownloadComic(
            id = 7003,
            name = "没有缓存",
            authorList = emptyList(),
            coverPath = "",
            zipPath = "",
            progress = 0f,
            status = DownloadStatus.PENDING,
            createTime = 1L,
        )
        val target = tree("empty-${UUID.randomUUID()}")

        assertThrows(IllegalStateException::class.java) { exportComicToPdf(context, empty, target) }
    }

    @Test
    fun mergedExportCombinesEveryChapterIntoOnePdf() {
        val first = comicWithPages(7004, "第一章", 1)
        val second = comicWithPages(7005, "第二章", 1)

        val uri = exportComicsToMergedPdf(context, listOf(first, second), tree("merged-${UUID.randomUUID()}"))

        val bytes = readBytes(uri)
        assertEquals("%PDF-", String(bytes.copyOfRange(0, 5), Charsets.US_ASCII))
        // 文档 Uri 会对中文路径做百分号编码，比较前先解码。
        assertTrue(URLDecoder.decode(uri, "UTF-8").contains("合并"))
    }

    @Test
    fun separateExportProducesOnePdfPerChapter() {
        val first = comicWithPages(7006, "分章一", 1)
        val second = comicWithPages(7007, "分章二", 1)

        val uris = exportComicsToSeparatePdf(context, listOf(first, second), tree("separate-${UUID.randomUUID()}"))

        assertEquals(2, uris.size)
        uris.forEach { assertEquals("%PDF-", String(readBytes(it).copyOfRange(0, 5), Charsets.US_ASCII)) }
    }

    @Test
    fun partialPageFailureDeletesTheIncompletePdfFromTheSafTree() {
        val target = tree("partial-${UUID.randomUUID()}")
        val comic = comicWithPages(7008, "部分失败", 3)
        // good page / corrupt page / good page
        File(comic.zipPath, "1.webp").writeBytes(ByteArray(64) { 0x7F })

        val error = assertThrows(IllegalStateException::class.java) {
            exportComicToPdf(context, comic, target)
        }
        assertTrue(error.message!!.contains("已尝试清理未完成文件"))

        val children = context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(
                target,
                DocumentsContract.getTreeDocumentId(target),
            ),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )!!
        children.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                assertFalse("SAF 目录中不应残留 partial PDF：$name", name.endsWith(".pdf"))
            }
        }
    }

    private companion object {
        const val PROVIDER_AUTHORITY = "jmcomic.debug.test.cache-documents"
    }
}
