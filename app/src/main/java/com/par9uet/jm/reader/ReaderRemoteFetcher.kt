package com.par9uet.jm.reader

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class ReaderBackgroundPreempted : Exception("后台图片请求让出网络槽位")

internal data class ReaderRemoteAttempt(
    val url: String,
    val file: File,
)

internal enum class ReaderRemoteCandidate {
    SINGLE,
    PRIMARY,
    SECONDARY,
}

internal interface ReaderRemoteObserver {
    fun onRequestStarted(url: String, candidate: ReaderRemoteCandidate, call: Call) = Unit
    fun onResponseHeaders(
        url: String,
        candidate: ReaderRemoteCandidate,
        elapsedMillis: Long,
        httpCode: Int,
    ) = Unit
    fun onRequestSucceeded(
        url: String,
        candidate: ReaderRemoteCandidate,
        timeToHeadersMillis: Long,
        bodyMillis: Long,
        totalMillis: Long,
    ) = Unit

    fun onRequestFailed(
        url: String,
        candidate: ReaderRemoteCandidate,
        totalMillis: Long,
        error: Throwable? = null,
    ) = Unit
    fun onRequestCanceled(
        url: String,
        candidate: ReaderRemoteCandidate,
        totalMillis: Long,
        preempted: Boolean,
    ) = Unit

    fun onHedgeSecondaryStarted() = Unit
    fun onHedgeWinner(primary: Boolean, url: String) = Unit
    fun onHedgeLoserCanceled() = Unit
}

/**
 * Opens hedged candidates only through response headers. The winner alone gets a temporary file
 * and reads a response body; every opened response owns its network permit until closed.
 */
internal class ReaderRemoteFetcher(
    private val client: OkHttpClient,
    private val createTemporary: suspend () -> File,
    private val discardTemporary: (File) -> Unit,
    private val validateTemporary: (File) -> Unit,
    private val maxSourceBytes: Long,
    private val readChunkBytes: Int,
    private val observer: ReaderRemoteObserver = object : ReaderRemoteObserver {},
    private val clockMillis: () -> Long = {
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
    },
) {
    suspend fun fetch(
        url: String,
        acquirePermit: suspend () -> ReaderNetworkPermit,
        shouldPreempt: () -> Boolean,
    ): Result<ReaderRemoteAttempt> = fetchCandidate(
        url = url,
        candidate = ReaderRemoteCandidate.SINGLE,
        acquirePermit = acquirePermit,
        shouldPreempt = shouldPreempt,
    )

    suspend fun fetchHedged(
        primaryUrl: String,
        secondaryUrl: String,
        delayMillis: Long,
        acquirePermit: suspend () -> ReaderNetworkPermit,
        shouldPreempt: () -> Boolean,
    ): ReaderRemoteAttempt = supervisorScope {
        val primary = async(start = CoroutineStart.DEFAULT) {
            openCandidate(
                url = primaryUrl,
                candidate = ReaderRemoteCandidate.PRIMARY,
                acquirePermit = acquirePermit,
                shouldPreempt = shouldPreempt,
            )
        }
        val earlyPrimary = if (delayMillis > 0L) {
            withTimeoutOrNull(delayMillis) { primary.await() }
        } else {
            null
        }
        if (earlyPrimary != null) {
            earlyPrimary.getOrNull()?.let { opened ->
                val primaryResult = readOpened(opened, shouldPreempt)
                primaryResult.getOrNull()?.let { winner ->
                    observer.onHedgeWinner(primary = true, url = winner.url)
                    return@supervisorScope winner
                }
                val secondary = fetchCandidate(
                    url = secondaryUrl,
                    candidate = ReaderRemoteCandidate.SECONDARY,
                    acquirePermit = acquirePermit,
                    shouldPreempt = shouldPreempt,
                )
                secondary.getOrNull()?.let { winner ->
                    observer.onHedgeWinner(primary = false, url = winner.url)
                    return@supervisorScope winner
                }
                throw secondary.exceptionOrNull()
                    ?: primaryResult.exceptionOrNull()
                    ?: ReaderImageException("CDN 请求失败")
            }
            val secondary = fetchCandidate(
                url = secondaryUrl,
                candidate = ReaderRemoteCandidate.SECONDARY,
                acquirePermit = acquirePermit,
                shouldPreempt = shouldPreempt,
            )
            secondary.getOrNull()?.let { winner ->
                observer.onHedgeWinner(primary = false, url = winner.url)
                return@supervisorScope winner
            }
            throw secondary.exceptionOrNull()
                ?: earlyPrimary.exceptionOrNull()
                ?: ReaderImageException("CDN 请求失败")
        }

        val secondary = async(start = CoroutineStart.DEFAULT) {
            openCandidate(
                url = secondaryUrl,
                candidate = ReaderRemoteCandidate.SECONDARY,
                acquirePermit = acquirePermit,
                shouldPreempt = shouldPreempt,
            )
        }
        val first = select<OpenedSelection> {
            primary.onAwait { OpenedSelection(it, primary = true, other = secondary) }
            secondary.onAwait { OpenedSelection(it, primary = false, other = primary) }
        }
        first.result.getOrNull()?.let { opened ->
            cancelOpenedLoser(first.other)
            val winner = readOpened(opened, shouldPreempt).getOrThrow()
            observer.onHedgeWinner(primary = first.primary, url = winner.url)
            return@supervisorScope winner
        }

        val secondResult = first.other.await()
        secondResult.getOrNull()?.let { opened ->
            val winner = readOpened(opened, shouldPreempt).getOrThrow()
            observer.onHedgeWinner(primary = !first.primary, url = winner.url)
            return@supervisorScope winner
        }
        throw secondResult.exceptionOrNull()
            ?: first.result.exceptionOrNull()
            ?: ReaderImageException("CDN 请求失败")
    }

    private suspend fun fetchCandidate(
        url: String,
        candidate: ReaderRemoteCandidate,
        acquirePermit: suspend () -> ReaderNetworkPermit,
        shouldPreempt: () -> Boolean,
    ): Result<ReaderRemoteAttempt> {
        val opened = openCandidate(url, candidate, acquirePermit, shouldPreempt)
        return opened.getOrNull()?.let { readOpened(it, shouldPreempt) } ?: Result.failure(
            opened.exceptionOrNull() ?: ReaderImageException("CDN 请求失败"),
        )
    }

    private suspend fun openCandidate(
        url: String,
        candidate: ReaderRemoteCandidate,
        acquirePermit: suspend () -> ReaderNetworkPermit,
        shouldPreempt: () -> Boolean,
    ): Result<ReaderOpenedRemote> {
        var permit: ReaderNetworkPermit? = null
        var call: Call? = null
        var response: Response? = null
        var startedAt = clockMillis()
        return try {
            permit = acquirePermit()
            if (shouldPreempt()) throw ReaderBackgroundPreempted()
            startedAt = clockMillis()
            call = client.newCall(imageRequest(url))
            response = awaitResponse(call, shouldPreempt) {
                observer.onRequestStarted(url, candidate, call)
                if (candidate == ReaderRemoteCandidate.SECONDARY) {
                    observer.onHedgeSecondaryStarted()
                }
            }
            val headersMillis = (clockMillis() - startedAt).coerceAtLeast(0L)
            observer.onResponseHeaders(url, candidate, headersMillis, response.code)
            if (!response.isSuccessful) {
                throw ReaderImageException("HTTP ${response.code}", httpCode = response.code)
            }
            val body = response.body ?: throw ReaderImageException("图片响应为空")
            val contentLength = body.contentLength()
            if (contentLength == 0L) throw ReaderImageException("图片响应为空")
            if (contentLength > maxSourceBytes) {
                throw ReaderImageException("图片过大")
            }
            val opened = ReaderOpenedRemote(
                url = url,
                candidate = candidate,
                call = call,
                response = response,
                permit = permit,
                startedAtMillis = startedAt,
                timeToHeadersMillis = headersMillis,
            )
            response = null
            permit = null
            Result.success(opened)
        } catch (error: CancellationException) {
            call?.cancel()
            throw error
        } catch (error: ReaderBackgroundPreempted) {
            call?.cancel()
            observer.onRequestCanceled(
                url,
                candidate,
                (clockMillis() - startedAt).coerceAtLeast(0L),
                preempted = true,
            )
            throw error
        } catch (error: OutOfMemoryError) {
            call?.cancel()
            throw error
        } catch (error: Throwable) {
            call?.cancel()
            observer.onRequestFailed(
                url,
                candidate,
                (clockMillis() - startedAt).coerceAtLeast(0L),
                error,
            )
            Result.failure(error)
        } finally {
            response?.close()
            permit?.close()
        }
    }

    private suspend fun awaitResponse(
        call: Call,
        shouldPreempt: () -> Boolean,
        onEnqueued: () -> Unit,
    ): Response {
        val completed = CompletableDeferred<Response>()
        var delivered = false
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    completed.completeExceptionally(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!completed.complete(response)) response.close()
                }
            })
            onEnqueued()
            while (true) {
                if (shouldPreempt()) throw ReaderBackgroundPreempted()
                val response = withTimeoutOrNull(PREEMPT_POLL_MILLIS) { completed.await() }
                if (response != null) {
                    delivered = true
                    return response
                }
            }
        } finally {
            if (!delivered) {
                call.cancel()
                completed.cancel()
            }
        }
    }

    private suspend fun readOpened(
        opened: ReaderOpenedRemote,
        shouldPreempt: () -> Boolean,
    ): Result<ReaderRemoteAttempt> {
        val temporary = try {
            createTemporary()
        } catch (error: CancellationException) {
            opened.cancelAndClose()
            throw error
        } catch (error: OutOfMemoryError) {
            opened.cancelAndClose()
            throw error
        } catch (error: Throwable) {
            opened.close()
            observer.onRequestFailed(
                opened.url,
                opened.candidate,
                (clockMillis() - opened.startedAtMillis).coerceAtLeast(0L),
                error,
            )
            return Result.failure(error)
        }
        val bodyStartedAt = clockMillis()
        val preempted = AtomicBoolean(false)
        return try {
            coroutineScope {
                val preemptionWatcher = launch {
                    while (isActive) {
                        if (shouldPreempt()) {
                            preempted.set(true)
                            opened.call.cancel()
                            break
                        }
                        delay(PREEMPT_POLL_MILLIS)
                    }
                }
                try {
                    writeResponseBody(opened, temporary, shouldPreempt, preempted)
                } finally {
                    preemptionWatcher.cancelAndJoin()
                }
            }
            validateTemporary(temporary)
            val finishedAt = clockMillis()
            observer.onRequestSucceeded(
                url = opened.url,
                candidate = opened.candidate,
                timeToHeadersMillis = opened.timeToHeadersMillis,
                bodyMillis = (finishedAt - bodyStartedAt).coerceAtLeast(0L),
                totalMillis = (finishedAt - opened.startedAtMillis).coerceAtLeast(0L),
            )
            Result.success(ReaderRemoteAttempt(opened.url, temporary))
        } catch (error: CancellationException) {
            opened.call.cancel()
            discardTemporary(temporary)
            observer.onRequestCanceled(
                opened.url,
                opened.candidate,
                (clockMillis() - opened.startedAtMillis).coerceAtLeast(0L),
                preempted = false,
            )
            throw error
        } catch (error: ReaderBackgroundPreempted) {
            opened.call.cancel()
            discardTemporary(temporary)
            observer.onRequestCanceled(
                opened.url,
                opened.candidate,
                (clockMillis() - opened.startedAtMillis).coerceAtLeast(0L),
                preempted = true,
            )
            throw error
        } catch (error: OutOfMemoryError) {
            opened.call.cancel()
            discardTemporary(temporary)
            throw error
        } catch (error: Throwable) {
            opened.call.cancel()
            discardTemporary(temporary)
            if (preempted.get()) {
                observer.onRequestCanceled(
                    opened.url,
                    opened.candidate,
                    (clockMillis() - opened.startedAtMillis).coerceAtLeast(0L),
                    preempted = true,
                )
                throw ReaderBackgroundPreempted()
            }
            observer.onRequestFailed(
                opened.url,
                opened.candidate,
                (clockMillis() - opened.startedAtMillis).coerceAtLeast(0L),
                error,
            )
            Result.failure(error)
        } finally {
            opened.close()
        }
    }

    private suspend fun writeResponseBody(
        opened: ReaderOpenedRemote,
        target: File,
        shouldPreempt: () -> Boolean,
        preempted: AtomicBoolean,
    ) {
        val buffer = ByteArray(readChunkBytes)
        var total = 0L
        val body = opened.response.body ?: throw ReaderImageException("图片响应为空")
        FileOutputStream(target).use { output ->
            body.byteStream().use { input ->
                while (true) {
                    if (shouldPreempt()) {
                        preempted.set(true)
                        opened.call.cancel()
                        throw ReaderBackgroundPreempted()
                    }
                    val read = runInterruptible(Dispatchers.IO) { input.read(buffer) }
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > maxSourceBytes) throw ReaderImageException("图片过大")
                    output.write(buffer, 0, read)
                }
            }
        }
        if (total == 0L) throw ReaderImageException("图片响应为空")
    }

    private suspend fun cancelOpenedLoser(
        loser: Deferred<Result<ReaderOpenedRemote>>,
    ) {
        val wasActive = !loser.isCompleted
        loser.cancel()
        val discarded = try {
            loser.await().getOrNull()
        } catch (error: CancellationException) {
            if (!currentCoroutineContext().isActive) throw error
            null
        }
        discarded?.cancelAndClose()
        if (wasActive || discarded != null) observer.onHedgeLoserCanceled()
    }

    private fun imageRequest(url: String): Request = Request.Builder()
        .url(url)
        .get()
        .header("Accept", "image/webp,image/*,*/*;q=0.8")
        .header("X-Requested-With", "com.JMComic3.app")
        .header("User-Agent", USER_AGENT)
        .header("Referer", REFERER)
        .build()

    private data class OpenedSelection(
        val result: Result<ReaderOpenedRemote>,
        val primary: Boolean,
        val other: Deferred<Result<ReaderOpenedRemote>>,
    )

    private class ReaderOpenedRemote(
        val url: String,
        val candidate: ReaderRemoteCandidate,
        val call: Call,
        val response: Response,
        private val permit: ReaderNetworkPermit,
        val startedAtMillis: Long,
        val timeToHeadersMillis: Long,
    ) {
        private val closed = AtomicBoolean(false)

        fun cancelAndClose() {
            call.cancel()
            close()
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            response.close()
            permit.close()
        }
    }

    private companion object {
        private const val PREEMPT_POLL_MILLIS = 12L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; Mobile) AppleWebKit/537.36 Chrome/91.0 Safari/537.36"
        private const val REFERER = "https://18comic.vip"
    }
}
