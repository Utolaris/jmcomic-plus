package com.par9uet.jm.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.par9uet.jm.database.model.FavoriteMetadataEntity

@Dao
interface FavoriteMetadataDao {
    @Query("SELECT * FROM favorite_metadata WHERE accountId = :accountId AND albumId IN (:albumIds)")
    suspend fun getByIds(accountId: Int, albumIds: List<Int>): List<FavoriteMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteMetadataEntity)

    @Query("DELETE FROM favorite_metadata WHERE accountId = :accountId AND albumId IN (:albumIds)")
    suspend fun deleteByIds(accountId: Int, albumIds: List<Int>)

    @Query(
        "DELETE FROM favorite_metadata " +
            "WHERE accountId = :accountId " +
            "AND albumId NOT IN (SELECT DISTINCT albumId FROM favorite_folder_memberships WHERE accountId = :accountId)"
    )
    suspend fun deleteOrphans(accountId: Int)
}
