package com.par9uet.jm.store

import com.par9uet.jm.utils.ensureAppNotificationChannels
import com.par9uet.jm.utils.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Starts work that improves the next interaction but does not determine the first safe screen.
 * Dependencies are resolved inside the launched jobs, rather than while the root composable is
 * being constructed.
 */
class PostStartupInitializer(
    private val scope: CoroutineScope,
    private val koin: Koin,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return

        launchTask("远程应用设置") {
            koin.get<RemoteSettingManager>().refresh()
        }
        launchTask("桌面图标入口") {
            koin.get<LocalSettingManager>().applyLauncherDisguiseIfNeeded()
        }
        launchTask("搜索历史") {
            koin.get<HistorySearchManager>().load()
        }
        launchTask("阅读历史") {
            koin.get<ReadHistoryManager>().load()
        }
        launchTask("通知渠道") {
            ensureAppNotificationChannels(koin.get())
        }
        launchTask("用户状态验证和自动签到") {
            val userManager = koin.get<UserManager>()
            userManager.verifyStoredLogin()
            userManager.autoSignInIfNeeded(
                enabled = koin.get<LocalSettingManager>().localSettingState.value.autoSignInEnabled,
                toastManager = koin.get()
            )
        }
    }

    private fun launchTask(name: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                log("启动后任务", "$name 失败：${error.message}")
            }
        }
    }

}
