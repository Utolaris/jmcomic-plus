package com.par9uet.jm.cache.migration

/**
 * L2：入队方（`CachePathViewModel`）与 `worker/CacheMigrationWorker` 共用的 WorkManager 契约。
 * 键与唯一任务名放在这里而不是 Worker 里，L2 就不必反向 import L1。
 */
object CacheMigrationWork {
    const val UNIQUE_WORK_NAME = "cache_path_migration"
    const val TARGET_TREE_URI = "target_tree_uri"
    const val PROGRESS = "progress"
    const val STAGE = "stage"
    const val ERROR = "error"
}
