package com.homeassisthub.client.network.model

/**
 * Computed live power data for a 3-phase system.
 *
 * On a 3-phase P1 meter, import and export CAN coexist: one phase may
 * import while another exports. The meter reports total import (sum of
 * importing phases) and total export (sum of exporting phases) separately.
 *
 * House consumption = inverterPowerW + importW - exportW
 * (i.e. vételezés + (napelem termelés - visszatáplálás))
 */
data class LivePowerData(
    val inverterPowerW: Double,
    val importW: Double,
    val exportW: Double,
    val houseW: Double,
    val hasInverter: Boolean,
    val timestamp: Long,
    val l1V: Double,
    val l2V: Double,
    val l3V: Double,
    val l1A: Double,
    val l2A: Double,
    val l3A: Double,
    val powerFactor: Double,
    val frequencyHz: Double,
    val currentTariff: Int,
    // Per-phase power (W)
    val powerImportL1W: Double = 0.0,
    val powerImportL2W: Double = 0.0,
    val powerImportL3W: Double = 0.0,
    val powerExportL1W: Double = 0.0,
    val powerExportL2W: Double = 0.0,
    val powerExportL3W: Double = 0.0
) {
    companion object {
        fun fromReading(r: P1ReadingDto): LivePowerData {
            // 3-phase: import and export are independent, do NOT enforce mutual exclusivity
            val importW = r.powerImportW
            val exportW = r.powerExportW
            val hasInverter = r.inverterPowerW > 0.0
            val houseW = if (hasInverter) {
                maxOf(0.0, r.inverterPowerW + importW - exportW)
            } else 0.0
            return LivePowerData(
                inverterPowerW = r.inverterPowerW,
                importW = importW,
                exportW = exportW,
                houseW = houseW,
                hasInverter = hasInverter,
                timestamp = r.timestamp,
                l1V = r.l1V,
                l2V = r.l2V,
                l3V = r.l3V,
                l1A = r.l1A,
                l2A = r.l2A,
                l3A = r.l3A,
                powerFactor = r.powerFactor,
                frequencyHz = r.frequencyHz,
                currentTariff = r.currentTariff,
                powerImportL1W = r.powerImportL1W,
                powerImportL2W = r.powerImportL2W,
                powerImportL3W = r.powerImportL3W,
                powerExportL1W = r.powerExportL1W,
                powerExportL2W = r.powerExportL2W,
                powerExportL3W = r.powerExportL3W
            )
        }
    }
}
