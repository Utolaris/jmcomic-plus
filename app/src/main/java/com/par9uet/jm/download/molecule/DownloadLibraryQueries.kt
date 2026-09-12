package com.par9uet.jm.download.molecule

import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.DownloadStatus
import com.par9uet.jm.download.model.DownloadItem
import com.par9uet.jm.download.model.DownloadItemGroup
import com.par9uet.jm.download.model.DownloadItemStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal fun DownloadStatus.toDomain(): DownloadItemStatus = when (this) {
    DownloadStatus.PENDING -> DownloadItemStatus.PENDING
    DownloadStatus.DOWNLOADING -> DownloadItemStatus.DOWNLOADING
    DownloadStatus.PAUSED -> DownloadItemStatus.PAUSED
    DownloadStatus.COMPLETE -> DownloadItemStatus.COMPLETE
    DownloadStatus.ERROR -> DownloadItemStatus.ERROR
}

internal fun DownloadComic.toDomain(): DownloadItem = DownloadItem(
    id = id,
    name = name,
    authorList = authorList,
    tagList = tagList,
    coverPath = coverPath,
    zipPath = zipPath,
    progress = progress,
    status = status.toDomain(),
    createTime = createTime,
    groupId = groupId,
    groupName = groupName,
    chapterName = chapterName,
)

/** L3 query port for the download library UI. DAO and Room entities stay inside this molecule. */
class DownloadLibraryQueries(
    private val downloadComicDao: DownloadComicDao,
) {
    fun observeCompleteList(): Flow<List<DownloadItem>> =
        downloadComicDao.observeCompleteList().map { items -> items.map(DownloadComic::toDomain) }

    fun observeActiveList(): Flow<List<DownloadItem>> =
        downloadComicDao.observeActiveList().map { items -> items.map(DownloadComic::toDomain) }

    fun observeErrorList(): Flow<List<DownloadItem>> =
        downloadComicDao.observeErrorList().map { items -> items.map(DownloadComic::toDomain) }

    fun observeByGroupId(groupId: Int): Flow<List<DownloadItem>> =
        downloadComicDao.observeByGroupId(groupId).map { items -> items.map(DownloadComic::toDomain) }

    fun observeCompleteByGroupId(groupId: Int): Flow<List<DownloadItem>> =
        downloadComicDao.observeCompleteByGroupId(groupId).map { items -> items.map(DownloadComic::toDomain) }

    suspend fun getById(comicId: Int): DownloadItem? = downloadComicDao.getById(comicId)?.toDomain()

    companion object {
        fun groupItems(items: List<DownloadItem>): List<DownloadItemGroup> {
            return items
                .groupBy(::groupIdOf)
                .values
                .map { groupItems ->
                    val sortedItems = groupItems.sortedBy { it.createTime }
                    val displayItem = sortedItems.firstOrNull { it.coverPath.isNotBlank() } ?: sortedItems.first()
                    DownloadItemGroup(
                        id = groupIdOf(displayItem),
                        name = displayItem.groupName.ifBlank { displayItem.name },
                        authorList = displayItem.authorList,
                        coverPath = displayItem.coverPath,
                        itemIds = sortedItems.map { it.id }.toSet(),
                        chapterCount = sortedItems.size,
                        latestTime = sortedItems.maxOf { it.createTime },
                        status = resolveGroupStatus(sortedItems),
                        progress = sortedItems.map { it.progress.coerceIn(0f, 1f) }.average().toFloat(),
                    )
                }
                .sortedByDescending { it.latestTime }
        }

        fun groupActiveDownloads(
            activeItems: List<DownloadItem>,
            completeItems: List<DownloadItem>,
        ): List<DownloadItemGroup> {
            val activeGroupIds = activeItems.map(::groupIdOf).toSet()
            val relatedCompleteItems = completeItems.filter { item ->
                groupIdOf(item) in activeGroupIds
            }
            return groupItems(activeItems + relatedCompleteItems)
        }

        private fun groupIdOf(item: DownloadItem): Int =
            if (item.groupId != 0) item.groupId else item.id

        private fun resolveGroupStatus(items: List<DownloadItem>): DownloadItemStatus {
            return when {
                items.any { it.status == DownloadItemStatus.DOWNLOADING } -> DownloadItemStatus.DOWNLOADING
                items.any { it.status == DownloadItemStatus.PENDING } -> DownloadItemStatus.PENDING
                items.any { it.status == DownloadItemStatus.PAUSED } -> DownloadItemStatus.PAUSED
                items.any { it.status == DownloadItemStatus.ERROR } -> DownloadItemStatus.ERROR
                else -> items.firstOrNull()?.status ?: DownloadItemStatus.PENDING
            }
        }
    }
}
