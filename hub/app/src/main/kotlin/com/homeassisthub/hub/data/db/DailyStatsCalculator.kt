package com.homeassisthub.hub.data.db

/**
 * Result of computing daily statistics from a list of [P1RawData] readings.
 * Used both by the nightly summary worker (persisted for completed days)
 * and for on-demand "today so far" live statistics (not persisted, since
 * the day is still in progress).
 */
data class DailyStats(
    val importT1Kwh: Double = 0.0,
    val importT2Kwh: Double = 0.0,
    val exportT1Kwh: Double = 0.0,
    val exportT2Kwh: Double = 0.0,
    val totalConsumedKwh: Double = 0.0,
    val totalExportedKwh: Double = 0.0,
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
    val avgL1V: Double = 0.0,
    val avgL2V: Double = 0.0,
    val avgL3V: Double = 0.0,
    val avgL1A: Double = 0.0,
    val avgL2A: Double = 0.0,
    val avgL3A: Double = 0.0,
    val avgPowerFactor: Double = 0.0,
    val avgFrequencyHz: Double = 50.0
)

object DailyStatsCalculator {

    /**
     * Computes all daily statistics from a chronologically-ordered list of
     * raw readings spanning (part of) a single day. Safe to call with a
     * partial day's worth of readings (e.g. "today so far").
     */
    fun compute(rawReadings: List<P1RawData>): DailyStats {
        if (rawReadings.isEmpty()) return DailyStats()
        val first = rawReadings.first()
        val last = rawReadings.last()

        val importT1Delta = (last.importT1Kwh - first.importT1Kwh).coerceAtLeast(0.0)
        val importT2Delta = (last.importT2Kwh - first.importT2Kwh).coerceAtLeast(0.0)
        val exportT1Delta = (last.exportT1Kwh - first.exportT1Kwh).coerceAtLeast(0.0)
        val exportT2Delta = (last.exportT2Kwh - first.exportT2Kwh).coerceAtLeast(0.0)
        val consumedDelta = importT1Delta + importT2Delta
        val exportedDelta = exportT1Delta + exportT2Delta

        val powers = rawReadings.map { it.currentPowerW }
        val imports = rawReadings.map { it.powerImportW }
        val exports = rawReadings.map { it.powerExportW }
        val minPower = powers.minOrNull() ?: 0.0
        val maxPower = powers.maxOrNull() ?: 0.0
        val avgPower = if (powers.isNotEmpty()) powers.average() else 0.0
        val maxImport = imports.maxOrNull() ?: 0.0
        val maxExport = exports.maxOrNull() ?: 0.0

        val hourlyConsumed = Array(24) { 0.0 }
        val hourlyExported = Array(24) { 0.0 }
        val cal = java.util.Calendar.getInstance()
        for (i in 1 until rawReadings.size) {
            val prev = rawReadings[i - 1]
            val curr = rawReadings[i]
            cal.timeInMillis = curr.timestamp
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val dtHours = (curr.timestamp - prev.timestamp) / 3_600_000.0
            if (dtHours > 0 && dtHours < 1.0) {
                hourlyConsumed[hour] += curr.powerImportW * dtHours / 1000.0
                hourlyExported[hour] += curr.powerExportW * dtHours / 1000.0
            }
        }
        var peakConsHour = -1
        var peakConsKwh = 0.0
        var peakExpHour = -1
        var peakExpKwh = 0.0
        for (h in 0..23) {
            if (hourlyConsumed[h] > peakConsKwh) { peakConsKwh = hourlyConsumed[h]; peakConsHour = h }
            if (hourlyExported[h] > peakExpKwh) { peakExpKwh = hourlyExported[h]; peakExpHour = h }
        }

        val selfConsumptionRatio = if (consumedDelta + exportedDelta > 0.0) {
            (consumedDelta / (consumedDelta + exportedDelta)).coerceIn(0.0, 1.0)
        } else 0.0
        val netEnergy = consumedDelta - exportedDelta

        val avgL1V = rawReadings.map { it.l1V }.filter { v -> v > 0.0 }.let { if (it.isNotEmpty()) it.average() else 0.0 }
        val avgL2V = rawReadings.map { it.l2V }.filter { v -> v > 0.0 }.let { if (it.isNotEmpty()) it.average() else 0.0 }
        val avgL3V = rawReadings.map { it.l3V }.filter { v -> v > 0.0 }.let { if (it.isNotEmpty()) it.average() else 0.0 }
        val avgL1A = rawReadings.map { it.l1A }.filter { a -> a > 0.0 }.let { if (it.isNotEmpty()) it.average() else 0.0 }
        val avgL2A = rawReadings.map { it.l2A }.filter { a -> a > 0.0 }.let { if (it.isNotEmpty()) it.average() else 0.0 }
        val avgL3A = rawReadings.map { it.l3A }.filter { a -> a > 0.0 }.let { if (it.isNotEmpty()) it.average() else 0.0 }
        val avgPF = rawReadings.map { it.powerFactor }.filter { pf -> pf > 0.0 }.let { if (it.isNotEmpty()) it.average() else 0.0 }
        val avgFreq = rawReadings.map { it.frequencyHz }.filter { f -> f > 0.0 }.let { if (it.isNotEmpty()) it.average() else 50.0 }

        return DailyStats(
            importT1Kwh = importT1Delta,
            importT2Kwh = importT2Delta,
            exportT1Kwh = exportT1Delta,
            exportT2Kwh = exportT2Delta,
            totalConsumedKwh = consumedDelta,
            totalExportedKwh = exportedDelta,
            minPowerW = minPower,
            maxPowerW = maxPower,
            avgPowerW = avgPower,
            maxImportW = maxImport,
            maxExportW = maxExport,
            peakConsumptionHour = peakConsHour,
            peakExportHour = peakExpHour,
            peakConsumptionKwh = peakConsKwh,
            peakExportKwh = peakExpKwh,
            selfConsumptionRatio = selfConsumptionRatio,
            netEnergyKwh = netEnergy,
            avgL1V = avgL1V,
            avgL2V = avgL2V,
            avgL3V = avgL3V,
            avgL1A = avgL1A,
            avgL2A = avgL2A,
            avgL3A = avgL3A,
            avgPowerFactor = avgPF,
            avgFrequencyHz = avgFreq
        )
    }
}
