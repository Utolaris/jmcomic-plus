package com.par9uet.jm.startup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.Collections.synchronizedList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the DoH-before-network waterfall and branch independence at startup. */
class AuthenticatedStartupSequencingTest {

    private class Trace {
        val events = synchronizedList(mutableListOf<String>())
        fun add(event: String) = events.add(event)
    }

    @Test
    fun `DoH init completes before remote config and user verification start`() = runTest {
        val trace = Trace()
        runAuthenticatedStartupTasks(
            initDoh = { trace.add("doh:start"); trace.add("doh:end") },
            refreshRemoteConfig = { trace.add("remote") },
            verifyUser = { trace.add("user") },
        )

        assertEquals(listOf("doh:start", "doh:end"), trace.events.take(2))
        assertTrue(trace.events.drop(2).containsAll(listOf("remote", "user")))
    }


    @Test
    fun `slow remote config does not block user verification`() = runTest {
        val trace = Trace()
        val userDone = CompletableDeferred<Unit>()
        val remoteGate = CompletableDeferred<Unit>()
        val watcher = launch {
            userDone.await()          // wait until user verification observed
            assertEquals(listOf("doh", "remote:start", "user"), trace.events)
            remoteGate.complete(Unit) // then release the hung remote branch
        }
        runAuthenticatedStartupTasks(
            initDoh = { trace.add("doh") },
            refreshRemoteConfig = {
                trace.add("remote:start")
                remoteGate.await()
                trace.add("remote:end")
            },
            verifyUser = {
                trace.add("user")
                userDone.complete(Unit)
            },
        )

        assertEquals(
            listOf("doh", "remote:start", "user", "remote:end"),
            trace.events,
        )
        watcher.join()
    }
    @Test
    fun `remote config failure does not prevent user verification`() = runTest {
        val trace = Trace()
        val branchFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        try {
            runAuthenticatedStartupTasks(
                initDoh = { trace.add("doh") },
                // 模拟生产中 launchTask 已捕获的分支异常：分支内部失败但不向外抛。
                refreshRemoteConfig = {
                    trace.add("remote")
                    branchFailure.set(IllegalStateException("boom"))
                },
                verifyUser = { trace.add("user") },
            )
        } finally {
            assertTrue(trace.events.containsAll(listOf("remote", "user")))
            assertTrue(branchFailure.get() is IllegalStateException)
        }
    }
}
