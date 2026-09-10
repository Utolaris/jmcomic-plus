package com.par9uet.jm.download.atom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.par9uet.jm.cache.*
import com.par9uet.jm.cache.getComicCoverDownloadFile
import com.par9uet.jm.cache.writeComicCacheConfig
import com.par9uet.jm.data.models.ComicPicImageState
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.utils.compressWebpCompat
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

fun interface DownloadPageDecoder {
    suspend fun decode(image: ComicPicImageState): Bitmap
}

interface DownloadContentStorage {
    fun chapterPath(task: DownloadComic): String
    fun pageExists(chapterPath: String, index: Int): Boolean
    fun writePage(chapterPath: String, index: Int, bitmap: Bitmap): Long
    fun writeCover(task: DownloadComic, bitmap: Bitmap): String
    fun writeConfig(current: DownloadComic, chapters: List<DownloadComic>)
}

class DownloadContentFiles(private val context: Context) : DownloadContentStorage {
    override fun chapterPath(task: DownloadComic): String = getComicChapterDownloadPath(context, task)

    override fun pageExists(chapterPath: String, index: Int): Boolean {
        if (!isDocumentCachePath(chapterPath)) return isValidDownloadImage(pageFile(chapterPath, index))
        val path = findCacheChildPath(context, chapterPath, "$index.webp") ?: return false
        return runCatching {
            val temporary = File.createTempFile("cache-validate-", ".webp", context.cacheDir)
            try {
                openCacheInputStream(context, path)?.use { input -> temporary.outputStream().use(input::copyTo) }
                isValidDownloadImage(temporary)
            } finally { temporary.delete() }
        }.getOrDefault(false)
    }

    override fun writePage(chapterPath: String, index: Int, bitmap: Bitmap): Long {
        if (isDocumentCachePath(chapterPath)) {
            val path = getOrCreateCacheFile(context, chapterPath, "$index.webp", "image/webp")
            return writeDocumentImage(path, bitmap)
        }
        val file = pageFile(chapterPath, index)
        writeDownloadImageAtomically(file) { bitmap.compressWebpCompat(50, it) }
        return file.length()
    }

    override fun writeCover(task: DownloadComic, bitmap: Bitmap): String {
        if (getDownloadTreeUri(context) != null) {
            val path = getComicCoverDownloadPath(context, task)
            writeDocumentImage(path, bitmap)
            return path
        }
        val file = getComicCoverDownloadFile(context, task)
        writeDownloadImageAtomically(file) { bitmap.compressWebpCompat(50, it) }
        return file.absolutePath
    }

    override fun writeConfig(current: DownloadComic, chapters: List<DownloadComic>) {
        writeDocumentComicCacheConfig(context, current, chapters)
    }

    private fun writeDocumentImage(path: String, bitmap: Bitmap): Long {
        val temporary = File.createTempFile("cache-image-", ".webp", context.cacheDir)
        try {
            writeDownloadImageAtomically(temporary) { bitmap.compressWebpCompat(50, it) }
            openCacheOutputStream(context, path).use { output -> temporary.inputStream().use { it.copyTo(output) } }
            return temporary.length()
        } catch (error: Throwable) {
            deleteCachePath(context, path)
            throw error
        } finally { temporary.delete() }
    }

    private fun pageFile(chapterPath: String, index: Int) = File(chapterPath, "$index.webp")
}

internal fun isValidDownloadImage(file: File): Boolean {
    if (!hasCompleteWebpContainer(file)) return false
    // Decode old files too: an interrupted write can still contain a valid image header.
    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return false
    bitmap.recycle()
    return true
}

internal fun hasCompleteWebpContainer(file: File): Boolean {
    if (!file.isFile || file.length() < 12L) return false
    val header = ByteArray(12)
    file.inputStream().use { if (it.read(header) != header.size) return false }
    if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
        String(header, 8, 4, Charsets.US_ASCII) != "WEBP") return false
    val payloadSize = (4..7).fold(0L) { size, index ->
        size or ((header[index].toLong() and 255L) shl ((index - 4) * 8))
    }
    return payloadSize + 8L == file.length()
}

internal fun writeDownloadImageAtomically(file: File, compress: (OutputStream) -> Boolean) {
    val temporary = File.createTempFile(".${file.name}-", ".tmp", file.parentFile)
    try {
        FileOutputStream(temporary).use { output ->
            check(compress(output)) { "图片压缩失败" }
            output.fd.sync()
        }
        check(temporary.length() > 0) { "图片文件为空" }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}
