package com.par9uet.jm.store

import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.database.model.UpdateComicStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal interface BackupTaskScheduler {
    fun downloadComic(comic: Comic)
    fun downloadChapters(parentComic: Comic, chapters: List<ComicChapter>)
}

class DownloadManager(
    private val downloadComicDao: DownloadComicDao,
    private val scope: CoroutineScope,
    private val toastManager: ToastManager,
    private val downloadWorkScheduler: DownloadWorkScheduler,
) : BackupTaskScheduler {
    override fun downloadComic(comic: Comic) {
        scope.launch(Dispatchers.IO) {
            if (downloadComicDao.getExistingIds(listOf(comic.id)).isNotEmpty()) {
                toastManager.showAsync("该漫画已在缓存列表中")
                return@launch
            }
            insertComicTask(comic)
            toastManager.showAsync("创建缓存任务成功")
            downloadWorkScheduler.enqueue(listOf(comic.id))
        }
    }

    fun downloadComics(comics: List<Comic>) {
        if (comics.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val existingIds = downloadComicDao.getExistingIds(comics.map { it.id }).toSet()
            val newComics = comics.filterNot { it.id in existingIds }
            if (newComics.isEmpty()) {
                toastManager.showAsync("所选漫画已在缓存列表中")
                return@launch
            }

            newComics.forEach { insertComicTask(it) }
            downloadWorkScheduler.enqueue(newComics.map { it.id })

            val skippedCount = comics.size - newComics.size
            toastManager.showAsync(
                if (skippedCount > 0) {
                    "已创建 ${newComics.size} 个缓存任务，跳过 $skippedCount 个已存在漫画"
                } else {
                    "已创建 ${newComics.size} 个缓存任务"
                }
            )
        }
    }

    override fun downloadChapters(parentComic: Comic, chapters: List<ComicChapter>) {
        if (chapters.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val existingIds = downloadComicDao.getExistingIds(chapters.map { it.id }).toSet()
            val newChapters = chapters.filterNot { it.id in existingIds }
            if (newChapters.isEmpty()) {
                toastManager.showAsync("所选章节已在缓存列表中")
                return@launch
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
            downloadWorkScheduler.enqueue(newChapters.map { it.id })

            val skippedCount = chapters.size - newChapters.size
            toastManager.showAsync(
                if (skippedCount > 0) {
                    "已创建 ${newChapters.size} 个缓存任务，跳过 $skippedCount 个已存在章节"
                } else {
                    "已创建 ${newChapters.size} 个缓存任务"
                }
            )
        }
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

    fun retryDownload(comicId: Int) {
        scope.launch(Dispatchers.IO) {
            val task = downloadComicDao.getById(comicId) ?: return@launch
            downloadComicDao.updateProgress(
                com.par9uet.jm.database.model.UpdateComicProgress(comicId, 0f)
            )
            downloadComicDao.updateStatus(
                UpdateComicStatus(comicId, DownloadStatus.PENDING)
            )
            downloadWorkScheduler.enqueue(listOf(comicId))
            toastManager.showAsync("已重新加入下载队列")
        }
    }

    /**
     * 恢复已暂停的下载任务：更新状态为 pending 并重新入队 WorkManager。
     * 与 retryDownload 不同，不会重置已下载进度，而是从断点继续。
     */
    fun resumeDownloads(comicIds: List<Int>) {
        if (comicIds.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val validIds = comicIds.filter { id ->
                val task = downloadComicDao.getById(id)
                task != null && task.status != DownloadStatus.COMPLETE
            }.distinct()
            if (validIds.isEmpty()) {
                toastManager.showAsync("没有可恢复的下载任务")
                return@launch
            }
            downloadComicDao.updateStatusByIds(validIds, DownloadStatus.PENDING)
            downloadWorkScheduler.enqueue(validIds)
            toastManager.showAsync("已恢复 ${validIds.size} 个下载任务")
        }
    }

    fun retryGroup(groupId: Int) {
        scope.launch(Dispatchers.IO) {
            val chapters = downloadComicDao.getByGroupId(groupId)
            val errorIds = chapters.filter { it.status == DownloadStatus.ERROR }.map { it.id }
            if (errorIds.isEmpty()) return@launch
            downloadComicDao.updateStatusByIds(errorIds, DownloadStatus.PENDING)
            errorIds.forEach { id ->
                downloadComicDao.updateProgress(
                    com.par9uet.jm.database.model.UpdateComicProgress(id, 0f)
                )
            }
            downloadWorkScheduler.enqueue(errorIds)
            toastManager.showAsync("已重新加入 ${errorIds.size} 个下载任务")
        }
    }

    fun redownloadGroup(groupId: Int) {
        scope.launch(Dispatchers.IO) {
            val items = downloadComicDao.getByGroupId(groupId)
            if (items.isEmpty()) return@launch
            items.forEach { item ->
                runCatching {
                    val zipFile = java.io.File(item.zipPath)
                    if (zipFile.exists()) {
                        if (zipFile.isDirectory) {
                            zipFile.deleteRecursively()
                        } else {
                            zipFile.delete()
                        }
                    }
                }
                val coverFile = java.io.File(item.coverPath)
                if (coverFile.exists()) coverFile.delete()
                downloadComicDao.updateStatus(
                    UpdateComicStatus(item.id, DownloadStatus.PENDING)
                )
                downloadComicDao.updateProgress(
                    com.par9uet.jm.database.model.UpdateComicProgress(item.id, 0f)
                )
            }
            downloadWorkScheduler.enqueue(items.map { it.id })
            toastManager.showAsync("已重新下载 ${items.size} 个任务")
        }
    }
}
