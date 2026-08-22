package com.par9uet.jm.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.par9uet.jm.database.model.FavoriteFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteFolderDao {
    @Query(
        "SELECT * FROM favorite_folders " +
            "WHERE accountId = :accountId ORDER BY folderId"
    )
    fun observeAll(accountId: Int): Flow<List<FavoriteFolderEntity>>

    @Query("SELECT * FROM favorite_folders WHERE accountId = :accountId ORDER BY folderId")
    suspend fun getAll(accountId: Int): List<FavoriteFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<FavoriteFolderEntity>)

    @Query("DELETE FROM favorite_folders WHERE accountId = :accountId AND folderId IN (:folderIds)")
    suspend fun deleteByIds(accountId: Int, folderIds: List<Int>)
}
