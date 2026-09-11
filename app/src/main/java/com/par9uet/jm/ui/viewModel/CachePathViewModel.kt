package com.par9uet.jm.ui.viewModel

import android.content.Intent
import android.net.Uri
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.par9uet.jm.cache.getDownloadTreeUri
import com.par9uet.jm.cache.migration.CacheMigrationScheduler
import com.par9uet.jm.cache.migration.CacheMigrationWork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CachePathUiState(
    val treeUri: String = "",
    val active: Boolean = false,
    val progress: Int = 0,
    val message: String = "",
)

class CachePathViewModel(
    application: Application,
    private val scheduler: CacheMigrationScheduler,
) : AndroidViewModel(application) {
    private val context: Application get() = getApplication()
    private val workManager = WorkManager.getInstance(context)
    private val mutableState = MutableStateFlow(CachePathUiState(treeUri = getDownloadTreeUri(context)?.toString().orEmpty()))
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(CacheMigrationWork.UNIQUE_WORK_NAME).collect { works ->
                val work = works.firstOrNull { !it.state.isFinished } ?: works.firstOrNull()
                val active = work != null && !work.state.isFinished
                mutableState.value = CachePathUiState(
                    treeUri = getDownloadTreeUri(context)?.toString().orEmpty(),
                    active = active,
                    progress = work?.progress?.getInt(CacheMigrationWork.PROGRESS, 0) ?: 0,
                    message = when {
                        active -> work?.progress?.getString(CacheMigrationWork.STAGE) ?: "正在等待当前缓存任务结束"
                        work?.state == WorkInfo.State.FAILED -> work.outputData.getString(CacheMigrationWork.ERROR) ?: "缓存迁移失败"
                        work?.state == WorkInfo.State.SUCCEEDED -> "迁移完成"
                        else -> ""
                    },
                )
            }
        }
    }

    fun selectDirectory(uri: Uri?) {
        if (uri == null || state.value.active) return
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            startMigration(uri.toString())
        } catch (error: Exception) {
            mutableState.value = mutableState.value.copy(message = "无法获取目录读写授权：${error.message.orEmpty()}")
        }
    }

    fun useDefaultDirectory() = startMigration("")

    private fun startMigration(targetUri: String) {
        if (state.value.active || targetUri == state.value.treeUri) return
        mutableState.value = mutableState.value.copy(active = true, progress = 0, message = "正在准备缓存迁移")
        scheduler.enqueue(targetUri)
    }
}
