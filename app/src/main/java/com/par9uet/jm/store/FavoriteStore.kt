package com.par9uet.jm.store

import android.os.SystemClock
import androidx.paging.PagingSource
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.CollectComicOrderFilter
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
import com.par9uet.jm.database.model.FavoriteMetadataEntity
import com.par9uet.jm.database.model.FavoriteMetadataTermEntity
import com.par9uet.jm.database.model.FavoriteSyncStateEntity
import com.par9uet.jm.utils.log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

const val FAVORITE_SCOPE_ALL = 0
const val FAVORITE_TERM_TAG = "tag"
const val FAVORITE_TERM_AUTHOR = "author"

data class FavoriteRemoteItem(
    val albumId: Int,
    val title: String,
    val authors: List<String> = emptyList(),
    val description: String = "",
    val image: String = "",
    val tags: List<String> = emptyList(),
    val categoryId: String? = null,
    val categoryTitle: String? = null,
    val subCategoryId: String? = null,
    val subCategoryTitle: String? = null,
)

data class FavoriteMetadataPayload(
    val albumId: Int,
    val title: String,
    val description: String,
    val authors: List<String>,
    val tags: List<String>,
    val roles: List<String>,
    val works: List<String>,
)

data class FavoriteSyncDelta(
    val added: Int,
    val removed: Int,
    val changed: Int,
    val unchanged: Int,
    val metadataIds: List<Int>,
)

class FavoriteStore(
    private val database: AppDatabase,
    private val comicDao: FavoriteComicDao,
    private val folderDao: FavoriteFolderDao,
    private val membershipDao: FavoriteFolderMembershipDao,
    private val metadataDao: FavoriteMetadataDao,
    private val termDao: FavoriteMetadataTermDao,
    private val syncStateDao: FavoriteSyncStateDao,
) {
    fun pagingSource(
        accountId: Int,
        order: CollectComicOrderFilter,
        blockedTagList: List<String>,
        searchText: String,
        selectedTags: Set<String>,
        selectedAuthors: Set<String>,
        folderId: Int,
        tagLogic: TagFilterLogic,
    ): PagingSource<Int, FavoriteComicEntity> = comicDao.pagingSource(
        buildFavoritePagingQuery(
            accountId = accountId,
            order = order,
            blockedTagList = blockedTagList,
            searchText = searchText,
            selectedTags = selectedTags,
            selectedAuthors = selectedAuthors,
            folderId = folderId,
            tagLogic = tagLogic,
        )
    )

    fun observeFolders(accountId: Int): Flow<Map<String, String>> =
        folderDao.observeAll(accountId).map { folders ->
            linkedMapOf<String, String>().apply {
                put("0", folders.firstOrNull { it.folderId == 0 }?.name ?: "全部")
                folders.filter { it.folderId != 0 }
                    .forEach { put(it.folderId.toString(), it.name) }
            }
        }

    fun observeTagCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>> =
        termDao.observeCounts(accountId, folderId, FAVORITE_TERM_TAG).map { counts ->
            counts.associate { it.value to it.count }
        }

    fun observeAuthorCounts(accountId: Int, folderId: Int): Flow<Map<String, Int>> =
        termDao.observeCounts(accountId, folderId, FAVORITE_TERM_AUTHOR).map { counts ->
            counts.associate { it.value to it.count }
        }

    suspend fun getCachedFolders(accountId: Int): Map<String, String> =
        folderDao.getAll(accountId).associate { it.folderId.toString() to it.name }
            .toMutableMap()
            .apply { putIfAbsent("0", "全部") }

    suspend fun reconcileLightweightSnapshot(
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

    suspend fun replaceAllSnapshot(
        accountId: Int,
        remoteItems: List<FavoriteRemoteItem>,
        remoteFolders: Map<Int, String>,
        metadata: List<FavoriteMetadataPayload>,
        syncedAt: Long,
        forceRefreshedAt: Long,
        folderMemberships: Map<Int, List<Int>> = emptyMap(),
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

    suspend fun applyMetadata(accountId: Int, payload: FavoriteMetadataPayload, syncedAt: Long) {
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

    suspend fun addFromComic(accountId: Int, comic: Comic, folderId: Int = FAVORITE_SCOPE_ALL) {
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

    suspend fun remove(accountId: Int, albumIds: List<Int>) {
        if (albumIds.isEmpty()) return
        database.withTransaction {
            membershipDao.deleteForAlbums(accountId, albumIds)
            comicDao.deleteByIds(accountId, albumIds)
            metadataDao.deleteByIds(accountId, albumIds)
            albumIds.forEach { termDao.deleteForAlbum(accountId, it) }
        }
    }

    suspend fun moveToFolder(accountId: Int, albumId: Int, folderId: Int) {
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

    suspend fun cacheFolder(accountId: Int, folderId: Int, name: String) {
        if (accountId <= 0 || folderId <= FAVORITE_SCOPE_ALL || name.isBlank()) return
        folderDao.upsertAll(
            listOf(FavoriteFolderEntity(accountId, folderId, name, System.currentTimeMillis()))
        )
    }

    suspend fun renameFolder(accountId: Int, folderId: Int, name: String) {
        if (accountId <= 0 || folderId <= FAVORITE_SCOPE_ALL || name.isBlank()) return
        cacheFolder(accountId, folderId, name)
    }

    suspend fun removeFolder(accountId: Int, folderId: Int) {
        if (accountId <= 0 || folderId <= FAVORITE_SCOPE_ALL) return
        database.withTransaction {
            membershipDao.deleteForScope(accountId, folderId)
            folderDao.deleteByIds(accountId, listOf(folderId))
        }
    }

    suspend fun markSyncSuccess(accountId: Int, scopeFolderId: Int, syncedAt: Long) {
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

internal fun planFavoriteSync(
    oldScopeIds: Set<Int>,
    existing: Map<Int, FavoriteComicEntity>,
    remoteItems: List<FavoriteRemoteItem>,
): FavoriteSyncDelta {
    val remoteById = remoteItems.distinctBy { it.albumId }.associateBy { it.albumId }
    val added = remoteById.keys.count { it !in existing }
    val changed = remoteById.values.count { item ->
        existing[item.albumId]?.let { !it.matchesLightweight(item) } == true
    }
    val removed = (oldScopeIds - remoteById.keys).size
    val metadataIds = remoteById.values.filter { item ->
        val local = existing[item.albumId]
        local == null || !local.metadataComplete || item.invalidatesMetadata(local)
    }.map { it.albumId }
    return FavoriteSyncDelta(
        added = added,
        removed = removed,
        changed = changed,
        unchanged = remoteById.size - added - changed,
        metadataIds = metadataIds,
    )
}

/**
 * The global all-favorites order after a scope sync: only folder 0 (the authoritative
 * all-favorites scope) may adopt the scope index; other folders must leave it untouched.
 */
internal fun resolveGlobalOrderAfterScopeSync(
    scopeFolderId: Int,
    scopeIndex: Int,
    existingGlobalOrder: Int,
): Int = if (scopeFolderId == FAVORITE_SCOPE_ALL) scopeIndex else existingGlobalOrder

/**
 * Temporary membership order for a locally moved comic: MAX(remoteOrder) + 1 of the folder's
 * known items, instead of jumping to the 'first/newest' position or relying on a dense count.
 * -1 (empty folder) yields 0; sparse orders like 0,1,5,8 yield 9.
 */
internal fun nextTemporaryRemoteOrder(maxRemoteOrder: Int): Int = maxRemoteOrder + 1

private fun buildFavoritePagingQuery(
    accountId: Int,
    order: CollectComicOrderFilter,
    blockedTagList: List<String>,
    searchText: String,
    selectedTags: Set<String>,
    selectedAuthors: Set<String>,
    folderId: Int,
    tagLogic: TagFilterLogic,
): SupportSQLiteQuery {
    val clauses = mutableListOf("c.accountId = ?")
    val args = mutableListOf<Any>(accountId)
    clauses += "m.folderId = ?"
    args += folderId

    val query = searchText.trim().lowercase()
    if (query.isNotBlank()) {
        clauses += "(LOWER(c.title) LIKE ? OR EXISTS (SELECT 1 FROM favorite_metadata_terms s WHERE s.accountId = c.accountId AND s.albumId = c.albumId AND s.normalizedValue LIKE ?))"
        val pattern = "%$query%"
        args += pattern
        args += pattern
    }

    blockedTagList.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().forEach { tag ->
        clauses += "NOT EXISTS (SELECT 1 FROM favorite_metadata_terms b WHERE b.accountId = c.accountId AND b.albumId = c.albumId AND b.termType = ? AND b.normalizedValue = ?)"
        args += FAVORITE_TERM_TAG
        args += tag
    }

    val normalizedTags = selectedTags.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
    when (tagLogic) {
        TagFilterLogic.AND -> normalizedTags.forEach { tag ->
            clauses += termExistsClause("t", FAVORITE_TERM_TAG)
            args += tag
        }
        TagFilterLogic.OR -> if (normalizedTags.isNotEmpty()) {
            clauses += normalizedTags.joinToString(" OR ", prefix = "(") { _ -> termExistsClause("t", FAVORITE_TERM_TAG) } + ")"
            normalizedTags.forEach { args += it }
        }
        TagFilterLogic.NOT -> normalizedTags.forEach { tag ->
            clauses += "NOT ${termExistsClause("t", FAVORITE_TERM_TAG)}"
            args += tag
        }
    }

    val normalizedAuthors = selectedAuthors.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
    if (normalizedAuthors.isNotEmpty()) {
        clauses += normalizedAuthors.joinToString(" OR ", prefix = "(") { _ -> termExistsClause("a", FAVORITE_TERM_AUTHOR) } + ")"
        normalizedAuthors.forEach { args += it }
    }

    // Scope-aware ordering: each folder list is ordered by its own membership.remoteOrder.
    // COLLECT_TIME and UPDATE_TIME intentionally resolve to the same stored order today; a
    // distinct UPDATE_TIME ordering needs extra synced metadata (known limitation).
    val orderBy = "m.remoteOrder ASC"
    return SimpleSQLiteQuery(
        "SELECT c.* FROM favorite_comics c " +
            "JOIN favorite_folder_memberships m ON m.accountId = c.accountId AND m.albumId = c.albumId " +
            "WHERE ${clauses.joinToString(" AND ")} ORDER BY $orderBy, c.albumId ASC",
        args.toTypedArray(),
    )
}

private fun termExistsClause(alias: String, type: String): String =
    "EXISTS (SELECT 1 FROM favorite_metadata_terms $alias WHERE $alias.accountId = c.accountId AND $alias.albumId = c.albumId AND $alias.termType = '$type' AND $alias.normalizedValue = ?)"

private fun FavoriteRemoteItem.toComicEntity(
    accountId: Int,
    order: Int,
    syncedAt: Long,
    existing: FavoriteComicEntity?,
    keepFullMetadata: Boolean,
): FavoriteComicEntity {
    val existingMetadata = existing?.takeIf { keepFullMetadata }
    val authors = existingMetadata?.authorList ?: authors.normalized()
    val tags = existingMetadata?.tagList ?: tags.normalized().ifEmpty { categoryTags() }
    val roles = existingMetadata?.roleList ?: emptyList()
    val works = existingMetadata?.workList ?: emptyList()
    return FavoriteComicEntity(
        accountId = accountId,
        albumId = albumId,
        title = title,
        authorList = authors,
        description = description,
        image = image,
        tagList = tags,
        roleList = roles,
        workList = works,
        categoryId = categoryId,
        categoryTitle = categoryTitle,
        subCategoryId = subCategoryId,
        subCategoryTitle = subCategoryTitle,
        metadataComplete = existingMetadata != null,
        metadataUpdatedAt = existingMetadata?.metadataUpdatedAt ?: 0L,
        lastFavoriteOrder = order,
        lastFavoriteSyncAt = syncedAt,
    )
}

private fun FavoriteRemoteItem.toIncompleteMetadata(
    accountId: Int,
    syncedAt: Long,
) = FavoriteMetadataEntity(
    accountId = accountId,
    albumId = albumId,
    tags = tags.normalized().ifEmpty {
        listOfNotNull(categoryTitle, subCategoryTitle).normalized()
    },
    authors = authors.normalized(),
    metadataComplete = false,
    metadataUpdatedAt = syncedAt,
)

private fun FavoriteRemoteItem.categoryTags(): List<String> =
    listOfNotNull(categoryTitle, subCategoryTitle).normalized()

private fun FavoriteComicEntity.categoryTags(): List<String> =
    listOfNotNull(categoryTitle, subCategoryTitle).normalized()

private fun FavoriteRemoteItem.toTerms(accountId: Int): List<FavoriteMetadataTermEntity> =
    buildTerms(
        accountId = accountId,
        albumId = albumId,
        tags = tags.normalized().ifEmpty {
            listOfNotNull(categoryTitle, subCategoryTitle)
        },
        authors = authors,
    )

private fun FavoriteMetadataPayload.toEntity(accountId: Int, syncedAt: Long) =
    FavoriteMetadataEntity(
        accountId = accountId,
        albumId = albumId,
        tags = tags.normalized(),
        authors = authors.normalized(),
        roles = roles.normalized(),
        works = works.normalized(),
        metadataComplete = true,
        metadataUpdatedAt = syncedAt,
    )

private fun FavoriteMetadataPayload.toTerms(
    accountId: Int,
    item: FavoriteRemoteItem,
): List<FavoriteMetadataTermEntity> = buildTerms(
    accountId = accountId,
    albumId = albumId,
    tags = tags.normalized().ifEmpty {
        listOfNotNull(item.categoryTitle, item.subCategoryTitle)
    },
    authors = authors,
)

private fun FavoriteComicEntity.toRemoteItem() = FavoriteRemoteItem(
    albumId = albumId,
    title = title,
    authors = authorList,
    description = description,
    image = image,
    tags = tagList,
    categoryId = categoryId,
    categoryTitle = categoryTitle,
    subCategoryId = subCategoryId,
    subCategoryTitle = subCategoryTitle,
)

private fun FavoriteRemoteItem.invalidatesMetadata(existing: FavoriteComicEntity): Boolean =
    (authors.isNotEmpty() && !existing.authorList.normalized().containsAll(authors.normalized())) ||
        (tags.isNotEmpty() && !existing.tagList.normalized().containsAll(tags.normalized())) ||
        categoryId != existing.categoryId ||
        categoryTitle != existing.categoryTitle ||
        subCategoryId != existing.subCategoryId ||
        subCategoryTitle != existing.subCategoryTitle

private fun FavoriteComicEntity.matchesLightweight(item: FavoriteRemoteItem): Boolean =
    title == item.title &&
        description == item.description &&
        image == item.image &&
        categoryId == item.categoryId &&
        categoryTitle == item.categoryTitle &&
        subCategoryId == item.subCategoryId &&
        subCategoryTitle == item.subCategoryTitle &&
        (item.authors.isEmpty() || authorList.normalized().containsAll(item.authors.normalized())) &&
        (item.tags.isEmpty() || tagList.normalized().containsAll(item.tags.normalized()))

private fun List<String>.normalized(): List<String> =
    map { it.trim() }.filter { it.isNotBlank() }.distinct()

private fun buildTerms(
    accountId: Int,
    albumId: Int,
    tags: List<String>,
    authors: List<String>,
): List<FavoriteMetadataTermEntity> {
    val result = linkedMapOf<String, FavoriteMetadataTermEntity>()
    fun add(type: String, values: List<String>) {
        values.normalized().forEach { value ->
            val normalized = value.lowercase()
            result["$type:$normalized"] = FavoriteMetadataTermEntity(
                accountId = accountId,
                albumId = albumId,
                termType = type,
                value = value,
                normalizedValue = normalized,
            )
        }
    }
    add(FAVORITE_TERM_TAG, tags)
    add(FAVORITE_TERM_AUTHOR, authors)
    return result.values.toList()
}

private fun Comic.toRemoteItem() = FavoriteRemoteItem(
    albumId = id,
    title = name,
    authors = authorList,
    description = description,
    tags = tagList,
)
