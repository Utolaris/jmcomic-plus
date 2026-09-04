package com.par9uet.jm.reader.atom

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import com.par9uet.jm.reader.ReaderDecodeProfile
import com.par9uet.jm.reader.ReaderDecodedPage
import com.par9uet.jm.reader.ReaderLruCache
import com.par9uet.jm.reader.ReaderPageKey

private data class ReaderBitmapCacheKey(
    val page: ReaderPageKey,
    val profileToken: String,
)

/** Bounded decoded-bitmap ownership and Android memory-pressure handling. */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class ReaderBitmapCache(
    maxSizeBytes: Long,
) : ComponentCallbacks2 {
    private val cache = ReaderLruCache<ReaderBitmapCacheKey, Bitmap>(
        maxSize = maxSizeBytes,
        sizeOf = { bitmap -> bitmap.allocationByteCount.toLong().coerceAtLeast(1L) },
    )

    fun get(pageKey: ReaderPageKey, profile: ReaderDecodeProfile): ReaderDecodedPage? {
        val bitmap = cache[ReaderBitmapCacheKey(pageKey, profile.cacheToken)]
            ?.takeUnless { it.isRecycled }
            ?: return null
        return ReaderDecodedPage(
            bitmap = bitmap,
            aspectRatio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1),
        )
    }

    fun put(pageKey: ReaderPageKey, profile: ReaderDecodeProfile, bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        cache.put(ReaderBitmapCacheKey(pageKey, profile.cacheToken), bitmap)
    }

    fun clear() = cache.evictAll()

    override fun onTrimMemory(level: Int) {
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> clear()
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
                cache.trimToSize(cache.maxSize() / 4L)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
                cache.trimToSize(cache.maxSize() / 4L)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                cache.trimToSize(cache.maxSize() / 2L)
        }
    }

    override fun onLowMemory() = clear()

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
}

internal fun readerBitmapCacheBudgetBytes(memoryClassMb: Int): Long =
    (memoryClassMb.toLong() * 1024L * 1024L / 8L)
        .coerceIn(16L * 1024L * 1024L, 64L * 1024L * 1024L)
