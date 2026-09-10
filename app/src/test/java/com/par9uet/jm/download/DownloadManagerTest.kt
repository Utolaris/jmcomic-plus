package com.par9uet.jm.download

import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.download.atom.DownloadFiles
import com.par9uet.jm.download.molecule.DownloadTaskOperations
import com.par9uet.jm.store.DownloadManager
import com.par9uet.jm.store.DownloadWorkScheduler
import com.par9uet.jm.store.ToastManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagerTest {
    @Test
    fun `selected chapters are resolved once per group and stopped before redownload writes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val job = SupervisorJob()
        val scope = CoroutineScope(job)
        val dao = RecordingDownloadDao().apply {
            tasks[1] = downloadTask(1, com.par9uet.jm.database.model.DownloadStatus.COMPLETE)
            tasks[2] = downloadTask(2, com.par9uet.jm.database.model.DownloadStatus.COMPLETE)
        }
        val events = mutableListOf<String>()
        val control = object : com.par9uet.jm.download.coordinator.DownloadExecutionControl {
            override suspend fun <T> withStoppedDownloads(comicIds: Collection<Int>, block: suspend () -> T): T {
                assertEquals(listOf(1, 2), comicIds.toList())
                events += "stop"
                return block()
            }
        }
        val scheduler = object : DownloadWorkScheduler {
            override suspend fun cancel(comicIds: Collection<Int>) {
                assertTrue(dao.tasks.values.all { it.status == com.par9uet.jm.database.model.DownloadStatus.COMPLETE })
                events += "cancel"
            }
            override fun enqueue(comicIds: Collection<Int>) {
                assertEquals(listOf(1, 2), comicIds.toList())
                assertTrue(dao.tasks.values.all { it.status == com.par9uet.jm.database.model.DownloadStatus.PENDING })
                events += "enqueue"
            }
        }
        try {
            DownloadManager(DownloadTaskOperations(dao, DownloadFiles()), scope, ToastManager(), scheduler, control)
                .redownloadDownloads(listOf(1, 2, 1, 404))
            job.children.toList().joinAll()
            runCurrent()
            assertEquals(listOf("stop", "cancel", "enqueue"), events)
        } finally {
            scope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `only persisted new tasks are enqueued once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val job = SupervisorJob()
        val scope = CoroutineScope(job)
        try {
            val dao = RecordingDownloadDao()
            val batches = mutableListOf<List<Int>>()
            val scheduler = object : DownloadWorkScheduler {
                override suspend fun cancel(comicIds: Collection<Int>) {}
                override fun enqueue(comicIds: Collection<Int>) {
                    assertTrue(comicIds.all { it in dao.tasks })
                    batches += comicIds.toList()
                }
            }
            val manager = DownloadManager(
                DownloadTaskOperations(dao, DownloadFiles()), scope, ToastManager(), scheduler, testDownloadCoordinator(dao),
            )
            val comic = Comic.create(id = 1, name = "漫画", authorList = emptyList())
            manager.downloadComic(comic)
            job.children.toList().joinAll()
            manager.downloadComic(comic)
            manager.downloadComics(emptyList())
            manager.retryDownload(404)
            job.children.toList().joinAll()
            runCurrent()
            assertEquals(listOf(listOf(1)), batches)
        } finally {
            scope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `cancelled creation never enqueues work`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val job = SupervisorJob()
        val scope = CoroutineScope(job)
        try {
            val dao = RecordingDownloadDao().apply { failure = CancellationException("cancelled") }
            val batches = mutableListOf<List<Int>>()
            val manager = DownloadManager(
                DownloadTaskOperations(dao, DownloadFiles()), scope, ToastManager(),
                object : DownloadWorkScheduler {
                    override suspend fun cancel(comicIds: Collection<Int>) {}
                    override fun enqueue(comicIds: Collection<Int>) {
                        batches += comicIds.toList()
                    }
                }, testDownloadCoordinator(dao),
            )
            manager.downloadComic(Comic.create(id = 1, name = "漫画", authorList = emptyList()))
            job.children.toList().joinAll()
            runCurrent()
            assertTrue(batches.isEmpty())
            assertTrue(dao.tasks.isEmpty())
        } finally {
            scope.cancel()
            Dispatchers.resetMain()
        }
    }
}
