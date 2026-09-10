package com.par9uet.jm.download

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.download.atom.DownloadContentFiles
import com.par9uet.jm.download.export.getCachedComicInfo
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DownloadContentFilesTest {
    @Test fun validPagesAreReusableButTruncatedPagesAreNotAndLegacyPathsRemainReadable() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "download-files-test-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getCacheDir() = root }
        val files = DownloadContentFiles(context)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            val task = DownloadComic(id = 1, name = "同名", authorList = emptyList(), coverPath = "", zipPath = "", progress = 0f, status = DownloadStatus.PENDING, createTime = 1)
            val chapter = files.chapterPath(task)
            files.writePage(chapter, 0, bitmap)
            assertTrue(files.pageExists(chapter, 0))
            val page = File(chapter, "0.webp")
            page.writeBytes(page.readBytes().dropLast(1).toByteArray())
            assertFalse(files.pageExists(chapter, 0))
            files.writePage(chapter, 0, bitmap)
            assertTrue(files.pageExists(chapter, 0))
            val oldDirectory = File(root, "download/旧标题/单篇").apply { mkdirs() }
            page.copyTo(File(oldDirectory, "0.webp"))
            val legacy = task.copy(zipPath = oldDirectory.absolutePath, status = DownloadStatus.COMPLETE)
            assertEquals(oldDirectory, getCachedComicInfo(context, legacy).imageDir)
            assertEquals(1, getCachedComicInfo(context, legacy).imageCount)
            val other = task.copy(id = 2)
            assertNotEquals(files.chapterPath(task), files.chapterPath(other))
            assertFalse(files.pageExists(files.chapterPath(other), 0))
        } finally {
            bitmap.recycle()
            root.deleteRecursively()
        }
    }
}
