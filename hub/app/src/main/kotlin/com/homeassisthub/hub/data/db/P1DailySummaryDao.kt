package com.homeassisthub.hub.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface P1DailySummaryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: P1DailySummary)

    @Query("SELECT * FROM p1_daily_summary WHERE date >= :startDate AND date <= :endDate ORDER BY date ASC")
    suspend fun getRange(startDate: String, endDate: String): List<P1DailySummary>

    @Query("SELECT * FROM p1_daily_summary ORDER BY date DESC")
    suspend fun getAll(): List<P1DailySummary>

    @Query("SELECT * FROM p1_daily_summary WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): P1DailySummary?
}
