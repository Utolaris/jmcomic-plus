package com.par9uet.jm.reader.atom

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [DeviceLocalChapterFiles] resolves three historical layouts (current chapter directory,
 * legacy `<comicId>` directory and legacy ZIP archives) by touching the filesystem. These are
 * the ordering and extraction rules that silently break when only the happy path is tested.
 */
@RunWith(AndroidJUnit4::class)
class LocalChapterFilesDeviceTest {
    private lateinit var downloadDirectory: File
    private lateinit var chapterFiles: DeviceLocalChapterFiles

    @Before
    fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        downloadDirectory = File(base.cacheDir, "local-chapter-${System.nanoTime()}").apply { mkdirs() }
        chapterFiles = DeviceLocalChapterFiles(downloadDirectory) { task ->
            File(downloadDirectory, "JM${task.id}/chapter-${task.id}")
        }
    }

    @After
    fun tearDown() {
        downloadDirectory.deleteRecursively()
    }

    private fun task(id: Int, zipPath: String = ""): DownloadComic = DownloadComic(
        id = id,
        name = "本地章节 $id",
        authorList = emptyList(),
        coverPath = "",
        zipPath = zipPath,
        progress = 1f,
        status = DownloadStatus.COMPLETE,
        createTime = 1L,
        groupId = id,
        groupName = "本地章节 $id",
    )

    private fun chapterWithPages(id: Int, vararg names: String): String {
        val directory = File(downloadDirectory, "JM$id/chapter-$id").apply { mkdirs() }
        names.forEach { File(directory, it).writeBytes(ByteArray(16)) }
        return directory.absolutePath
    }

    private fun names(paths: List<String>): List<String> = paths.map { File(it).nameWithoutExtension }

    @Test
    fun pagesAreListedInNaturalOrderNotLexicographicOrder() {
        val chapter = chapterWithPages(8001, "0.webp", "1.webp", "2.webp", "10.webp")

        val images = chapterFiles.images(8001, task(8001, chapter))

        assertEquals(listOf("0", "1", "2", "10"), names(images))
    }

    @Test
    fun nonImageFilesAreIgnored() {
        val chapter = chapterWithPages(8002, "0.webp", "1.jpg", "cover.txt", "config.json")

        assertEquals(listOf("0", "1"), names(chapterFiles.images(8002, task(8002, chapter))))
    }

    @Test
    fun legacyComicIdDirectoryIsUsedWhenTheChapterDirectoryHasNoImages() {
        val legacy = File(downloadDirectory, "8003").apply { mkdirs() }
        File(legacy, "0.webp").writeBytes(ByteArray(16))
        File(legacy, "1.webp").writeBytes(ByteArray(16))

        assertEquals(listOf("0", "1"), names(chapterFiles.images(8003, task(8003))))
    }

    @Test
    fun lexicographicFilenamesFallBackToNameOrdering() {
        val chapter = chapterWithPages(8004, "b.webp", "a.webp", "c.webp")

        assertEquals(listOf("a", "b", "c"), names(chapterFiles.images(8004, task(8004, chapter))))
    }

    @Test
    fun legacyZipArchiveIsExtractedBeforeListing() {
        val archive = File(downloadDirectory, "legacy-8005.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            listOf("0.webp", "1.webp", "2.webp").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(ByteArray(16))
                zip.closeEntry()
            }
        }

        val images = chapterFiles.images(8005, task(8005, archive.absolutePath))

        assertEquals(listOf("0", "1", "2"), names(images))
        val extracted = File(downloadDirectory, "8005")
        assertTrue("解压结果应落到旧版目录", extracted.isDirectory)
        assertEquals(listOf("0", "1", "2"), names(chapterFiles.images(8005, task(8005))))
    }

    @Test
    fun missingChapterReturnsNoImagesInsteadOfThrowing() {
        assertTrue(chapterFiles.images(8006, task(8006)).isEmpty())
        assertTrue(chapterFiles.images(8006, null).isEmpty())
    }

    @Test
    fun anArchiveWithoutImagesDoesNotProduceAPartialChapter() {
        val archive = File(downloadDirectory, "empty-8007.zip")
        ZipOutputStream(FileOutputStream(archive)).use { /* no entries */ }

        val images = chapterFiles.images(8007, task(8007, archive.absolutePath))

        assertTrue(images.isEmpty())
        assertTrue("没有图片的压缩包不应留下解压目录", !File(downloadDirectory, "8007").exists())
        assertTrue("临时目录必须清理", downloadDirectory.list().orEmpty().none { it.startsWith(".8007-unzip-") })
    }
}
