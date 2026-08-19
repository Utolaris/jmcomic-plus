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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

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

private data class DecodedCacheWrite(
    val file: File,
    val bitmap: Bitmap,
    val quality: Int,
    val generation: Long,
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
    private val maxDecodeConcurrency = if (imageWorkConcurrency == 1) 1 else 4
    private val initialDecodeConcurrency = localSettingManager.localSettingState.value
        .readDecodeConcurrency
        .coerceIn(1, maxDecodeConcurrency)

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val networkLimiter = ReaderDynamicLimiter(imageWorkConcurrency)
    private val backgroundNetworkLimiter =
        if (imageWorkConcurrency > 1) ReaderDynamicLimiter(imageWorkConcurrency - 1) else null
    private val decodeLimiter = ReaderDynamicLimiter(initialDecodeConcurrency)
    private val backgroundDecodeLimiter =
        if (maxDecodeConcurrency > 1) {
            ReaderDynamicLimiter(initialDecodeConcurrency - 1)
        } else {
            null
        }
    private val requests = ReaderInFlightRegistry<ReaderInFlightKey, ReaderDecodedPage>(scope)
    private val bitmapCache = ReaderLruCache<BitmapCacheKey, Bitmap>(
        maxSize = bitmapCacheBudgetBytes(memoryClassMb),
        sizeOf = { bitmap -> bitmap.allocationByteCount.toLong().coerceAtLeast(1L) },
    )
    private val aspectRatioCache = ReaderLruCache<ReaderPageKey, Float>(256L)

    /** Source and decoded writes never block one another. Cleanup takes both locks in this order. */
    private val sourceCacheMutex = Mutex()
    private val decodedCacheMutex = Mutex()
    private val cacheGeneration = AtomicLong(0L)
    private val diskCacheDir = File(appContext.cacheDir, "reader_pages").apply { mkdirs() }
    private val activeSourceFiles = ConcurrentHashMap<File, AtomicInteger>()
    private val decodedCacheWrites = Channel<DecodedCacheWrite>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_LATEST,
    )
    private val diskTrimRequests = Channel<Unit>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_LATEST,
    )

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
        scope.launch {
            localSettingManager.localSettingState
                .map { it.readDecodeConcurrency.coerceIn(1, maxDecodeConcurrency) }
                .distinctUntilChanged()
                .collect { concurrency ->
                    decodeLimiter.updateLimit(concurrency)
                    backgroundDecodeLimiter?.updateLimit((concurrency - 1).coerceAtLeast(0))
                }
        }
        scope.launch {
            for (write in decodedCacheWrites) {
                runCatching { writeDecodedCache(write) }
            }
        }
        scope.launch {
            for (ignored in diskTrimRequests) {
                runCatching { trimDiskCache() }
            }
        }
    }

    suspend fun loadVisiblePage(page: ReaderPage): ReaderDecodedPage =
        request(page, ReaderRequestPriority.VISIBLE, currentProfile())

    /** Download work is background throughput, not a visible request. */
    suspend fun loadForDownload(page: ReaderPage): ReaderDecodedPage =
        request(page, ReaderRequestPriority.BACKGROUND, ReaderDecodeProfile.DOWNLOAD)

    suspend fun prefetchPage(page: ReaderPage) {
        request(page, ReaderRequestPriority.PREFETCH, currentProfile())
    }

    fun cancelPrefetch(pageKey: ReaderPageKey): Boolean =
        requests.cancelPrefetchMatching { it.page == pageKey } > 0

    fun cancelAllPrefetch() = requests.cancelAllPrefetch()

    fun clearMemory() {
        bitmapCache.evictAll()
        aspectRatioCache.evictAll()
    }

    /** Clear the reader directory without invalidating the live pipeline instance. */
    suspend fun clearDiskCache() {
        sourceCacheMutex.withLock {
            decodedCacheMutex.withLock {
                cacheGeneration.incrementAndGet()
                ensureDiskCacheDir()
                diskCacheDir.listFiles().orEmpty()
                    .filterNot { activeSourceFiles.containsKey(it) }
                    .forEach { it.deleteRecursively() }
                ensureDiskCacheDir()
            }
        }
    }

    fun close() {
        appContext.unregisterComponentCallbacks(memoryCallbacks)
        requests.cancelAllPrefetch()
        decodedCacheWrites.close()
        diskTrimRequests.close()
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
        if (profile != ReaderDecodeProfile.DOWNLOAD) {
            cachedBitmap(cacheKey)?.let { bitmap ->
                return bitmap.toReaderDecodedPage()
            }
        }

        val inFlightKey = ReaderInFlightKey(page.key, profile.cacheToken)
        return requests.request(inFlightKey, priority) { handle ->
            performLoad(page, profile, cacheKey, handle)
        }
    }

    private suspend fun performLoad(
        page: ReaderPage,
        profile: ReaderDecodeProfile,
        cacheKey: BitmapCacheKey,
        handle: ReaderLoadHandle,
    ): ReaderDecodedPage = withContext(Dispatchers.IO) {
        if (profile != ReaderDecodeProfile.DOWNLOAD) {
            cachedBitmap(cacheKey)?.let { bitmap ->
                return@withContext bitmap.toReaderDecodedPage()
            }
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
                profile = profile,
            )
        }

        if (profile != ReaderDecodeProfile.DOWNLOAD) {
            decodeReaderDecodedFile(decodedFile, profile)?.let { cached ->
                cacheBitmap(cacheKey, page.key, cached.bitmap, cached.aspectRatio)
                return@withContext ReaderDecodedPage(cached.bitmap, cached.aspectRatio)
            } ?: decodedFile.takeIf(File::exists)?.delete()
        }

        val cachedSource = acquireCachedSourceFile(sourceFile)
        val rawFile = cachedSource?.first ?: fetchAndCacheSource(page, sourceFile, handle)
        try {
            val decoded = withDecodePriority(handle) {
                decodeReaderRawFile(rawFile, page, profile)
            }
            decodeAndCache(
                pageKey = page.key,
                cacheKey = cacheKey,
                decoded = decoded,
                decodedFile = decodedFile,
                profile = profile,
            )
        } finally {
            if (cachedSource != null) {
                releaseSourceFile(rawFile, cachedSource.second)
            } else {
                releaseSourceFile(rawFile, cacheGeneration.get(), temporary = true)
            }
        }
    }

    private fun decodeAndCache(
        pageKey: ReaderPageKey,
        cacheKey: BitmapCacheKey,
        decoded: DecodedReaderImage,
        decodedFile: File,
        profile: ReaderDecodeProfile,
    ): ReaderDecodedPage {
        if (profile != ReaderDecodeProfile.DOWNLOAD) {
            cacheBitmap(cacheKey, pageKey, decoded.bitmap, decoded.aspectRatio)
            scheduleDecodedCacheWrite(decodedFile, decoded.bitmap, profile.webpQuality)
        }
        return ReaderDecodedPage(decoded.bitmap, decoded.aspectRatio)
    }

    private suspend fun fetchAndCacheSource(
        page: ReaderPage,
        sourceFile: File,
        handle: ReaderLoadHandle,
    ): File {
        val generation = cacheGeneration.get()
        var networkError: Throwable? = null
        try {
            val temporary = createSourceTempFile()
            try {
                fetchRemoteToFile(page.originSrc, temporary, handle)
                return installSourceFile(sourceFile, temporary, generation)
            } catch (error: Throwable) {
                releaseSourceFile(temporary, generation, temporary = true)
                throw error
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            networkError = error
        }

        try {
            val fallbackFetcher = page.fallbackFetcher
            val bytes: ByteArray? = if (fallbackFetcher == null) {
                null
            } else {
                withNetworkPriority(handle) { fallbackFetcher() }
            }
            if (bytes != null && bytes.isNotEmpty() && bytes.size.toLong() <= MAX_SOURCE_BYTES) {
                val temporary = createSourceTempFile()
                try {
                    FileOutputStream(temporary).use { it.write(bytes) }
                    return installSourceFile(sourceFile, temporary, generation)
                } catch (error: Throwable) {
                    releaseSourceFile(temporary, generation, temporary = true)
                    throw error
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            networkError = error
        }
        throw ReaderImageException("网络错误", networkError)
    }

    private suspend fun fetchRemoteToFile(
        url: String,
        target: File,
        handle: ReaderLoadHandle,
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ReaderImageException("HTTP ${response.code}")
            val body = response.body ?: throw ReaderImageException("图片响应为空")
            if (body.contentLength() > MAX_SOURCE_BYTES) {
                throw ReaderImageException("图片过大")
            }
            val buffer = ByteArray(NETWORK_READ_CHUNK_BYTES)
            var total = 0L
            FileOutputStream(target).use { output ->
                body.byteStream().use { input ->
                    while (true) {
                        // Never wait for a visible request while holding a network permit.
                        handle.awaitBackgroundTurn()
                        val read = withNetworkPriority(handle) { input.read(buffer) }
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > MAX_SOURCE_BYTES) throw ReaderImageException("图片过大")
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (total == 0L) throw ReaderImageException("图片响应为空")
        }
    }

    private suspend fun <T> withNetworkPriority(
        handle: ReaderLoadHandle,
        block: suspend () -> T,
    ): T {
        if (handle.isVisible) return networkLimiter.withPermit(block)
        val backgroundLimiter = backgroundNetworkLimiter
        if (backgroundLimiter == null) {
            if (handle.priority == ReaderRequestPriority.PREFETCH) {
                handle.awaitVisible()
            } else {
                handle.awaitBackgroundTurn()
            }
            return networkLimiter.withPermit(block)
        }

        while (true) {
            if (handle.isVisible) return networkLimiter.withPermit(block)
            if (backgroundLimiter.tryAcquire()) {
                if (handle.isVisible) {
                    backgroundLimiter.release()
                    continue
                }
                return try {
                    networkLimiter.withPermit(block)
                } finally {
                    backgroundLimiter.release()
                }
            }
            delay(PRIORITY_RETRY_DELAY_MILLIS)
        }
    }

    private suspend fun <T> withDecodePriority(
        handle: ReaderLoadHandle,
        block: suspend () -> T,
    ): T {
        if (handle.isVisible) return decodeLimiter.withPermit(block)
        val backgroundLimiter = backgroundDecodeLimiter
        if (backgroundLimiter == null) {
            if (handle.priority == ReaderRequestPriority.PREFETCH) {
                handle.awaitVisible()
            } else {
                handle.awaitBackgroundTurn()
            }
            return decodeLimiter.withPermit(block)
        }
        if (backgroundLimiter.limit <= 0 && handle.priority == ReaderRequestPriority.PREFETCH) {
            while (!handle.isVisible && backgroundLimiter.limit <= 0) {
                delay(PRIORITY_RETRY_DELAY_MILLIS)
            }
            if (handle.isVisible) return decodeLimiter.withPermit(block)
        } else if (backgroundLimiter.limit <= 0) {
            handle.awaitBackgroundTurn()
            return decodeLimiter.withPermit(block)
        }

        while (true) {
            if (handle.isVisible) return decodeLimiter.withPermit(block)
            if (backgroundLimiter.tryAcquire()) {
                if (handle.isVisible) {
                    backgroundLimiter.release()
                    continue
                }
                return try {
                    decodeLimiter.withPermit(block)
                } finally {
                    backgroundLimiter.release()
                }
            }
            delay(PRIORITY_RETRY_DELAY_MILLIS)
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

    private suspend fun createSourceTempFile(): File = sourceCacheMutex.withLock {
        ensureDiskCacheDir()
        File.createTempFile("reader-source-", ".source", diskCacheDir).also {
            activeSourceFiles[it] = AtomicInteger(1)
        }
    }

    private suspend fun acquireCachedSourceFile(file: File): Pair<File, Long>? =
        sourceCacheMutex.withLock {
            if (!file.isFile || file.length() !in 1..MAX_SOURCE_BYTES) {
                null
            } else {
                activeSourceFiles.computeIfAbsent(file) { AtomicInteger() }.incrementAndGet()
                file to cacheGeneration.get()
            }
        }

    private fun releaseSourceFile(
        file: File,
        generation: Long,
        temporary: Boolean = false,
    ) {
        activeSourceFiles[file]?.let { references ->
            if (references.decrementAndGet() <= 0) {
                activeSourceFiles.remove(file, references)
            }
        }
        if (temporary || generation != cacheGeneration.get()) file.delete()
    }

    private suspend fun installSourceFile(
        destination: File,
        temporary: File,
        generation: Long,
    ): File {
        sourceCacheMutex.withLock {
            if (generation != cacheGeneration.get()) return@withLock
            ensureDiskCacheDir()
            if (!destination.isFile || destination.length() !in 1..MAX_SOURCE_BYTES) {
                temporary.copyTo(destination, overwrite = true)
            }
        }
        return temporary
    }

    private fun scheduleDecodedCacheWrite(file: File, bitmap: Bitmap, quality: Int) {
        if (file.isFile || bitmap.isRecycled) return
        decodedCacheWrites.trySend(
            DecodedCacheWrite(
                file = file,
                bitmap = bitmap,
                quality = quality,
                generation = cacheGeneration.get(),
            )
        )
    }

    private suspend fun writeDecodedCache(write: DecodedCacheWrite) {
        if (write.file.isFile || write.bitmap.isRecycled) return
        decodedCacheMutex.withLock {
            if (
                write.generation != cacheGeneration.get() ||
                write.file.isFile ||
                write.bitmap.isRecycled
            ) {
                return@withLock
            }
            ensureDiskCacheDir()
            val temporary = File.createTempFile("${write.file.name}-", ".tmp", diskCacheDir)
            try {
                FileOutputStream(temporary).use { output ->
                    if (!write.bitmap.compressWebpCompat(write.quality, output)) return@withLock
                }
                if (!temporary.renameTo(write.file)) {
                    temporary.copyTo(write.file, overwrite = true)
                }
            } finally {
                temporary.delete()
            }
        }
        requestDiskTrim()
    }

    private fun requestDiskTrim() {
        diskTrimRequests.trySend(Unit)
    }

    private suspend fun trimDiskCache() {
        if (!sourceCacheMutex.tryLock()) return
        try {
            if (!decodedCacheMutex.tryLock()) return
            try {
                ensureDiskCacheDir()
                val files = diskCacheDir.listFiles { file ->
                    file.isFile &&
                        !activeSourceFiles.containsKey(file) &&
                        (file.extension == "source" || file.extension == "webp")
                }.orEmpty()
                var total = files.sumOf(File::length)
                if (total <= MAX_DISK_CACHE_BYTES) return
                files.sortedBy(File::lastModified).forEach { file ->
                    if (total <= TARGET_DISK_CACHE_BYTES) return@forEach
                    total -= file.length()
                    file.delete()
                }
            } finally {
                decodedCacheMutex.unlock()
            }
        } finally {
            sourceCacheMutex.unlock()
        }
    }

    private fun ensureDiskCacheDir() {
        if (diskCacheDir.isDirectory) return
        if (diskCacheDir.exists()) diskCacheDir.deleteRecursively()
        diskCacheDir.mkdirs()
    }

    private fun Bitmap.toReaderDecodedPage(): ReaderDecodedPage =
        ReaderDecodedPage(this, width.toFloat() / height.coerceAtLeast(1))

    private companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; Mobile) AppleWebKit/537.36 Chrome/91.0 Safari/537.36"
        private const val REFERER = "https://18comic.vip"
        private const val NETWORK_READ_CHUNK_BYTES = 32 * 1024
        private const val MAX_SOURCE_BYTES = 40L * 1024L * 1024L
        private const val MAX_DISK_CACHE_BYTES = 256L * 1024L * 1024L
        private const val TARGET_DISK_CACHE_BYTES = 224L * 1024L * 1024L
        private const val PRIORITY_RETRY_DELAY_MILLIS = 24L

        private fun bitmapCacheBudgetBytes(memoryClassMb: Int): Long =
            (memoryClassMb.toLong() * 1024L * 1024L / 8L)
                .coerceIn(16L * 1024L * 1024L, 64L * 1024L * 1024L)
    }
}
