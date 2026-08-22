package com.par9uet.jm.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JmImageHostHealthStoreTest {
    @Test
    fun healthyHostOutranksCoolingHost() {
        val clock = MutableClock(10_000L)
        val store = store(clock)
        store.recordLatencySample("a.example", 80L)
        store.recordLatencySample("b.example", 20L)
        store.recordHostFailure("b.example")

        assertEquals(listOf("a.example", "b.example"), store.orderedHosts())
        assertEquals(setOf("b.example"), store.snapshot().failedHosts)
    }

    @Test
    fun preferredHealthyHostReceivesPreferenceAheadOfFasterHost() {
        val store = store()
        store.restore(
            JmImageHostPersistence(
                preferredHost = "a.example",
                latencies = mapOf(
                    "a.example" to JmImageHostLatency(90L, 1L),
                    "b.example" to JmImageHostLatency(20L, 1L),
                ),
            )
        )

        assertEquals(listOf("a.example", "b.example"), store.orderedHosts())
    }

    @Test
    fun latencyRanksHostsWhenThereIsNoPreferredHost() {
        val store = store()
        store.restore(
            JmImageHostPersistence(
                preferredHost = null,
                latencies = mapOf(
                    "a.example" to JmImageHostLatency(90L, 1L),
                    "b.example" to JmImageHostLatency(20L, 1L),
                ),
            )
        )

        assertEquals(listOf("b.example", "a.example"), store.orderedHosts())
    }

    @Test
    fun failedPreferredHostIsDemoted() {
        val store = store()
        store.restore(
            JmImageHostPersistence(
                preferredHost = "a.example",
                latencies = mapOf(
                    "a.example" to JmImageHostLatency(20L, 1L),
                    "b.example" to JmImageHostLatency(40L, 1L),
                ),
            )
        )
        store.recordHostFailure("https://a.example/media/albums/1_3x4.jpg")

        assertEquals("b.example", store.preferredHost.value)
        assertEquals(listOf("b.example", "a.example"), store.orderedHosts())
    }

    @Test
    fun cooldownExpiryMakesHostEligibleAgain() {
        val clock = MutableClock(10_000L)
        val store = store(clock)
        store.recordHostFailure("a.example")
        assertEquals(listOf("b.example", "a.example"), store.orderedHosts("a.example"))

        clock.nowMillis += 1_001L

        assertEquals(listOf("a.example", "b.example"), store.orderedHosts("a.example"))
        assertTrue(store.snapshot().failedHosts.isEmpty())
    }

    @Test
    fun persistedPreferredAndLatencyRestoreIncludingDynamicHost() {
        val original = store(maxHosts = 4)
        original.registerHost("https://dynamic.example/")
        original.recordLatencySample("a.example", 60L)
        original.recordLatencySample("dynamic.example", 15L)
        val persistence = original.persistence()

        val restored = store(maxHosts = 4)
        restored.restore(persistence)

        assertEquals("dynamic.example", restored.preferredHost.value)
        assertEquals(15L, restored.snapshot().latencyMillis["dynamic.example"])
        assertTrue("dynamic.example" in restored.snapshot().hosts)
    }

    @Test
    fun networkChangeClearsNetworkSpecificRankingAndCooldown() {
        val store = store()
        store.recordLatencySample("a.example", 30L)
        store.recordHostFailure("b.example")
        val generation = store.networkGeneration.value

        store.onNetworkChanged()

        val snapshot = store.snapshot()
        assertNull(snapshot.preferredHost)
        assertTrue(snapshot.latencyMillis.isEmpty())
        assertTrue(snapshot.failedHosts.isEmpty())
        assertEquals(generation + 1L, store.networkGeneration.value)
    }

    private fun store(
        clock: MutableClock = MutableClock(10_000L),
        maxHosts: Int = 2,
    ) = JmImageHostHealthStore(
        knownHosts = listOf("a.example", "b.example"),
        clockMillis = clock::read,
        cooldownMillis = 1_000L,
        maxHosts = maxHosts,
    )

    private class MutableClock(var nowMillis: Long) {
        fun read(): Long = nowMillis
    }

    @Test
    fun recordHealthyClearsFailureWithoutTouchingLatency() {
        val clock = MutableClock(10_000L)
        val store = store(clock)
        store.recordLatencySample("a.example", 50L)
        store.recordHostFailure("a.example")
        assertEquals(setOf("a.example"), store.snapshot().failedHosts)

        store.recordHealthy("a.example")

        val snapshot = store.snapshot()
        assertTrue(snapshot.failedHosts.isEmpty())
        // 延迟测量保持不变，未被注入伪样本
        assertEquals(50L, snapshot.latencyMillis["a.example"])
    }

    @Test
    fun recordLatencySampleUpdatesEwma() {
        val store = store()

        store.recordLatencySample("a.example", 100L)
        assertEquals(100L, store.snapshot().latencyMillis["a.example"])

        store.recordLatencySample("a.example", 200L)
        assertEquals(150L, store.snapshot().latencyMillis["a.example"])
    }

    @Test
    fun recordHealthyDoesNotInjectLatencyForUnmeasuredHost() {
        val store = store()

        store.recordHealthy("a.example")

        assertNull(store.snapshot().latencyMillis["a.example"])
    }
}
