package com.par9uet.jm.cache

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.par9uet.jm.database.model.DownloadComic
import java.io.File

fun isDocumentCachePath(path: String): Boolean = path.startsWith("content://")

fun getTreeUriForCachePath(path: String): Uri? = runCatching {
    if (!isDocumentCachePath(path)) return@runCatching null
    val uri = path.toUri()
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
        root.toUri(),
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
    val parent = root.toUri()
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

fun getComicCoverDownloadPath(context: Context, comic: DownloadComic): String =
    getOrCreateCacheFile(context, getComicDownloadRootPath(context, comic), "cover.webp", "image/webp")

fun getCacheParentPath(path: String): String? = runCatching {
    if (!isDocumentCachePath(path)) {
        File(path).parentFile?.absolutePath
    } else {
        val uri = path.toUri()
        val documentId = DocumentsContract.getDocumentId(uri)
        documentId.substringBeforeLast('/', "").takeIf(String::isNotBlank)
            ?.let { DocumentsContract.buildDocumentUriUsingTree(uri, it).toString() }
    }
}.getOrNull()
