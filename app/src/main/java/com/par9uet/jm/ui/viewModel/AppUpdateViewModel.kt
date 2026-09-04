package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.BuildConfig
import com.par9uet.jm.store.AppUpdateDownloads
import com.par9uet.jm.store.AppUpdateDownloadRequest
import com.par9uet.jm.store.AppUpdateDownloadState
import com.par9uet.jm.store.AppUpdateDownloadStatus
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.update.AppUpdateInstaller
import com.par9uet.jm.update.GithubRelease
import com.par9uet.jm.update.ReleaseSource
import com.par9uet.jm.update.UpdateState
import com.par9uet.jm.update.compareVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUpdateUiState(
    val updateState: UpdateState = UpdateState.Idle,
    val visibleRelease: GithubRelease? = null,
    val releaseDialogVisible: Boolean = false,
    val showDownloadDialog: Boolean = false,
)

class AppUpdateViewModel(
    private val releaseSource: ReleaseSource,
    private val downloads: AppUpdateDownloads,
    private val installer: AppUpdateInstaller,
    private val toastManager: ToastManager,
) : ViewModel() {
    private val _state = MutableStateFlow(AppUpdateUiState())
    val state = _state.asStateFlow()
    val downloadState = downloads.state

    init {
        checkUpdate()
    }

    fun checkUpdate() {
        if (_state.value.updateState == UpdateState.Checking) return
        _state.update { it.copy(updateState = UpdateState.Checking) }
        viewModelScope.launch {
            try {
                val release = releaseSource.latest()
                val hasUpdate = compareVersion(release.version, BuildConfig.VERSION_NAME) > 0
                _state.update {
                    it.copy(
                        updateState = UpdateState.Success(release, hasUpdate),
                        visibleRelease = release,
                        releaseDialogVisible = hasUpdate,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(updateState = UpdateState.Error(e.message ?: "检查更新失败")) }
            }
        }
    }

    fun showRelease() {
        val release = (_state.value.updateState as? UpdateState.Success)?.release ?: return
        _state.update { it.copy(visibleRelease = release, releaseDialogVisible = true) }
    }

    fun dismissRelease() {
        _state.update { it.copy(releaseDialogVisible = false) }
    }

    fun downloadRelease() {
        val release = _state.value.visibleRelease ?: return
        downloads.start(
            AppUpdateDownloadRequest(
                version = release.version,
                fileName = release.fileName.ifBlank { "jm-mobile_v${release.version}_unknown.apk" },
                downloadUrl = release.downloadUrl,
            )
        )
        _state.update { it.copy(releaseDialogVisible = false, showDownloadDialog = true) }
    }

    fun dismissDownload() {
        _state.update { it.copy(showDownloadDialog = false) }
    }

    fun toggleDownloadPause() {
        if (downloadState.value.status == AppUpdateDownloadStatus.Paused) downloads.resume()
        else downloads.pause()
    }

    fun cancelDownload() {
        downloads.cancel()
        dismissDownload()
    }

    fun backgroundDownload() {
        downloads.sendToBackground()
        dismissDownload()
    }

    fun isApkReady(download: AppUpdateDownloadState): Boolean =
        download.status == AppUpdateDownloadStatus.Completed && installer.isAvailable(download.savedPath)

    fun installDownload() {
        try {
            installer.install(downloadState.value.savedPath)
        } catch (e: Exception) {
            toastManager.showAsync("打开安装器失败：${e.message ?: "未知错误"}")
        }
    }
}
