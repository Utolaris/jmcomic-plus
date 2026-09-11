package com.par9uet.jm.cache.migration

/** L2 契约：迁移任务对界面可见的状态。字段与 Worker 写入的 progress / output data 一一对应。 */
sealed interface CacheMigrationWorkState {
    /** 没有正在跑的迁移，也没有可用的结果。 */
    data object Idle : CacheMigrationWorkState

    /** 迁移已入队或正在执行；[stage] 为空表示任务还没上报过阶段。 */
    data class Running(val progress: Int, val stage: String?) : CacheMigrationWorkState

    /** 迁移结束并成功。 */
    data object Succeeded : CacheMigrationWorkState

    /** 迁移失败；[message] 为空表示失败时没有留下原因。 */
    data class Failed(val message: String?) : CacheMigrationWorkState
}
