package com.example.resonant.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.resonant.data.local.dao.AutoDownloadJobDao
import com.example.resonant.data.local.dao.DownloadedSongDao
import com.example.resonant.data.local.dao.OfflineCollectionDao
import com.example.resonant.data.local.entities.AutoDownloadJob
import com.example.resonant.data.local.entities.DownloadedSong
import com.example.resonant.data.local.entities.DownloadCollection
import com.example.resonant.data.local.entities.DownloadCollectionSong
import com.example.resonant.data.local.entities.OfflinePlaylist
import com.example.resonant.data.local.entities.OfflinePlaymix
import com.example.resonant.data.local.entities.OfflinePlaymixClip

@Database(
    entities = [
        DownloadedSong::class,
        DownloadCollection::class,
        DownloadCollectionSong::class,
        AutoDownloadJob::class,
        OfflinePlaymix::class,
        OfflinePlaymixClip::class,
        OfflinePlaylist::class
    ],
    version = 7,
    exportSchema = false
)
@androidx.room.TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadedSongDao(): DownloadedSongDao

    abstract fun autoDownloadJobDao(): AutoDownloadJobDao

    abstract fun offlineCollectionDao(): OfflineCollectionDao

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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE downloaded_songs " +
                        "ADD COLUMN isIndividuallySaved INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS download_collections (
                        userId TEXT NOT NULL,
                        collectionType TEXT NOT NULL,
                        collectionId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        PRIMARY KEY(userId, collectionType, collectionId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS download_collection_songs (
                        userId TEXT NOT NULL,
                        collectionType TEXT NOT NULL,
                        collectionId TEXT NOT NULL,
                        songId TEXT NOT NULL,
                        PRIMARY KEY(userId, collectionType, collectionId, songId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_download_collection_songs_userId_songId " +
                        "ON download_collection_songs(userId, songId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_download_collection_songs_userId_collectionType_collectionId " +
                        "ON download_collection_songs(userId, collectionType, collectionId)"
                )
            }
        }

        // v4 → v5: add an index on songId alone so lookups/counts that filter
        // only by songId (e.g. countBySongId, countBySongIds) don't fall back
        // to a full table scan — the existing PK is (userId, songId), which
        // can't be used as a prefix index without userId in the WHERE clause.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_downloaded_songs_songId " +
                        "ON downloaded_songs(songId)"
                )
            }
        }

        // v5 → v6: tabla auto_download_jobs (descargas automáticas). Solo añade; no toca las
        // descargas existentes. Columnas, PK e índice deben coincidir con la entidad AutoDownloadJob.
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `auto_download_jobs` (
                        `userId` TEXT NOT NULL,
                        `planId` TEXT NOT NULL,
                        `songId` TEXT NOT NULL,
                        `artistId` TEXT NOT NULL,
                        `artistName` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `attempts` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`userId`, `planId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_auto_download_jobs_userId_state` " +
                        "ON `auto_download_jobs` (`userId`, `state`)"
                )
            }
        }

        // v6 → v7: paquetes sin conexión de Playmix (detalle y clips) y orden/estado de playlists descargadas
        // (collection-queue-offline). Solo crea tablas nuevas; no toca las descargas existentes. Columnas, PK e
        // índices deben coincidir con OfflinePlaymix, OfflinePlaymixClip y OfflinePlaylist.
        internal val MIGRATION_6_7_STATEMENTS = listOf(
            """
            CREATE TABLE IF NOT EXISTS `offline_playmix` (
                `userId` TEXT NOT NULL,
                `playmixId` TEXT NOT NULL,
                `detailJson` TEXT NOT NULL,
                `contentVersion` TEXT,
                `syncedAtEpochMs` INTEGER NOT NULL,
                `unavailable` INTEGER NOT NULL,
                `pendingUpdate` INTEGER NOT NULL,
                PRIMARY KEY(`userId`, `playmixId`)
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS `offline_playmix_clip` (
                `userId` TEXT NOT NULL,
                `playmixId` TEXT NOT NULL,
                `transitionId` TEXT NOT NULL,
                `renderRevision` INTEGER NOT NULL,
                `cacheKey` TEXT NOT NULL,
                `sizeBytes` INTEGER NOT NULL,
                PRIMARY KEY(`userId`, `playmixId`, `transitionId`)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS `index_offline_playmix_clip_userId_playmixId` " +
                "ON `offline_playmix_clip` (`userId`, `playmixId`)",
            """
            CREATE TABLE IF NOT EXISTS `offline_playlist` (
                `userId` TEXT NOT NULL,
                `playlistId` TEXT NOT NULL,
                `songIdsJson` TEXT NOT NULL,
                `syncedAtEpochMs` INTEGER NOT NULL,
                `unavailable` INTEGER NOT NULL,
                `pendingUpdate` INTEGER NOT NULL,
                PRIMARY KEY(`userId`, `playlistId`)
            )
            """.trimIndent()
        )

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_6_7_STATEMENTS.forEach { db.execSQL(it) }
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "resonant_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
