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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import coil.ImageLoader
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.retrofit.model.ComicDetailResponse
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.store.PostStartupInitializer
import com.par9uet.jm.store.RemoteSettingManager
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.ui.components.JmCoverImage
import com.par9uet.jm.ui.components.AppSnackbarHost
import com.par9uet.jm.ui.screens.AppLockScreen
import com.par9uet.jm.ui.screens.AppScreen
import com.par9uet.jm.ui.screens.NsfwWarningDialog
import com.par9uet.jm.ui.screens.WelcomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.getKoin

@Composable
fun App(
    toastManager: ToastManager = getKoin().get(),
    localSettingManager: LocalSettingManager = getKoin().get(),
    postStartupInitializer: PostStartupInitializer = getKoin().get(),
) {
    val localSetting by localSettingManager.localSettingState.collectAsState()
    val showOnboarding = !localSetting.onboardingCompleted
    var isLocked by remember { mutableStateOf(localSetting.appLockEnabled) }
    var sessionNsfwDismissed by remember { mutableStateOf(localSetting.nsfwWarningDismissed) }

    // Only the small local state needed to choose the first safe screen is loaded here. All
    // network, history, launcher, notification, and account work waits for the first frame to be
    // handed to the user, so background tasks never compete with first-frame CPU/disk work.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        postStartupInitializer.start()
    }

    LaunchedEffect(localSetting.appLockEnabled) {
        if (!localSetting.appLockEnabled) isLocked = false
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, localSetting.appLockEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && localSetting.appLockEnabled) {
                isLocked = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val showAppLock = localSetting.appLockEnabled && isLocked && !showOnboarding
    val showNsfwDialog = !showAppLock && !showOnboarding &&
        !sessionNsfwDismissed && !localSetting.nsfwWarningDismissed

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
                isLocked = localSettingManager.localSettingState.value.appLockEnabled
            }
        )

        showAppLock -> AppLockScreen(
            unlockMode = localSetting.appLockUnlockMode,
            correctPassword = localSetting.appLockPassword,
            correctPattern = localSetting.appLockPattern,
            passwordLength = localSetting.appLockPasswordLength,
            onUnlock = { isLocked = false }
        )

        else -> MainAppContent(
            localSetting = localSetting,
            localSettingManager = localSettingManager,
            toastManager = toastManager,
            showNsfwDialog = showNsfwDialog,
            onNsfwDismissed = { sessionNsfwDismissed = true },
        )
    }
}

@Composable
private fun MainAppContent(
    localSetting: LocalSetting,
    localSettingManager: LocalSettingManager,
    toastManager: ToastManager,
    showNsfwDialog: Boolean,
    onNsfwDismissed: () -> Unit,
) {
    val mainNavController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboardManager = LocalClipboardManager.current
    val koin = getKoin()
    var lastClipboardText by remember { mutableStateOf("") }
    var clipboardDetectedComicId by remember { mutableStateOf<Int?>(null) }
    var clipboardDetectedComic by remember { mutableStateOf<Comic?>(null) }
    var pendingNavComicId by remember { mutableStateOf(-1) }

    DisposableEffect(lifecycleOwner, localSetting.clipboardAutoDetectEnabled) {
        if (!localSetting.clipboardAutoDetectEnabled) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val clipText = clipboardManager.getText()?.text ?: ""
                    if (clipText.isNotBlank() && clipText != lastClipboardText) {
                        lastClipboardText = clipText
                        val digits = clipText.filter { it.isDigit() }
                        if (digits.length in 3..12) {
                            clipboardDetectedComicId = digits.toIntOrNull()
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
            is NetWorkResult.Success<*> -> {
                @Suppress("UNCHECKED_CAST")
                clipboardDetectedComic = (result.data as ComicDetailResponse).toComic()
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
            snackbarHostState.showSnackbar(
                message = text,
                actionLabel = null,
                duration = SnackbarDuration.Short
            )
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
                onAccept = { dontShowAgain ->
                    if (dontShowAgain) localSettingManager.dismissNsfwWarning()
                    onNsfwDismissed()
                },
                onDismiss = onNsfwDismissed,
            )
        }

        clipboardDetectedComic?.let { comic ->
            ClipboardDetectedComicDialog(
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
    comic: Comic,
    onDismiss: () -> Unit,
    onNavigate: (Int) -> Unit,
    remoteSettingManager: RemoteSettingManager = getKoin().get(),
    imageLoader: ImageLoader = getKoin().get(),
) {
    val remoteSetting by remoteSettingManager.remoteSettingState.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检测到漫画编码", fontWeight = FontWeight.Bold) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                JmCoverImage(
                    comicId = comic.id,
                    remoteHost = remoteSetting.imgHost,
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
        },
        confirmButton = {
            TextButton(onClick = { onNavigate(comic.id) }) { Text("跳转详情") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private tailrec fun Context.findActivity(): MainActivity? {
    return when (this) {
        is MainActivity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
