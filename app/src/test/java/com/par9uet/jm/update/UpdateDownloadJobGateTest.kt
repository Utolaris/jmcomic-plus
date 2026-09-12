package com.par9uet.jm.update

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule

class UpdateDownloadJobGateTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun awaitWithTimeout(block: suspend () -> Unit) {
        withTimeout(5_000) { block() }
    }

    @Test
    fun `start A then start B stops A and B completes without A overwriting B file`() = runBlocking {
        val gate = UpdateDownloadJobGate()
        val sharedPath = File(temporaryFolder.root, "update.apk")
        val aStarted = CompletableDeferred<Unit>()
        val bCompleted = CompletableDeferred<Unit>()
        val aHold = CompletableDeferred<Unit>()

        gate.start(scope) {
            aStarted.complete(Unit)
            try {
                // Mimic a long download that checks cancel before finishing.
                aHold.await()
                sharedPath.writeText("A-COMPLETE")
            } catch (cancelled: CancellationException) {
                sharedPath.delete()
                throw cancelled
            }
        }
        awaitWithTimeout { aStarted.await() }

        gate.start(scope) {
            // Runs only after A has fully ended (cancelAndJoin).
            sharedPath.writeText("B-COMPLETE")
            bCompleted.complete(Unit)
        }

        awaitWithTimeout { bCompleted.await() }
        assertEquals("B-COMPLETE", sharedPath.readText())
        // A never completed its write after B started.
        assertFalse(sharedPath.readText().contains("A-COMPLETE"))
    }

    @Test
    fun `start A then cancel then start B completes`() = runBlocking {
        val gate = UpdateDownloadJobGate()
        val path = File(temporaryFolder.root, "update.apk")
        val aStarted = CompletableDeferred<Unit>()
        val bCompleted = CompletableDeferred<Unit>()

        gate.start(scope) {
            aStarted.complete(Unit)
            try {
                while (true) {
                    delay(50)
                    if (gate.canceled) {
                        path.delete()
                        return@start
                    }
                }
            } catch (cancelled: CancellationException) {
                path.delete()
                throw cancelled
            }
        }
        awaitWithTimeout { aStarted.await() }

        gate.cancel()
        assertFalse(path.exists())

        gate.start(scope) {
            path.writeText("B-AFTER-CANCEL")
            bCompleted.complete(Unit)
        }
        awaitWithTimeout { bCompleted.await() }
        assertEquals("B-AFTER-CANCEL", path.readText())
        assertFalse(gate.canceled)
    }

    @Test
    fun `new start after previous completed still runs the new download`() = runBlocking {
        val gate = UpdateDownloadJobGate()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()

        gate.start(scope) {
            writes += "A"
            first.complete(Unit)
        }
        awaitWithTimeout { first.await() }

        gate.start(scope) {
            writes += "B"
            second.complete(Unit)
        }
        awaitWithTimeout { second.await() }
        assertEquals(listOf("A", "B"), writes)
    }
}
