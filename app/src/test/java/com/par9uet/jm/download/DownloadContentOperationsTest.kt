package com.par9uet.jm.download

import android.graphics.Bitmap
import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.download.atom.DownloadContentStorage
import com.par9uet.jm.download.atom.DownloadCoverImages
import com.par9uet.jm.download.atom.DownloadPageDecoder
import com.par9uet.jm.download.molecule.DeviceDownloadContentOperations
import com.par9uet.jm.image.cancellationExceptionOrNull
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.retrofit.model.ComicPicListResponse
import com.par9uet.jm.core.network.NetWorkResult
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DownloadContentOperationsTest {
    private val dao = RecordingDownloadDao()
    private val task = DownloadComic(
        id = 1, name = "漫画", authorList = emptyList(), coverPath = "/cover.webp", zipPath = "",
        progress = 1f, status = DownloadStatus.DOWNLOADING, createTime = 1, groupId = 100,
    )
    private var response: NetWorkResult<ComicPicListResponse> = NetWorkResult.Success(
        ComicPicListResponse(listOf("one", "two"), 1, 123, "speed"),
    )
    private val existing = mutableSetOf(0, 1)
    private var saved: DownloadComic? = null
    private val cancelled = CancellationException("cancelled")
    private val repository = Proxy.newProxyInstance(
        ComicRepository::class.java.classLoader, arrayOf(ComicRepository::class.java),
    ) { _, method, _ ->
        check(method.name == "getComicPicList") { "Unexpected repository call: ${method.name}" }
        response
    } as ComicRepository
    private val storage = object : DownloadContentStorage {
        override fun chapterPath(task: DownloadComic) = "/chapter"
        override fun pageExists(chapterPath: String, index: Int) = index in existing
        override fun writePage(chapterPath: String, index: Int, bitmap: Bitmap): Long = error("Unexpected write")
        override fun writeCover(task: DownloadComic, bitmap: Bitmap): String = error("Unexpected cover write")
        override fun writeConfig(current: DownloadComic, chapters: List<DownloadComic>) {
            assertEquals(DownloadStatus.COMPLETE, current.status)
            assertEquals("/chapter", current.zipPath)
            assertEquals(listOf(current), chapters)
            saved = current
        }
    }
    private val content = DeviceDownloadContentOperations(
        dao, repository,
        DownloadPageDecoder { image ->
            assertEquals(0, image.index)
            assertEquals(1, image.comicId)
            throw cancelled
        },
        CoverImageHostResolver(knownHosts = listOf("cdn.example")),
        DownloadCoverImages { _, _ -> error("Unexpected cover request") },
        storage,
    )

    @Test
    fun `existing pages skip decoding and still advance progress`() = runTest {
        val progress = mutableListOf<Float>()
        content.downloadPages(task) { progress += it }
        assertEquals(listOf(0.5f, 1f), progress)
    }

    @Test
    fun `missing page cancellation retains cause for coordinator propagation`() = runTest {
        existing.clear()
        try {
            content.downloadPages(task) { fail("No page was saved") }
            fail("Expected cancellation")
        } catch (actual: Exception) {
            val propagated = actual.cancellationExceptionOrNull()
            assertNotNull(propagated)
            assertEquals(cancelled.message, propagated!!.message)
        }
    }

    @Test
    fun `empty and failed page lists fail before progress updates`() = runTest {
        for (result in listOf(
            NetWorkResult.Success(ComicPicListResponse(emptyList(), 1, 123, "")),
            NetWorkResult.Error("network"),
        )) {
            response = result
            try {
                content.downloadPages(task) { fail("No page was saved") }
                fail("Expected failure")
            } catch (_: IllegalStateException) {
                // Both errors must reach the coordinator's retry policy.
            }
        }
    }

    @Test
    fun `completion writes refreshed path status and group into cache config`() = runTest {
        dao.tasks[1] = task
        content.complete(task)
        assertEquals(dao.tasks[1], saved)
        assertNotNull(saved)
    }
}
