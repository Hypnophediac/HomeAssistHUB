package com.homeassisthub.hub.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Historical inverter active power reading, used for backfilling
 * past production data from the FusionSolar Northbound OpenAPI.
 *
 * Each row represents a 5-minute (or hourly) interval snapshot of
 * the inverter's active power output in watts.
 */
@Entity(tableName = "inverter_history")
data class InverterHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val activePowerW: Double
)
