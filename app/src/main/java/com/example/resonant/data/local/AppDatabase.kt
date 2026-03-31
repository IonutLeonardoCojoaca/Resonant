package com.example.resonant.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.resonant.data.local.dao.DownloadedSongDao
import com.example.resonant.data.local.entities.DownloadedSong

@Database(entities = [DownloadedSong::class], version = 3, exportSchema = false)
@androidx.room.TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadedSongDao(): DownloadedSongDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // No-op: no structural changes between v1 and v2.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }

        // v2 → v3: add userId to support per-user download isolation.
        // Recreates the table with a composite PK (userId, songId).
        // Existing rows are migrated with userId = '' (empty string).
        // IMPORTANT: For any future schema change, increment version and add a new
        // Migration(oldVersion, newVersion) with the necessary SQL.
        // NEVER use fallbackToDestructiveMigration() — it wipes all downloaded songs.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE downloaded_songs_new (
                        userId TEXT NOT NULL DEFAULT '',
                        songId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artistName TEXT NOT NULL,
                        album TEXT,
                        duration TEXT,
                        localAudioPath TEXT NOT NULL,
                        localImagePath TEXT,
                        downloadDate INTEGER NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        audioAnalysisJson TEXT,
                        PRIMARY KEY(userId, songId)
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO downloaded_songs_new
                        (userId, songId, title, artistName, album, duration,
                         localAudioPath, localImagePath, downloadDate, sizeBytes, audioAnalysisJson)
                    SELECT '' AS userId, songId, title, artistName, album, duration,
                           localAudioPath, localImagePath, downloadDate, sizeBytes, audioAnalysisJson
                    FROM downloaded_songs
                """.trimIndent())
                db.execSQL("DROP TABLE downloaded_songs")
                db.execSQL("ALTER TABLE downloaded_songs_new RENAME TO downloaded_songs")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "resonant_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}