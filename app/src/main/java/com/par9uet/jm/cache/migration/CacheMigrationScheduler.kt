package com.par9uet.jm.cache.migration

import kotlinx.coroutines.flow.Flow

/**
 * L2 端口：提交一次缓存目录迁移，并观察它的进展。
 *
 * 决定"何时迁移、怎么展示"的界面层不必认识 WorkManager，也不会 import `worker.*`：
 * Worker 的构造、唯一任务名和 `WorkInfo` 的解读都留在实现里
 * （`worker/WorkManagerCacheMigrationScheduler`）。
 */
interface CacheMigrationScheduler {
    fun enqueue(targetTreeUri: String)

    /** 当前迁移的状态，含进程重启前就结束的那次留下的结果。 */
    fun observe(): Flow<CacheMigrationWorkState>
}
