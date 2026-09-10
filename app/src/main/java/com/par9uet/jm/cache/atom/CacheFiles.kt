package com.par9uet.jm.cache.atom

import com.par9uet.jm.cache.CacheArea
import com.par9uet.jm.cache.CacheSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface CacheFiles {
    suspend fun scan(): List<CacheSize>
    /** Reader files are exclusively removed through the reader's cache generation protocol. */
    suspend fun remove(areas: Set<CacheArea>)
}

class DeviceCacheFiles(private val cacheDirectory: File) : CacheFiles {
    override suspend fun scan(): List<CacheSize> = withContext(Dispatchers.IO) {
        CacheArea.entries.map { CacheSize(it, size(directory(it))) }
    }

    override suspend fun remove(areas: Set<CacheArea>) = withContext(Dispatchers.IO) {
        val targets = if (CacheArea.ALL in areas) {
            cacheDirectory.listFiles().orEmpty().filterNot { it.name == "reader_pages" }
        } else areas.filterNot { it == CacheArea.READER }.map(::directory)
        targets.forEach { file ->
            check(!file.exists() || file.deleteRecursively()) { "部分缓存文件清理失败，请重试" }
        }
    }

    private fun directory(area: CacheArea): File = when (area) {
        CacheArea.ALL -> cacheDirectory
        CacheArea.PDF -> File(cacheDirectory, "pdf_export")
        else -> File(cacheDirectory, area.id)
    }

    private fun size(directory: File): Long = if (directory.exists()) {
        directory.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    } else 0L
}
