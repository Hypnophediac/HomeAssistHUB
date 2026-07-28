package com.homeassisthub.hub.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface InverterDailySummaryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: InverterDailySummary)

    @Query("SELECT * FROM inverter_daily_summary WHERE date >= :startDate AND date <= :endDate ORDER BY date ASC")
    suspend fun getRange(startDate: String, endDate: String): List<InverterDailySummary>

    @Query("SELECT * FROM inverter_daily_summary WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): InverterDailySummary?
}
