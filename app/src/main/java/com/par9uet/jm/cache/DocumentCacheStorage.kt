package com.par9uet.jm.cache

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.google.gson.Gson
import com.par9uet.jm.database.model.DownloadComic
import java.io.File
import java.io.OutputStream

fun isDocumentCachePath(path: String): Boolean = path.startsWith("content://")

fun getTreeUriForCachePath(path: String): Uri? = runCatching {
    if (!isDocumentCachePath(path)) return@runCatching null
    val uri = Uri.parse(path)
    DocumentsContract.buildTreeDocumentUri(
        uri.authority ?: return@runCatching null,
        DocumentsContract.getTreeDocumentId(uri),
    )
}.getOrNull()

fun getComicDownloadRootPath(context: Context, comic: DownloadComic): String {
    val treeUri = getDownloadTreeUri(context)
    if (treeUri == null) return getComicDownloadRootDir(context, comic).absolutePath
    val root = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    return requireNotNull(findOrCreateCacheDocument(
        context,
        root,
        getComicCacheRootName(comic),
        DocumentsContract.Document.MIME_TYPE_DIR,
    )).toString()
}

fun getComicChapterDownloadPath(context: Context, comic: DownloadComic): String {
    val root = getComicDownloadRootPath(context, comic)
    if (!isDocumentCachePath(root)) return File(root, getChapterCacheName(comic)).also { it.mkdirs() }.absolutePath
    return requireNotNull(findOrCreateCacheDocument(
        context,
        Uri.parse(root),
        getChapterCacheName(comic),
        DocumentsContract.Document.MIME_TYPE_DIR,
    )).toString()
}

/**
 * Find an already migrated chapter directory without falling back to the
 * default cache. This also understands the directory names used before the
 * chapter-id suffix was introduced.
 */
fun findExistingComicChapterDownloadPath(context: Context, comic: DownloadComic): String? {
    return try {
        findExistingComicChapterPath(context, getComicDownloadRootPath(context, comic), comic)
    } catch (_: Exception) {
        null
    }
}

/**
 * Locate a chapter directory that already holds images under [root] without creating anything.
 * Returns null when nothing was written yet; provider failures propagate to the caller.
 */
fun findExistingComicChapterPath(context: Context, root: String, comic: DownloadComic): String? {
    val names = buildList {
        add(getChapterCacheName(comic))
        if (comic.chapterName.isNotBlank()) add(safeCacheFileName(comic.chapterName))
        // The old single-chapter layout used the comic name or "单篇". Do not
        // use these shared names for multi-chapter rows, otherwise every row
        // could resolve to the same legacy directory.
        if (comic.groupId == 0 || comic.groupId == comic.id) {
            add(safeCacheFileName(comic.name))
            add("单篇")
        }
    }.filter { it.isNotBlank() }.distinct()
    if (!isDocumentCachePath(root)) {
        val rootDir = File(root)
        if (!rootDir.isDirectory) return null
        return names.asSequence()
            .map { File(rootDir, it) }
            .firstOrNull { it.isDirectory && listComicImageFiles(it).isNotEmpty() }
            ?.absolutePath
    }
    val parent = Uri.parse(root)
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
    val candidates = context.contentResolver.query(
        children,
        arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
        null, null, null,
    )?.use { cursor ->
        buildList<String> {
            while (cursor.moveToNext()) {
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR && cursor.getString(1) in names) {
                    add(DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0)).toString())
                }
            }
        }
    } ?: error("无法读取缓存目录")
    // Strict listing: a provider that cannot answer must fail the caller, not look like a
    // chapter directory without images.
    return candidates.firstOrNull { listComicImageEntriesOrThrow(context, it).isNotEmpty() }
}

fun getOrCreateCacheFile(
    context: Context,
    directoryPath: String,
    name: String,
    mimeType: String,
): String {
    if (!isDocumentCachePath(directoryPath)) return File(directoryPath, name).absolutePath
    return requireNotNull(findOrCreateCacheDocument(context, Uri.parse(directoryPath), name, mimeType)).toString()
}

fun getComicCoverDownloadPath(context: Context, comic: DownloadComic): String =
    getOrCreateCacheFile(context, getComicDownloadRootPath(context, comic), "cover.webp", "image/webp")

fun openCacheOutputStream(context: Context, path: String): OutputStream =
    if (isDocumentCachePath(path)) {
        requireNotNull(context.contentResolver.openOutputStream(Uri.parse(path), "wt"))
    } else {
        File(path).also { it.parentFile?.mkdirs() }.outputStream()
    }

fun cachePathExists(context: Context, path: String): Boolean = if (isDocumentCachePath(path)) {
    runCatching {
        context.contentResolver.query(
            Uri.parse(path),
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
            Uri.parse(path),
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
        Uri.parse(path), arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null,
    )?.use {
        it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
    } ?: error("无法读取缓存文件类型")
}

fun cachePathLength(context: Context, path: String): Long = if (isDocumentCachePath(path)) {
    runCatching {
        context.contentResolver.query(
            Uri.parse(path),
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
        val result = context.contentResolver.openInputStream(Uri.parse(path))
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
    return runCatching { documentPathSize(context, Uri.parse(path)) }.getOrDefault(0L)
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
    val parent = Uri.parse(directoryPath)
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

fun getCacheParentPath(path: String): String? = runCatching {
    if (!isDocumentCachePath(path)) {
        File(path).parentFile?.absolutePath
    } else {
        val uri = Uri.parse(path)
        val documentId = DocumentsContract.getDocumentId(uri)
        documentId.substringBeforeLast('/', "").takeIf(String::isNotBlank)
            ?.let { DocumentsContract.buildDocumentUriUsingTree(uri, it).toString() }
    }
}.getOrNull()

fun findCacheChildPath(context: Context, parentPath: String, name: String): String? {
    return runCatching { findCacheChildPathOrThrow(context, parentPath, name) }.getOrNull()
}

/** Like [findCacheChildPath], but preserves provider/query failures for transactional callers. */
fun findCacheChildPathOrThrow(context: Context, parentPath: String, name: String): String? {
    if (!isDocumentCachePath(parentPath)) {
        return File(parentPath, name).takeIf(File::exists)?.absolutePath
    }
    val parent = Uri.parse(parentPath)
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
    if (isDocumentCachePath(path)) context.contentResolver.openInputStream(Uri.parse(path)) else File(path).inputStream()

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
    openCacheOutputStream(context, configPath).bufferedWriter(Charsets.UTF_8).use {
        it.write(gson.toJson(config))
    }
}

fun deleteCachePath(context: Context, path: String): Boolean = if (isDocumentCachePath(path)) {
    runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(path)) }.getOrDefault(false)
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
