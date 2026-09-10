package com.par9uet.jm.download

import com.par9uet.jm.cache.getComicCacheRootName
import com.par9uet.jm.cache.getChapterCacheName
import com.par9uet.jm.download.atom.hasCompleteWebpContainer
import com.par9uet.jm.download.atom.writeDownloadImageAtomically
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DownloadFileIntegrityTest {
    @Test fun `same titles and sanitized chapter names do not share storage`() {
        val first = downloadTask(1).copy(groupId = 10, chapterName = "第一章")
        val second = downloadTask(2).copy(groupId = 20, chapterName = "第一章")
        assertNotEquals(getComicCacheRootName(first), getComicCacheRootName(second))
        assertNotEquals(getChapterCacheName(first), getChapterCacheName(second.copy(groupId = 10)))
        assertEquals(getComicCacheRootName(first), getComicCacheRootName(first.copy(name = "新名称", groupName = "新名称")))
        assertEquals(getChapterCacheName(first), getChapterCacheName(first.copy(chapterName = "重命名")))
    }

    @Test fun `failed and interrupted writes preserve committed page and remove temporary files`() {
        val dir = Files.createTempDirectory("download-atomic").toFile()
        try {
            val page = File(dir, "0.webp").apply { writeText("previous") }
            assertThrows(IllegalStateException::class.java) {
                writeDownloadImageAtomically(page) { it.write("partial".toByteArray()); false }
            }
            assertEquals("previous", page.readText())
            assertThrows(IOException::class.java) {
                writeDownloadImageAtomically(page) { it.write(1); throw IOException("disk full") }
            }
            assertEquals("previous", page.readText())
            assertEquals(listOf("0.webp"), dir.list()!!.toList())
            page.delete()
            assertThrows(IllegalStateException::class.java) { writeDownloadImageAtomically(page) { true } }
            assertFalse(page.exists())
            writeDownloadImageAtomically(page) { it.write("complete".toByteArray()); true }
            assertEquals("complete", page.readText())
            assertEquals(listOf("0.webp"), dir.list()!!.toList())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `truncated webp with intact header is not reusable`() {
        val dir = Files.createTempDirectory("download-truncated").toFile()
        try {
            val page = File(dir, "0.webp")
            page.writeBytes("RIFF".toByteArray() + byteArrayOf(8, 0, 0, 0) + "WEBP".toByteArray())
            assertFalse(hasCompleteWebpContainer(page))
            page.appendBytes(byteArrayOf(1, 2, 3, 4))
            assertTrue(hasCompleteWebpContainer(page))
            page.writeBytes(byteArrayOf())
            assertFalse(hasCompleteWebpContainer(page))
        } finally { dir.deleteRecursively() }
    }
}
