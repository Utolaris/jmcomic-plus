package com.par9uet.jm.cache.migration

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo
import com.par9uet.jm.utils.CACHE_MIGRATION_NOTIFICATION_ID
import com.par9uet.jm.utils.cacheMigrationNotification

/**
 * L2 adapter that turns migration progress into the worker's foreground notification. The Worker
 * only hands over a percent and a stage label, so it stays free of notification assembly and no
 * longer references another L1 entry (MainActivity).
 */
class CacheMigrationNotifications(private val appContext: Context) {
    fun create(percent: Int, stage: String): ForegroundInfo = ForegroundInfo(
        CACHE_MIGRATION_NOTIFICATION_ID,
        cacheMigrationNotification(appContext, percent, stage),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
}
