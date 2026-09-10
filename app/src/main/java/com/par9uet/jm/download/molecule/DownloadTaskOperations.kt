package com.par9uet.jm.download.molecule

import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.database.model.UpdateComicStatus
import com.par9uet.jm.download.atom.DownloadFiles

data class DownloadTaskResult(
    val comicIds: List<Int> = emptyList(),
    val message: String,
)

class DownloadTaskOperations(
    private val downloadComicDao: DownloadComicDao,
    private val files: DownloadFiles,
) {
    suspend fun allTaskIds(): List<Int> = downloadComicDao.getAll().map { it.id }

    suspend fun groupTaskIds(groupId: Int): List<Int> = downloadComicDao.getByGroupId(groupId).map { it.id }

    suspend fun groupIdsForTasks(ids: Collection<Int>): List<Int> = ids.mapNotNull { id ->
        downloadComicDao.getById(id)?.let { it.groupId.takeIf { group -> group != 0 } ?: it.id }
    }.distinct()

    suspend fun pauseDownloads(ids: Collection<Int>) {
        val activeIds = ids.filter { downloadComicDao.getById(it)?.status != DownloadStatus.COMPLETE }
        downloadComicDao.updateStatusByIds(activeIds, DownloadStatus.PAUSED)
    }

    suspend fun deleteDownloads(ids: Collection<Int>) = downloadComicDao.deleteByIds(ids.toList())

    suspend fun invalidateDownloads(ids: Collection<Int>) {
        downloadComicDao.updateStatusByIds(ids.toList(), DownloadStatus.ERROR)
        ids.forEach { downloadComicDao.updateProgress(com.par9uet.jm.database.model.UpdateComicProgress(it, 0f)) }
    }

    suspend fun downloadComic(comic: Comic): DownloadTaskResult? {
        if (downloadComicDao.getExistingIds(listOf(comic.id)).isNotEmpty()) {
            return DownloadTaskResult(message = "该漫画已在缓存列表中")
        }
        insertComicTask(comic)
        return DownloadTaskResult(listOf(comic.id), "创建缓存任务成功")
    }

    suspend fun downloadComics(comics: List<Comic>): DownloadTaskResult? {
        if (comics.isEmpty()) return null
        val existingIds = downloadComicDao.getExistingIds(comics.map { it.id }).toSet()
        val newComics = comics.filterNot { it.id in existingIds }
        if (newComics.isEmpty()) {
            return DownloadTaskResult(message = "所选漫画已在缓存列表中")
        }

        newComics.forEach { insertComicTask(it) }
        val queuedIds = newComics.map { it.id }

        val skippedCount = comics.size - newComics.size
        return DownloadTaskResult(queuedIds,
            if (skippedCount > 0) {
                "已创建 ${newComics.size} 个缓存任务，跳过 $skippedCount 个已存在漫画"
            } else {
                "已创建 ${newComics.size} 个缓存任务"
            }
        )
    }

    suspend fun downloadChapters(parentComic: Comic, chapters: List<ComicChapter>): DownloadTaskResult? {
        if (chapters.isEmpty()) return null
        val existingIds = downloadComicDao.getExistingIds(chapters.map { it.id }).toSet()
        val newChapters = chapters.filterNot { it.id in existingIds }
        if (newChapters.isEmpty()) {
            return DownloadTaskResult(message = "所选章节已在缓存列表中")
        }

        val now = System.currentTimeMillis()
        newChapters.forEachIndexed { index, chapter ->
            downloadComicDao.insert(
                DownloadComic(
                    id = chapter.id,
                    name = "${parentComic.name} ${chapter.name}".trim(),
                    authorList = parentComic.authorList,
                    tagList = parentComic.tagList,
                    coverPath = "",
                    zipPath = "",
                    progress = 0f,
                    status = DownloadStatus.PENDING,
                    createTime = now + index,
                    groupId = parentComic.id,
                    groupName = parentComic.name,
                    chapterName = chapter.name
                )
            )
        }
        val queuedIds = newChapters.map { it.id }

        val skippedCount = chapters.size - newChapters.size
        return DownloadTaskResult(queuedIds,
            if (skippedCount > 0) {
                "已创建 ${newChapters.size} 个缓存任务，跳过 $skippedCount 个已存在章节"
            } else {
                "已创建 ${newChapters.size} 个缓存任务"
            }
        )
    }

    private suspend fun insertComicTask(comic: Comic) {
        downloadComicDao.insert(
            DownloadComic(
                id = comic.id,
                name = comic.name,
                authorList = comic.authorList,
                tagList = comic.tagList,
                coverPath = "",
                zipPath = "",
                progress = 0f,
                status = DownloadStatus.PENDING,
                createTime = System.currentTimeMillis(),
                groupId = comic.id,
                groupName = comic.name
            )
        )
    }

    suspend fun retryDownload(comicId: Int): DownloadTaskResult? {
        downloadComicDao.getById(comicId) ?: return null
        downloadComicDao.updateProgress(
            com.par9uet.jm.database.model.UpdateComicProgress(comicId, 0f)
        )
        downloadComicDao.updateStatus(
            UpdateComicStatus(comicId, DownloadStatus.PENDING)
        )
        val queuedIds = listOf(comicId)
        return DownloadTaskResult(queuedIds, "已重新加入下载队列")
    }

    /**
     * 恢复已暂停的下载任务：更新状态为 pending 并返回待入队 ID。
     * 与 retryDownload 不同，不会重置已下载进度，而是从断点继续。
     */
    suspend fun resumeDownloads(comicIds: List<Int>): DownloadTaskResult? {
        if (comicIds.isEmpty()) return null
        val validIds = comicIds.filter { id ->
            val task = downloadComicDao.getById(id)
            task != null && task.status != DownloadStatus.COMPLETE
        }.distinct()
        if (validIds.isEmpty()) {
            return DownloadTaskResult(message = "没有可恢复的下载任务")
        }
        downloadComicDao.updateStatusByIds(validIds, DownloadStatus.PENDING)
        val queuedIds = validIds
        return DownloadTaskResult(queuedIds, "已恢复 ${validIds.size} 个下载任务")
    }

    suspend fun retryGroup(groupId: Int): DownloadTaskResult? {
        val chapters = downloadComicDao.getByGroupId(groupId)
        val errorIds = chapters.filter { it.status == DownloadStatus.ERROR }.map { it.id }
        if (errorIds.isEmpty()) return null
        downloadComicDao.updateStatusByIds(errorIds, DownloadStatus.PENDING)
        errorIds.forEach { id ->
            downloadComicDao.updateProgress(
                com.par9uet.jm.database.model.UpdateComicProgress(id, 0f)
            )
        }
        val queuedIds = errorIds
        return DownloadTaskResult(queuedIds, "已重新加入 ${errorIds.size} 个下载任务")
    }

    suspend fun redownloadGroup(groupId: Int): DownloadTaskResult? {
        val items = downloadComicDao.getByGroupId(groupId)
        if (items.isEmpty()) return null
        items.forEach { item ->
            files.delete(item.zipPath, item.coverPath)
            downloadComicDao.updateStatus(
                UpdateComicStatus(item.id, DownloadStatus.PENDING)
            )
            downloadComicDao.updateProgress(
                com.par9uet.jm.database.model.UpdateComicProgress(item.id, 0f)
            )
        }
        val queuedIds = items.map { it.id }
        return DownloadTaskResult(queuedIds, "已重新下载 ${items.size} 个任务")
    }
}
