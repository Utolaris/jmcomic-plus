package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModelStore
import com.par9uet.jm.BuildConfig
import com.par9uet.jm.store.AppUpdateDownloadRequest
import com.par9uet.jm.store.AppUpdateDownloadState
import com.par9uet.jm.store.AppUpdateDownloadStatus
import com.par9uet.jm.store.ToastManager
import com.par9uet.jm.store.AppUpdateDownloads
import com.par9uet.jm.update.AppUpdateInstaller
import com.par9uet.jm.update.GithubRelease
import com.par9uet.jm.update.ReleaseSource
import com.par9uet.jm.update.UpdateState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {
    private val release = GithubRelease("9999.0", "release", "https://example.com/release", "changes", "https://example.com/app.apk", "app.apk")
    private val downloads = FakeDownloads()
    private val installer = FakeInstaller()
    private val toast = ToastManager()

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeDownloads : AppUpdateDownloads {
        override val state = MutableStateFlow(AppUpdateDownloadState())
        var request: AppUpdateDownloadRequest? = null
        var canceled = false
        override fun start(request: AppUpdateDownloadRequest) {
            this.request = request
            state.value = state.value.copy(status = AppUpdateDownloadStatus.Downloading)
        }
        override fun pause() { state.value = state.value.copy(status = AppUpdateDownloadStatus.Paused) }
        override fun resume() { state.value = state.value.copy(status = AppUpdateDownloadStatus.Downloading) }
        override fun cancel() { canceled = true }
        override fun sendToBackground() { state.value = state.value.copy(background = true) }
    }

    private class FakeInstaller : AppUpdateInstaller {
        var installed: String? = null
        var available = true
        override fun isAvailable(savedPath: String) = available && savedPath.isNotEmpty()
        override fun install(savedPath: String) {
            check(isAvailable(savedPath)) { "file missing" }
            installed = savedPath
        }
    }

    @Test fun `check is deduplicated and newer release opens dialog`() = runTest {
        val response = CompletableDeferred<GithubRelease>()
        var calls = 0
        val vm = AppUpdateViewModel(ReleaseSource { calls++; response.await() }, downloads, installer, toast)
        vm.checkUpdate(); runCurrent()
        assertEquals(1, calls)
        assertEquals(UpdateState.Checking, vm.state.value.updateState)
        response.complete(release); runCurrent()
        assertTrue((vm.state.value.updateState as UpdateState.Success).hasUpdate)
        assertTrue(vm.state.value.releaseDialogVisible)
        vm.dismissRelease()
        assertFalse(vm.state.value.releaseDialogVisible)
        vm.showRelease()
        assertTrue(vm.state.value.releaseDialogVisible)
    }

    @Test fun `current version stays closed and a failed check can retry`() = runTest {
        var fail = true
        val vm = AppUpdateViewModel(ReleaseSource {
            if (fail) error("offline")
            release.copy(version = BuildConfig.VERSION_NAME)
        }, downloads, installer, toast)
        runCurrent()
        assertEquals(UpdateState.Error("offline"), vm.state.value.updateState)
        fail = false
        vm.checkUpdate(); runCurrent()
        assertFalse((vm.state.value.updateState as UpdateState.Success).hasUpdate)
        assertFalse(vm.state.value.releaseDialogVisible)
    }

    @Test fun `release download controls retain pause resume background and cancel behavior`() = runTest {
        val vm = AppUpdateViewModel(ReleaseSource { release }, downloads, installer, toast)
        runCurrent()
        vm.downloadRelease()
        assertEquals(release.downloadUrl, downloads.request!!.downloadUrl)
        assertFalse(vm.state.value.releaseDialogVisible)
        assertTrue(vm.state.value.showDownloadDialog)
        vm.toggleDownloadPause()
        assertEquals(AppUpdateDownloadStatus.Paused, downloads.state.value.status)
        vm.toggleDownloadPause()
        assertEquals(AppUpdateDownloadStatus.Downloading, downloads.state.value.status)
        vm.backgroundDownload()
        assertTrue(downloads.state.value.background)
        assertFalse(vm.state.value.showDownloadDialog)
        vm.cancelDownload()
        assertTrue(downloads.canceled)
    }

    @Test fun `installation requires an available completed download and reports failure`() = runTest {
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { toast.message.collect { messages += it } }
        val vm = AppUpdateViewModel(ReleaseSource { release }, downloads, installer, toast)
        runCurrent()
        assertFalse(vm.isApkReady(downloads.state.value))
        downloads.state.value = AppUpdateDownloadState(status = AppUpdateDownloadStatus.Completed, savedPath = "/tmp/app.apk")
        assertTrue(vm.isApkReady(downloads.state.value))
        vm.installDownload()
        assertEquals("/tmp/app.apk", installer.installed)
        installer.available = false
        assertFalse(vm.isApkReady(downloads.state.value))
        vm.installDownload(); runCurrent()
        assertEquals(listOf("打开安装器失败：file missing"), messages)
    }

    @Test fun `leaving screen cancels check without publishing a false error`() = runTest {
        val response = CompletableDeferred<GithubRelease>()
        val vm = AppUpdateViewModel(ReleaseSource { response.await() }, downloads, installer, toast)
        val store = ViewModelStore().apply { put("update", vm) }
        runCurrent()
        store.clear()
        response.complete(release); runCurrent()
        assertEquals(UpdateState.Checking, vm.state.value.updateState)
        assertFalse(vm.state.value.releaseDialogVisible)
    }
}
