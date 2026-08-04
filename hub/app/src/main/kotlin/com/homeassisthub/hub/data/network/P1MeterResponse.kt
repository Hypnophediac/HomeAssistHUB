package com.homeassisthub.hub.data.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Raw JSON payload returned by the Greenness ADA P1 meter's local HTTP API
 * (GET http://[IP]:8989/json). All values are returned as strings.
 */
@JsonClass(generateAdapter = true)
data class P1MeterResponse(
    // ── Meta ──
    @Json(name = "timestamp") val timestampStr: String? = null,
    @Json(name = "cosem_logical_device_name") val deviceName: String? = null,
    @Json(name = "meter_serial_number") val meterSerial: String? = null,
    @Json(name = "current_tariff") val currentTariffStr: String? = null,
    @Json(name = "os_version") val firmwareVersion: String? = null,

    // ── Cumulative energy (kWh) ──
    @Json(name = "active_import_energy_total") val importTotalStr: String = "0.000",
    @Json(name = "active_import_energy_tariff_1") val importT1Str: String = "0.000",
    @Json(name = "active_import_energy_tariff_2") val importT2Str: String = "0.000",
    @Json(name = "active_export_energy_total") val exportTotalStr: String = "0.000",
    @Json(name = "active_export_energy_tariff_1") val exportT1Str: String = "0.000",
    @Json(name = "active_export_energy_tariff_2") val exportT2Str: String = "0.000",

    // ── Instantaneous total power (kW → multiply by 1000 for W) ──
    @Json(name = "instantaneous_power_import") val powerImportKwStr: String = "0.000",
    @Json(name = "instantaneous_power_export") val powerExportKwStr: String = "0.000",

    // ── Per-phase voltage (V) ──
    @Json(name = "voltage_phase_l1") val l1VStr: String = "0.0",
    @Json(name = "voltage_phase_l2") val l2VStr: String = "0.0",
    @Json(name = "voltage_phase_l3") val l3VStr: String = "0.0",

    // ── Per-phase current (A) ──
    @Json(name = "current_phase_l1") val l1AStr: String = "0.0",
    @Json(name = "current_phase_l2") val l2AStr: String = "0.0",
    @Json(name = "current_phase_l3") val l3AStr: String = "0.0",

    // ── Per-phase power (kW → multiply by 1000 for W) ──
    @Json(name = "instantaneous_power_import_l1") val powerImportL1KwStr: String = "0.000",
    @Json(name = "instantaneous_power_import_l2") val powerImportL2KwStr: String = "0.000",
    @Json(name = "instantaneous_power_import_l3") val powerImportL3KwStr: String = "0.000",
    @Json(name = "instantaneous_power_export_l1") val powerExportL1KwStr: String = "0.000",
    @Json(name = "instantaneous_power_export_l2") val powerExportL2KwStr: String = "0.000",
    @Json(name = "instantaneous_power_export_l3") val powerExportL3KwStr: String = "0.000",

    // ── Power factor ──
    @Json(name = "power_factor") val powerFactorStr: String = "0.0",
    @Json(name = "power_factor_l1") val powerFactorL1Str: String = "0.0",
    @Json(name = "power_factor_l2") val powerFactorL2Str: String = "0.0",
    @Json(name = "power_factor_l3") val powerFactorL3Str: String = "0.0",

    // ── Frequency ──
    @Json(name = "frequency") val frequencyStr: String = "50.0",

    // ── Reactive energy (kWh) ──
    @Json(name = "reactive_import_energy") val reactiveImportStr: String = "0.000",
    @Json(name = "reactive_export_energy") val reactiveExportStr: String = "0.000",
    @Json(name = "reactive_energy_qi") val reactiveQiStr: String = "0.000",
    @Json(name = "reactive_energy_qii") val reactiveQiiStr: String = "0.000",
    @Json(name = "reactive_energy_qiii") val reactiveQiiiStr: String = "0.000",
    @Json(name = "reactive_energy_qiv") val reactiveQivStr: String = "0.000",

    // ── Instantaneous reactive power (kW) ──
    @Json(name = "instantaneous_reactive_power_qi") val reactivePowerQiStr: String = "0.000",
    @Json(name = "instantaneous_reactive_power_qii") val reactivePowerQiiStr: String = "0.000",
    @Json(name = "instantaneous_reactive_power_qiii") val reactivePowerQiiiStr: String = "0.000",
    @Json(name = "instantaneous_reactive_power_qiv") val reactivePowerQivStr: String = "0.000",

    // ── Device status ──
    @Json(name = "circuit_breaker_status") val circuitBreakerStatus: String? = null,
    @Json(name = "limiter_threshold") val limiterThresholdStr: String? = null,
    @Json(name = "current_limit_l1") val currentLimitL1Str: String = "0.0",
    @Json(name = "current_limit_l2") val currentLimitL2Str: String = "0.0",
    @Json(name = "current_limit_l3") val currentLimitL3Str: String = "0.0"
) {
    // ── Parsed double properties ──

    /** Net power in watts: import - export (positive = consuming, negative = exporting). */
    val powerW: Double
        get() {
            val imp = powerImportKwStr.toDoubleOrNull() ?: 0.0
            val exp = powerExportKwStr.toDoubleOrNull() ?: 0.0
            return (imp - exp) * 1000.0
        }

    val powerImportW: Double get() = (powerImportKwStr.toDoubleOrNull() ?: 0.0) * 1000.0
    val powerExportW: Double get() = (powerExportKwStr.toDoubleOrNull() ?: 0.0) * 1000.0

    val l1V: Double get() = l1VStr.toDoubleOrNull() ?: 0.0
    val l2V: Double get() = l2VStr.toDoubleOrNull() ?: 0.0
    val l3V: Double get() = l3VStr.toDoubleOrNull() ?: 0.0

    val l1A: Double get() = l1AStr.toDoubleOrNull() ?: 0.0
    val l2A: Double get() = l2AStr.toDoubleOrNull() ?: 0.0
    val l3A: Double get() = l3AStr.toDoubleOrNull() ?: 0.0

    // Per-phase power: trust meter values directly.
    // Do NOT fall back to V×I×PF using total direction — in 3-phase systems
    // with asymmetric loads, individual phases can import while others export
    // simultaneously. The total direction cannot determine per-phase direction.
    val powerImportL1W: Double get() = (powerImportL1KwStr.toDoubleOrNull() ?: 0.0) * 1000.0
    val powerImportL2W: Double get() = (powerImportL2KwStr.toDoubleOrNull() ?: 0.0) * 1000.0
    val powerImportL3W: Double get() = (powerImportL3KwStr.toDoubleOrNull() ?: 0.0) * 1000.0
    val powerExportL1W: Double get() = (powerExportL1KwStr.toDoubleOrNull() ?: 0.0) * 1000.0
    val powerExportL2W: Double get() = (powerExportL2KwStr.toDoubleOrNull() ?: 0.0) * 1000.0
    val powerExportL3W: Double get() = (powerExportL3KwStr.toDoubleOrNull() ?: 0.0) * 1000.0

    val powerFactor: Double get() = powerFactorStr.toDoubleOrNull() ?: 0.0
    val powerFactorL1: Double get() = powerFactorL1Str.toDoubleOrNull() ?: 0.0
    val powerFactorL2: Double get() = powerFactorL2Str.toDoubleOrNull() ?: 0.0
    val powerFactorL3: Double get() = powerFactorL3Str.toDoubleOrNull() ?: 0.0

    val frequencyHz: Double get() = frequencyStr.toDoubleOrNull() ?: 50.0

    val importT1Kwh: Double get() = importT1Str.toDoubleOrNull() ?: 0.0
    val importT2Kwh: Double get() = importT2Str.toDoubleOrNull() ?: 0.0
    val exportT1Kwh: Double get() = exportT1Str.toDoubleOrNull() ?: 0.0
    val exportT2Kwh: Double get() = exportT2Str.toDoubleOrNull() ?: 0.0
    val importTotalKwh: Double get() = importTotalStr.toDoubleOrNull() ?: 0.0
    val exportTotalKwh: Double get() = exportTotalStr.toDoubleOrNull() ?: 0.0

    val reactiveImportKwh: Double get() = reactiveImportStr.toDoubleOrNull() ?: 0.0
    val reactiveExportKwh: Double get() = reactiveExportStr.toDoubleOrNull() ?: 0.0
    val reactiveQiKwh: Double get() = reactiveQiStr.toDoubleOrNull() ?: 0.0
    val reactiveQiiKwh: Double get() = reactiveQiiStr.toDoubleOrNull() ?: 0.0
    val reactiveQiiiKwh: Double get() = reactiveQiiiStr.toDoubleOrNull() ?: 0.0
    val reactiveQivKwh: Double get() = reactiveQivStr.toDoubleOrNull() ?: 0.0

    val currentTariff: Int get() = currentTariffStr?.takeLast(1)?.toIntOrNull() ?: 1

    val voltageV: Double get() = if (l1V > 0) (l1V + l2V + l3V) / 3.0 else 230.0
}
