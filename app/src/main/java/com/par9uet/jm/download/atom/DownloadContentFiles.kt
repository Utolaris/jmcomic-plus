package com.par9uet.jm.download.atom

import android.content.Context
import android.graphics.Bitmap
import com.par9uet.jm.cache.getComicChapterDownloadDir
import com.par9uet.jm.cache.getComicCoverDownloadFile
import com.par9uet.jm.cache.writeComicCacheConfig
import com.par9uet.jm.data.models.ComicPicImageState
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.utils.compressWebpCompat
import java.io.File
import java.io.FileOutputStream

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
    override fun chapterPath(task: DownloadComic): String = getComicChapterDownloadDir(context, task).absolutePath

    override fun pageExists(chapterPath: String, index: Int): Boolean = pageFile(chapterPath, index).exists()

    override fun writePage(chapterPath: String, index: Int, bitmap: Bitmap): Long {
        val file = pageFile(chapterPath, index)
        FileOutputStream(file).use { bitmap.compressWebpCompat(50, it) }
        return file.length()
    }

    override fun writeCover(task: DownloadComic, bitmap: Bitmap): String {
        val file = getComicCoverDownloadFile(context, task)
        FileOutputStream(file).use { bitmap.compressWebpCompat(50, it) }
        return file.absolutePath
    }

    override fun writeConfig(current: DownloadComic, chapters: List<DownloadComic>) {
        writeComicCacheConfig(context, current, chapters)
    }

    private fun pageFile(chapterPath: String, index: Int) = File(chapterPath, "$index.webp")
}
