package com.par9uet.jm.favorites.data

import com.par9uet.jm.database.dao.FavoriteMetadataTermDao
import com.par9uet.jm.database.model.FavoriteComicEntity
import com.par9uet.jm.database.model.FavoriteFolderEntity
import com.par9uet.jm.database.model.FavoriteMetadataEntity
import com.par9uet.jm.database.model.FavoriteMetadataTermEntity

/**
 * Snapshot equality that ignores volatile sync timestamps so paging/list invalidation
 * only happens when user-visible content actually changed.
 */
internal fun FavoriteComicEntity.sameSnapshotContent(other: FavoriteComicEntity): Boolean =
    copy(metadataUpdatedAt = 0L, lastFavoriteSyncAt = 0L) ==
        other.copy(metadataUpdatedAt = 0L, lastFavoriteSyncAt = 0L)

internal fun FavoriteMetadataEntity.sameSnapshotContent(other: FavoriteMetadataEntity): Boolean =
    copy(metadataUpdatedAt = 0L) == other.copy(metadataUpdatedAt = 0L)

internal fun FavoriteFolderEntity.sameSnapshotContent(other: FavoriteFolderEntity): Boolean =
    copy(lastSyncedAt = 0L) == other.copy(lastSyncedAt = 0L)

/** Replace the term index for one album in a single logical step (caller owns the transaction). */
internal suspend fun FavoriteMetadataTermDao.replaceTerms(
    accountId: Int,
    albumId: Int,
    terms: List<FavoriteMetadataTermEntity>,
) {
    deleteForAlbum(accountId, albumId)
    if (terms.isNotEmpty()) upsertAll(terms)
}
