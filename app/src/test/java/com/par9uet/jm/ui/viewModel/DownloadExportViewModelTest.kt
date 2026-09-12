package com.par9uet.jm.ui.viewModel

import com.par9uet.jm.download.downloadTask
import com.par9uet.jm.download.export.DownloadCacheSummary
import com.par9uet.jm.download.export.DownloadExportOperations
import com.par9uet.jm.download.export.PdfExportMode
import com.par9uet.jm.download.molecule.toDomain
import com.par9uet.jm.download.model.DownloadItem
import com.par9uet.jm.store.ToastManager
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadExportViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher(scheduler)) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Operations : DownloadExportOperations {
        val calls = mutableListOf<Pair<List<Int>, PdfExportMode>>()
        var exportAction: suspend () -> Unit = {}
        var inspection: suspend (List<DownloadItem>) -> DownloadCacheSummary = { DownloadCacheSummary(it.size, 10) }
        override suspend fun inspect(chapters: List<DownloadItem>, cachePath: String) = inspection(chapters)
        override suspend fun export(chapters: List<DownloadItem>, uri: String, mode: PdfExportMode) {
            calls += chapters.map { it.id } to mode
            exportAction()
        }
    }

    private fun item(id: Int = 1) = downloadTask(id).toDomain()

    @Test fun `picker uses confirmed chapter and mode snapshot and duplicate callbacks cannot export twice`() = runTest(scheduler) {
        val ops = Operations()
        val release = CompletableDeferred<Unit>()
        ops.exportAction = { release.await() }
        val vm = DownloadExportViewModel(ops, ToastManager())
        vm.selectChapters(setOf(1))
        assertTrue(vm.prepareExport(listOf(item(1), item(2)), PdfExportMode.SplitByChapter))
        vm.selectChapters(setOf(2))
        vm.exportTo("content://tree/destination")
        runCurrent()
        vm.exportTo("content://tree/destination")
        assertFalse(vm.prepareExport(listOf(item(2)), PdfExportMode.Merge))
        assertEquals(listOf(listOf(1) to PdfExportMode.SplitByChapter), ops.calls)
        assertTrue(vm.state.value.exporting)
        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.exporting)
        vm.exportTo("content://tree/destination")
        assertEquals(1, ops.calls.size)
    }

    @Test fun `picker cancellation performs no IO and export errors permit retry`() = runTest(scheduler) {
        val ops = Operations()
        val vm = DownloadExportViewModel(ops, ToastManager())
        assertFalse(vm.prepareExport(emptyList(), PdfExportMode.Merge))
        vm.selectChapters(setOf(1))
        vm.prepareExport(listOf(item()), PdfExportMode.Merge)
        vm.exportTo(null)
        advanceUntilIdle()
        assertTrue(ops.calls.isEmpty())
        ops.exportAction = { error("write failed") }
        vm.prepareExport(listOf(item()), PdfExportMode.Merge)
        vm.exportTo("content://tree/destination")
        advanceUntilIdle()
        assertFalse(vm.state.value.exporting)
        ops.exportAction = {}
        assertTrue(vm.prepareExport(listOf(item()), PdfExportMode.Merge))
        vm.exportTo("content://tree/destination")
        advanceUntilIdle()
        assertEquals(2, ops.calls.size)
    }

    @Test fun `late file inspection cannot overwrite a newer chapter summary`() = runTest(scheduler) {
        val ops = Operations()
        val release = CompletableDeferred<Unit>()
        ops.inspection = { chapters ->
            if (chapters.first().id == 1) withContext(NonCancellable) { release.await() }
            DownloadCacheSummary(chapters.first().id, 10)
        }
        val vm = DownloadExportViewModel(ops, ToastManager())
        vm.inspect(listOf(item(1)), "/old")
        runCurrent()
        vm.inspect(listOf(item(2)), "/new")
        runCurrent()
        assertEquals(2, vm.state.value.summary!!.imageCount)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, vm.state.value.summary!!.imageCount)
        vm.inspect(emptyList(), "")
        assertNull(vm.state.value.summary)
    }
}
