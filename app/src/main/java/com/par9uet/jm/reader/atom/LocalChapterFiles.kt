package com.par9uet.jm.reader.atom

import android.content.Context
import com.par9uet.jm.cache.getComicChapterDownloadDir
import com.par9uet.jm.cache.getDownloadDir
import com.par9uet.jm.cache.listComicImageFiles
import com.par9uet.jm.database.model.DownloadComic
import java.io.File
import java.util.zip.ZipInputStream

fun interface LocalChapterFiles {
    fun images(comicId: Int, task: DownloadComic?): List<String>
}

/** Resolves saved paths, current directories and legacy ZIPs; callers run this on IO. */
class DeviceLocalChapterFiles(
    private val downloadDirectory: File,
    private val chapterDirectory: (DownloadComic) -> File,
) : LocalChapterFiles {
    constructor(context: Context) : this(getDownloadDir(context), { getComicChapterDownloadDir(context, it) })

    @Synchronized
    override fun images(comicId: Int, task: DownloadComic?): List<String> {
        fun imagesIn(dir: File?) = dir?.takeIf { it.isDirectory }?.let(::listComicImageFiles).orEmpty()
        val savedPath = task?.zipPath?.takeIf { it.isNotBlank() }?.let(::File)
        imagesIn(savedPath).takeIf { it.isNotEmpty() }?.let { return it.map(File::getAbsolutePath) }
        imagesIn(task?.let(chapterDirectory)).takeIf { it.isNotEmpty() }?.let { return it.map(File::getAbsolutePath) }
        val legacyDirectory = File(downloadDirectory, comicId.toString())
        imagesIn(legacyDirectory).takeIf { it.isNotEmpty() }?.let { return it.map(File::getAbsolutePath) }
        if (savedPath?.isFile != true) return emptyList()

        // A failed extraction must not make a partially extracted chapter look complete.
        downloadDirectory.mkdirs()
        val temporary = java.nio.file.Files.createTempDirectory(downloadDirectory.toPath(), ".$comicId-unzip-").toFile()
        try {
            ZipInputStream(savedPath.inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    if (!entry.isDirectory) {
                        val name = File(entry.name).name
                        if (name != "." && name != ".." && name.isNotBlank()) {
                            File(temporary, name).outputStream().use { input.copyTo(it) }
                        }
                    }
                    input.closeEntry()
                }
            }
            if (imagesIn(temporary).isEmpty()) return emptyList()
            check(!legacyDirectory.exists() || legacyDirectory.deleteRecursively()) { "无法更新本地解压目录" }
            check(temporary.renameTo(legacyDirectory)) { "无法保存本地解压图片" }
            return imagesIn(legacyDirectory).map(File::getAbsolutePath)
        } finally {
            temporary.deleteRecursively()
        }
    }
}
