package com.par9uet.jm.cache.migration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What a finished migration reports back to the entry layer. */
sealed interface CacheMigrationOutcome {
    data object Success : CacheMigrationOutcome
    data class Failure(val message: String) : CacheMigrationOutcome
}

/**
 * Runs [block] once in-flight download writes have finished, holding new writers off for the whole
 * migration. A narrow callback keeps this coordinator from depending on the download executor.
 */
interface CacheMigrationDownloadGate {
    suspend fun <T> withIdleDownloads(block: suspend () -> T): T
}

/**
 * Progress delivery for one migration. [stage] marks a control-flow transition and must reach the
 * user; [progress] comes from the byte copy loop, so dropping or throttling it is harmless.
 */
interface CacheMigrationFeedback {
    suspend fun stage(percent: Int, stage: String)
    suspend fun progress(percent: Int, stage: String)
}

/**
 * L2: owns the migration order and its failure branches. Every source is resolved before the
 * destination is touched; cache index rows are committed in one Room transaction, then the
 * active tree is swapped. The commit runs non-cancellable. Remaining window: the DB transaction
 * and the preference/tree switch are not one cross-storage atomic unit — a crash between them
 * can leave the tree switched after rows committed (or vice versa) and needs a retry path.
 */
class CacheMigrationCoordinator(
    private val operations: CacheMigrationOperations,
    private val downloads: CacheMigrationDownloadGate,
) {
    suspend fun migrate(targetTreeUri: String, feedback: CacheMigrationFeedback): CacheMigrationOutcome {
        feedback.stage(0, WAITING_STAGE)
        return downloads.withIdleDownloads { run(targetTreeUri, feedback) }
    }

    private suspend fun run(targetTreeUri: String, feedback: CacheMigrationFeedback): CacheMigrationOutcome =
        withContext(Dispatchers.IO) {
            val sourceTreeUri = operations.sourceTreeUri()
            if (sourceTreeUri == targetTreeUri) return@withContext CacheMigrationOutcome.Success
            if (sourceTreeUri.isNotBlank() && targetTreeUri.isNotBlank() &&
                operations.treesOverlap(sourceTreeUri, targetTreeUri)
            ) {
                return@withContext CacheMigrationOutcome.Failure("请选择与原缓存目录互不包含的目录")
            }

            try {
                feedback.stage(0, WAITING_STAGE)

                val records = operations.records()
                // Resolve every source before touching the destination, so a provider that cannot
                // answer stops the migration instead of leaving a half-filled target behind.
                val sources = operations.resolveSources(records)
                operations.ensureTargetDoesNotOverlapSources(targetTreeUri, sources.paths)
                val totalBytes = sources.paths.sumOf(operations::size).coerceAtLeast(1L)
                var copiedBytes = 0L
                var lastReportedPercent = -1

                val copy = operations.copy(sources, records, targetTreeUri) { delta ->
                    copiedBytes += delta
                    val percent = ((copiedBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
                    if (percent != lastReportedPercent) {
                        lastReportedPercent = percent
                        feedback.progress(percent, "正在迁移缓存文件 · $percent%")
                    }
                }
                operations.writeConfigs(copy)

                feedback.stage(99, COMMITTING_STAGE)
                withContext(NonCancellable) {
                    operations.commitRecords(copy.records)
                    operations.activateTargetTree(targetTreeUri)
                    operations.pruneSources(sources.paths, copy)
                    operations.removeSourceMetadata(records, sources.scannedRoots)
                    operations.removeEmptyDefaultCacheDirectories()
                }
                feedback.stage(100, COMPLETED_STAGE)
                CacheMigrationOutcome.Success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CacheMigrationOutcome.Failure(error.message ?: "缓存迁移失败，原路径未切换")
            }
        }

    companion object {
        const val WAITING_STAGE = "正在等待当前缓存任务结束"
        const val COMMITTING_STAGE = "正在更新缓存索引"
        const val COMPLETED_STAGE = "迁移完成"
    }
}
