package com.par9uet.jm.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.par9uet.jm.cache.migration.CacheMigrationScheduler
import com.par9uet.jm.cache.migration.CacheMigrationWork

/**
 * `CacheMigrationScheduler` 的 WorkManager 实现。和 `WorkManagerDownloadWorkScheduler` 一样，
 * 唯一构造 Worker 的地方就是这里，L2 只通过端口提交任务。
 */
internal class WorkManagerCacheMigrationScheduler(
    private val context: Context,
) : CacheMigrationScheduler {
    override fun enqueue(targetTreeUri: String) {
        val request = OneTimeWorkRequestBuilder<CacheMigrationWorker>()
            .setInputData(workDataOf(CacheMigrationWork.TARGET_TREE_URI to targetTreeUri))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(CacheMigrationWork.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
