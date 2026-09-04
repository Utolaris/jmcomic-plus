package com.par9uet.jm.reader.atom

import com.par9uet.jm.reader.ReaderDecodeProfile
import com.par9uet.jm.reader.ReaderPageKey
import com.par9uet.jm.reader.readerCacheKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderImageDiskCacheTest {
    @Test
    fun `clear defers active source deletion until its lease is released`() = runBlocking {
        withDiskCache { directory, cache ->
            val pageKey = pageKey()
            val sourceFile = File(directory, "${readerCacheKey(pageKey, "source")}.source")
            sourceFile.writeBytes(byteArrayOf(1, 2, 3))

            val source = cache.acquireCachedSource(pageKey)
            assertNotNull(source)
            cache.clear()
            assertTrue(sourceFile.exists())

            cache.releaseSource(requireNotNull(source))
            assertFalse(sourceFile.exists())
        }
    }

    @Test
    fun `clear removes inactive source and decoded files`() = runBlocking {
        withDiskCache { directory, cache ->
            val pageKey = pageKey()
            val sourceFile = File(directory, "${readerCacheKey(pageKey, "source")}.source")
                .apply { writeBytes(byteArrayOf(1)) }
            val decodedFile = cache.decodedFile(pageKey, ReaderDecodeProfile.HIGH)
                .apply { writeBytes(byteArrayOf(2)) }

            cache.clear()

            assertFalse(sourceFile.exists())
            assertFalse(decodedFile.exists())
        }
    }

    @Test
    fun `clear preserves an active temporary source until release`() = runBlocking {
        withDiskCache { _, cache ->
            val generation = cache.currentGeneration()
            val temporary = cache.createSourceTempFile().apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val source = cache.transientSource(
                pageKey = pageKey(),
                file = temporary,
                generation = generation,
                persistAfterValidation = true,
            )

            cache.clear()
            assertTrue(temporary.exists())

            cache.releaseSource(source)
            assertFalse(temporary.exists())
        }
    }

    private suspend fun withDiskCache(
        block: suspend (directory: File, cache: ReaderImageDiskCache) -> Unit,
    ) {
        val directory = Files.createTempDirectory("reader-disk-cache-test").toFile()
        val job = SupervisorJob()
        val cache = ReaderImageDiskCache(directory, CoroutineScope(job + Dispatchers.IO))
        try {
            block(directory, cache)
        } finally {
            cache.close()
            job.cancel()
            directory.deleteRecursively()
        }
    }

    private fun pageKey() = ReaderPageKey(
        comicId = 7,
        pageIndex = 2,
        sourceIdentity = "https://example.test/media/photos/7/00003.webp",
        scrambleId = 0,
        speed = "1",
    )
}
