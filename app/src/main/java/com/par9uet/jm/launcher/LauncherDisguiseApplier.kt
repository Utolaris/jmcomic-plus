package com.par9uet.jm.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.utils.log

interface LauncherIdentityApplier {
    fun apply(disguise: LauncherDisguise)
}

class LauncherDisguiseApplier(
    private val context: Context,
) : LauncherIdentityApplier {
    override fun apply(disguise: LauncherDisguise) {
        val packageManager = context.packageManager
        val componentClassPrefix = context.packageName
        LauncherDisguise.entries.forEach { item ->
            runCatching {
                val componentName = ComponentName(
                    context.packageName,
                    "$componentClassPrefix${item.aliasClassName}"
                )
                val expectedEnabled = item == disguise
                val currentEnabled = when (packageManager.getComponentEnabledSetting(componentName)) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
                    // The default alias is enabled in the manifest; the disguise aliases are not.
                    else -> item == LauncherDisguise.Default
                }
                if (currentEnabled == expectedEnabled) return@runCatching
                packageManager.setComponentEnabledSetting(
                    componentName,
                    if (expectedEnabled) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    },
                    PackageManager.DONT_KILL_APP
                )
            }.onFailure {
                log("切换桌面图标入口失败：${item.id}，原因：${it.message}")
            }
        }
    }
}
