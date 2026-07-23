package com.homeassisthub.hub.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "p1_daily_summary")
data class P1DailySummary(
    @PrimaryKey
    val date: String,
    // ── Energy totals (kWh) ──
    val totalConsumedKwh: Double,
    val totalExportedKwh: Double,
    val importT1Kwh: Double = 0.0,
    val importT2Kwh: Double = 0.0,
    val exportT1Kwh: Double = 0.0,
    val exportT2Kwh: Double = 0.0,
    // ── Power statistics (W) ──
    val minPowerW: Double = 0.0,
    val maxPowerW: Double = 0.0,
    val avgPowerW: Double = 0.0,
    val maxImportW: Double = 0.0,
    val maxExportW: Double = 0.0,
    // ── Peak hours ──
    val peakConsumptionHour: Int = -1,
    val peakExportHour: Int = -1,
    val peakConsumptionKwh: Double = 0.0,
    val peakExportKwh: Double = 0.0,
    // ── Self-consumption ──
    val selfConsumptionRatio: Double = 0.0,
    val netEnergyKwh: Double = 0.0,
    // ── Per-phase averages ──
    val avgL1V: Double = 0.0,
    val avgL2V: Double = 0.0,
    val avgL3V: Double = 0.0,
    val avgL1A: Double = 0.0,
    val avgL2A: Double = 0.0,
    val avgL3A: Double = 0.0,
    val avgPowerFactor: Double = 0.0,
    val avgFrequencyHz: Double = 50.0
)
