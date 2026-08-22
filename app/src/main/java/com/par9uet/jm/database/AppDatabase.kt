package com.par9uet.jm.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.par9uet.jm.database.converter.ListStringToStringConverter
import com.par9uet.jm.database.dao.DownloadComicDao
import com.par9uet.jm.database.dao.FavoriteComicDao
import com.par9uet.jm.database.dao.FavoriteFolderDao
import com.par9uet.jm.database.dao.FavoriteFolderMembershipDao
import com.par9uet.jm.database.dao.FavoriteMetadataDao
import com.par9uet.jm.database.dao.FavoriteMetadataTermDao
import com.par9uet.jm.database.dao.FavoriteSyncStateDao
import com.par9uet.jm.database.model.DownloadComic
import com.par9uet.jm.database.model.FavoriteComicEntity
import com.par9uet.jm.database.model.FavoriteFolderEntity
import com.par9uet.jm.database.model.FavoriteFolderMembershipEntity
import com.par9uet.jm.database.model.FavoriteMetadataEntity
import com.par9uet.jm.database.model.FavoriteMetadataTermEntity
import com.par9uet.jm.database.model.FavoriteSyncStateEntity

@Database(
    entities = [
        DownloadComic::class,
        FavoriteComicEntity::class,
        FavoriteFolderEntity::class,
        FavoriteFolderMembershipEntity::class,
        FavoriteMetadataEntity::class,
        FavoriteMetadataTermEntity::class,
        FavoriteSyncStateEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
@TypeConverters(ListStringToStringConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadComicDao(): DownloadComicDao
    abstract fun favoriteComicDao(): FavoriteComicDao
    abstract fun favoriteFolderDao(): FavoriteFolderDao
    abstract fun favoriteFolderMembershipDao(): FavoriteFolderMembershipDao
    abstract fun favoriteMetadataDao(): FavoriteMetadataDao
    abstract fun favoriteMetadataTermDao(): FavoriteMetadataTermDao
    abstract fun favoriteSyncStateDao(): FavoriteSyncStateDao
}
