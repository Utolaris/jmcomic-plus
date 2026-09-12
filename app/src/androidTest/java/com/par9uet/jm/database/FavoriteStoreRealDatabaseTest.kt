package com.par9uet.jm.database

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.database.model.FavoriteComicEntity
import com.par9uet.jm.favorites.data.FavoriteMetadataPayload
import com.par9uet.jm.favorites.data.FavoriteRemoteItem
import com.par9uet.jm.favorites.data.FavoriteStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises [FavoriteStore] on a **file backed** database instead of an in-memory one.
 *
 * The unit level suite uses Room's in-memory builder, which hides real SQLite behaviour:
 * file persistence across reopen, Unicode LIKE matching and index usage. These cases are the
 * ones that only fail on a device.
 */
@RunWith(AndroidJUnit4::class)
class FavoriteStoreRealDatabaseTest {
    private lateinit var context: Context
    private lateinit var databaseFile: File
    private lateinit var database: AppDatabase
    private lateinit var store: FavoriteStore

    private val accountA = 1001
    private val accountB = 1002

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseFile = context.getDatabasePath("favorite-device-${System.nanoTime()}.db")
        databaseFile.parentFile?.mkdirs()
        open()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseFile.name)
    }

    private fun open() {
        database = Room.databaseBuilder(context, AppDatabase::class.java, databaseFile.name).build()
        store = FavoriteStore(
            database,
            database.favoriteComicDao(),
            database.favoriteFolderDao(),
            database.favoriteFolderMembershipDao(),
            database.favoriteMetadataDao(),
            database.favoriteMetadataTermDao(),
            database.favoriteSyncStateDao(),
        )
    }

    private suspend fun seed(
        accountId: Int,
        items: List<FavoriteRemoteItem>,
        folders: Map<Int, String> = mapOf(0 to "全部"),
        /** folderId -> albumIds；folderId 0（全部）会被实现丢弃，不需要也不应该传。 */
        memberships: Map<Int, List<Int>> = emptyMap(),
    ) {
        val metadata = items.map {
            FavoriteMetadataPayload(
                albumId = it.albumId,
                title = it.title,
                description = it.description,
                authors = it.authors,
                tags = it.tags,
                roles = emptyList(),
                works = emptyList(),
            )
        }
        store.replaceAllSnapshot(
            accountId = accountId,
            remoteItems = items,
            remoteFolders = folders,
            metadata = metadata,
            syncedAt = 1_000L,
            forceRefreshedAt = 1_000L,
            folderMemberships = memberships,
        )
    }

    private suspend fun load(
        accountId: Int,
        searchText: String = "",
        blockedTagList: List<String> = emptyList(),
        selectedTags: Set<String> = emptySet(),
        folderId: Int = 0,
        tagLogic: TagFilterLogic = TagFilterLogic.AND,
    ): List<FavoriteComicEntity> {
        val result = store.pagingSource(
            accountId = accountId,
            blockedTagList = blockedTagList,
            searchText = searchText,
            selectedTags = selectedTags,
            selectedAuthors = emptySet(),
            folderId = folderId,
            tagLogic = tagLogic,
        ).load(PagingSource.LoadParams.Refresh(null, 50, false))
        return (result as PagingSource.LoadResult.Page).data
    }

    @Test
    fun snapshotSurvivesClosingAndReopeningTheDatabaseFile() = runBlocking {
        seed(accountA, listOf(FavoriteRemoteItem(11, "持久化漫画")))

        database.close()
        open()

        assertEquals(listOf(11), load(accountA).map { it.albumId })
    }

    @Test
    fun chineseSearchMatchesTitlesOnRealSqlite() = runBlocking {
        seed(
            accountA,
            listOf(
                FavoriteRemoteItem(21, "進擊的巨人"),
                FavoriteRemoteItem(22, "測試漫畫"),
                FavoriteRemoteItem(23, "Unrelated title"),
            ),
        )

        assertEquals(listOf(21), load(accountA, searchText = "巨人").map { it.albumId })
        assertEquals(listOf(22), load(accountA, searchText = "測試").map { it.albumId })
        assertEquals(3, load(accountA).size)
        assertTrue("不存在的关键词必须返回空", load(accountA, searchText = "不存在的关键词").isEmpty())
    }

    @Test
    fun blockedTagsAreExcludedFromThePagingResult() = runBlocking {
        seed(
            accountA,
            listOf(
                FavoriteRemoteItem(31, "含屏蔽标签", tags = listOf("NTR")),
                FavoriteRemoteItem(32, "正常标签", tags = listOf("恋爱")),
            ),
        )

        assertEquals(setOf(31, 32), load(accountA).map { it.albumId }.toSet())
        assertEquals(listOf(32), load(accountA, blockedTagList = listOf("NTR")).map { it.albumId })
    }

    @Test
    fun twoAccountsAreIsolatedInsideOneDatabaseFile() = runBlocking {
        seed(accountA, listOf(FavoriteRemoteItem(41, "账号 A 的收藏")))
        seed(accountB, listOf(FavoriteRemoteItem(42, "账号 B 的收藏")))

        assertEquals(listOf(41), load(accountA).map { it.albumId })
        assertEquals(listOf(42), load(accountB).map { it.albumId })
    }

    @Test
    fun folderScopeLimitsTheResultToItsMembers() = runBlocking {
        seed(
            accountA,
            items = listOf(
                FavoriteRemoteItem(51, "先读"),
                FavoriteRemoteItem(52, "稍后"),
            ),
            folders = mapOf(0 to "全部", 2 to "先读清单"),
            memberships = mapOf(2 to listOf(51)),
        )

        assertEquals(setOf(51, 52), load(accountA, folderId = 0).map { it.albumId }.toSet())
        assertEquals(listOf(51), load(accountA, folderId = 2).map { it.albumId })
        assertTrue("没有成员的文件夹必须返回空", load(accountA, folderId = 3).isEmpty())
    }

    @Test
    fun folderMembershipOrderDrivesThePagingOrderNotTheRemoteOrder() = runBlocking {
        seed(
            accountA,
            items = listOf(
                FavoriteRemoteItem(61, "第一篇"),
                FavoriteRemoteItem(62, "第二篇"),
                FavoriteRemoteItem(63, "第三篇"),
            ),
            folders = mapOf(0 to "全部", 2 to "自定义顺序"),
            memberships = mapOf(2 to listOf(63, 61, 62)),
        )

        assertEquals(listOf(61, 62, 63), load(accountA, folderId = 0).map { it.albumId })
        assertEquals(
            "每个文件夹各自维护同步下来的成员顺序",
            listOf(63, 61, 62),
            load(accountA, folderId = 2).map { it.albumId },
        )
    }

    @Test
    fun membershipForTheAllFolderIsIgnoredInsteadOfDuplicated() = runBlocking {
        seed(
            accountA,
            items = listOf(FavoriteRemoteItem(71, "唯一一条")),
            memberships = mapOf(0 to listOf(71), 2 to listOf(71)),
        )

        assertEquals(listOf(71), load(accountA, folderId = 0).map { it.albumId })
        assertEquals(listOf(71), load(accountA, folderId = 2).map { it.albumId })
    }
}
