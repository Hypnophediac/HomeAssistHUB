package com.homeassisthub.hub.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface P1RawDao {

    @Insert
    suspend fun insert(data: P1RawData)

    @Query("SELECT * FROM p1_raw_data WHERE timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp ASC")
    suspend fun getRange(startMs: Long, endMs: Long): List<P1RawData>

    @Query("SELECT * FROM p1_raw_data ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): P1RawData?

    @Query("SELECT * FROM p1_raw_data ORDER BY timestamp ASC LIMIT 1")
    suspend fun getOldest(): P1RawData?

    @Query("SELECT * FROM p1_raw_data WHERE timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp ASC LIMIT 1")
    suspend fun getFirstInRange(startMs: Long, endMs: Long): P1RawData?

    @Query("SELECT * FROM p1_raw_data WHERE timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastInRange(startMs: Long, endMs: Long): P1RawData?

    @Query("DELETE FROM p1_raw_data WHERE timestamp < :olderThanEpochMillis")
    suspend fun deleteOlderThan(olderThanEpochMillis: Long)
}
