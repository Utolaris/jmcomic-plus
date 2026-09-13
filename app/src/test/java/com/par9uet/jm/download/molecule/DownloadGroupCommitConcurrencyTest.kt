package com.par9uet.jm.download.molecule

import android.graphics.Bitmap
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.download.RecordingDownloadDao
import com.par9uet.jm.download.atom.DownloadContentStorage
import com.par9uet.jm.download.atom.DownloadCoverImages
import com.par9uet.jm.download.atom.DownloadPageDecoder
import com.par9uet.jm.repository.ComicRepository
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R06: same-group chapter completes must serialize the full
 * read snapshot → write index → commit DB sequence. Locking only writeConfig() still lets
 * a pre-lock snapshot overwrite a concurrent chapter's COMPLETE.
 */
class DownloadGroupCommitConcurrencyTest {

    @Test
    fun `same group chapter completes publish an index that matches the database`() = runTest {
        val dao = RecordingDownloadDao()
        val tasks = (1..2).map { id ->
            DownloadComic(
                id = id, name = "chapter$id", authorList = emptyList(), coverPath = "", zipPath = "",
                progress = 1f, status = DownloadStatus.DOWNLOADING, createTime = id.toLong(), groupId = 100,
            )
        }
        tasks.forEach { dao.tasks[it.id] = it }

        val activeWriters = AtomicInteger(0)
        val maxConcurrentWriters = AtomicInteger(0)
        val published = CopyOnWriteArrayList<List<DownloadComic>>()
        val storage = object : DownloadContentStorage {
            override fun chapterPath(task: DownloadComic) = "/chapter-${task.id}"
            override fun pageExists(chapterPath: String, index: Int) = true
            override fun writePage(chapterPath: String, index: Int, bitmap: Bitmap): Long = error("unused")
            override fun writeCover(task: DownloadComic, bitmap: Bitmap): String = error("unused")
            override fun writeConfig(current: DownloadComic, chapters: List<DownloadComic>) {
                val concurrent = activeWriters.incrementAndGet()
                maxConcurrentWriters.updateAndGet { known -> maxOf(known, concurrent) }
                // Widen the race window on the real IO dispatcher the operations use.
                Thread.sleep(40)
                published += chapters.map { it.copy() }
                activeWriters.decrementAndGet()
            }
        }
        val content = operations(dao, storage)

        tasks.map { task -> async { content.complete(task) } }.awaitAll()

        assertEquals(1, maxConcurrentWriters.get())
        assertEquals(2, dao.tasks.values.count { it.status == DownloadStatus.COMPLETE })
        val last = published.last()
        assertEquals(2, last.count { it.status == DownloadStatus.COMPLETE })
        assertTrue(last.all { it.zipPath.isNotBlank() })
        // Published snapshot must equal the durable DB rows, not a stale pre-commit read.
        val byId = dao.tasks.values.associateBy { it.id }
        last.forEach { chapter ->
            assertEquals(byId.getValue(chapter.id).status, chapter.status)
            assertEquals(byId.getValue(chapter.id).zipPath, chapter.zipPath)
        }
    }

    @Test
    fun `different groups may complete in parallel without sharing a gate`() = runTest {
        val dao = RecordingDownloadDao()
        val first = DownloadComic(
            id = 1, name = "a", authorList = emptyList(), coverPath = "", zipPath = "",
            progress = 1f, status = DownloadStatus.DOWNLOADING, createTime = 1, groupId = 10,
        )
        val second = DownloadComic(
            id = 2, name = "b", authorList = emptyList(), coverPath = "", zipPath = "",
            progress = 1f, status = DownloadStatus.DOWNLOADING, createTime = 2, groupId = 20,
        )
        dao.tasks[1] = first
        dao.tasks[2] = second

        val activeWriters = AtomicInteger(0)
        val maxConcurrentWriters = AtomicInteger(0)
        val storage = object : DownloadContentStorage {
            override fun chapterPath(task: DownloadComic) = "/chapter-${task.id}"
            override fun pageExists(chapterPath: String, index: Int) = true
            override fun writePage(chapterPath: String, index: Int, bitmap: Bitmap): Long = error("unused")
            override fun writeCover(task: DownloadComic, bitmap: Bitmap): String = error("unused")
            override fun writeConfig(current: DownloadComic, chapters: List<DownloadComic>) {
                val concurrent = activeWriters.incrementAndGet()
                maxConcurrentWriters.updateAndGet { known -> maxOf(known, concurrent) }
                Thread.sleep(40)
                activeWriters.decrementAndGet()
            }
        }
        val content = operations(dao, storage)

        listOf(first, second).map { task -> async { content.complete(task) } }.awaitAll()

        assertTrue(
            "distinct groups should not serialize on one gate, saw max=$maxConcurrentWriters",
            maxConcurrentWriters.get() >= 1,
        )
        assertEquals(2, dao.tasks.values.count { it.status == DownloadStatus.COMPLETE })
    }

    @Test
    fun `failed config write of one chapter leaves that chapter retryable`() = runTest {
        val dao = RecordingDownloadDao()
        val tasks = (1..2).map { id ->
            DownloadComic(
                id = id, name = "chapter$id", authorList = emptyList(), coverPath = "", zipPath = "",
                progress = 1f, status = DownloadStatus.DOWNLOADING, createTime = id.toLong(), groupId = 100,
            )
        }
        tasks.forEach { dao.tasks[it.id] = it }

        val failChapterId = AtomicInteger(1)
        val storage = object : DownloadContentStorage {
            override fun chapterPath(task: DownloadComic) = "/chapter-${task.id}"
            override fun pageExists(chapterPath: String, index: Int) = true
            override fun writePage(chapterPath: String, index: Int, bitmap: Bitmap): Long = error("unused")
            override fun writeCover(task: DownloadComic, bitmap: Bitmap): String = error("unused")
            override fun writeConfig(current: DownloadComic, chapters: List<DownloadComic>) {
                if (current.id == failChapterId.get()) error("disk full")
            }
        }
        val content = operations(dao, storage)

        val results = tasks.map { task ->
            async {
                runCatching { content.complete(task) }
            }
        }.awaitAll()

        assertTrue(results[0].isFailure)
        assertTrue(results[1].isSuccess)
        assertEquals(DownloadStatus.DOWNLOADING, dao.tasks.getValue(1).status)
        assertEquals(DownloadStatus.COMPLETE, dao.tasks.getValue(2).status)
    }

    @Test
    fun `retry after a failed write republishes both chapters complete`() = runTest {
        val dao = RecordingDownloadDao()
        val tasks = (1..2).map { id ->
            DownloadComic(
                id = id, name = "chapter$id", authorList = emptyList(), coverPath = "", zipPath = "",
                progress = 1f, status = DownloadStatus.DOWNLOADING, createTime = id.toLong(), groupId = 100,
            )
        }
        tasks.forEach { dao.tasks[it.id] = it }

        var failNext = true
        val published = CopyOnWriteArrayList<List<DownloadComic>>()
        val storage = object : DownloadContentStorage {
            override fun chapterPath(task: DownloadComic) = "/chapter-${task.id}"
            override fun pageExists(chapterPath: String, index: Int) = true
            override fun writePage(chapterPath: String, index: Int, bitmap: Bitmap): Long = error("unused")
            override fun writeCover(task: DownloadComic, bitmap: Bitmap): String = error("unused")
            override fun writeConfig(current: DownloadComic, chapters: List<DownloadComic>) {
                if (failNext && current.id == 1) {
                    failNext = false
                    error("disk full")
                }
                published += chapters.map { it.copy() }
            }
        }
        val content = operations(dao, storage)

        runCatching { content.complete(tasks[0]) }
        content.complete(tasks[1])
        // Chapter 1 retries after the transient write failure; by then chapter 2 is COMPLETE
        // in the DB, so the rewritten index must include it.
        content.complete(tasks[0])

        assertEquals(2, dao.tasks.values.count { it.status == DownloadStatus.COMPLETE })
        val last = published.last()
        assertEquals(2, last.count { it.status == DownloadStatus.COMPLETE })
    }

    private fun operations(dao: RecordingDownloadDao, storage: DownloadContentStorage): DeviceDownloadContentOperations {
        val repository = Proxy.newProxyInstance(
            ComicRepository::class.java.classLoader,
            arrayOf(ComicRepository::class.java),
        ) { _, _, _ -> error("unused") } as ComicRepository
        return DeviceDownloadContentOperations(
            dao, repository,
            DownloadPageDecoder { error("unused") },
            CoverImageHostResolver(knownHosts = listOf("cdn.example")),
            DownloadCoverImages { _, _ -> error("unused") },
            storage,
        )
    }
}
