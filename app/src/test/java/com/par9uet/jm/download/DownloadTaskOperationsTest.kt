package com.par9uet.jm.download

import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.download.atom.DownloadFiles
import com.par9uet.jm.download.molecule.DownloadTaskOperations
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadTaskOperationsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val dao = RecordingDownloadDao()
    private val operations = DownloadTaskOperations(dao, DownloadFiles())

    @Test
    fun `creation preserves metadata and skips existing comics`() = runTest {
        val comic = Comic.create(id = 10, name = "漫画", authorList = listOf("作者"))
        val first = operations.downloadComic(comic)!!
        assertEquals(listOf(10), first.comicIds)
        val saved = dao.tasks.getValue(10)
        assertEquals(comic.name, saved.name)
        assertEquals(comic.authorList, saved.authorList)
        assertEquals(comic.tagList, saved.tagList)
        assertEquals(10, saved.groupId)
        assertEquals(DownloadStatus.PENDING, saved.status)
        assertEquals(0f, saved.progress)
        assertTrue(operations.downloadComic(comic)!!.comicIds.isEmpty())
        assertEquals(saved, dao.tasks[10])
        val batch = operations.downloadComics(listOf(comic, Comic.create(id = 11, name = "漫画", authorList = emptyList())))!!
        assertEquals(listOf(11), batch.comicIds)
        assertEquals("已创建 1 个缓存任务，跳过 1 个已存在漫画", batch.message)
    }

    @Test
    fun `chapters keep supplied order and parent metadata while skipping existing tasks`() = runTest {
        dao.tasks[2] = task(2)
        val parent = Comic.create(id = 100, name = "合集", authorList = listOf("作者"))
        val result = operations.downloadChapters(parent, listOf(
            ComicChapter(id = 3, name = "第三章"),
            ComicChapter(id = 2, name = "第二章"),
            ComicChapter(id = 1, name = "第一章"),
        ))!!
        assertEquals(listOf(3, 1), result.comicIds)
        assertEquals(dao.tasks.getValue(3).createTime + 1, dao.tasks.getValue(1).createTime)
        assertEquals(100, dao.tasks.getValue(3).groupId)
        assertEquals("合集 第三章", dao.tasks.getValue(3).name)
        assertEquals("第三章", dao.tasks.getValue(3).chapterName)
        assertEquals(parent.authorList, dao.tasks.getValue(3).authorList)
        assertEquals("已创建 2 个缓存任务，跳过 1 个已存在章节", result.message)
    }

    @Test
    fun `retry resets progress while resume preserves progress and excludes complete or missing tasks`() = runTest {
        dao.tasks[1] = task(1, DownloadStatus.ERROR)
        dao.tasks[2] = task(2, DownloadStatus.PAUSED)
        dao.tasks[3] = task(3, DownloadStatus.COMPLETE)
        assertEquals(listOf(1), operations.retryDownload(1)!!.comicIds)
        assertEquals(0f, dao.tasks.getValue(1).progress)
        assertEquals(DownloadStatus.PENDING, dao.tasks.getValue(1).status)
        assertEquals(listOf(2), operations.resumeDownloads(listOf(2, 3, 9, 2))!!.comicIds)
        assertEquals(0.6f, dao.tasks.getValue(2).progress)
        assertEquals(DownloadStatus.PENDING, dao.tasks.getValue(2).status)
        assertEquals(DownloadStatus.COMPLETE, dao.tasks.getValue(3).status)
    }

    @Test
    fun `group retry resets only failed chapters`() = runTest {
        dao.tasks[1] = task(1, DownloadStatus.ERROR)
        dao.tasks[2] = task(2, DownloadStatus.PAUSED)
        dao.tasks[3] = task(3, DownloadStatus.COMPLETE)
        assertEquals(listOf(1), operations.retryGroup(100)!!.comicIds)
        assertEquals(0f, dao.tasks.getValue(1).progress)
        assertEquals(DownloadStatus.PENDING, dao.tasks.getValue(1).status)
        assertEquals(DownloadStatus.PAUSED, dao.tasks.getValue(2).status)
        assertEquals(0.6f, dao.tasks.getValue(2).progress)
        assertEquals(DownloadStatus.COMPLETE, dao.tasks.getValue(3).status)
    }

    @Test
    fun `redownload removes old archives directories and covers before resetting all chapters`() = runTest {
        val archive = temporaryFolder.newFile("pages.zip")
        val directory = temporaryFolder.newFolder("pages")
        File(directory, "1.jpg").writeText("page")
        val cover = temporaryFolder.newFile("cover.jpg")
        dao.tasks[1] = task(1, DownloadStatus.COMPLETE).copy(zipPath = archive.path, coverPath = cover.path)
        dao.tasks[2] = task(2).copy(zipPath = directory.path)
        assertEquals(listOf(1, 2), operations.redownloadGroup(100)!!.comicIds)
        assertFalse(archive.exists())
        assertFalse(directory.exists())
        assertFalse(cover.exists())
        dao.tasks.values.forEach {
            assertEquals(DownloadStatus.PENDING, it.status)
            assertEquals(0f, it.progress)
        }
        assertEquals(archive.path, dao.tasks.getValue(1).zipPath)
    }

    @Test
    fun `empty and missing tasks produce no queue work`() = runTest {
        assertNull(operations.downloadComics(emptyList()))
        assertNull(operations.downloadChapters(Comic.create(id = 1, name = "漫画", authorList = emptyList()), emptyList()))
        assertNull(operations.retryDownload(404))
        assertNull(operations.resumeDownloads(emptyList()))
        assertTrue(operations.resumeDownloads(listOf(404))!!.comicIds.isEmpty())
        assertNull(operations.retryGroup(404))
        assertNull(operations.redownloadGroup(404))
    }

    @Test
    fun `storage failure and cancellation propagate without a success result`() = runTest {
        for (failure in listOf(IOException("disk full"), CancellationException("cancelled"))) {
            dao.failure = failure
            try {
                operations.downloadComic(Comic.create(id = 1, name = "漫画", authorList = emptyList()))
                fail("Expected failure")
            } catch (actual: Exception) {
                assertSame(failure, actual)
            }
            assertTrue(dao.tasks.isEmpty())
        }
    }

    private fun task(id: Int, status: DownloadStatus = DownloadStatus.ERROR) = DownloadComic(
        id = id, name = "漫画", authorList = emptyList(), coverPath = "", zipPath = "",
        progress = 0.6f, status = status, createTime = id.toLong(), groupId = 100,
    )
}
