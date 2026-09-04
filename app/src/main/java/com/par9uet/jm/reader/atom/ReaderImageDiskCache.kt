package com.par9uet.jm.reader.atom

import android.graphics.Bitmap
import com.par9uet.jm.reader.READER_MAX_SOURCE_BYTES
import com.par9uet.jm.reader.ReaderDecodeProfile
import com.par9uet.jm.reader.ReaderPageKey
import com.par9uet.jm.reader.readerCacheKey
import com.par9uet.jm.utils.compressWebpCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ReaderSourceFile(
    val file: File,
    val generation: Long,
    val persistent: Boolean,
    val persistAfterValidation: Boolean,
    val cacheFile: File,
)

private data class DecodedCacheWrite(
    val file: File,
    val bitmap: Bitmap,
    val quality: Int,
    val generation: Long,
)

/** Owns reader source/decoded files, leases, generations, writes and disk trimming. */
internal class ReaderImageDiskCache(
    directory: File,
    scope: CoroutineScope,
) {
    private val sourceCacheMutex = Mutex()
    private val decodedCacheMutex = Mutex()
    private val cacheGeneration = AtomicLong(0L)
    private val diskCacheDir = directory.apply { mkdirs() }
    private val activeSourceFiles = ConcurrentHashMap<File, AtomicInteger>()
    private val decodedCacheWrites = Channel<DecodedCacheWrite>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_LATEST,
    )
    private val diskTrimRequests = Channel<Unit>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_LATEST,
    )

    init {
        scope.launch {
            for (write in decodedCacheWrites) {
                runCatching { writeDecodedCache(write) }
            }
        }
        scope.launch {
            for (ignored in diskTrimRequests) {
                runCatching { trimDiskCache() }
            }
        }
    }

    fun decodedFile(pageKey: ReaderPageKey, profile: ReaderDecodeProfile): File =
        File(diskCacheDir, "${readerCacheKey(pageKey, "decoded", profile)}.webp")

    fun currentGeneration(): Long = cacheGeneration.get()

    suspend fun createSourceTempFile(): File = sourceCacheMutex.withLock {
        ensureDiskCacheDir()
        File.createTempFile("reader-source-", ".source", diskCacheDir).also {
            activeSourceFiles[it] = AtomicInteger(1)
        }
    }

    fun discardTemporary(file: File) {
        releaseSourceFile(file, cacheGeneration.get(), temporary = true)
    }

    suspend fun acquireCachedSource(pageKey: ReaderPageKey): ReaderSourceFile? =
        sourceCacheMutex.withLock {
            val file = sourceCacheFile(pageKey)
            if (!file.isFile || file.length() !in 1..READER_MAX_SOURCE_BYTES) {
                null
            } else {
                activeSourceFiles.computeIfAbsent(file) { AtomicInteger() }.incrementAndGet()
                ReaderSourceFile(
                    file = file,
                    generation = cacheGeneration.get(),
                    persistent = true,
                    persistAfterValidation = false,
                    cacheFile = file,
                )
            }
        }

    fun transientSource(
        pageKey: ReaderPageKey,
        file: File,
        generation: Long,
        persistAfterValidation: Boolean,
    ): ReaderSourceFile = ReaderSourceFile(
        file = file,
        generation = generation,
        persistent = false,
        persistAfterValidation = persistAfterValidation,
        cacheFile = sourceCacheFile(pageKey),
    )

    fun releaseSource(source: ReaderSourceFile) {
        releaseSourceFile(
            file = source.file,
            generation = source.generation,
            temporary = !source.persistent,
        )
    }

    suspend fun promoteSourceIfNeeded(
        source: ReaderSourceFile,
        forcePersistence: Boolean = false,
    ) {
        if ((!source.persistAfterValidation && !forcePersistence) || source.persistent) return
        try {
            var promoted = false
            sourceCacheMutex.withLock {
                if (source.generation != cacheGeneration.get()) return@withLock
                ensureDiskCacheDir()
                if (
                    !source.cacheFile.isFile ||
                    source.cacheFile.length() !in 1..READER_MAX_SOURCE_BYTES
                ) {
                    source.file.copyTo(source.cacheFile, overwrite = true)
                }
                promoted = source.cacheFile.isFile &&
                    source.cacheFile.length() in 1..READER_MAX_SOURCE_BYTES
            }
            if (promoted) requestDiskTrim()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Promotion is best effort; the validated temporary file still serves this request.
        }
    }

    suspend fun invalidateSource(source: ReaderSourceFile) {
        if (!source.persistent) return
        sourceCacheMutex.withLock {
            source.cacheFile.delete()
        }
    }

    fun scheduleDecodedCacheWrite(file: File, bitmap: Bitmap, quality: Int) {
        if (file.isFile || bitmap.isRecycled) return
        decodedCacheWrites.trySend(
            DecodedCacheWrite(
                file = file,
                bitmap = bitmap,
                quality = quality,
                generation = cacheGeneration.get(),
            )
        )
    }

    suspend fun clear() {
        sourceCacheMutex.withLock {
            decodedCacheMutex.withLock {
                cacheGeneration.incrementAndGet()
                ensureDiskCacheDir()
                diskCacheDir.listFiles().orEmpty()
                    .filterNot { activeSourceFiles.containsKey(it) }
                    .forEach { it.deleteRecursively() }
                ensureDiskCacheDir()
            }
        }
    }

    fun close() {
        decodedCacheWrites.close()
        diskTrimRequests.close()
    }

    private fun sourceCacheFile(pageKey: ReaderPageKey): File =
        File(diskCacheDir, "${readerCacheKey(pageKey, "source")}.source")

    private fun releaseSourceFile(
        file: File,
        generation: Long,
        temporary: Boolean,
    ) {
        activeSourceFiles[file]?.let { references ->
            if (references.decrementAndGet() <= 0) {
                activeSourceFiles.remove(file, references)
            }
        }
        if (temporary || generation != cacheGeneration.get()) file.delete()
        if (!temporary) requestDiskTrim()
    }

    private suspend fun writeDecodedCache(write: DecodedCacheWrite) {
        if (write.file.isFile || write.bitmap.isRecycled) return
        decodedCacheMutex.withLock {
            if (
                write.generation != cacheGeneration.get() ||
                write.file.isFile ||
                write.bitmap.isRecycled
            ) {
                return@withLock
            }
            ensureDiskCacheDir()
            val temporary = File.createTempFile("${write.file.name}-", ".tmp", diskCacheDir)
            try {
                FileOutputStream(temporary).use { output ->
                    if (!write.bitmap.compressWebpCompat(write.quality, output)) return@withLock
                }
                if (!temporary.renameTo(write.file)) {
                    temporary.copyTo(write.file, overwrite = true)
                }
            } finally {
                temporary.delete()
            }
        }
        requestDiskTrim()
    }

    private fun requestDiskTrim() {
        diskTrimRequests.trySend(Unit)
    }

    private suspend fun trimDiskCache() {
        if (!sourceCacheMutex.tryLock()) return
        try {
            if (!decodedCacheMutex.tryLock()) return
            try {
                ensureDiskCacheDir()
                val files = diskCacheDir.listFiles { file ->
                    file.isFile &&
                        !activeSourceFiles.containsKey(file) &&
                        (file.extension == "source" || file.extension == "webp")
                }.orEmpty()
                var total = files.sumOf(File::length)
                if (total <= MAX_DISK_CACHE_BYTES) return
                files.sortedBy(File::lastModified).forEach { file ->
                    if (total <= TARGET_DISK_CACHE_BYTES) return@forEach
                    total -= file.length()
                    file.delete()
                }
            } finally {
                decodedCacheMutex.unlock()
            }
        } finally {
            sourceCacheMutex.unlock()
        }
    }

    private fun ensureDiskCacheDir() {
        if (diskCacheDir.isDirectory) return
        if (diskCacheDir.exists()) diskCacheDir.deleteRecursively()
        diskCacheDir.mkdirs()
    }

    private companion object {
        const val MAX_DISK_CACHE_BYTES = 256L * 1024L * 1024L
        const val TARGET_DISK_CACHE_BYTES = 224L * 1024L * 1024L
    }
}
