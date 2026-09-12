package com.par9uet.jm.update

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadJobGateTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private val activeWriters = AtomicInteger(0)
    private val maxConcurrentWriters = AtomicInteger(0)
    private val events = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun enterWriter(name: String) {
        events += "$name-enter"
        val current = activeWriters.incrementAndGet()
        maxConcurrentWriters.updateAndGet { known -> maxOf(known, current) }
    }

    private fun exitWriter(name: String) {
        events += "$name-exit"
        activeWriters.decrementAndGet()
    }

    private suspend fun awaitEvent(event: String) {
        withTimeout(5_000) {
            while (!events.contains(event)) delay(5)
        }
    }

    private suspend fun awaitDone(signal: CompletableDeferred<Unit>) {
        withTimeout(5_000) { signal.await() }
    }

    /** Writer that holds the mutex until its job is cancelled (non-cooperative body). */
    private fun startHoldingWriter(gate: UpdateDownloadJobGate, name: String) {
        gate.start(scope) {
            enterWriter(name)
            try {
                awaitCancellation()
            } finally {
                exitWriter(name)
            }
        }
    }

    @Test
    fun `start A then start B never runs two writers and B starts only after A exits`() = runBlocking {
        val gate = UpdateDownloadJobGate()
        val bDone = CompletableDeferred<Unit>()

        startHoldingWriter(gate, "A")
        awaitEvent("A-enter")

        gate.start(scope) {
            enterWriter("B")
            assertTrue("A must exit before B enters", events.indexOf("A-exit") >= 0)
            exitWriter("B")
            bDone.complete(Unit)
        }

        awaitDone(bDone)
        assertEquals(1, maxConcurrentWriters.get())
        assertTrue(events.indexOf("A-exit") < events.indexOf("B-enter"))
    }

    @Test
    fun `start A then cancel then start B waits for A to leave the writer section`() = runBlocking {
        val gate = UpdateDownloadJobGate()
        val bDone = CompletableDeferred<Unit>()

        startHoldingWriter(gate, "A")
        awaitEvent("A-enter")

        gate.cancel()
        gate.start(scope) {
            enterWriter("B")
            assertTrue(events.indexOf("A-exit") >= 0)
            exitWriter("B")
            bDone.complete(Unit)
        }

        awaitDone(bDone)
        assertEquals(1, maxConcurrentWriters.get())
        assertTrue(events.indexOf("A-exit") < events.indexOf("B-enter"))
    }

    @Test
    fun `A then B then C never overlaps writers`() = runBlocking {
        val gate = UpdateDownloadJobGate()
        val cDone = CompletableDeferred<Unit>()

        startHoldingWriter(gate, "A")
        awaitEvent("A-enter")
        // Replacements: each start cancels the previous holder and waits on the mutex.
        startHoldingWriter(gate, "B")
        gate.start(scope) {
            enterWriter("C")
            exitWriter("C")
            cDone.complete(Unit)
        }

        awaitDone(cDone)
        assertEquals(1, maxConcurrentWriters.get())
        assertTrue(events.indexOf("A-exit") >= 0)
        assertTrue(events.indexOf("A-exit") < events.indexOf("C-enter"))
        // No two enter events without an exit in between.
        var open = 0
        events.forEach { event ->
            if (event.endsWith("-enter")) {
                open++
                assertTrue("overlapping writers at $event", open == 1)
            } else {
                open--
            }
        }
    }

    @Test
    fun `pause while replacement waits is still paused when the new writer starts`() = runBlocking {
        val gate = UpdateDownloadJobGate()
        val bObservedPause = CompletableDeferred<Boolean>()
        val bDone = CompletableDeferred<Unit>()

        startHoldingWriter(gate, "A")
        awaitEvent("A-enter")

        // Pause before the replacement job can take the mutex (A still holds it).
        gate.paused = true
        gate.start(scope) {
            enterWriter("B")
            bObservedPause.complete(gate.paused)
            exitWriter("B")
            bDone.complete(Unit)
        }

        awaitDone(bDone)
        assertEquals(true, withTimeout(1_000) { bObservedPause.await() })
        assertEquals(1, maxConcurrentWriters.get())
    }

    @Test
    fun `cancel near completion lets the next start write with canceled cleared`() = runBlocking {
        val gate = UpdateDownloadJobGate()
        val aFinishedWrite = CompletableDeferred<Unit>()
        val aSawCancel = CompletableDeferred<Boolean>()
        val bDone = CompletableDeferred<Unit>()

        gate.start(scope) {
            enterWriter("A")
            try {
                aFinishedWrite.complete(Unit)
                // Finish body, then observe cancel before any Completed publish.
                delay(300)
                aSawCancel.complete(gate.canceled)
                if (gate.canceled) {
                    return@start
                }
            } catch (cancelled: CancellationException) {
                aSawCancel.complete(true)
                throw cancelled
            } finally {
                exitWriter("A")
            }
        }
        awaitDone(aFinishedWrite)
        gate.cancel()

        gate.start(scope) {
            enterWriter("B")
            assertFalse("new writer must not inherit cancel from the replaced one", gate.canceled)
            exitWriter("B")
            bDone.complete(Unit)
        }
        awaitDone(bDone)
        assertEquals(1, maxConcurrentWriters.get())
        assertTrue(aSawCancel.await())
    }
}
