package com.par9uet.jm.download

import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.download.atom.DownloadFiles
import com.par9uet.jm.download.molecule.DownloadTaskOperations
import com.par9uet.jm.download.coordinator.DownloadManager
import com.par9uet.jm.download.DownloadWorkScheduler
import com.par9uet.jm.core.ToastManager
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DownloadCacheCleanupTest {
    @Test fun `cleanup stops writers invalidates rows then removes files and records`() = runTest {
        val dao = RecordingDownloadDao().apply {
            tasks[1] = downloadTask()
            tasks[2] = downloadTask(2, DownloadStatus.COMPLETE)
        }
        val started = CompletableDeferred<Unit>()
        var writerExited = false
        val coordinator = testDownloadCoordinator(dao) {
            started.complete(Unit)
            try { awaitCancellation() } finally { writerExited = true }
        }
        val worker = launch { coordinator.download(1, "", 1, 0) }
        started.await()
        val cancelled = mutableListOf<Int>()
        val manager = DownloadManager(
            DownloadTaskOperations(dao, DownloadFiles()), this, ToastManager(),
            object : DownloadWorkScheduler {
                override fun enqueue(comicIds: Collection<Int>) { fail("Cleanup must not enqueue") }
                override suspend fun cancel(comicIds: Collection<Int>) { cancelled += comicIds }
            }, coordinator,
        )
        manager.clearDownloadedCache {
            assertTrue(writerExited)
            assertEquals(listOf(1, 2), cancelled)
            assertTrue(dao.tasks.values.all { it.status == DownloadStatus.ERROR && it.progress == 0f })
        }
        worker.join()
        assertTrue(dao.tasks.isEmpty())
        assertEquals(com.par9uet.jm.download.coordinator.DownloadOutcome.FAILURE, coordinator.download(1, "", 1, 0))
        // The same comic can be added again because its stale complete row is gone.
        assertEquals(listOf(1), DownloadTaskOperations(dao, DownloadFiles()).downloadComic(
            com.par9uet.jm.data.models.Comic.create(1, "漫画", emptyList()),
        )!!.comicIds)
    }

    @Test fun `failed cleanup never leaves missing files advertised as complete`() = runTest {
        val dao = RecordingDownloadDao().apply { tasks[1] = downloadTask(status = DownloadStatus.COMPLETE) }
        val manager = DownloadManager(
            DownloadTaskOperations(dao, DownloadFiles()), this, ToastManager(),
            object : DownloadWorkScheduler {
                override fun enqueue(comicIds: Collection<Int>) {}
                override suspend fun cancel(comicIds: Collection<Int>) {}
            }, testDownloadCoordinator(dao),
        )
        try {
            manager.clearDownloadedCache { throw java.io.IOException("Cannot delete remaining file") }
            fail("Expected error")
        } catch (_: java.io.IOException) {
            assertEquals(DownloadStatus.ERROR, dao.tasks.getValue(1).status)
        }
    }

    @Test fun `pause cancels scheduled work before recording paused state and preserves complete rows`() = runTest {
        val dao = RecordingDownloadDao().apply {
            tasks[1] = downloadTask()
            tasks[2] = downloadTask(2, DownloadStatus.COMPLETE)
        }
        var cancelled = false
        val manager = DownloadManager(
            DownloadTaskOperations(dao, DownloadFiles()), this, ToastManager(),
            object : DownloadWorkScheduler {
                override fun enqueue(comicIds: Collection<Int>) {}
                override suspend fun cancel(comicIds: Collection<Int>) {
                    assertEquals(DownloadStatus.PENDING, dao.tasks.getValue(1).status)
                    cancelled = true
                }
            }, testDownloadCoordinator(dao),
        )
        manager.pauseDownloads(listOf(1, 2))
        assertTrue(cancelled)
        assertEquals(DownloadStatus.PAUSED, dao.tasks.getValue(1).status)
        assertEquals(DownloadStatus.COMPLETE, dao.tasks.getValue(2).status)
    }
}
