package com.par9uet.jm.worker

import android.content.Context
import android.provider.DocumentsContract
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
import com.par9uet.jm.cache.findOrCreateCacheDocument
import com.par9uet.jm.cache.getDownloadTreeUri
import com.par9uet.jm.cache.migration.CacheMigrationCoordinator
import com.par9uet.jm.cache.migration.CacheMigrationDownloadGate
import com.par9uet.jm.cache.migration.CacheMigrationWork
import com.par9uet.jm.cache.migration.DeviceCacheMigrationOperations
import com.par9uet.jm.cache.setDownloadTreeUri
import com.par9uet.jm.database.AppDatabase
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract test for [CacheMigrationWorker]: it must only translate WorkManager arguments into a
 * coordinator call and map [com.par9uet.jm.cache.migration.CacheMigrationOutcome] back to a
 * WorkManager result. No cache API, database or notification type may appear in this worker.
 */
@RunWith(AndroidJUnit4::class)
class CacheMigrationWorkerContractTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        setDownloadTreeUri(context, "")
    }

    @After
    fun tearDown() {
        database.close()
        setDownloadTreeUri(context, "")
    }

    @Test
    fun missingTargetKeyFallsBackToTheDefaultTreeAndMapsToSuccess() = runBlocking {
        val result = createWorker(targetTree = null).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(100, result.outputData.getInt(CacheMigrationWork.PROGRESS, -1))
        assertEquals(CacheMigrationCoordinator.COMPLETED_STAGE, result.outputData.getString(CacheMigrationWork.STAGE))
        assertNull("没有目标目录时不应切换缓存路径", getDownloadTreeUri(context))
    }

    @Test
    fun theTargetTreeArgumentReachesTheCoordinatorVerbatim() = runBlocking {
        // A tree nested inside the active one can only be refused if the worker passed the argument on.
        val sourceTree = createTreeDirectory("source-${UUID.randomUUID()}")
        setDownloadTreeUri(context, sourceTree.toString())
        val sourceRoot = DocumentsContract.buildDocumentUriUsingTree(
            sourceTree,
            DocumentsContract.getTreeDocumentId(sourceTree),
        )
        val nested = requireNotNull(findOrCreateCacheDocument(context, sourceRoot, "nested", DocumentsContract.Document.MIME_TYPE_DIR))
        val nestedTree = DocumentsContract.buildTreeDocumentUri(
            PROVIDER_AUTHORITY,
            DocumentsContract.getDocumentId(nested),
        ).toString()

        val result = createWorker(targetTree = nestedTree).doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals(
            "请选择与原缓存目录互不包含的目录",
            result.outputData.getString(CacheMigrationWork.ERROR),
        )
        assertEquals("被拒绝的迁移不能切换缓存路径", sourceTree.toString(), getDownloadTreeUri(context)?.toString())
    }

    private fun createWorker(targetTree: String?): CacheMigrationWorker {
        val data = Data.Builder()
        if (targetTree != null) data.putString(CacheMigrationWork.TARGET_TREE_URI, targetTree)
        val params = WorkerParameters(
            UUID.randomUUID(),
            data.build(),
            emptyList(),
            WorkerParameters.RuntimeExtras(),
            0,
            0,
            Executor { it.run() },
            Dispatchers.Default,
            ImmediateTaskExecutor,
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = null
            },
            ProgressUpdater { _, _, _ -> completedFuture() },
            ForegroundUpdater { _, _, _ -> completedFuture() },
        )
        return CacheMigrationWorker(
            context,
            params,
            CacheMigrationCoordinator(
                DeviceCacheMigrationOperations(context, database.downloadComicDao(), database),
                object : CacheMigrationDownloadGate {
                    override suspend fun <T> withIdleDownloads(block: suspend () -> T): T = block()
                },
            ),
        )
    }

    private fun createTreeDirectory(name: String): android.net.Uri {
        val providerTree = DocumentsContract.buildTreeDocumentUri(PROVIDER_AUTHORITY, "root")
        val providerRoot = DocumentsContract.buildDocumentUriUsingTree(providerTree, "root")
        val child = requireNotNull(findOrCreateCacheDocument(context, providerRoot, name, DocumentsContract.Document.MIME_TYPE_DIR))
        return DocumentsContract.buildTreeDocumentUri(PROVIDER_AUTHORITY, DocumentsContract.getDocumentId(child))
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

    private companion object {
        const val PROVIDER_AUTHORITY = "jmcomic.debug.test.cache-documents"
    }
}
