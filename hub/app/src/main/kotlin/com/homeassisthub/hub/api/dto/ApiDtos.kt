package com.homeassisthub.hub.api.dto

import com.homeassisthub.hub.data.db.P1DataEntity
import com.homeassisthub.hub.discovery.DiscoveredDevice
import com.homeassisthub.hub.security.DeviceCredential
import kotlinx.serialization.Serializable

@Serializable
data class DiscoveredDeviceDto(
    val name: String,
    val ipAddress: String,
    val port: Int,
    val source: String
)

fun DiscoveredDevice.toDto() = DiscoveredDeviceDto(
    name = name,
    ipAddress = ipAddress,
    port = port,
    source = source.name
)

/** Request/full representation, used only for the POST body (includes credentials). */
@Serializable
data class DeviceCredentialDto(
    val deviceId: String,
    val deviceType: String,
    val ipAddress: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
)

fun DeviceCredentialDto.toDomain() = DeviceCredential(
    deviceId = deviceId,
    deviceType = deviceType,
    ipAddress = ipAddress,
    port = port,
    username = username,
    password = password
)

/** Response representation for listing devices; deliberately omits the password. */
@Serializable
data class DeviceCredentialSummaryDto(
    val deviceId: String,
    val deviceType: String,
    val ipAddress: String,
    val port: Int,
    val username: String
)

fun DeviceCredential.toSummaryDto() = DeviceCredentialSummaryDto(
    deviceId = deviceId,
    deviceType = deviceType,
    ipAddress = ipAddress,
    port = port,
    username = username
)

@Serializable
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
    val powerImportL1W: Double = 0.0,
    val powerImportL2W: Double = 0.0,
    val powerImportL3W: Double = 0.0,
    val powerExportL1W: Double = 0.0,
    val powerExportL2W: Double = 0.0,
    val powerExportL3W: Double = 0.0,
    val powerFactor: Double = 0.0,
    val frequencyHz: Double = 50.0,
    val importT1Kwh: Double = 0.0,
    val importT2Kwh: Double = 0.0,
    val exportT1Kwh: Double = 0.0,
    val exportT2Kwh: Double = 0.0,
    val currentTariff: Int = 1
)

fun P1DataEntity.toDto() = P1ReadingDto(
    timestamp = timestamp,
    powerW = powerW,
    voltageV = voltageV,
    powerImportW = powerImportW,
    powerExportW = powerExportW,
    l1V = l1V,
    l2V = l2V,
    l3V = l3V,
    l1A = l1A,
    l2A = l2A,
    l3A = l3A,
    powerImportL1W = powerImportL1W,
    powerImportL2W = powerImportL2W,
    powerImportL3W = powerImportL3W,
    powerExportL1W = powerExportL1W,
    powerExportL2W = powerExportL2W,
    powerExportL3W = powerExportL3W,
    powerFactor = powerFactor,
    frequencyHz = frequencyHz,
    importT1Kwh = importT1Kwh,
    importT2Kwh = importT2Kwh,
    exportT1Kwh = exportT1Kwh,
    exportT2Kwh = exportT2Kwh,
    currentTariff = currentTariff
)

@Serializable
data class ApiErrorDto(val error: String)

@Serializable
data class ApiHealthDto(val status: String, val uptimeMs: Long)

@Serializable
data class EnergyHourlyDto(
    val hour: Int,
    val consumedKwh: Double,
    val exportedKwh: Double
)

@Serializable
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

@Serializable
data class EnergyPeriodEntryDto(
    val label: String,
    val consumedKwh: Double,
    val exportedKwh: Double
)

@Serializable
data class EnergyPeriodResponseDto(
    val entries: List<EnergyPeriodEntryDto>,
    val totalConsumedKwh: Double,
    val totalExportedKwh: Double
)
