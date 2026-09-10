package com.par9uet.jm.reader.atom

import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class LocalChapterFilesTest {
    private fun task(path: String = "") = DownloadComic(
        id = 11, name = "漫画", authorList = emptyList(), coverPath = "", zipPath = path,
        progress = 1f, status = DownloadStatus.COMPLETE, createTime = 1,
    )

    @Test fun `saved legacy directory wins over current directory and pages are naturally ordered`() {
        val root = Files.createTempDirectory("local-reader").toFile()
        try {
            val old = File(root, "旧标题/第一章").apply { mkdirs() }
            listOf("10.webp", "2.jpg", "0.png", "config.json").forEach { File(old, it).writeText("data") }
            val current = File(root, "JM11/chapter-11").apply { mkdirs() }
            File(current, "1.webp").writeText("other")
            val files = DeviceLocalChapterFiles(root) { current }
            assertEquals(listOf("0.png", "2.jpg", "10.webp"), files.images(11, task(old.path)).map { File(it).name })
            assertTrue(files.images(11, task(old.path)).all { File(it).parentFile == old })
            assertEquals(listOf(File(current, "1.webp").absolutePath), files.images(11, task()))
        } finally { root.deleteRecursively() }
    }

    @Test fun `legacy zip flattens entries stays in cache and can be reopened without archive`() {
        val root = Files.createTempDirectory("local-reader-zip").toFile()
        try {
            val zip = File(root, "legacy.zip")
            ZipOutputStream(zip.outputStream()).use { output ->
                listOf("nested/10.webp", "../../2.webp").forEach {
                    output.putNextEntry(ZipEntry(it)); output.write(it.toByteArray()); output.closeEntry()
                }
            }
            val files = DeviceLocalChapterFiles(root) { File(root, "missing") }
            val images = files.images(11, task(zip.path))
            assertEquals(listOf("2.webp", "10.webp"), images.map { File(it).name })
            assertTrue(images.all { File(it).parentFile == File(root, "11") })
            zip.delete()
            assertEquals(images, files.images(11, null))
            assertEquals(emptyList<String>(), files.images(404, null))
        } finally { root.deleteRecursively() }
    }

    @Test fun `interrupted zip extraction never publishes a partial chapter`() {
        val root = Files.createTempDirectory("local-reader-bad-zip").toFile()
        try {
            val zip = File(root, "bad.zip")
            ZipOutputStream(zip.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("0.webp"))
                output.write(ByteArray(4096) { (it % 251).toByte() })
                output.closeEntry()
            }
            zip.writeBytes(zip.readBytes().take(80).toByteArray())
            val files = DeviceLocalChapterFiles(root) { File(root, "missing") }
            assertThrows(java.io.IOException::class.java) { files.images(11, task(zip.path)) }
            assertFalse(File(root, "11").exists())
            assertEquals(listOf("bad.zip"), root.list()!!.toList())
        } finally { root.deleteRecursively() }
    }
}
