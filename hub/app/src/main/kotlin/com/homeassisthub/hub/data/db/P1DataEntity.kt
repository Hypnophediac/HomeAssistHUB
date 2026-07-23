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
    val voltageV: Double,
    val powerImportW: Double = 0.0,
    val powerExportW: Double = 0.0,
    // ── Per-phase voltage (V) ──
    val l1V: Double = 0.0,
    val l2V: Double = 0.0,
    val l3V: Double = 0.0,
    // ── Per-phase current (A) ──
    val l1A: Double = 0.0,
    val l2A: Double = 0.0,
    val l3A: Double = 0.0,
    // ── Per-phase power (W) ──
    val powerImportL1W: Double = 0.0,
    val powerImportL2W: Double = 0.0,
    val powerImportL3W: Double = 0.0,
    val powerExportL1W: Double = 0.0,
    val powerExportL2W: Double = 0.0,
    val powerExportL3W: Double = 0.0,
    // ── Power factor & frequency ──
    val powerFactor: Double = 0.0,
    val frequencyHz: Double = 50.0,
    // ── Cumulative energy (kWh) ──
    val importT1Kwh: Double = 0.0,
    val importT2Kwh: Double = 0.0,
    val exportT1Kwh: Double = 0.0,
    val exportT2Kwh: Double = 0.0,
    val currentTariff: Int = 1
)
