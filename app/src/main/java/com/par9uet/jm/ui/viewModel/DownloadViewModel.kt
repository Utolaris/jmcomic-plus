package com.par9uet.jm.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.store.DownloadManager
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

data class DownloadComicGroup(
    val id: Int,
    val name: String,
    val authorList: List<String>,
    val coverPath: String,
    val itemIds: Set<Int>,
    val chapterCount: Int,
    val latestTime: Long,
    val status: DownloadStatus,
    val progress: Float,
)

class DownloadViewModel(
    private val downloadComicDao: DownloadComicDao,
    private val downloadManager: DownloadManager
) : ViewModel() {
    private val _editState = MutableStateFlow(DownloadEditState())
    val editState = _editState.asStateFlow()

    private val completeList = downloadComicDao.observeCompleteList()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val activeList = downloadComicDao.observeActiveList()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val errorList = downloadComicDao.observeErrorList()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val completeGroups = completeList
        .map(::groupDownloads)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeGroups = combine(activeList, completeList) { activeItems, completeItems ->
        groupActiveDownloads(activeItems, completeItems)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val errorGroups = errorList
        .map(::groupDownloads)
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
            downloadComicDao.deleteByIds(ids)
            clearSelection()
        }
    }

    fun deleteOne(id: Int) {
        deleteMany(setOf(id))
    }

    fun deleteMany(ids: Set<Int>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            downloadComicDao.deleteByIds(ids.toList())
            _editState.update {
                val selected = it.selectedIds - ids
                it.copy(editing = selected.isNotEmpty(), selectedIds = selected)
            }
        }
    }

    fun pauseSelected() {
        updateSelectedStatus(DownloadStatus.PAUSED)
    }

    fun startSelected() {
        val ids = _editState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        clearSelection()
        downloadManager.resumeDownloads(ids)
    }

    private fun updateSelectedStatus(status: DownloadStatus) {
        val ids = _editState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            downloadComicDao.updateStatusByIds(ids, status)
            clearSelection()
        }
    }

    fun redownloadSelected() {
        val ids = _editState.value.selectedIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val groupIds = mutableSetOf<Int>()
            ids.forEach { id ->
                val item = downloadComicDao.getById(id)
                if (item != null) {
                    groupIds.add(if (item.groupId != 0) item.groupId else item.id)
                }
            }
            groupIds.forEach { groupId ->
                downloadManager.redownloadGroup(groupId)
            }
            clearSelection()
        }
    }

    fun redownloadOne(groupId: Int) {
        downloadManager.redownloadGroup(groupId)
    }

}

private fun groupDownloads(items: List<DownloadComic>): List<DownloadComicGroup> {
    return items
        .groupBy(::downloadGroupId)
        .values
        .map { groupItems ->
            val sortedItems = groupItems.sortedBy { it.createTime }
            val displayItem = sortedItems.firstOrNull { it.coverPath.isNotBlank() } ?: sortedItems.first()
            DownloadComicGroup(
                id = if (displayItem.groupId != 0) displayItem.groupId else displayItem.id,
                name = displayItem.groupName.ifBlank { displayItem.name },
                authorList = displayItem.authorList,
                coverPath = resolveGroupCoverPath(sortedItems, displayItem),
                itemIds = sortedItems.map { it.id }.toSet(),
                chapterCount = sortedItems.size,
                latestTime = sortedItems.maxOf { it.createTime },
                status = resolveGroupStatus(sortedItems),
                progress = sortedItems.map { it.progress.coerceIn(0f, 1f) }.average().toFloat()
            )
        }
        .sortedByDescending { it.latestTime }
}

private fun groupActiveDownloads(
    activeItems: List<DownloadComic>,
    completeItems: List<DownloadComic>
): List<DownloadComicGroup> {
    val activeGroupIds = activeItems.map(::downloadGroupId).toSet()
    val relatedCompleteItems = completeItems.filter { item ->
        downloadGroupId(item) in activeGroupIds
    }
    return groupDownloads(activeItems + relatedCompleteItems)
}

private fun downloadGroupId(item: DownloadComic): Int {
    return if (item.groupId != 0) item.groupId else item.id
}

private fun resolveGroupCoverPath(items: List<DownloadComic>, displayItem: DownloadComic): String {
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
    } ?: displayItem.coverPath
}

private fun resolveGroupStatus(items: List<DownloadComic>): DownloadStatus {
    return when {
        items.any { it.status == DownloadStatus.DOWNLOADING } -> DownloadStatus.DOWNLOADING
        items.any { it.status == DownloadStatus.PENDING } -> DownloadStatus.PENDING
        items.any { it.status == DownloadStatus.PAUSED } -> DownloadStatus.PAUSED
        items.any { it.status == DownloadStatus.ERROR } -> DownloadStatus.ERROR
        else -> items.firstOrNull()?.status ?: DownloadStatus.PENDING
    }
}
