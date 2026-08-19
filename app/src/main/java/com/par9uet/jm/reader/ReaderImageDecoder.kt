package com.par9uet.jm.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
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

private const val MAX_SOURCE_BYTES = 40L * 1024L * 1024L
private const val MAX_SOURCE_PIXELS = 80_000_000L
private const val MAX_REGION_STRIP_HEIGHT = 2_048
private const val MAX_REGION_STRIP_PIXELS = 4_000_000

internal fun decodeReaderRawFile(
    file: File,
    page: ReaderPage,
    profile: ReaderDecodeProfile,
): DecodedReaderImage {
    require(file.isFile && file.length() in 1..MAX_SOURCE_BYTES) { "图片源文件无效" }
    val bounds = readBounds(file)
    return try {
        val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false)
        decodeWithRegionDecoder(decoder, bounds, page, profile)
    } catch (error: OutOfMemoryError) {
        throw error
    } catch (_: Exception) {
        decodeBitmapFactory(file, bounds, page, profile)
    }
}

internal fun decodeReaderRawBytes(
    bytes: ByteArray,
    page: ReaderPage,
    profile: ReaderDecodeProfile,
): DecodedReaderImage {
    require(bytes.isNotEmpty() && bytes.size.toLong() <= MAX_SOURCE_BYTES) { "图片源数据无效" }
    val bounds = readBounds(bytes)
    return try {
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        decodeWithRegionDecoder(decoder, bounds, page, profile)
    } catch (error: OutOfMemoryError) {
        throw error
    } catch (_: Exception) {
        decodeBitmapFactory(bytes, bounds, page, profile)
    }
}

internal fun decodeReaderDecodedFile(
    file: File,
    profile: ReaderDecodeProfile,
): DecodedReaderImage? {
    if (!file.isFile || file.length() !in 1..MAX_SOURCE_BYTES) return null
    val bounds = readBounds(file)
    return runCatching {
        // A decoded-cache file is already at the profile's target size. Never sample it again.
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions(1, profile))
            ?: return null
        DecodedReaderImage(bitmap, bounds.first.toFloat() / bounds.second.toFloat())
    }.getOrNull()
}

private fun decodeWithRegionDecoder(
    decoder: BitmapRegionDecoder,
    bounds: Pair<Int, Int>,
    page: ReaderPage,
    profile: ReaderDecodeProfile,
): DecodedReaderImage {
    val (sourceWidth, sourceHeight) = bounds
    val aspectRatio = sourceWidth.toFloat() / sourceHeight.toFloat()
    val segments = readerScrambleSegmentCount(
        comicId = page.comicId,
        scrambleId = page.scrambleId,
        speed = page.speed,
        originSrc = page.originSrc,
    )
    require(segments == 0 || segments <= sourceHeight) { "图片分段数量无效" }
    val ranges = if (segments == 0) {
        ordinarySourceRanges(sourceWidth, sourceHeight)
    } else {
        scrambledSourceRanges(sourceHeight, segments)
    }
    return decodeRegionPage(
        decoder = decoder,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        sourceRanges = ranges,
        profile = profile,
        aspectRatio = aspectRatio,
    )
}

/** Decode each source strip directly into the final bitmap; no full sampled + assembled pair. */
private fun decodeRegionPage(
    decoder: BitmapRegionDecoder,
    sourceWidth: Int,
    sourceHeight: Int,
    sourceRanges: List<ReaderSourceRange>,
    profile: ReaderDecodeProfile,
    aspectRatio: Float,
): DecodedReaderImage {
    require(sourceRanges.isNotEmpty()) { "图片分段为空" }
    require(
        sourceRanges.all { it.top >= 0 && it.bottom <= sourceHeight && it.bottom > it.top } &&
            sourceRanges.sumOf(ReaderSourceRange::height) == sourceHeight
    ) { "图片分段范围无效" }

    val targetSize = readerDecodedPageSize(
        width = sourceWidth,
        height = sourceHeight,
        maxPixels = profile.maxPixels,
        maxWidth = profile.maxWidth,
    )
    val sampleSize = readerRegionSampleSize(
        width = sourceWidth,
        height = sourceHeight,
        maxPixels = profile.maxPixels,
        maxWidth = profile.maxWidth,
    )
    val strips = splitSourceRanges(sourceRanges, sourceWidth)
    val ordered = createBitmap(
        targetSize.first,
        targetSize.second,
        if (profile.useRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888,
    )
    val canvas = Canvas(ordered)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    var outputOffset = 0
    try {
        strips.forEach { range ->
            val destinationTop = scaledBoundary(outputOffset, sourceHeight, targetSize.second)
            val destinationBottom = scaledBoundary(
                outputOffset + range.height,
                sourceHeight,
                targetSize.second,
            )
            if (destinationBottom > destinationTop) {
                val segment = decoder.decodeRegion(
                    Rect(0, range.top, sourceWidth, range.bottom),
                    decodeOptions(sampleSize, profile),
                ) ?: error("区域解码为空")
                try {
                    canvas.drawBitmap(
                        segment,
                        null,
                        Rect(0, destinationTop, targetSize.first, destinationBottom),
                        paint,
                    )
                } finally {
                    segment.recycle()
                }
            }
            outputOffset += range.height
        }
        require(outputOffset == sourceHeight) { "图片分段重排高度不一致" }
        return DecodedReaderImage(ordered, aspectRatio)
    } catch (error: Throwable) {
        ordered.recycle()
        throw error
    } finally {
        decoder.recycle()
    }
}

private fun splitSourceRanges(
    sourceRanges: List<ReaderSourceRange>,
    sourceWidth: Int,
): List<ReaderSourceRange> {
    val stripHeight = min(
        MAX_REGION_STRIP_HEIGHT,
        (MAX_REGION_STRIP_PIXELS / sourceWidth).coerceAtLeast(1),
    )
    return sourceRanges.flatMap { range ->
        (range.top until range.bottom step stripHeight).map { top ->
            ReaderSourceRange(top, min(range.bottom, top + stripHeight))
        }
    }
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
