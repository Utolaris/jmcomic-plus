package com.par9uet.jm.database

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking

class AppDatabaseMigrationTest {
    private lateinit var databaseFile: File
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        databaseFile = context.getDatabasePath("migration-${System.nanoTime()}.db")
        databaseFile.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.execSQL(
                "CREATE TABLE download_comics (" +
                    "id INTEGER NOT NULL PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "authorList TEXT NOT NULL, " +
                    "coverPath TEXT NOT NULL, " +
                    "zipPath TEXT NOT NULL, " +
                    "progress REAL NOT NULL, " +
                    "status TEXT NOT NULL, " +
                    "createTime INTEGER NOT NULL)"
            )
            db.execSQL("PRAGMA user_version = 2")
            db.insert("download_comics", null, ContentValues().apply {
                put("id", 42)
                put("name", "old comic")
                put("authorList", "[\"author\"]")
                put("coverPath", "/cache/cover.webp")
                put("zipPath", "/cache/comic")
                put("progress", 1.0)
                put("status", "complete")
                put("createTime", 123L)
            })
        }
    }

    @After
    fun tearDown() {
        database?.close()
        databaseFile.delete()
    }

    @Test
    fun `v2 through v5 migration preserves downloads and creates favorite schema`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.databaseBuilder(context, AppDatabase::class.java, databaseFile.name)
            .addMigrations(*com.par9uet.jm.di.appDatabaseMigrations.toTypedArray())
            .build()

        val opened = database!!.openHelper.writableDatabase
        assertEquals(5, opened.version)
        val task = database!!.downloadComicDao().getById(42)
        assertNotNull(task)
        assertEquals("old comic", task!!.name)
        assertEquals(listOf("author"), task.authorList)
        assertEquals(0, task.groupId)
        assertEquals("", task.groupName)
        assertEquals("", task.chapterName)
        assertEquals(emptyList<String>(), task.tagList)

        val tables = schemaObjects(opened, "table")
        assertTrue("favorite_comics" in tables)
        assertTrue("favorite_folders" in tables)
        assertTrue("favorite_folder_memberships" in tables)
        assertTrue("favorite_metadata" in tables)
        assertTrue("favorite_metadata_terms" in tables)
        assertTrue("favorite_sync_state" in tables)

        val indexes = schemaObjects(opened, "index")
        assertTrue("index_favorite_comics_accountId_lastFavoriteOrder" in indexes)
        assertTrue("index_favorite_folder_memberships_accountId_folderId_remoteOrder" in indexes)
    }

    private fun schemaObjects(db: androidx.sqlite.db.SupportSQLiteDatabase, type: String): Set<String> =
        db.query(
            "SELECT name FROM sqlite_master WHERE type = ?",
            arrayOf(type),
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
}
