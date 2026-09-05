package com.par9uet.jm.store

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.database.AppDatabase
import com.par9uet.jm.favorites.data.FavoriteRemotePage
import com.par9uet.jm.favorites.data.FavoriteRemoteQuery
import com.par9uet.jm.favorites.data.FavoriteSession
import com.par9uet.jm.favorites.data.FavoriteSessionSnapshot
import com.par9uet.jm.favorites.usecase.SyncFavorites
import com.par9uet.jm.retrofit.model.NetWorkResult
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavoriteStoreSyncTest {
    private lateinit var database: AppDatabase
    private lateinit var store: FavoriteStore
    private val accountId = 7
    private val folders = mapOf(0 to "全部", 2 to "收藏夹")
    private val item = FavoriteRemoteItem(11, "漫画")
    private val metadata = FavoriteMetadataPayload(
        albumId = 11,
        title = "漫画",
        description = "",
        authors = listOf("详情作者"),
        tags = listOf("详情标签"),
        roles = emptyList(),
        works = emptyList(),
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).build()
        store = FavoriteStore(
            database, database.favoriteComicDao(), database.favoriteFolderDao(),
            database.favoriteFolderMembershipDao(), database.favoriteMetadataDao(),
            database.favoriteMetadataTermDao(), database.favoriteSyncStateDao(),
        )
        // Record actual SQLite row mutations, including REPLACE of identical rows.
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE sync_test_writes (tableName TEXT NOT NULL)")
        observedTables.forEach { table ->
            listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                sql.execSQL(
                    "CREATE TRIGGER test_${table}_$operation AFTER $operation ON $table " +
                        "BEGIN INSERT INTO sync_test_writes VALUES ('$table'); END",
                )
            }
        }
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun identicalForceSnapshotOnlyUpdatesSyncTime() = runBlocking {
        seed()
        val before = database.favoriteComicDao().getAll(accountId)
        clearWrites()
        store.replaceAllSnapshot(accountId, listOf(item), folders, listOf(metadata), 200, 200,
            mapOf(2 to listOf(item.albumId)))
        assertEquals(emptySet<String>(), writtenTables())
        assertEquals(before, database.favoriteComicDao().getAll(accountId))
        assertEquals(200L, database.favoriteSyncStateDao().get(accountId, 0)?.lastSuccessfulSyncAt)
    }

    @Test
    fun repeatedLightweightSyncKeepsPagingSourceValidAndAdvancesSyncTime() = runBlocking {
        seed()
        val source = store.pagingSource(accountId, emptyList(), "", emptySet(), emptySet(), 0, TagFilterLogic.AND)
        assertTrue(source.load(PagingSource.LoadParams.Refresh(null, 20, true)) is PagingSource.LoadResult.Page)
        clearWrites()
        repeat(2) { synchronize(item, metadata) }
        assertEquals(emptySet<String>(), writtenTables())
        assertFalse(source.invalid)
        assertTrue(checkNotNull(database.favoriteSyncStateDao().get(accountId, 0)).lastSuccessfulSyncAt > 100)
    }

    @Test
    fun differingListAndDetailTagsDoNotRewriteAnUnchangedDisplayedComic() = runBlocking {
        val listItem = item.copy(authors = listOf("列表作者"), tags = listOf("列表标签"))
        seed(listItem)
        clearWrites()
        repeat(2) { synchronize(listItem, metadata) }
        assertEquals(emptySet<String>(), writtenTables())
    }

    @Test
    fun lightweightSyncDoesNotEraseADescriptionMissingFromTheListResponse() = runBlocking {
        val full = metadata.copy(description = "详情页简介")
        seed(full = full)
        clearWrites()
        repeat(2) { synchronize(item, full) }
        assertEquals("详情页简介", database.favoriteComicDao().getAll(accountId).single().description)
        assertEquals(emptySet<String>(), writtenTables())
    }

    @Test
    fun differingListAndDetailTextDoesNotAlternateDisplayedContentOnEverySync() = runBlocking {
        val listItem = item.copy(title = "列表短标题", description = "列表简介", authors = listOf("列表作者"))
        val full = metadata.copy(title = "详情完整标题", description = "详情完整简介")
        seed(listItem, full)
        clearWrites()
        repeat(2) { synchronize(listItem, full) }
        assertEquals(emptySet<String>(), writtenTables())
        val comic = database.favoriteComicDao().getAll(accountId).single()
        assertEquals(full.title, comic.title)
        assertEquals(full.description, comic.description)
    }

    @Test
    fun changedTitleAndDescriptionAreConfirmedByDetailFetch() = runBlocking {
        seed()
        clearWrites()
        val updated = item.copy(title = "新标题", description = "新简介")
        val full = metadata.copy(title = updated.title, description = updated.description)
        synchronize(updated, full)
        assertEquals(setOf("favorite_comics"), writtenTables())
        val comic = database.favoriteComicDao().getAll(accountId).single()
        assertEquals(updated.title, comic.title)
        assertEquals(updated.description, comic.description)
        clearWrites()
        synchronize(updated, full)
        assertEquals(emptySet<String>(), writtenTables())
    }

    @Test
    fun realFolderAndMetadataChangesStillReachObservedTables() = runBlocking {
        seed()
        clearWrites()
        store.reconcileLightweightSnapshot(accountId, 0, listOf(item), folders + (2 to "新名称"), 200)
        assertEquals(setOf("favorite_folders"), writtenTables())
        clearWrites()
        store.applyMetadata(accountId, metadata.copy(tags = listOf("新标签")), 300)
        assertEquals(setOf("favorite_comics", "favorite_metadata", "favorite_metadata_terms"), writtenTables())
        assertEquals(listOf("新标签"), database.favoriteComicDao().getAll(accountId).single().tagList)
    }

    private suspend fun seed(listItem: FavoriteRemoteItem = item, full: FavoriteMetadataPayload = metadata) {
        store.replaceAllSnapshot(accountId, listOf(listItem), folders, listOf(full), 100, 100,
            mapOf(2 to listOf(listItem.albumId)))
    }

    private suspend fun synchronize(listItem: FavoriteRemoteItem, full: FavoriteMetadataPayload) {
        val snapshot = FavoriteSessionSnapshot(accountId, 0)
        val session = object : FavoriteSession {
            override val accountIdFlow = flowOf(accountId)
            override val sessionFlow = flowOf(snapshot)
            override fun currentAccountId() = accountId
            override fun snapshot() = snapshot
            override fun isCurrent(snapshot: FavoriteSessionSnapshot) = snapshot == this.snapshot()
            override suspend fun <T> withCurrentSession(snapshot: FavoriteSessionSnapshot, block: suspend () -> T): T = block()
        }
        val remote = object : FavoriteRemoteQuery {
            override suspend fun getFavorites(folderId: Int, page: Int) = FavoriteRemotePage(
                listOf(listItem), folders, 1, 1,
            )
            override suspend fun getMetadata(albumId: Int) = full
        }
        assertTrue(SyncFavorites(remote, store, session).synchronize(snapshot) is NetWorkResult.Success)
    }

    private fun clearWrites() = database.openHelper.writableDatabase.execSQL("DELETE FROM sync_test_writes")

    private fun writtenTables(): Set<String> = database.openHelper.readableDatabase
        .query("SELECT DISTINCT tableName FROM sync_test_writes").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private val observedTables = listOf(
        "favorite_comics", "favorite_folders", "favorite_folder_memberships",
        "favorite_metadata", "favorite_metadata_terms",
    )
}
