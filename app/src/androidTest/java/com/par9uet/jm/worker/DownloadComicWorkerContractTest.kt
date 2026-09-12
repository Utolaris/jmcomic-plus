package com.par9uet.jm.worker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.impl.utils.taskexecutor.SerialExecutor
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.par9uet.jm.database.AppDatabase
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.download.coordinator.DownloadComicCoordinator
import com.par9uet.jm.download.coordinator.DownloadFeedback
import com.par9uet.jm.download.molecule.DownloadContentOperations
import com.par9uet.jm.storage.RemoteConfigPreferences
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract test for [DownloadComicWorker]: it must only translate WorkManager arguments into a
 * coordinator call and map [com.par9uet.jm.download.coordinator.DownloadOutcome] back to a
 * WorkManager result. No repository, storage or network type may appear in this worker.
 */
@RunWith(AndroidJUnit4::class)
class DownloadComicWorkerContractTest {
    private lateinit var database: AppDatabase
    private val reported = mutableListOf<Report>()
    private var coverError: Throwable? = null

    private data class Report(val batchId: String, val batchTotal: Int, val comicId: Int, val success: Boolean)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        reported.clear()
        coverError = null
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun insertTask(id: Int, status: DownloadStatus) {
        database.downloadComicDao().insert(
            DownloadComic(
                id = id,
                name = "契约测试 $id",
                authorList = emptyList(),
                coverPath = "",
                zipPath = "",
                progress = 0f,
                status = status,
                createTime = 1L,
            )
        )
    }

    private fun createWorker(
        comicId: Int,
        batchId: String?,
        batchTotal: Int,
        runAttemptCount: Int,
    ): DownloadComicWorker {
        val data = Data.Builder().putInt("comicId", comicId).putInt("batchTotal", batchTotal)
        if (batchId != null) data.putString("batchId", batchId)
        val params = WorkerParameters(
            UUID.randomUUID(),
            data.build(),
            emptyList(),
            WorkerParameters.RuntimeExtras(),
            runAttemptCount,
            0,
            Executor { it.run() },
            Dispatchers.Default,
            ImmediateTaskExecutor,
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: android.content.Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = null
            },
            ProgressUpdater { _, _, _ -> completedFuture() },
            ForegroundUpdater { _, _, _ -> completedFuture() },
        )
        return DownloadComicWorker(
            InstrumentationRegistry.getInstrumentation().targetContext,
            params,
            DownloadComicCoordinator(
                database.downloadComicDao(),
                object : RemoteConfigPreferences {
                    override val remoteImageHost = MutableStateFlow("")
                },
                object : DownloadContentOperations {
                    override suspend fun downloadCover(
                        downloadTask: DownloadComic,
                        coverOwnerId: Int,
                        remoteHost: String,
                    ): String {
                        coverError?.let { throw it }
                        return ""
                    }

                    override suspend fun downloadPages(
                        downloadTask: DownloadComic,
                        onProgress: suspend (Float) -> Unit,
                    ) = Unit

                    override suspend fun complete(downloadTask: DownloadComic) = Unit
                },
                object : DownloadFeedback {
                    override fun start(groupId: Int) = Unit
                    override fun stop(groupId: Int) = Unit
                    override fun showProgress(downloadTask: DownloadComic, progress: Float) = Unit
                    override fun cancel(groupId: Int) = Unit
                    override fun report(batchId: String, batchTotal: Int, comicId: Int, success: Boolean) {
                        reported += Report(batchId, batchTotal, comicId, success)
                    }
                },
            ),
        )
    }

    @Test
    fun completedTaskMapsToSuccessWithoutTouchingTheContentPipeline() = runBlocking {
        insertTask(9001, DownloadStatus.COMPLETE)

        val result = createWorker(9001, "batch-a", 3, 0).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue("已完成的任务不应再触发下载", reported.isEmpty())
    }

    @Test
    fun missingComicIdMapsToFailure() = runBlocking {
        val result = createWorker(-1, "batch-a", 1, 0).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun workArgumentsReachTheCoordinatorVerbatim() = runBlocking {
        insertTask(9002, DownloadStatus.PENDING)
        coverError = IllegalStateException("device-network-down")

        val result = createWorker(9002, "batch-device", 7, 9).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(
            listOf(Report(batchId = "batch-device", batchTotal = 7, comicId = 9002, success = false)),
            reported,
        )
    }

    @Test
    fun missingBatchIdIsTreatedAsEmptyInsteadOfCrashing() = runBlocking {
        insertTask(9003, DownloadStatus.PENDING)
        coverError = IllegalStateException("device-network-down")

        createWorker(9003, null, 2, 9).doWork()

        assertEquals("", reported.single().batchId)
    }

    @Test
    fun transientErrorRetriesUntilTheAttemptBudgetIsSpent() = runBlocking {
        insertTask(9004, DownloadStatus.PENDING)
        coverError = IllegalStateException("device-network-down")

        val early = createWorker(9004, "batch-b", 1, 0).doWork()
        assertTrue("首次失败应重试", early is ListenableWorker.Result.Retry)

        val exhausted = createWorker(9004, "batch-b", 1, 9).doWork()
        assertTrue("超过重试预算后应终态失败", exhausted is ListenableWorker.Result.Failure)
    }

    private object ImmediateTaskExecutor : TaskExecutor {
        private val executor = Executor { it.run() }
        private val serial = object : SerialExecutor {
            override fun execute(command: Runnable) = command.run()
            override fun hasPendingTasks() = false
        }

        override fun getMainThreadExecutor() = executor
        override fun getSerialTaskExecutor() = serial
    }

    private fun completedFuture(): com.google.common.util.concurrent.ListenableFuture<Void> =
        SettableFuture.create<Void>().apply { set(null) }
}
