package com.par9uet.jm.favorites.usecase

import com.par9uet.jm.favorites.TestFavoriteSession
import com.par9uet.jm.favorites.data.FavoriteLocalSync
import com.par9uet.jm.favorites.data.FavoriteRemotePage
import com.par9uet.jm.favorites.data.FavoriteRemoteQuery
import com.par9uet.jm.retrofit.model.NetWorkResult
import com.par9uet.jm.store.FavoriteMetadataPayload
import com.par9uet.jm.store.FavoriteRemoteItem
import com.par9uet.jm.store.FavoriteSyncDelta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SyncFavoritesTest {
    private class LocalSnapshot : FavoriteLocalSync {
        val replacements = mutableListOf<Pair<Int, List<Int>>>()
        var markedSuccessful = false
        override suspend fun replaceAllSnapshot(
            accountId: Int, remoteItems: List<FavoriteRemoteItem>, remoteFolders: Map<Int, String>,
            metadata: List<FavoriteMetadataPayload>, syncedAt: Long, forceRefreshedAt: Long,
            folderMemberships: Map<Int, List<Int>>,
        ) { replacements += accountId to remoteItems.map { it.albumId } }
        override suspend fun reconcileLightweightSnapshot(
            accountId: Int, scopeFolderId: Int, remoteItems: List<FavoriteRemoteItem>,
            remoteFolders: Map<Int, String>, syncedAt: Long,
        ) = FavoriteSyncDelta(0, 0, 0, remoteItems.size, remoteItems.map { it.albumId })
        override suspend fun applyMetadata(accountId: Int, payload: FavoriteMetadataPayload, syncedAt: Long) = Unit
        override suspend fun markSyncSuccess(accountId: Int, scopeFolderId: Int, syncedAt: Long) { markedSuccessful = true }
    }

    private class Remote : FavoriteRemoteQuery {
        var pageHandler: suspend (Int, Int) -> FavoriteRemotePage = { _, _ -> page() }
        var metadataHandler: suspend (Int) -> FavoriteMetadataPayload = { id ->
            FavoriteMetadataPayload(id, "item", "", emptyList(), emptyList(), emptyList(), emptyList())
        }
        override suspend fun getFavorites(folderId: Int, page: Int) = pageHandler(folderId, page)
        override suspend fun getMetadata(albumId: Int) = metadataHandler(albumId)
    }

    @Test
    fun `account switch stops paging before requesting another accounts data`() = runTest {
        val session = TestFavoriteSession()
        val local = LocalSnapshot()
        val remote = Remote()
        var pages = 0
        remote.pageHandler = { _, _ ->
            pages++
            session.switchAccount(8)
            page(totalPages = 2)
        }
        val sync = SyncFavorites(remote, local, session) { 0L }
        val result = sync.synchronize(session.snapshot(), force = true)
        assertTrue(result is NetWorkResult.Error)
        assertEquals(1, pages)
        assertTrue(local.replacements.isEmpty())
    }

    @Test
    fun `A B A during a page cannot commit the old snapshot`() = runTest {
        val session = TestFavoriteSession()
        val local = LocalSnapshot()
        val remote = Remote()
        remote.pageHandler = { _, _ ->
            session.switchAccount(8)
            session.switchAccount(7)
            page()
        }
        val sync = SyncFavorites(remote, local, session) { 0L }
        val result = sync.synchronize(session.snapshot(), force = true)
        assertTrue(result is NetWorkResult.Error)
        assertTrue(local.replacements.isEmpty())
    }

    @Test
    fun `force refresh commits only a complete snapshot and always reads all folders`() = runTest {
        val session = TestFavoriteSession()
        val local = LocalSnapshot()
        val remote = Remote()
        val requestedFolders = mutableListOf<Int>()
        remote.pageHandler = { folder, _ -> requestedFolders += folder; page() }
        val sync = SyncFavorites(remote, local, session) { 0L }
        assertTrue(sync.synchronize(session.snapshot(), folderId = 9, force = true) is NetWorkResult.Success)
        assertEquals(listOf(0), requestedFolders)
        assertEquals(listOf(7 to listOf(11)), local.replacements)

        remote.metadataHandler = { error("offline") }
        assertTrue(sync.synchronize(session.snapshot(), force = true) is NetWorkResult.Error)
        assertEquals(1, local.replacements.size)
    }

    @Test
    fun `metadata cancellation propagates without marking sync successful`() = runTest {
        val session = TestFavoriteSession()
        val local = LocalSnapshot()
        val remote = Remote().apply { metadataHandler = { throw CancellationException("cancelled") } }
        val sync = SyncFavorites(remote, local, session) { 0L }
        try {
            sync.synchronize(session.snapshot())
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertFalse(local.markedSuccessful)
        }
    }

    @Test
    fun `A B A during metadata cannot replace the account snapshot`() = runTest {
        val session = TestFavoriteSession()
        val local = LocalSnapshot()
        val remote = Remote().apply {
            metadataHandler = { id ->
                session.switchAccount(8)
                session.switchAccount(7)
                FavoriteMetadataPayload(id, "item", "", emptyList(), emptyList(), emptyList(), emptyList())
            }
        }
        val sync = SyncFavorites(remote, local, session) { 0L }
        assertTrue(sync.synchronize(session.snapshot(), force = true) is NetWorkResult.Error)
        assertTrue(local.replacements.isEmpty())
    }

    companion object {
        private fun page(totalPages: Int = 1) = FavoriteRemotePage(
            items = listOf(FavoriteRemoteItem(11, "item")),
            folders = mapOf(0 to "全部"), totalItems = 1, totalPages = totalPages,
        )
    }
}
