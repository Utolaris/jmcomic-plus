package com.par9uet.jm.utils

import android.annotation.SuppressLint
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.par9uet.jm.R

const val DOWNLOAD_NOTIFICATION_CHANNEL_ID = "download_progress"
const val UPDATE_DOWNLOADED_CHANNEL_ID = "update_downloaded"
const val COMIC_CACHE_NOTIFICATION_ID_BASE = 20_000
const val APP_UPDATE_NOTIFICATION_ID = 10_001
const val APP_UPDATE_PENDING_INTENT_REQUEST_CODE = 10_101
const val EXTRA_NAVIGATE_ROUTE = "navigate_route"
const val NAVIGATE_ROUTE_CHECK_UPDATE = "checkUpdate"

fun ensureAppNotificationChannels(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    val progressChannel = NotificationChannel(
        DOWNLOAD_NOTIFICATION_CHANNEL_ID,
        "Download progress",
        NotificationManager.IMPORTANCE_LOW
    ).apply {
        description = "Shows app update and comic cache download progress"
        setSound(null, null)
    }
    manager.createNotificationChannel(progressChannel)
    val updateChannel = NotificationChannel(
        UPDATE_DOWNLOADED_CHANNEL_ID,
        "Update downloaded",
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = "Notifies you when an update package has been downloaded"
        enableVibration(true)
    }
    manager.createNotificationChannel(updateChannel)
}

/**
 * 下载完成通知：点击后打开 launcher 主 Activity 并携带 [EXTRA_NAVIGATE_ROUTE] = checkUpdate，
 * 由 AppScreen 读取后导航到检查更新页面。不直接引用 MainActivity，切断 utils→入口依赖环。
 */
@SuppressLint("MissingPermission")
fun showUpdateDownloadedNotification(
    context: Context,
    version: String,
    savedPath: String,
) {
    if (!canPostNotification(context)) return
    val intent = launcherActivityIntent(context).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(EXTRA_NAVIGATE_ROUTE, NAVIGATE_ROUTE_CHECK_UPDATE)
        putExtra(EXTRA_UPDATE_SAVED_PATH, savedPath)
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        APP_UPDATE_PENDING_INTENT_REQUEST_CODE,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val notification = NotificationCompat.Builder(context, UPDATE_DOWNLOADED_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_download_notification)
        .setContentTitle("更新包已下载完成")
        .setContentText("v$version 已就绪，点击前往安装")
        .setAutoCancel(true)
        .setOngoing(false)
        .setContentIntent(pendingIntent)
        .build()
    runCatching {
        NotificationManagerCompat.from(context).notify(APP_UPDATE_NOTIFICATION_ID, notification)
    }
}

const val EXTRA_UPDATE_SAVED_PATH = "update_saved_path"

@SuppressLint("MissingPermission")
fun showProgressNotification(
    context: Context,
    notificationId: Int,
    title: String,
    text: String,
    progressPercent: Int
) {
    if (!canPostNotification(context)) return
    val notification = NotificationCompat.Builder(context, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_download_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(100, progressPercent.coerceIn(0, 100), false)
        .build()
    runCatching {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}

fun cancelProgressNotification(context: Context, notificationId: Int) {
    runCatching {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }
}

const val CACHE_MIGRATION_NOTIFICATION_ID = 19_940

/**
 * 缓存目录迁移的前台通知。放在这里和其余通知构造待在一起，Worker 只负责把进度交出来，
 * 不需要知道通知怎么拼、也不需要反向引用入口 Activity。
 */
fun cacheMigrationNotification(context: Context, percent: Int, stage: String): Notification {
    val openApp = PendingIntent.getActivity(
        context,
        CACHE_MIGRATION_NOTIFICATION_ID,
        launcherActivityIntent(context).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(context, DOWNLOAD_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_download_notification)
        .setContentTitle("正在迁移漫画缓存")
        .setContentText(stage)
        .setContentIntent(openApp)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(100, percent.coerceIn(0, 100), false)
        .build()
}

/** Launcher activity intent without importing the concrete Activity type. */
private fun launcherActivityIntent(context: Context): Intent {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (launch != null) return Intent(launch)
    return Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setPackage(context.packageName)
    }
}

private fun canPostNotification(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
}
