package com.par9uet.jm.cache.migration

/**
 * L2 端口：提交一次缓存目录迁移。决定"何时迁移"的 `CachePathViewModel` 不必知道
 * "怎么执行"，Worker 类型留在 `worker/` 的实现里（`WorkManagerCacheMigrationScheduler`）。
 */
interface CacheMigrationScheduler {
    fun enqueue(targetTreeUri: String)
}
