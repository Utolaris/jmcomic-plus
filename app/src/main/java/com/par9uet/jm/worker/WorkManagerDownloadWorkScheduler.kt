package com.par9uet.jm.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.par9uet.jm.download.DownloadWorkScheduler
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class WorkManagerDownloadWorkScheduler(
    private val context: Context,
) : DownloadWorkScheduler {
    override fun enqueue(comicIds: Collection<Int>) {
        val distinctComicIds = comicIds.distinct()
        if (distinctComicIds.isEmpty()) return

        val batchId = if (distinctComicIds.size > 1) UUID.randomUUID().toString() else ""
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workManager = WorkManager.getInstance(context)
        distinctComicIds.forEach { comicId ->
            val request = OneTimeWorkRequestBuilder<DownloadComicWorker>()
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        "comicId" to comicId,
                        "batchId" to batchId,
                        "batchTotal" to distinctComicIds.size,
                    )
                )
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    DOWNLOAD_RETRY_BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                .build()
            workManager.enqueueUniqueWork(workName(comicId), ExistingWorkPolicy.KEEP, request)
        }
    }

    override suspend fun cancel(comicIds: Collection<Int>) = withContext(Dispatchers.IO) {
        val workManager = WorkManager.getInstance(context)
        comicIds.distinct().forEach { comicId ->
            try {
                workManager.cancelUniqueWork(workName(comicId))
                    .result.get(CANCEL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                // Fail the cancel: callers must not mutate DB while work may still be writing.
                throw IllegalStateException(
                    "取消缓存任务超时：${workName(comicId)}",
                    e,
                )
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("cancel interrupted")
            }
        }
    }

    private fun workName(comicId: Int) = "comic-download-$comicId"

    private companion object {
        const val DOWNLOAD_RETRY_BACKOFF_SECONDS = 30L
        const val CANCEL_TIMEOUT_SECONDS = 5L
    }
}
