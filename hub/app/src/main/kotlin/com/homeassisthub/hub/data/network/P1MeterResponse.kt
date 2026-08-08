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

    // ── Per-phase balanced current (A) — accounts for actual power flow ──
    @Json(name = "current_phase_Bl1") val bl1AStr: String = "0.0",
    @Json(name = "current_phase_Bl2") val bl2AStr: String = "0.0",
    @Json(name = "current_phase_Bl3") val bl3AStr: String = "0.0",

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

    // Balanced current — use Bl values if available, fall back to l values
    val bl1A: Double get() {
        val bl = bl1AStr.toDoubleOrNull() ?: 0.0
        return if (bl > 0.0) bl else l1A
    }
    val bl2A: Double get() {
        val bl = bl2AStr.toDoubleOrNull() ?: 0.0
        return if (bl > 0.0) bl else l2A
    }
    val bl3A: Double get() {
        val bl = bl3AStr.toDoubleOrNull() ?: 0.0
        return if (bl > 0.0) bl else l3A
    }

    // Per-phase power: use meter values if reported (>0). Otherwise compute
    // from V×Bl×PF and determine direction from total import/export:
    // - If totalImport=0: ALL phases export (exportLx = V×Bl×PF, importLx = 0)
    // - If totalExport=0: ALL phases import (importLx = V×Bl×PF, exportLx = 0)
    // - If both>0 (mixed): brute-force 2^3 assignment for phases with current,
    //   then distribute remaining export/import equally among zero-current phases
    //   (the inverter is symmetrical, so export happens on all phases even if
    //   the meter can't measure the small current below its resolution).

    private fun computeAllPhasePowers(): Array<Pair<Double, Double>> {
        val totalImp = powerImportW
        val totalExp = powerExportW

        // Check if meter provides per-phase values directly
        val rawImp = doubleArrayOf(
            (powerImportL1KwStr.toDoubleOrNull() ?: 0.0) * 1000.0,
            (powerImportL2KwStr.toDoubleOrNull() ?: 0.0) * 1000.0,
            (powerImportL3KwStr.toDoubleOrNull() ?: 0.0) * 1000.0
        )
        val rawExp = doubleArrayOf(
            (powerExportL1KwStr.toDoubleOrNull() ?: 0.0) * 1000.0,
            (powerExportL2KwStr.toDoubleOrNull() ?: 0.0) * 1000.0,
            (powerExportL3KwStr.toDoubleOrNull() ?: 0.0) * 1000.0
        )
        if (rawImp.any { it > 0.0 } || rawExp.any { it > 0.0 }) {
            return arrayOf(
                rawImp[0] to rawExp[0],
                rawImp[1] to rawExp[1],
                rawImp[2] to rawExp[2]
            )
        }

        // Compute V×Bl×PF for each phase
        val voltages = doubleArrayOf(l1V, l2V, l3V)
        val currents = doubleArrayOf(bl1A, bl2A, bl3A)
        val pfs = doubleArrayOf(powerFactorL1, powerFactorL2, powerFactorL3)
        val phases = DoubleArray(3) { i -> voltages[i] * currents[i] * pfs[i] }

        if (totalImp <= 0.0 && totalExp <= 0.0) {
            return arrayOf(0.0 to 0.0, 0.0 to 0.0, 0.0 to 0.0)
        }

        // All phases export
        if (totalImp <= 0.0 && totalExp > 0.0) {
            val result = Array(3) { 0.0 to 0.0 }
            val hasPower = phases.any { it > 0.0 }
            if (hasPower) {
                val scale = totalExp / phases.filter { it > 0.0 }.sum()
                for (i in 0..2) {
                    result[i] = if (phases[i] > 0.0) 0.0 to (phases[i] * scale) else 0.0 to 0.0
                }
                // Distribute remaining export among zero-current phases
                val usedExport = result.sumOf { it.second }
                val remaining = totalExp - usedExport
                val zeroCount = phases.count { it <= 0.0 }
                if (remaining > 0.0 && zeroCount > 0) {
                    val perPhase = remaining / zeroCount
                    for (i in 0..2) {
                        if (phases[i] <= 0.0) result[i] = 0.0 to perPhase
                    }
                }
            } else {
                // All phases have 0 current — distribute equally
                val perPhase = totalExp / 3.0
                return arrayOf(0.0 to perPhase, 0.0 to perPhase, 0.0 to perPhase)
            }
            return result
        }

        // All phases import
        if (totalExp <= 0.0 && totalImp > 0.0) {
            val result = Array(3) { 0.0 to 0.0 }
            val hasPower = phases.any { it > 0.0 }
            if (hasPower) {
                val scale = totalImp / phases.filter { it > 0.0 }.sum()
                for (i in 0..2) {
                    result[i] = if (phases[i] > 0.0) (phases[i] * scale) to 0.0 else 0.0 to 0.0
                }
                // Distribute remaining import among zero-current phases
                val usedImport = result.sumOf { it.first }
                val remaining = totalImp - usedImport
                val zeroCount = phases.count { it <= 0.0 }
                if (remaining > 0.0 && zeroCount > 0) {
                    val perPhase = remaining / zeroCount
                    for (i in 0..2) {
                        if (phases[i] <= 0.0) result[i] = perPhase to 0.0
                    }
                }
            } else {
                val perPhase = totalImp / 3.0
                return arrayOf(perPhase to 0.0, perPhase to 0.0, perPhase to 0.0)
            }
            return result
        }

        // Mixed: some phases import, some export
        // Brute-force assignment for phases with measurable current
        if (phases.all { it <= 0.0 }) {
            // No measurable current on any phase — can't determine direction
            return arrayOf(0.0 to 0.0, 0.0 to 0.0, 0.0 to 0.0)
        }

        var bestErr = Double.MAX_VALUE
        var bestAssignment = intArrayOf(0, 0, 0)
        for (mask in 0..7) {
            var impSum = 0.0
            var expSum = 0.0
            for (pi in 0..2) {
                if (phases[pi] <= 0.0) continue
                if ((mask shr pi) and 1 == 0) impSum += phases[pi] else expSum += phases[pi]
            }
            val impScale = if (impSum > 0.0) totalImp / impSum else 0.0
            val expScale = if (expSum > 0.0) totalExp / expSum else 0.0
            val err = kotlin.math.abs(impSum * impScale - totalImp) + kotlin.math.abs(expSum * expScale - totalExp)
            val rawErr = kotlin.math.abs(impSum - totalImp) + kotlin.math.abs(expSum - totalExp)
            val combinedErr = rawErr + err * 0.01
            if (combinedErr < bestErr) {
                bestErr = combinedErr
                bestAssignment = intArrayOf((mask shr 0) and 1, (mask shr 1) and 1, (mask shr 2) and 1)
            }
        }

        // Compute scaled values for phases with current
        val impSumAssigned = phases.filterIndexed { i, _ -> bestAssignment[i] == 0 && phases[i] > 0.0 }.sum()
        val expSumAssigned = phases.filterIndexed { i, _ -> bestAssignment[i] == 1 && phases[i] > 0.0 }.sum()
        val impScale = if (impSumAssigned > 0.0) totalImp / impSumAssigned else 0.0
        val expScale = if (expSumAssigned > 0.0) totalExp / expSumAssigned else 0.0

        val result = Array(3) { 0.0 to 0.0 }
        for (i in 0..2) {
            if (phases[i] <= 0.0) continue
            if (bestAssignment[i] == 0) {
                result[i] = (phases[i] * impScale) to 0.0
            } else {
                result[i] = 0.0 to (phases[i] * expScale)
            }
        }

        // Distribute remaining export among zero-current phases
        val usedExport = result.sumOf { it.second }
        val remainingExport = totalExp - usedExport
        val zeroCount = phases.count { it <= 0.0 }
        if (remainingExport > 0.0 && zeroCount > 0) {
            val perPhase = remainingExport / zeroCount
            for (i in 0..2) {
                if (phases[i] <= 0.0) result[i] = 0.0 to perPhase
            }
        }

        // Distribute remaining import among zero-current phases (if no export was assigned)
        val usedImport = result.sumOf { it.first }
        val remainingImport = totalImp - usedImport
        if (remainingImport > 0.0 && zeroCount > 0) {
            val zeroPhasesWithoutExport = phases.indices.filter { phases[it] <= 0.0 && result[it].second <= 0.0 }
            if (zeroPhasesWithoutExport.isNotEmpty()) {
                val perPhase = remainingImport / zeroPhasesWithoutExport.size
                for (i in zeroPhasesWithoutExport) {
                    result[i] = perPhase to 0.0
                }
            }
        }

        return result
    }

    private fun phasePower(lx: Int): Pair<Double, Double> = computeAllPhasePowers()[lx - 1]

    val powerImportL1W: Double get() = phasePower(1).first
    val powerImportL2W: Double get() = phasePower(2).first
    val powerImportL3W: Double get() = phasePower(3).first
    val powerExportL1W: Double get() = phasePower(1).second
    val powerExportL2W: Double get() = phasePower(2).second
    val powerExportL3W: Double get() = phasePower(3).second

    val powerFactor: Double get() {
        val pf = powerFactorStr.toDoubleOrNull() ?: 0.0
        return if (pf > 0.0) pf else 1.0
    }
    val powerFactorL1: Double get() {
        val pf = powerFactorL1Str.toDoubleOrNull() ?: 0.0
        if (pf > 0.0) return pf
        val total = powerFactorStr.toDoubleOrNull() ?: 0.0
        return if (total > 0.0) total else 1.0
    }
    val powerFactorL2: Double get() {
        val pf = powerFactorL2Str.toDoubleOrNull() ?: 0.0
        if (pf > 0.0) return pf
        val total = powerFactorStr.toDoubleOrNull() ?: 0.0
        return if (total > 0.0) total else 1.0
    }
    val powerFactorL3: Double get() {
        val pf = powerFactorL3Str.toDoubleOrNull() ?: 0.0
        if (pf > 0.0) return pf
        val total = powerFactorStr.toDoubleOrNull() ?: 0.0
        return if (total > 0.0) total else 1.0
    }

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
