package com.par9uet.jm.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.par9uet.jm.BuildConfig
import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.utils.log

/** Generated into the namespace package, which is where the manifest declares the aliases. */
private val ALIAS_CLASS_PACKAGE = BuildConfig::class.java.name.substringBeforeLast('.')

/**
 * The manifest resolves alias class names against the module namespace, which is not the
 * application id on build types that append a suffix, so [Context.getPackageName] cannot name
 * them. The class name has to come from a class in the namespace package instead.
 */
internal fun launcherAliasComponent(context: Context, disguise: LauncherDisguise): ComponentName =
    ComponentName(context.packageName, "$ALIAS_CLASS_PACKAGE${disguise.aliasClassName}")

/**
 * Enables [disguise] before disabling the other aliases. An app whose aliases are all disabled
 * drops off the home screen, so a failed enable must leave the current entry untouched instead
 * of swapping to nothing. Returns whether [disguise] is the active entry afterwards.
 */
internal fun switchLauncherAlias(
    disguise: LauncherDisguise,
    isEnabled: (LauncherDisguise) -> Boolean,
    setEnabled: (LauncherDisguise, Boolean) -> Unit,
    onFailure: (String) -> Unit,
): Boolean {
    val enabled = runCatching {
        if (!isEnabled(disguise)) setEnabled(disguise, true)
        true
    }.getOrElse { error ->
        onFailure("启用桌面图标入口失败：${disguise.id}，原因：${error.message}")
        false
    }
    if (!enabled) return false

    LauncherDisguise.entries.forEach { item ->
        if (item == disguise) return@forEach
        runCatching {
            if (isEnabled(item)) setEnabled(item, false)
        }.onFailure { error ->
            onFailure("关闭桌面图标入口失败：${item.id}，原因：${error.message}")
        }
    }
    return true
}

interface LauncherIdentityApplier {
    /** Returns whether [disguise] is the launcher entry in use afterwards. */
    fun apply(disguise: LauncherDisguise): Boolean
}

class LauncherDisguiseApplier(
    private val context: Context,
) : LauncherIdentityApplier {
    override fun apply(disguise: LauncherDisguise): Boolean = switchLauncherAlias(
        disguise = disguise,
        isEnabled = { isEnabled(context.packageManager, it) },
        setEnabled = { item, enabled -> setEnabled(context.packageManager, item, enabled) },
        onFailure = ::log,
    )

    private fun isEnabled(packageManager: PackageManager, item: LauncherDisguise): Boolean =
        when (packageManager.getComponentEnabledSetting(launcherAliasComponent(context, item))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            // The default alias is enabled in the manifest; the disguise aliases are not.
            else -> item == LauncherDisguise.Default
        }

    private fun setEnabled(packageManager: PackageManager, item: LauncherDisguise, enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            launcherAliasComponent(context, item),
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP
        )
    }
}
