package com.par9uet.jm.reader

import android.os.SystemClock
import com.par9uet.jm.image.JmImageHostHealthManager
import com.par9uet.jm.image.JmImageHostSnapshot
import com.par9uet.jm.image.isJmImagePathAllowed
import com.par9uet.jm.image.jmImageHostLatencyEwma
import com.par9uet.jm.image.orderJmImageHosts
import com.par9uet.jm.image.selectJmPreferredHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

internal typealias ReaderImageHostSnapshot = JmImageHostSnapshot

/** Reader-specific URL rewriting and connection warming over the shared JM host health state. */
internal class ReaderImageHostManager(
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
    private val healthManager: JmImageHostHealthManager,
) {
    private data class WarmState(@Volatile var warmedAtMillis: Long = 0L)

    private val warmStates = ConcurrentHashMap<String, WarmState>()
    private val networkObserver: Job = scope.launch(Dispatchers.IO) {
        var generation = healthManager.networkGeneration.value
        healthManager.networkGeneration.collect { nextGeneration ->
            if (nextGeneration != generation) {
                warmStates.clear()
                httpClient.connectionPool.evictAll()
                generation = nextGeneration
            }
        }
    }

    fun orderedImageUrls(originUrl: String): List<String> {
        val url = originUrl.toHttpUrlOrNull() ?: return listOf(originUrl)
        if (
            url.scheme != "https" ||
            url.username.isNotEmpty() ||
            url.password.isNotEmpty() ||
            !canMirror(url.host, url.encodedPath)
        ) {
            return listOf(originUrl)
        }
        return healthManager.orderedHosts(url.host).map { host ->
            if (host == url.host) originUrl else replaceReaderImageHost(originUrl, host) ?: originUrl
        }.distinct()
    }

    fun preferredLatencyMillis(): Long? = healthManager.preferredLatencyMillis()

    /** Reader 的 TTFB（响应头到达耗时）样本，进入延迟 EWMA。 */
    fun recordLatencySample(url: String, ttfbMillis: Long) {
        healthManager.recordLatencySample(url, ttfbMillis)
    }

    /** 主机/网络级失败才调用；资源级失败（404/内容断言等）不全局惩罚 CDN。 */
    fun recordHostFailure(url: String) {
        healthManager.recordHostFailure(url)
    }

    fun warmImageConnections(originUrl: String) {
        val parsed = originUrl.toHttpUrlOrNull() ?: return
        if (!canMirror(parsed.host, parsed.encodedPath)) return
        healthManager.orderedHosts(parsed.host).take(WARMUP_HOST_COUNT).forEach { host ->
            val state = warmStates.getOrPut(host) { WarmState() }
            val now = SystemClock.elapsedRealtime()
            if (now - state.warmedAtMillis < WARMUP_REUSE_MILLIS) return@forEach
            state.warmedAtMillis = now
            val warmupUrl = parsed.newBuilder().host(host).build().toString()
            scope.launch(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(warmupUrl)
                    .head()
                    .header("X-Requested-With", "com.JMComic3.app")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", REFERER)
                    .build()
                val call = httpClient.newCall(request)
                try {
                    runInterruptible(Dispatchers.IO) { call.execute().use { } }
                } catch (error: CancellationException) {
                    call.cancel()
                    state.warmedAtMillis = 0L
                    throw error
                } catch (_: Exception) {
                    state.warmedAtMillis = 0L
                }
            }
        }
    }

    fun snapshot(): ReaderImageHostSnapshot = healthManager.snapshot()

    fun close() {
        networkObserver.cancel()
        warmStates.clear()
    }

    private fun canMirror(host: String, path: String): Boolean =
        healthManager.containsHost(host) && isReaderImageMirrorPathAllowed(path)

    private companion object {
        private const val WARMUP_REUSE_MILLIS = 4L * 60L * 1_000L
        private const val WARMUP_HOST_COUNT = 3
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; Mobile) AppleWebKit/537.36 Chrome/91.0 Safari/537.36"
        private const val REFERER = "https://18comic.vip"
    }
}

internal fun orderReaderImageHosts(
    candidates: List<String>,
    originHost: String,
    preferredHost: String?,
    latencyMillis: Map<String, Long?>,
    failedAtMillis: Map<String, Long>,
    nowMillis: Long,
    cooldownMillis: Long,
): List<String> = orderJmImageHosts(
    candidates = candidates,
    originHost = originHost,
    preferredHost = preferredHost,
    latencyMillis = latencyMillis,
    failedAtMillis = failedAtMillis,
    nowMillis = nowMillis,
    cooldownMillis = cooldownMillis,
)

internal fun replaceReaderImageHost(originalUrl: String, host: String): String? {
    val url = originalUrl.toHttpUrlOrNull() ?: return null
    if (url.scheme != "https" || host.isBlank()) return null
    return runCatching { url.newBuilder().host(host).build().toString() }.getOrNull()
}

internal fun isReaderImageMirrorAllowed(
    host: String,
    path: String,
    allowlistedHosts: Collection<String>,
): Boolean = host in allowlistedHosts && isReaderImageMirrorPathAllowed(path)

internal fun isReaderImageMirrorPathAllowed(path: String): Boolean =
    isJmImagePathAllowed(path)

internal fun readerHostLatencyEwma(previous: Long?, current: Long): Long =
    jmImageHostLatencyEwma(previous, current)

internal fun selectReaderPreferredHost(
    candidates: Collection<String>,
    latencyMillis: Map<String, Long?>,
    failedAtMillis: Map<String, Long>,
    nowMillis: Long,
    cooldownMillis: Long,
): String? = selectJmPreferredHost(
    candidates = candidates,
    latencyMillis = latencyMillis,
    failedAtMillis = failedAtMillis,
    nowMillis = nowMillis,
    cooldownMillis = cooldownMillis,
)
