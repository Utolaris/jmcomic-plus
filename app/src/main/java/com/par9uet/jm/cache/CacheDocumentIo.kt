package com.par9uet.jm.cache

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.google.gson.Gson
import com.par9uet.jm.database.model.DownloadComic
import java.io.File
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption

fun openCacheOutputStream(context: Context, path: String): OutputStream =
    if (isDocumentCachePath(path)) {
        requireNotNull(context.contentResolver.openOutputStream(path.toUri(), "wt"))
    } else {
        File(path).also { it.parentFile?.mkdirs() }.outputStream()
    }

fun cachePathExists(context: Context, path: String): Boolean = if (isDocumentCachePath(path)) {
    runCatching {
        context.contentResolver.query(
            path.toUri(),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null, null, null,
        )?.use { it.moveToFirst() } == true
    }.getOrDefault(false)
} else File(path).exists()

enum class CachePathAccess {
    MISSING,
    READABLE,
    INACCESSIBLE,
}

/**
 * Cheap probe that keeps "nothing is saved here" apart from "the provider cannot answer now".
 * Copying re-reads every file, so this must not walk the tree or open file contents.
 */
fun inspectCachePath(context: Context, path: String): CachePathAccess {
    if (path.isBlank()) return CachePathAccess.MISSING
    if (!isDocumentCachePath(path)) {
        val file = File(path)
        if (!file.exists()) return CachePathAccess.MISSING
        return if (file.isDirectory && file.listFiles() == null) {
            CachePathAccess.INACCESSIBLE
        } else {
            CachePathAccess.READABLE
        }
    }
    return try {
        context.contentResolver.query(
            path.toUri(),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) CachePathAccess.READABLE else CachePathAccess.MISSING }
            ?: CachePathAccess.INACCESSIBLE
    } catch (_: Exception) {
        CachePathAccess.INACCESSIBLE
    }
}

fun cachePathIsDirectory(context: Context, path: String): Boolean {
    if (!isDocumentCachePath(path)) return File(path).isDirectory
    return context.contentResolver.query(
        path.toUri(), arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null,
    )?.use {
        it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
    } ?: error("无法读取缓存文件类型")
}

fun cachePathLength(context: Context, path: String): Long = if (isDocumentCachePath(path)) {
    runCatching {
        context.contentResolver.query(
            path.toUri(),
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null, null, null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)
} else File(path).length()

/** Some SAF providers do not expose COLUMN_SIZE even for non-empty files. */
fun cachePathHasContent(context: Context, path: String): Boolean {
    return cachePathContentStatus(context, path) == CachePathContent.HAS_CONTENT
}

enum class CachePathContent {
    EMPTY,
    HAS_CONTENT,
    UNREADABLE,
}

fun cachePathContentStatus(context: Context, path: String): CachePathContent {
    if (path.isBlank()) return CachePathContent.UNREADABLE
    if (!isDocumentCachePath(path)) {
        val file = File(path)
        if (!file.isFile) return CachePathContent.UNREADABLE
        return when {
            file.length() > 0L -> CachePathContent.HAS_CONTENT
            else -> CachePathContent.EMPTY
        }
    }
    return try {
        val result = context.contentResolver.openInputStream(path.toUri())
            ?: return CachePathContent.UNREADABLE
        result.use { if (it.read() >= 0) CachePathContent.HAS_CONTENT else CachePathContent.EMPTY }
    } catch (_: Exception) {
        CachePathContent.UNREADABLE
    }
}

fun cachePathSize(context: Context, path: String): Long {
    if (!isDocumentCachePath(path)) {
        val file = File(path)
        return if (file.isDirectory) {
            file.walkTopDown().filter(File::isFile).sumOf(File::length)
        } else {
            file.length()
        }
    }
    return runCatching { documentPathSize(context, path.toUri()) }.getOrDefault(0L)
}

data class CacheImageEntry(
    val name: String,
    val path: String,
)

fun listComicImagePaths(context: Context, directoryPath: String): List<String> =
    listComicImageEntries(context, directoryPath).map(CacheImageEntry::path)

fun listComicImageEntries(context: Context, directoryPath: String): List<CacheImageEntry> {
    return runCatching { listComicImageEntriesOrThrow(context, directoryPath) }.getOrDefault(emptyList())
}

/**
 * Lists the images in a cache directory, failing instead of returning an empty list when the
 * directory cannot be read. Callers that read "no images" as "nothing saved here" need this,
 * because a swallowed provider failure would otherwise look like an empty chapter.
 */
fun listComicImageEntriesOrThrow(context: Context, directoryPath: String): List<CacheImageEntry> {
    if (!isDocumentCachePath(directoryPath)) {
        val directory = File(directoryPath)
        if (!directory.isDirectory) return emptyList()
        val files = directory.listFiles() ?: error("无法读取缓存目录")
        return files
            .filter { it.isFile && it.extension.lowercase() in setOf("webp", "jpg", "jpeg", "png") }
            .sortedWith(compareBy<File> { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.name })
            .map { CacheImageEntry(it.name, it.absolutePath) }
    }
    val parent = directoryPath.toUri()
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
    val cursor = context.contentResolver.query(
        children,
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null, null, null,
    ) ?: error("无法读取缓存目录")
    val result = mutableListOf<CacheImageEntry>()
    cursor.use {
        while (it.moveToNext()) {
            val name = it.getString(1)
            if (name.substringAfterLast('.', "").lowercase() in setOf("webp", "jpg", "jpeg", "png")) {
                val uri = DocumentsContract.buildDocumentUriUsingTree(parent, it.getString(0)).toString()
                result += CacheImageEntry(name, uri)
            }
        }
    }
    return result.sortedWith(
        compareBy<CacheImageEntry> { it.name.substringBeforeLast('.').toIntOrNull() ?: Int.MAX_VALUE }
            .thenBy { it.name }
    )
}

fun findCacheChildPath(context: Context, parentPath: String, name: String): String? {
    return runCatching { findCacheChildPathOrThrow(context, parentPath, name) }.getOrNull()
}

/** Like [findCacheChildPath], but preserves provider/query failures for transactional callers. */
fun findCacheChildPathOrThrow(context: Context, parentPath: String, name: String): String? {
    if (!isDocumentCachePath(parentPath)) {
        return File(parentPath, name).takeIf(File::exists)?.absolutePath
    }
    val parent = parentPath.toUri()
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
    val cursor = context.contentResolver.query(
        children,
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null, null, null,
    ) ?: error("无法读取缓存目录")
    return cursor.use {
        while (it.moveToNext()) {
            if (it.getString(1) == name) {
                return@use DocumentsContract.buildDocumentUriUsingTree(parent, it.getString(0)).toString()
            }
        }
        null
    }
}

fun openCacheInputStream(context: Context, path: String) =
    if (isDocumentCachePath(path)) context.contentResolver.openInputStream(path.toUri()) else File(path).inputStream()

fun writeDocumentComicCacheConfig(
    context: Context,
    comic: DownloadComic,
    chapters: List<DownloadComic>,
    gson: Gson = Gson(),
) {
    val rootPath = getComicDownloadRootPath(context, comic)
    val coverPath = getComicCoverDownloadPath(context, comic)
    writeDocumentComicCacheConfigAtPath(context, comic, chapters, rootPath, coverPath, gson)
}

fun writeDocumentComicCacheConfigAtPath(
    context: Context,
    comic: DownloadComic,
    chapters: List<DownloadComic>,
    rootPath: String,
    coverPath: String,
    gson: Gson = Gson(),
) {
    val config = buildComicCacheConfig(comic, chapters, rootPath, coverPath) { path ->
        listComicImageEntries(context, path).map(CacheImageEntry::name)
    }
    val configPath = getOrCreateCacheFile(context, rootPath, "config.json", "application/json")
    writeCacheConfigText(context, configPath, gson.toJson(config))
}

/**
 * Publishes config text so an interrupted write never leaves a truncated config.json as the
 * readable index. Regular files replace via a sibling temp + ATOMIC_MOVE. SAF paths stage a
 * full sibling document and prefer renameDocument; when the provider refuses rename the
 * staged bytes overwrite the target, and a failed overwrite deletes the target so the
 * DB (still not COMPLETE for the failing chapter) can drive a retry instead of keeping a
 * half index. The staging document is always removed.
 */
fun writeCacheConfigText(context: Context, configPath: String, json: String) {
    if (!isDocumentCachePath(configPath)) {
        writeTextAtomically(File(configPath), json)
        return
    }
    val parentPath = requireNotNull(getCacheParentPath(configPath)) { "缓存配置缺少父目录" }
    val stagingPath = getOrCreateCacheFile(context, parentPath, "config.json.tmp", "application/json")
    val buffer = File.createTempFile("cache-config-", ".json", context.cacheDir)
    try {
        buffer.writeText(json, Charsets.UTF_8)
        openCacheOutputStream(context, stagingPath).use { output ->
            buffer.inputStream().use { it.copyTo(output) }
        }
        val renamed = runCatching {
            DocumentsContract.renameDocument(
                context.contentResolver,
                stagingPath.toUri(),
                "config.json",
            ) != null
        }.getOrDefault(false)
        if (renamed) return
        try {
            openCacheOutputStream(context, configPath).use { output ->
                buffer.inputStream().use { it.copyTo(output) }
            }
        } catch (error: Throwable) {
            deleteCachePath(context, configPath)
            throw error
        }
    } finally {
        runCatching { deleteCachePath(context, stagingPath) }
        buffer.delete()
    }
}

/**
 * Replaces [file] with [text] via a sibling temporary file and ATOMIC_MOVE so a crash or
 * concurrent reader never observes a truncated index.
 */
fun writeTextAtomically(file: File, text: String, charset: Charset = Charsets.UTF_8) {
    val parent = file.parentFile
    parent?.mkdirs()
    val temporary = File.createTempFile(".${file.name}-", ".tmp", parent)
    try {
        temporary.writeText(text, charset)
        Files.move(
            temporary.toPath(),
            file.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } finally {
        temporary.delete()
    }
}

fun deleteCachePath(context: Context, path: String): Boolean = if (isDocumentCachePath(path)) {
    runCatching { DocumentsContract.deleteDocument(context.contentResolver, path.toUri()) }.getOrDefault(false)
} else {
    File(path).let { if (it.isDirectory) it.deleteRecursively() else it.delete() }
}

fun findOrCreateCacheDocument(context: Context, parent: Uri, name: String, mimeType: String): Uri? {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
    context.contentResolver.query(
        children,
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null, null, null,
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            if (cursor.getString(1) == name) {
                return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
            }
        }
    }
    return DocumentsContract.createDocument(context.contentResolver, parent, mimeType, name)
}

fun getOrCreateCacheFile(
    context: Context,
    directoryPath: String,
    name: String,
    mimeType: String,
): String {
    if (!isDocumentCachePath(directoryPath)) return File(directoryPath, name).absolutePath
    return requireNotNull(findOrCreateCacheDocument(context, directoryPath.toUri(), name, mimeType)).toString()
}

private fun documentPathSize(context: Context, uri: Uri): Long {
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )
    val row = context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) null else Triple(
            cursor.getString(0),
            cursor.getString(1),
            if (cursor.isNull(2)) 0L else cursor.getLong(2),
        )
    } ?: return 0L
    if (row.second != DocumentsContract.Document.MIME_TYPE_DIR) return row.third

    val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, row.first)
    return context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
        var total = 0L
        while (cursor.moveToNext()) {
            val childId = cursor.getString(0)
            val mimeType = cursor.getString(1)
            total += if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                documentPathSize(
                    context,
                    DocumentsContract.buildDocumentUriUsingTree(uri, childId),
                )
            } else if (cursor.isNull(2)) {
                0L
            } else {
                cursor.getLong(2)
            }
        }
        total
    } ?: 0L
}
