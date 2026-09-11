package com.par9uet.jm.cache.atom

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.par9uet.jm.cache.CacheArea
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies [DeviceCacheFiles] against the real device filesystem.
 *
 * The reader directory is never deleted through this port; it is only reclaimed through the
 * reader cache generation protocol. These assertions are the regression guard for that rule.
 */
@RunWith(AndroidJUnit4::class)
class CacheFilesDeviceTest {
    private lateinit var root: File
    private lateinit var files: CacheFiles

    @Before
    fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(base.cacheDir, "cache-files-device-${System.nanoTime()}").apply { mkdirs() }
        files = DeviceCacheFiles(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun areaDirectory(area: CacheArea): File = when (area) {
        CacheArea.PDF -> File(root, "pdf_export")
        CacheArea.ALL -> root
        else -> File(root, area.id)
    }

    private fun seed(area: CacheArea, byteSize: Int = 64, vararg names: String) {
        val directory = areaDirectory(area).apply { mkdirs() }
        names.forEach { File(directory, it).writeBytes(ByteArray(byteSize)) }
    }

    private fun sizes(): Map<CacheArea, Long> =
        runBlocking { files.scan() }.associate { it.area to it.sizeBytes }

    @Test
    fun scanReportsRealByteCountsAndZeroForMissingAreas() {
        seed(CacheArea.COMMON, 100, "a.bin", "b.bin")
        seed(CacheArea.DECODE, 40, "c.bin")

        val sizes = sizes()

        assertEquals(200L, sizes[CacheArea.COMMON])
        assertEquals(40L, sizes[CacheArea.DECODE])
        assertEquals(0L, sizes[CacheArea.DOWNLOAD])
        assertEquals(0L, sizes[CacheArea.READER])
        assertEquals(240L, sizes[CacheArea.ALL])
    }

    @Test
    fun scanCountsNestedFilesInsideAnArea() {
        val nested = File(areaDirectory(CacheArea.DOWNLOAD), "JM1/chapter-1").apply { mkdirs() }
        File(nested, "0.webp").writeBytes(ByteArray(30))
        File(nested, "1.webp").writeBytes(ByteArray(30))

        assertEquals(60L, sizes()[CacheArea.DOWNLOAD])
    }

    @Test
    fun clearingEverythingKeepsTheReaderCacheIntact() {
        seed(CacheArea.COMMON, 16, "a.bin")
        seed(CacheArea.DOWNLOAD, 16, "b.bin")
        seed(CacheArea.DECODE, 16, "c.bin")
        seed(CacheArea.PDF, 16, "d.pdf")
        seed(CacheArea.READER, 16, "page.bin")

        runBlocking { files.remove(setOf(CacheArea.ALL)) }

        assertFalse(areaDirectory(CacheArea.COMMON).exists())
        assertFalse(areaDirectory(CacheArea.DOWNLOAD).exists())
        assertFalse(areaDirectory(CacheArea.DECODE).exists())
        assertFalse(areaDirectory(CacheArea.PDF).exists())
        assertTrue("阅读器目录必须保留，只能由阅读器租约协议回收", areaDirectory(CacheArea.READER).exists())
        assertEquals(listOf("page.bin"), areaDirectory(CacheArea.READER).list()?.toList())
    }

    @Test
    fun selectingOnlyTheReaderAreaDeletesNothing() {
        seed(CacheArea.READER, 16, "page.bin")
        seed(CacheArea.COMMON, 16, "a.bin")

        runBlocking { files.remove(setOf(CacheArea.READER)) }

        assertTrue(areaDirectory(CacheArea.READER).exists())
        assertTrue(areaDirectory(CacheArea.COMMON).exists())
    }

    @Test
    fun removingASingleAreaLeavesTheOthersUntouched() {
        seed(CacheArea.COMMON, 16, "a.bin")
        seed(CacheArea.DOWNLOAD, 16, "b.bin")

        runBlocking { files.remove(setOf(CacheArea.COMMON)) }

        assertFalse(areaDirectory(CacheArea.COMMON).exists())
        assertTrue(areaDirectory(CacheArea.DOWNLOAD).exists())
        assertEquals(16L, sizes()[CacheArea.DOWNLOAD])
    }

    @Test
    fun strayFilesInTheCacheRootAreRemovedButReaderPagesSurvive() {
        File(root, "stray.tmp").writeBytes(ByteArray(8))
        seed(CacheArea.READER, 8, "page.bin")

        runBlocking { files.remove(setOf(CacheArea.ALL)) }

        assertFalse(File(root, "stray.tmp").exists())
        assertTrue(areaDirectory(CacheArea.READER).exists())
    }
}
