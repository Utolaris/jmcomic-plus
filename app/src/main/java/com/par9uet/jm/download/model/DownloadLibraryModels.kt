package com.par9uet.jm.download.model

/** UI-facing download status. Persistence values stay in `database.model.DownloadStatus`. */
enum class DownloadItemStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETE,
    ERROR,
}

data class DownloadItem(
    val id: Int,
    val name: String,
    val authorList: List<String>,
    val tagList: List<String>,
    val coverPath: String,
    // Mirrors the persisted zipPath: directory, legacy ZIP, or document URI.
    val zipPath: String,
    val progress: Float,
    val status: DownloadItemStatus,
    val createTime: Long,
    val groupId: Int,
    val groupName: String,
    val chapterName: String,
)

data class DownloadItemGroup(
    val id: Int,
    val name: String,
    val authorList: List<String>,
    val coverPath: String,
    val itemIds: Set<Int>,
    val chapterCount: Int,
    val latestTime: Long,
    val status: DownloadItemStatus,
    val progress: Float,
)
