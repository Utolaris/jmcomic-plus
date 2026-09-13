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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * F08: config write must run before COMPLETE is committed; a failed write must not leave
 * a COMPLETE task that subsequent retries skip.
 */
class DownloadCompleteOrderTest {
    @Test
    fun `failed config write leaves status not COMPLETE so retry can rewrite`() = runTest {
        val dao = RecordingDownloadDao()
        val task = DownloadComic(
            id = 1, name = "漫画", authorList = emptyList(), coverPath = "", zipPath = "",
            progress = 1f, status = DownloadStatus.DOWNLOADING, createTime = 1, groupId = 0,
        )
        dao.tasks[1] = task
        val repository = Proxy.newProxyInstance(
            ComicRepository::class.java.classLoader,
            arrayOf(ComicRepository::class.java),
        ) { _, _, _ -> error("unused") } as ComicRepository
        val storage = object : DownloadContentStorage {
            override fun chapterPath(task: DownloadComic) = "/chapter"
            override fun pageExists(chapterPath: String, index: Int) = true
            override fun writePage(chapterPath: String, index: Int, bitmap: Bitmap): Long = error("unused")
            override fun writeCover(task: DownloadComic, bitmap: Bitmap): String = error("unused")
            override fun writeConfig(current: DownloadComic, chapters: List<DownloadComic>) {
                error("disk full")
            }
        }
        val content = DeviceDownloadContentOperations(
            dao, repository,
            DownloadPageDecoder { error("unused") },
            CoverImageHostResolver(knownHosts = listOf("cdn.example")),
            DownloadCoverImages { _, _ -> error("unused") },
            storage,
        )

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { content.complete(task) }
        }
        assertEquals(DownloadStatus.DOWNLOADING, dao.tasks[1]!!.status)
    }
}
