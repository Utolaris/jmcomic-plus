package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.download.molecule.DownloadLibraryQueries
import com.par9uet.jm.download.model.DownloadItem
import com.par9uet.jm.download.model.DownloadItemGroup
import com.par9uet.jm.download.coordinator.DownloadManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class DownloadEditState(
    val editing: Boolean = false,
    val selectedIds: Set<Int> = emptySet()
)

class DownloadViewModel(
    private val queries: DownloadLibraryQueries,
    private val downloadManager: DownloadManager,
) : ViewModel() {
    private val _editState = MutableStateFlow(DownloadEditState())
    val editState = _editState.asStateFlow()

    private val completeList = queries.observeCompleteList()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val activeList = queries.observeActiveList()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val errorList = queries.observeErrorList()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val completeGroups = completeList
        .map { items -> withCoverResolution(DownloadLibraryQueries.groupItems(items), items) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeGroups = combine(activeList, completeList) { activeItems, completeItems ->
        withCoverResolution(
            DownloadLibraryQueries.groupActiveDownloads(activeItems, completeItems),
            activeItems + completeItems,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val errorGroups = errorList
        .map { items -> withCoverResolution(DownloadLibraryQueries.groupItems(items), items) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun enterEdit(id: Int) {
        enterEdit(setOf(id))
    }

    fun enterEdit(ids: Set<Int>) {
        _editState.update {
            it.copy(editing = true, selectedIds = it.selectedIds + ids)
        }
    }

    fun toggleSelected(id: Int) {
        toggleSelected(setOf(id))
    }

    fun toggleSelected(ids: Set<Int>) {
        _editState.update {
            val allSelected = ids.all { id -> id in it.selectedIds }
            val selected = if (allSelected) {
                it.selectedIds - ids
            } else {
                it.selectedIds + ids
            }
            it.copy(editing = selected.isNotEmpty(), selectedIds = selected)
        }
    }

    fun setSelected(ids: Set<Int>) {
        _editState.update {
            it.copy(
                editing = ids.isNotEmpty(),
                selectedIds = ids
            )
        }
    }

    fun clearSelection() {
        _editState.update { DownloadEditState() }
    }

    fun deleteSelected() {
        val ids = _editState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            downloadManager.deleteDownloads(ids)
            clearSelection()
        }
    }

    fun deleteOne(id: Int) {
        deleteMany(setOf(id))
    }

    fun deleteMany(ids: Set<Int>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            downloadManager.deleteDownloads(ids)
            _editState.update {
                val selected = it.selectedIds - ids
                it.copy(editing = selected.isNotEmpty(), selectedIds = selected)
            }
        }
    }

    fun pauseSelected() {
        val ids = _editState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            downloadManager.pauseDownloads(ids)
            clearSelection()
        }
    }

    fun startSelected() {
        val ids = _editState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        clearSelection()
        downloadManager.resumeDownloads(ids)
    }

    fun redownloadSelected() {
        val ids = _editState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        downloadManager.redownloadDownloads(ids)
        clearSelection()
    }

    fun redownloadOne(groupId: Int) {
        downloadManager.redownloadGroup(groupId)
    }

    private fun withCoverResolution(
        groups: List<DownloadItemGroup>,
        sourceItems: List<DownloadItem>,
    ): List<DownloadItemGroup> {
        if (groups.isEmpty()) return groups
        val byGroupId = sourceItems.groupBy { item ->
            if (item.groupId != 0) item.groupId else item.id
        }
        return groups.map { group ->
            val items = byGroupId[group.id] ?: return@map group
            group.copy(coverPath = resolveGroupCoverPath(items, group.coverPath))
        }
    }
}

private fun resolveGroupCoverPath(items: List<DownloadItem>, fallbackCover: String): String {
    val directCover = items.firstNotNullOfOrNull { item ->
        item.coverPath.takeIf { it.isNotBlank() && File(it).exists() }
    }
    if (directCover != null) {
        return directCover
    }
    return items.firstNotNullOfOrNull { item ->
        val path = item.zipPath.takeIf { it.isNotBlank() } ?: return@firstNotNullOfOrNull null
        val file = File(path)
        val rootDir = when {
            file.isDirectory -> file.parentFile
            file.isFile -> file.parentFile
            else -> null
        }
        rootDir?.let { File(it, "cover.webp") }?.takeIf { it.exists() }?.absolutePath
    } ?: fallbackCover
}
