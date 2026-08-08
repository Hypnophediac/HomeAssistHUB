package com.homeassisthub.hub.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [P1DataEntity::class, P1RawData::class, P1DailySummary::class, InverterHistoryEntity::class, InverterDailySummary::class],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun p1Dao(): P1Dao
    abstract fun p1RawDao(): P1RawDao
    abstract fun p1DailySummaryDao(): P1DailySummaryDao
    abstract fun inverterHistoryDao(): InverterHistoryDao
    abstract fun inverterDailySummaryDao(): InverterDailySummaryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Additive-only: creates the new table without touching existing
         *  P1/inverter history data (the Hub runs continuously, so a
         *  destructive migration would wipe historical data on upgrade). */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `inverter_daily_summary` (" +
                        "`date` TEXT NOT NULL, `producedKwh` REAL NOT NULL, PRIMARY KEY(`date`))"
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE inverter_history ADD COLUMN dailyEnergyKwh REAL NOT NULL DEFAULT 0.0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "homeassist_hub.db"
                ).addMigrations(MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
