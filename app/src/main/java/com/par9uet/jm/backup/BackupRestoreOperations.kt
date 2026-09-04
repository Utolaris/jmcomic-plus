package com.par9uet.jm.backup

import android.content.Context
import androidx.core.net.toUri
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.store.BACKUP_PROTECTION_NONE
import com.par9uet.jm.store.BackupContentOptions
import com.par9uet.jm.store.BackupFile
import com.par9uet.jm.store.BackupManager
import com.par9uet.jm.store.ComicCacheBackup
import com.par9uet.jm.store.ComicGroupBackup
import com.par9uet.jm.store.DownloadManager
import com.par9uet.jm.store.LocalSettingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class BackupDraft(
    val options: BackupContentOptions = BackupContentOptions(),
    val protectionType: String = BACKUP_PROTECTION_NONE,
    val password: String? = null,
    val pattern: String? = null,
    val comicCache: ComicCacheBackup? = null,
)

internal interface BackupRestoreOperations {
    suspend fun loadComicCache(): ComicCacheBackup
    suspend fun write(uri: String, draft: BackupDraft)
    suspend fun read(uri: String): BackupFile
    suspend fun restore(backup: BackupFile, includeSettings: Boolean, groups: List<ComicGroupBackup>): String
}

/** Combines backup serialization with device settings, documents and download scheduling. */
internal class DeviceBackupRestoreOperations(
    private val context: Context,
    private val settings: LocalSettingManager,
    private val downloadDao: DownloadComicDao,
    private val downloads: DownloadManager,
    private val codec: BackupManager,
) : BackupRestoreOperations {
    override suspend fun loadComicCache(): ComicCacheBackup = withContext(Dispatchers.IO) {
        codec.buildComicCacheBackup(downloadDao.getAll())
    }

    override suspend fun write(uri: String, draft: BackupDraft) = withContext(Dispatchers.IO) {
        val json = codec.createBackup(
            localSetting = if (draft.options.includeLocalSetting) settings.currentLocalSettingSnapshot() else null,
            comicCache = if (draft.options.includeComicCache) draft.comicCache else null,
            options = draft.options,
            protectionType = draft.protectionType,
            password = draft.password,
            pattern = draft.pattern,
        )
        check(codec.writeToUri(context, uri.toUri(), json)) { "写入备份文件失败" }
    }

    override suspend fun read(uri: String): BackupFile = withContext(Dispatchers.IO) {
        val json = codec.readFromUri(context, uri.toUri()) ?: error("无法读取备份文件")
        codec.parseBackup(json).getOrThrow()
    }

    override suspend fun restore(
        backup: BackupFile,
        includeSettings: Boolean,
        groups: List<ComicGroupBackup>,
    ): String {
        val restored = mutableListOf<String>()
        if (includeSettings) {
            codec.extractLocalSetting(backup)?.let {
                settings.applyLocalSetting(it)
                restored += "本地设置"
            }
        }
        groups.forEach { group ->
            val comic = Comic.create(id = group.id, name = group.name, authorList = group.authors)
            val chapters = group.chapters.sortedBy { it.sortOrder }.map { ComicChapter(id = it.id, name = it.name) }
            if (chapters.size == 1 && chapters.first().name.isBlank()) downloads.downloadComic(comic)
            else downloads.downloadChapters(comic, chapters)
        }
        if (groups.isNotEmpty()) {
            restored += "${groups.size} 部漫画的缓存任务（共 ${groups.sumOf { it.chapterCount }} 章）"
        }
        return if (restored.isEmpty()) "未找到可恢复的内容" else "已恢复：${restored.joinToString("、")}"
    }
}
