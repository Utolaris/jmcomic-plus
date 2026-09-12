package com.par9uet.jm.download

import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.download.coordinator.DownloadComicCoordinator
import com.par9uet.jm.download.coordinator.DownloadFeedback
import com.par9uet.jm.download.coordinator.DownloadOutcome
import com.par9uet.jm.download.molecule.DownloadContentOperations
import com.par9uet.jm.download.molecule.downloadPageWithinTimeout
import com.par9uet.jm.storage.RemoteConfigPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

internal fun downloadTask(id: Int = 1, status: DownloadStatus = DownloadStatus.PENDING) = DownloadComic(
    id = id, name = "同名漫画", authorList = emptyList(), coverPath = "", zipPath = "",
    progress = 0f, status = status, createTime = 1, groupId = 100,
)

internal fun testDownloadCoordinator(
    dao: RecordingDownloadDao,
    download: suspend () -> Unit = {},
): DownloadComicCoordinator = DownloadComicCoordinator(
    dao, object : RemoteConfigPreferences { override val remoteImageHost = MutableStateFlow("cdn.example") },
    object : DownloadContentOperations {
        override suspend fun downloadCover(downloadTask: DownloadComic, coverOwnerId: Int, remoteHost: String) = ""
        override suspend fun downloadPages(downloadTask: DownloadComic, onProgress: suspend (Float) -> Unit) {
            download()
            onProgress(1f)
        }
        override suspend fun complete(downloadTask: DownloadComic) {
            dao.tasks[downloadTask.id] = dao.tasks.getValue(downloadTask.id).copy(status = DownloadStatus.COMPLETE)
        }
    },
    object : DownloadFeedback {
        override fun start(groupId: Int) {}
        override fun stop(groupId: Int) {}
        override fun showProgress(downloadTask: DownloadComic, progress: Float) {}
        override fun cancel(groupId: Int) {}
        override fun report(batchId: String, batchTotal: Int, comicId: Int, success: Boolean) {}
    },
)

class DownloadLifecycleRegressionTest {
    @Test fun `queued paused and completed work never starts a writer`() = runTest {
        val dao = RecordingDownloadDao()
        val coordinator = testDownloadCoordinator(dao) { fail("Must not download") }
        for (status in listOf(DownloadStatus.PAUSED, DownloadStatus.COMPLETE)) {
            dao.tasks[1] = downloadTask(status = status)
            assertEquals(DownloadOutcome.SUCCESS, coordinator.download(1, "", 1, 0))
            assertEquals(status, dao.tasks.getValue(1).status)
        }
    }

    @Test fun `duplicate work cannot write concurrently and pause waits for writer exit`() = runTest {
        val dao = RecordingDownloadDao().apply { tasks[1] = downloadTask() }
        val started = CompletableDeferred<Unit>()
        var exited = false
        var calls = 0
        val coordinator = testDownloadCoordinator(dao) {
            calls++
            started.complete(Unit)
            try { awaitCancellation() } finally { exited = true }
        }
        val worker = launch { coordinator.download(1, "", 1, 0) }
        started.await()
        assertEquals(DownloadOutcome.SUCCESS, coordinator.download(1, "", 1, 0))
        assertEquals(1, calls)
        coordinator.withStoppedDownloads(listOf(1)) {
            assertTrue(exited)
            dao.tasks[1] = dao.tasks.getValue(1).copy(status = DownloadStatus.PAUSED)
        }
        worker.join()
        assertTrue(worker.isCancelled)
        assertEquals(DownloadStatus.PAUSED, dao.tasks.getValue(1).status)
    }

    @Test fun `page deadline uses retry policy and final attempt becomes error`() = runTest {
        val dao = RecordingDownloadDao().apply { tasks[1] = downloadTask() }
        val coordinator = testDownloadCoordinator(dao) {
            downloadPageWithinTimeout(10) { delay(20); Unit }
        }
        assertEquals(DownloadOutcome.RETRY, coordinator.download(1, "", 1, 0))
        assertTrue(currentCoroutineContext().isActive)
        assertEquals(DownloadOutcome.FAILURE, coordinator.download(1, "", 1, 5))
        assertEquals(DownloadStatus.ERROR, dao.tasks.getValue(1).status)
    }

    @Test fun `parent cancellation is not converted into page timeout`() = runTest {
        val started = CompletableDeferred<Unit>()
        val worker = launch {
            downloadPageWithinTimeout(1000) { started.complete(Unit); awaitCancellation() }
            fail("Must propagate cancellation")
        }
        started.await()
        worker.cancelAndJoin()
        assertTrue(worker.isCancelled)
    }
}
