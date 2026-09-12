package com.par9uet.jm.favorites.data

import android.os.SystemClock
import androidx.room.withTransaction
import com.par9uet.jm.database.AppDatabase
import com.par9uet.jm.database.dao.FavoriteComicDao
import com.par9uet.jm.database.dao.FavoriteFolderDao
import com.par9uet.jm.database.dao.FavoriteFolderMembershipDao
import com.par9uet.jm.database.dao.FavoriteMetadataDao
import com.par9uet.jm.database.dao.FavoriteMetadataTermDao
import com.par9uet.jm.database.dao.FavoriteSyncStateDao
import com.par9uet.jm.database.model.FavoriteFolderEntity
import com.par9uet.jm.database.model.FavoriteFolderMembershipEntity
import com.par9uet.jm.database.model.FavoriteSyncStateEntity
import com.par9uet.jm.utils.log

/** Snapshot reconciliation used by the complete Favorites synchronization molecule. */
internal class FavoriteLocalSyncStore(
    private val database: AppDatabase,
    private val comicDao: FavoriteComicDao,
    private val folderDao: FavoriteFolderDao,
    private val membershipDao: FavoriteFolderMembershipDao,
    private val metadataDao: FavoriteMetadataDao,
    private val termDao: FavoriteMetadataTermDao,
    private val syncStateDao: FavoriteSyncStateDao,
) : FavoriteLocalSync {
    override suspend fun reconcileLightweightSnapshot(
        accountId: Int,
        scopeFolderId: Int,
        remoteItems: List<FavoriteRemoteItem>,
        remoteFolders: Map<Int, String>,
        syncedAt: Long,
    ): FavoriteSyncDelta {
        val remoteById = remoteItems.distinctBy { it.albumId }.associateBy { it.albumId }
        val oldScopeOrder = membershipDao.getAlbumIds(accountId, scopeFolderId)
        val oldScopeIds = oldScopeOrder.toSet()
        val existing = if (remoteById.isEmpty()) {
            emptyMap()
        } else {
            comicDao.getByIds(accountId, remoteById.keys.toList()).associateBy { it.albumId }
        }
        val delta = planFavoriteSync(oldScopeIds, existing, remoteById.values.toList())
        val removedIds = oldScopeIds - remoteById.keys
        val remoteOrder = remoteById.keys.toList()
        val membershipChanged = oldScopeOrder != remoteOrder
        val remoteOrderById = remoteById.keys.withIndex().associate { it.value to it.index }
        // lastFavoriteOrder is the global/all-favorites order. Only an authoritative
        // all-favorites sync (folder 0) may rewrite it; a non-zero folder sync updates
        // membership.remoteOrder for that folder only.
        val updateGlobalOrder = scopeFolderId == FAVORITE_SCOPE_ALL

        val transactionStartedAt = SystemClock.elapsedRealtime()
        database.withTransaction {
            val existingMetadataById = if (remoteById.isEmpty()) {
                emptyMap()
            } else {
                metadataDao.getByIds(accountId, remoteById.keys.toList()).associateBy { it.albumId }
            }
            val incompleteMetadataIds = remoteById.values
                .filter { item ->
                    val local = existing[item.albumId]
                    local?.metadataComplete != true &&
                        (local == null || !local.matchesLightweight(item))
                }
                .map { it.albumId }
            val existingTermsByAlbum = if (incompleteMetadataIds.isEmpty()) {
                emptyMap()
            } else {
                termDao.getForAlbums(accountId, incompleteMetadataIds)
                    .groupBy { it.albumId }
            }

            if (membershipChanged) {
                if (scopeFolderId == FAVORITE_SCOPE_ALL && removedIds.isNotEmpty()) {
                    // The all-favorites snapshot is authoritative. Remove memberships in every
                    // folder only after all remote pages have completed successfully.
                    membershipDao.deleteForAlbums(accountId, removedIds.toList())
                } else {
                    membershipDao.deleteForScope(accountId, scopeFolderId)
                }
                val memberships = remoteById.values.mapIndexed { index, item ->
                    FavoriteFolderMembershipEntity(
                        accountId = accountId,
                        folderId = scopeFolderId,
                        albumId = item.albumId,
                        remoteOrder = index,
                        lastSyncedAt = syncedAt,
                    )
                }
                if (memberships.isNotEmpty()) membershipDao.upsertAll(memberships)
            }

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
                    val nextComic = item.toComicEntity(
                        accountId = accountId,
                        order = order,
                        syncedAt = syncedAt,
                        existing = local,
                        keepFullMetadata = keepFullMetadata,
                    )
                    // List metadata may differ from the retained detail metadata. Only the
                    // merged row, not that remote difference, can justify invalidating Paging.
                    if (local == null || !local.sameSnapshotContent(nextComic)) {
                        comicDao.upsert(nextComic)
                    }
                }
                if (!keepFullMetadata && lightweightChanged) {
                    val nextMetadata = item.toIncompleteMetadata(accountId, syncedAt)
                    val currentMetadata = existingMetadataById[item.albumId]
                    if (currentMetadata == null || !currentMetadata.sameSnapshotContent(nextMetadata)) {
                        metadataDao.upsert(nextMetadata)
                    }
                    val nextTerms = item.toTerms(accountId)
                    if (existingTermsByAlbum[item.albumId].orEmpty().toSet() != nextTerms.toSet()) {
                        termDao.replaceTerms(accountId, item.albumId, nextTerms)
                    }
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
        val desiredMemberships = buildList {
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
        val desiredComics = remoteById.values.mapIndexed { index, item ->
            val full = checkNotNull(metadataById[item.albumId])
            item.toComicEntity(
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
            )
        }
        val desiredMetadata = remoteById.values.map { item ->
            checkNotNull(metadataById[item.albumId]).toEntity(accountId, syncedAt)
        }
        val desiredTermsByAlbum = remoteById.values.associate { item ->
            item.albumId to checkNotNull(metadataById[item.albumId])
                .toTerms(accountId, item)
        }
        val transactionStartedAt = SystemClock.elapsedRealtime()
        database.withTransaction {
            val existingMemberships = membershipDao.getAll(accountId)
            val membershipChanged = existingMemberships.map { it.copy(lastSyncedAt = 0L) }.toSet() !=
                desiredMemberships.map { it.copy(lastSyncedAt = 0L) }.toSet()
            if (membershipChanged) {
                membershipDao.deleteAll(accountId)
                if (desiredMemberships.isNotEmpty()) membershipDao.upsertAll(desiredMemberships)
            }

            val existingComics = comicDao.getAll(accountId).associateBy { it.albumId }
            desiredComics.forEach { nextComic ->
                val currentComic = existingComics[nextComic.albumId]
                if (currentComic == null || !currentComic.sameSnapshotContent(nextComic)) {
                    comicDao.upsert(nextComic)
                }
            }

            val existingMetadata = metadataDao.getAll(accountId).associateBy { it.albumId }
            desiredMetadata.forEach { nextMetadata ->
                val currentMetadata = existingMetadata[nextMetadata.albumId]
                if (currentMetadata == null || !currentMetadata.sameSnapshotContent(nextMetadata)) {
                    metadataDao.upsert(nextMetadata)
                }
            }

            val existingTermsByAlbum = termDao.getAll(accountId)
                .groupBy { it.albumId }
                .mapValues { (_, terms) -> terms.toSet() }
            desiredTermsByAlbum.forEach { (albumId, nextTerms) ->
                if (existingTermsByAlbum[albumId].orEmpty() != nextTerms.toSet()) {
                    termDao.replaceTerms(accountId, albumId, nextTerms)
                }
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
            val nextComic = existing.copy(
                title = payload.title.ifBlank { existing.title },
                description = payload.description,
                authorList = payload.authors.normalized(),
                tagList = payload.tags.normalized().ifEmpty { existing.categoryTags() },
                roleList = payload.roles.normalized(),
                workList = payload.works.normalized(),
                metadataComplete = true,
                metadataUpdatedAt = syncedAt,
            )
            if (!existing.sameSnapshotContent(nextComic)) comicDao.upsert(nextComic)

            val nextMetadata = payload.toEntity(accountId, syncedAt)
            val existingMetadata = metadataDao.getByIds(accountId, listOf(payload.albumId))
                .firstOrNull()
            if (existingMetadata == null || !existingMetadata.sameSnapshotContent(nextMetadata)) {
                metadataDao.upsert(nextMetadata)
            }

            val nextTerms = payload.toTerms(accountId, existing.toRemoteItem())
            val existingTerms = termDao.getForAlbums(accountId, listOf(payload.albumId)).toSet()
            if (existingTerms != nextTerms.toSet()) {
                termDao.replaceTerms(accountId, payload.albumId, nextTerms)
            }
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
        val existingById = folderDao.getAll(accountId).associateBy { it.folderId }
        val changed = normalized.mapNotNull { (id, name) ->
            val next = FavoriteFolderEntity(accountId, id, name, syncedAt)
            next.takeUnless { existingById[id]?.sameSnapshotContent(next) == true }
        }
        if (changed.isNotEmpty()) folderDao.upsertAll(changed)
        val oldIds = existingById.keys
        val removed = if (removeMissing) oldIds - normalized.keys else emptySet()
        if (removed.isNotEmpty()) {
            folderDao.deleteByIds(accountId, removed.toList())
            removed.filter { it != FAVORITE_SCOPE_ALL }
                .forEach { membershipDao.deleteForScope(accountId, it) }
        }
    }
}
