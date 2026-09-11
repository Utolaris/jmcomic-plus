package com.par9uet.jm.worker

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.par9uet.jm.cache.getCacheMigrationRequestId
import com.par9uet.jm.cache.migration.CacheMigrationScheduler
import com.par9uet.jm.cache.migration.CacheMigrationWork
import com.par9uet.jm.cache.migration.CacheMigrationWorkState
import com.par9uet.jm.cache.setCacheMigrationRequestId
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

    override fun enqueue(targetTreeUri: String) {
        val request = OneTimeWorkRequestBuilder<CacheMigrationWorker>()
            .setInputData(workDataOf(CacheMigrationWork.TARGET_TREE_URI to targetTreeUri))
            .build()
        // 记到磁盘，进程重启之后还要靠它认出"这次"的结果。
        setCacheMigrationRequestId(context, request.id.toString())
        workManager.enqueueUniqueWork(CacheMigrationWork.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun observe(): Flow<CacheMigrationWorkState> =
        workManager.getWorkInfosForUniqueWorkFlow(CacheMigrationWork.UNIQUE_WORK_NAME)
            .map { works -> currentMigrationState(works, lastRequestId()) }

    private fun lastRequestId(): UUID? = getCacheMigrationRequestId(context)
        ?.let { id -> runCatching { UUID.fromString(id) }.getOrNull() }
}

/**
 * 从同名任务的全部历史记录里挑出"当前这次"。
 *
 * WorkManager 只保证这一组，不保证顺序（`getWorkInfosForUniqueWork` 的查询没有 ORDER BY），
 * 所以绝不按列表顺序挑：先看有没有未结束的（唯一任务名 + KEEP，至多一条），再按入队时记下的
 * id 认领；认不出来就什么都不显示——宁可没有结果，也不把更早的结果当成本次的结果。
 */
internal fun currentMigrationState(works: List<WorkInfo>, lastRequestId: UUID?): CacheMigrationWorkState {
    val work = works.firstOrNull { !it.state.isFinished }
        ?: works.firstOrNull { it.id == lastRequestId }
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
