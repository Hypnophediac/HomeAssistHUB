package com.homeassisthub.client.network.model

import com.squareup.moshi.JsonClass

/** Mirrors the Hub's Ktor API DTOs (com.homeassisthub.hub.api.dto.*). */

@JsonClass(generateAdapter = true)
data class DiscoveredDeviceDto(
    val name: String,
    val ipAddress: String,
    val port: Int,
    val source: String
)

/** Request body for POST /api/v1/devices (includes credentials). */
@JsonClass(generateAdapter = true)
data class DeviceCredentialRequestDto(
    val deviceId: String,
    val deviceType: String,
    val ipAddress: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
)

/** Response representation; the Hub never returns passwords. */
@JsonClass(generateAdapter = true)
data class DeviceCredentialSummaryDto(
    val deviceId: String,
    val deviceType: String,
    val ipAddress: String,
    val port: Int,
    val username: String,
    val streamUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class P1ReadingDto(
    val timestamp: Long,
    val powerW: Double,
    val voltageV: Double,
    val powerImportW: Double = 0.0,
    val powerExportW: Double = 0.0,
    val l1V: Double = 0.0,
    val l2V: Double = 0.0,
    val l3V: Double = 0.0,
    val l1A: Double = 0.0,
    val l2A: Double = 0.0,
    val l3A: Double = 0.0,
    val currentTariff: Int = 1,
    val inverterPowerW: Double = 0.0,
    val realConsumptionW: Double = 0.0
)

@JsonClass(generateAdapter = true)
data class EnergyHourlyDto(
    val hour: Int,
    val consumedKwh: Double,
    val exportedKwh: Double
)

@JsonClass(generateAdapter = true)
data class EnergyDailyResponseDto(
    val hourly: List<EnergyHourlyDto>,
    val latestPowerW: Double,
    val latestL1V: Double,
    val latestL2V: Double,
    val latestL3V: Double,
    val totalConsumedKwh: Double,
    val totalExportedKwh: Double,
    val latestPowerImportW: Double = 0.0,
    val latestPowerExportW: Double = 0.0,
    val latestL1A: Double = 0.0,
    val latestL2A: Double = 0.0,
    val latestL3A: Double = 0.0,
    val latestPowerImportL1W: Double = 0.0,
    val latestPowerImportL2W: Double = 0.0,
    val latestPowerImportL3W: Double = 0.0,
    val latestPowerExportL1W: Double = 0.0,
    val latestPowerExportL2W: Double = 0.0,
    val latestPowerExportL3W: Double = 0.0,
    val latestPowerFactor: Double = 0.0,
    val latestFrequencyHz: Double = 50.0,
    val latestCurrentTariff: Int = 1,
    // ── Daily statistics ──
    val minPowerW: Double = 0.0,
    val maxPowerW: Double = 0.0,
    val avgPowerW: Double = 0.0,
    val maxImportW: Double = 0.0,
    val maxExportW: Double = 0.0,
    val peakConsumptionHour: Int = -1,
    val peakExportHour: Int = -1,
    val peakConsumptionKwh: Double = 0.0,
    val peakExportKwh: Double = 0.0,
    val selfConsumptionRatio: Double = 0.0,
    val netEnergyKwh: Double = 0.0,
    val importT1Kwh: Double = 0.0,
    val importT2Kwh: Double = 0.0,
    val exportT1Kwh: Double = 0.0,
    val exportT2Kwh: Double = 0.0,
    val avgL1V: Double = 0.0,
    val avgL2V: Double = 0.0,
    val avgL3V: Double = 0.0,
    val avgL1A: Double = 0.0,
    val avgL2A: Double = 0.0,
    val avgL3A: Double = 0.0,
    val avgPowerFactor: Double = 0.0,
    val avgFrequencyHz: Double = 50.0
)

@JsonClass(generateAdapter = true)
data class EnergyPeriodEntryDto(
    val label: String,
    val consumedKwh: Double,
    val exportedKwh: Double
)

@JsonClass(generateAdapter = true)
data class EnergyPeriodResponseDto(
    val entries: List<EnergyPeriodEntryDto>,
    val totalConsumedKwh: Double,
    val totalExportedKwh: Double
)

@JsonClass(generateAdapter = true)
data class DailySummaryDto(
    val inverterDailyKwh: Double = 0.0,
    val p1DailyImportKwh: Double = 0.0,
    val p1DailyExportKwh: Double = 0.0,
    val houseDailyKwh: Double = 0.0
)
