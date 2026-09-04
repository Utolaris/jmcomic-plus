package com.par9uet.jm.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.par9uet.jm.store.DownloadWorkScheduler
import java.util.UUID
import java.util.concurrent.TimeUnit

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
            workManager.enqueue(request)
        }
    }

    private companion object {
        const val DOWNLOAD_RETRY_BACKOFF_SECONDS = 30L
    }
}
