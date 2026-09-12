package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.download.export.DownloadCacheSummary
import com.par9uet.jm.download.export.DownloadExportOperations
import com.par9uet.jm.download.export.PdfExportMode
import com.par9uet.jm.download.model.DownloadItem
import com.par9uet.jm.store.ToastManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadExportState(
    val selectedChapterIds: Set<Int> = emptySet(),
    val exporting: Boolean = false,
    val summary: DownloadCacheSummary? = null,
)

class DownloadExportViewModel(
    private val operations: DownloadExportOperations,
    private val toastManager: ToastManager,
) : ViewModel() {
    private val _state = MutableStateFlow(DownloadExportState())
    val state = _state.asStateFlow()
    private data class Request(val chapters: List<DownloadItem>, val mode: PdfExportMode)
    private var pendingRequest: Request? = null
    private var inspectionJob: Job? = null
    private var inspectionGeneration = 0L

    fun selectChapters(ids: Set<Int>) {
        if (!_state.value.exporting) _state.update { it.copy(selectedChapterIds = ids.toSet()) }
    }

    /** Capture exactly what was confirmed before opening the system folder picker. */
    fun prepareExport(chapters: List<DownloadItem>, mode: PdfExportMode): Boolean {
        if (_state.value.exporting) return false
        val selected = chapters.filter { it.id in _state.value.selectedChapterIds }
        if (selected.isEmpty()) {
            toastManager.showAsync("未选择可导出的缓存章节")
            return false
        }
        pendingRequest = Request(selected.toList(), mode)
        return true
    }

    fun exportTo(uri: String?) {
        if (_state.value.exporting) return
        val request = pendingRequest ?: return
        pendingRequest = null
        if (uri == null) {
            toastManager.showAsync("未选择导出文件夹")
            return
        }
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            try {
                operations.export(request.chapters, uri, request.mode)
                toastManager.showAsync(if (request.mode == PdfExportMode.SplitByChapter) {
                    "已导出 ${request.chapters.size} 个章节 PDF"
                } else "PDF 导出成功")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                toastManager.showAsync(error.message ?: "PDF 导出失败")
            } finally {
                _state.update { it.copy(exporting = false) }
            }
        }
    }

    fun inspect(chapters: List<DownloadItem>, cachePath: String) {
        inspectionJob?.cancel()
        val generation = ++inspectionGeneration
        _state.update { it.copy(summary = null) }
        if (chapters.isEmpty()) return
        inspectionJob = viewModelScope.launch {
            try {
                val summary = operations.inspect(chapters, cachePath)
                if (generation == inspectionGeneration) _state.update { it.copy(summary = summary) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Files can disappear during cleanup; the chapter list remains usable.
            }
        }
    }
}
