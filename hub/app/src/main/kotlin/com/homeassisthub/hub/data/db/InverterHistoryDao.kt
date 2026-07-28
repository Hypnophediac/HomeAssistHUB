package com.homeassisthub.hub.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface InverterHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<InverterHistoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: InverterHistoryEntity)

    @Query("SELECT * FROM inverter_history WHERE timestamp >= :startMs AND timestamp <= :endMs ORDER BY timestamp ASC")
    suspend fun getRange(startMs: Long, endMs: Long): List<InverterHistoryEntity>

    @Query("SELECT * FROM inverter_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<InverterHistoryEntity>

    @Query("SELECT * FROM inverter_history ORDER BY timestamp ASC LIMIT 1")
    suspend fun getOldest(): InverterHistoryEntity?

    @Query("SELECT * FROM inverter_history ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): InverterHistoryEntity?

    @Query("SELECT COUNT(*) FROM inverter_history")
    suspend fun count(): Int

    @Query("SELECT * FROM inverter_history WHERE timestamp > :sinceMs ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getRangeSince(sinceMs: Long, limit: Int = 500): List<InverterHistoryEntity>

    @Query("DELETE FROM inverter_history WHERE timestamp < :olderThanEpochMillis")
    suspend fun deleteOlderThan(olderThanEpochMillis: Long)

    @Query("DELETE FROM inverter_history")
    suspend fun deleteAll()
}
