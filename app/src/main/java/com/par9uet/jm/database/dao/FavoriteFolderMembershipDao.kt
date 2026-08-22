package com.par9uet.jm.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.par9uet.jm.database.model.FavoriteFolderMembershipEntity

@Dao
interface FavoriteFolderMembershipDao {
    @Query(
        "SELECT albumId FROM favorite_folder_memberships " +
            "WHERE accountId = :accountId AND folderId = :folderId ORDER BY remoteOrder"
    )
    suspend fun getAlbumIds(accountId: Int, folderId: Int): List<Int>

    @Query(
        "SELECT COALESCE(MAX(remoteOrder), -1) FROM favorite_folder_memberships " +
            "WHERE accountId = :accountId AND folderId = :folderId"
    )
    suspend fun maxRemoteOrder(accountId: Int, folderId: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<FavoriteFolderMembershipEntity>)

    @Query(
        "DELETE FROM favorite_folder_memberships " +
            "WHERE accountId = :accountId AND folderId = :folderId"
    )
    suspend fun deleteForScope(accountId: Int, folderId: Int)

    @Query(
        "DELETE FROM favorite_folder_memberships " +
            "WHERE accountId = :accountId AND albumId IN (:albumIds)"
    )
    suspend fun deleteForAlbums(accountId: Int, albumIds: List<Int>)

    @Query(
        "DELETE FROM favorite_folder_memberships " +
            "WHERE accountId = :accountId AND albumId = :albumId AND folderId != 0"
    )
    suspend fun deleteNonDefaultForAlbum(accountId: Int, albumId: Int)

    @Query("DELETE FROM favorite_folder_memberships WHERE accountId = :accountId")
    suspend fun deleteAll(accountId: Int)
}
