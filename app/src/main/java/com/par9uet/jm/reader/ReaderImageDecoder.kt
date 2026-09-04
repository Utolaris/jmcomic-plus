package com.par9uet.jm.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

internal data class DecodedReaderImage(
    val bitmap: Bitmap,
    val aspectRatio: Float,
)

private const val MAX_SOURCE_PIXELS = 80_000_000L

/** Full-resolution decode options; scramble reorder requires original pixel geometry. */
private fun fullSizeOptions(profile: ReaderDecodeProfile): BitmapFactory.Options =
    BitmapFactory.Options().apply {
        inSampleSize = 1
        inScaled = false
        inPreferredConfig = if (profile.useRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
    }

/** Performs a cheap format/bounds check before a temporary source becomes durable cache. */
internal fun validateReaderSourceFile(file: File): Pair<Int, Int> {
    require(file.isFile && file.length() in 1..READER_MAX_SOURCE_BYTES) { "图片源文件无效" }
    return readBounds(file)
}

internal fun decodeReaderRawFile(
    file: File,
    page: ReaderPage,
    profile: ReaderDecodeProfile,
): DecodedReaderImage {
    require(file.isFile && file.length() in 1..READER_MAX_SOURCE_BYTES) { "图片源文件无效" }
    val bounds = readBounds(file)
    // Full-size decode first: scramble reorder must happen in ORIGINAL pixel space, and the
    // old strip pipeline's per-strip FILTER_BITMAP rescaling produced horizontal seam lines.
    // On OOM, retry once with an aggressive sample size rather than crashing the reader.
    val bitmap = try {
        BitmapFactory.decodeFile(file.absolutePath, fullSizeOptions(profile)) ?: error("图片解码为空")
    } catch (error: OutOfMemoryError) {
        decodeSampledForSeamRecovery(file.absolutePath, bounds, profile)
    }
    return reorderAndScaleFallback(bitmap, bounds, page, profile)
}

internal fun decodeReaderRawBytes(
    bytes: ByteArray,
    page: ReaderPage,
    profile: ReaderDecodeProfile,
): DecodedReaderImage {
    require(bytes.isNotEmpty() && bytes.size.toLong() <= READER_MAX_SOURCE_BYTES) { "图片源数据无效" }
    val bounds = readBounds(bytes)
    val bitmap = try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, fullSizeOptions(profile)) ?: error("图片解码为空")
    } catch (error: OutOfMemoryError) {
        decodeSampledBytesForSeamRecovery(bytes, bounds, profile)
    }
    return reorderAndScaleFallback(bitmap, bounds, page, profile)
}

/** OOM fallback: a sampled decode still allows 1:1 reorder without per-segment filtering. */
private fun decodeSampledForSeamRecovery(path: String, bounds: Pair<Int, Int>, profile: ReaderDecodeProfile): Bitmap {
    System.gc()
    val recoverySample = readerRegionSampleSize(
        width = bounds.first,
        height = bounds.second,
        maxPixels = MAX_SOURCE_PIXELS / 4,
        maxWidth = profile.maxWidth,
    ).coerceAtLeast(2)
    return BitmapFactory.decodeFile(path, decodeOptions(recoverySample, profile)) ?: error("图片解码为空")
}

private fun decodeSampledBytesForSeamRecovery(
    bytes: ByteArray,
    bounds: Pair<Int, Int>,
    profile: ReaderDecodeProfile,
): Bitmap {
    System.gc()
    val recoverySample = readerRegionSampleSize(
        width = bounds.first,
        height = bounds.second,
        maxPixels = MAX_SOURCE_PIXELS / 4,
        maxWidth = profile.maxWidth,
    ).coerceAtLeast(2)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions(recoverySample, profile))
        ?: error("图片解码为空")
}

internal fun decodeReaderDecodedFile(
    file: File,
    profile: ReaderDecodeProfile,
): DecodedReaderImage? {
    if (!file.isFile || file.length() !in 1..READER_MAX_SOURCE_BYTES) return null
    val bounds = readBounds(file)
    return runCatching {
        // A decoded-cache file is already at the profile's target size. Never sample it again.
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions(1, profile))
            ?: return null
        DecodedReaderImage(bitmap, bounds.first.toFloat() / bounds.second.toFloat())
    }.getOrNull()
}

private fun decodeBitmapFactory(
    file: File,
    bounds: Pair<Int, Int>,
    page: ReaderPage,
    profile: ReaderDecodeProfile,
): DecodedReaderImage {
    val bitmap = BitmapFactory.decodeFile(
        file.absolutePath,
        decodeOptions(
            readerRegionSampleSize(
                bounds.first,
                bounds.second,
                profile.maxPixels,
                maxWidth = profile.maxWidth,
            ),
            profile,
        ),
    ) ?: error("图片解码为空")
    return reorderAndScaleFallback(bitmap, bounds, page, profile)
}

private fun decodeBitmapFactory(
    bytes: ByteArray,
    bounds: Pair<Int, Int>,
    page: ReaderPage,
    profile: ReaderDecodeProfile,
): DecodedReaderImage {
    val bitmap = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        decodeOptions(
            readerRegionSampleSize(
                bounds.first,
                bounds.second,
                profile.maxPixels,
                maxWidth = profile.maxWidth,
            ),
            profile,
        ),
    ) ?: error("图片解码为空")
    return reorderAndScaleFallback(bitmap, bounds, page, profile)
}

private fun reorderAndScaleFallback(
    source: Bitmap,
    bounds: Pair<Int, Int>,
    page: ReaderPage,
    profile: ReaderDecodeProfile,
): DecodedReaderImage {
    val (width, height) = bounds
    val segments = readerScrambleSegmentCount(page.comicId, page.scrambleId, page.speed, page.originSrc)
    val ranges = if (segments == 0) ordinarySourceRanges(width, height) else scrambledSourceRanges(height, segments)
    var ordered = source
    if (!sourceRangesAreSequential(ranges, height)) {
        val assembled = createBitmap(
            source.width,
            source.height,
            source.config ?: Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(assembled)
        val paint = Paint(Paint.DITHER_FLAG)
        var destinationTop = 0
        ranges.forEach { range ->
            val sourceTop = sampledBoundary(range.top, height, source.height)
            val sourceBottom = sampledBoundary(range.bottom, height, source.height)
            if (sourceBottom > sourceTop) {
                val rangeHeight = sourceBottom - sourceTop
                canvas.drawBitmap(
                    source,
                    Rect(0, sourceTop, source.width, sourceBottom),
                    Rect(0, destinationTop, source.width, destinationTop + rangeHeight),
                    paint,
                )
                destinationTop += rangeHeight
            }
        }
        require(destinationTop == source.height) { "图片分段重排高度不一致" }
        source.recycle()
        ordered = assembled
    }

    val targetSize = readerDecodedPageSize(width, height, profile.maxPixels, profile.maxWidth)
    if (ordered.width <= targetSize.first && ordered.height <= targetSize.second) {
        return DecodedReaderImage(ordered, width.toFloat() / height.toFloat())
    }
    val scaledSize = scaleDownSize(ordered.width, ordered.height, targetSize)
    if (scaledSize == (ordered.width to ordered.height)) {
        return DecodedReaderImage(ordered, width.toFloat() / height.toFloat())
    }
    val scaled = createBitmap(
        scaledSize.first,
        scaledSize.second,
        ordered.config ?: Bitmap.Config.ARGB_8888,
    )
    try {
        Canvas(scaled).drawBitmap(
            ordered,
            null,
            Rect(0, 0, scaled.width, scaled.height),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
        )
    } catch (error: Throwable) {
        scaled.recycle()
        throw error
    }
    ordered.recycle()
    return DecodedReaderImage(scaled, width.toFloat() / height.toFloat())
}

private fun readBounds(file: File): Pair<Int, Int> {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    return validateBounds(options.outWidth, options.outHeight)
}

private fun readBounds(bytes: ByteArray): Pair<Int, Int> {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    return validateBounds(options.outWidth, options.outHeight)
}

private fun validateBounds(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0) { "图片尺寸无效" }
    require(width.toLong() * height.toLong() <= MAX_SOURCE_PIXELS) { "图片尺寸过大" }
    return width to height
}

internal fun scaledBoundary(value: Int, sourceExtent: Int, targetExtent: Int): Int =
    (value.toLong() * targetExtent / sourceExtent.toLong()).toInt()

private fun scaleDownSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetSize: Pair<Int, Int>,
): Pair<Int, Int> {
    val scale = min(
        1.0,
        min(
            targetSize.first.toDouble() / sourceWidth,
            targetSize.second.toDouble() / sourceHeight,
        ),
    )
    return (sourceWidth * scale).roundToInt().coerceAtLeast(1) to
        (sourceHeight * scale).roundToInt().coerceAtLeast(1)
}

@Suppress("DEPRECATION")
private fun decodeOptions(
    sampleSize: Int,
    profile: ReaderDecodeProfile,
): BitmapFactory.Options = BitmapFactory.Options().apply {
    inSampleSize = sampleSize.coerceAtLeast(1)
    inScaled = false
    inPreferredConfig = if (profile.useRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
}
