package com.par9uet.jm.store

import com.par9uet.jm.utils.ensureAppNotificationChannels
import com.par9uet.jm.utils.log
import com.par9uet.jm.network.DohManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
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

        launchTask("DoH 网络配置") {
            runAuthenticatedStartupTasks(
                initDoh = { koin.get<DohManager>().init() },
                refreshRemoteConfig = { koin.get<RemoteConfigManager>().refresh() },
                verifyUser = {
                    val userManager = koin.get<UserManager>()
                    userManager.verifyStoredLogin()
                    userManager.autoSignInIfNeeded(
                        enabled = koin.get<LocalSettingManager>().currentAutoSignInEnabled(),
                        toastManager = koin.get(),
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

/**
 * DoH must be active before the first authenticated/network request resolves DNS: init runs
 * first; only then do remote-config and user verification start, in parallel branches inside a
 * supervisorScope so one slow/failing branch never blocks or cancels the other. Cancellation
 * still propagates to both branches.
 */
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
