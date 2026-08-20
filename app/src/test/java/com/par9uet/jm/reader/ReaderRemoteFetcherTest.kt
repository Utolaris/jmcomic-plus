package com.par9uet.jm.reader

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderRemoteFetcherTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() {
        servers.forEach { server -> runCatching { server.shutdown() } }
    }

    @Test
    fun fastHeadersAndSlowBodyDoNotStartSecondary() = runBlocking {
        val primary = server(
            MockResponse()
                .setResponseCode(200)
                .setHeadersDelay(20L, TimeUnit.MILLISECONDS)
                .setBodyDelay(500L, TimeUnit.MILLISECONDS)
                .setBody("PRIMARY"),
        )
        val secondary = server(MockResponse().setResponseCode(200).setBody("SECONDARY"))
        val filesCreated = AtomicInteger()
        val secondaryStarted = AtomicInteger()
        val fetcher = fetcher(
            observer = object : ReaderRemoteObserver {
                override fun onHedgeSecondaryStarted() {
                    secondaryStarted.incrementAndGet()
                }
            },
            filesCreated = filesCreated,
        )

        val result = withTimeout(3_000L) {
            fetcher.fetchHedged(
                primaryUrl = primary.url("/media/photos/1/1.webp").toString(),
                secondaryUrl = secondary.url("/media/photos/1/1.webp").toString(),
                delayMillis = 100L,
                acquirePermit = ::unboundedPermit,
                shouldPreempt = { false },
            )
        }

        assertEquals("PRIMARY", result.file.readText())
        assertEquals(1, primary.requestCount)
        assertEquals(0, secondary.requestCount)
        assertEquals(1, filesCreated.get())
        assertEquals(0, secondaryStarted.get())
    }

    @Test
    fun slowPrimaryHeadersStartSecondaryAndCancelPrimaryCall() = runBlocking {
        val primary = server(
            MockResponse()
                .setResponseCode(200)
                .setHeadersDelay(600L, TimeUnit.MILLISECONDS)
                .setBody("PRIMARY"),
        )
        val secondary = server(MockResponse().setResponseCode(200).setBody("SECONDARY"))
        val calls = ConcurrentHashMap<String, Call>()
        val secondaryStarted = AtomicInteger()
        val fetcher = fetcher(
            observer = object : ReaderRemoteObserver {
                override fun onRequestStarted(
                    url: String,
                    candidate: ReaderRemoteCandidate,
                    call: Call,
                ) {
                    calls[url] = call
                }

                override fun onHedgeSecondaryStarted() {
                    secondaryStarted.incrementAndGet()
                }
            },
        )
        val primaryUrl = primary.url("/media/photos/2/1.webp").toString()

        val result = withTimeout(3_000L) {
            fetcher.fetchHedged(
                primaryUrl = primaryUrl,
                secondaryUrl = secondary.url("/media/photos/2/1.webp").toString(),
                delayMillis = 100L,
                acquirePermit = ::unboundedPermit,
                shouldPreempt = { false },
            )
        }

        assertEquals("SECONDARY", result.file.readText())
        assertEquals(1, secondary.requestCount)
        assertEquals(1, secondaryStarted.get())
        assertTrue(calls.getValue(primaryUrl).isCanceled())
    }

    @Test
    fun immediateHttpFailureFallsBackWithoutWaitingForHedgeDelay() = runBlocking {
        val primary = server(MockResponse().setResponseCode(500))
        val secondary = server(MockResponse().setResponseCode(200).setBody("SECONDARY"))
        val fetcher = fetcher()
        val startedAt = System.nanoTime()

        val result = withTimeout(3_000L) {
            fetcher.fetchHedged(
                primaryUrl = primary.url("/media/photos/3/1.webp").toString(),
                secondaryUrl = secondary.url("/media/photos/3/1.webp").toString(),
                delayMillis = 500L,
                acquirePermit = ::unboundedPermit,
                shouldPreempt = { false },
            )
        }
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertEquals("SECONDARY", result.file.readText())
        assertEquals(1, primary.requestCount)
        assertEquals(1, secondary.requestCount)
        assertTrue("fallback waited for the full hedge delay: ${elapsedMillis}ms", elapsedMillis < 400L)
    }

    @Test
    fun hedgeCreatesOnlyWinnerFileAndCancelsLoser() = runBlocking {
        val primary = server(
            MockResponse()
                .setResponseCode(200)
                .setHeadersDelay(500L, TimeUnit.MILLISECONDS)
                .setBody("PRIMARY"),
        )
        val secondary = server(MockResponse().setResponseCode(200).setBody("SECONDARY"))
        val calls = ConcurrentHashMap<String, Call>()
        val filesCreated = AtomicInteger()
        val loserCanceled = AtomicInteger()
        val observer = object : ReaderRemoteObserver {
            override fun onRequestStarted(
                url: String,
                candidate: ReaderRemoteCandidate,
                call: Call,
            ) {
                calls[url] = call
            }

            override fun onHedgeLoserCanceled() {
                loserCanceled.incrementAndGet()
            }
        }
        val fetcher = fetcher(observer, filesCreated)
        val primaryUrl = primary.url("/media/photos/4/1.webp").toString()

        val result = fetcher.fetchHedged(
            primaryUrl = primaryUrl,
            secondaryUrl = secondary.url("/media/photos/4/1.webp").toString(),
            delayMillis = 50L,
            acquirePermit = ::unboundedPermit,
            shouldPreempt = { false },
        )

        assertEquals("SECONDARY", result.file.readText())
        assertEquals(1, filesCreated.get())
        assertEquals(1, temporaryFolder.root.listFiles().orEmpty().count { it.isFile })
        assertEquals(1, loserCanceled.get())
        assertTrue(calls.getValue(primaryUrl).isCanceled())
    }

    @Test
    fun backgroundPreemptionReleasesSlotForVisibleHedge() = runBlocking {
        val background = server(
            MockResponse()
                .setResponseCode(200)
                .setBody("x".repeat(256 * 1024))
                .throttleBody(1_024L, 100L, TimeUnit.MILLISECONDS),
        )
        val primary = server(
            MockResponse()
                .setResponseCode(200)
                .setHeadersDelay(600L, TimeUnit.MILLISECONDS)
                .setBody("PRIMARY"),
        )
        val secondary = server(MockResponse().setResponseCode(200).setBody("SECONDARY"))
        val scheduler = ReaderNetworkScheduler(totalConcurrency = 2, initialBackgroundConcurrency = 1)
        val hasVisible = AtomicBoolean(false)
        val calls = ConcurrentHashMap<String, Call>()
        val filesCreated = AtomicInteger()
        val preempted = CompletableDeferred<Unit>()
        val fetcher = fetcher(
            observer = callCapturingObserver(calls),
            filesCreated = filesCreated,
        )
        val backgroundUrl = background.url("/media/photos/background.webp").toString()
        val backgroundJob = async(Dispatchers.Default) {
            try {
                fetcher.fetch(
                    url = backgroundUrl,
                    acquirePermit = {
                        scheduler.acquire(
                            isVisible = { false },
                            hasVisibleRequest = hasVisible::get,
                        )
                    },
                    shouldPreempt = hasVisible::get,
                )
            } catch (_: ReaderBackgroundPreempted) {
                preempted.complete(Unit)
            }
        }
        assertTrue(background.takeRequest(1L, TimeUnit.SECONDS) != null)
        withTimeout(1_000L) {
            while (filesCreated.get() < 1) delay(5L)
        }

        hasVisible.set(true)
        val result = withTimeout(3_000L) {
            fetcher.fetchHedged(
                primaryUrl = primary.url("/media/photos/visible.webp").toString(),
                secondaryUrl = secondary.url("/media/photos/visible.webp").toString(),
                delayMillis = 100L,
                acquirePermit = {
                    scheduler.acquire(
                        isVisible = { true },
                        hasVisibleRequest = hasVisible::get,
                    )
                },
                shouldPreempt = { false },
            )
        }

        assertEquals("SECONDARY", result.file.readText())
        withTimeout(1_000L) { preempted.await() }
        backgroundJob.await()
        assertEquals(1, secondary.requestCount)
        assertTrue(calls.getValue(backgroundUrl).isCanceled())
        assertEquals(2, filesCreated.get())
        assertEquals(1, temporaryFolder.root.listFiles().orEmpty().count { it.isFile })
    }

    @Test
    fun sourceRegistryDeduplicatesPrefetchVisibleAndDownloadConsumers() = runBlocking {
        val server = server(
            MockResponse()
                .setResponseCode(200)
                .setHeadersDelay(40L, TimeUnit.MILLISECONDS)
                .setBodyDelay(200L, TimeUnit.MILLISECONDS)
                .setBody("SOURCE"),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tracker = ReaderVisibleRequestTracker()
        val registry = ReaderInFlightRegistry<String, File>(scope, visibleRequestTracker = tracker)
        val scheduler = ReaderNetworkScheduler(totalConcurrency = 2, initialBackgroundConcurrency = 1)
        val loaderCalls = AtomicInteger()
        val fetcher = fetcher()
        val url = server.url("/media/photos/shared.webp").toString()

        suspend fun load(priority: ReaderRequestPriority): File = registry.request("logical-source", priority) { handle ->
            loaderCalls.incrementAndGet()
            fetcher.fetch(
                url = url,
                acquirePermit = {
                    scheduler.acquire(
                        isVisible = { handle.isVisible },
                        hasVisibleRequest = { handle.hasVisibleRequest },
                    )
                },
                shouldPreempt = { !handle.isVisible && handle.hasVisibleRequest },
            ).getOrThrow().file
        }

        val prefetch = async(Dispatchers.Default) { load(ReaderRequestPriority.PREFETCH) }
        assertTrue(server.takeRequest(1L, TimeUnit.SECONDS) != null)
        val visible = async { load(ReaderRequestPriority.VISIBLE) }
        val download = async { load(ReaderRequestPriority.BACKGROUND) }

        val first = prefetch.await()
        assertSame(first, visible.await())
        assertSame(first, download.await())
        assertEquals(1, loaderCalls.get())
        assertEquals(1, server.requestCount)
        scope.cancel()
    }

    private fun server(response: MockResponse): MockWebServer = MockWebServer().also { server ->
        server.enqueue(response)
        server.start()
        servers += server
    }

    private fun fetcher(
        observer: ReaderRemoteObserver = object : ReaderRemoteObserver {},
        filesCreated: AtomicInteger = AtomicInteger(),
    ): ReaderRemoteFetcher = ReaderRemoteFetcher(
        client = OkHttpClient.Builder()
            .connectTimeout(2L, TimeUnit.SECONDS)
            .readTimeout(2L, TimeUnit.SECONDS)
            .callTimeout(3L, TimeUnit.SECONDS)
            .build(),
        createTemporary = {
            File(
                temporaryFolder.root,
                "source-${filesCreated.incrementAndGet()}.tmp",
            ).apply { createNewFile() }
        },
        discardTemporary = File::delete,
        validateTemporary = { file -> check(file.isFile && file.length() > 0L) },
        maxSourceBytes = 2L * 1024L * 1024L,
        readChunkBytes = 1_024,
        observer = observer,
    )

    private fun callCapturingObserver(calls: ConcurrentHashMap<String, Call>) =
        object : ReaderRemoteObserver {
            override fun onRequestStarted(
                url: String,
                candidate: ReaderRemoteCandidate,
                call: Call,
            ) {
                calls[url] = call
            }
        }

    private suspend fun unboundedPermit(): ReaderNetworkPermit = ReaderNetworkPermit {}
}
