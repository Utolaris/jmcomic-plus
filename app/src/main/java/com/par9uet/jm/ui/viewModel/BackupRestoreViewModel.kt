package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.backup.BackupDraft
import com.par9uet.jm.backup.BackupRestoreOperations
import com.par9uet.jm.store.BACKUP_PROTECTION_BOTH
import com.par9uet.jm.store.BACKUP_PROTECTION_NONE
import com.par9uet.jm.store.BACKUP_PROTECTION_PASSWORD
import com.par9uet.jm.store.BACKUP_PROTECTION_PATTERN
import com.par9uet.jm.store.BackupContentOptions
import com.par9uet.jm.store.BackupFile
import com.par9uet.jm.store.BackupManager
import com.par9uet.jm.store.ComicGroupBackup
import com.par9uet.jm.store.ToastManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

internal enum class BackupStep { None, SelectContent, SelectProtection, SetPassword, SetPattern }
internal enum class RestoreStep { None, VerifyPassword, VerifyPattern, SelectContent, SelectComicCache }

internal data class BackupRestoreUiState(
    val backupStep: BackupStep = BackupStep.None,
    val contentOptions: BackupContentOptions = BackupContentOptions(),
    val createDocumentName: String? = null,
    val awaitingDocument: Boolean = false,
    val busy: Boolean = false,
    val restoreBackup: BackupFile? = null,
    val restoreStep: RestoreStep = RestoreStep.None,
    val restoreGroups: List<ComicGroupBackup> = emptyList(),
)

internal class BackupRestoreViewModel(
    private val operations: BackupRestoreOperations,
    private val codec: BackupManager,
    private val toastManager: ToastManager,
) : ViewModel() {
    private val _state = MutableStateFlow(BackupRestoreUiState())
    val state = _state.asStateFlow()
    private var draft = BackupDraft()
    private var restoreOptions = BackupContentOptions()
    private var operationJob: Job? = null
    private var operationId = 0L

    fun beginBackup() {
        if (_state.value.busy || _state.value.awaitingDocument) return
        reset()
        _state.update { it.copy(backupStep = BackupStep.SelectContent) }
    }

    fun changeContent(options: BackupContentOptions) {
        if (_state.value.busy) return
        draft = draft.copy(options = options)
        _state.update { it.copy(contentOptions = options) }
    }

    fun confirmContent() {
        if (draft.options.isEmpty) {
            toastManager.showAsync("请至少选择一项备份内容")
            return
        }
        runOperation("读取缓存列表失败") {
            if (draft.options.includeComicCache) {
                val cache = operations.loadComicCache()
                coroutineContext.ensureActive()
                if (cache.groups.isEmpty()) {
                    toastManager.showAsync("当前没有缓存记录，已自动取消勾选缓存目录")
                    draft = draft.copy(options = draft.options.copy(includeComicCache = false), comicCache = null)
                } else {
                    draft = draft.copy(comicCache = cache)
                }
            }
            _state.update {
                it.copy(
                    contentOptions = draft.options,
                    backupStep = if (draft.options.isEmpty) BackupStep.SelectContent else BackupStep.SelectProtection,
                )
            }
        }
    }

    fun selectProtection(type: String) {
        if (_state.value.backupStep != BackupStep.SelectProtection) return
        draft = draft.copy(protectionType = type)
        when (type) {
            BACKUP_PROTECTION_NONE -> requestCreateDocument()
            BACKUP_PROTECTION_PASSWORD, BACKUP_PROTECTION_BOTH ->
                _state.update { it.copy(backupStep = BackupStep.SetPassword) }
            BACKUP_PROTECTION_PATTERN -> _state.update { it.copy(backupStep = BackupStep.SetPattern) }
        }
    }

    fun setPassword(password: String) {
        if (_state.value.backupStep != BackupStep.SetPassword) return
        draft = draft.copy(password = password)
        if (draft.protectionType == BACKUP_PROTECTION_BOTH) {
            _state.update { it.copy(backupStep = BackupStep.SetPattern) }
        } else requestCreateDocument()
    }

    fun setPattern(pattern: String) {
        if (_state.value.backupStep != BackupStep.SetPattern) return
        draft = draft.copy(pattern = pattern)
        requestCreateDocument()
    }

    private fun requestCreateDocument() {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINESE).format(Date())
        _state.update {
            it.copy(backupStep = BackupStep.None, createDocumentName = "jm-mobile-backup-$timestamp.json", awaitingDocument = true)
        }
    }

    fun documentPickerLaunched() {
        _state.update { it.copy(createDocumentName = null) }
    }

    fun writeDocument(uri: String?) {
        if (uri == null) {
            reset()
            toastManager.showAsync("未选择保存位置")
            return
        }
        val selectedDraft = draft
        draft = BackupDraft()
        _state.update { it.copy(awaitingDocument = false, contentOptions = BackupContentOptions()) }
        runOperation("备份失败") {
            operations.write(uri, selectedDraft)
            coroutineContext.ensureActive()
            toastManager.showAsync("备份成功")
        }
    }

    fun beginRestore(): Boolean {
        if (_state.value.busy || _state.value.awaitingDocument) return false
        reset()
        _state.update { it.copy(awaitingDocument = true) }
        return true
    }

    fun readDocument(uri: String?) {
        _state.update { it.copy(awaitingDocument = false) }
        if (uri == null) {
            toastManager.showAsync("未选择备份文件")
            return
        }
        runOperation("恢复失败") {
            val backup = operations.read(uri)
            coroutineContext.ensureActive()
            _state.update {
                it.copy(
                    restoreBackup = backup,
                    restoreStep = when {
                        codec.needsPassword(backup) -> RestoreStep.VerifyPassword
                        codec.needsPattern(backup) -> RestoreStep.VerifyPattern
                        else -> RestoreStep.SelectContent
                    },
                )
            }
        }
    }

    fun verifyPassword(password: String): Boolean {
        val current = _state.value
        val backup = current.restoreBackup ?: return false
        if (current.restoreStep != RestoreStep.VerifyPassword || !codec.verifyPassword(backup, password)) return false
        _state.update {
            it.copy(restoreStep = if (codec.needsPattern(backup)) RestoreStep.VerifyPattern else RestoreStep.SelectContent)
        }
        return true
    }

    fun verifyPattern(pattern: String): Boolean {
        val current = _state.value
        val backup = current.restoreBackup ?: return false
        if (current.restoreStep != RestoreStep.VerifyPattern || !codec.verifyPattern(backup, pattern)) return false
        _state.update { it.copy(restoreStep = RestoreStep.SelectContent) }
        return true
    }

    fun selectRestoreContent(options: BackupContentOptions) {
        val current = _state.value
        val backup = current.restoreBackup ?: return
        if (current.restoreStep != RestoreStep.SelectContent) return
        restoreOptions = options
        if (options.includeComicCache && backup.meta.includeComicCache) {
            _state.update { it.copy(restoreStep = RestoreStep.SelectComicCache, restoreGroups = codec.extractComicCache(backup).groups) }
        } else restoreSelected(emptyList())
    }

    fun restoreSelected(groups: List<ComicGroupBackup>) {
        val current = _state.value
        val backup = current.restoreBackup ?: return
        if (current.restoreStep != RestoreStep.SelectContent && current.restoreStep != RestoreStep.SelectComicCache) return
        val includeSettings = restoreOptions.includeLocalSetting
        runOperation("恢复失败") {
            val message = operations.restore(backup, includeSettings, groups)
            coroutineContext.ensureActive()
            toastManager.showAsync(message)
            _state.update { it.copy(restoreBackup = null, restoreStep = RestoreStep.None, restoreGroups = emptyList()) }
        }
    }

    fun skipComicCache() = restoreSelected(emptyList())

    fun cancelRestore() {
        reset()
        toastManager.showAsync("已取消恢复")
    }

    fun cancelBackup() = reset()

    private fun reset() {
        operationId++
        operationJob?.cancel()
        draft = BackupDraft()
        restoreOptions = BackupContentOptions()
        _state.value = BackupRestoreUiState()
    }

    private fun runOperation(errorPrefix: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        val id = ++operationId
        _state.update { it.copy(busy = true) }
        operationJob = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (id == operationId) toastManager.showAsync("$errorPrefix：${e.message ?: "未知错误"}")
            } finally {
                if (id == operationId) _state.update { it.copy(busy = false) }
            }
        }
    }
}
