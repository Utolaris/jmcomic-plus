package com.par9uet.jm.worker

import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus

internal const val DOWNLOAD_MAX_ATTEMPTS = 6

internal fun shouldRetryDownload(runAttemptCount: Int): Boolean =
    runAttemptCount < DOWNLOAD_MAX_ATTEMPTS - 1

internal fun advancedDownloadProgress(current: Float, next: Float): Float =
    maxOf(current, next.coerceIn(0f, 1f))

internal fun groupDownloadProgress(
    chapters: List<DownloadComic>,
    activeChapterId: Int,
    activeProgress: Float,
): Float {
    if (chapters.isEmpty()) return activeProgress.coerceIn(0f, 1f)
    return chapters.map { chapter ->
        when {
            chapter.id == activeChapterId -> activeProgress
            chapter.status == DownloadStatus.COMPLETE -> 1f
            else -> chapter.progress.coerceIn(0f, 1f)
        }
    }.average().toFloat().coerceIn(0f, 1f)
}
