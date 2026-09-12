package com.par9uet.jm.favorites.data

import com.par9uet.jm.database.AppDatabase
import com.par9uet.jm.database.dao.FavoriteComicDao
import com.par9uet.jm.database.dao.FavoriteFolderDao
import com.par9uet.jm.database.dao.FavoriteFolderMembershipDao
import com.par9uet.jm.database.dao.FavoriteMetadataDao
import com.par9uet.jm.database.dao.FavoriteMetadataTermDao
import com.par9uet.jm.database.dao.FavoriteSyncStateDao
import com.par9uet.jm.favorites.model.FavoriteLocalQuery

/**
 * Public DI-facing facade over the local Favorites snapshot. Behavior is owned by the
 * three internal collaborators; this type only composes them so a single [FavoriteStore]
 * still satisfies all three ports.
 */
class FavoriteStore(
    database: AppDatabase,
    comicDao: FavoriteComicDao,
    folderDao: FavoriteFolderDao,
    membershipDao: FavoriteFolderMembershipDao,
    metadataDao: FavoriteMetadataDao,
    termDao: FavoriteMetadataTermDao,
    syncStateDao: FavoriteSyncStateDao,
) : FavoriteLocalQuery by FavoriteLocalQueryStore(comicDao, folderDao, termDao),
    FavoriteLocalMutation by FavoriteLocalMutationStore(
        database = database,
        comicDao = comicDao,
        folderDao = folderDao,
        membershipDao = membershipDao,
        metadataDao = metadataDao,
        termDao = termDao,
    ),
    FavoriteLocalSync by FavoriteLocalSyncStore(
        database = database,
        comicDao = comicDao,
        folderDao = folderDao,
        membershipDao = membershipDao,
        metadataDao = metadataDao,
        termDao = termDao,
        syncStateDao = syncStateDao,
    )
