package com.par9uet.jm.reader

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import com.par9uet.jm.BuildConfig
import com.par9uet.jm.image.ImageHostFailureKind
import com.par9uet.jm.image.JmImageHostHealthManager
import com.par9uet.jm.image.classifyImageHostFailure
import com.par9uet.jm.network.DohManager
import com.par9uet.jm.store.ReaderPreferences
import com.par9uet.jm.utils.compressWebpCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

class ReaderImageException(
    message: String,
    cause: Throwable? = null,
    val httpCode: Int? = null,
) : Exception(message, cause)

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

private data class ReaderSourceFile(
    val file: File,
    val generation: Long,
    val persistent: Boolean,
    val persistAfterValidation: Boolean,
    val cacheFile: File,
)

class ReaderImagePipeline internal constructor(
    context: Context,
    private val readerPreferences: ReaderPreferences,
    imageHostHealthManager: JmImageHostHealthManager,
    dohManager: DohManager,
) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val memoryClassMb = activityManager?.memoryClass ?: 256
    private val lowRamDevice = activityManager?.isLowRamDevice ?: false
    private val imageWorkConcurrency = ReaderConcurrencyPolicy.imageWorkConcurrency(lowRamDevice, memoryClassMb)
    private val maxDecodeConcurrency = ReaderConcurrencyPolicy.maxDecodeConcurrency(lowRamDevice, memoryClassMb)
    private val initialDecodeConcurrency = ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
        memoryOptEnabled = readerPreferences.memoryOptEnabled.value,
        userConcurrency = readerPreferences.decodeConcurrency.value,
        lowRamDevice = lowRamDevice,
        memoryClassMb = memoryClassMb,
    )
    private val networkConcurrency = if (
        imageWorkConcurrency > 1 && memoryClassMb >= 512
    ) {
        3
    } else {
        imageWorkConcurrency
    }

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val networkScheduler = ReaderNetworkScheduler(
        totalConcurrency = networkConcurrency,
        initialBackgroundConcurrency = if (
            networkConcurrency >= 3 &&
            readerPreferences.prefetchCount.value >= 5
        ) 2 else 1,
    )
    private val decodeLimiter = ReaderDynamicLimiter(initialDecodeConcurrency)
    private val backgroundDecodeLimiter =
        if (maxDecodeConcurrency > 1) {
            ReaderDynamicLimiter(initialDecodeConcurrency - 1)
        } else {
            null
        }
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
    private val visibleRequestTracker = ReaderVisibleRequestTracker()
    private val requests = ReaderInFlightRegistry<ReaderInFlightKey, ReaderDecodedPage>(
        scope = scope,
        visibleRequestTracker = visibleRequestTracker,
    )
    private val sourceRequests = ReaderInFlightRegistry<String, ReaderSourceFile>(
        scope = scope,
        visibleRequestTracker = visibleRequestTracker,
        onEntryReleased = { source ->
            releaseSourceFile(
                file = source.file,
                generation = source.generation,
                temporary = !source.persistent,
            )
        },
    )
    private val decodedCacheWrites = Channel<DecodedCacheWrite>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_LATEST,
    )
    private val diskTrimRequests = Channel<Unit>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_LATEST,
    )

    // DoH is injected as the shared Dns provider; smart CDN host selection stays upstream of
    // DNS resolution and keeps working unchanged.
    private val httpClient = OkHttpClient.Builder()
        .dns(dohManager)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val imageHostManager = ReaderImageHostManager(
        httpClient = httpClient,
        scope = scope,
        healthManager = imageHostHealthManager,
    )
    private val metrics = ReaderMetrics(BuildConfig.DEBUG)
    private val remoteFetcher = ReaderRemoteFetcher(
        client = httpClient,
        createTemporary = ::createSourceTempFile,
        discardTemporary = { file ->
            releaseSourceFile(file, cacheGeneration.get(), temporary = true)
        },
        validateTemporary = { file -> validateReaderSourceFile(file) },
        maxSourceBytes = MAX_SOURCE_BYTES,
        readChunkBytes = NETWORK_READ_CHUNK_BYTES,
        observer = object : ReaderRemoteObserver {
            override fun onRequestStarted(
                url: String,
                candidate: ReaderRemoteCandidate,
                call: Call,
            ) {
                metrics.networkStarted()
            }

            override fun onResponseHeaders(
                url: String,
                candidate: ReaderRemoteCandidate,
                elapsedMillis: Long,
                httpCode: Int,
            ) {
                metrics.responseHeadersReceived(
                    elapsedMillis = elapsedMillis,
                    primary = candidate == ReaderRemoteCandidate.PRIMARY,
                )
                if (httpCode in 200..299) {
                    // 成功响应头到达耗时是 Reader 排名所需的 TTFB 样本；
                    // 不等待 body 下载、图片解码或渲染完成。
                    imageHostManager.recordLatencySample(url, elapsedMillis)
                }
            }

            override fun onRequestSucceeded(
                url: String,
                candidate: ReaderRemoteCandidate,
                timeToHeadersMillis: Long,
                bodyMillis: Long,
                totalMillis: Long,
            ) {
                metrics.networkFinished(success = true, elapsedMillis = totalMillis)
                metrics.bodyDownloadFinished(bodyMillis)
                url.toHttpUrlOrNull()?.host?.let(metrics::hostSuccess)
            }

            override fun onRequestFailed(
                url: String,
                candidate: ReaderRemoteCandidate,
                totalMillis: Long,
                error: Throwable?,
            ) {
                metrics.networkFinished(success = false, elapsedMillis = totalMillis)
                url.toHttpUrlOrNull()?.host?.let(metrics::hostFailure)
                // 主机/网络级失败才冷却 CDN；404/内容断言只影响当前图片，
                // 由候选回退/重试逻辑处理，不全局惩罚主机
                val failureKind = when {
                    error is ReaderImageException && error.httpCode == null ->
                        ImageHostFailureKind.RESOURCE_FAILURE
                    else -> classifyImageHostFailure(
                        error,
                        httpCodeHint = (error as? ReaderImageException)?.httpCode,
                    )
                }
                if (failureKind == ImageHostFailureKind.HOST_FAILURE) {
                    imageHostManager.recordHostFailure(url)
                }
            }

            override fun onRequestCanceled(
                url: String,
                candidate: ReaderRemoteCandidate,
                totalMillis: Long,
                preempted: Boolean,
            ) {
                metrics.networkCanceled(totalMillis)
            }

            override fun onHedgeSecondaryStarted() = metrics.hedgeSecondaryStarted()

            override fun onHedgeWinner(primary: Boolean, url: String) {
                metrics.hedgeWinner(primary, url.toHttpUrlOrNull()?.host)
            }

            override fun onHedgeLoserCanceled() = metrics.hedgeLoserCanceled()
        },
    )

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
            combine(
                readerPreferences.memoryOptEnabled,
                readerPreferences.decodeConcurrency,
            ) { memoryOpt, userConcurrency -> memoryOpt to userConcurrency }
                // 内存优化开关与并发设置共同决定生效并发；关闭内存优化后恢复硬件默认，
                // 不再被历史保存的 readDecodeConcurrency 限速。运行时切换立即生效。
                .map { (memoryOpt, userConcurrency) ->
                    ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
                        memoryOptEnabled = memoryOpt,
                        userConcurrency = userConcurrency,
                        lowRamDevice = lowRamDevice,
                        memoryClassMb = memoryClassMb,
                    )
                }
                .distinctUntilChanged()
                .collect { concurrency ->
                    decodeLimiter.updateLimit(concurrency)
                    backgroundDecodeLimiter?.updateLimit((concurrency - 1).coerceAtLeast(0))
                }
        }
        scope.launch {
            readerPreferences.prefetchCount
                .collect { count ->
                    networkScheduler.updateBackgroundLimit(
                        if (networkConcurrency >= 3 && count >= 5) 2 else 1,
                    )
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
        withReaderVisibleLoadDeadline(VISIBLE_LOAD_TIMEOUT_MILLIS) {
            request(page, ReaderRequestPriority.VISIBLE, currentProfile())
        }

    /** Download work is background throughput, not a visible request. */
    suspend fun loadForDownload(page: ReaderPage): ReaderDecodedPage =
        request(page, ReaderRequestPriority.BACKGROUND, ReaderDecodeProfile.DOWNLOAD)

    suspend fun prefetchPage(page: ReaderPage) {
        if (imageWorkConcurrency == 1) {
            prefetchPageSource(page)
        } else {
            request(page, ReaderRequestPriority.PREFETCH, currentProfile())
        }
    }

    /** Source-only warming keeps a single-slot device responsive and avoids bitmap heap growth. */
    suspend fun prefetchPageSource(page: ReaderPage) = withContext(Dispatchers.IO) {
        metrics.request(ReaderRequestPriority.PREFETCH)
        val startedAt = SystemClock.elapsedRealtime()
        try {
            if (page.localFile?.isFile == true) return@withContext
            sourceRequests.request(sourceInFlightKey(page.key), ReaderRequestPriority.PREFETCH) { handle ->
                val source = loadSourceFile(
                    page = page,
                    priority = ReaderRequestPriority.PREFETCH,
                    handle = handle,
                    retryFromDecode = false,
                )
                if (!source.persistent) {
                    validateReaderSourceFile(source.file)
                    promoteSourceIfNeeded(source)
                }
                source
            }
        } finally {
            metrics.requestFinished(
                priority = ReaderRequestPriority.PREFETCH,
                elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
            )
        }
    }

    internal fun adaptivePrefetchPolicy(
        configuredDistance: Int,
        jumpDistance: Int,
        directionStreak: Int,
        pageVelocity: Float,
        turboMode: Boolean,
    ): ReaderPrefetchPolicy = readerAdaptivePrefetchPolicy(
        configuredDistance = configuredDistance,
        memoryClassMb = memoryClassMb,
        lowRamDevice = lowRamDevice,
        singleNetworkSlot = imageWorkConcurrency == 1,
        jumpDistance = jumpDistance,
        directionStreak = directionStreak,
        pageVelocity = pageVelocity,
        networkLatencyMillis = imageHostManager.preferredLatencyMillis(),
        turboMode = turboMode,
    )

    internal fun metricsSnapshot(): ReaderMetricsSnapshot = metrics.snapshot()

    internal fun imageHostSnapshot(): ReaderImageHostSnapshot = imageHostManager.snapshot()

    fun warmImageConnections(page: ReaderPage) {
        imageHostManager.warmImageConnections(page.originSrc)
    }

    fun cancelPrefetch(pageKey: ReaderPageKey): Boolean {
        val decodedCancelled = requests.cancelPrefetchMatching {
            it.page.stableIdentity() == pageKey.stableIdentity()
        } > 0
        val sourceCancelled = sourceRequests.cancelPrefetch(sourceInFlightKey(pageKey))
        if (decodedCancelled || sourceCancelled) metrics.prefetchCanceled()
        return decodedCancelled || sourceCancelled
    }

    fun cancelAllPrefetch() {
        requests.cancelAllPrefetch()
        sourceRequests.cancelAllPrefetch()
    }

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
        imageHostManager.close()
        httpClient.dispatcher.cancelAll()
        httpClient.connectionPool.evictAll()
    }

    private suspend fun request(
        page: ReaderPage,
        priority: ReaderRequestPriority,
        profile: ReaderDecodeProfile,
    ): ReaderDecodedPage {
        val startedAt = SystemClock.elapsedRealtime()
        metrics.request(priority)
        val canonicalPageKey = canonicalPageKey(page.key)
        val cacheKey = BitmapCacheKey(canonicalPageKey, profile.cacheToken)
        if (profile != ReaderDecodeProfile.DOWNLOAD) {
            cachedBitmap(cacheKey)?.let { bitmap ->
                metrics.memoryHit()
                if (priority == ReaderRequestPriority.PREFETCH) metrics.prefetchCacheHit()
                metrics.requestFinished(priority, SystemClock.elapsedRealtime() - startedAt)
                return bitmap.toReaderDecodedPage()
            }
        }
        metrics.cacheMiss()

        val inFlightKey = ReaderInFlightKey(canonicalPageKey, profile.cacheToken)
        return try {
            requests.request(inFlightKey, priority) { handle ->
                performLoad(page, priority, profile, cacheKey, handle)
            }
        } finally {
            metrics.requestFinished(priority, SystemClock.elapsedRealtime() - startedAt)
        }
    }

    private suspend fun performLoad(
        page: ReaderPage,
        priority: ReaderRequestPriority,
        profile: ReaderDecodeProfile,
        cacheKey: BitmapCacheKey,
        handle: ReaderLoadHandle,
    ): ReaderDecodedPage = withContext(Dispatchers.IO) {
        if (profile != ReaderDecodeProfile.DOWNLOAD) {
            cachedBitmap(cacheKey)?.let { bitmap ->
                metrics.memoryHit()
                return@withContext bitmap.toReaderDecodedPage()
            }
        }

        val decodedFile = File(
            diskCacheDir,
            "${readerCacheKey(page.key, "decoded", profile)}.webp",
        )
        val localFile = page.localFile
        if (localFile?.isFile == true) {
            return@withContext decodeAndCache(
                pageKey = page.key,
                cacheKey = cacheKey,
                decoded = withMeasuredDecode(handle) {
                    decodeReaderRawFile(localFile, page, profile)
                },
                decodedFile = decodedFile,
                profile = profile,
            )
        }

        if (profile != ReaderDecodeProfile.DOWNLOAD) {
            decodeReaderDecodedFile(decodedFile, profile)?.let { cached ->
                metrics.decodedDiskHit()
                cacheBitmap(cacheKey, page.key, cached.bitmap, cached.aspectRatio)
                return@withContext ReaderDecodedPage(cached.bitmap, cached.aspectRatio)
            } ?: decodedFile.takeIf(File::exists)?.delete()
        }

        var sourceLease = acquireSource(page, priority, retryFromDecode = false)
        var retriedAfterDecodeFailure = false
        var decodedPage: ReaderDecodedPage? = null
        try {
            while (decodedPage == null) {
                val decoded = try {
                    withMeasuredDecode(handle) {
                        decodeReaderRawFile(sourceLease.value.file, page, profile)
                    }
                } catch (error: Throwable) {
                    if (
                        !retriedAfterDecodeFailure &&
                        shouldInvalidateSourceAfterDecodeFailure(error)
                    ) {
                        retriedAfterDecodeFailure = true
                        invalidateSource(page.key, sourceLease.value)
                        sourceLease.release()
                        sourceLease = acquireSource(page, priority, retryFromDecode = true)
                        continue
                    }
                    throw error
                }
                promoteSourceIfNeeded(
                    source = sourceLease.value,
                    forcePersistence = priority == ReaderRequestPriority.VISIBLE,
                )
                decodedPage = decodeAndCache(
                    pageKey = page.key,
                    cacheKey = cacheKey,
                    decoded = decoded,
                    decodedFile = decodedFile,
                    profile = profile,
                )
            }
        } finally {
            sourceLease.release()
        }
        return@withContext checkNotNull(decodedPage)
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

    private suspend fun acquireSource(
        page: ReaderPage,
        priority: ReaderRequestPriority,
        retryFromDecode: Boolean,
    ): ReaderInFlightLease<ReaderSourceFile> = sourceRequests.acquire(
        sourceInFlightKey(page.key),
        priority,
    ) { handle ->
        loadSourceFile(
            page = page,
            priority = priority,
            handle = handle,
            retryFromDecode = retryFromDecode,
        )
    }

    private suspend fun loadSourceFile(
        page: ReaderPage,
        priority: ReaderRequestPriority,
        handle: ReaderLoadHandle,
        retryFromDecode: Boolean,
    ): ReaderSourceFile {
        val sourceFile = File(
            diskCacheDir,
            "${readerCacheKey(page.key, "source")}.source",
        )
        acquireCachedSourceFile(sourceFile)?.let { (file, generation) ->
            metrics.sourceHit()
            if (priority == ReaderRequestPriority.PREFETCH) metrics.prefetchCacheHit()
            return ReaderSourceFile(
                file = file,
                generation = generation,
                persistent = true,
                persistAfterValidation = false,
                cacheFile = sourceFile,
            )
        }
        return fetchSourceFile(
            page = page,
            sourceFile = sourceFile,
            handle = handle,
            persistSourceCache = priority != ReaderRequestPriority.BACKGROUND,
            preferFallback = retryFromDecode,
        )
    }

    private suspend fun fetchSourceFile(
        page: ReaderPage,
        sourceFile: File,
        handle: ReaderLoadHandle,
        persistSourceCache: Boolean,
        preferFallback: Boolean,
    ): ReaderSourceFile {
        val generation = cacheGeneration.get()
        var networkError: Throwable? = null

        suspend fun fetchFallback(): ReaderSourceFile? {
            val fallbackFetcher = page.fallbackFetcher ?: return null
            var bytes: ByteArray?
            while (true) {
                try {
                    bytes = withNetworkRequestPriority(handle) { fallbackFetcher() }
                    break
                } catch (_: ReaderBackgroundPreempted) {
                    handle.awaitBackgroundTurn()
                }
            }
            if (bytes == null || bytes.isEmpty() || bytes.size.toLong() > MAX_SOURCE_BYTES) {
                return null
            }
            val temporary = createSourceTempFile()
            return try {
                FileOutputStream(temporary).use { it.write(bytes) }
                validateReaderSourceFile(temporary)
                ReaderSourceFile(
                    file = temporary,
                    generation = generation,
                    persistent = false,
                    persistAfterValidation = persistSourceCache,
                    cacheFile = sourceFile,
                )
            } catch (error: Throwable) {
                releaseSourceFile(temporary, generation, temporary = true)
                throw error
            }
        }

        suspend fun fetchRemote(): ReaderSourceFile {
            val urls = imageHostManager.orderedImageUrls(page.originSrc)
            if (urls.isEmpty()) throw ReaderImageException("图片地址为空")
            imageHostManager.warmImageConnections(page.originSrc)
            var lastError: Throwable? = null
            val attemptedUrls = LinkedHashSet<String>()
            val visiblePolicy = readerVisibleSourcePolicy(
                orderedUrls = urls,
                fastestKnownLatencyMillis = imageHostManager.preferredLatencyMillis(),
            )
            var remainingUrls = if (handle.isVisible) visiblePolicy.urls else urls
            if (handle.isVisible && visiblePolicy.hedgeEnabled) {
                attemptedUrls += visiblePolicy.urls
                metrics.hedgeStarted()
                try {
                    val winner = fetchRemoteHedged(
                        primaryUrl = visiblePolicy.urls[0],
                        secondaryUrl = visiblePolicy.urls[1],
                        handle = handle,
                    )
                    return ReaderSourceFile(
                        file = winner.file,
                        generation = generation,
                        persistent = false,
                        persistAfterValidation = persistSourceCache,
                        cacheFile = sourceFile,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ReaderBackgroundPreempted) {
                    throw error
                } catch (error: Throwable) {
                    lastError = error
                    remainingUrls = urls.filterNot(attemptedUrls::contains)
                }
            }
            for (url in remainingUrls) {
                if (handle.isVisible && attemptedUrls.size >= MAX_VISIBLE_SOURCE_ATTEMPTS) break
                if (!attemptedUrls.add(url)) continue
                val attempt = fetchRemoteCandidate(url, handle)
                attempt.getOrNull()?.let { winner ->
                    return ReaderSourceFile(
                        file = winner.file,
                        generation = generation,
                        persistent = false,
                        persistAfterValidation = persistSourceCache,
                        cacheFile = sourceFile,
                    )
                }
                lastError = attempt.exceptionOrNull() ?: lastError
            }
            throw lastError ?: ReaderImageException("网络错误")
        }

        if (preferFallback && !handle.isVisible) {
            try {
                fetchFallback()?.let { return it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                networkError = error
            }
        }

        while (true) {
            try {
                return fetchRemote()
            } catch (error: CancellationException) {
                throw error
            } catch (_: ReaderBackgroundPreempted) {
                // Keep the source in-flight entry alive. Once foreground work is idle (or this
                // same source is promoted to visible), retry from a fresh HTTP call and permit.
                handle.awaitBackgroundTurn()
            } catch (error: Throwable) {
                networkError = error
                break
            }
        }

        if (!preferFallback && !handle.isVisible) {
            try {
                fetchFallback()?.let { return it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                networkError = error
            }
        }
        throw ReaderImageException("网络错误", networkError)
    }

    private suspend fun fetchRemoteCandidate(
        url: String,
        handle: ReaderLoadHandle,
    ): Result<ReaderRemoteAttempt> = remoteFetcher.fetch(
        url = url,
        acquirePermit = { acquireNetworkPermit(handle) },
        shouldPreempt = { !handle.isVisible && handle.hasVisibleRequest },
    )

    /** Visible loads start the secondary only after the primary misses the short hedge window. */
    private suspend fun fetchRemoteHedged(
        primaryUrl: String,
        secondaryUrl: String,
        handle: ReaderLoadHandle,
    ): ReaderRemoteAttempt = remoteFetcher.fetchHedged(
        primaryUrl = primaryUrl,
        secondaryUrl = secondaryUrl,
        delayMillis = VISIBLE_HEDGE_DELAY_MILLIS,
        acquirePermit = { acquireNetworkPermit(handle) },
        shouldPreempt = { !handle.isVisible && handle.hasVisibleRequest },
    )

    private suspend fun acquireNetworkPermit(handle: ReaderLoadHandle): ReaderNetworkPermit =
        networkScheduler.acquire(
            isVisible = { handle.isVisible },
            hasVisibleRequest = { handle.hasVisibleRequest },
        )

    private suspend fun <T> withNetworkRequestPriority(
        handle: ReaderLoadHandle,
        block: suspend () -> T,
    ): T {
        val permit = acquireNetworkPermit(handle)
        return try {
            if (!handle.isVisible && handle.hasVisibleRequest) {
                throw ReaderBackgroundPreempted()
            }
            block()
        } finally {
            permit.close()
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

    private suspend fun <T> withMeasuredDecode(
        handle: ReaderLoadHandle,
        block: suspend () -> T,
    ): T {
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            withDecodePriority(handle, block)
        } finally {
            metrics.decodeFinished(SystemClock.elapsedRealtime() - startedAt)
        }
    }

    private fun cachedBitmap(cacheKey: BitmapCacheKey): Bitmap? =
        bitmapCache[cacheKey]?.takeUnless { it.isRecycled }

    private fun sourceInFlightKey(pageKey: ReaderPageKey): String =
        readerCacheKey(pageKey, "source")

    private fun canonicalPageKey(pageKey: ReaderPageKey): ReaderPageKey =
        pageKey.copy(sourceIdentity = readerLogicalSourceIdentity(pageKey.sourceIdentity))

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
        return if (readerPreferences.memoryOptEnabled.value) {
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
        if (!temporary) requestDiskTrim()
    }

    private suspend fun promoteSourceIfNeeded(
        source: ReaderSourceFile,
        forcePersistence: Boolean = false,
    ) {
        if ((!source.persistAfterValidation && !forcePersistence) || source.persistent) return
        try {
            var promoted = false
            sourceCacheMutex.withLock {
                if (source.generation != cacheGeneration.get()) return@withLock
                ensureDiskCacheDir()
                if (!source.cacheFile.isFile || source.cacheFile.length() !in 1..MAX_SOURCE_BYTES) {
                    source.file.copyTo(source.cacheFile, overwrite = true)
                }
                promoted = source.cacheFile.isFile &&
                    source.cacheFile.length() in 1..MAX_SOURCE_BYTES
            }
            if (promoted) requestDiskTrim()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Source promotion is best effort; the validated temp still serves this request.
        }
    }

    private suspend fun invalidateSource(
        pageKey: ReaderPageKey,
        source: ReaderSourceFile,
    ) {
        if (source.persistent) {
            sourceCacheMutex.withLock {
                source.cacheFile.delete()
            }
        }
        sourceRequests.invalidate(sourceInFlightKey(pageKey))
    }

    private fun shouldInvalidateSourceAfterDecodeFailure(error: Throwable): Boolean =
        error !is CancellationException && error !is OutOfMemoryError

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
        private const val NETWORK_READ_CHUNK_BYTES = 32 * 1024
        private const val MAX_SOURCE_BYTES = 40L * 1024L * 1024L
        private const val MAX_DISK_CACHE_BYTES = 256L * 1024L * 1024L
        private const val TARGET_DISK_CACHE_BYTES = 224L * 1024L * 1024L
        private const val PRIORITY_RETRY_DELAY_MILLIS = 24L
        private const val VISIBLE_HEDGE_DELAY_MILLIS = 100L
        private const val VISIBLE_LOAD_TIMEOUT_MILLIS = 20_000L

        private fun bitmapCacheBudgetBytes(memoryClassMb: Int): Long =
            (memoryClassMb.toLong() * 1024L * 1024L / 8L)
                .coerceIn(16L * 1024L * 1024L, 64L * 1024L * 1024L)
    }
}
