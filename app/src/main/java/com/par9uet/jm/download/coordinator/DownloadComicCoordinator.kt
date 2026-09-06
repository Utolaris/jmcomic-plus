package com.par9uet.jm.download.coordinator

import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.database.model.UpdateComicCover
import com.par9uet.jm.database.model.UpdateComicProgress
import com.par9uet.jm.database.model.UpdateComicStatus
import com.par9uet.jm.download.molecule.DownloadContentOperations
import com.par9uet.jm.image.cancellationExceptionOrNull
import com.par9uet.jm.store.RemoteConfigPreferences

enum class DownloadOutcome { SUCCESS, RETRY, FAILURE }

class DownloadComicCoordinator(
    private val downloadComicDao: DownloadComicDao,
    private val remoteConfigPreferences: RemoteConfigPreferences,
    private val content: DownloadContentOperations,
    private val feedback: DownloadFeedback,
) {
    suspend fun download(comicId: Int, batchId: String, batchTotal: Int, runAttemptCount: Int): DownloadOutcome {
        if (comicId == -1) {
            return DownloadOutcome.FAILURE
        }

        val coverOwnerId = downloadComicDao.getById(comicId)?.let {
            it.groupId.takeIf { g -> g != 0 } ?: comicId
        } ?: comicId

        return try {
            val downloadTask = downloadComicDao.getById(comicId) ?: return DownloadOutcome.FAILURE
            downloadComicDao.updateStatus(UpdateComicStatus(comicId, DownloadStatus.DOWNLOADING))
            feedback.start(coverOwnerId)
            feedback.showProgress(
                downloadTask,
                resolveGroupProgress(downloadTask, downloadTask.progress)
            )

            val coverPath = content.downloadCover(downloadTask, coverOwnerId, remoteConfigPreferences.remoteImageHost.value)
            downloadComicDao.updateCover(UpdateComicCover(comicId, coverPath))

            var maxProgress = downloadComicDao.getById(comicId)?.progress ?: 0f
            content.downloadPages(downloadTask) { nextProgress ->
                val progress = updateChapterProgressIfAdvanced(downloadTask, maxProgress, nextProgress)
                maxProgress = progress.chapterProgress
                feedback.showProgress(downloadTask, progress.groupProgress)
            }
            feedback.showProgress(downloadTask, updateChapterProgress(downloadTask, 1f))

            content.complete(downloadTask)
            feedback.stop(coverOwnerId)
            cancelComicCacheNotificationIfIdle(downloadTask)
            feedback.report(batchId, batchTotal, comicId, success = true)
            DownloadOutcome.SUCCESS
        } catch (e: Exception) {
            e.cancellationExceptionOrNull()?.let { throw it }
            if (shouldRetryDownload(runAttemptCount)) {
                DownloadOutcome.RETRY
            } else {
                downloadComicDao.updateStatus(UpdateComicStatus(comicId, DownloadStatus.ERROR))
                feedback.stop(coverOwnerId)
                downloadComicDao.getById(comicId)?.let {
                    cancelComicCacheNotificationIfIdle(it)
                }
                feedback.report(batchId, batchTotal, comicId, success = false)
                DownloadOutcome.FAILURE
            }
        }
    }

    private suspend fun updateChapterProgress(downloadTask: DownloadComic, progress: Float): Float {
        val chapterProgress = progress.coerceIn(0f, 1f)
        downloadComicDao.updateProgress(UpdateComicProgress(downloadTask.id, chapterProgress))
        return resolveGroupProgress(downloadTask, chapterProgress)
    }

    private suspend fun updateChapterProgressIfAdvanced(
        downloadTask: DownloadComic,
        currentMaxProgress: Float,
        nextProgress: Float
    ): DownloadProgress {
        val chapterProgress = advancedDownloadProgress(currentMaxProgress, nextProgress)
        if (chapterProgress > currentMaxProgress) {
            downloadComicDao.updateProgress(UpdateComicProgress(downloadTask.id, chapterProgress))
        }
        return DownloadProgress(
            chapterProgress = chapterProgress,
            groupProgress = resolveGroupProgress(downloadTask, chapterProgress)
        )
    }

    private suspend fun resolveGroupProgress(downloadTask: DownloadComic, currentProgress: Float): Float {
        val groupId = downloadTask.groupId.takeIf { it != 0 } ?: downloadTask.id
        val chapters = downloadComicDao.getByGroupId(groupId)
        return groupDownloadProgress(chapters, downloadTask.id, currentProgress)
    }

    private suspend fun cancelComicCacheNotificationIfIdle(downloadTask: DownloadComic) {
        val groupId = downloadTask.groupId.takeIf { it != 0 } ?: downloadTask.id
        val chapters = downloadComicDao.getByGroupId(groupId)
        val hasActiveTask = chapters.any {
            it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING
        }
        if (!hasActiveTask) {
            feedback.cancel(groupId)
        }
    }

    private data class DownloadProgress(
        val chapterProgress: Float,
        val groupProgress: Float,
    )
}
