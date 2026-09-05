package com.par9uet.jm.worker

import com.par9uet.jm.coil.CoverImageHostResolver
import com.par9uet.jm.coil.jmCoverCacheKey
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadComicWorkerRegressionTest {
    @Test
    fun `retry stops at final attempt`() {
        assertTrue(shouldRetryDownload(0))
        assertTrue(shouldRetryDownload(DOWNLOAD_MAX_ATTEMPTS - 2))
        assertEquals(false, shouldRetryDownload(DOWNLOAD_MAX_ATTEMPTS - 1))
    }

    @Test
    fun `progress never moves backwards and group progress averages chapters`() {
        assertEquals(0.8f, advancedDownloadProgress(0.8f, 0.2f), 0f)
        val chapters = listOf(
            task(1, DownloadStatus.COMPLETE, 0.4f),
            task(2, DownloadStatus.DOWNLOADING, 0.3f),
            task(3, DownloadStatus.PENDING, 0.9f),
        )

        assertEquals((1f + 0.75f + 0.9f) / 3f, groupDownloadProgress(chapters, 2, 0.75f), 0.0001f)
    }

    @Test
    fun `cover candidates remain sequential while sharing one cache identity`() {
        val resolver = CoverImageHostResolver(knownHosts = listOf("a.example", "b.example"))
        val candidates = resolver.coverUrls(42, "a.example")
        val attempted = mutableSetOf<String>()
        val visited = mutableListOf<String>()
        repeat(candidates.size) {
            val next = com.par9uet.jm.coil.nextCoverCandidateUrl(candidates, attempted) ?: return@repeat
            visited += next
            attempted += next
        }

        assertEquals(candidates, visited)
        assertEquals("jm-cover-42", jmCoverCacheKey(42))
    }

    @Test
    fun `multiple chapters use one logical group progress`() {
        val group = (1..4).map { id ->
            task(id, if (id == 1) DownloadStatus.COMPLETE else DownloadStatus.PENDING, if (id == 1) 1f else 0f)
        }

        assertEquals(0.375f, groupDownloadProgress(group, 2, 0.5f), 0.0001f)
    }

    private fun task(id: Int, status: DownloadStatus, progress: Float) = DownloadComic(
        id = id,
        name = "漫画$id",
        authorList = emptyList(),
        coverPath = "",
        zipPath = "",
        progress = progress,
        status = status,
        createTime = id.toLong(),
    )
}
