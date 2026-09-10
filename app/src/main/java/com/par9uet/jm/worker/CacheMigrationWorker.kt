package com.par9uet.jm.worker

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.par9uet.jm.MainActivity
import com.par9uet.jm.R
import com.par9uet.jm.cache.cachePathIsDirectory
import com.par9uet.jm.cache.cachePathContentStatus
import com.par9uet.jm.cache.CachePathAccess
import com.par9uet.jm.cache.CachePathContent
import com.par9uet.jm.cache.cachePathSize
import com.par9uet.jm.cache.deleteCachePath
import com.par9uet.jm.cache.findOrCreateCacheDocument
import com.par9uet.jm.cache.findCacheChildPathOrThrow
import com.par9uet.jm.cache.findExistingComicChapterPath
import com.par9uet.jm.cache.getDownloadDir
import com.par9uet.jm.cache.getChapterCacheName
import com.par9uet.jm.cache.isDocumentCachePath
import com.par9uet.jm.cache.openCacheOutputStream
import com.par9uet.jm.cache.setDownloadTreeUri
import com.par9uet.jm.cache.writeDocumentComicCacheConfigAtPath
import com.par9uet.jm.cache.inspectCachePath
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.download.coordinator.DownloadComicCoordinator
import com.par9uet.jm.cache.getDownloadTreeUri
import com.par9uet.jm.cache.getComicCacheRootName
import androidx.room.withTransaction
import com.par9uet.jm.database.AppDatabase
import com.par9uet.jm.cache.cacheDocumentTreesOverlap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.par9uet.jm.utils.DOWNLOAD_NOTIFICATION_CHANNEL_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

const val CACHE_MIGRATION_WORK_NAME = "cache_path_migration"
const val CACHE_MIGRATION_TARGET_URI = "target_tree_uri"
const val CACHE_MIGRATION_PROGRESS = "progress"
const val CACHE_MIGRATION_STAGE = "stage"
const val CACHE_MIGRATION_ERROR = "error"
private const val CACHE_MIGRATION_NOTIFICATION_ID = 19_940

class CacheMigrationWorker(
    private val appContext: Context,
    params: WorkerParameters,
    private val downloadComicDao: DownloadComicDao,
    private val coordinator: DownloadComicCoordinator,
    private val database: AppDatabase,
) : CoroutineWorker(appContext, params) {
    private var lastReportedPercent = -1

    override suspend fun doWork(): Result {
        updateProgress(0, "正在等待当前缓存任务结束")
        return coordinator.withIdleDownloads { migrate() }
    }

    private suspend fun migrate(): Result = withContext(Dispatchers.IO) {
        val targetTreeUri = inputData.getString(CACHE_MIGRATION_TARGET_URI).orEmpty()
        val sourceTreeUri = getDownloadTreeUri(appContext)?.toString().orEmpty()
        if (sourceTreeUri == targetTreeUri) return@withContext Result.success()
        if (sourceTreeUri.isNotBlank() && targetTreeUri.isNotBlank()) {
            val source = Uri.parse(sourceTreeUri)
            val target = Uri.parse(targetTreeUri)
            if (source.authority == target.authority && cacheDocumentTreesOverlap(
                    DocumentsContract.getTreeDocumentId(source), DocumentsContract.getTreeDocumentId(target),
                )) return@withContext Result.failure(workDataOf(CACHE_MIGRATION_ERROR to "请选择与原缓存目录互不包含的目录"))
        }

        try {
            updateProgress(0, "正在等待当前缓存任务结束")

            val records = downloadComicDao.getAll()
            // Resolve every source before touching the destination. A path the provider cannot
            // answer for fails the migration; a path that is simply gone (a re-download already
            // deleted the files, the user cleared the cache) is not data worth blocking on.
            val groupCovers = records.groupBy(::groupIdOf)
                .mapValues { (_, chapters) -> resolveGroupCover(chapters) }
            val resolvedRoots = mutableMapOf<Int, String?>()
            val chapterSources = records.associate { record ->
                record.id to resolveChapterSource(record, resolvedRoots)
            }
            val sourcePaths = (
                groupCovers.values.filterNotNull() + chapterSources.values.mapNotNull { it.path }
                ).distinct()
            val scannedRoots = chapterSources.values.mapNotNull { it.scannedRoot }.toSet()
            ensureTargetDoesNotOverlapSources(targetTreeUri, sourcePaths)
            val totalBytes = sourcePaths.sumOf { cachePathSize(appContext, it) }.coerceAtLeast(1L)
            var copiedBytes = 0L
            val copiedCoverGroups = mutableSetOf<Int>()
            val targetRoots = mutableMapOf<Int, String>()

            fun reportCopied(delta: Long) {
                copiedBytes += delta
                val percent = ((copiedBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
                updateProgressBlocking(percent, "正在迁移缓存文件 · $percent%")
            }

            val migrated = records.map { record ->
                currentCoroutineContext().ensureActive()
                val groupId = groupIdOf(record)
                val rootPath = targetRoots.getOrPut(groupId) { destinationComicRoot(record, targetTreeUri) }
                val coverSource = groupCovers[groupId]
                val coverPath = coverSource?.let {
                    destinationFile(rootPath, "cover.webp", "image/webp")
                }.orEmpty()
                if (coverSource != null && copiedCoverGroups.add(groupId)) {
                    copyFile(coverSource, coverPath, ::reportCopied)
                }
                val sourceChapter = chapterSources.getValue(record.id)
                val chapterPath = sourceChapter.path?.let { source ->
                    val destination = prepareDestinationChapter(record, rootPath, sourceChapter.legacyZip)
                    check(source != destination) { "目标目录与原缓存目录相同" }
                    if (sourceChapter.legacyZip) copyFile(source, destination, ::reportCopied)
                    else copyDirectory(source, destination, ::reportCopied)
                    destination
                }.orEmpty()
                record.copy(coverPath = coverPath, zipPath = chapterPath)
            }

            migrated.groupBy(::groupIdOf).forEach { (groupId, chapters) ->
                val rootPath = targetRoots[groupId] ?: error("未找到目标缓存目录")
                val coverPath = chapters.firstOrNull { it.coverPath.isNotBlank() }?.coverPath.orEmpty()
                writeDocumentComicCacheConfigAtPath(
                    appContext,
                    chapters.first(),
                    chapters,
                    rootPath,
                    coverPath,
                )
            }

            updateProgress(99, "正在更新缓存索引")
            withContext(NonCancellable) {
                database.withTransaction {
                    for (record in migrated) downloadComicDao.update(record)
                }
                setDownloadTreeUri(appContext, targetTreeUri)
                val targetPaths = migrated.flatMap { listOf(it.coverPath, it.zipPath) }.filter(String::isNotBlank).toSet()
                sourcePaths.filterNot(targetPaths::contains).sortedByDescending { it.length }.forEach { path ->
                    runCatching { deleteCachePath(appContext, path) }
                }
                removeSourceMetadata(records, scannedRoots)
                removeEmptyDefaultCacheDirectories()
            }
            updateProgress(100, "迁移完成")
            Result.success(workDataOf(CACHE_MIGRATION_PROGRESS to 100, CACHE_MIGRATION_STAGE to "迁移完成"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            Result.failure(workDataOf(CACHE_MIGRATION_ERROR to (throwable.message ?: "缓存迁移失败，原路径未切换")))
        }
    }

    /** A chapter cache that exists and will be copied, plus where it was found. */
    private data class SourceChapter(
        val path: String?,
        val legacyZip: Boolean = false,
        val scannedRoot: String? = null,
    )

    private fun groupIdOf(record: DownloadComic) = record.groupId.takeIf { it != 0 } ?: record.id

    private fun resolveGroupCover(chapters: List<DownloadComic>): String? =
        chapters.firstNotNullOfOrNull { chapter ->
            val path = chapter.coverPath
            if (path.isBlank()) return@firstNotNullOfOrNull null
            when (inspectCachePath(appContext, path)) {
                // The cover is optional metadata: a saved path that is gone, or an empty file,
                // is not worth failing the migration for.
                CachePathAccess.MISSING -> null
                CachePathAccess.INACCESSIBLE -> error("无法读取缓存封面，请检查目录授权：$path")
                CachePathAccess.READABLE -> when (cachePathContentStatus(appContext, path)) {
                    CachePathContent.HAS_CONTENT -> path
                    CachePathContent.EMPTY -> null
                    CachePathContent.UNREADABLE -> error("无法读取缓存封面，请检查目录授权：$path")
                }
            }
        }

    private fun resolveChapterSource(record: DownloadComic, resolvedRoots: MutableMap<Int, String?>): SourceChapter {
        val savedPath = record.zipPath
        if (savedPath.isNotBlank()) {
            when (inspectCachePath(appContext, savedPath)) {
                CachePathAccess.READABLE -> return SourceChapter(
                    path = savedPath,
                    legacyZip = !cachePathIsDirectory(appContext, savedPath),
                )
                // A re-download deletes the files first and only then resets the row, so a saved
                // path that no longer exists means there is nothing to copy rather than a failure.
                CachePathAccess.MISSING -> Unit
                CachePathAccess.INACCESSIBLE -> error("无法读取缓存路径，请检查目录授权：$savedPath")
            }
        }
        // A running or paused download writes pages into the chapter directory before the row
        // gets a saved zipPath, so the directory has to be found by name.
        val sourceRoot = sourceRoot(record, resolvedRoots)
        val partialPath = sourceRoot?.let { findExistingComicChapterPath(appContext, it, record) }
        return SourceChapter(path = partialPath, scannedRoot = partialPath?.let { sourceRoot })
    }

    private fun sourceRoot(record: DownloadComic, cache: MutableMap<Int, String?>): String? {
        val groupId = groupIdOf(record)
        if (cache.containsKey(groupId)) return cache[groupId]
        val root = resolveSourceRoot(record)
        cache[groupId] = root
        return root
    }

    private fun resolveSourceRoot(record: DownloadComic): String? {
        val name = getComicCacheRootName(record)
        val treeUri = getDownloadTreeUri(appContext) ?: return File(getDownloadDir(appContext), name).absolutePath
        val root = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        ).toString()
        return findCacheChildPathOrThrow(appContext, root, name)
    }

    private fun ensureTargetDoesNotOverlapSources(targetTreeUri: String, sourcePaths: List<String>) {
        if (targetTreeUri.isBlank()) {
            val targetRoot = getDownloadDir(appContext).canonicalFile.toPath()
            sourcePaths.filterNot(::isDocumentCachePath).forEach { sourcePath ->
                val source = File(sourcePath).canonicalFile.toPath()
                check(!targetRoot.startsWith(source) && !source.startsWith(targetRoot)) {
                    "目标缓存目录与原缓存重叠，无法安全迁移"
                }
            }
            return
        }
        val target = Uri.parse(targetTreeUri)
        val targetId = DocumentsContract.getTreeDocumentId(target)
        sourcePaths.filter(::isDocumentCachePath).forEach { sourcePath ->
            val source = Uri.parse(sourcePath)
            if (source.authority == target.authority) {
                val sourceId = DocumentsContract.getDocumentId(source)
                check(!cacheDocumentTreesOverlap(sourceId, targetId)) {
                    "目标缓存目录与原缓存重叠，无法安全迁移"
                }
            }
        }
    }


    private suspend fun updateProgress(percent: Int, stage: String) {
        setProgress(workDataOf(CACHE_MIGRATION_PROGRESS to percent, CACHE_MIGRATION_STAGE to stage))
        setForeground(createForegroundInfo(percent, stage))
    }

    private fun updateProgressBlocking(percent: Int, stage: String) {
        if (percent == lastReportedPercent) return
        lastReportedPercent = percent
        runCatching {
            setProgressAsync(workDataOf(CACHE_MIGRATION_PROGRESS to percent, CACHE_MIGRATION_STAGE to stage)).get()
            setForegroundAsync(createForegroundInfo(percent, stage)).get()
        }
    }

    private fun createForegroundInfo(percent: Int, stage: String): ForegroundInfo {
        val openApp = PendingIntent.getActivity(
            appContext,
            CACHE_MIGRATION_NOTIFICATION_ID,
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle("正在迁移漫画缓存")
            .setContentText(stage)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .build()
        return ForegroundInfo(CACHE_MIGRATION_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun destinationComicRoot(record: DownloadComic, treeUri: String): String {
        val comicName = getComicCacheRootName(record)
        if (treeUri.isBlank()) return File(getDownloadDir(appContext), comicName).also(File::mkdirs).absolutePath
        val tree = Uri.parse(treeUri)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return requireNotNull(findOrCreateCacheDocument(appContext, root, comicName, DocumentsContract.Document.MIME_TYPE_DIR)).toString()
    }

    /** Recreate only the chapter that is about to be copied, so unrelated target content survives. */
    private fun prepareDestinationChapter(
        record: DownloadComic,
        rootPath: String,
        legacyZip: Boolean,
    ): String {
        val name = if (legacyZip) "${record.id}.zip" else getChapterCacheName(record)
        val existing = findCacheChildPathOrThrow(appContext, rootPath, name)
        if (existing != null) check(deleteCachePath(appContext, existing)) { "无法清理目标缓存目录" }
        return if (legacyZip) destinationFile(rootPath, name, "application/zip")
        else destinationDirectory(rootPath, name)
    }

    private fun destinationDirectory(parentPath: String, name: String): String {
        if (!isDocumentCachePath(parentPath)) return File(parentPath, name).also(File::mkdirs).absolutePath
        return requireNotNull(findOrCreateCacheDocument(appContext, Uri.parse(parentPath), name, DocumentsContract.Document.MIME_TYPE_DIR)).toString()
    }

    private fun destinationFile(parentPath: String, name: String, mimeType: String): String {
        if (!isDocumentCachePath(parentPath)) return File(parentPath, name).absolutePath
        return requireNotNull(findOrCreateCacheDocument(appContext, Uri.parse(parentPath), name, mimeType)).toString()
    }

    private fun copyDirectory(sourcePath: String, destinationPath: String, onBytes: (Long) -> Unit) {
        if (!isDocumentCachePath(sourcePath)) {
            val source = File(sourcePath)
            check(source.isDirectory) { "无法读取原缓存目录" }
            val children = source.listFiles() ?: error("无法读取原缓存目录")
            children.forEach { child ->
                if (child.isDirectory) copyDirectory(child.absolutePath, destinationDirectory(destinationPath, child.name), onBytes)
                else copyFile(child.absolutePath, destinationFile(destinationPath, child.name, mimeType(child.name)), onBytes)
            }
            return
        }
        val source = Uri.parse(sourcePath)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(source, DocumentsContract.getDocumentId(source))
        appContext.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val child = DocumentsContract.buildDocumentUriUsingTree(source, cursor.getString(0)).toString()
                val name = cursor.getString(1)
                val type = cursor.getString(2)
                if (type == DocumentsContract.Document.MIME_TYPE_DIR) copyDirectory(child, destinationDirectory(destinationPath, name), onBytes)
                else copyFile(child, destinationFile(destinationPath, name, type ?: mimeType(name)), onBytes)
            }
        } ?: error("无法读取原缓存目录")
    }

    private fun copyFile(sourcePath: String, destinationPath: String, onBytes: (Long) -> Unit) {
        check(sourcePath != destinationPath) { "目标文件与原文件相同" }
        val input = if (isDocumentCachePath(sourcePath)) requireNotNull(appContext.contentResolver.openInputStream(Uri.parse(sourcePath)))
        else File(sourcePath).inputStream()
        input.use { source ->
            openCacheOutputStream(appContext, destinationPath).use { destination -> copyWithProgress(source, destination, onBytes) }
        }
    }

    private fun copyWithProgress(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            if (isStopped) throw CancellationException("缓存迁移已取消")
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            onBytes(read.toLong())
        }
    }

    private fun removeEmptyDefaultCacheDirectories() {
        val root = getDownloadDir(appContext)
        root.walkBottomUp().forEach { file ->
            if (file != root && file.isDirectory && file.listFiles().isNullOrEmpty()) file.delete()
        }
    }

    private fun removeSourceMetadata(records: List<DownloadComic>, extraRoots: Collection<String>) {
        val roots = (records.flatMap { listOf(it.coverPath, it.zipPath) }
            .filter(String::isNotBlank)
            .mapNotNull(::sourceComicRoot) + extraRoots).distinct()
        roots.forEach(::removeMetadataFromRoot)
    }

    private fun sourceComicRoot(path: String): String? = runCatching {
        if (!isDocumentCachePath(path)) {
            val file = File(path)
            (if (file.isDirectory) file.parentFile else file.parentFile)?.absolutePath
        } else {
            val uri = Uri.parse(path)
            val documentId = DocumentsContract.getDocumentId(uri)
            val parentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
            parentId.takeIf(String::isNotBlank)
                ?.let { DocumentsContract.buildDocumentUriUsingTree(uri, it).toString() }
        }
    }.getOrNull()

    private fun removeMetadataFromRoot(rootPath: String) {
        if (!isDocumentCachePath(rootPath)) {
            val root = File(rootPath)
            File(root, "config.json").delete()
            File(root, "cover.webp").takeIf { it.length() == 0L }?.delete()
            if (root.isDirectory && root.listFiles().isNullOrEmpty()) root.delete()
            return
        }
        val root = Uri.parse(rootPath)
        runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getDocumentId(root))
            appContext.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    val child = DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(0))
                    val isEmptyCover = name == "cover.webp" &&
                        cachePathContentStatus(appContext, child.toString()) == CachePathContent.EMPTY
                    if (name == "config.json" || isEmptyCover) {
                        DocumentsContract.deleteDocument(appContext.contentResolver, child)
                    }
                }
            }
            val remainingChildren = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getDocumentId(root))
            val isEmpty = appContext.contentResolver.query(
                remainingChildren,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null, null, null,
            )?.use { cursor -> !cursor.moveToFirst() } ?: false
            if (isEmpty) DocumentsContract.deleteDocument(appContext.contentResolver, root)
        }
    }

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "webp" -> "image/webp"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }
}
