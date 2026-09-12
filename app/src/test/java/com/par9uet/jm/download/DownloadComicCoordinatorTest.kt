package com.par9uet.jm.download

import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.download.coordinator.DownloadComicCoordinator
import com.par9uet.jm.download.coordinator.DownloadFeedback
import com.par9uet.jm.download.coordinator.DownloadOutcome
import com.par9uet.jm.download.molecule.DownloadContentOperations
import com.par9uet.jm.storage.RemoteConfigPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DownloadComicCoordinatorTest {
    private val dao = RecordingDownloadDao()
    private val events = mutableListOf<String>()
    private val progress = mutableListOf<Float>()
    private var failure: Exception? = null
    private val content = object : DownloadContentOperations {
        override suspend fun downloadCover(downloadTask: DownloadComic, coverOwnerId: Int, remoteHost: String): String {
            assertEquals(100, coverOwnerId)
            assertEquals("cdn.example", remoteHost)
            assertEquals(DownloadStatus.DOWNLOADING, dao.tasks.getValue(1).status)
            events += "cover"
            return "/cover.webp"
        }
        override suspend fun downloadPages(downloadTask: DownloadComic, onProgress: suspend (Float) -> Unit) {
            assertEquals("/cover.webp", dao.tasks.getValue(1).coverPath)
            events += "pages"
            failure?.let { throw it }
            onProgress(0.2f)
            assertEquals(0.6f, dao.tasks.getValue(1).progress)
            onProgress(0.8f)
        }
        override suspend fun complete(downloadTask: DownloadComic) {
            assertEquals(1f, dao.tasks.getValue(1).progress)
            events += "complete"
            dao.tasks[1] = dao.tasks.getValue(1).copy(status = DownloadStatus.COMPLETE)
        }
    }
    private val feedback = object : DownloadFeedback {
        override fun start(groupId: Int) { events += "start" }
        override fun stop(groupId: Int) { events += "stop" }
        override fun showProgress(downloadTask: DownloadComic, progress: Float) {
            this@DownloadComicCoordinatorTest.progress += progress
        }
        override fun cancel(groupId: Int) { events += "cancel" }
        override fun report(batchId: String, batchTotal: Int, comicId: Int, success: Boolean) {
            assertEquals("batch", batchId)
            assertEquals(2, batchTotal)
            events += "report:$success"
        }
    }
    private val coordinator = DownloadComicCoordinator(
        dao,
        object : RemoteConfigPreferences { override val remoteImageHost = MutableStateFlow("cdn.example") },
        content, feedback,
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `migration waits for existing writer and blocks later writers until finished`() = runTest {
        dao.tasks[1] = task(1)
        dao.tasks[2] = task(2)
        val firstWriting = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val migrationStarted = CompletableDeferred<Unit>()
        val finishMigration = CompletableDeferred<Unit>()
        val secondWriting = CompletableDeferred<Unit>()
        val controlled = object : DownloadContentOperations {
            override suspend fun downloadCover(downloadTask: DownloadComic, coverOwnerId: Int, remoteHost: String) = "/cover.webp"
            override suspend fun complete(downloadTask: DownloadComic) {
                dao.tasks[downloadTask.id] = dao.tasks.getValue(downloadTask.id).copy(status = DownloadStatus.COMPLETE)
            }
            override suspend fun downloadPages(downloadTask: DownloadComic, onProgress: suspend (Float) -> Unit) {
                if (downloadTask.id == 1) {
                    firstWriting.complete(Unit)
                    finishFirst.await()
                } else secondWriting.complete(Unit)
                onProgress(1f)
            }
        }
        val subject = DownloadComicCoordinator(dao,
            object : RemoteConfigPreferences { override val remoteImageHost = MutableStateFlow("cdn.example") },
            controlled, feedback)
        val first = async { subject.download(1, "batch", 2, 0) }
        firstWriting.await()
        val migration = async {
            subject.withIdleDownloads {
                migrationStarted.complete(Unit)
                finishMigration.await()
            }
        }
        runCurrent()
        assertFalse(migrationStarted.isCompleted)
        val second = async { subject.download(2, "batch", 2, 0) }
        runCurrent()
        assertFalse(secondWriting.isCompleted)
        finishFirst.complete(Unit)
        migrationStarted.await()
        assertFalse(secondWriting.isCompleted)
        finishMigration.complete(Unit)
        migration.await()
        assertEquals(DownloadOutcome.SUCCESS, first.await())
        assertEquals(DownloadOutcome.SUCCESS, second.await())
        assertTrue(secondWriting.isCompleted)
    }

    @Test
    fun `failed migration releases download gate`() = runTest {
        try {
            coordinator.withIdleDownloads { error("copy failed") }
            fail("Migration should fail")
        } catch (_: IllegalStateException) { }
        dao.tasks[1] = task(1)
        assertEquals(DownloadOutcome.SUCCESS, coordinator.download(1, "batch", 2, 0))
    }

    @Test
    fun `successful flow completes before reporting and progress never regresses`() = runTest {
        dao.tasks[1] = task(1)
        assertEquals(DownloadOutcome.SUCCESS, coordinator.download(1, "batch", 2, 0))
        assertEquals(listOf("start", "cover", "pages", "complete", "stop", "cancel", "report:true"), events)
        assertEquals(listOf(0.6f, 0.6f, 0.8f, 1f), progress)
        assertEquals(DownloadStatus.COMPLETE, dao.tasks.getValue(1).status)
    }

    @Test
    fun `retryable failure neither marks error nor reports final failure`() = runTest {
        dao.tasks[1] = task(1)
        failure = IllegalStateException("network")
        assertEquals(DownloadOutcome.RETRY, coordinator.download(1, "batch", 2, 4))
        assertEquals(DownloadStatus.DOWNLOADING, dao.tasks.getValue(1).status)
        assertEquals(listOf("start", "cover", "pages"), events)
    }

    @Test
    fun `pausing during retry backoff clears progress feedback without a running worker`() = runTest {
        dao.tasks[1] = task(1)
        failure = IllegalStateException("network")
        assertEquals(DownloadOutcome.RETRY, coordinator.download(1, "batch", 2, 0))
        coordinator.withStoppedDownloads(listOf(1)) {
            dao.tasks[1] = dao.tasks.getValue(1).copy(status = DownloadStatus.PAUSED)
        }
        assertEquals(listOf("start", "cover", "pages", "stop", "cancel"), events)
    }

    @Test
    fun `last attempt marks error and reports failure`() = runTest {
        dao.tasks[1] = task(1)
        failure = IllegalStateException("network")
        assertEquals(DownloadOutcome.FAILURE, coordinator.download(1, "batch", 2, 5))
        assertEquals(DownloadStatus.ERROR, dao.tasks.getValue(1).status)
        assertEquals(listOf("start", "cover", "pages", "stop", "cancel", "report:false"), events)
    }

    @Test
    fun `wrapped cancellation propagates on every attempt`() = runTest {
        for (attempt in listOf(0, 5)) {
            dao.tasks[1] = task(1)
            events.clear()
            val cancelled = CancellationException("stopped")
            failure = IllegalStateException("page", cancelled)
            try {
                coordinator.download(1, "batch", 2, attempt)
                fail("Cancellation must propagate")
            } catch (actual: CancellationException) {
                // coroutineScope can recover the stack trace into a copy of CancellationException.
                assertEquals(cancelled.message, actual.message)
            }
            assertEquals(listOf("start", "cover", "pages", "stop", "cancel"), events)
            assertEquals(DownloadStatus.DOWNLOADING, dao.tasks.getValue(1).status)
        }
    }

    @Test
    fun `active sibling keeps notification and group progress includes all chapters`() = runTest {
        dao.tasks[1] = task(1)
        dao.tasks[2] = task(2).copy(status = DownloadStatus.PENDING, progress = 0f)
        assertEquals(DownloadOutcome.SUCCESS, coordinator.download(1, "batch", 2, 0))
        assertEquals(listOf(0.3f, 0.3f, 0.4f, 0.5f), progress)
        assertFalse("cancel" in events)
    }

    @Test
    fun `invalid and missing tasks fail without starting downloads`() = runTest {
        assertEquals(DownloadOutcome.FAILURE, coordinator.download(-1, "batch", 2, 0))
        assertEquals(DownloadOutcome.FAILURE, coordinator.download(404, "batch", 2, 0))
        assertTrue(events.isEmpty())
    }

    private fun task(id: Int) = DownloadComic(
        id = id, name = "漫画", authorList = emptyList(), coverPath = "", zipPath = "",
        progress = 0.6f, status = DownloadStatus.PENDING, createTime = id.toLong(), groupId = 100,
    )
}
