package com.par9uet.jm.download.coordinator

import android.content.Context
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.storage.CacheNotificationPreferences
import com.par9uet.jm.utils.COMIC_CACHE_NOTIFICATION_ID_BASE
import com.par9uet.jm.utils.DownloadSpeedTracker
import com.par9uet.jm.utils.cancelProgressNotification
import com.par9uet.jm.utils.showProgressNotification

interface DownloadFeedback {
    fun start(groupId: Int)
    fun stop(groupId: Int)
    fun showProgress(downloadTask: DownloadComic, progress: Float)
    fun cancel(groupId: Int)
    fun report(batchId: String, batchTotal: Int, comicId: Int, success: Boolean)
}

class DeviceDownloadFeedback(
    private val appContext: Context,
    private val cacheNotificationPreferences: CacheNotificationPreferences,
    private val downloadToastAggregator: DownloadToastAggregator,
) : DownloadFeedback {
    override fun start(groupId: Int) = DownloadSpeedTracker.startTracking(groupId)
    override fun stop(groupId: Int) = DownloadSpeedTracker.stopTracking(groupId)
    override fun cancel(groupId: Int) =
        cancelProgressNotification(appContext, COMIC_CACHE_NOTIFICATION_ID_BASE + groupId)

    override fun report(batchId: String, batchTotal: Int, comicId: Int, success: Boolean) =
        downloadToastAggregator.report(batchId, batchTotal, comicId, success)

    override fun showProgress(downloadTask: DownloadComic, progress: Float) {
        val groupId = downloadTask.groupId.takeIf { it != 0 } ?: downloadTask.id
        val setting = cacheNotificationPreferences.cacheNotification.value
        if (!setting.show) {
            cancelProgressNotification(appContext, COMIC_CACHE_NOTIFICATION_ID_BASE + groupId)
            return
        }
        val comicName = downloadTask.groupName.ifBlank { downloadTask.name }
        val title = if (setting.showName && comicName.isNotBlank()) {
            "正在缓存$comicName"
        } else {
            "正在缓存漫画"
        }
        val progressPercent = (progress.coerceIn(0f, 1f) * 100).toInt()
        showProgressNotification(
            context = appContext,
            notificationId = COMIC_CACHE_NOTIFICATION_ID_BASE + groupId,
            title = title,
            text = "$progressPercent%",
            progressPercent = progressPercent
        )
    }

}
