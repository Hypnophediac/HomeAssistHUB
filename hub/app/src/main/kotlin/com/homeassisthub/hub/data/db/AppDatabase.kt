package com.homeassisthub.hub.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [P1DataEntity::class, P1RawData::class, P1DailySummary::class, InverterHistoryEntity::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun p1Dao(): P1Dao
    abstract fun p1RawDao(): P1RawDao
    abstract fun p1DailySummaryDao(): P1DailySummaryDao
    abstract fun inverterHistoryDao(): InverterHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "homeassist_hub.db"
                ).fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
