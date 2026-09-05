package com.par9uet.jm.di

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.par9uet.jm.database.AppDatabase
import com.par9uet.jm.store.DownloadManager
import com.par9uet.jm.store.DownloadWorkScheduler
import com.par9uet.jm.store.FavoriteStore
import com.par9uet.jm.ui.viewModel.DownloadComicDetailViewModel
import com.par9uet.jm.ui.viewModel.DownloadViewModel
import com.par9uet.jm.worker.DownloadComicWorker
import com.par9uet.jm.worker.WorkManagerDownloadWorkScheduler
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.dsl.worker
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module


val databaseModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "app_database"
        )
            .addMigrations(*appDatabaseMigrations.toTypedArray())
            .fallbackToDestructiveMigration(false)
            .build()
    }
    single { get<AppDatabase>().downloadComicDao() }
    single { get<AppDatabase>().favoriteComicDao() }
    single { get<AppDatabase>().favoriteFolderDao() }
    single { get<AppDatabase>().favoriteFolderMembershipDao() }
    single { get<AppDatabase>().favoriteMetadataDao() }
    single { get<AppDatabase>().favoriteMetadataTermDao() }
    single { get<AppDatabase>().favoriteSyncStateDao() }
    single { FavoriteStore(get(), get(), get(), get(), get(), get(), get()) }
    single<DownloadWorkScheduler> { WorkManagerDownloadWorkScheduler(androidContext()) }
    single { DownloadManager(get(), get(), get(), get()) }
    viewModel { DownloadViewModel(get(), get()) }
    viewModel { DownloadComicDetailViewModel(get()) }

    worker {
        DownloadComicWorker(
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
        )
    }
}

internal val appDatabaseMigrations = listOf<Migration>(
    object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE download_comics ADD COLUMN groupId INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE download_comics ADD COLUMN groupName TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE download_comics ADD COLUMN chapterName TEXT NOT NULL DEFAULT ''")
        }
    },

    object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE download_comics ADD COLUMN tagList TEXT NOT NULL DEFAULT '[]'")
        }
    },

    object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS favorite_comics (
                accountId INTEGER NOT NULL,
                albumId INTEGER NOT NULL,
                title TEXT NOT NULL,
                authorList TEXT NOT NULL,
                description TEXT NOT NULL,
                image TEXT NOT NULL,
                tagList TEXT NOT NULL,
                roleList TEXT NOT NULL,
                workList TEXT NOT NULL,
                categoryId TEXT,
                categoryTitle TEXT,
                subCategoryId TEXT,
                subCategoryTitle TEXT,
                metadataComplete INTEGER NOT NULL,
                metadataUpdatedAt INTEGER NOT NULL,
                lastFavoriteOrder INTEGER NOT NULL,
                lastFavoriteSyncAt INTEGER NOT NULL,
                PRIMARY KEY(accountId, albumId)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_favorite_comics_accountId_lastFavoriteOrder ON favorite_comics(accountId, lastFavoriteOrder)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_favorite_comics_accountId_metadataComplete ON favorite_comics(accountId, metadataComplete)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS favorite_folders (
                accountId INTEGER NOT NULL,
                folderId INTEGER NOT NULL,
                name TEXT NOT NULL,
                lastSyncedAt INTEGER NOT NULL,
                PRIMARY KEY(accountId, folderId)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS favorite_folder_memberships (
                accountId INTEGER NOT NULL,
                folderId INTEGER NOT NULL,
                albumId INTEGER NOT NULL,
                remoteOrder INTEGER NOT NULL,
                lastSyncedAt INTEGER NOT NULL,
                PRIMARY KEY(accountId, folderId, albumId)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_favorite_folder_memberships_accountId_folderId_remoteOrder ON favorite_folder_memberships(accountId, folderId, remoteOrder)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_favorite_folder_memberships_accountId_albumId ON favorite_folder_memberships(accountId, albumId)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS favorite_metadata (
                accountId INTEGER NOT NULL,
                albumId INTEGER NOT NULL,
                tags TEXT NOT NULL,
                authors TEXT NOT NULL,
                roles TEXT NOT NULL,
                works TEXT NOT NULL,
                metadataComplete INTEGER NOT NULL,
                metadataUpdatedAt INTEGER NOT NULL,
                PRIMARY KEY(accountId, albumId)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_favorite_metadata_accountId_metadataComplete ON favorite_metadata(accountId, metadataComplete)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS favorite_metadata_terms (
                accountId INTEGER NOT NULL,
                albumId INTEGER NOT NULL,
                termType TEXT NOT NULL,
                value TEXT NOT NULL,
                normalizedValue TEXT NOT NULL,
                PRIMARY KEY(accountId, albumId, termType, normalizedValue)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_favorite_metadata_terms_accountId_termType_normalizedValue ON favorite_metadata_terms(accountId, termType, normalizedValue)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_favorite_metadata_terms_accountId_albumId ON favorite_metadata_terms(accountId, albumId)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS favorite_sync_state (
                accountId INTEGER NOT NULL,
                scopeFolderId INTEGER NOT NULL,
                lastSuccessfulSyncAt INTEGER NOT NULL,
                lastForceRefreshAt INTEGER NOT NULL,
                generation INTEGER NOT NULL,
                PRIMARY KEY(accountId, scopeFolderId)
            )
            """.trimIndent()
        )
        }
    },
)
