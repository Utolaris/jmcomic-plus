package com.par9uet.jm.startup

import com.par9uet.jm.network.DohManager
import com.par9uet.jm.store.HistorySearchManager
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.ReadHistoryManager
import com.par9uet.jm.store.RemoteConfigManager
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.store.UserManager
import com.par9uet.jm.utils.ensureAppNotificationChannels
import com.par9uet.jm.utils.log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.koin.core.Koin

/** Coordinates work that improves later interactions without delaying the first safe screen. */
class PostStartupCoordinator(
    private val scope: CoroutineScope,
    private val koin: Koin,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return

        launchTask("DoH 网络配置") {
            runAuthenticatedStartupTasks(
                initDoh = { koin.get<DohManager>().init() },
                refreshRemoteConfig = { koin.get<RemoteConfigManager>().refresh() },
                verifyUser = {
                    val userManager = koin.get<UserManager>()
                    userManager.verifyStoredLogin()
                    userManager.autoSignInIfNeeded(
                        enabled = koin.get<LocalSettingManager>().currentAutoSignInEnabled(),
                        toastManager = koin.get<ToastManager>(),
                    )
                },
            )
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

/** Activates DoH before remote configuration and account verification start in parallel. */
internal suspend fun runAuthenticatedStartupTasks(
    initDoh: suspend () -> Unit,
    refreshRemoteConfig: suspend () -> Unit,
    verifyUser: suspend () -> Unit,
) {
    initDoh()
    supervisorScope {
        launch { refreshRemoteConfig() }
        launch { verifyUser() }
    }
}
