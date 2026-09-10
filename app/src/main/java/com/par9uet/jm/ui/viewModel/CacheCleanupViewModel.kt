package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.cache.CacheArea
import com.par9uet.jm.cache.atom.CacheFiles
import com.par9uet.jm.cache.CacheSize
import com.par9uet.jm.utils.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CacheCleanupState(
    val loading: Boolean = true,
    val cleaning: Boolean = false,
    val items: List<CacheSize> = emptyList(),
    val selected: Set<CacheArea> = emptySet(),
    val result: String? = null,
) {
    val effectiveSelection: Set<CacheArea> get() =
        if (CacheArea.ALL in selected) setOf(CacheArea.ALL) else selected
    val selectedBytes: Long get() = items.filter { it.area in effectiveSelection }.sumOf { it.sizeBytes }
}

class CacheCleanupViewModel(
    private val files: CacheFiles,
    private val clearReaderCache: suspend () -> Unit,
    private val clearDownloads: suspend (suspend () -> Unit) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(CacheCleanupState())
    val state = _state.asStateFlow()

    init { refresh() }

    fun select(area: CacheArea, selected: Boolean) {
        _state.update {
            if (it.cleaning) it else it.copy(selected = if (selected) it.selected + area else it.selected - area)
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            try {
                val items = files.scan()
                _state.update { it.copy(items = items, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, result = error.message ?: "读取缓存失败") }
            }
        }
    }

    fun clean() {
        val current = _state.value
        if (current.loading || current.cleaning || current.selected.isEmpty()) return
        val selection = current.effectiveSelection
        _state.update { it.copy(cleaning = true, result = null) }
        viewModelScope.launch {
            try {
                val before = files.scan().filter { it.area in selection }.sumOf { it.sizeBytes }
                suspend fun deleteFiles() {
                    if (CacheArea.ALL in selection || CacheArea.READER in selection) clearReaderCache()
                    files.remove(selection)
                }
                if (CacheArea.ALL in selection || CacheArea.DOWNLOAD in selection) {
                    clearDownloads { deleteFiles() }
                } else deleteFiles()
                val items = files.scan()
                _state.update {
                    it.copy(items = items, selected = emptySet(), result = "已清理 ${formatBytes(before)}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(result = error.message ?: "缓存清理失败，请重试") }
            } finally {
                _state.update { it.copy(cleaning = false) }
            }
        }
    }
}
