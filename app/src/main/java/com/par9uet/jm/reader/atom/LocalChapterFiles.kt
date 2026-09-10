package com.par9uet.jm.reader.atom

import android.content.Context
import com.par9uet.jm.cache.*
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
    private val context: Context? = null,
    private val chapterDirectory: (DownloadComic) -> File,
) : LocalChapterFiles {
    constructor(downloadDirectory: File, chapterDirectory: (DownloadComic) -> File) : this(downloadDirectory, null, chapterDirectory)

    constructor(context: Context) : this(getDownloadDir(context), context, { getComicChapterDownloadDir(context, it) })

    @Synchronized
    override fun images(comicId: Int, task: DownloadComic?): List<String> {
        if (context != null && task != null) {
            val path = task.zipPath
            if (isDocumentCachePath(path)) {
                val images = listComicImagePaths(context, path)
                if (images.isNotEmpty()) return images
                if (cachePathIsDirectory(context, path)) return emptyList()
                // A migrated legacy archive can still be extracted through its URI.
                val localZip = File.createTempFile("legacy-", ".zip", downloadDirectory)
                try {
                    openCacheInputStream(context, path)?.use { input -> localZip.outputStream().use(input::copyTo) }
                    return images(comicId, task.copy(zipPath = localZip.absolutePath))
                } finally { localZip.delete() }
            }
            findExistingComicChapterDownloadPath(context, task)?.let {
                listComicImagePaths(context, it).takeIf(List<String>::isNotEmpty)?.let { paths -> return paths }
            }
        }
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
