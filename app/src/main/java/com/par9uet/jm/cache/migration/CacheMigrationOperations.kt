package com.par9uet.jm.cache.migration

import com.par9uet.jm.database.model.DownloadComic

/** A chapter cache that exists and will be copied, plus where it was found. */
data class SourceChapter(
    val path: String?,
    val legacyZip: Boolean = false,
    val scannedRoot: String? = null,
)

/** Every file a migration has to move, resolved before the destination is touched. */
data class CacheMigrationSources(
    val paths: List<String>,
    val scannedRoots: Set<String>,
    val groupCovers: Map<Int, String?>,
    val chapters: Map<Int, SourceChapter>,
)

/** The rows a migration rewrote, together with the comic roots they now live under. */
data class CacheMigrationCopy(
    val records: List<DownloadComic>,
    val roots: Map<Int, String>,
)

/**
 * L3: combines the cache document atoms with the download rows that point at them. It knows how to
 * read, resolve and move one comic's files; it holds no migration order, reports no progress and
 * decides nothing about *when* a step may run — it performs the step it is asked for, so the index
 * write and the tree switch happen when [CacheMigrationCoordinator] calls for them.
 */
interface CacheMigrationOperations {
    fun sourceTreeUri(): String

    fun treesOverlap(sourceTreeUri: String, targetTreeUri: String): Boolean

    suspend fun records(): List<DownloadComic>

    fun resolveSources(records: List<DownloadComic>): CacheMigrationSources

    fun ensureTargetDoesNotOverlapSources(targetTreeUri: String, sourcePaths: List<String>)

    fun size(path: String): Long

    suspend fun copy(
        sources: CacheMigrationSources,
        records: List<DownloadComic>,
        targetTreeUri: String,
        onBytes: suspend (Long) -> Unit,
    ): CacheMigrationCopy

    fun writeConfigs(copy: CacheMigrationCopy)

    suspend fun commitRecords(records: List<DownloadComic>)

    fun activateTargetTree(targetTreeUri: String)

    fun pruneSources(sourcePaths: List<String>, copy: CacheMigrationCopy)

    fun removeSourceMetadata(records: List<DownloadComic>, scannedRoots: Set<String>)

    fun removeEmptyDefaultCacheDirectories()
}
