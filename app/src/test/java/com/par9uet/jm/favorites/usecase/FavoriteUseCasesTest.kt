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
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteUseCasesTest {
    private val session = TestFavoriteSession()
    private val sessionSnapshot = FavoriteSessionSnapshot(accountId = 42, generation = 0)

    @Test
    fun `stale snapshot before remote execution never starts the bound batch`() = runTest {
        val remote = RecordingRemoteMutation()
        val local = RecordingLocalMutation()
        val staleSnapshot = FavoriteSessionSnapshot(accountId = 7, generation = 3)

        val result = MoveFavorites(remote, local, session)(staleSnapshot, listOf(1), folderId = 7)

        assertEquals(FavoritesBatchResult(succeeded = 0, failed = 1), result)
        assertTrue(remote.movedIds.isEmpty())
        assertTrue(session.boundBatchesStarted.isEmpty())
    }

    @Test
    fun `session change while remote is running stops the batch before the next remote call`() = runTest {
        val remote = RecordingRemoteMutation()
        val local = RecordingLocalMutation()
        var uncollectCalls = 0
        remote.uncollectHandler = { comicId ->
            uncollectCalls++
            if (uncollectCalls == 1) {
                // The captured account A snapshot loses validity while the first remote
                // mutation for A is still in flight; account B becomes active.
                session.switchAccount(43)
            }
            NetWorkResult.Success(Unit)
        }

        val result = UncollectFavorites(remote, local, session)(
            sessionSnapshot,
            listOf(1, 2, 3),
        )

        // The account switch happens inside the first remote call, so even that item cannot
        // commit locally (its snapshot is already stale) and nothing further touches B.
        assertEquals(FavoritesBatchResult(succeeded = 0, failed = 3), result)
        assertEquals(listOf(1), remote.uncollectedIds)
        assertTrue(local.removedIds.isEmpty())
    }

    @Test
    fun `remote success followed by stale session does not commit old-account state`() = runTest {
        val remote = RecordingRemoteMutation()
        val local = RecordingLocalMutation()
        remote.moveHandler = { _, _ ->
            session.switchAccount(43)
            NetWorkResult.Success(Unit)
        }

        val result = MoveFavorites(remote, local, session)(sessionSnapshot, listOf(11), folderId = 7)

        // Remote reported success but the account switched mid-flight: the local commit must be
        // rejected and the failure counted rather than written to the new account state.
        assertEquals(FavoritesBatchResult(succeeded = 0, failed = 1), result)
        assertTrue(local.movedIds.isEmpty())
    }

    @Test
    fun `folder mutations run inside the bound remote session`() = runTest {
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

        // Each folder operation opened exactly one bound batch against the captured account.
        assertEquals(3, session.boundBatchesStarted.size)
        assertTrue(session.boundBatchesStarted.all { it.first == 42 })

        // A snapshot from another account never reaches the remote mutation.
        val staleCreate = CreateFavoriteFolder(remote, session)(
            FavoriteSessionSnapshot(accountId = 43, generation = 9),
            "Never",
        )
        assertTrue(staleCreate is NetWorkResult.Error)
        assertEquals(listOf("New"), remote.createdFolderNames)
    }

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

    /** Mimics the UserManager contract: bound work starts only while the snapshot is live. */
    private class TestFavoriteSession(initialAccountId: Int = 42) : FavoriteSession {
        private var accountId = initialAccountId
        private var generation = 0L
        val boundBatchesStarted = mutableListOf<Pair<Int, Long>>()

        override val accountIdFlow: Flow<Int> get() = kotlinx.coroutines.flow.flowOf(accountId)

        override fun currentAccountId(): Int = accountId

        override fun snapshot(): FavoriteSessionSnapshot = FavoriteSessionSnapshot(accountId, generation)

        override fun isCurrent(snapshot: FavoriteSessionSnapshot): Boolean =
            snapshot == FavoriteSessionSnapshot(accountId, generation)

        override suspend fun <T> withCurrentSession(
            snapshot: FavoriteSessionSnapshot,
            block: suspend () -> T,
        ): T? = if (isCurrent(snapshot)) block() else null

        override suspend fun <T> withBoundRemoteSession(
            snapshot: FavoriteSessionSnapshot,
            block: suspend () -> T,
        ): T? {
            if (!isCurrent(snapshot)) return null
            boundBatchesStarted += snapshot.accountId to snapshot.generation
            return block()
        }

        fun switchAccount(newAccountId: Int) {
            accountId = newAccountId
            generation++
        }
    }

    private class RecordingRemoteMutation : FavoriteRemoteMutation {
        val uncollectedIds = mutableListOf<Int>()
        val movedIds = mutableListOf<Int>()
        val createdFolderNames = mutableListOf<String>()
        val uncollectResults = mutableMapOf<Int, NetWorkResult<Unit>>()
        val moveResults = mutableMapOf<Int, NetWorkResult<Unit>>()
        var uncollectHandler: (suspend (Int) -> NetWorkResult<Unit>)? = null
        var moveHandler: (suspend (Int, Int) -> NetWorkResult<Unit>)? = null

        override suspend fun collectComic(comicId: Int): NetWorkResult<Unit> = NetWorkResult.Success(Unit)

        override suspend fun uncollectComic(comicId: Int): NetWorkResult<Unit> {
            uncollectedIds += comicId
            return uncollectHandler?.invoke(comicId)
                ?: uncollectResults[comicId]
                ?: NetWorkResult.Success(Unit)
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
            return moveHandler?.invoke(comicId, folderId)
                ?: moveResults[comicId]
                ?: NetWorkResult.Success(Unit)
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
