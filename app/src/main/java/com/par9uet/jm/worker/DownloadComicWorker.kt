package com.par9uet.jm.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.par9uet.jm.download.coordinator.DownloadComicCoordinator
import com.par9uet.jm.download.coordinator.DownloadOutcome

class DownloadComicWorker(
    appContext: Context,
    params: WorkerParameters,
    private val coordinator: DownloadComicCoordinator,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (
        coordinator.download(
            comicId = inputData.getInt("comicId", -1),
            batchId = inputData.getString("batchId").orEmpty(),
            batchTotal = inputData.getInt("batchTotal", 1),
            runAttemptCount = runAttemptCount,
        )
    ) {
        DownloadOutcome.SUCCESS -> Result.success()
        DownloadOutcome.RETRY -> Result.retry()
        DownloadOutcome.FAILURE -> Result.failure()
    }
}
