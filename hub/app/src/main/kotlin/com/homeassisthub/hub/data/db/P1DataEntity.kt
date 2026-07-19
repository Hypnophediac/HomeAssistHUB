package com.homeassisthub.hub.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single P1 smart meter reading, persisted roughly once per minute
 * by the P1MeterController (Phase 3).
 */
@Entity(tableName = "p1_readings")
data class P1DataEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val powerW: Double,
    val voltageV: Double
)
