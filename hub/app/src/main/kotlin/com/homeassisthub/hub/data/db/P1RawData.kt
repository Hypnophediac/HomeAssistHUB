package com.homeassisthub.hub.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "p1_raw_data")
data class P1RawData(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,

    // ── Cumulative energy (kWh) ──
    val importT1Kwh: Double,
    val importT2Kwh: Double,
    val exportT1Kwh: Double,
    val exportT2Kwh: Double,
    val importTotalKwh: Double = 0.0,
    val exportTotalKwh: Double = 0.0,

    // ── Instantaneous power (W) ──
    val currentPowerW: Double,
    val powerImportW: Double = 0.0,
    val powerExportW: Double = 0.0,

    // ── Per-phase voltage (V) ──
    val l1V: Double,
    val l2V: Double,
    val l3V: Double,

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

    // ── Power factor ──
    val powerFactor: Double = 0.0,
    val powerFactorL1: Double = 0.0,
    val powerFactorL2: Double = 0.0,
    val powerFactorL3: Double = 0.0,

    // ── Frequency ──
    val frequencyHz: Double = 50.0,

    // ── Reactive energy (kWh) ──
    val reactiveImportKwh: Double = 0.0,
    val reactiveExportKwh: Double = 0.0,

    // ── Meta ──
    val currentTariff: Int = 1,
    val meterSerial: String? = null,
    val deviceName: String? = null,
    val firmwareVersion: String? = null,

    // ── Device status ──
    val circuitBreakerStatus: String? = null,
    val limiterThreshold: Double = 0.0
)
