package com.par9uet.jm.download.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.DocumentsContract
import com.par9uet.jm.cache.*
import com.par9uet.jm.reader.atom.DeviceLocalChapterFiles
import com.par9uet.jm.cache.getDownloadDir
import com.par9uet.jm.cache.listComicImageFiles
import com.par9uet.jm.database.model.DownloadComic
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

data class CachedComicInfo(
    val imageCount: Int,
    val totalBytes: Long,
    val imageDir: File?,
    val zipFile: File?
)

fun getCachedComicInfo(context: Context, comic: DownloadComic): CachedComicInfo {
    val imageDir = comic.zipPath.takeUnless(::isDocumentCachePath)?.let(::File)?.takeIf(File::isDirectory)
    val imageFiles = DeviceLocalChapterFiles(context).images(comic.id, comic)
    val zipFile = comic.zipPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile && it.exists() }
    val totalBytes = imageFiles.sumOf { cachePathLength(context, it) } + (zipFile?.length() ?: 0L)
    return CachedComicInfo(
        imageCount = imageFiles.size,
        totalBytes = totalBytes,
        imageDir = imageDir,
        zipFile = zipFile
    )
}

fun exportComicToPdf(
    context: Context,
    comic: DownloadComic,
    treeUri: Uri
): String {
    val imageFiles = DeviceLocalChapterFiles(context).images(comic.id, comic)
    if (imageFiles.isEmpty()) {
        throw IllegalStateException("未找到可导出的缓存图片")
    }

    val fileName = safeFileName("${comic.name}_${comic.id}.pdf")
    return writeImagesToPdf(context, treeUri, fileName, imageFiles)
}

fun exportComicsToMergedPdf(
    context: Context,
    comics: List<DownloadComic>,
    treeUri: Uri
): String {
    val imageFiles = comics.flatMap { comic ->
        DeviceLocalChapterFiles(context).images(comic.id, comic)
    }
    if (imageFiles.isEmpty()) {
        throw IllegalStateException("未找到可导出的缓存图片")
    }
    val groupName = comics.firstOrNull { it.groupName.isNotBlank() }?.groupName
        ?: comics.firstOrNull()?.name
        ?: "comic"
    val fileName = safeFileName("${groupName}_合并_${comics.size}章.pdf")
    return writeImagesToPdf(context, treeUri, fileName, imageFiles)
}

fun exportComicsToSeparatePdf(
    context: Context,
    comics: List<DownloadComic>,
    treeUri: Uri
): List<String> {
    if (comics.isEmpty()) {
        throw IllegalStateException("没有可导出的缓存章节")
    }
    return comics.map { exportComicToPdf(context, it, treeUri) }
}

private const val PDF_MAX_BITMAP_DIMENSION = 2000

private fun writeImagesToPdf(
    context: Context,
    treeUri: Uri,
    fileName: String,
    imageFiles: List<String>
): String {
    val parentDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
    val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocumentId)
    val outputUri = DocumentsContract.createDocument(
        context.contentResolver,
        parentUri,
        "application/pdf",
        fileName
    ) ?: throw IllegalStateException("无法创建 PDF 文件")

    val failedPages = mutableListOf<Int>()
    context.contentResolver.openOutputStream(outputUri)?.use { output ->
        val document = PdfDocument()
        var pageIndex = 0
        try {
            imageFiles.forEachIndexed { index, file ->
                var bitmap: Bitmap? = null
                try {
                    bitmap = decodeBitmapForPdf(context, file)
                        ?: run {
                            failedPages.add(index + 1)
                            return@forEachIndexed
                        }
                    val pageWidth = bitmap.width.coerceAtLeast(1)
                    val pageHeight = bitmap.height.coerceAtLeast(1)
                    val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
                    val page = document.startPage(pageInfo)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    document.finishPage(page)
                    pageIndex++
                    bitmap.recycle()
                    bitmap = null
                } catch (e: OutOfMemoryError) {
                    System.gc()
                    bitmap?.recycle()
                    failedPages.add(index + 1)
                } catch (e: Exception) {
                    bitmap?.recycle()
                    failedPages.add(index + 1)
                }
            }
            document.writeTo(output)
        } finally {
            document.close()
        }
    } ?: throw IllegalStateException("无法写入 PDF 文件")

    if (failedPages.isNotEmpty()) {
        throw IllegalStateException(
            "导出完成但有 ${failedPages.size}/${imageFiles.size} 页失败：${failedPages.joinToString()}，可能内存不足或图片损坏"
        )
    }

    return outputUri.toString()
}

private fun decodeBitmapForPdf(context: Context, path: String): Bitmap? {
    val boundsOptions = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    openCacheInputStream(context, path)?.use { BitmapFactory.decodeStream(it, null, boundsOptions) }
    val width = boundsOptions.outWidth
    val height = boundsOptions.outHeight
    if (width <= 0 || height <= 0) return null

    val sampleSize = calculateSampleSize(width, height)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return openCacheInputStream(context, path)?.use { BitmapFactory.decodeStream(it, null, options) }
}

private fun calculateSampleSize(width: Int, height: Int): Int {
    var sampleSize = 1
    var maxDim = maxOf(width, height)
    while (maxDim / sampleSize > PDF_MAX_BITMAP_DIMENSION) {
        sampleSize *= 2
        maxDim /= 2
    }
    return sampleSize
}

private fun safeFileName(name: String): String {
    return name.replace(Regex("""[\\/:*?"<>|]"""), "_")
}
