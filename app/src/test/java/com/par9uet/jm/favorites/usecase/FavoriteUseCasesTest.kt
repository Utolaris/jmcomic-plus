package com.par9uet.jm.favorites.usecase

import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.favorites.data.FavoriteLocalMutation
import com.par9uet.jm.favorites.data.FavoriteRemoteMutation
import com.par9uet.jm.favorites.data.FavoriteSession
import com.par9uet.jm.favorites.data.FavoriteSessionSnapshot
import com.par9uet.jm.retrofit.model.NetWorkResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteUseCasesTest {
    private val session = TestFavoriteSession()
    private val sessionSnapshot = FavoriteSessionSnapshot(accountId = 42, generation = 0)

    @Test
    fun `uncollect removes local state only after remote success`() = runTest {
        val remote = RecordingRemoteMutation().apply {
            uncollectResults[2] = NetWorkResult.Error("remote failed")
        }
        val local = RecordingLocalMutation()

        val result = UncollectFavorites(remote, local, session)(sessionSnapshot, listOf(1, 2, 1))

        assertEquals(FavoritesBatchResult(succeeded = 1, failed = 1), result)
        assertEquals(listOf(1, 2), remote.uncollectedIds)
        assertEquals(listOf(1), local.removedIds)
    }

    @Test
    fun `moving selected favorites updates local membership only after each remote success`() = runTest {
        val remote = RecordingRemoteMutation().apply {
            moveResults[2] = NetWorkResult.Error("remote failed")
        }
        val local = RecordingLocalMutation()

        val result = MoveFavorites(remote, local, session)(sessionSnapshot, listOf(1, 2, 1), folderId = 7)

        assertEquals(FavoritesBatchResult(succeeded = 1, failed = 1), result)
        assertEquals(listOf(1, 2), remote.movedIds)
        assertEquals(listOf(1 to 7), local.movedIds)
    }

    @Test
    fun `folder mutations keep the local snapshot aligned with successful remote changes`() = runTest {
        val remote = RecordingRemoteMutation()
        val local = RecordingLocalMutation()

        assertEquals(
            NetWorkResult.Success(Unit),
            CreateFavoriteFolder(remote, session)(sessionSnapshot, "New"),
        )
        assertEquals(
            NetWorkResult.Success(Unit),
            DeleteFavoriteFolder(remote, local, session)(sessionSnapshot, 7),
        )
        assertEquals(
            NetWorkResult.Success(Unit),
            RenameFavoriteFolder(remote, local, session)(sessionSnapshot, 7, "Renamed"),
        )

        assertEquals(listOf(7), local.removedFolderIds)
        assertEquals(listOf(7 to "Renamed"), local.renamedFolders)
        assertEquals(listOf("New"), remote.createdFolderNames)
    }

    private class TestFavoriteSession : FavoriteSession {
        override val accountIdFlow: Flow<Int> = flowOf(42)

        override fun currentAccountId(): Int = 42

        override fun snapshot(): FavoriteSessionSnapshot = FavoriteSessionSnapshot(42, 0)

        override fun isCurrent(snapshot: FavoriteSessionSnapshot): Boolean =
            snapshot == FavoriteSessionSnapshot(42, 0)

        override suspend fun <T> withCurrentSession(
            snapshot: FavoriteSessionSnapshot,
            block: suspend () -> T,
        ): T? = if (isCurrent(snapshot)) block() else null
    }

    private class RecordingRemoteMutation : FavoriteRemoteMutation {
        val uncollectedIds = mutableListOf<Int>()
        val movedIds = mutableListOf<Int>()
        val createdFolderNames = mutableListOf<String>()
        val uncollectResults = mutableMapOf<Int, NetWorkResult<Unit>>()
        val moveResults = mutableMapOf<Int, NetWorkResult<Unit>>()

        override suspend fun collectComic(comicId: Int): NetWorkResult<Unit> = NetWorkResult.Success(Unit)

        override suspend fun uncollectComic(comicId: Int): NetWorkResult<Unit> {
            uncollectedIds += comicId
            return uncollectResults[comicId] ?: NetWorkResult.Success(Unit)
        }

        override suspend fun createFolder(name: String): NetWorkResult<Unit> {
            createdFolderNames += name
            return NetWorkResult.Success(Unit)
        }

        override suspend fun deleteFolder(folderId: Int): NetWorkResult<Unit> = NetWorkResult.Success(Unit)

        override suspend fun renameFolder(folderId: Int, name: String): NetWorkResult<Unit> =
            NetWorkResult.Success(Unit)

        override suspend fun moveComicToFolder(comicId: Int, folderId: Int): NetWorkResult<Unit> {
            movedIds += comicId
            return moveResults[comicId] ?: NetWorkResult.Success(Unit)
        }
    }

    private class RecordingLocalMutation : FavoriteLocalMutation {
        val removedIds = mutableListOf<Int>()
        val movedIds = mutableListOf<Pair<Int, Int>>()
        val removedFolderIds = mutableListOf<Int>()
        val renamedFolders = mutableListOf<Pair<Int, String>>()

        override suspend fun addFromComic(accountId: Int, comic: Comic, folderId: Int) = Unit

        override suspend fun remove(accountId: Int, albumIds: Collection<Int>) {
            removedIds += albumIds
        }

        override suspend fun moveToFolder(accountId: Int, albumId: Int, folderId: Int) {
            movedIds += albumId to folderId
        }

        override suspend fun cacheFolder(accountId: Int, folderId: Int, name: String) = Unit

        override suspend fun removeFolder(accountId: Int, folderId: Int) {
            removedFolderIds += folderId
        }

        override suspend fun renameFolder(accountId: Int, folderId: Int, name: String) {
            renamedFolders += folderId to name
        }
    }
}
