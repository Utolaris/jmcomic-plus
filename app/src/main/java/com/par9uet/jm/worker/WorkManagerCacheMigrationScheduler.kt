package com.par9uet.jm.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.par9uet.jm.cache.migration.CacheMigrationScheduler
import com.par9uet.jm.cache.migration.CacheMigrationWork
import com.par9uet.jm.cache.migration.CacheMigrationWorkState
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `CacheMigrationScheduler` 的 WorkManager 实现。和 `WorkManagerDownloadWorkScheduler` 一样，
 * 唯一构造 Worker 的地方就是这里，L2 只通过端口提交任务、拿状态。
 */
internal class WorkManagerCacheMigrationScheduler(
    private val context: Context,
) : CacheMigrationScheduler {
    private val workManager = WorkManager.getInstance(context)

    /** 本进程最后一次入队的那次；用来在同名任务的历史记录里认出"这次"。 */
    private var lastEnqueuedId: UUID? = null

    override fun enqueue(targetTreeUri: String) {
        val request = OneTimeWorkRequestBuilder<CacheMigrationWorker>()
            .setInputData(workDataOf(CacheMigrationWork.TARGET_TREE_URI to targetTreeUri))
            .build()
        lastEnqueuedId = request.id
        workManager.enqueueUniqueWork(CacheMigrationWork.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun observe(): Flow<CacheMigrationWorkState> =
        workManager.getWorkInfosForUniqueWorkFlow(CacheMigrationWork.UNIQUE_WORK_NAME)
            .map { works -> currentMigrationState(works, lastEnqueuedId) }
}

/**
 * 从同名任务的全部历史记录里挑出"当前这次"。
 *
 * WorkManager 只保证这一组，不保证顺序（`getWorkInfosForUniqueWork` 的查询没有 ORDER BY），
 * 所以先看有没有未结束的（唯一任务名 + KEEP，至多一条），再按本次入队的 id 认领，
 * 最后才退回列表末尾——冷启动时唯一可用的线索，宁可给出最近一次的结果也不要按列表顺序乱挑。
 */
internal fun currentMigrationState(works: List<WorkInfo>, lastEnqueuedId: UUID?): CacheMigrationWorkState {
    val work = works.firstOrNull { !it.state.isFinished }
        ?: works.firstOrNull { it.id == lastEnqueuedId }
        ?: works.lastOrNull()
        ?: return CacheMigrationWorkState.Idle
    return when (work.state) {
        WorkInfo.State.SUCCEEDED -> CacheMigrationWorkState.Succeeded
        WorkInfo.State.FAILED -> CacheMigrationWorkState.Failed(
            work.outputData.getString(CacheMigrationWork.ERROR),
        )
        WorkInfo.State.CANCELLED -> CacheMigrationWorkState.Idle
        else -> CacheMigrationWorkState.Running(
            progress = work.progress.getInt(CacheMigrationWork.PROGRESS, 0),
            stage = work.progress.getString(CacheMigrationWork.STAGE),
        )
    }
}
