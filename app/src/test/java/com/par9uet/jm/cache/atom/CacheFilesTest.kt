package com.par9uet.jm.cache.atom

import com.par9uet.jm.cache.CacheArea
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CacheFilesTest {
    @Test fun `all removes normal caches but never bypasses reader leases`() = runTest {
        val root = Files.createTempDirectory("cache-cleanup").toFile()
        try {
            listOf("download", "common", "reader_pages", "pdf_export").forEach {
                File(root, it).mkdirs()
                File(root, "$it/content").writeBytes(ByteArray(10))
            }
            val files = DeviceCacheFiles(root)
            assertEquals(40L, files.scan().single { it.area == CacheArea.ALL }.sizeBytes)
            assertEquals(10L, files.scan().single { it.area == CacheArea.PDF }.sizeBytes)
            files.remove(setOf(CacheArea.ALL, CacheArea.DOWNLOAD))
            assertEquals(listOf("reader_pages"), root.list()!!.toList())
            files.remove(setOf(CacheArea.READER))
            assertTrue(File(root, "reader_pages/content").exists())
        } finally { root.deleteRecursively() }
    }
}
