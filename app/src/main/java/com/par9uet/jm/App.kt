package com.par9uet.jm

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.par9uet.jm.ui.navigation.RetainedMainNavigation
import com.par9uet.jm.ui.models.ComicDetailLoader
import com.par9uet.jm.ui.models.ComicDetailOpener
import com.par9uet.jm.ui.models.LocalComicDetailLoader
import com.par9uet.jm.ui.models.LocalComicDetailOpener
import com.par9uet.jm.ui.models.LocalRemoteImageHost
import com.par9uet.jm.ui.viewModel.ComicDetailViewModel
import coil.ImageLoader
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.startup.PostStartupCoordinator
import com.par9uet.jm.storage.LocalSettingManager
import com.par9uet.jm.storage.ReaderResumeManager
import com.par9uet.jm.storage.RemoteConfigPreferences
import com.par9uet.jm.core.ToastManager
import com.par9uet.jm.ui.components.JmCoverImage
import com.par9uet.jm.ui.components.AppSnackbarHost
import com.par9uet.jm.ui.glass.GlassModal
import com.par9uet.jm.ui.screens.AppLockScreen
import com.par9uet.jm.ui.screens.AppScreen
import com.par9uet.jm.ui.screens.NsfwWarningDialog
import com.par9uet.jm.ui.screens.WelcomeScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin
import org.koin.compose.viewmodel.koinActivityViewModel

@Composable
fun App(
    toastManager: ToastManager = getKoin().get(),
    localSettingManager: LocalSettingManager = getKoin().get(),
    postStartupCoordinator: PostStartupCoordinator = getKoin().get(),
    remoteConfigPreferences: RemoteConfigPreferences = getKoin().get(),
) {
    val appLock by localSettingManager.appLock.collectAsState()
    val onboardingCompleted by localSettingManager.onboardingCompleted.collectAsState()
    val nsfwWarningDismissed by localSettingManager.nsfwWarningDismissed.collectAsState()
    val miscSettings by localSettingManager.misc.collectAsState()
    val showOnboarding = !onboardingCompleted
    var isLocked by remember { mutableStateOf(appLock.enabled) }
    var sessionNsfwDismissed by remember { mutableStateOf(nsfwWarningDismissed) }

    // Only the small local state needed to choose the first safe screen is loaded here. All
    // network, history, launcher, notification, and account work waits for the first frame to be
    // handed to the user, so background tasks never compete with first-frame CPU/disk work.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        postStartupCoordinator.start()
    }

    LaunchedEffect(appLock.enabled) {
        if (!appLock.enabled) isLocked = false
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, appLock.enabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && appLock.enabled) {
                isLocked = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val showAppLock = appLock.enabled && isLocked && !showOnboarding
    val showNsfwDialog = !showAppLock && !showOnboarding &&
        !sessionNsfwDismissed && !nsfwWarningDismissed

    // Mark the first real screen for startup traces. Permission prompts are scheduled after that
    // frame and never compete with the onboarding or app-lock screen.
    val context = LocalContext.current
    LaunchedEffect(showOnboarding, showAppLock) {
        withFrameNanos { }
        context.findActivity()?.let { activity ->
            activity.reportFullyDrawn()
            if (!showOnboarding && !showAppLock) {
                activity.requestNotificationPermissionIfNeeded()
            }
        }
    }

    when {
        showOnboarding -> WelcomeScreen(
            onComplete = {
                isLocked = localSettingManager.appLock.value.enabled
            }
        )

        showAppLock -> AppLockScreen(
            unlockMode = appLock.unlockMode,
            correctPassword = appLock.password,
            correctPattern = appLock.pattern,
            passwordLength = appLock.passwordLength,
            onUnlock = { isLocked = false }
        )

    }
    // 远端图片主机是 App 级环境值：在这里读一次，组件与页面只消费环境值，
    // 避免每个看图的地方各自依赖 storage 端口。
    val remoteImageHost by remoteConfigPreferences.remoteImageHost.collectAsState()
    val koin = getKoin()
    val detailLoader = remember(koin) {
        ComicDetailLoader { id ->
            withContext(Dispatchers.IO) {
                val outcome = runCatching { koin.get<ComicRepository>().getComicDetail(id) }
                // runCatching 会连 CancellationException 一起吞掉，把它当成"详情获取失败"
                // 会让协程取消无法传播（经典坑）。必须原样抛出。
                outcome.exceptionOrNull()?.let { error ->
                    if (error is CancellationException) throw error
                }
                when (val result = outcome.getOrNull()) {
                    is NetWorkResult.Success -> result.data
                    else -> null
                }
            }
        }
    }
    CompositionLocalProvider(
        LocalRemoteImageHost provides remoteImageHost,
        LocalComicDetailLoader provides detailLoader,
    ) {
        RetainedMainNavigation(visible = !showOnboarding && !showAppLock) { mainNavController ->
            // 在根上准备"打开详情"的编排：先用列表项预置详情状态，再导航。
            // ui/components 只读 CompositionLocal，不依赖 ViewModel。
            val comicDetailViewModel: ComicDetailViewModel = koinActivityViewModel()
            val detailOpener = remember(mainNavController, comicDetailViewModel) {
                ComicDetailOpener { comic ->
                    comicDetailViewModel.prepareDetail(comic)
                    mainNavController.navigate("comicDetail/${comic.id}")
                }
            }
            CompositionLocalProvider(LocalComicDetailOpener provides detailOpener) {
                MainAppContent(
                    mainNavController = mainNavController,
                    clipboardAutoDetectEnabled = miscSettings.clipboardAutoDetectEnabled,
                    localSettingManager = localSettingManager,
                    toastManager = toastManager,
                    showNsfwDialog = showNsfwDialog,
                    onNsfwDismissed = { sessionNsfwDismissed = true },
                )
            }
        }
    }
}

@Composable
private fun MainAppContent(
    mainNavController: NavHostController,
    clipboardAutoDetectEnabled: Boolean,
    localSettingManager: LocalSettingManager,
    toastManager: ToastManager,
    showNsfwDialog: Boolean,
    onNsfwDismissed: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val koin = getKoin()
    var lastClipboardText by remember { mutableStateOf("") }
    var clipboardDetectedComicId by remember { mutableStateOf<Int?>(null) }
    var clipboardDetectedComic by remember { mutableStateOf<Comic?>(null) }
    var pendingNavComicId by remember { mutableIntStateOf(-1) }

    // Process death while reading never runs onDispose. If Navigation could not restore the
    // back stack (HyperOS cold start), fall back to the durable reader resume mark.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        kotlinx.coroutines.delay(300)
        val route = mainNavController.currentDestination?.route
        if (route != null && !route.startsWith("tab")) return@LaunchedEffect
        val session = koin.get<ReaderResumeManager>().peekResumable() ?: return@LaunchedEffect
        val target = if (session.localOnly) {
            "localComicRead/${session.chapterId}"
        } else {
            "comicRead/${session.chapterId}"
        }
        runCatching { mainNavController.navigate(target) }
            .onSuccess { toastManager.showAsync("已恢复上次阅读") }
    }

    DisposableEffect(lifecycleOwner, clipboardAutoDetectEnabled) {
        if (!clipboardAutoDetectEnabled) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    clipboardScope.launch {
                        val clipEntry = clipboard.getClipEntry()
                        val clipText = clipEntry?.clipData?.getItemAt(0)?.text?.toString() ?: ""
                        if (clipText.isNotBlank() && clipText != lastClipboardText) {
                            lastClipboardText = clipText
                            val digits = clipText.filter { it.isDigit() }
                            if (digits.length in 3..12) {
                                clipboardDetectedComicId = digits.toIntOrNull()
                            }
                        }
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    // Resolve the repository only after the clipboard feature has actually detected an ID.
    LaunchedEffect(clipboardDetectedComicId) {
        val id = clipboardDetectedComicId ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            runCatching { koin.get<ComicRepository>().getComicDetail(id) }.getOrNull()
        }
        when (result) {
            is NetWorkResult.Success -> {
                clipboardDetectedComic = result.data
            }

            else -> {
                toastManager.showAsync("剪切板检测：漫画编码 ${id} 无效")
                clipboardDetectedComicId = null
            }
        }
    }

    LaunchedEffect(pendingNavComicId) {
        if (pendingNavComicId > 0) {
            mainNavController.navigate("comicDetail/$pendingNavComicId")
            pendingNavComicId = -1
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        toastManager.message.collect { text ->
            // Central transient-feedback policy: every message is visible for ~5 seconds.
            // Indefinite duration keeps the current snackbar on screen until the timeout
            // cancels it, so queued messages display in order without overwriting.
            withTimeoutOrNull(5_000L) {
                snackbarHostState.showSnackbar(
                    message = text,
                    actionLabel = null,
                    duration = SnackbarDuration.Indefinite,
                )
            }
        }
    }

    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (showNsfwDialog && canBlur) Modifier.blur(32.dp) else Modifier)
        ) {
            AppScreen(externalNavController = mainNavController)
            AppSnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 80.dp)
                    .imePadding()
            )
        }

        if (showNsfwDialog) {
            NsfwWarningDialog(
                visible = showNsfwDialog,
                onAccept = { dontShowAgain ->
                    if (dontShowAgain) localSettingManager.dismissNsfwWarning()
                    onNsfwDismissed()
                },
                onDismiss = onNsfwDismissed,
            )
        }

        clipboardDetectedComic?.let { comic ->
            ClipboardDetectedComicDialog(
                visible = true,
                comic = comic,
                onDismiss = {
                    clipboardDetectedComic = null
                    clipboardDetectedComicId = null
                },
                onNavigate = { id ->
                    clipboardDetectedComic = null
                    clipboardDetectedComicId = null
                    pendingNavComicId = id
                },
            )
        }
    }
}

@Composable
private fun ClipboardDetectedComicDialog(
    visible: Boolean,
    comic: Comic,
    onDismiss: () -> Unit,
    onNavigate: (Int) -> Unit,
    remoteConfigPreferences: RemoteConfigPreferences = getKoin().get(),
    imageLoader: ImageLoader = getKoin().get(),
) {
    val remoteHost by remoteConfigPreferences.remoteImageHost.collectAsState()
    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        surfaceId = "clipboard-comic-glass",
        modifier = Modifier.widthIn(max = 420.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("检测到漫画编码", fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                JmCoverImage(
                    comicId = comic.id,
                    remoteHost = remoteHost,
                    imageLoader = imageLoader,
                    contentDescription = "${comic.name}的封面",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(96.dp)
                        .height(128.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "JM${comic.id}",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = comic.name,
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (comic.authorList.isNotEmpty()) {
                        Text(
                            text = "作者：${comic.authorList.joinToString("、")}",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (comic.tagList.isNotEmpty()) {
                        Text(
                            text = "标签：${comic.tagList.take(8).joinToString("、")}",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = { onNavigate(comic.id) }) { Text("跳转详情") }
            }
        }
    }
}

private tailrec fun Context.findActivity(): MainActivity? {
    return when (this) {
        is MainActivity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
