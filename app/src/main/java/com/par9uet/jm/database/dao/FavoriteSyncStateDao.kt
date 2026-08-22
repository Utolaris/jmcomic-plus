package com.par9uet.jm.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.par9uet.jm.database.model.FavoriteSyncStateEntity

@Dao
interface FavoriteSyncStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteSyncStateEntity)

    @Query(
        "SELECT * FROM favorite_sync_state " +
            "WHERE accountId = :accountId AND scopeFolderId = :scopeFolderId LIMIT 1"
    )
    suspend fun get(accountId: Int, scopeFolderId: Int): FavoriteSyncStateEntity?
}
