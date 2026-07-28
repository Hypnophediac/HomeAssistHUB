package com.homeassisthub.hub.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Finalized daily solar production (kWh), snapshotted from the Huawei
 * Kiosk API's "dailyEnergy" hardware counter at (or near) midnight. See
 * [com.homeassisthub.hub.controller.InverterLiveData.dailyEnergyKwh].
 */
@Entity(tableName = "inverter_daily_summary")
data class InverterDailySummary(
    @PrimaryKey
    val date: String,
    val producedKwh: Double
)
