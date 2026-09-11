package com.par9uet.jm.cache.migration

import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L2 分支：迁移顺序、失败分类和"提交必须发生在拷贝之后"这三件事都在协调器里，
 * 所以这里用假的操作端口验证决策，真机的文件与数据库行为交给插桩测试。
 */
class CacheMigrationCoordinatorTest {
    @Test
    fun `a target equal to the active tree is a no-op`() = runTest {
        val operations = FakeCacheMigrationOperations(sourceTreeUri = "content://tree")
        val feedback = RecordingFeedback()

        val outcome = coordinator(operations).migrate("content://tree", feedback)

        assertEquals(CacheMigrationOutcome.Success, outcome)
        assertTrue(operations.events.isEmpty())
        assertEquals(listOf("stage:0:${CacheMigrationCoordinator.WAITING_STAGE}"), feedback.events)
    }

    @Test
    fun `a target nested in the active tree is rejected before anything is read`() = runTest {
        val operations = FakeCacheMigrationOperations(sourceTreeUri = "content://tree", overlap = true)
        val feedback = RecordingFeedback()

        val outcome = coordinator(operations).migrate("content://tree/child", feedback)

        assertEquals(CacheMigrationOutcome.Failure("请选择与原缓存目录互不包含的目录"), outcome)
        assertTrue(operations.events.isEmpty())
    }

    @Test
    fun `index and tree switch only happen after every file landed`() = runTest {
        val operations = FakeCacheMigrationOperations(records = listOf(chapter(1)))
        val feedback = RecordingFeedback()

        val outcome = coordinator(operations).migrate("content://target", feedback)

        assertEquals(CacheMigrationOutcome.Success, outcome)
        assertEquals(listOf("guard", "copy:content://target", "config", "commit", "activate:content://target", "prune", "metadata", "empty"), operations.events)
        assertEquals(
            listOf(
                "stage:0:${CacheMigrationCoordinator.WAITING_STAGE}",
                "stage:0:${CacheMigrationCoordinator.WAITING_STAGE}",
                "progress:99:正在迁移缓存文件 · 99%",
                "stage:99:${CacheMigrationCoordinator.COMMITTING_STAGE}",
                "stage:100:${CacheMigrationCoordinator.COMPLETED_STAGE}",
            ),
            feedback.events,
        )
    }

    @Test
    fun `byte progress is deduplicated but stage transitions are not`() = runTest {
        val operations = FakeCacheMigrationOperations(records = listOf(chapter(1)), copyBytes = listOf(1L, 1L))
        val feedback = RecordingFeedback()

        val outcome = coordinator(operations).migrate("content://target", feedback)

        assertEquals(CacheMigrationOutcome.Success, outcome)
        assertEquals(1, feedback.events.count { it.startsWith("progress:") })
    }

    @Test
    fun `a failing source is reported with its message and nothing is committed`() = runTest {
        val operations = FakeCacheMigrationOperations(readFailure = IllegalStateException("无法读取缓存路径，请检查目录授权：x"))
        val feedback = RecordingFeedback()

        val outcome = coordinator(operations).migrate("content://target", feedback)

        assertEquals(CacheMigrationOutcome.Failure("无法读取缓存路径，请检查目录授权：x"), outcome)
        assertTrue(operations.events.isEmpty())
    }

    @Test
    fun `a failing copy never activates the target tree`() = runTest {
        val operations = FakeCacheMigrationOperations(
            records = listOf(chapter(1)),
            copyFailure = IllegalStateException("目标目录与原缓存目录相同"),
        )

        val outcome = coordinator(operations).migrate("content://target", RecordingFeedback())

        assertEquals(CacheMigrationOutcome.Failure("目标目录与原缓存目录相同"), outcome)
        assertEquals(listOf("guard", "copy:content://target"), operations.events)
    }

    @Test
    fun `a cancelled copy propagates instead of being reported as a failure`() = runTest {
        val operations = FakeCacheMigrationOperations(
            records = listOf(chapter(1)),
            copyFailure = CancellationException("缓存迁移已取消"),
        )

        val error = runCatching { coordinator(operations).migrate("content://target", RecordingFeedback()) }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(listOf("guard", "copy:content://target"), operations.events)
    }

    private fun coordinator(operations: CacheMigrationOperations) = CacheMigrationCoordinator(
        operations,
        object : CacheMigrationDownloadGate {
            override suspend fun <T> withIdleDownloads(block: suspend () -> T): T = block()
        },
    )

    private fun chapter(id: Int) = DownloadComic(
        id = id,
        name = "迁移测试",
        authorList = emptyList(),
        coverPath = "",
        zipPath = "",
        progress = 1f,
        status = DownloadStatus.COMPLETE,
        createTime = 1L,
    )

    private class RecordingFeedback : CacheMigrationFeedback {
        val events = mutableListOf<String>()

        override suspend fun stage(percent: Int, stage: String) {
            events += "stage:$percent:$stage"
        }

        override suspend fun progress(percent: Int, stage: String) {
            events += "progress:$percent:$stage"
        }
    }

    private class FakeCacheMigrationOperations(
        private val sourceTreeUri: String = "",
        private val overlap: Boolean = false,
        private val records: List<DownloadComic> = emptyList(),
        private val readFailure: Exception? = null,
        private val copyFailure: Exception? = null,
        private val copyBytes: List<Long> = listOf(1L),
    ) : CacheMigrationOperations {
        val events = mutableListOf<String>()

        override fun sourceTreeUri() = sourceTreeUri

        override fun treesOverlap(sourceTreeUri: String, targetTreeUri: String) = overlap

        override suspend fun records(): List<DownloadComic> {
            readFailure?.let { throw it }
            return records
        }

        override fun resolveSources(records: List<DownloadComic>) =
            CacheMigrationSources(emptyList(), emptySet(), emptyMap(), emptyMap())

        override fun ensureTargetDoesNotOverlapSources(targetTreeUri: String, sourcePaths: List<String>) {
            events += "guard"
        }

        override fun size(path: String) = 0L

        override suspend fun copy(
            sources: CacheMigrationSources,
            records: List<DownloadComic>,
            targetTreeUri: String,
            onBytes: suspend (Long) -> Unit,
        ): CacheMigrationCopy {
            events += "copy:$targetTreeUri"
            copyBytes.forEach { onBytes(it) }
            copyFailure?.let { throw it }
            return CacheMigrationCopy(records, emptyMap())
        }

        override fun writeConfigs(copy: CacheMigrationCopy) {
            events += "config"
        }

        override suspend fun commitRecords(records: List<DownloadComic>) {
            events += "commit"
        }

        override fun activateTargetTree(targetTreeUri: String) {
            events += "activate:$targetTreeUri"
        }

        override fun pruneSources(sourcePaths: List<String>, copy: CacheMigrationCopy) {
            events += "prune"
        }

        override fun removeSourceMetadata(records: List<DownloadComic>, scannedRoots: Set<String>) {
            events += "metadata"
        }

        override fun removeEmptyDefaultCacheDirectories() {
            events += "empty"
        }
    }
}
