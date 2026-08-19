package com.par9uet.jm.reader

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.min

data class ReaderSourceRange(val top: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

private val scrambleSeedMap = listOf(2, 4, 6, 8, 10, 12, 14, 16, 18, 20)

internal fun readerPageName(originSrc: String): String = originSrc
    .substringBefore('?')
    .substringAfterLast('/')
    .substringBeforeLast('.')

internal fun readerScrambleSegmentCount(
    comicId: Int,
    scrambleId: Int,
    speed: String,
    originSrc: String,
): Int {
    if (originSrc.substringBefore('?').endsWith(".gif", ignoreCase = true)) return 0
    if (speed == "1" || comicId <= scrambleId) return 0
    val page = readerPageName(originSrc)
    val keyMd5 = md5("$comicId$page")
    var lastCharCode = keyMd5.last().code
    when {
        comicId in 268850..421925 -> lastCharCode %= 10
        comicId >= 421926 -> lastCharCode %= 8
    }
    return scrambleSeedMap.getOrNull(lastCharCode) ?: 10
}

/** Exact inverse order used by the existing JM reader, including the first remainder segment. */
internal fun scrambledSourceRanges(height: Int, segments: Int): List<ReaderSourceRange> {
    if (height <= 0 || segments <= 0 || segments > height) return emptyList()
    val segmentHeight = height / segments
    val remainder = height % segments
    return List(segments) { index ->
        val top = height - segmentHeight * (index + 1) - remainder
        val currentHeight = segmentHeight + if (index == 0) remainder else 0
        ReaderSourceRange(top, top + currentHeight)
    }
}

internal fun ordinarySourceRanges(
    width: Int,
    height: Int,
    maxStripHeight: Int = 2_048,
    maxStripPixels: Int = 4_000_000,
): List<ReaderSourceRange> {
    if (width <= 0 || height <= 0) return emptyList()
    val stripHeight = min(maxStripHeight, (maxStripPixels / width).coerceAtLeast(1))
    return (0 until height step stripHeight).map { top ->
        ReaderSourceRange(top, min(height, top + stripHeight))
    }
}

internal fun sampledDimension(value: Int, sample: Int): Int =
    ((value.toLong() + sample - 1L) / sample.toLong()).toInt().coerceAtLeast(1)

internal fun sampledBoundary(value: Int, sourceExtent: Int, sampledExtent: Int): Int =
    (value.toLong() * sampledExtent / sourceExtent.toLong()).toInt()

internal fun sourceRangesAreSequential(
    ranges: List<ReaderSourceRange>,
    sourceHeight: Int,
): Boolean {
    var expectedTop = 0
    ranges.forEach { range ->
        if (range.top != expectedTop || range.bottom <= range.top) return false
        expectedTop = range.bottom
    }
    return expectedTop == sourceHeight
}

internal fun readerDecodedPageSize(
    width: Int,
    height: Int,
    maxPixels: Long,
    maxWidth: Int,
): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 1 to 1
    val safeMaxWidth = maxWidth.coerceAtLeast(1)
    val safeMaxPixels = maxPixels.coerceAtLeast(1L)
    val widthScale = safeMaxWidth.toDouble() / width
    val pixelScale = kotlin.math.sqrt(safeMaxPixels.toDouble() / (width.toLong() * height.toLong()))
    val scale = min(1.0, min(widthScale, pixelScale))
    var targetWidth = (width * scale).toInt().coerceAtLeast(1)
    var targetHeight = (height * scale).toInt().coerceAtLeast(1)
    while (targetWidth.toLong() * targetHeight > safeMaxPixels) {
        if (targetHeight >= targetWidth) targetHeight-- else targetWidth--
    }
    return targetWidth to targetHeight
}

internal fun readerRegionSampleSize(
    width: Int,
    height: Int,
    maxPixels: Long,
    maxSample: Int = 32,
): Int {
    var sample = 1
    val budget = min(16_000_000L, maxPixels.coerceAtLeast(1L) * 2L)
    while (
        sampledDimension(width, sample).toLong() * sampledDimension(height, sample) > budget &&
        sample < maxSample
    ) {
        sample *= 2
    }
    return sample
}

private fun md5(value: String): String = MessageDigest.getInstance("MD5")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
