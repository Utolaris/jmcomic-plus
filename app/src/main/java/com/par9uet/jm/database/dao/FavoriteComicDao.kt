package com.par9uet.jm.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.par9uet.jm.database.model.FavoriteComicEntity

@Dao
interface FavoriteComicDao {
    @RawQuery(
        observedEntities = [
            FavoriteComicEntity::class,
            com.par9uet.jm.database.model.FavoriteMetadataEntity::class,
            com.par9uet.jm.database.model.FavoriteMetadataTermEntity::class,
            com.par9uet.jm.database.model.FavoriteFolderMembershipEntity::class,
        ]
    )
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, FavoriteComicEntity>

    @Query("SELECT * FROM favorite_comics WHERE accountId = :accountId ORDER BY albumId")
    suspend fun getAll(accountId: Int): List<FavoriteComicEntity>

    @Query("SELECT * FROM favorite_comics WHERE accountId = :accountId AND albumId IN (:albumIds)")
    suspend fun getByIds(accountId: Int, albumIds: List<Int>): List<FavoriteComicEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteComicEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<FavoriteComicEntity>)

    @Query("DELETE FROM favorite_comics WHERE accountId = :accountId AND albumId IN (:albumIds)")
    suspend fun deleteByIds(accountId: Int, albumIds: List<Int>)

    @Query(
        "DELETE FROM favorite_comics " +
            "WHERE accountId = :accountId " +
            "AND albumId NOT IN (SELECT DISTINCT albumId FROM favorite_folder_memberships WHERE accountId = :accountId)"
    )
    suspend fun deleteOrphans(accountId: Int)
}
