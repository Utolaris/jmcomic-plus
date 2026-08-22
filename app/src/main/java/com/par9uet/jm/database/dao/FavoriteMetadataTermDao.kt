package com.par9uet.jm.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.par9uet.jm.database.model.FavoriteMetadataTermEntity
import com.par9uet.jm.database.model.FavoriteTermCount
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteMetadataTermDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<FavoriteMetadataTermEntity>)

    @Query(
        "DELETE FROM favorite_metadata_terms " +
            "WHERE accountId = :accountId AND albumId = :albumId"
    )
    suspend fun deleteForAlbum(accountId: Int, albumId: Int)

    @Query(
        "DELETE FROM favorite_metadata_terms " +
            "WHERE accountId = :accountId " +
            "AND albumId NOT IN (SELECT DISTINCT albumId FROM favorite_folder_memberships WHERE accountId = :accountId)"
    )
    suspend fun deleteOrphans(accountId: Int)

    @Query(
        "SELECT t.value AS value, COUNT(DISTINCT t.albumId) AS count " +
            "FROM favorite_metadata_terms t " +
            "JOIN favorite_folder_memberships m " +
            "ON m.accountId = t.accountId AND m.albumId = t.albumId " +
            "WHERE t.accountId = :accountId " +
            "AND t.termType = :termType " +
            "AND m.folderId = :folderId " +
            "GROUP BY t.normalizedValue, t.value " +
            "ORDER BY t.value COLLATE NOCASE"
    )
    fun observeCounts(
        accountId: Int,
        folderId: Int,
        termType: String,
    ): Flow<List<FavoriteTermCount>>
}
