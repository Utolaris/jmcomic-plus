package com.par9uet.jm.reader.molecule

import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.download.downloadTask
import com.par9uet.jm.reader.atom.LocalChapterFiles
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LoadLocalChapterTest {
    @Test fun `loads group chapters and files on IO and returns only complete chapter query results`() = runTest {
        val task = downloadTask(11, DownloadStatus.COMPLETE)
        val dao = Proxy.newProxyInstance(DownloadComicDao::class.java.classLoader, arrayOf(DownloadComicDao::class.java)) { _, method, args ->
            when (method.name) {
                "getById" -> task
                "getCompleteByGroupId" -> {
                    assertEquals(100, args[0])
                    listOf(task.copy(chapterName = "第一章"), task.copy(id = 12))
                }
                else -> error("Unexpected DAO call")
            }
        } as DownloadComicDao
        Executors.newSingleThreadExecutor { Thread(it, "local-reader-io") }.asCoroutineDispatcher().use { io ->
            val loader = LoadLocalChapter(dao, LocalChapterFiles { id, item ->
                assertEquals("local-reader-io", Thread.currentThread().name)
                assertEquals(11, id)
                assertEquals(task, item)
                listOf("/old/0.webp", "/old/1.webp")
            }, io)
            val result = loader(11)
            assertEquals(100, result.groupId)
            assertEquals(listOf("第一章", "第 2 章"), result.chapters.map { it.name })
            assertEquals(listOf("/old/0.webp", "/old/1.webp"), result.imagePaths)
        }
    }
}
