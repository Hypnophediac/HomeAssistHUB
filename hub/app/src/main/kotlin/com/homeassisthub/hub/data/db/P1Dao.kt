package com.homeassisthub.hub.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface P1Dao {

    @Insert
    suspend fun insert(entity: P1DataEntity)

    @Query("SELECT * FROM p1_readings ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<P1DataEntity>

    @Query("SELECT * FROM p1_readings ORDER BY timestamp ASC")
    fun observeAll(): Flow<List<P1DataEntity>>

    @Query("SELECT * FROM p1_readings WHERE timestamp >= :sinceEpochMillis ORDER BY timestamp ASC")
    fun observeSince(sinceEpochMillis: Long): Flow<List<P1DataEntity>>

    @Query("DELETE FROM p1_readings WHERE timestamp < :olderThanEpochMillis")
    suspend fun deleteOlderThan(olderThanEpochMillis: Long)
}
