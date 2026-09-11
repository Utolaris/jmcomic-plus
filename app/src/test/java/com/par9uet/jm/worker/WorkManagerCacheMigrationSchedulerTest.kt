package com.par9uet.jm.worker

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.par9uet.jm.cache.migration.CacheMigrationWork
import com.par9uet.jm.cache.migration.CacheMigrationWorkState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 同名任务的历史记录里挑"当前这次"的规则。WorkManager 的查询没有 ORDER BY，
 * 所以顺序不能当依据：有未结束的就用它，没有就认本次入队的 id。
 */
class WorkManagerCacheMigrationSchedulerTest {
    @Test
    fun `an unfinished run wins over the finished history`() {
        val running = work(WorkInfo.State.RUNNING, progress = workDataOf(CacheMigrationWork.PROGRESS to 42))

        val state = currentMigrationState(listOf(work(WorkInfo.State.SUCCEEDED), running), lastEnqueuedId = null)

        assertEquals(CacheMigrationWorkState.Running(progress = 42, stage = null), state)
    }

    @Test
    fun `a finished run is matched by the enqueued id instead of the first entry`() {
        val old = work(WorkInfo.State.SUCCEEDED)
        val current = work(WorkInfo.State.FAILED, output = workDataOf(CacheMigrationWork.ERROR to "无法读取缓存路径"))

        // 上一次成功、这一次失败：按列表顺序取第一条会报"迁移完成"。
        val state = currentMigrationState(listOf(old, current), lastEnqueuedId = current.id)

        assertEquals(CacheMigrationWorkState.Failed("无法读取缓存路径"), state)
    }

    @Test
    fun `the enqueued id also picks an older success when it is the one that ran`() {
        val current = work(WorkInfo.State.SUCCEEDED)
        val later = work(WorkInfo.State.FAILED, output = workDataOf(CacheMigrationWork.ERROR to "别的失败"))

        val state = currentMigrationState(listOf(current, later), lastEnqueuedId = current.id)

        assertEquals(CacheMigrationWorkState.Succeeded, state)
    }

    @Test
    fun `without a recorded id the newest entry is used`() {
        val state = currentMigrationState(
            listOf(
                work(WorkInfo.State.SUCCEEDED),
                work(WorkInfo.State.FAILED, output = workDataOf(CacheMigrationWork.ERROR to "冷启动遗留")),
            ),
            lastEnqueuedId = null,
        )

        assertEquals(CacheMigrationWorkState.Failed("冷启动遗留"), state)
    }

    @Test
    fun `a failure without an error message stays reportable`() {
        val state = currentMigrationState(listOf(work(WorkInfo.State.FAILED)), lastEnqueuedId = null)

        assertEquals(CacheMigrationWorkState.Failed(null), state)
    }

    @Test
    fun `a cancelled run reports nothing even when an earlier run succeeded`() {
        val cancelled = work(WorkInfo.State.CANCELLED)

        val state = currentMigrationState(
            listOf(work(WorkInfo.State.SUCCEEDED), cancelled),
            lastEnqueuedId = cancelled.id,
        )

        assertEquals(CacheMigrationWorkState.Idle, state)
    }

    @Test
    fun `an enqueued run reports the stage it already wrote`() {
        val enqueued = work(
            WorkInfo.State.ENQUEUED,
            progress = workDataOf(
                CacheMigrationWork.PROGRESS to 99,
                CacheMigrationWork.STAGE to "正在更新缓存索引",
            ),
        )

        val state = currentMigrationState(listOf(enqueued), lastEnqueuedId = null)

        assertEquals(CacheMigrationWorkState.Running(progress = 99, stage = "正在更新缓存索引"), state)
    }

    @Test
    fun `an empty history is idle`() {
        assertEquals(CacheMigrationWorkState.Idle, currentMigrationState(emptyList(), lastEnqueuedId = null))
    }

    private fun work(
        state: WorkInfo.State,
        id: UUID = UUID.randomUUID(),
        progress: Data = Data.EMPTY,
        output: Data = Data.EMPTY,
    ) = WorkInfo(
        id = id,
        state = state,
        tags = emptySet(),
        outputData = output,
        progress = progress,
    )
}
