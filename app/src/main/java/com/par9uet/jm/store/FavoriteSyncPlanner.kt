package com.par9uet.jm.store

import com.par9uet.jm.database.model.FavoriteComicEntity

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
