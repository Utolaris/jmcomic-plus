package com.par9uet.jm.store

import android.os.SystemClock
import androidx.paging.PagingSource
import androidx.room.withTransaction
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.favorites.data.FavoriteLocalQuery
import com.par9uet.jm.favorites.data.FavoriteLocalMutation
import com.par9uet.jm.favorites.data.FavoriteLocalSync
import com.par9uet.jm.data.models.TagFilterLogic
import com.par9uet.jm.database.AppDatabase
import com.par9uet.jm.database.dao.FavoriteComicDao
import com.par9uet.jm.database.dao.FavoriteFolderDao
import com.par9uet.jm.database.dao.FavoriteFolderMembershipDao
import com.par9uet.jm.database.dao.FavoriteMetadataDao
import com.par9uet.jm.database.dao.FavoriteMetadataTermDao
import com.par9uet.jm.database.dao.FavoriteSyncStateDao
import com.par9uet.jm.database.model.FavoriteComicEntity
import com.par9uet.jm.database.model.FavoriteFolderEntity
import com.par9uet.jm.database.model.FavoriteFolderMembershipEntity
import com.par9uet.jm.database.model.FavoriteMetadataTermEntity
import com.par9uet.jm.database.model.FavoriteSyncStateEntity
import com.par9uet.jm.utils.log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class FavoriteStore(
    private val database: AppDatabase,
    private val comicDao: FavoriteComicDao,
    private val folderDao: FavoriteFolderDao,
    private val membershipDao: FavoriteFolderMembershipDao,
    private val metadataDao: FavoriteMetadataDao,
    private val termDao: FavoriteMetadataTermDao,
    private val syncStateDao: FavoriteSyncStateDao,
) : FavoriteLocalQuery, FavoriteLocalMutation, FavoriteLocalSync {
    override fun pagingSource(
        accountId: Int,
        blockedTagList: List<String>,
        searchText: String,
        selectedTags: Set<String>,
        selectedAuthors: Set<String>,
        folderId: Int,
        tagLogic: TagFilterLogic,
    ): PagingSource<Int, FavoriteComicEntity> = comicDao.pagingSource(
        buildFavoritePagingQuery(
            accountId = accountId,
            blockedTagList = blockedTagList,
            searchText = searchText,
            selectedTags = selectedTags,
            selectedAuthors = selectedAuthors,
            folderId = folderId,
            tagLogic = tagLogic,
        )
    )

    override fun observeFolders(accountId: Int): Flow<Map<String, String>> =
        folderDao.observeAll(accountId).map { folders ->
            linkedMapOf<String, String>().apply {
                put("0", folders.firstOrNull { it.folderId == 0 }?.name ?: "全部")
                folders.filter { it.folderId != 0 }
                    .forEach { put(it.folderId.toString(), it.name) }
            }
        }

    override fun observeTagCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>> =
        termDao.observeCounts(accountId, folderId, FAVORITE_TERM_TAG).map { counts ->
            counts.associate { it.value to it.count }
        }

    override fun observeAuthorCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>> =
        termDao.observeCounts(accountId, folderId, FAVORITE_TERM_AUTHOR).map { counts ->
            counts.associate { it.value to it.count }
        }

    override suspend fun getCachedFolders(accountId: Int): Map<String, String> =
        folderDao.getAll(accountId).associate { it.folderId.toString() to it.name }
            .toMutableMap()
            .apply { putIfAbsent("0", "全部") }

    override suspend fun getComics(accountId: Int, albumIds: Collection<Int>): List<Comic> {
        val requestedIds = albumIds.distinct()
        if (accountId <= 0 || requestedIds.isEmpty()) return emptyList()
        val comicsById = comicDao.getByIds(accountId, requestedIds).associateBy { it.albumId }
        return requestedIds.mapNotNull { comicsById[it]?.toComic() }
    }

    override suspend fun reconcileLightweightSnapshot(
        accountId: Int,
        scopeFolderId: Int,
        remoteItems: List<FavoriteRemoteItem>,
        remoteFolders: Map<Int, String>,
        syncedAt: Long,
    ): FavoriteSyncDelta {
        val remoteById = remoteItems.distinctBy { it.albumId }.associateBy { it.albumId }
        val oldScopeIds = membershipDao.getAlbumIds(accountId, scopeFolderId).toSet()
        val existing = if (remoteById.isEmpty()) {
            emptyMap()
        } else {
            comicDao.getByIds(accountId, remoteById.keys.toList()).associateBy { it.albumId }
        }
        val delta = planFavoriteSync(oldScopeIds, existing, remoteById.values.toList())
        val removedIds = oldScopeIds - remoteById.keys
        val remoteOrderById = remoteById.keys.withIndex().associate { it.value to it.index }
        // lastFavoriteOrder is the global/all-favorites order. Only an authoritative
        // all-favorites sync (folder 0) may rewrite it; a non-zero folder sync updates
        // membership.remoteOrder for that folder only.
        val updateGlobalOrder = scopeFolderId == FAVORITE_SCOPE_ALL

        val transactionStartedAt = SystemClock.elapsedRealtime()
        database.withTransaction {
            if (scopeFolderId == FAVORITE_SCOPE_ALL && removedIds.isNotEmpty()) {
                // The all-favorites snapshot is authoritative. Remove memberships in every
                // folder only after all remote pages have completed successfully.
                membershipDao.deleteForAlbums(accountId, removedIds.toList())
            } else {
                membershipDao.deleteForScope(accountId, scopeFolderId)
            }
            membershipDao.upsertAll(
                remoteById.values.mapIndexed { index, item ->
                    FavoriteFolderMembershipEntity(
                        accountId = accountId,
                        folderId = scopeFolderId,
                        albumId = item.albumId,
                        remoteOrder = index,
                        lastSyncedAt = syncedAt,
                    )
                }
            )

            remoteById.values.forEach { item ->
                val local = existing[item.albumId]
                // Keep the last complete metadata while a refresh is in flight. If a normal
                // metadata request fails, the previous filter/index state remains usable and
                // the next sync will schedule the item again from the lightweight diff.
                val keepFullMetadata = local?.metadataComplete == true
                val order = resolveGlobalOrderAfterScopeSync(
                    scopeFolderId = scopeFolderId,
                    scopeIndex = remoteOrderById[item.albumId] ?: 0,
                    existingGlobalOrder = local?.lastFavoriteOrder ?: 0,
                )
                val lightweightChanged = local == null || !local.matchesLightweight(item)
                val orderChanged = updateGlobalOrder && local != null && local.lastFavoriteOrder != order
                val shouldUpdateComic = lightweightChanged || orderChanged
                if (shouldUpdateComic) {
                    comicDao.upsert(
                        item.toComicEntity(
                            accountId = accountId,
                            order = order,
                            syncedAt = syncedAt,
                            existing = local,
                            keepFullMetadata = keepFullMetadata,
                        )
                    )
                }
                if (!keepFullMetadata && lightweightChanged) {
                    metadataDao.upsert(item.toIncompleteMetadata(accountId, syncedAt))
                    replaceTerms(accountId, item.albumId, item.toTerms(accountId))
                }
            }
            updateFolders(
                accountId = accountId,
                remoteFolders = remoteFolders,
                syncedAt = syncedAt,
                removeMissing = scopeFolderId == FAVORITE_SCOPE_ALL,
            )
            comicDao.deleteOrphans(accountId)
            metadataDao.deleteOrphans(accountId)
            termDao.deleteOrphans(accountId)
        }
        log(
            "FavoritesStore",
            "reconcile account=$accountId folder=$scopeFolderId local=${existing.size} " +
                "remote=${remoteById.size} " +
                "transaction=${SystemClock.elapsedRealtime() - transactionStartedAt}ms",
        )

        return delta
    }

    override suspend fun replaceAllSnapshot(
        accountId: Int,
        remoteItems: List<FavoriteRemoteItem>,
        remoteFolders: Map<Int, String>,
        metadata: List<FavoriteMetadataPayload>,
        syncedAt: Long,
        forceRefreshedAt: Long,
        folderMemberships: Map<Int, List<Int>>,
    ) {
        val remoteById = remoteItems.distinctBy { it.albumId }.associateBy { it.albumId }
        val metadataById = metadata.associateBy { it.albumId }
        require(metadataById.keys.containsAll(remoteById.keys)) {
            "force refresh metadata is incomplete"
        }
        val transactionStartedAt = SystemClock.elapsedRealtime()
        database.withTransaction {
            membershipDao.deleteAll(accountId)
            membershipDao.upsertAll(
                buildList {
                    remoteById.values.forEachIndexed { index, item ->
                        add(
                            FavoriteFolderMembershipEntity(
                                accountId = accountId,
                                folderId = FAVORITE_SCOPE_ALL,
                                albumId = item.albumId,
                                remoteOrder = index,
                                lastSyncedAt = syncedAt,
                            )
                        )
                    }
                    folderMemberships.filterKeys { it > FAVORITE_SCOPE_ALL }
                        .forEach { (folderId, albumIds) ->
                            albumIds.distinct()
                                .filter { it in remoteById }
                                .forEachIndexed { index, albumId ->
                                    add(
                                        FavoriteFolderMembershipEntity(
                                            accountId = accountId,
                                            folderId = folderId,
                                            albumId = albumId,
                                            remoteOrder = index,
                                            lastSyncedAt = syncedAt,
                                        )
                                    )
                                }
                        }
                }
            )
            remoteById.values.forEachIndexed { index, item ->
                val full = checkNotNull(metadataById[item.albumId])
                comicDao.upsert(item.toComicEntity(
                    accountId = accountId,
                    order = index,
                    syncedAt = syncedAt,
                    existing = null,
                    keepFullMetadata = true,
                ).copy(
                    authorList = full.authors.normalized(),
                    tagList = full.tags.normalized().ifEmpty { item.categoryTags() },
                    roleList = full.roles.normalized(),
                    workList = full.works.normalized(),
                    title = full.title.ifBlank { item.title },
                    description = full.description,
                    metadataComplete = true,
                    metadataUpdatedAt = syncedAt,
                ))
                metadataDao.upsert(full.toEntity(accountId, syncedAt))
                replaceTerms(accountId, full.albumId, full.toTerms(accountId, item))
            }
            updateFolders(accountId, remoteFolders, syncedAt)
            comicDao.deleteOrphans(accountId)
            metadataDao.deleteOrphans(accountId)
            termDao.deleteOrphans(accountId)
            syncStateDao.upsert(
                FavoriteSyncStateEntity(
                    accountId = accountId,
                    scopeFolderId = FAVORITE_SCOPE_ALL,
                    lastSuccessfulSyncAt = syncedAt,
                    lastForceRefreshAt = forceRefreshedAt,
                    generation = syncedAt,
                )
            )
        }
        log(
            "FavoritesStore",
            "force replace account=$accountId remote=${remoteById.size} " +
                "transaction=${SystemClock.elapsedRealtime() - transactionStartedAt}ms",
        )
    }

    override suspend fun applyMetadata(accountId: Int, payload: FavoriteMetadataPayload, syncedAt: Long) {
        database.withTransaction {
            val existing = comicDao.getByIds(accountId, listOf(payload.albumId))
                .firstOrNull() ?: return@withTransaction
            comicDao.upsert(
                existing.copy(
                    title = payload.title.ifBlank { existing.title },
                    description = payload.description,
                    authorList = payload.authors.normalized(),
                    tagList = payload.tags.normalized().ifEmpty {
                        existing.categoryTags()
                    },
                    roleList = payload.roles.normalized(),
                    workList = payload.works.normalized(),
                    metadataComplete = true,
                    metadataUpdatedAt = syncedAt,
                )
            )
            metadataDao.upsert(payload.toEntity(accountId, syncedAt))
            replaceTerms(
                accountId,
                payload.albumId,
                payload.toTerms(accountId, existing.toRemoteItem()),
            )
        }
    }

    override suspend fun addFromComic(accountId: Int, comic: Comic, folderId: Int) {
        val now = System.currentTimeMillis()
        val item = comic.toRemoteItem()
        val metadata = FavoriteMetadataPayload(
            albumId = comic.id,
            title = comic.name,
            description = comic.description,
            authors = comic.authorList,
            tags = comic.tagList,
            roles = comic.roleList,
            works = comic.workList,
        )
        database.withTransaction {
            comicDao.upsert(item.toComicEntity(accountId, 0, now, null, keepFullMetadata = true).copy(
                metadataComplete = true,
                metadataUpdatedAt = now,
            ))
            metadataDao.upsert(metadata.toEntity(accountId, now))
            replaceTerms(accountId, comic.id, metadata.toTerms(accountId, item))
            membershipDao.upsertAll(
                buildList {
                    add(FavoriteFolderMembershipEntity(accountId, FAVORITE_SCOPE_ALL, comic.id, 0, now))
                    if (folderId != FAVORITE_SCOPE_ALL) {
                        add(FavoriteFolderMembershipEntity(accountId, folderId, comic.id, 0, now))
                    }
                }
            )
        }
    }

    override suspend fun remove(accountId: Int, albumIds: Collection<Int>) {
        if (albumIds.isEmpty()) return
        database.withTransaction {
            membershipDao.deleteForAlbums(accountId, albumIds.toList())
            comicDao.deleteByIds(accountId, albumIds.toList())
            metadataDao.deleteByIds(accountId, albumIds.toList())
            albumIds.forEach { termDao.deleteForAlbum(accountId, it) }
        }
    }

    override suspend fun moveToFolder(accountId: Int, albumId: Int, folderId: Int) {
        if (folderId == FAVORITE_SCOPE_ALL) return
        val now = System.currentTimeMillis()
        database.withTransaction {
            membershipDao.deleteNonDefaultForAlbum(accountId, albumId)
            membershipDao.upsertAll(
                listOf(
                    FavoriteFolderMembershipEntity(
                        accountId = accountId,
                        folderId = folderId,
                        albumId = albumId,
                        // Do not fake 'newest/order 0': append after MAX(remoteOrder) of the
                        // folder items we currently know until the next server sync confirms
                        // the real order. Computed inside the same transaction as the insert.
                        remoteOrder = nextTemporaryRemoteOrder(
                            membershipDao.maxRemoteOrder(accountId, folderId)
                        ),
                        lastSyncedAt = now,
                    )
                )
            )
        }
    }

    override suspend fun cacheFolder(accountId: Int, folderId: Int, name: String) {
        if (accountId <= 0 || folderId <= FAVORITE_SCOPE_ALL || name.isBlank()) return
        folderDao.upsertAll(
            listOf(FavoriteFolderEntity(accountId, folderId, name, System.currentTimeMillis()))
        )
    }

    override suspend fun renameFolder(accountId: Int, folderId: Int, name: String) {
        if (accountId <= 0 || folderId <= FAVORITE_SCOPE_ALL || name.isBlank()) return
        cacheFolder(accountId, folderId, name)
    }

    override suspend fun removeFolder(accountId: Int, folderId: Int) {
        if (accountId <= 0 || folderId <= FAVORITE_SCOPE_ALL) return
        database.withTransaction {
            membershipDao.deleteForScope(accountId, folderId)
            folderDao.deleteByIds(accountId, listOf(folderId))
        }
    }

    override suspend fun markSyncSuccess(accountId: Int, scopeFolderId: Int, syncedAt: Long) {
        val previous = syncStateDao.get(accountId, scopeFolderId)
        syncStateDao.upsert(
            FavoriteSyncStateEntity(
                accountId = accountId,
                scopeFolderId = scopeFolderId,
                lastSuccessfulSyncAt = syncedAt,
                lastForceRefreshAt = previous?.lastForceRefreshAt ?: 0L,
                generation = syncedAt,
            )
        )
    }

    private suspend fun updateFolders(
        accountId: Int,
        remoteFolders: Map<Int, String>,
        syncedAt: Long,
        removeMissing: Boolean = true,
    ) {
        if (remoteFolders.isEmpty()) return
        val normalized = remoteFolders.toMutableMap().apply { putIfAbsent(0, "全部") }
        val oldIds = folderDao.getAll(accountId).map { it.folderId }.toSet()
        folderDao.upsertAll(
            normalized.map { (id, name) ->
                FavoriteFolderEntity(accountId, id, name, syncedAt)
            }
        )
        val removed = if (removeMissing) oldIds - normalized.keys else emptySet()
        if (removed.isNotEmpty()) {
            folderDao.deleteByIds(accountId, removed.toList())
            removed.filter { it != FAVORITE_SCOPE_ALL }
                .forEach { membershipDao.deleteForScope(accountId, it) }
        }
    }

    private suspend fun replaceTerms(
        accountId: Int,
        albumId: Int,
        terms: List<FavoriteMetadataTermEntity>,
    ) {
        termDao.deleteForAlbum(accountId, albumId)
        if (terms.isNotEmpty()) termDao.upsertAll(terms)
    }
}
