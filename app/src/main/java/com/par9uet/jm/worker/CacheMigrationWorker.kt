package com.par9uet.jm.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.par9uet.jm.cache.migration.CacheMigrationCoordinator
import com.par9uet.jm.cache.migration.CacheMigrationFeedback
import com.par9uet.jm.cache.migration.CacheMigrationNotifications
import com.par9uet.jm.cache.migration.CacheMigrationOutcome
import com.par9uet.jm.cache.migration.CacheMigrationWork

/**
 * L1 entry: reads the requested target tree, hands the migration to the coordinator and maps the
 * outcome onto a WorkManager result. Progress only carries the WorkManager data keys; the
 * notification itself is built by [CacheMigrationNotifications].
 */
class CacheMigrationWorker(
    appContext: Context,
    params: WorkerParameters,
    private val coordinator: CacheMigrationCoordinator,
) : CoroutineWorker(appContext, params) {
    private val notifications by lazy { CacheMigrationNotifications(applicationContext) }
    private val feedback = object : CacheMigrationFeedback {
        override suspend fun stage(percent: Int, stage: String) = publish(percent, stage)

        override suspend fun progress(percent: Int, stage: String) {
            // A dropped progress update must never abort a migration that is otherwise complete.
            runCatching { publish(percent, stage) }
        }
    }

    override suspend fun doWork(): Result = when (
        val outcome = coordinator.migrate(
            targetTreeUri = inputData.getString(CacheMigrationWork.TARGET_TREE_URI).orEmpty(),
            feedback = feedback,
        )
    ) {
        is CacheMigrationOutcome.Success -> Result.success(
            workDataOf(
                CacheMigrationWork.PROGRESS to 100,
                CacheMigrationWork.STAGE to CacheMigrationCoordinator.COMPLETED_STAGE,
            ),
        )
        is CacheMigrationOutcome.Failure -> Result.failure(
            workDataOf(CacheMigrationWork.ERROR to outcome.message),
        )
    }

    private suspend fun publish(percent: Int, stage: String) {
        setProgress(workDataOf(CacheMigrationWork.PROGRESS to percent, CacheMigrationWork.STAGE to stage))
        setForeground(notifications.create(percent, stage))
    }
}
