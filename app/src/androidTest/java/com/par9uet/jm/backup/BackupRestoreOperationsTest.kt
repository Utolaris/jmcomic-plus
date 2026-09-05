package com.par9uet.jm.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.ComicChapter
import com.par9uet.jm.data.models.LocalSetting
import com.par9uet.jm.database.AppDatabase
import com.par9uet.jm.launcher.LauncherIdentityApplier
import com.par9uet.jm.data.models.LauncherDisguise
import com.par9uet.jm.store.BACKUP_PROTECTION_NONE
import com.par9uet.jm.store.BackupContentOptions
import com.par9uet.jm.store.BackupManager
import com.par9uet.jm.store.BackupTaskScheduler
import com.par9uet.jm.store.ChapterBackup
import com.par9uet.jm.store.ComicCacheBackup
import com.par9uet.jm.store.ComicGroupBackup
import com.par9uet.jm.store.LocalSettingManager
import com.par9uet.jm.storage.LocalSettingPersistence
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class BackupRestoreOperationsTest {
    private lateinit var database: AppDatabase
    private lateinit var operations: DeviceBackupRestoreOperations
    private val codec = BackupManager()
    private val scheduler = RecordingScheduler()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val persistence = object : LocalSettingPersistence {
            var value: LocalSetting? = null
            override fun load(): LocalSetting? = value
            override fun persist(localSetting: LocalSetting) { value = localSetting }
        }
        val settings = LocalSettingManager(persistence, object : LauncherIdentityApplier {
            override fun apply(disguise: LauncherDisguise) = Unit
        })
        operations = DeviceBackupRestoreOperations(
            context = context,
            settings = settings,
            downloadDao = database.downloadComicDao(),
            downloads = scheduler,
            codec = codec,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `backup switches are serialized independently and empty selection is rejected`() {
        val settingOnly = codec.createBackup(
            localSetting = LocalSetting(),
            comicCache = null,
            options = BackupContentOptions(includeLocalSetting = true, includeComicCache = false),
        )
        val cacheOnly = codec.createBackup(
            localSetting = null,
            comicCache = ComicCacheBackup(),
            options = BackupContentOptions(includeLocalSetting = false, includeComicCache = true),
        )

        assertEquals(false, codec.parseBackup(settingOnly).getOrThrow().meta.includeComicCache)
        assertEquals(true, codec.parseBackup(cacheOnly).getOrThrow().meta.includeComicCache)
        assertThrows(IllegalArgumentException::class.java) {
            codec.createBackup(null, null, BackupContentOptions(false, false))
        }
    }

    @Test
    fun `restore sorts chapters and reports single and multi chapter summary`() = runBlocking {
        val single = ComicGroupBackup(
            id = 10,
            name = "单章",
            authors = listOf("作者"),
            tags = emptyList(),
            chapters = listOf(ChapterBackup(11, "", 20)),
        )
        val multi = ComicGroupBackup(
            id = 20,
            name = "多章",
            authors = emptyList(),
            tags = emptyList(),
            chapters = listOf(
                ChapterBackup(22, "第二章", 20),
                ChapterBackup(21, "第一章", 10),
            ),
        )
        val backup = codec.parseBackup(
            codec.createBackup(
                LocalSetting(), ComicCacheBackup(listOf(single, multi)),
                BackupContentOptions(includeLocalSetting = false, includeComicCache = true),
            )
        ).getOrThrow()

        val summary = operations.restore(backup, includeSettings = false, groups = listOf(single, multi))

        assertEquals("已恢复：2 部漫画的缓存任务（共 3 章）", summary)
        assertEquals(listOf(10), scheduler.comics.map { it.id })
        assertEquals(listOf(21, 22), scheduler.chapters.single().second.map { it.id })
    }

    @Test
    fun `file read and write failures surface actionable errors`() = runBlocking {
        assertEquals("无法读取备份文件", assertThrows(IllegalStateException::class.java) {
            runBlocking { operations.read("content://missing-backup") }
        }.message)
        assertEquals("写入备份文件失败", assertThrows(IllegalStateException::class.java) {
            runBlocking {
                operations.write(
                    "content://missing-backup",
                    BackupDraft(options = BackupContentOptions(includeLocalSetting = true)),
                )
            }
        }.message)
    }

    private class RecordingScheduler : BackupTaskScheduler {
        val comics = mutableListOf<Comic>()
        val chapters = mutableListOf<Pair<Comic, List<ComicChapter>>>()
        override fun downloadComic(comic: Comic) { comics += comic }
        override fun downloadChapters(parentComic: Comic, chapters: List<ComicChapter>) {
            this.chapters += parentComic to chapters
        }
    }
}
