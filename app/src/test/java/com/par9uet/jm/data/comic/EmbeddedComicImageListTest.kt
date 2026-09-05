package com.par9uet.jm.data.comic

import io.github.jukomu.jmcomic.api.model.JmImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedComicImageListTest {
    private val photoImage = JmImage(
        "photo-1", "17", "001.jpg", "https://img.example/001.jpg", "?a=1", 0,
    )
    private val readImage = JmImage(
        "photo-1", "23", "001.jpg", "https://img.example/001.jpg", "?a=2", 0,
    )

    @Test
    fun `getPhoto result is used when it has images`() = runTest {
        var readCalls = 0
        val result = loadEmbeddedImagesWithFallback(
            photo = { listOf(photoImage) },
            read = { readCalls++; listOf(readImage) },
        )

        assertEquals(listOf(photoImage), result)
        assertEquals(0, readCalls)
    }

    @Test
    fun `getPhoto failure falls back to getComicRead`() = runTest {
        val result = loadEmbeddedImagesWithFallback(
            photo = { error("photo unavailable") },
            read = { listOf(readImage) },
        )

        assertEquals(listOf(readImage), result)
    }

    @Test
    fun `empty image list is preserved for caller error handling`() = runTest {
        val result = loadEmbeddedImagesWithFallback(
            photo = { emptyList() },
            read = { emptyList() },
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `cancellation from photo is never converted to fallback`() = runTest {
        val cancelled = CancellationException("stop")
        var readCalls = 0

        try {
            loadEmbeddedImagesWithFallback(
                photo = { throw cancelled },
                read = { readCalls++; listOf(readImage) },
            )
            error("expected cancellation")
        } catch (error: CancellationException) {
            assertEquals(cancelled, error)
        }
        assertEquals(0, readCalls)
    }

    @Test
    fun `scramble and album ids prefer photo values and fall back to image values`() {
        val fromPhoto = buildComicPicListResponse(
            id = 99,
            photoAlbumId = "88",
            photoScrambleId = "17",
            images = listOf(photoImage),
        )
        assertEquals(88, fromPhoto.__aId)
        assertEquals(17, fromPhoto.__scrambleId)

        val fromImage = buildComicPicListResponse(
            id = 99,
            photoAlbumId = null,
            photoScrambleId = null,
            images = listOf(readImage),
        )
        assertEquals(99, fromImage.__aId)
        assertEquals(23, fromImage.__scrambleId)
    }

    @Test
    fun `wrapped image urls are normalized without changing ordinary urls`() {
        assertEquals(
            "https://cdn.example/image.jpg",
            fixEmbeddedImageUrl("wrapper https://cdn.example/image.jpg"),
        )
        assertEquals(
            "https://cdn.example/image.jpg",
            fixEmbeddedImageUrl("https://cdn.example/image.jpg"),
        )
    }
}
