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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class DownloadOutcome { SUCCESS, RETRY, FAILURE }

class DownloadComicCoordinator(
    private val downloadComicDao: DownloadComicDao,
    private val remoteConfigPreferences: RemoteConfigPreferences,
    private val content: DownloadContentOperations,
    private val feedback: DownloadFeedback,
) : DownloadExecutionControl {
    private val executionGate = Mutex()
    private val running = mutableMapOf<Int, Job>()

    /** Do not let a new writer start until cancellation and the state/file mutation finish. */
    override suspend fun <T> withStoppedDownloads(comicIds: Collection<Int>, block: suspend () -> T): T =
        executionGate.withLock {
            val tasks = comicIds.mapNotNull { downloadComicDao.getById(it) }
            val jobs = synchronized(running) { comicIds.mapNotNull(running::get) }
            jobs.forEach { it.cancel() }
            jobs.joinAll()
            try {
                block()
            } finally {
                // Retry backoff has no running coroutine, but may still own a progress notice.
                withContext(NonCancellable) {
                    tasks.distinctBy { it.groupId.takeIf { id -> id != 0 } ?: it.id }.forEach { task ->
                        val groupId = task.groupId.takeIf { it != 0 } ?: task.id
                        val active = downloadComicDao.getByGroupId(groupId).any {
                            it.status == DownloadStatus.PENDING || it.status == DownloadStatus.DOWNLOADING
                        }
                        if (!active) {
                            feedback.stop(groupId)
                            feedback.cancel(groupId)
                        }
                    }
                }
            }
        }

    suspend fun download(comicId: Int, batchId: String, batchTotal: Int, runAttemptCount: Int): DownloadOutcome = coroutineScope {
        val job = currentCoroutineContext().job
        val skipped = executionGate.withLock {
            currentCoroutineContext().ensureActive()
            val task = downloadComicDao.getById(comicId)
            when {
                comicId == -1 || task == null -> DownloadOutcome.FAILURE
                task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.COMPLETE -> DownloadOutcome.SUCCESS
                else -> synchronized(running) {
                    if (running.containsKey(comicId)) DownloadOutcome.SUCCESS
                    else { running[comicId] = job; null }
                }
            }
        }
        if (skipped != null) return@coroutineScope skipped
        try {
            downloadAttempt(comicId, batchId, batchTotal, runAttemptCount)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                downloadComicDao.getById(comicId)?.let { task ->
                    feedback.stop(task.groupId.takeIf { it != 0 } ?: task.id)
                    feedback.cancel(task.groupId.takeIf { it != 0 } ?: task.id)
                }
            }
            throw cancelled
        } finally {
            synchronized(running) { if (running[comicId] === job) running.remove(comicId) }
        }
    }

    private suspend fun downloadAttempt(comicId: Int, batchId: String, batchTotal: Int, runAttemptCount: Int): DownloadOutcome {
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
