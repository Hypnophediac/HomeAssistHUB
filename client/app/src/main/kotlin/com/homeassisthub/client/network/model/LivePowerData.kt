package com.homeassisthub.client.network.model

/**
 * Computed live power data with enforced mutual exclusivity:
 * a Hungarian ad-vesz meter never imports and exports simultaneously.
 *
 * - If importing: importW > 0, exportW = 0
 * - If exporting: exportW > 0, importW = 0
 *
 * House consumption = inverterPowerW + importW - exportW
 * (equivalently: inverterPowerW - netGridW when exporting)
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
    val currentTariff: Int
) {
    companion object {
        fun fromReading(r: P1ReadingDto): LivePowerData {
            // Enforce mutual exclusivity: a P1 meter never imports and exports at once
            val importW = if (r.powerImportW > r.powerExportW) r.powerImportW else 0.0
            val exportW = if (r.powerExportW > r.powerImportW) r.powerExportW else 0.0
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
                powerFactor = 0.0,
                frequencyHz = 50.0,
                currentTariff = r.currentTariff
            )
        }
    }
}
