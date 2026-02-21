package com.example.resonant.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.resonant.data.local.dao.DownloadedSongDao
import com.example.resonant.data.local.entities.DownloadedSong

@Database(entities = [DownloadedSong::class], version = 2, exportSchema = false)
@androidx.room.TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun downloadedSongDao(): DownloadedSongDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "resonant_database"
                )
                    .fallbackToDestructiveMigration() // Borra la BD si cambias la estructura (útil en dev)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}