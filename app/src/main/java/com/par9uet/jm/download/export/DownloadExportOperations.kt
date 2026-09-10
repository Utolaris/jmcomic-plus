package com.par9uet.jm.download.export

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.par9uet.jm.database.model.DownloadComic
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class PdfExportMode { Merge, SplitByChapter }

data class DownloadCacheSummary(val imageCount: Int, val totalBytes: Long)

interface DownloadExportOperations {
    suspend fun inspect(chapters: List<DownloadComic>, cachePath: String): DownloadCacheSummary
    suspend fun export(chapters: List<DownloadComic>, uri: String, mode: PdfExportMode)
}

/** Android document permission and PDF/file access stay behind this adapter. */
class DeviceDownloadExportOperations(private val context: Context) : DownloadExportOperations {
    override suspend fun inspect(chapters: List<DownloadComic>, cachePath: String): DownloadCacheSummary =
        withContext(Dispatchers.IO) {
            val infos = chapters.map { getCachedComicInfo(context, it) }
            val root = cachePath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
            DownloadCacheSummary(
                imageCount = infos.sumOf { it.imageCount },
                totalBytes = root?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() }
                    ?: infos.sumOf { it.totalBytes },
            )
        }

    override suspend fun export(chapters: List<DownloadComic>, uri: String, mode: PdfExportMode) {
        withContext(Dispatchers.IO) {
            require(chapters.isNotEmpty()) { "未选择可导出的缓存章节" }
            val tree = uri.toUri()
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            when (mode) {
                PdfExportMode.Merge -> if (chapters.size > 1) exportComicsToMergedPdf(context, chapters, tree)
                    else exportComicToPdf(context, chapters.single(), tree)
                PdfExportMode.SplitByChapter -> exportComicsToSeparatePdf(context, chapters, tree)
            }
        }
    }
}
