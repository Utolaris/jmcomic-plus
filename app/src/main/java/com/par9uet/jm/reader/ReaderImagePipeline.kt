package com.par9uet.jm.reader

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.utils.applyTlsCompat
import com.par9uet.jm.utils.compressWebpCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ReaderImageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Result returned to the currently composed reader item; the LRU remains the long-lived owner. */
data class ReaderDecodedPage(
    val bitmap: Bitmap,
    val aspectRatio: Float,
)

private data class BitmapCacheKey(
    val page: ReaderPageKey,
    val profileToken: String,
)

class ReaderImagePipeline(
    context: Context,
    private val localSettingManager: LocalSettingManager,
) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val memoryClassMb = activityManager?.memoryClass ?: 256
    private val lowRamDevice = activityManager?.isLowRamDevice ?: false
    private val imageWorkConcurrency = if (lowRamDevice || memoryClassMb < 384) 1 else 2
    private val decodeConcurrency = if (imageWorkConcurrency == 1) {
        1
    } else {
        localSettingManager.localSettingState.value.readDecodeConcurrency.coerceIn(1, 2)
    }
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val networkLimiter = Semaphore(imageWorkConcurrency)
    private val backgroundNetworkLimiter =
        if (imageWorkConcurrency > 1) Semaphore(imageWorkConcurrency - 1) else null
    private val decodeLimiter = Semaphore(decodeConcurrency)
    private val backgroundDecodeLimiter =
        if (decodeConcurrency > 1) Semaphore(decodeConcurrency - 1) else null
    private val requests = ReaderInFlightRegistry<ReaderPageKey, ReaderDecodedPage>(scope)
    private val bitmapCache = ReaderLruCache<BitmapCacheKey, Bitmap>(
        maxSize = bitmapCacheBudgetBytes(memoryClassMb),
        sizeOf = { bitmap -> bitmap.allocationByteCount.toLong().coerceAtLeast(1L) },
    )
    private val aspectRatioCache = ReaderLruCache<ReaderPageKey, Float>(256L)
    private val diskCacheMutex = Mutex()
    private val diskCacheDir = File(appContext.cacheDir, "reader_pages").apply { mkdirs() }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .applyTlsCompat()
        .build()
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            when {
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> bitmapCache.evictAll()
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ->
                    bitmapCache.trimToSize(bitmapCache.maxSize() / 4L)
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ->
                    bitmapCache.trimToSize(bitmapCache.maxSize() / 4L)
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ->
                    bitmapCache.trimToSize(bitmapCache.maxSize() / 2L)
            }
        }

        override fun onLowMemory() = bitmapCache.evictAll()

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    init {
        appContext.registerComponentCallbacks(memoryCallbacks)
    }

    suspend fun loadVisiblePage(page: ReaderPage): ReaderDecodedPage =
        request(page, ReaderRequestPriority.VISIBLE, currentProfile())

    /** Download worker entry point. It keeps the historical full-quality decode profile. */
    suspend fun loadForDownload(page: ReaderPage): ReaderDecodedPage =
        request(page, ReaderRequestPriority.VISIBLE, ReaderDecodeProfile.DOWNLOAD)

    suspend fun prefetchPage(page: ReaderPage) {
        request(page, ReaderRequestPriority.PREFETCH, currentProfile())
    }

    fun cancelPrefetch(pageKey: ReaderPageKey): Boolean = requests.cancelPrefetch(pageKey)

    fun cancelAllPrefetch() = requests.cancelAllPrefetch()

    fun clearMemory() {
        bitmapCache.evictAll()
        aspectRatioCache.evictAll()
    }

    fun close() {
        appContext.unregisterComponentCallbacks(memoryCallbacks)
        requests.cancelAllPrefetch()
        scopeJob.cancel()
        bitmapCache.evictAll()
        aspectRatioCache.evictAll()
        httpClient.dispatcher.cancelAll()
        httpClient.connectionPool.evictAll()
    }

    private suspend fun request(
        page: ReaderPage,
        priority: ReaderRequestPriority,
        profile: ReaderDecodeProfile,
    ): ReaderDecodedPage {
        val cacheKey = BitmapCacheKey(page.key, profile.cacheToken)
        cachedBitmap(cacheKey)?.let { bitmap ->
            return ReaderDecodedPage(bitmap, bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1))
        }

        return requests.request(page.key, priority) { handle ->
            performLoad(page, profile, cacheKey, handle)
        }
    }

    private suspend fun performLoad(
        page: ReaderPage,
        profile: ReaderDecodeProfile,
        cacheKey: BitmapCacheKey,
        handle: ReaderLoadHandle,
    ): ReaderDecodedPage = withContext(Dispatchers.IO) {
        cachedBitmap(cacheKey)?.let { bitmap ->
            return@withContext ReaderDecodedPage(
                bitmap,
                bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1),
            )
        }

        val decodedFile = File(
            diskCacheDir,
            "${readerCacheKey(page.key, "decoded", profile)}.webp",
        )
        val sourceFile = File(
            diskCacheDir,
            "${readerCacheKey(page.key, "source")}.source",
        )

        val localFile = page.localFile
        if (localFile?.isFile == true) {
            return@withContext decodeAndCache(
                pageKey = page.key,
                cacheKey = cacheKey,
                decoded = withDecodePriority(handle) {
                    decodeReaderRawFile(localFile, page, profile)
                },
                decodedFile = decodedFile,
                persistDecoded = profile.name != ReaderDecodeProfile.DOWNLOAD.name,
                webpQuality = profile.webpQuality,
            )
        }

        decodeReaderDecodedFile(decodedFile, profile)?.let { cached ->
            cacheBitmap(cacheKey, page.key, cached.bitmap, cached.aspectRatio)
            return@withContext ReaderDecodedPage(cached.bitmap, cached.aspectRatio)
        } ?: decodedFile.takeIf(File::exists)?.delete()

        val validSourceFile = sourceFile.takeIf { it.isFile && it.length() in 1..MAX_SOURCE_BYTES }
        val reusedSourceCache = validSourceFile != null
        val rawFile = validSourceFile ?: run {
            val bytes = fetchSourceBytes(page, handle)
            try {
                persistSourceCache(sourceFile, bytes)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // A cache write is best effort; the fetched bytes still feed the current decode.
            }
            sourceFile.takeIf { it.isFile } ?: run {
                val temporary = File.createTempFile("reader-source-", ".source", diskCacheDir)
                temporary.writeBytes(bytes)
                temporary
            }
        }

        val decoded = try {
            withDecodePriority(handle) { decodeReaderRawFile(rawFile, page, profile) }
        } catch (error: Throwable) {
            if (reusedSourceCache) rawFile.delete()
            throw error
        } finally {
            if (rawFile.name.startsWith("reader-source-")) rawFile.delete()
        }
        decodeAndCache(
            pageKey = page.key,
            cacheKey = cacheKey,
            decoded = decoded,
            decodedFile = decodedFile,
            persistDecoded = profile.name != ReaderDecodeProfile.DOWNLOAD.name,
            webpQuality = profile.webpQuality,
        )
    }

    private fun decodeAndCache(
        pageKey: ReaderPageKey,
        cacheKey: BitmapCacheKey,
        decoded: DecodedReaderImage,
        decodedFile: File,
        persistDecoded: Boolean,
        webpQuality: Int,
    ): ReaderDecodedPage {
        cacheBitmap(cacheKey, pageKey, decoded.bitmap, decoded.aspectRatio)
        if (persistDecoded) {
            scheduleDecodedCacheWrite(decodedFile, decoded.bitmap, webpQuality)
        }
        return ReaderDecodedPage(decoded.bitmap, decoded.aspectRatio)
    }

    private suspend fun fetchSourceBytes(page: ReaderPage, handle: ReaderLoadHandle): ByteArray {
        var networkError: Throwable? = null
        try {
            return withNetworkPriority(handle) {
                fetchRemoteBytes(page.originSrc, handle)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            networkError = error
        }

        try {
            withNetworkPriority(handle) {
                page.fallbackFetcher?.invoke()
            }?.let { bytes ->
                if (bytes.isNotEmpty()) return bytes
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            networkError = error
        }
        throw ReaderImageException("网络错误", networkError)
    }

    private suspend fun fetchRemoteBytes(url: String, handle: ReaderLoadHandle): ByteArray =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", USER_AGENT)
                .header("Referer", REFERER)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ReaderImageException("HTTP ${response.code}")
                val body = response.body ?: throw ReaderImageException("图片响应为空")
                val output = ByteArrayOutputStream(
                    body.contentLength().takeIf { it in 1..MAX_SOURCE_BYTES }?.toInt() ?: 32 * 1024,
                )
                val buffer = ByteArray(NETWORK_READ_CHUNK_BYTES)
                body.byteStream().use { input ->
                    var total = 0L
                    while (true) {
                        handle.awaitBackgroundTurn()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > MAX_SOURCE_BYTES) throw ReaderImageException("图片过大")
                        output.write(buffer, 0, read)
                    }
                }
                if (output.size() == 0) throw ReaderImageException("图片响应为空")
                output.toByteArray()
            }
        }

    private suspend fun <T> withNetworkPriority(
        handle: ReaderLoadHandle,
        block: suspend () -> T,
    ): T {
        if (handle.isVisible) return networkLimiter.withPermit { block() }
        val backgroundLimiter = backgroundNetworkLimiter
        if (backgroundLimiter == null) {
            // A one-slot device never spends its only network slot on speculative work. The
            // same in-flight request is promoted immediately when a visible consumer arrives.
            handle.awaitVisible()
            return networkLimiter.withPermit { block() }
        }
        return backgroundLimiter.withPermit {
            networkLimiter.withPermit { block() }
        }
    }

    private suspend fun <T> withDecodePriority(
        handle: ReaderLoadHandle,
        block: () -> T,
    ): T {
        if (handle.isVisible) return decodeLimiter.withPermit { block() }
        val backgroundLimiter = backgroundDecodeLimiter
        if (backgroundLimiter == null) {
            handle.awaitVisible()
            return decodeLimiter.withPermit { block() }
        }
        return backgroundLimiter.withPermit {
            decodeLimiter.withPermit { block() }
        }
    }

    private fun cachedBitmap(cacheKey: BitmapCacheKey): Bitmap? =
        bitmapCache[cacheKey]?.takeUnless { it.isRecycled }

    private fun cacheBitmap(
        cacheKey: BitmapCacheKey,
        pageKey: ReaderPageKey,
        bitmap: Bitmap,
        aspectRatio: Float,
    ) {
        if (bitmap.isRecycled) return
        bitmapCache.put(cacheKey, bitmap)
        aspectRatioCache.put(pageKey, aspectRatio)
    }

    private fun currentProfile(): ReaderDecodeProfile {
        val setting = localSettingManager.localSettingState.value
        return if (setting.readMemoryOptEnabled) {
            ReaderDecodeProfile.LOW
        } else {
            ReaderDecodeProfile.HIGH
        }
    }

    private suspend fun persistSourceCache(file: File, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size.toLong() > MAX_SOURCE_BYTES) return
        withContext(Dispatchers.IO) {
            diskCacheMutex.withLock {
                if (file.isFile && file.length() == bytes.size.toLong()) return@withLock
                val temporary = File.createTempFile("${file.name}-", ".tmp", diskCacheDir)
                try {
                    FileOutputStream(temporary).use { output -> output.write(bytes) }
                    if (!temporary.renameTo(file)) temporary.copyTo(file, overwrite = true)
                } finally {
                    temporary.delete()
                }
            }
            trimDiskCache()
        }
    }

    private fun scheduleDecodedCacheWrite(file: File, bitmap: Bitmap, quality: Int) {
        if (file.isFile || bitmap.isRecycled) return
        scope.launch {
            diskCacheMutex.withLock {
                if (file.isFile || bitmap.isRecycled) return@withLock
                val temporary = File.createTempFile("${file.name}-", ".tmp", diskCacheDir)
                try {
                    FileOutputStream(temporary).use { output ->
                        if (!bitmap.compressWebpCompat(quality, output)) return@withLock
                    }
                    if (!temporary.renameTo(file)) temporary.copyTo(file, overwrite = true)
                } finally {
                    temporary.delete()
                }
            }
            trimDiskCache()
        }
    }

    private suspend fun trimDiskCache() {
        diskCacheMutex.withLock {
            val files = diskCacheDir.listFiles { file ->
                file.isFile && (file.extension == "source" || file.extension == "webp")
            }.orEmpty()
            var total = files.sumOf(File::length)
            if (total <= MAX_DISK_CACHE_BYTES) return@withLock
            files.sortedBy(File::lastModified).forEach { file ->
                if (total <= TARGET_DISK_CACHE_BYTES) return@forEach
                total -= file.length()
                file.delete()
            }
        }
    }

    private companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; Mobile) AppleWebKit/537.36 Chrome/91.0 Safari/537.36"
        private const val REFERER = "https://18comic.vip"
        private const val NETWORK_READ_CHUNK_BYTES = 32 * 1024
        private const val MAX_SOURCE_BYTES = 40L * 1024L * 1024L
        private const val MAX_DISK_CACHE_BYTES = 256L * 1024L * 1024L
        private const val TARGET_DISK_CACHE_BYTES = 224L * 1024L * 1024L

        private fun bitmapCacheBudgetBytes(memoryClassMb: Int): Long =
            (memoryClassMb.toLong() * 1024L * 1024L / 8L)
                .coerceIn(16L * 1024L * 1024L, 64L * 1024L * 1024L)
    }
}
