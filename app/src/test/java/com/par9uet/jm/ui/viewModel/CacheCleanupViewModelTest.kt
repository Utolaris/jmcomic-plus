package com.par9uet.jm.ui.viewModel

import com.par9uet.jm.cache.CacheArea
import com.par9uet.jm.cache.CacheSize
import com.par9uet.jm.cache.atom.CacheFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class CacheCleanupViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher(scheduler)) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `all cache selection deduplicates bytes and holds download stop across reader and file cleanup`() = runTest(scheduler) {
        val events = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val files = object : CacheFiles {
            override suspend fun scan() = listOf(CacheSize(CacheArea.ALL, 100), CacheSize(CacheArea.DOWNLOAD, 60))
            override suspend fun remove(areas: Set<CacheArea>) {
                assertEquals(setOf(CacheArea.ALL), areas)
                events += "files"
                release.await()
            }
        }
        val vm = CacheCleanupViewModel(files, { events += "reader" }, { action ->
            events += "stop"
            action()
            events += "records"
        })
        runCurrent()
        vm.select(CacheArea.ALL, true)
        vm.select(CacheArea.DOWNLOAD, true)
        assertEquals(100L, vm.state.value.selectedBytes)
        vm.clean()
        runCurrent()
        vm.clean()
        vm.select(CacheArea.PDF, true)
        assertTrue(vm.state.value.cleaning)
        assertFalse(CacheArea.PDF in vm.state.value.selected)
        assertEquals(listOf("stop", "reader", "files"), events)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("stop", "reader", "files", "records"), events)
        assertFalse(vm.state.value.cleaning)
        assertTrue(vm.state.value.selected.isEmpty())
        assertTrue(vm.state.value.result!!.startsWith("已清理"))
    }

    @Test fun `ordinary cache does not stop downloads and failure leaves selection available for retry`() = runTest(scheduler) {
        var failing = true
        val vm = CacheCleanupViewModel(object : CacheFiles {
            override suspend fun scan() = listOf(CacheSize(CacheArea.PDF, 50))
            override suspend fun remove(areas: Set<CacheArea>) {
                if (failing) error("disk busy")
            }
        }, { fail("No reader clearing") }, { fail("No download clearing") })
        runCurrent()
        vm.select(CacheArea.PDF, true)
        vm.clean()
        advanceUntilIdle()
        assertEquals("disk busy", vm.state.value.result)
        assertFalse(vm.state.value.cleaning)
        assertEquals(setOf(CacheArea.PDF), vm.state.value.selected)
        failing = false
        vm.clean()
        advanceUntilIdle()
        assertTrue(vm.state.value.selected.isEmpty())
    }
}
